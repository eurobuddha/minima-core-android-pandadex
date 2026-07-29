package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-safe MEXC MINIMA/USDT price oracle — the market maker's reference mid.
 *
 * Ported from PandaPools' MarketPrice (itself a trim of minimaSwap's PriceOracle), plus the
 * staleness ladder a MAKER needs: an out-of-date price is how a market maker gets run over, so
 * as the last good read ages we quote progressively WIDER, and past a hard limit the ladder
 * withdraws entirely rather than standing on a number we no longer believe.
 *
 * This is the one external dependency in the app. It is an INPUT, not the venue: orders,
 * matching and settlement are entirely on-chain, and if this feed disappears the ladder simply
 * withdraws and manual trading is unaffected.
 *
 * Touches NO funds and NO node state. One process-wide cached snapshot; fetches run on a single
 * daemon thread; readers always see the last good value.
 */
public final class MarketPrice {
    private MarketPrice() {}

    private static final String DEPTH_URL = "https://api.mexc.com/api/v3/depth?symbol=MINIMAUSDT&limit=20";
    private static final String BOOK_URL  = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";

    private static final long   FRESH_MS        = 5 * 60_000L; // mid counts as usable up to this old
    private static final long   FETCH_GAP_MS    = 30_000L;     // min gap between fetch attempts (rate limit)
    private static final double DEPTH_MIN_USDT  = 25;          // effective bid/ask = level where cumulative notional ≥ this
    private static final double MAX_SPREAD      = 0.20;        // reject a book wider than this (too thin to quote)
    private static final int    MAX_BODY        = 16 * 1024;   // cap the read (~16 KB)

    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandadex-price"); t.setDaemon(true); return t;
    });

    private static double  mid = 0;         // USDT per 1 MINIMA (last GOOD read)
    private static long    fetchedAt = 0;   // when that read landed (ms since epoch)
    private static long    lastTryMs = 0;   // fetch pacing baseline
    private static boolean fetching = false;
    private static String  lastError = null;

    /** Unit tests only — plants a snapshot so engine cycles can run without the network.
     *  Also pins the fetch pacer so refreshAsync() stays inert during the test. */
    static void testSnapshot(double m, long fetchedAtMs) {
        synchronized (LOCK) {
            mid = m;
            fetchedAt = fetchedAtMs;
            lastTryMs = System.currentTimeMillis();
            fetching = false;
        }
    }

    // ---- cached snapshot accessors ----

    /** Last good mid (USDT per 1 MINIMA), or 0 if never fetched. */
    public static double mid() { synchronized (LOCK) { return mid; } }

    /** Age of the last good read in ms; {@link Long#MAX_VALUE} if never fetched or the clock jumped backward
     *  (a backward NTP/manual jump must read as STALE, never fresh). */
    public static long ageMs() {
        synchronized (LOCK) {
            if (fetchedAt <= 0) return Long.MAX_VALUE;
            long a = System.currentTimeMillis() - fetchedAt;
            return a < 0 ? Long.MAX_VALUE : a;
        }
    }

    /** True when we hold a usable, recent price. */
    public static boolean fresh() { return mid() > 0 && ageMs() <= FRESH_MS; }

    // ---- staleness ladder (maker safety) ----

    /** Past this age the price is not trustworthy at any spread — the ladder withdraws. */
    public static final long WITHDRAW_MS = 20 * 60_000L;
    /** Age at which widening reaches its maximum. */
    private static final long WIDEN_FULL_MS = 15 * 60_000L;
    /** How far the spread is stretched at full widening. */
    private static final double MAX_WIDEN = 6.0;

    /**
     * Spread multiplier for the current price age: 1.0 while fresh, ramping to MAX_WIDEN as it
     * goes stale. Quoting wider on an ageing price means that if the real market has moved, a
     * taker has to pay more to pick us off.
     */
    public static double widenFactor() {
        long age = ageMs();
        if (age == Long.MAX_VALUE) return MAX_WIDEN;
        if (age <= FRESH_MS) return 1.0;
        if (age >= WIDEN_FULL_MS) return MAX_WIDEN;
        double t = (double) (age - FRESH_MS) / (double) (WIDEN_FULL_MS - FRESH_MS);
        return 1.0 + t * (MAX_WIDEN - 1.0);
    }

    /** True when the feed is too old to quote on at all — the ladder must come off the book. */
    public static boolean mustWithdraw() {
        return mid() <= 0 || ageMs() >= WITHDRAW_MS;
    }

    /** Human-readable feed state for the maker screen. */
    public static String stateLabel() {
        if (mid() <= 0) return "no price feed";
        long age = ageMs();
        if (age == Long.MAX_VALUE) return "no price feed";
        if (age >= WITHDRAW_MS) return "feed stale — withdrawn";
        String ago = age < 60_000 ? (age / 1000) + "s ago" : (age / 60_000) + "m ago";
        if (age > FRESH_MS) return "feed " + ago + " — quoting wider";
        return "feed " + ago;
    }

    public static String lastError() { synchronized (LOCK) { return lastError; } }

    // ---- fetching ----

    /** Rate-limited background refresh — safe to call from any thread / every block / every view open.
     *  Never runs the network on the caller's thread. */
    public static void refreshAsync() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (fetching || now - lastTryMs < FETCH_GAP_MS) return;
            fetching = true; lastTryMs = now;
        }
        EXEC.execute(() -> { try { fetchOnce(); } finally { synchronized (LOCK) { fetching = false; } } });
    }

    private static void fetchOnce() {
        try {
            double b, a;
            try {
                // PRIMARY: order-book depth. On a thin market the top-of-book is dust and trivially movable,
                // so the effective bid/ask is the level where CUMULATIVE notional reaches DEPTH_MIN_USDT.
                JSONObject depth = httpGet(DEPTH_URL);
                b = effectiveLevel(depth.optJSONArray("bids"));
                a = effectiveLevel(depth.optJSONArray("asks"));
            } catch (IOException depthDown) {
                // FALLBACK (depth endpoint unreachable): top-of-book only. Weaker but still spread-checked.
                JSONObject book = httpGet(BOOK_URL);
                b = book.optDouble("bidPrice", 0);
                a = book.optDouble("askPrice", 0);
            }
            if (!(b > 0) || !(a > 0) || b > a) throw new IOException("thin/empty book");
            if ((a - b) / a >= MAX_SPREAD) throw new IOException("spread too wide — book too thin to quote");
            double m = (a + b) / 2;
            if (!(m > 0) || Double.isInfinite(m)) throw new IOException("bad price");
            synchronized (LOCK) {
                mid = m; fetchedAt = System.currentTimeMillis(); lastError = null;
            }
        } catch (Exception e) {
            // Keep the last good snapshot; just record why this attempt failed.
            synchronized (LOCK) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
    }

    /** Price at which cumulative notional (price × qty) reaches DEPTH_MIN_USDT, or 0 if the side can't absorb
     *  it (too thin to quote). Rows are ["price","qty"] pairs, best level first. */
    private static double effectiveLevel(JSONArray side) {
        try {
            if (side == null) return 0;
            double cum = 0;
            for (int i = 0; i < side.length(); i++) {
                JSONArray row = side.getJSONArray(i);
                double px = Double.parseDouble(row.getString(0));
                double qty = Double.parseDouble(row.getString(1));
                if (!(px > 0) || !(qty > 0)) return 0;
                cum += px * qty;
                if (cum >= DEPTH_MIN_USDT) return px;
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    private static JSONObject httpGet(String u) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); total += n; if (total > MAX_BODY) break; }
            }
            String body = bos.toString("UTF-8");
            if (code < 200 || code >= 300)
                throw new IOException("HTTP " + code + " " + body.substring(0, Math.min(80, body.length())));
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("bad JSON: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

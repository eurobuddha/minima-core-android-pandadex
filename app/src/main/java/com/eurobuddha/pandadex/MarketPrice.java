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
 * The external price is an INPUT to automated fund decisions: orders,
 * matching and settlement are entirely on-chain, and if this feed disappears the ladder simply
 * withdraws and manual trading is unaffected.
 *
 * Fetches make no node calls, but their result controls maker prices. One process-wide snapshot; fetches run on a single
 * daemon thread; readers always see the last good value.
 */
public final class MarketPrice {
    private MarketPrice() {}

    private static final String DEPTH_URL = "https://api.mexc.com/api/v3/depth?symbol=MINIMAUSDT&limit=20";

    private static final long   FRESH_MS        = 5 * 60_000L; // mid counts as usable up to this old
    private static final long   FETCH_GAP_MS    = 30_000L;     // min gap between fetch attempts (rate limit)
    private static final double DEPTH_MIN_USDT  = 25;          // effective bid/ask = level where cumulative notional ≥ this
    private static final double MAX_SPREAD      = 0.20;        // reject a book wider than this (too thin to quote)
    static final long MAX_REQUEST_MS = 30_000L;
    private static java.util.function.LongSupplier clock = android.os.SystemClock::elapsedRealtime;
    private static final int    MAX_BODY        = 16 * 1024;   // cap the read (~16 KB)

    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandadex-price"); t.setDaemon(true); return t;
    });

    private static double suspect = 0;
    private static long suspectAt = -1;
    private static boolean quoteQuarantined;
    private static double  mid = 0;         // USDT per 1 MINIMA (last GOOD read)
    private static long    fetchedAt = -1;  // elapsed time when its request STARTED
    private static long    lastTryMs = -1;   // fetch pacing baseline
    private static boolean fetching = false;
    private static String  lastError = null;

    /** Unit tests only — plants a snapshot so engine cycles can run without the network.
     *  Also pins the fetch pacer so refreshAsync() stays inert during the test. */
    static void testSnapshot(double m, long fetchedAtMs) {
        testSnapshot(m,fetchedAtMs,System::currentTimeMillis);
    }
    static void testSnapshot(double m,long at,java.util.function.LongSupplier testClock) {
        synchronized(LOCK){
            clock=testClock;mid=m;suspect=0;suspectAt=-1;quoteQuarantined=false;
            fetchedAt=at;lastTryMs=clock.getAsLong();fetching=false;lastError=null;
        }
    }

    // ---- cached snapshot accessors ----

    /** Last good mid (USDT per 1 MINIMA), or 0 if never fetched. */
    public static double mid() { synchronized (LOCK) { return mid; } }

    /** Age from request start using elapsed time, including sleep. Wall-clock changes cannot
     *  rejuvenate a quote. A missing/invalid monotonic stamp fails closed. */
    public static long ageMs() {
        synchronized (LOCK) {
            if (fetchedAt < 0) return Long.MAX_VALUE;
            long a = clock.getAsLong() - fetchedAt;
            return a < 0 ? Long.MAX_VALUE : a;
        }
    }

    /** True when we hold a usable, recent price. */
    public static boolean fresh() { synchronized(LOCK){return !quoteQuarantined && mid > 0 && ageMs() <= FRESH_MS;} }

    // ---- staleness ladder (maker safety) ----

    /** Past this age the price is not trustworthy at any spread — the ladder withdraws. */
    public static final long WITHDRAW_MS = 20 * 60_000L;
    // The background host normally wakes every 15 minutes. Corroboration may use a
    // previous sample only while that sample is within the existing maximum quote age.
    private static final long MAX_CORROBORATION_MS = WITHDRAW_MS;
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
        synchronized(LOCK){return quoteQuarantined || mid <= 0 || ageMs() >= WITHDRAW_MS;}
    }

    /** Human-readable feed state for the maker screen. */
    public static String stateLabel() {
        synchronized(LOCK){if(quoteQuarantined)return "large price move unconfirmed — quoting paused";}
        if (mid() <= 0) return "no price feed";
        long age = ageMs();
        if (age == Long.MAX_VALUE) return "no price feed";
        if (age >= WITHDRAW_MS) return "feed stale — withdrawal needed";
        String ago = age < 60_000 ? (age / 1000) + "s ago" : (age / 60_000) + "m ago";
        if (age > FRESH_MS) return "feed " + ago + " — quoting wider";
        return "feed " + ago;
    }

    /** One coherent reference for an asynchronous maker action. */
    static final class Quote {
        final double mid,widen;
        final boolean unavailable;
        Quote(double mid,double widen,boolean unavailable){this.mid=mid;this.widen=widen;this.unavailable=unavailable;}
    }
    static Quote quote() {
        synchronized(LOCK){return new Quote(mid,widenFactor(),mustWithdraw());}
    }

    public static String lastError() { synchronized (LOCK) { return lastError; } }

    // ---- fetching ----

    /** Rate-limited background refresh — safe to call from any thread / every block / every view open.
     *  Never runs the network on the caller's thread. */
    public static void refreshAsync() {
        synchronized (LOCK) {
            long now = clock.getAsLong();
            if (fetching || (lastTryMs >= 0 && now >= lastTryMs && now - lastTryMs < FETCH_GAP_MS)) return;
            fetching = true; lastTryMs = now;
        }
        try {EXEC.execute(() -> { try { fetchOnce(); } finally { synchronized (LOCK) { fetching = false; } } });}
        catch(java.util.concurrent.RejectedExecutionException unavailable){synchronized(LOCK){fetching=false;lastError="Price worker unavailable";}}
    }

    private static void fetchOnce() {
        final long started=clock.getAsLong();
        try {
            // Maker prices require both depth sides. An unavailable depth endpoint must not
            // silently downgrade automated trading to movable top-of-book dust.
            JSONObject depth = httpGet((HttpURLConnection)new URL(DEPTH_URL).openConnection(),clock,started);
            double b = effectiveLevel(depth.optJSONArray("bids"), true);
            double a = effectiveLevel(depth.optJSONArray("asks"), false);
            if (!(b > 0) || !(a > 0) || b > a) throw new IOException("thin/empty book");
            if ((a - b) / a >= MAX_SPREAD) throw new IOException("spread too wide — book too thin to quote");
            double m = (a + b) / 2;
            if (!(m > 0) || Double.isInfinite(m)) throw new IOException("bad price");
            acceptMid(m,started);
        } catch (Exception e) {
            // Keep the last good snapshot; just record why this attempt failed.
            synchronized (LOCK) {
                suspect = 0; // corroboration must be consecutive successful depth readings
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
    }

    /** The minimaSwap two-read jump guard, adapted to the existing process-local snapshot. */
    static boolean acceptMid(double candidate) {return acceptMid(candidate,clock.getAsLong());}
    static boolean acceptMid(double candidate,long requestStarted) {
        synchronized (LOCK) {
            long now=clock.getAsLong(),elapsed=now-requestStarted;
            if(requestStarted<0||elapsed<0||elapsed>=MAX_REQUEST_MS){
                suspect=0;lastError="Price response exceeded its total deadline";return false;
            }
            if (!(candidate > 0) || !Double.isFinite(candidate)) { suspect = 0; return false; }
            if (mid > 0 && Math.abs(candidate - mid) / mid > 0.5) {
                if (!(suspect > 0 && now >= suspectAt && now - suspectAt < MAX_CORROBORATION_MS
                        && Math.abs(candidate - suspect) / suspect < 0.10)) {
                    quoteQuarantined = true;
                    suspect = candidate; suspectAt = requestStarted; lastError = "Large price move — waiting for a second depth reading";
                    return false;
                }
            }
            suspect = 0; quoteQuarantined = false; mid = candidate; fetchedAt = requestStarted; lastError = null;
            return true;
        }
    }

    /** Price at which cumulative notional (price × qty) reaches DEPTH_MIN_USDT, or 0 if the side can't absorb
     *  it (too thin to quote). Rows are ["price","qty"] pairs, best level first. */
    static double effectiveLevel(JSONArray side, boolean bids) {
        try {
            if (side == null) return 0;
            double cum = 0, previous = bids ? Double.MAX_VALUE : 0;
            for (int i = 0; i < side.length(); i++) {
                JSONArray row = side.getJSONArray(i);
                double px = Double.parseDouble(row.getString(0));
                double qty = Double.parseDouble(row.getString(1));
                if (!(px > 0) || !(qty > 0) || !Double.isFinite(px) || !Double.isFinite(qty)
                        || (bids ? px > previous : px < previous) || !Double.isFinite(px * qty)) return 0;
                previous = px;
                cum += px * qty;
                if (!Double.isFinite(cum)) return 0;
                if (cum >= DEPTH_MIN_USDT) return px;
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    /** One bounded body/deadline, adapted from the existing export elapsed-budget guard.
     * A native/DNS read can ignore timeouts; a late result is still never accepted as fresh. */
    static JSONObject httpGet(HttpURLConnection c,java.util.function.LongSupplier timer,long started) throws IOException {
        try {
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(Math.min(10_000,remaining(timer,started)));
            c.setReadTimeout(Math.min(10_000,remaining(timer,started)));
            c.setRequestProperty("Accept","application/json");
            int code=c.getResponseCode();remaining(timer,started);
            if(code<200||code>=300)throw new IOException("Price HTTP "+code);
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                if(in==null)throw new IOException("Empty price response");
                byte[] buffer=new byte[4096];int total=0;
                while(true){
                    c.setReadTimeout(Math.min(10_000,remaining(timer,started)));
                    int n=in.read(buffer);remaining(timer,started);
                    if(n<0)break;
                    total+=n;if(total>MAX_BODY)throw new IOException("Price response exceeds size limit");
                    bos.write(buffer,0,n);
                }
            }
            remaining(timer,started);
            JSONObject value=new JSONObject(bos.toString("UTF-8"));
            remaining(timer,started);return value;
        }catch(org.json.JSONException invalid){throw new IOException("Invalid price JSON",invalid);}
        finally{c.disconnect();}
    }
    private static int remaining(java.util.function.LongSupplier timer,long started) throws IOException {
        long elapsed=timer.getAsLong()-started;
        if(started<0||elapsed<0||elapsed>=MAX_REQUEST_MS)throw new IOException("Price response exceeded its total deadline");
        return (int)(MAX_REQUEST_MS-elapsed);
    }
}

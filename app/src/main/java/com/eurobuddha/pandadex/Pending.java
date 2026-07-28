package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Optimistic order lifecycle: a placed/cancelled/edited order shows INSTANTLY as a pending
 * row and disappears the moment the chain shows its result, handing the display over to the
 * real order. Persisted so a restart keeps the story.
 *
 * Kinds: PLACE (done when orderId APPEARS on the book), CANCEL (its coin GONE), EDIT (old coin
 * gone AND the orderId back). Funds are safe regardless — the chain is the truth and this is
 * presentation — so nothing here ever implies money is at risk.
 */
public final class Pending {

    public static final String PLACE = "PLACE";
    public static final String CANCEL = "CANCEL";
    public static final String EDIT = "EDIT";

    /** After this long without the chain showing the result, say so plainly. */
    private static final long SLOW_MS = 3 * 60_000;
    /** Stop claiming anything is in progress after this long — the chain is the truth and the
     *  book itself will show the real state. */
    private static final long GIVEUP_MS = 20 * 60_000;
    private static final String PREFS = "pandadex_pending";
    private static final String KEY = "rows";

    public static final class Row {
        public String kind;
        public String orderId;
        public String coinid;        // CANCEL/EDIT target coin
        public boolean buy;
        public BigDecimal minima;
        public BigDecimal price;
        public long submitMs;
        public long submitBlock;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("orderId", orderId);
            o.put("coinid", coinid == null ? "" : coinid);
            o.put("buy", buy);
            o.put("minima", minima.toPlainString());
            o.put("price", price.toPlainString());
            o.put("submitMs", submitMs);
            o.put("submitBlock", submitBlock);
            return o;
        }

        static Row from(JSONObject o) {
            Row r = new Row();
            r.kind = o.optString("kind");
            r.orderId = o.optString("orderId");
            r.coinid = o.optString("coinid");
            r.buy = o.optBoolean("buy");
            r.minima = Util.dec(o.optString("minima", "0"));
            r.price = Util.dec(o.optString("price", "0"));
            r.submitMs = o.optLong("submitMs");
            r.submitBlock = o.optLong("submitBlock");
            return r;
        }

        /**
         * What the user sees while the outcome is unknown. Blocks are ~50s, so the honest
         * message is simply that it's on its way — and if it takes unusually long, say THAT
         * rather than implying the funds are in doubt.
         */
        public String status(long chainBlock) {
            long secs = Math.max(0, (System.currentTimeMillis() - submitMs) / 1000);
            String verb = PLACE.equals(kind) ? "Sending"
                        : CANCEL.equals(kind) ? "Cancelling" : "Updating price";
            // An open-ended "Sending…" tells the user nothing about whether to keep waiting.
            // Blocks land roughly every 50s, so show the clock AND what we're waiting for —
            // and once it is clearly overdue, say that plainly instead of spinning forever.
            String clock = secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
            if (System.currentTimeMillis() - submitMs > SLOW_MS) {
                return verb + " — " + clock + ", longer than usual. It will appear when a block "
                        + "includes it; your funds are safe either way.";
            }
            return verb + "… waiting for the next block (~50s) · " + clock;
        }
    }

    private final SharedPreferences prefs;
    private final List<Row> rows = new ArrayList<>();

    public Pending(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) rows.add(Row.from(a.getJSONObject(i)));
        } catch (Exception ignore) {}
    }

    public List<Row> rows() { return rows; }

    public void add(Row r) {
        rows.add(r);
        save();
    }

    /** Told when a row completes, so the host can announce it. */
    public interface Listener {
        /** The order is on the book — fillable and cancellable RIGHT NOW. */
        void onLive(Row r);
        /** A cancel or reprice took effect. */
        void onSettled(Row r);
    }

    /**
     * Resolve rows against the live book. A row exists ONLY while its outcome is unknown; the
     * instant the chain shows the result the row is removed and the real order takes over the
     * display, so an order is never shown twice.
     *
     * There is deliberately no block-depth countdown. An order is live the moment its id
     * appears on the book — it can be filled and cancelled from that instant — so counting
     * further blocks would report doubt that doesn't exist while the order was already
     * tradeable. (The old "Confirming n/3" also stalled on 1/3 whenever the block poll paused,
     * e.g. while an input had focus, which made a perfectly live order look broken.)
     */
    public boolean resolve(java.util.Map<String, Order5> book, long chainBlock, Listener l) {
        boolean changed = false;
        java.util.Set<String> liveOrderIds = new java.util.HashSet<>();
        for (Order5 o : book.values()) liveOrderIds.add(o.orderId);
        java.util.Iterator<Row> it = rows.iterator();
        while (it.hasNext()) {
            Row r = it.next();
            boolean done = false;
            if (PLACE.equals(r.kind) && liveOrderIds.contains(r.orderId)) {
                if (l != null) l.onLive(r);
                done = true;
            } else if (CANCEL.equals(r.kind) && !book.containsKey(r.coinid)) {
                if (l != null) l.onSettled(r);
                done = true;
            } else if (EDIT.equals(r.kind) && !book.containsKey(r.coinid)
                    && liveOrderIds.contains(r.orderId)) {
                if (l != null) l.onSettled(r);
                done = true;
            }
            if (done || System.currentTimeMillis() - r.submitMs > GIVEUP_MS) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
        return changed;
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Row r : rows) a.put(r.json());
        } catch (Exception ignore) {}
        prefs.edit().putString(KEY, a.toString()).apply();
    }
}

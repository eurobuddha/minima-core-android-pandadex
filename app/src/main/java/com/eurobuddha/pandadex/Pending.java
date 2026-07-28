package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Optimistic order lifecycle (the snappiness core): a placed/cancelled/edited order shows
 * INSTANTLY as a pending row and resolves against the live book — never a blank wait for a
 * block (ActivityLog pattern: "Confirming n/3"). Persisted so restarts keep the story.
 *
 * Kinds: PLACE (resolves when orderId APPEARS), CANCEL (orderId's coin GONE), EDIT (orderId
 * reappears with the new want). Rows expire to FAILED display after TIMEOUT_MS without
 * resolution (funds are safe either way — the chain is the truth; this is presentation).
 */
public final class Pending {

    public static final String PLACE = "PLACE";
    public static final String CANCEL = "CANCEL";
    public static final String EDIT = "EDIT";

    public static final int CONFIRM_BLOCKS = 3;
    private static final long TIMEOUT_MS = 10 * 60_000;
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
        public long seenBlock;       // block the resolution was first observed (0 = unresolved)

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
            o.put("seenBlock", seenBlock);
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
            r.seenBlock = o.optLong("seenBlock");
            return r;
        }

        public boolean confirmed(long chainBlock) {
            return seenBlock > 0 && chainBlock - seenBlock >= CONFIRM_BLOCKS;
        }

        public String status(long chainBlock) {
            if (seenBlock == 0) {
                if (System.currentTimeMillis() - submitMs > TIMEOUT_MS) return "NOT CONFIRMED — check funds";
                return kind.equals(PLACE) ? "PLACING…" : kind.equals(CANCEL) ? "CANCELLING…" : "EDITING…";
            }
            long n = Math.min(CONFIRM_BLOCKS, Math.max(1, chainBlock - seenBlock + 1));
            return "Confirming " + n + "/" + CONFIRM_BLOCKS;
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

    /** Resolve rows against the live book; drop rows once confirmed. Returns true if changed. */
    public boolean resolve(java.util.Map<String, Order5> book, long chainBlock) {
        boolean changed = false;
        java.util.Set<String> liveOrderIds = new java.util.HashSet<>();
        for (Order5 o : book.values()) liveOrderIds.add(o.orderId);
        java.util.Iterator<Row> it = rows.iterator();
        while (it.hasNext()) {
            Row r = it.next();
            boolean resolvedNow = false;
            if (r.seenBlock == 0) {
                if (PLACE.equals(r.kind) && liveOrderIds.contains(r.orderId)) resolvedNow = true;
                if (CANCEL.equals(r.kind) && !book.containsKey(r.coinid)) resolvedNow = true;
                if (EDIT.equals(r.kind) && !book.containsKey(r.coinid)
                        && liveOrderIds.contains(r.orderId)) resolvedNow = true;
                if (resolvedNow) {
                    r.seenBlock = chainBlock;
                    changed = true;
                }
            }
            if (r.confirmed(chainBlock)
                    || System.currentTimeMillis() - r.submitMs > 2 * TIMEOUT_MS) {
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

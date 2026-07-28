package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The market maker's settings and its memory of which on-chain order belongs to which rung.
 *
 * The settings mirror AtomiX's order editor: an explicit per-rung ladder (price + MINIMA size
 * per rung, each side independent) plus the peg's seed parameters — step %, level count and
 * independent ask/bid sizes — which regenerate the rungs around the live MEXC mid while pegged.
 *
 * The slot→order mapping is the important part: without it a restart would not recognise the
 * ladder already on the book and would post a second one on top of it. It is keyed by the
 * order id we generated (stable across re-locks, since a re-lock preserves state port 4).
 */
public final class MakerConfig {

    private static final String PREFS = "pandadex_maker";
    private static final String K_LEVELS = "levels";     // legacy 0.2.x offset ladder (migration only)
    private static final String K_ASKS = "asks";
    private static final String K_BIDS = "bids";
    private static final String K_PEGGED = "pegged";
    private static final String K_STEP = "step";
    private static final String K_NLEVELS = "nlevels";
    private static final String K_ASKSIZE = "asksize";
    private static final String K_BIDSIZE = "bidsize";
    private static final String K_MID = "manualmid";
    private static final String K_SKEW = "skew";
    private static final String K_REPRICE = "reprice";
    private static final String K_ARMED = "armed";
    private static final String K_SLOTS = "slots";
    private static final String K_LASTMID = "lastmid";
    private static final String K_SIZES = "slotsizes";

    private final SharedPreferences prefs;

    /** Explicit rungs, best first (A1/B1). Authoritative when NOT pegged; while pegged they
     *  hold the last generated ladder so the fields show what is actually quoted. */
    public final List<MakerLadder.Level> asks = new ArrayList<>();
    public final List<MakerLadder.Level> bids = new ArrayList<>();

    /** Peg (auto market-make) seed parameters — AtomiX's quick-generate fields. */
    public boolean pegged = true;
    public BigDecimal stepPct = new BigDecimal("0.20");
    public int levelCount = 3;
    public BigDecimal askSize = BigDecimal.ZERO;
    public BigDecimal bidSize = BigDecimal.ZERO;
    /** The mid typed into the auto-fill row while unpegged — a seed for generating rungs,
     *  remembered so reopening the tab shows what the ladder was built from. */
    public BigDecimal manualMid = BigDecimal.ZERO;

    public BigDecimal skewPct = BigDecimal.ZERO;
    public BigDecimal repricePct = new BigDecimal("0.25");
    public boolean armed = false;
    /** slot id ("B1"/"A2"…) → the order id we placed for it. */
    public final Map<String, String> slotOrderIds = new HashMap<>();
    /** slot id → the MINIMA size we POSTED there. Needed to recognise a partial fill: an
     *  order's own fields can't tell you it shrank, only what it holds now. */
    public final Map<String, String> slotSizes = new HashMap<>();
    public BigDecimal lastActedMid = null;

    public MakerConfig(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** In-memory only, for unit tests — {@link #save()} is a no-op without prefs. */
    MakerConfig() {
        prefs = null;
    }

    /** Record what we posted for a slot: both the order id and the size, together. */
    public void rememberSlot(String slotId, String orderId, BigDecimal sizeMinima) {
        slotOrderIds.put(slotId, orderId);
        slotSizes.put(slotId, sizeMinima.toPlainString());
        save();
    }

    /** Drop a slot by the order id we placed for it, keeping both maps in step. */
    public void forgetSlotByOrderId(String orderId) {
        if (orderId == null) return;
        String slot = null;
        for (Map.Entry<String, String> e : slotOrderIds.entrySet()) {
            if (orderId.equals(e.getValue())) { slot = e.getKey(); break; }
        }
        if (slot == null) return;
        slotOrderIds.remove(slot);
        slotSizes.remove(slot);
        save();
    }

    public void clearSlots() {
        slotOrderIds.clear();
        slotSizes.clear();
        save();
    }

    /** The size we posted for this slot, or null if we never did. */
    public BigDecimal postedSizeFor(String slotId) {
        String v = slotSizes.get(slotId);
        if (v == null || v.isEmpty()) return null;
        BigDecimal b = Util.dec(v);
        return b.signum() > 0 ? b : null;
    }

    private void load() {
        asks.clear();
        bids.clear();
        readLevels(prefs.getString(K_ASKS, "[]"), asks);
        readLevels(prefs.getString(K_BIDS, "[]"), bids);
        MakerLadder.sanitize(asks, true);
        MakerLadder.sanitize(bids, false);
        pegged = prefs.getBoolean(K_PEGGED, true);
        stepPct = Util.decOr(prefs.getString(K_STEP, "0.20"), new BigDecimal("0.20"));
        levelCount = Math.max(1, Math.min(MakerLadder.MAX_LEVELS, prefs.getInt(K_NLEVELS, 3)));
        askSize = Util.dec(prefs.getString(K_ASKSIZE, "0"));
        bidSize = Util.dec(prefs.getString(K_BIDSIZE, "0"));
        manualMid = Util.dec(prefs.getString(K_MID, "0"));
        skewPct = Util.dec(prefs.getString(K_SKEW, "0"));
        repricePct = Util.decOr(prefs.getString(K_REPRICE, "0.25"), new BigDecimal("0.25"));
        armed = prefs.getBoolean(K_ARMED, false);

        // ---- migration from the 0.2.x offset ladder: it was pegged by construction, so the
        // old offsets/sizes become peg seed parameters (first offset = step, first size = both
        // side sizes). The rungs themselves regenerate on the next armed cycle.
        if (askSize.signum() <= 0 && bidSize.signum() <= 0 && asks.isEmpty() && bids.isEmpty()) {
            try {
                JSONArray a = new JSONArray(prefs.getString(K_LEVELS, "[]"));
                if (a.length() > 0) {
                    JSONObject o = a.getJSONObject(0);
                    BigDecimal off = Util.dec(o.optString("off", "0"));
                    BigDecimal size = Util.dec(o.optString("size", "0"));
                    if (off.signum() > 0) stepPct = off;
                    if (size.signum() > 0) { askSize = size; bidSize = size; }
                    levelCount = Math.max(1, Math.min(MakerLadder.MAX_LEVELS, a.length()));
                    pegged = true;
                }
            } catch (Exception ignore) {}
        }

        slotOrderIds.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_SLOTS, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                slotOrderIds.put(k, o.optString(k, ""));
            }
        } catch (Exception ignore) {}
        slotSizes.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_SIZES, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                slotSizes.put(k, o.optString(k, ""));
            }
        } catch (Exception ignore) {}
        String lm = prefs.getString(K_LASTMID, "");
        lastActedMid = lm.isEmpty() ? null : Util.dec(lm);
    }

    private static void readLevels(String raw, List<MakerLadder.Level> out) {
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new MakerLadder.Level(Util.dec(o.optString("p", "0")),
                        Util.dec(o.optString("a", "0"))));
            }
        } catch (Exception ignore) {}
    }

    private static JSONArray levelsJson(List<MakerLadder.Level> levels) {
        JSONArray a = new JSONArray();
        try {
            for (MakerLadder.Level l : levels) {
                JSONObject o = new JSONObject();
                o.put("p", l.price.toPlainString());
                o.put("a", l.sizeMinima.toPlainString());
                a.put(o);
            }
        } catch (Exception ignore) {}
        return a;
    }

    public MakerLadder.Config toLadderConfig() {
        return new MakerLadder.Config(pegged, stepPct, levelCount, askSize, bidSize,
                new ArrayList<>(asks), new ArrayList<>(bids), skewPct, repricePct);
    }

    public void save() {
        if (prefs == null) return;   // test instance
        MakerLadder.sanitize(asks, true);
        MakerLadder.sanitize(bids, false);
        JSONObject slots = new JSONObject();
        JSONObject sizes = new JSONObject();
        try {
            for (Map.Entry<String, String> e : slotOrderIds.entrySet()) slots.put(e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : slotSizes.entrySet()) sizes.put(e.getKey(), e.getValue());
        } catch (Exception ignore) {}
        prefs.edit()
                .putString(K_ASKS, levelsJson(asks).toString())
                .putString(K_BIDS, levelsJson(bids).toString())
                .putBoolean(K_PEGGED, pegged)
                .putString(K_STEP, stepPct.toPlainString())
                .putInt(K_NLEVELS, levelCount)
                .putString(K_ASKSIZE, askSize.toPlainString())
                .putString(K_BIDSIZE, bidSize.toPlainString())
                .putString(K_MID, manualMid.toPlainString())
                .putString(K_SKEW, skewPct.toPlainString())
                .putString(K_REPRICE, repricePct.toPlainString())
                .putBoolean(K_ARMED, armed)
                .putString(K_SLOTS, slots.toString())
                .putString(K_SIZES, sizes.toString())
                .putString(K_LASTMID, lastActedMid == null ? "" : lastActedMid.toPlainString())
                .apply();
    }
}

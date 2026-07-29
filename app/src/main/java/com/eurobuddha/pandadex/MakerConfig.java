package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The market maker's settings and its memory of which on-chain order belongs to which rung.
 *
 * The rung rows are stored POSITIONALLY (index i = rung i+1 out from the mid) and are never
 * sanitized/sorted here: while pegged, a rung's SIZE is the user's per-rung choice and its
 * position IS its identity — sorting would silently reassign sizes to different rungs. The
 * manual branch of {@link MakerLadder#desired} sanitizes its own copies.
 *
 * The slot→order records are the important part: without them a restart would not recognise
 * the ladder already on the book and would post a second one on top of it. Each record also
 * carries WHEN it was sent (block height), because an order takes ~1 block of PoW + mining to
 * surface in the confirmed book — a record whose order is not yet visible is IN FLIGHT, not
 * missing, and re-creating it would duplicate real on-chain funds (observed live in 0.2.6).
 * Cancel tombstones are the same idea for the reverse direction: an orderId we have asked to
 * cancel but whose coin may still surface — the engine keeps cancelling anything tombstoned
 * until it is gone, which is what finally catches late-confirming orphans.
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
    private static final String K_SLOTS = "slots";       // legacy 0.2.x id map (migration only)
    private static final String K_SIZES = "slotsizes";   // legacy 0.2.x size map (migration only)
    private static final String K_SLOTS2 = "slots2";
    private static final String K_TOMB = "tombstones";
    private static final String K_LASTMID = "lastmid";

    private final SharedPreferences prefs;

    /** What we posted for one rung, and when. */
    public static final class SlotRec {
        public final String orderId;
        public final BigDecimal size;        // MINIMA we POSTED — recognises partial fills
        /** Block the CREATE send was accepted at. 0 = unknown (legacy/restart) — the engine
         *  stamps the current block on first sight, giving a fresh patience window. */
        public long sentBlock;
        /** Block of the last create/relock sent for this slot — the settling window. */
        public long lastActionBlock;

        public SlotRec(String orderId, BigDecimal size, long sentBlock, long lastActionBlock) {
            this.orderId = orderId;
            this.size = size;
            this.sentBlock = sentBlock;
            this.lastActionBlock = lastActionBlock;
        }
    }

    /** Positional rung rows (index = rung). See class doc — never sorted, blanks are gaps. */
    public final List<MakerLadder.Level> asks = new ArrayList<>();
    public final List<MakerLadder.Level> bids = new ArrayList<>();

    /** Auto-fill seed parameters — UI conveniences only; the engine never reads them. */
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

    /** slot id ("B1"/"A2"…) → what we posted for it and when. */
    public final Map<String, SlotRec> slots = new LinkedHashMap<>();
    /** orderId → block its cancel was sent. Lives until the coin is verifiably gone. */
    public final Map<String, Long> cancelTombstones = new LinkedHashMap<>();
    public BigDecimal lastActedMid = null;

    public MakerConfig(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** In-memory only, for unit tests — {@link #save()} is a no-op without prefs. */
    MakerConfig() {
        prefs = null;
    }

    // ---------------------------------------------------------------- slot records

    /** Record a CREATE that the node ACCEPTED (call from onPosted, never before). */
    public void rememberSlot(String slotId, String orderId, BigDecimal sizeMinima, long block) {
        slots.put(slotId, new SlotRec(orderId, sizeMinima, block, block));
        save();
    }

    /** A relock/adjustment was sent for this slot — restart its settling window. */
    public void noteSlotAction(String slotId, long block) {
        SlotRec r = slots.get(slotId);
        if (r == null) return;
        r.lastActionBlock = block;
        save();
    }

    public void forgetSlot(String slotId) {
        if (slots.remove(slotId) != null) save();
    }

    public void forgetSlotByOrderId(String orderId) {
        if (orderId == null) return;
        String slot = null;
        for (Map.Entry<String, SlotRec> e : slots.entrySet()) {
            if (orderId.equals(e.getValue().orderId)) { slot = e.getKey(); break; }
        }
        if (slot != null) { slots.remove(slot); save(); }
    }

    public void clearSlots() {
        slots.clear();
        save();
    }

    public String orderIdFor(String slotId) {
        SlotRec r = slots.get(slotId);
        return r == null ? null : r.orderId;
    }

    /** The size we posted for this slot, or null if we never did. */
    public BigDecimal postedSizeFor(String slotId) {
        SlotRec r = slots.get(slotId);
        return (r == null || r.size == null || r.size.signum() <= 0) ? null : r.size;
    }

    // ---------------------------------------------------------------- tombstones

    public void tombstone(String orderId, long block) {
        if (orderId == null || orderId.isEmpty()) return;
        cancelTombstones.put(orderId, block);
        save();
    }

    public void clearTombstone(String orderId) {
        if (cancelTombstones.remove(orderId) != null) save();
    }

    // ---------------------------------------------------------------- load / save

    private void load() {
        asks.clear();
        bids.clear();
        readLevels(prefs.getString(K_ASKS, "[]"), asks);
        readLevels(prefs.getString(K_BIDS, "[]"), bids);
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
        // side sizes). ONE-SHOT: the legacy key is dropped on the next save().
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

        // ---- 0.2.6 → 0.2.7: pegged sizes moved from the seed scalars into the rung rows.
        // A 0.2.6 config normally saved its generated rungs (sizes included) so nothing to do;
        // but a peg that never filled (awaiting the first price) has sizes with empty rows —
        // synthesize them once so the ladder the user configured isn't silently lost.
        if (pegged && !MakerLadder.hasSizedRung(asks) && askSize.signum() > 0) {
            asks.clear();
            for (int i = 0; i < levelCount; i++) asks.add(new MakerLadder.Level(BigDecimal.ZERO, askSize));
        }
        if (pegged && !MakerLadder.hasSizedRung(bids) && bidSize.signum() > 0) {
            bids.clear();
            for (int i = 0; i < levelCount; i++) bids.add(new MakerLadder.Level(BigDecimal.ZERO, bidSize));
        }

        slots.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_SLOTS2, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject r = o.optJSONObject(k);
                if (r == null) continue;
                slots.put(k, new SlotRec(r.optString("id", ""), Util.dec(r.optString("size", "0")),
                        r.optLong("sent", 0), r.optLong("act", 0)));
            }
        } catch (Exception ignore) {}

        // ---- 0.2.6 → 0.2.7: legacy flat id/size maps become records with sentBlock=0 (the
        // engine stamps the current block on first sight = fresh patience window, so an
        // upgrade mid-flight can neither duplicate nor orphan). ONE-SHOT via save().
        if (slots.isEmpty()) {
            try {
                JSONObject ids = new JSONObject(prefs.getString(K_SLOTS, "{}"));
                JSONObject sizes = new JSONObject(prefs.getString(K_SIZES, "{}"));
                for (Iterator<String> it = ids.keys(); it.hasNext(); ) {
                    String k = it.next();
                    slots.put(k, new SlotRec(ids.optString(k, ""),
                            Util.dec(sizes.optString(k, "0")), 0, 0));
                }
            } catch (Exception ignore) {}
        }

        cancelTombstones.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_TOMB, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                cancelTombstones.put(k, o.optLong(k, 0));
            }
        } catch (Exception ignore) {}

        String lm = prefs.getString(K_LASTMID, "");
        lastActedMid = lm.isEmpty() ? null : Util.dec(lm);
    }

    private static void readLevels(String raw, List<MakerLadder.Level> out) {
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length() && i < MakerLadder.MAX_LEVELS; i++) {
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
                o.put("p", l == null || l.price == null ? "0" : l.price.toPlainString());
                o.put("a", l == null || l.sizeMinima == null ? "0" : l.sizeMinima.toPlainString());
                a.put(o);
            }
        } catch (Exception ignore) {}
        return a;
    }

    public MakerLadder.Config toLadderConfig() {
        return new MakerLadder.Config(pegged, stepPct,
                new ArrayList<>(asks), new ArrayList<>(bids), skewPct, repricePct);
    }

    public void save() {
        if (prefs == null) return;   // test instance
        JSONObject recs = new JSONObject();
        JSONObject tomb = new JSONObject();
        try {
            for (Map.Entry<String, SlotRec> e : slots.entrySet()) {
                SlotRec r = e.getValue();
                JSONObject o = new JSONObject();
                o.put("id", r.orderId);
                o.put("size", r.size == null ? "0" : r.size.toPlainString());
                o.put("sent", r.sentBlock);
                o.put("act", r.lastActionBlock);
                recs.put(e.getKey(), o);
            }
            for (Map.Entry<String, Long> e : cancelTombstones.entrySet()) tomb.put(e.getKey(), e.getValue());
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
                .putString(K_SLOTS2, recs.toString())
                .putString(K_TOMB, tomb.toString())
                .putString(K_LASTMID, lastActedMid == null ? "" : lastActedMid.toPlainString())
                .remove(K_LEVELS)   // legacy stores are migrate-once, never re-read
                .remove(K_SLOTS)
                .remove(K_SIZES)
                .apply();
    }
}

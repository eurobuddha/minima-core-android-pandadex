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
 * The slot→order mapping is the important part: without it a restart would not recognise the
 * ladder already on the book and would post a second one on top of it. It is keyed by the
 * order id we generated (stable across re-locks, since a re-lock preserves state port 4).
 */
public final class MakerConfig {

    private static final String PREFS = "pandadex_maker";
    private static final String K_LEVELS = "levels";
    private static final String K_SKEW = "skew";
    private static final String K_REPRICE = "reprice";
    private static final String K_ARMED = "armed";
    private static final String K_SLOTS = "slots";
    private static final String K_LASTMID = "lastmid";

    private final SharedPreferences prefs;

    public final List<MakerLadder.Level> levels = new ArrayList<>();
    public BigDecimal skewPct = BigDecimal.ZERO;
    public BigDecimal repricePct = new BigDecimal("0.25");
    public boolean armed = false;
    /** slot id ("B1"/"A2"…) → the order id we placed for it. */
    public final Map<String, String> slotOrderIds = new HashMap<>();
    public BigDecimal lastActedMid = null;

    public MakerConfig(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        levels.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(K_LEVELS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                levels.add(new MakerLadder.Level(Util.dec(o.optString("off", "0")),
                        Util.dec(o.optString("size", "0"))));
            }
        } catch (Exception ignore) {}
        if (levels.isEmpty()) defaults();
        skewPct = Util.dec(prefs.getString(K_SKEW, "0"));
        repricePct = Util.decOr(prefs.getString(K_REPRICE, "0.25"), new BigDecimal("0.25"));
        armed = prefs.getBoolean(K_ARMED, false);
        slotOrderIds.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_SLOTS, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                slotOrderIds.put(k, o.optString(k, ""));
            }
        } catch (Exception ignore) {}
        String lm = prefs.getString(K_LASTMID, "");
        lastActedMid = lm.isEmpty() ? null : Util.dec(lm);
    }

    /** A sane starting ladder: three rungs a side, widening outwards with growing size. */
    public void defaults() {
        levels.clear();
        levels.add(new MakerLadder.Level(new BigDecimal("0.20"), new BigDecimal("200")));
        levels.add(new MakerLadder.Level(new BigDecimal("0.40"), new BigDecimal("300")));
        levels.add(new MakerLadder.Level(new BigDecimal("0.60"), new BigDecimal("500")));
    }

    public MakerLadder.Config toLadderConfig() {
        return new MakerLadder.Config(new ArrayList<>(levels), skewPct, repricePct, true, true);
    }

    public void save() {
        JSONArray a = new JSONArray();
        try {
            for (MakerLadder.Level l : levels) {
                JSONObject o = new JSONObject();
                o.put("off", l.offsetPct.toPlainString());
                o.put("size", l.sizeMinima.toPlainString());
                a.put(o);
            }
        } catch (Exception ignore) {}
        JSONObject slots = new JSONObject();
        try {
            for (Map.Entry<String, String> e : slotOrderIds.entrySet()) slots.put(e.getKey(), e.getValue());
        } catch (Exception ignore) {}
        prefs.edit()
                .putString(K_LEVELS, a.toString())
                .putString(K_SKEW, skewPct.toPlainString())
                .putString(K_REPRICE, repricePct.toPlainString())
                .putBoolean(K_ARMED, armed)
                .putString(K_SLOTS, slots.toString())
                .putString(K_LASTMID, lastActedMid == null ? "" : lastActedMid.toPlainString())
                .apply();
    }
}

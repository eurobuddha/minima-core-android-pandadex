package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;

/**
 * Reconstructs THIS wallet's own trades from the node's transaction history.
 *
 * The market tape is otherwise built only from book scans this device happened to witness, so
 * a phone that was asleep, mid-typing, or freshly installed has gaps — which is why two phones
 * that traded with each other could show completely different 24h figures for the same market.
 * The chain already holds the answer for every trade this wallet took part in; this reads it
 * back.
 *
 * What it CANNOT recover, honestly: trades between other people that this node never observed.
 * There is no server to ask. Those remain forward-only.
 *
 * Identification rule — deliberately based on what the money actually did, not on any state we
 * choose to trust: a transaction that touches the book address AND moves exactly two assets in
 * OPPOSITE directions is a trade. Its MINIMA leg is the size, the ratio is the price, and the
 * sign of the MINIMA leg is the side. A cancel or a GTC relock moves one asset (or nets to
 * nothing), so it is never mistaken for a trade.
 *
 * Rows are keyed on the SPENT order coinid — the same key {@link FillTape} uses — so a trade
 * already recorded live is silently ignored rather than double-counted.
 */
public final class TradeBackfill {

    /** One page at a time: a single txpow runs to several KB, and an oversized IPC reply is an
     *  uncatchable kill on the upstream node. */
    private static final int PAGE = 1;
    /** Bound the whole sweep so a long history can never turn into an unbounded scan. */
    private static final int MAX_PAGES = 400;
    private static final String KEY_DONE = "backfill_done_v1";

    public interface Done { void finished(int recovered); }

    private final NodeApi node;
    private final DexDb db;
    private final String myAddrHex;
    private int recovered = 0;
    private int page = 0;

    public TradeBackfill(NodeApi node, DexDb db, String myAddrHex) {
        this.node = node;
        this.db = db;
        this.myAddrHex = myAddrHex == null ? "" : myAddrHex;
    }

    /** Run once per install (cheap no-op afterwards). The high-water mark is a simple done
     *  flag: live observation covers everything from here on, so this only has to close the
     *  historical gap. */
    public void runOnce(Done cb) {
        if ("1".equals(db.meta(KEY_DONE, ""))) { if (cb != null) cb.finished(0); return; }
        step(cb);
    }

    /** Force a re-scan (used after a wipe or when the user asks to rebuild history). */
    public void rerun(Done cb) {
        db.putMeta(KEY_DONE, "");
        page = 0;
        recovered = 0;
        step(cb);
    }

    private void step(Done cb) {
        if (page >= MAX_PAGES) { finish(cb); return; }
        final int offset = page;
        node.cmd("history max:" + PAGE + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                if (txpows == null || txpows.length() == 0) { finish(cb); return; }
                JSONObject details = resp.optJSONObject("details");
                for (int i = 0; i < txpows.length(); i++) {
                    try {
                        consider(txpows.optJSONObject(i), details);
                    } catch (Throwable ignore) {
                        // a single unreadable entry must never abort the recovery
                    }
                }
                page++;
                step(cb);
            }
            @Override public void onError(String message) { finish(cb); }
        });
    }

    private void finish(Done cb) {
        db.putMeta(KEY_DONE, "1");
        if (cb != null) cb.finished(recovered);
    }

    /** Decide whether one history entry is a trade of ours, and record it if so. */
    private void consider(JSONObject txpow, JSONObject allDetails) {
        if (txpow == null) return;
        String txpowid = txpow.optString("txpowid", "");
        JSONObject body = txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        if (txn == null) return;

        // the order coin(s) this transaction consumed from the book
        String spentOrderCoin = null;
        JSONArray ins = txn.optJSONArray("inputs");
        if (ins == null) return;
        for (int i = 0; i < ins.length(); i++) {
            JSONObject c = ins.optJSONObject(i);
            if (c == null) continue;
            String addr = c.optString("address", "");
            String mx = c.optString("miniaddress", "");
            if (DexContract.ADDR_V5.equalsIgnoreCase(addr)
                    || DexContract.ADDR_V5_MX.equalsIgnoreCase(mx)) {
                spentOrderCoin = c.optString("coinid", "");
                break;                       // attribute to the first consumed order
            }
        }
        if (spentOrderCoin == null || spentOrderCoin.isEmpty()) return;   // not a book txn

        // what this transaction did to OUR balance
        JSONObject diff = differenceFor(txpowid, allDetails);
        if (diff == null) return;
        BigDecimal minimaDelta = BigDecimal.ZERO, usdtDelta = BigDecimal.ZERO;
        int nonZero = 0;
        Iterator<String> keys = diff.keys();
        while (keys.hasNext()) {
            String tok = keys.next();
            BigDecimal v = Util.dec(diff.optString(tok, "0"));
            if (v.signum() == 0) continue;
            nonZero++;
            if ("0x00".equalsIgnoreCase(tok)) minimaDelta = v;
            else if (DexContract.USDT_ID.equalsIgnoreCase(tok)) usdtDelta = v;
        }
        // a trade moves BOTH assets, in opposite directions. a cancel or a relock does not.
        if (nonZero != 2 || minimaDelta.signum() == 0 || usdtDelta.signum() == 0) return;
        if (minimaDelta.signum() == usdtDelta.signum()) return;

        BigDecimal size = minimaDelta.abs();
        if (size.signum() == 0) return;
        BigDecimal price = usdtDelta.abs().divide(size, PriceMath.PRICE_DP, RoundingMode.HALF_UP);
        boolean weBought = minimaDelta.signum() > 0;
        long timeMs = txpow.optLong("timemilli", 0);
        if (timeMs <= 0) {
            JSONObject header = txpow.optJSONObject("header");
            timeMs = header == null ? System.currentTimeMillis() : header.optLong("timemilli",
                    System.currentTimeMillis());
        }
        long block = 0;
        JSONObject header = txpow.optJSONObject("header");
        if (header != null) block = Util.dec(header.optString("block", "0")).longValue();

        // keyed on the spent order coin — identical to the live path, so a trade already
        // recorded is ignored rather than counted twice
        boolean isNew = db.addFill(spentOrderCoin, timeMs, block, price, size, weBought, false, true);
        db.addMyTrade(spentOrderCoin, timeMs, block, price, size, weBought, false, "");
        if (isNew) recovered++;
    }

    /** The per-transaction balance change the node reports, tolerating both the keyed-by-txpowid
     *  shape and a single-entry shape. */
    private static JSONObject differenceFor(String txpowid, JSONObject allDetails) {
        if (allDetails == null) return null;
        JSONObject entry = allDetails.optJSONObject(txpowid);
        if (entry == null && allDetails.has("difference")) entry = allDetails;
        if (entry == null) return null;
        return entry.optJSONObject("difference");
    }
}

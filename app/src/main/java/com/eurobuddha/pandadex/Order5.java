package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Set;

/**
 * One live order coin on the V5 book. Parsed defensively from a `coins` row: token coin
 * VALUE is `tokenamount` (raw `amount` is the sub-grain coloured underlying — reading it is
 * a fund bug, see usdtSwap lesson); price is ALWAYS derived from port 2 / locked amount
 * (port 6 is display-only and taker-writable on remainders).
 */
public final class Order5 {

    public final String coinid;
    public final String ownerPk;      // port 0
    public final String wantAddr;     // port 1
    public final BigDecimal wantAmt;  // port 2
    public final String wantTok;      // port 3 (0x00 or mxUSDT)
    public final String orderId;      // port 4
    public final boolean sell;        // port 5 "1" = sell (MINIMA locked)
    public final boolean gtc;         // port 7
    public final BigDecimal minRem;   // port 8
    public final BigDecimal locked;   // tokenamount||amount of the coin
    public final String lockedTok;    // coin tokenid
    public final long created;        // block the coin was created in
    private boolean relevant;         // node-side ownership belt (coins relevant:true)

    private Order5(String coinid, String ownerPk, String wantAddr, BigDecimal wantAmt,
                   String wantTok, String orderId, boolean sell, boolean gtc,
                   BigDecimal minRem, BigDecimal locked, String lockedTok, long created) {
        this.coinid = coinid;
        this.ownerPk = ownerPk;
        this.wantAddr = wantAddr;
        this.wantAmt = wantAmt;
        this.wantTok = wantTok;
        this.orderId = orderId;
        this.sell = sell;
        this.gtc = gtc;
        this.minRem = minRem;
        this.locked = locked;
        this.lockedTok = lockedTok;
        this.created = created;
    }

    /** Parse a `coins` row; returns null for anything malformed (never throws). */
    public static Order5 from(JSONObject coin) {
        try {
            String coinid = coin.optString("coinid", "");
            if (coinid.isEmpty()) return null;
            String[] st = new String[9];
            Object stateObj = coin.opt("state");
            if (stateObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) stateObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    int p = e.optInt("port", -1);
                    if (p >= 0 && p < 9) st[p] = e.optString("data", null);
                }
            } else if (stateObj instanceof JSONObject) {   // simplestate:true map form
                JSONObject map = (JSONObject) stateObj;
                for (int p = 0; p < 9; p++) {
                    if (map.has(String.valueOf(p))) st[p] = map.optString(String.valueOf(p), null);
                }
            }
            if (st[0] == null || st[1] == null || st[2] == null || st[4] == null) return null;
            String tokenid = coin.optString("tokenid", "0x00");
            String amt = coin.optString("tokenamount", "");
            if (amt.isEmpty() || "0x00".equals(tokenid)) amt = coin.optString("amount", "0");
            BigDecimal locked = Util.dec(amt);
            if (locked.signum() <= 0) return null;
            BigDecimal want = Util.dec(st[2]);
            if (want.signum() <= 0) return null;
            boolean sell = !"0".equals(st[5] == null ? "1" : st[5].trim());
            boolean gtc = "1".equals(st[7] == null ? "" : st[7].trim());
            BigDecimal minRem = Util.decOr(st[8], BigDecimal.ZERO);
            long created = coin.optLong("created", 0);
            String wantTok = st[3] == null ? "0x00" : st[3];
            return new Order5(coinid, st[0], st[1], want, wantTok, st[4], sell, gtc, minRem,
                    locked, tokenid, created);
        } catch (Throwable t) {
            return null;
        }
    }

    public void markRelevant() { relevant = true; }

    /** Chain-derived ownership: node-side relevance belt OR key match (Limit's KeySet fix). */
    public boolean isMine(Set<String> myKeys) {
        return relevant || (myKeys != null && myKeys.contains(ownerPk));
    }

    /** MINIMA side of the order (locked for sells, wanted for buys). */
    public BigDecimal minimaAmount() { return sell ? locked : wantAmt; }

    /** mxUSDT side of the order (wanted for sells, locked for buys). */
    public BigDecimal usdtAmount() { return sell ? wantAmt : locked; }

    /** Price in mxUSDT per MINIMA — derived from enforced amounts only. */
    public BigDecimal price() {
        return PriceMath.price(usdtAmount(), minimaAmount().signum() == 0 ? BigDecimal.ONE : minimaAmount());
    }

    public long age(long chainBlock) { return created <= 0 ? 0 : Math.max(0, chainBlock - created); }

    public boolean expired(long chainBlock) { return age(chainBlock) > DexContract.EXPIRY_BLOCKS; }

    public boolean renewDue(long chainBlock) { return gtc && age(chainBlock) >= DexContract.RENEW_AT; }
}

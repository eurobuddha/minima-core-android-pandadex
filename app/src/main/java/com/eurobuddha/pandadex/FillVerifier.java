package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Settles the one thing a book diff genuinely cannot: when an order coin vanishes whole, did
 * it TRADE or was it CANCELLED?
 *
 * {@link FillTape} counts every full disappearance as a fill, because by book-diff alone the
 * two look identical. That is fine for our own cancels (recorded in the cancel log) but not
 * for anyone else's — including the same user's other phone — and every one of those lands in
 * the tape that IS this app's last price, candles and 24h stats.
 *
 * On-chain they are not identical at all. Both outcomes pay the maker's payout address
 * (state port 1), but they pay DIFFERENT THINGS:
 *
 *   CANCEL  — {@link DexTxn#cancel} refunds exactly {@code locked} of {@code lockedTok}
 *   FILL    — the taker pays {@code wantAmt} of {@code wantTok}, the OTHER token
 *
 * So one bounded `coins address:` query settles it. Only POSITIVE proof of a refund suppresses
 * anything: if the evidence is absent or the query fails we still record the fill, because
 * losing a real trade is the worse error — a missing trade is invisible, a phantom one is not.
 */
public final class FillVerifier {

    /** What the chain says happened to a vanished order coin. */
    public enum Verdict { CANCELLED, FILLED, UNKNOWN }

    public interface Cb { void onVerdict(Verdict v); }

    private final NodeApi node;

    public FillVerifier(NodeApi node) { this.node = node; }

    /**
     * @param o         the order as we last saw it resting
     * @param seenBlock chain height when it disappeared — evidence older than the order itself
     *                  is unrelated and must not decide the verdict
     */
    public void verify(Order5 o, long seenBlock, Cb cb) {
        if (o == null || o.wantAddr == null || o.wantAddr.isEmpty()) {
            cb.onVerdict(Verdict.UNKNOWN);
            return;
        }
        node.cmd("coins simplestate:true address:" + o.wantAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                cb.onVerdict(adjudicate(json, o, seenBlock));
            }
            @Override public void onError(String message) {
                cb.onVerdict(Verdict.UNKNOWN);   // no evidence either way — caller keeps the fill
            }
        });
    }

    /** Pure, so the decision table is testable without a node. */
    static Verdict adjudicate(JSONObject json, Order5 o, long seenBlock) {
        Object resp = json == null ? null : json.opt("response");
        if (!(resp instanceof JSONArray)) return Verdict.UNKNOWN;
        JSONArray arr = (JSONArray) resp;
        boolean refund = false, payment = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            // The order coin itself predates its own disappearance, as does anything already
            // sitting at this address beforehand. Only coins created no earlier than the order
            // can be the product of spending it.
            if (c.optLong("created", 0) < o.created) continue;
            String tok = c.optString("tokenid", "0x00");
            BigDecimal amt = Util.dec(c.optString("tokenamount",
                    c.optString("amount", "0")));
            if (amt.signum() <= 0) continue;
            if (sameToken(tok, o.lockedTok) && amt.compareTo(o.locked) == 0) refund = true;
            else if (sameToken(tok, o.wantTok) && amt.compareTo(o.wantAmt) == 0) payment = true;
        }
        // A refund is proof of a cancel. Payment evidence is corroboration for a fill, but its
        // absence proves nothing (proceeds get spent), so it never suppresses on its own.
        if (refund && !payment) return Verdict.CANCELLED;
        if (payment) return Verdict.FILLED;
        return Verdict.UNKNOWN;
    }

    private static boolean sameToken(String a, String b) {
        if (a == null) a = "0x00";
        if (b == null) b = "0x00";
        return a.equalsIgnoreCase(b);
    }
}

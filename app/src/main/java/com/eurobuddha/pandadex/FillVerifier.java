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
 * So one bounded `coins address:` query settles it. Only positive payment evidence records a
 * full fill; positive refund evidence records a cancel. UNKNOWN is deliberately left out of the
 * normal tape so ambiguous rows do not look like real trades.
 */
public final class FillVerifier {

    /** A compact evidence window keeps the native IPC reply far below its 256 KB cap while
     *  covering the foreground and five-minute watcher scan gaps. */
    static final int EVIDENCE_BLOCKS = 12;

    /** What the chain says happened to a vanished order coin. */
    public enum Verdict { CANCELLED, FILLED, UNKNOWN }

    public interface Cb { void onVerdict(Verdict v); }

    private final NodeApi node;

    public FillVerifier(NodeApi node) { this.node = node; }

    /**
     * @param o         the order as we last saw it resting
     * @param seenBlock chain height when it disappeared. Only a recent output can be evidence;
     *                  an older matching wallet coin is unrelated.
     */
    public void verify(Order5 o, long seenBlock, Cb cb) {
        if (o == null || o.wantAddr == null || o.wantAddr.isEmpty()) {
            cb.onVerdict(Verdict.UNKNOWN);
            return;
        }
        node.cmd("coins simplestate:true address:" + o.wantAddr
                + " coinage:0 depth:" + EVIDENCE_BLOCKS, new NodeApi.Cb() {
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
            // A matching coin that predates the disappearance window is merely another wallet
            // UTXO. The previous order-created cutoff let any later, unrelated same-value
            // deposit suppress a genuine fill.
            long earliest = Math.max(o.created, seenBlock - EVIDENCE_BLOCKS);
            if (c.optLong("created", 0) < earliest) continue;
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

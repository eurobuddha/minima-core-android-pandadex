package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * EXCLUSIVITY (ported back from pandadex-mds/verifier.js, where it was measured). Adjudicating each
 * vanished order INDEPENDENTLY is too weak, and it is the bug behind a tape full of trades that
 * never happened. Every order a wallet creates carries the SAME payout address — port 1 is the
 * wallet's receive address — so one address holds every rung's refund, every proceeds output and
 * all change. A cancelled ASK rung refunds exactly its size in MINIMA; a BID rung of the same size
 * WANTS exactly that much MINIMA. Judged separately, that one refund coin "proves payment" for
 * every bid rung that vanishes in the same window. On the reported ladder, three cancellations
 * produced three phantom trades.
 *
 * The fix is not a better predicate, it is that a coin is evidence for at most ONE order:
 *
 *   pass 1  refunds  — a coin equal to an order's `locked` in its `lockedTok` proves THAT order was
 *                      cancelled, and CLAIMS the coin so nobody else can read it as their payment.
 *   pass 2  payments — of what is left, a coin equal to an order's `wantAmt` in its `wantTok`
 *                      proves that order was filled.
 *   pass 3  the rest — UNKNOWN, never recorded.
 *
 * An order with BOTH shapes available is genuinely ambiguous and is settled in pass 2, where a
 * payment wins — this class's existing precedence, kept deliberately so a single-order verdict is
 * unchanged by the batch path.
 */
public final class FillVerifier {

    /** A compact evidence window keeps the native IPC reply far below its 256 KB cap while
     *  covering the foreground and five-minute watcher scan gaps. */
    static final int EVIDENCE_BLOCKS = 12;

    /** What the chain says happened to a vanished order coin. */
    public enum Verdict { CANCELLED, FILLED, UNKNOWN }

    private final NodeApi node;

    public FillVerifier(NodeApi node) { this.node = node; }

    /** One vanished order awaiting a verdict. {@code since} is the last block it was seen alive. */
    public static final class Item {
        public final String coinid;
        public final Order5 order;
        public final long since;
        public Item(String coinid, Order5 order, long since) {
            this.coinid = coinid; this.order = order; this.since = since;
        }
    }

    public interface BatchCb { void onVerdicts(Map<String, Verdict> verdicts); }

    /**
     * Fetch evidence once per DISTINCT payout address, then adjudicate the whole scan together —
     * one query for a ladder, and the exclusivity rule has every vanish in front of it at once.
     */
    public void verifyBatch(List<Item> items, long seenBlock, BatchCb cb) {
        if (items == null || items.isEmpty()) { cb.onVerdicts(new HashMap<>()); return; }
        List<String> addrs = new ArrayList<>();
        for (Item it : items) {
            String a = it.order == null ? null : it.order.wantAddr;
            if (a != null && !a.isEmpty() && !addrs.contains(a)) addrs.add(a);
        }
        Map<String, JSONArray> rowsByAddr = new HashMap<>();
        fetch(addrs, 0, rowsByAddr, () -> cb.onVerdicts(adjudicateBatch(rowsByAddr, items, seenBlock)));
    }

    private void fetch(List<String> addrs, int idx, Map<String, JSONArray> out, Runnable done) {
        if (idx >= addrs.size()) { done.run(); return; }
        String addr = addrs.get(idx);
        node.cmd("coins simplestate:true address:" + addr + " coinage:0 depth:" + EVIDENCE_BLOCKS,
                new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json == null ? null : json.opt("response");
                // Absent stays ABSENT, never an empty array: an unreadable reply is "we do not
                // know", and must not be mistaken for "there is no evidence".
                if (resp instanceof JSONArray) out.put(addr, (JSONArray) resp);
                fetch(addrs, idx + 1, out, done);
            }
            @Override public void onError(String message) { fetch(addrs, idx + 1, out, done); }
        });
    }

    /**
     * The earliest block a coin could have been created and still explain this disappearance.
     * {@code since} — the last block the order was seen alive — is the tightest of the three and
     * the reason an older, unrelated wallet coin of the same size cannot be mistaken for evidence.
     */
    static long earliest(Order5 o, long seenBlock, long since) {
        return Math.max(o == null ? 0 : o.created, Math.max(seenBlock - EVIDENCE_BLOCKS, since));
    }

    /** Rows come from the node with a coinid; the index is only a fallback so a synthetic row
     *  without one cannot collapse every claim onto a single key. */
    private static String claimKey(JSONObject coin, int index) {
        String id = coin == null ? "" : coin.optString("coinid", "");
        return id.isEmpty() ? "#" + index : id;
    }

    /** The first coin matching token+amount that no other order has already claimed. */
    static String findUnclaimed(JSONArray rows, Set<String> claimed, String tok,
                                BigDecimal amount, long floor) {
        if (rows == null || amount == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c == null) continue;
            String key = claimKey(c, i);
            if (claimed.contains(key)) continue;
            if (c.optLong("created", 0) < floor) continue;
            if (!sameToken(c.optString("tokenid", "0x00"), tok)) continue;
            BigDecimal amt = Util.dec(c.optString("tokenamount", c.optString("amount", "0")));
            if (amt.signum() <= 0) continue;
            if (amt.compareTo(amount) == 0) return key;
        }
        return null;
    }

    /** Pure, so the decision table is testable without a node. Returns coinid -> verdict. */
    static Map<String, Verdict> adjudicateBatch(Map<String, JSONArray> rowsByAddr,
                                                List<Item> items, long seenBlock) {
        Map<String, Verdict> out = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        if (items == null) return out;
        for (Item it : items) out.put(it.coinid, Verdict.UNKNOWN);

        // pass 1 — a clean refund proves a cancellation and CLAIMS the coin. That claim is the
        // whole fix: it stops the same refund being read as somebody else's payment. An order
        // that also has a payment-shaped coin available is ambiguous and waits for pass 2.
        for (Item it : items) {
            if (it.order == null) continue;
            JSONArray rows = rowsByAddr.get(it.order.wantAddr);
            if (rows == null) continue;
            long floor = earliest(it.order, seenBlock, it.since);
            if (findUnclaimed(rows, claimed, it.order.wantTok, it.order.wantAmt, floor) != null) continue;
            String found = findUnclaimed(rows, claimed, it.order.lockedTok, it.order.locked, floor);
            if (found != null) { claimed.add(found); out.put(it.coinid, Verdict.CANCELLED); }
        }

        // pass 2 — of what remains, a payment proves a fill
        for (Item it : items) {
            if (it.order == null || out.get(it.coinid) != Verdict.UNKNOWN) continue;
            JSONArray rows = rowsByAddr.get(it.order.wantAddr);
            if (rows == null) continue;
            long floor = earliest(it.order, seenBlock, it.since);
            String found = findUnclaimed(rows, claimed, it.order.wantTok, it.order.wantAmt, floor);
            if (found != null) { claimed.add(found); out.put(it.coinid, Verdict.FILLED); continue; }
            // nothing left that could be a payment — fall back to a refund it could not claim earlier
            found = findUnclaimed(rows, claimed, it.order.lockedTok, it.order.locked, floor);
            if (found != null) { claimed.add(found); out.put(it.coinid, Verdict.CANCELLED); }
        }
        return out;
    }

    /** Single-order form, kept for callers and tests. Exclusivity is trivial with one item. */
    static Verdict adjudicate(JSONObject json, Order5 o, long seenBlock) {
        Object resp = json == null ? null : json.opt("response");
        if (!(resp instanceof JSONArray) || o == null) return Verdict.UNKNOWN;
        Map<String, JSONArray> byAddr = new HashMap<>();
        byAddr.put(o.wantAddr, (JSONArray) resp);
        List<Item> one = new ArrayList<>();
        one.add(new Item(o.coinid, o, 0));
        Verdict v = adjudicateBatch(byAddr, one, seenBlock).get(o.coinid);
        return v == null ? Verdict.UNKNOWN : v;
    }

    private static boolean sameToken(String a, String b) {
        if (a == null) a = "0x00";
        if (b == null) b = "0x00";
        return a.equalsIgnoreCase(b);
    }
}

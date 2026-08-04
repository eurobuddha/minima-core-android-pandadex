package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What ACTUALLY happened to an order coin, read from the transaction that spent it.
 *
 * {@link FillVerifier} infers a verdict from the CURRENT UTXO set: the order coin is gone, and some
 * coin at the payout address looks like it could explain that. Exclusive matching makes the
 * inference honest, but it cannot rescue the case where the payout has already been SPENT ONWARD —
 * there is no unspent coin left to point at, so a real fill reads UNKNOWN and is dropped.
 *
 * That case is not rare, it is the common one for your OWN orders: the market maker spends its
 * proceeds immediately to fund the next rung. The fills most likely to be lost are exactly the ones
 * you most want recorded.
 *
 * `history` records TRANSACTIONS, not the current UTXO set, so it still holds the answer long after
 * the proceeds have moved. Find the transaction whose inputs include the order coin and read its
 * outputs: a payout of {@code wantAmt} in {@code wantTok} to the order's payout address means
 * FILLED; a refund of {@code locked} in {@code lockedTok} means CANCELLED. Order-linked and
 * definitive — no amount coincidence between two orders can confuse it.
 *
 * `history relevant:true` returns only transactions relevant to this wallet, which is precisely the
 * set worth spending calls on: our own orders, and orders we filled ourselves. A stranger's order
 * filled by another stranger never appears, and correctly falls back to payout evidence.
 *
 * It also answers something nothing else can: a cancel posted from ANOTHER DEVICE on the same seed.
 * That transaction is wallet-relevant here, so this node sees it — where the local cancel log, which
 * only knows what this app submitted, would read it as a fill.
 *
 * PAGING IS MANDATORY, not an optimisation. An oversized reply exceeds the app's MAX_MESSAGE_LEN and
 * the Binder limit behind it, and kills the client uncatchably — there is no error to handle. Start
 * at {@link #PAGE_MAX}, halve on a bad page and retry the SAME offset, and at max:1 step over the
 * single oversized transaction so it cannot stall everything behind it. Ported from
 * pandadex-mds/history.js, which carries this discipline from pandapools-mds.
 */
public final class DexHistory {

    /** Transactions per page to start with. Kept small deliberately — see the class note. */
    static final int PAGE_MAX = 8;
    /** Hard ceiling on node calls for one lookup, so a deep history cannot spin forever. */
    static final int MAX_FETCHES = 12;
    /** Consecutive oversized transactions we will step over before giving up. */
    static final int MAX_SKIP = 3;

    /** The transaction that spent an order coin, and what it paid out. */
    public static final class Spend {
        public final String txpowid;
        public final int inputIndex;
        public final JSONArray outputs;
        Spend(String txpowid, int inputIndex, JSONArray outputs) {
            this.txpowid = txpowid == null ? "" : txpowid;
            this.inputIndex = inputIndex;
            this.outputs = outputs == null ? new JSONArray() : outputs;
        }
    }

    public interface Cb {
        /** Only coins actually found are present; anything missing falls through to payout evidence. */
        void onSpends(Map<String, Spend> found);
    }

    /** Indirection purely so the paging discipline — the part that exists to stay under the IPC
     *  cap — is testable without an Android node. */
    interface Cmd { void run(String command, NodeApi.Cb cb); }

    private final Cmd cmd;

    public DexHistory(NodeApi node) { this.cmd = node::cmd; }

    DexHistory(Cmd cmd) { this.cmd = cmd; }

    /** Walk recent wallet history until every wanted coinid is accounted for, or the budget runs out. */
    public void findSpends(Collection<String> coinids, Cb cb) {
        Set<String> wanted = new HashSet<>();
        if (coinids != null) {
            for (String id : coinids) if (id != null && !id.isEmpty()) wanted.add(id);
        }
        Map<String, Spend> found = new HashMap<>();
        if (wanted.isEmpty()) { cb.onSpends(found); return; }
        new Pager(wanted, found, cb).page(0);
    }

    /** Callback recursion, not a loop: every page is a node round-trip. */
    private final class Pager {
        private final Set<String> wanted;
        private final Map<String, Spend> found;
        private final Cb cb;
        private int pageMax = PAGE_MAX, fetches = 0, skips = 0;

        Pager(Set<String> wanted, Map<String, Spend> found, Cb cb) {
            this.wanted = wanted; this.found = found; this.cb = cb;
        }

        void page(final int offset) {
            if (fetches++ >= MAX_FETCHES) { cb.onSpends(found); return; }
            cmd.run("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) { handle(json, offset); }
                @Override public void onError(String message) { bad(offset); }
            });
        }

        /** A dropped or over-cap page: halve and ask again for the SAME offset, so nothing is
         *  skipped merely because one page was too big to deliver. */
        void bad(int offset) {
            if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); page(offset); return; }
            if (++skips <= MAX_SKIP) { page(offset + 1); return; }   // one huge txn — step over it
            cb.onSpends(found);
        }

        void handle(JSONObject json, int offset) {
            JSONObject resp = json == null ? null : json.optJSONObject("response");
            JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
            if (txpows == null) { bad(offset); return; }
            skips = 0;
            int got = txpows.length();
            for (int j = 0; j < got && !wanted.isEmpty(); j++) {
                JSONObject tx = txpows.optJSONObject(j);
                if (tx == null) continue;
                JSONArray ins = coinsOf(tx, "inputs"), outs = coinsOf(tx, "outputs");
                for (int k = 0; k < ins.length(); k++) {
                    JSONObject in = ins.optJSONObject(k);
                    String id = in == null ? "" : in.optString("coinid", "");
                    if (!id.isEmpty() && wanted.remove(id)) {
                        found.put(id, new Spend(tx.optString("txpowid", ""), k, outs));
                    }
                }
            }
            if (wanted.isEmpty()) { cb.onSpends(found); return; }
            if (got < pageMax) { cb.onSpends(found); return; }      // reached the end of history
            page(offset + got);
        }
    }

    // ------------------------------------------------------------------ pure

    static JSONArray coinsOf(JSONObject tx, String which) {
        JSONObject body = tx == null ? null : tx.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        JSONArray arr = txn == null ? null : txn.optJSONArray(which);
        return arr == null ? new JSONArray() : arr;
    }

    static boolean isMinima(String tok) {
        return tok == null || tok.isEmpty() || tok.equalsIgnoreCase("0x00");
    }

    /** Token coins carry the scaled figure in `tokenamount`; `amount` is the raw colour fraction. */
    static BigDecimal value(JSONObject coin) {
        if (coin == null) return BigDecimal.ZERO;
        if (isMinima(coin.optString("tokenid", "0x00"))) return Util.dec(coin.optString("amount", "0"));
        String scaled = coin.optString("tokenamount", "");
        return Util.dec(scaled.isEmpty() ? coin.optString("amount", "0") : scaled);
    }

    static boolean sameToken(String a, String b) {
        if (a == null || a.isEmpty()) a = "0x00";
        if (b == null || b.isEmpty()) b = "0x00";
        return a.equalsIgnoreCase(b);
    }

    /** Outputs report the address in either form depending on the node build. */
    static boolean paysTo(JSONObject out, String addr) {
        if (out == null || addr == null || addr.isEmpty()) return false;
        return addr.equalsIgnoreCase(out.optString("address", ""))
                || addr.equalsIgnoreCase(out.optString("miniaddress", ""));
    }

    /**
     * Given the spending transaction's outputs, what happened to this order?
     * Returns null when the outputs say neither — the caller then falls back to payout evidence.
     */
    static FillVerifier.Verdict verdictFor(Spend spend, Order5 o) {
        if (spend == null || o == null) return null;
        if (spend.inputIndex < 0 || spend.inputIndex >= spend.outputs.length()) return null;
        return verdictForOutput(spend.outputs.optJSONObject(spend.inputIndex), o);
    }

    static FillVerifier.Verdict verdictFor(JSONArray outputs, Order5 o) {
        if (outputs == null || o == null) return null;
        for (int i = 0; i < outputs.length(); i++) {
            JSONObject out = outputs.optJSONObject(i);
            FillVerifier.Verdict v = verdictForOutput(out, o);
            if (v != null) return v;
        }
        return null;
    }

    static FillVerifier.Verdict verdictForOutput(JSONObject out, Order5 o) {
        if (!paysTo(out, o.wantAddr)) return null;
        String tok = out.optString("tokenid", "0x00");
        BigDecimal val = value(out);
        // the taker paid the maker what the order asked for
        if (sameToken(tok, o.wantTok) && val.compareTo(o.wantAmt) == 0) return FillVerifier.Verdict.FILLED;
        // the owner took their own funds back
        if (sameToken(tok, o.lockedTok) && val.compareTo(o.locked) == 0) return FillVerifier.Verdict.CANCELLED;
        return null;
    }
}

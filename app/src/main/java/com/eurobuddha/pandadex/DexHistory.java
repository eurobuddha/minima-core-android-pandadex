package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded wallet-history lookup. A matching input is accepted only after the stock node
 * confirms its TxPoW is on the current chain. Mempool/orphan entries are not spend proof. */
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
        public final String transactionId;
        public final int confirmations;
        public JSONObject input;
        public int inputCount;
        public long proofOrder, proofTimeMs;
        public long inclusionBlock, inclusionTimeMs;
        public String inclusionBlockId = "";
        public JSONArray transactionState = new JSONArray();
        public final JSONArray outputs;
        Spend(String txpowid, int inputIndex, JSONArray outputs) {
            this(txpowid, inputIndex, outputs, "", -1);
        }
        Spend(String txpowid, int inputIndex, JSONArray outputs, String transactionId, int confirmations) {
            this.transactionId = transactionId;
            this.confirmations = confirmations;
            this.txpowid = txpowid == null ? "" : txpowid;
            this.inputIndex = inputIndex;
            this.outputs = outputs == null ? new JSONArray() : outputs;
        }
    }

    /** Freeze callback proof before a receipt can be persisted or adopted. Reused from OwnerReceipt. */
    static DexHistory.Spend copyProof(DexHistory.Spend source) throws org.json.JSONException {
        DexHistory.Spend copy=new DexHistory.Spend(source.txpowid,source.inputIndex,new JSONArray(source.outputs.toString()),source.transactionId,source.confirmations);
        copy.input=source.input==null?null:new JSONObject(source.input.toString());copy.inputCount=source.inputCount;
        copy.transactionState=new JSONArray(source.transactionState.toString());copy.inclusionBlock=source.inclusionBlock;copy.inclusionBlockId=source.inclusionBlockId;
        copy.inclusionTimeMs=source.inclusionTimeMs;copy.proofOrder=source.proofOrder;copy.proofTimeMs=source.proofTimeMs;return copy;
    }

    public interface Cb {
        /** Only included spends are returned. A missing entry remains unresolved. */
        void onSpends(Map<String, Spend> found);
    }

    /** Indirection purely so the paging discipline — the part that exists to stay under the IPC
     *  cap — is testable without an Android node. */
    interface Cmd { void run(String command, NodeApi.Cb cb); }

    interface ProgressStore {
        int offset(boolean relevant, Collection<String> coinids);
        void checkpoint(boolean relevant, Collection<String> unresolved,
                        Collection<String> found, int nextOffset);
    }

    private final Cmd cmd;
    private final ProgressStore progress;
    private String recoveryError = "";

    public DexHistory(NodeApi node, DexDb db) { this(node::cmd, db); }
    DexHistory(Cmd cmd) { this(cmd, null); }
    DexHistory(Cmd cmd, ProgressStore progress) { this.cmd = cmd; this.progress = progress; }
    void review(ChainReview.Store store, long tip, java.util.function.Consumer<String> done) {
        ChainReview review = new ChainReview(cmd, store);
        review.run(tip, () -> done.accept(review.error()));
    }
    String recoveryError() { return recoveryError; }

    private int resume(boolean relevant, Collection<String> wanted) {
        if (progress == null) return 0;
        try { return Math.max(0, progress.offset(relevant, wanted)); }
        catch (RuntimeException e) {
            recoveryError = "Historical search progress could not be read. Existing receipts are retained.";
            return 0;
        }
    }

    /** Walk recent wallet history until every wanted coinid is accounted for, or the budget runs out. */
    public void findSpends(Collection<String> coinids, Cb cb) {
        Set<String> wanted = new HashSet<>();
        if (coinids != null) {
            for (String id : coinids) if (id != null && !id.isEmpty()) wanted.add(id);
        }
        Map<String, Spend> found = new HashMap<>();
        if (wanted.isEmpty()) { cb.onSpends(found); return; }
        new Pager(wanted, found, cb, true, resume(true, wanted)).page(0);
    }

    /** Public tape also needs non-wallet transactions. Same paging and inclusion bounds. */
    public void findMarketSpends(Collection<String> coinids, Cb cb) {
        Set<String> wanted = new HashSet<>();
        if (coinids != null) for (String id : coinids) if (id != null && !id.isEmpty()) wanted.add(id);
        Map<String, Spend> found = new HashMap<>();
        if (wanted.isEmpty()) { cb.onSpends(found); return; }
        new Pager(wanted, found, cb, false, resume(false, wanted)).page(0);
    }

    interface Discovery {
        boolean known(String coinid, String txpowid);
        void found(Order5 order, Spend spend);
    }
    static final String DISCOVERY_CURSOR = "V5_HISTORY_DISCOVERY";

    /** Discover included V5 order spends even if no running book scan ever saw their inputs. */
    void discover(Discovery discovery, Runnable complete) {
        Set<String> marker = new HashSet<>(); marker.add(DISCOVERY_CURSOR);
        new Pager(marker, new HashMap<>(), ignored -> complete.run(), false,
                resume(false, marker), discovery).page(0);
    }

    static Order5 historicalOrder(JSONObject input) {
        if (!paysTo(input, DexContract.ADDR_V5) && !paysTo(input, DexContract.ADDR_V5_MX)) return null;
        Order5 order = Order5.from(input);
        return order != null && order.fillable() ? order : null;
    }

    /** Callback recursion, not a loop: every page is a node round-trip. */
    private final class Pager {
        private final Set<String> wanted;
        private final Map<String, Spend> found;
        private final Cb cb;
        private final boolean relevant;
        private final int startOffset;
        private final Discovery discovery;
        private int discovered;
        private String discoveryError = "";
        private boolean probingHead, done;
        private int pageMax = PAGE_MAX, fetches = 0, skips = 0, checks = 0;
        private final Set<String> checked = new HashSet<>();

        Pager(Set<String> wanted, Map<String, Spend> found, Cb cb, boolean relevant, int startOffset) {
            this(wanted, found, cb, relevant, startOffset, null);
        }

        Pager(Set<String> wanted, Map<String, Spend> found, Cb cb, boolean relevant,
              int startOffset, Discovery discovery) {
            this.discovery = discovery;
            this.relevant = relevant;
            this.startOffset = startOffset;
            this.probingHead = startOffset > 0;
            this.wanted = wanted; this.found = found; this.cb = cb;
        }

        void page(final int offset) {
            if (done) return;
            if (offset < 0 || offset > Integer.MAX_VALUE - PAGE_MAX) { finish(0, true); return; }
            if (fetches++ >= MAX_FETCHES) { finish(offset, false); return; }
            cmd.run("history relevant:" + relevant + " max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) { handle(json, offset); }
                @Override public void onError(String message) {
                    if (NodeApi.ERR_TOO_LONG.equals(message)) bad(offset);
                    else finish(offset, false);
                }
            });
        }

        /** A dropped or over-cap page: halve and ask again for the SAME offset, so nothing is
         *  skipped merely because one page was too big to deliver. */
        void bad(int offset) {
            if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); page(offset); return; }
            if (probingHead) { probingHead = false; page(startOffset); return; }
            if (++skips <= MAX_SKIP) { page(offset + 1); return; }   // one huge txn — step over it
            finish(offset, false);
        }

        void handle(JSONObject json, int offset) {
            if (done) return;
            JSONObject resp = json == null ? null : json.optJSONObject("response");
            JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
            if (!TxValidation.truthy(json, "status") || txpows == null || txpows.length() > pageMax) { finish(offset, false); return; }
            skips = 0;
            inspect(txpows, 0, offset);
        }

        void inspect(JSONArray txpows, int index, int offset) {
            if (done) return;
            for (int j = index; j < txpows.length() && !wanted.isEmpty(); j++) {
                JSONObject tx = txpows.optJSONObject(j);
                if (tx == null) continue;
                String txid = tx.optString("txpowid", "");
                if (!FundingCoins.hex(txid) || checked.contains(txid)) continue;
                JSONArray ins = coinsOf(tx, "inputs");
                boolean relevant = false;
                for (int k = 0; k < ins.length(); k++) {
                    JSONObject in = ins.optJSONObject(k);
                    if (discovery == null) {
                        if (in != null && wanted.contains(in.optString("coinid", ""))) relevant = true;
                    } else {
                        Order5 order = historicalOrder(in);
                        try {
                            if (order != null && !discovery.known(order.coinid, txid)) relevant = true;
                        } catch (RuntimeException e) { discoveryFailed(offset); return; }
                    }
                }
                if (!relevant) continue;
                if (checks++ >= MAX_FETCHES) { finish(offset, false); return; }
                checked.add(txid);
                final int next = j + 1;
                cmd.run("txpow onchain:" + txid, new NodeApi.Cb() {
                    public void onResult(JSONObject reply) {
                        if (done) return;
                        int depth = ChainEvidence.confirmationDepth(reply);
                        if (depth >= 0) {
                            final long proofOrder = ChainEvidence.nextProofOrder();
                            final long proofTimeMs = System.currentTimeMillis();
                            String blockid = ChainEvidence.inclusionBlockId(reply);
                            if (!blockid.isEmpty() && ChainEvidence.inclusionBlock(reply) > 0) {
                                cmd.run("txpow txpowid:" + blockid, new NodeApi.Cb() {
                                    public void onResult(JSONObject blockReply) {
                                        if (done) return;
                                        if (!accept(tx, ins, txid, depth, reply,
                                                ChainEvidence.inclusionTime(reply, blockReply, txid), offset, proofOrder, proofTimeMs)) return;
                                        inspect(txpows, next, offset);
                                    }
                                    public void onError(String message) {
                                        if (done) return;
                                        if (!accept(tx, ins, txid, depth, reply, 0, offset, proofOrder, proofTimeMs)) return;
                                        inspect(txpows, next, offset);
                                    }
                                });
                                return;
                            }
                            if (!accept(tx, ins, txid, depth, reply, 0, offset, proofOrder, proofTimeMs)) return;
                        }
                        inspect(txpows, next, offset);
                    }
                    public void onError(String message) { finish(offset, false); }
                });
                return;
            }
            if (wanted.isEmpty() || txpows.length() < pageMax) { finish(0, true); return; }
            if (probingHead) {
                probingHead = false;
                page(startOffset); // Still inspect new transactions before continuing older history.
            } else page(offset + txpows.length());
        }
        private boolean accept(JSONObject tx, JSONArray ins, String txid, int depth,
                               JSONObject inclusion, long timeMs, int offset, long proofOrder, long proofTimeMs) {
            JSONArray outs = coinsOf(tx, "outputs");
            for (int k = 0; k < ins.length(); k++) {
                JSONObject in = ins.optJSONObject(k);
                String id = in == null ? "" : in.optString("coinid", "");
                Order5 historical = discovery == null ? null : historicalOrder(in);
                boolean deliver = discovery == null && wanted.remove(id);
                if (historical != null) {
                    try { deliver = !discovery.known(id, txid); }
                    catch (RuntimeException e) { discoveryFailed(offset); return false; }
                    if (deliver && discovered >= 32) { finish(offset, false); return false; }
                }
                if (deliver) {
                    Spend spend = new Spend(txid, k, outs, ChainEvidence.transactionId(tx), depth);
                    spend.input = in;
                    spend.inputCount = ins.length();
                    spend.proofOrder = proofOrder;
                    spend.proofTimeMs = proofTimeMs;
                    spend.inclusionBlock = ChainEvidence.inclusionBlock(inclusion);
                    spend.inclusionBlockId = ChainEvidence.inclusionBlockId(inclusion);
                    spend.inclusionTimeMs = timeMs;
                    JSONObject body = tx.optJSONObject("body");
                    JSONObject transaction = body == null ? null : body.optJSONObject("txn");
                    JSONArray state = transaction == null ? null : transaction.optJSONArray("state");
                    if (state != null) spend.transactionState = state;
                    if (discovery == null) found.put(id, spend);
                    else {
                        try { discovery.found(historical, spend); discovered++; }
                        catch (RuntimeException e) { discoveryFailed(offset); return false; }
                    }
                }
            }
            return true;
        }

        private void discoveryFailed(int offset) {
            discoveryError = "Historical discovery could not save its evidence. The unread position will be retried; keep existing app data.";
            finish(offset, false);
        }

        private void finish(int nextOffset, boolean endOfPass) {
            if (done) return;
            done = true;
            // Offsets are not stable IDs: overlap the prior page and cycle at the end. Never
            // interpret a completed traversal, a skipped oversized row, or a miss as non-occurrence.
            int next = endOfPass ? 0 : probingHead ? startOffset : nextOffset;
            if (next > startOffset) next = Math.max(startOffset + 1, next - PAGE_MAX);
            if (progress != null) {
                try {
                    progress.checkpoint(relevant, wanted, found.keySet(), next);
                    recoveryError = discoveryError;
                } catch (RuntimeException e) {
                    // Included proof is still valid; failed progress persistence means repeat work,
                    // not discarded receipts or a callback left hanging forever.
                    recoveryError = "Historical search progress could not be saved. Existing receipts are retained; this search will repeat.";
                }
            }
            cb.onSpends(found);
        }
    }

    // ------------------------------------------------------------------ pure

    static JSONArray coinsOf(JSONObject tx, String which) {
        JSONObject body = tx == null ? null : tx.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        JSONArray arr = txn == null ? null : txn.optJSONArray(which);
        return arr == null ? new JSONArray() : arr;
    }

    /** TxPoW outputs hold state separately in body.txn.state until materialized as UTXOs. */
    static Order5 successorAtInput(Spend spend) {
        return orderOutput(spend, spend == null ? -1 : spend.inputIndex);
    }

    static Order5 orderOutput(Spend spend, int index) {
        if (spend == null || spend.confirmations < 0 || index < 0
                || index >= spend.outputs.length()) return null;
        JSONObject output = spend.outputs.optJSONObject(index);
        if (!TxValidation.truthy(output, "storestate") || !paysTo(output, DexContract.ADDR_V5)) return null;
        try {
            JSONObject coin = new JSONObject(output.toString());
            coin.put("state", spend.transactionState);
            return Order5.from(coin);
        } catch (Exception invalid) { return null; }
    }

    /** The included input must be THIS order; preserve exactly the V5 owner-relock invariants. */
    static Order5 relockSuccessor(Spend spend, Order5 original) {
        if (spend == null || original == null || spend.input == null
                || !original.coinid.equalsIgnoreCase(spend.input.optString("coinid", ""))) return null;
        Order5 next = successorAtInput(spend);
        if (next == null || !original.ownerPk.equalsIgnoreCase(next.ownerPk)
                || !original.wantAddr.equalsIgnoreCase(next.wantAddr)
                || !original.wantTok.equalsIgnoreCase(next.wantTok)
                || !original.lockedTok.equalsIgnoreCase(next.lockedTok)
                || !original.orderId.equalsIgnoreCase(next.orderId)
                || original.sell != next.sell || original.gtc != next.gtc
                || original.locked.compareTo(next.locked) != 0 || original.minRem.compareTo(next.minRem) != 0) return null;
        return next;
    }

    static boolean isMinima(String tok) {
        return tok == null || tok.isEmpty() || tok.equalsIgnoreCase("0x00");
    }

    /** Token coins carry the scaled figure in `tokenamount`; `amount` is the raw colour fraction. */
    static BigDecimal value(JSONObject coin) {
        if (coin == null) return BigDecimal.ZERO;
        if (isMinima(coin.optString("tokenid", "0x00"))) return Util.decOr(coin.optString("amount", "0"), BigDecimal.ZERO);
        String scaled = coin.optString("tokenamount", "");
        return Util.decOr(scaled, BigDecimal.ZERO);
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

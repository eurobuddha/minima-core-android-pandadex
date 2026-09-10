package com.eurobuddha.pandadex;

import org.json.JSONObject;
import java.util.List;

/** Bounded serial rechecks, adapted from PandaPools ActivityLog.verifyNext. A node miss
 * removes current corroboration, not the original receipt and not proof of non-occurrence. */
final class ChainReview {
    static final String CURRENT = "CURRENT", MISSING = "MISSING", RECORDED = "RECORDED";
    static final int LIMIT = 8;
    static final class Conflict extends IllegalStateException {
        Conflict() { super("Updated inclusion evidence requires history reconciliation"); }
    }
    static final class Entry {
        final String txpowid;
        final long revision;
        Entry(String txpowid, long revision) { this.txpowid = txpowid; this.revision = revision; }
    }
    static final class Evidence {
        final String state, blockid, error;
        final int depth;
        final long block;
        final long proofOrder;
        Evidence(String state, int depth, long block, String blockid, String error) {
            this.state=state; this.depth=depth; this.block=block; this.blockid=blockid; this.error=error;
            this.proofOrder = state.isEmpty() ? 0 : ChainEvidence.nextProofOrder();
        }
    }
    interface Store {
        List<Entry> reviewBatch(long tip);
        /** Compare the revision inside the write transaction; stale callbacks cannot overwrite a newer check. */
        void reviewed(Entry entry, Evidence evidence, long checkedAt);
    }
    private final DexHistory.Cmd cmd;
    private final Store store;
    private static boolean running;
    private boolean finished;
    private String error = "";
    String error() { return error; }
    ChainReview(DexHistory.Cmd cmd, Store store) { this.cmd=cmd; this.store=store; }

    void run(long tip, Runnable done) {
        synchronized (ChainReview.class) {
            if (running || tip <= 0) { done.run(); return; }
            running=true;
        }
        try { next(store.reviewBatch(tip),0,done); }
        catch (RuntimeException failure) { error = "Repeat confirmation checks could not complete. Existing receipt evidence is retained."; finish(done); }
    }
    private void next(List<Entry> batch, int index, Runnable done) {
        if (finished) return;
        if (index >= Math.min(LIMIT,batch.size())) { finish(done); return; }
        Entry entry=batch.get(index);
        if (!FundingCoins.hex(entry.txpowid)) {
            store.reviewed(entry,unavailable("Invalid stored TxPoW ID; original evidence retained."),System.currentTimeMillis());
            next(batch,index+1,done); return;
        }
        cmd.run("txpow onchain:"+entry.txpowid,new NodeApi.Cb() {
            private boolean delivered;
            public void onResult(JSONObject reply) { accept(evidence(reply)); }
            public void onError(String error) { accept(unavailable("Node check unavailable; prior evidence retained.")); }
            private void accept(Evidence evidence) {
                if (delivered || finished) return;
                delivered=true;
                try {
                    store.reviewed(entry,evidence,System.currentTimeMillis());
                    // A failed transport/malformed reply must not cause eight consecutive timeouts.
                    if (evidence.state.isEmpty()) finish(done); else next(batch,index+1,done);
                } catch (RuntimeException failure) { error = "Repeat confirmation checks could not complete. Existing receipt evidence is retained."; finish(done); }
            }
        });
    }
    private void finish(Runnable done) {
        if (finished) return;
        finished=true;
        synchronized (ChainReview.class) { running=false; }
        done.run();
    }
    static Evidence unavailable(String message) { return new Evidence("",-1,0,"",message); }
    static Evidence evidence(JSONObject reply) {
        JSONObject r=reply==null?null:reply.optJSONObject("response");
        if (!TxValidation.truthy(reply,"status") || r==null) return unavailable("No valid inclusion reply.");
        Object found=r.opt("found");
        if (Boolean.FALSE.equals(found) || "false".equals(found)) return new Evidence(MISSING,-1,0,"","");
        int depth=ChainEvidence.confirmationDepth(reply);
        long block=ChainEvidence.inclusionBlock(reply);String blockid=ChainEvidence.inclusionBlockId(reply);
        if (depth<0 || block<=0 || blockid.isEmpty()) return unavailable("Incomplete inclusion evidence.");
        return new Evidence(CURRENT,depth,block,blockid,"");
    }
    static boolean accounted(String status) { return status==null || !(status.startsWith("RECHECK_REQUIRED") || status.startsWith("SUPERSEDED_")); }
    static String label(String state, int depth, long checkedAt) {
        if (state == null) return "Legacy record: no usable TxPoW ID for repeat checks";
        String at=checkedAt>0?" (check time "+java.time.Instant.ofEpochMilli(checkedAt)+")":"";
        if (MISSING.equals(state)) return "Not found on this node at last check — retained, excluded from totals"+at;
        if (CURRENT.equals(state)) return depth+" confirmations at last node check"+at;
        return "Stored evidence — awaiting repeat node check";
    }
    static TradeExport.TradeRow aggregatePending(TradeExport.TradeRow row) {
        String status=accounted(row.verificationStatus)?"RECHECK_REQUIRED":row.verificationStatus;
        return new TradeExport.TradeRow(row.spentCoin,row.timeMs,row.block,row.price,row.sizeMinima,
                row.buy,row.maker,row.orderId,row.txpowid,row.sourceKind,row.sourceCoinids,row.proceedsCoinid,status,
                "Aggregate receipt awaits a fresh match of all selected inputs, payout and inclusion time. Earlier evidence is retained and excluded from totals. "
                        +row.verificationNote,row.verifiedBlock);
    }
    static TradeExport.TradeRow decorate(TradeExport.TradeRow row, String state, int depth, long checkedAt, String error) {
        String status=MISSING.equals(state) && accounted(row.verificationStatus)?"RECHECK_REQUIRED":row.verificationStatus;
        String note=row.verificationNote;
        if (state!=null) {
            String check=label(state,depth,checkedAt);
            if (error!=null && !error.isEmpty()) check+=". "+error;
            note=check+". Original evidence: "+note;
        }
        return new TradeExport.TradeRow(row.spentCoin,row.timeMs,row.block,row.price,row.sizeMinima,
                row.buy,row.maker,row.orderId,row.txpowid,row.sourceKind,row.sourceCoinids,row.proceedsCoinid,
                status,note,row.verifiedBlock);
    }

}

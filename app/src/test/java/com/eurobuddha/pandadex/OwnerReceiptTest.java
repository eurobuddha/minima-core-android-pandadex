package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OwnerReceiptTest {
    static void reconcile(Pending p,Order5 order,Pending.Listener listener)throws Exception {
        p.reconcile(new DexHistory(CancellationReceiptTest.history(order,true)),Collections.emptyMap(),120,o->true,listener);
    }
    @Test public void creationCaptureRetainsEveryFundingInputAndOriginalIntent()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();Map<String,DexHistory.Spend> found=CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row));
        OwnerReceipt saved=OwnerReceipt.capture(row,found);JSONObject json=new JSONObject(saved.json);
        assertEquals("CREATED",saved.outcome);assertEquals(2,json.getJSONObject("proof").getJSONArray("inputs").length());
        assertEquals(row.submitMs,json.getJSONObject("expected").getLong("submitMs"));assertEquals(100,saved.block);assertEquals("0xbbaa",saved.blockid);
        assertEquals(0,saved.blockTimeMs);assertTrue(saved.recordedAt>0);assertTrue(saved.matchesIntent(row));
    }
    @Test public void incompleteInclusionCoordinatesOrWrongOutputsCannotArchive()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();Map<String,DexHistory.Spend> found=CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row));
        DexHistory.Spend first=found.get(CreationEvidence.inputs(row).get(0));first.inclusionBlock=0;
        assertThrows(IllegalArgumentException.class,()->OwnerReceipt.capture(row,found));first.inclusionBlock=100;first.outputs.getJSONObject(0).put("amount","99");
        assertThrows(IllegalArgumentException.class,()->OwnerReceipt.capture(row,found));
    }
    @Test public void oversizedEvidenceNeverDropsThePendingReceipt()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();Map<String,DexHistory.Spend> found=CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row));
        found.get(CreationEvidence.inputs(row).get(0)).input.put("metadata","x".repeat(TakerReceipt.MAX_BYTES));
        assertThrows(IllegalArgumentException.class,()->OwnerReceipt.capture(row,found));
    }
    @Test public void completedArchiveExistsBeforeAnySuccessNotification()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);Pending.Row row=PendingIntentConsistencyTest.cancel(m);
        reconcile(p,RelockReceiptTest.order(),new Pending.Listener(){public void onLive(Pending.Row r){fail();}public void onSettled(Pending.Row r){
            assertEquals("CANCELLED",m.completed.get(row.receiptId).outcome);assertTrue(new Pending(m).rows().isEmpty());
        }});assertEquals(1,m.completed.size());
    }
    @Test public void failedArchiveKeepsPendingAndSuppressesSuccess()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);PendingIntentConsistencyTest.cancel(m);String original=m.data;m.archiveFail=true;
        assertThrows(IllegalStateException.class,()->reconcile(p,RelockReceiptTest.order(),CancellationReceiptTest.settled(new AtomicInteger())));
        assertEquals(original,m.data);assertTrue(m.completed.isEmpty());
    }
    @Test public void failedCleanupReplaysIdempotentlyAndPreservesFirstArchivedTime()throws Exception {
        class Memory extends PendingRecoveryTest.Memory {boolean cleanupFail;public boolean write(String next){return cleanupFail?false:super.write(next);}}
        Memory m=new Memory();Pending p=new Pending(m);Pending.Row row=PendingIntentConsistencyTest.cancel(m);m.cleanupFail=true;
        assertThrows(IllegalStateException.class,()->reconcile(p,RelockReceiptTest.order(),CancellationReceiptTest.settled(new AtomicInteger())));
        assertEquals(1,m.completed.size());assertEquals(1,p.rows().size());String archived=m.completed.get(row.receiptId).json;
        m.cleanupFail=false;Pending.Row current=p.rows().get(0);current.phase="SUBMITTED";current.postedId="0xabcd";p.add(current);
        AtomicInteger count=new AtomicInteger();reconcile(new Pending(m),RelockReceiptTest.order(),CancellationReceiptTest.settled(count));
        assertEquals(1,count.get());assertTrue(p.rows().isEmpty());assertEquals(archived,m.completed.get(row.receiptId).json);
    }
    @Test public void changedIntentDuringVerificationCannotBeRemovedByStaleProof()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);PendingIntentConsistencyTest.cancel(m);
        assertThrows(IllegalStateException.class,()->p.reconcile(new DexHistory(CancellationReceiptTest.history(RelockReceiptTest.order(),true)),Collections.emptyMap(),120,o->{
            Pending.Row current=p.rows().get(0);current.minima=new BigDecimal("777");p.add(current);return true;
        },CancellationReceiptTest.settled(new AtomicInteger())));
        assertTrue(m.completed.isEmpty());assertEquals(new BigDecimal("777"),p.rows().get(0).minima);
    }
    @Test public void unconfiguredArchiveCannotSilentlyDiscardVerifiedReceipt()throws Exception {
        final String[] raw={"[]"};Pending p=new Pending(new Pending.Store(){public String read(){return raw[0];}public boolean write(String json){raw[0]=json;return true;}});
        Pending.Row row=CreationEvidenceTest.receipt();p.add(row);String original=raw[0];
        assertThrows(IllegalStateException.class,()->CreationEvidenceTest.reconcile(p,row,PendingRecoveryTest.listener(()->fail(),()->{})));assertEquals(original,raw[0]);
    }
    @Test public void listenerFailureCannotEraseArchivedEvidence()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);Pending.Row row=CreationEvidenceTest.receipt();p.add(row);
        assertThrows(IllegalStateException.class,()->CreationEvidenceTest.reconcile(p,row,PendingRecoveryTest.listener(()->{throw new IllegalStateException("view gone");},()->{})));
        assertTrue(p.rows().isEmpty());assertEquals(1,m.completed.size());
    }
    @Test public void laterMutationOfHistoryObjectsCannotChangeCapturedProof()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();Map<String,DexHistory.Spend> found=CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row));
        OwnerReceipt saved=OwnerReceipt.capture(row,found);DexHistory.Spend source=found.get(CreationEvidence.inputs(row).get(0));
        source.inclusionBlock=999;source.inclusionBlockId="0xeeee";source.outputs.getJSONObject(0).put("amount","999");
        assertEquals(100,saved.spend.inclusionBlock);assertEquals("0xbbaa",saved.spend.inclusionBlockId);
        assertEquals("100",saved.spend.outputs.getJSONObject(0).getString("amount"));
    }
    @Test public void statusShowsOnlyReturnedDepthAndFlagsMissingOrMovedInclusion() {
        OwnerReceipt.Entry current=new OwnerReceipt.Entry("CANCELLED","0xaa",100,"0xbb",0,50,ChainReview.CURRENT,8,60,100,"0xbb");
        assertTrue(current.current());assertEquals("On-chain · 8 confirmations",current.status());
        OwnerReceipt.Entry missing=new OwnerReceipt.Entry("CANCELLED","0xaa",100,"0xbb",0,50,ChainReview.MISSING,-1,60,100,"0xbb");assertFalse(missing.current());assertTrue(missing.status().contains("not found"));
        OwnerReceipt.Entry moved=new OwnerReceipt.Entry("CANCELLED","0xaa",100,"0xbb",0,50,ChainReview.CURRENT,9,60,101,"0xcc");assertFalse(moved.current());assertTrue(moved.status().contains("reconciliation"));
    }
}

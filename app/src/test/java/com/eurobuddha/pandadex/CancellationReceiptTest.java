package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CancellationReceiptTest {
    static final class Memory extends PendingRecoveryTest.Memory {int writes;public boolean write(String s){writes++;return super.write(s);}}
    static Order5 order(String coin)throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();raw.put("coinid",coin);return Order5.from(raw);
    }
    static DexTxn.Result callback(){return CreationEvidenceTest.callback();}
    static DexTxn.Result batch(Pending p)throws Exception{return p.cancellationResult(Arrays.asList(order("0xaa"),order("0xbb")),100,callback());}
    static HistoryProgressTest.Node history(Order5 order,boolean confirmed)throws Exception {
        HistoryProgressTest.Node n=new HistoryProgressTest.Node();n.unconfirmed=!confirmed;
        JSONObject refund=new TestJson().put("address",order.wantAddr).put("tokenid",order.lockedTok)
                .put("amount",order.locked.toPlainString()).put("storestate",false);
        n.txs.add(new TestJson().put("txpowid","0xabcd").put("body",new TestJson().put("txn",new TestJson()
                .put("inputs",new JSONArray().put(new JSONObject(order.sourceJson())))
                .put("outputs",new JSONArray().put(refund)))));return n;
    }
    static Pending.Listener settled(AtomicInteger count){return new Pending.Listener(){
        public void onLive(Pending.Row r){fail("cancel cannot create order");}
        public void onSettled(Pending.Row r){count.incrementAndGet();}
        public void onGaveUp(Pending.Row r){}
    };}
    @Test public void entireBatchIsSavedOnceBeforeAnySigning()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);assertTrue(cb.onPrepared("cancel_fixture"));
        assertEquals(1,m.writes);List<Pending.Row> rows=new Pending(m).rows();assertEquals(2,rows.size());
        for(Pending.Row r:rows){assertEquals("PREPARED",r.phase);assertEquals("cancel_fixture",r.transactionHandle);
            assertEquals(100,r.submitBlock);assertTrue(Pending.cancelSourceMatches(r,order(r.coinid)));assertTrue(r.status(101).contains("intent saved"));}
        assertTrue(cb.beforePost());for(Pending.Row r:new Pending(m).rows())assertEquals("POSTING",r.phase);
    }
    @Test public void failedPrepareAndPostingCommitsRefuseDependentWork()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);m.fail=true;
        assertFalse(cb.onPrepared("cancel_fixture"));assertFalse(cb.beforePost());assertEquals("[]",m.data);
        m.fail=false;cb=batch(p);assertTrue(cb.onPrepared("cancel_next"));m.fail=true;assertFalse(cb.beforePost());
        for(Pending.Row r:new Pending(m).rows())assertEquals("PREPARED",r.phase);
    }
    @Test public void lostReplyStillAllowsLinkedRefundRecoveryAfterRestart()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(order("0xaa")),100,callback());
        assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());
        Pending restarted=new Pending(m);AtomicInteger count=new AtomicInteger();
        restarted.reconcile(new DexHistory(history(order("0xaa"),true)),Collections.emptyMap(),120,o->true,settled(count));
        assertEquals(1,count.get());assertTrue(restarted.rows().isEmpty());cb.onPosted("0xabcd");assertTrue(restarted.rows().isEmpty());
    }
    @Test public void unconfirmedRefundNeverResolvesRetainedCancellation()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(order("0xaa")),100,callback());
        assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());AtomicInteger count=new AtomicInteger();
        p.reconcile(new DexHistory(history(order("0xaa"),false)),Collections.emptyMap(),120,o->true,settled(count));
        assertEquals(0,count.get());assertEquals(1,p.rows().size());
    }
    @Test public void mismatchedOriginalFundingCannotVerifyARefund()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(order("0xaa")),100,callback());
        assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());
        JSONObject wrong=new JSONObject(order("0xaa").sourceJson());wrong.put("amount","99");Order5 changed=Order5.from(wrong);
        AtomicInteger count=new AtomicInteger();p.reconcile(new DexHistory(history(changed,true)),Collections.emptyMap(),120,o->true,settled(count));
        assertEquals(0,count.get());assertEquals(1,p.rows().size());
    }
    @Test public void failedAcceptedStatusWriteRetainsPostingAndCompletesCallbackOnce()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);AtomicInteger count=new AtomicInteger();
        DexTxn.Result cb=p.cancellationResult(Collections.singletonList(order("0xaa")),100,new DexTxn.Result(){
            public void onPosted(String id){count.incrementAndGet();}public void onFailed(String error){fail("duplicate callback");}});
        assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());m.fail=true;cb.onPosted("0xabcd");
        cb.onPosted("0xabcd");cb.onFailed("late");assertEquals(1,count.get());assertEquals("POSTING",new Pending(m).rows().get(0).phase);
    }
    @Test public void lateBatchReplyUpdatesOnlyStillUnresolvedSources()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());
        p.reconcile(new DexHistory(history(order("0xaa"),true)),Collections.emptyMap(),120,o->true,settled(new AtomicInteger()));
        assertEquals(1,p.rows().size());assertEquals("0xbb",p.rows().get(0).coinid);cb.onPosted("0xabcd");
        assertEquals(1,p.rows().size());assertEquals("SUBMITTED",p.rows().get(0).phase);assertEquals("0xabcd",p.rows().get(0).postedId);
    }
    @Test public void preSubmitFailureIsRetainedWithoutPretendingToAwaitMining()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);assertTrue(cb.onPrepared("cancel_fixture"));cb.onFailed("input locked");
        for(Pending.Row r:p.rows()){assertEquals("NOT_SUBMITTED",r.phase);assertTrue(r.status(101).contains("No transaction was sent"));}
        HistoryProgressTest.Node n=history(order("0xaa"),true);p.reconcile(new DexHistory(n),Collections.emptyMap(),120,o->true,settled(new AtomicInteger()));
        assertTrue(n.calls.isEmpty());assertEquals(2,p.rows().size());
    }
    @Test public void unknownResultPreservesEverySourceAndDoesNotReplay()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);assertTrue(cb.onPrepared("cancel_fixture"));assertTrue(cb.beforePost());
        cb.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);assertFalse(cb.beforePost());
        for(Pending.Row r:new Pending(m).rows()){assertEquals("UNKNOWN",r.phase);assertTrue(r.status(101).contains("outcome unknown"));}
    }
    @Test public void missingBatchReceiptPreventsPostingTheWholeBatch()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);assertTrue(cb.onPrepared("cancel_fixture"));
        JSONArray rows=new JSONArray(m.data);m.data=new JSONArray().put(rows.getJSONObject(0)).toString();
        assertFalse(cb.beforePost());assertEquals("PREPARED",p.rows().get(0).phase);
    }
    @Test public void corruptStoreAndDuplicateSourcesCannotCreateAPartialJournal()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=batch(p);m.data="broken original";
        assertFalse(cb.onPrepared("cancel_fixture"));assertEquals("broken original",m.data);
        Order5 o=order("0xaa");assertThrows(IllegalArgumentException.class,()->p.cancellationResult(Arrays.asList(o,o),100,callback()));
    }
    @Test public void callerRefusalIsRespectedBeforePosting()throws Exception {
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(order("0xaa")),100,new DexTxn.Result(){
            public boolean beforePost(){return false;}public void onPosted(String id){fail("refused post");}public void onFailed(String e){}});
        assertTrue(cb.onPrepared("cancel_fixture"));assertFalse(cb.beforePost());assertEquals("PREPARED",p.rows().get(0).phase);
    }
    @Test public void realCancelEntryPointsRefuseMissingReceiptStorageBeforeNodeAccess()throws Exception {
        AtomicInteger errors=new AtomicInteger();DexTxn.Result cb=new DexTxn.Result(){public void onPosted(String id){fail("no store");}
            public void onFailed(String e){assertTrue(e.contains("receipts"));errors.incrementAndGet();}};
        DexTxn tx=new DexTxn(null,null);tx.cancel(order("0xaa"),cb);tx.cancelBatch(Arrays.asList(order("0xaa"),order("0xbb")),cb);
        assertEquals(2,errors.get());
    }
    @Test public void tokenMetadataDoesNotInflateCancellationEvidence()throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();raw.put("token",new TestJson().put("name","x".repeat(2_000_000)));
        raw.getJSONObject("state").put("99","y".repeat(100_000));Order5 source=Order5.from(raw);
        Memory m=new Memory();Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(source),100,callback());
        assertTrue(cb.onPrepared("cancel_fixture"));Pending.Row row=p.rows().get(0);
        assertTrue(row.cancelSource.length()<2048);assertTrue(m.data.length()<4096);assertTrue(Pending.cancelSourceMatches(row,source));
        assertFalse(new JSONObject(row.cancelSource).has("token"));assertFalse(new JSONObject(row.cancelSource).getJSONObject("state").has("99"));
    }

}

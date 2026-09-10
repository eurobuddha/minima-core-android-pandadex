package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RelockReceiptTest {
    static Order5 order() {return Order5.from(TransactionHardeningTest.orderCoin());}
    static DexHistory.Spend proof(Order5 source,String want,int depth)throws Exception {
        JSONObject out=new JSONObject(source.sourceJson()).put("coinid","0xee").put("address",DexContract.ADDR_V5).put("storestate",true).put("state",new JSONArray());
        JSONObject state=new JSONObject(source.sourceJson()).getJSONObject("state");state.put("2",want);
        JSONArray states=new JSONArray();for(Iterator<String> it=state.keys();it.hasNext();){String k=it.next();states.put(new TestJson().put("port",Integer.parseInt(k)).put("data",state.getString(k)));}
        DexHistory.Spend spend=new DexHistory.Spend("0xabcd",0,new JSONArray().put(out),"0xbbaa",depth);
        spend.input=new JSONObject(source.sourceJson());spend.transactionState=states;return spend;
    }
    static Pending.Row prepared(Pending p,Order5 source,String wanted)throws Exception {
        DexTxn.Result cb=p.relockResult(source,new BigDecimal(wanted),100,CreationEvidenceTest.callback());assertTrue(cb.onPrepared("relock_fixture"));return p.rows().get(0);
    }
    @Test public void exactWantedAmountSurvivesRoundedDisplayPrice()throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin().put("amount","999999999");Order5 source=Order5.from(raw);
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending.Row row=prepared(new Pending(m),source,"1.00000001");
        assertEquals("1.00000001",row.editWant);assertTrue(Pending.editMatches(row,proof(source,"1.00000001",3),source));
        assertFalse(Pending.editMatches(row,proof(source,"1.00000002",3),source));
        assertNotEquals(new BigDecimal(row.editWant),PriceMath.up(source.locked.multiply(row.price,PriceMath.MC),PriceMath.USDT_DP));
    }
    @Test public void validBuyRelockCanVerifyEvenWhenDisplayPriceRoundsToZero()throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin().put("tokenid",DexContract.USDT_ID).put("tokenamount","0.00000001");
        raw.getJSONObject("state").put("3","0x00").put("5","0").put("8","0.00000001");Order5 source=Order5.from(raw);
        Pending.Row row=prepared(new Pending(new PendingRecoveryTest.Memory()),source,"999999999");assertEquals(0,row.price.signum());
        assertTrue(Pending.editMatches(row,proof(source,"999999999",3),source));
    }
    @Test public void lostRelockReplyResolvesFromIncludedExactSuccessorAfterRestart()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);Order5 source=order();
        DexTxn.Result cb=p.relockResult(source,new BigDecimal("2"),100,CreationEvidenceTest.callback());
        assertTrue(cb.onPrepared("relock_fixture"));assertTrue(cb.beforePost());DexHistory.Spend spend=proof(source,"2",3);
        HistoryProgressTest.Node n=new HistoryProgressTest.Node();n.txs.add(new TestJson().put("txpowid","0xabcd").put("body",new TestJson().put("txn",new TestJson()
                .put("inputs",new JSONArray().put(spend.input)).put("outputs",spend.outputs).put("state",spend.transactionState))));
        AtomicInteger settled=new AtomicInteger();Pending restarted=new Pending(m);
        restarted.reconcile(new DexHistory(n),Collections.emptyMap(),120,o->true,CancellationReceiptTest.settled(settled));
        assertEquals(1,settled.get());assertTrue(restarted.rows().isEmpty());cb.onPosted("0xabcd");assertTrue(restarted.rows().isEmpty());
    }
    @Test public void renewalRetainsUnchangedExactWantAndReportsRenewal()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending.Row row=prepared(new Pending(m),order(),"1");
        assertTrue(row.isRenewal());assertTrue(Pending.editMatches(row,proof(order(),"1",3),order()));
        assertFalse(Pending.editMatches(row,proof(order(),"1.00000001",3),order()));
        row.editSource="bad";assertFalse(row.isRenewal());assertFalse(Pending.editMatches(row,proof(order(),"1",3),order()));
    }
    @Test public void incompleteModernIntentNeverFallsBackToLegacyPrice()throws Exception {
        Pending.Row row=prepared(new Pending(new PendingRecoveryTest.Memory()),order(),"2");DexHistory.Spend spend=proof(order(),"2",3);
        String source=row.editSource;row.editSource="";assertFalse(Pending.editMatches(row,spend,order()));
        row.editSource=source;row.editWant="";assertFalse(Pending.editMatches(row,spend,order()));
        row.editWant="garbage";assertFalse(Pending.editMatches(row,spend,order()));
    }
    @Test public void editedSourceAndUnconfirmedSuccessorCannotConfirm()throws Exception {
        Order5 source=order();Pending.Row row=prepared(new Pending(new PendingRecoveryTest.Memory()),source,"2");
        assertFalse(Pending.editMatches(row,proof(source,"2",-1),source));
        JSONObject changed=new JSONObject(source.sourceJson()).put("amount","99");Order5 other=Order5.from(changed);
        assertFalse(Pending.editMatches(row,proof(other,"2",3),other));
    }
    @Test public void failedRelockJournalWritesPreventSigningAndPosting()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);m.fail=true;
        DexTxn.Result cb=p.relockResult(order(),new BigDecimal("2"),100,CreationEvidenceTest.callback());assertFalse(cb.onPrepared("relock_fixture"));assertFalse(cb.beforePost());
        m.fail=false;cb=p.relockResult(order(),new BigDecimal("2"),100,CreationEvidenceTest.callback());assertTrue(cb.onPrepared("relock_second"));
        m.fail=true;assertFalse(cb.beforePost());assertEquals("PREPARED",new Pending(m).rows().get(0).phase);
    }
    @Test public void actualRelockAndRenewalEntryPointRequiresReceiptStoreBeforeNodeAccess() {
        AtomicInteger failed=new AtomicInteger();DexTxn.Result cb=new DexTxn.Result(){public void onPosted(String id){fail("no store");}
            public void onFailed(String error){assertTrue(error.contains("receipt"));failed.incrementAndGet();}};
        DexTxn tx=new DexTxn(null,null);tx.relock(order(),new BigDecimal("2"),cb);tx.relock(order(),null,cb);assertEquals(2,failed.get());
    }
    @Test public void partialModernIntentIsNotRetiredByACompetingRefund()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);prepared(p,order(),"2");
        JSONArray saved=new JSONArray(m.data);saved.getJSONObject(0).put("editSource","");m.data=saved.toString();
        AtomicInteger outcomes=new AtomicInteger();Pending.Listener l=new Pending.Listener(){
            public void onLive(Pending.Row r){outcomes.incrementAndGet();}public void onSettled(Pending.Row r){outcomes.incrementAndGet();}
            public void onCancelledInstead(Pending.Row r){outcomes.incrementAndGet();}public void onGaveUp(Pending.Row r){}
        };
        p.reconcile(new DexHistory(CancellationReceiptTest.history(order(),true)),Collections.emptyMap(),120,o->true,l);
        assertEquals(0,outcomes.get());assertEquals(1,p.rows().size());
    }

}

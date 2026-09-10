package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PendingIntentConsistencyTest {
    static Pending.Row cancel(PendingRecoveryTest.Memory m)throws Exception {
        Pending p=new Pending(m);DexTxn.Result cb=p.cancellationResult(Collections.singletonList(RelockReceiptTest.order()),100,CreationEvidenceTest.callback());
        assertTrue(cb.onPrepared("cancel_consistency"));assertTrue(cb.beforePost());return p.rows().get(0);
    }
    static Pending.Row edit(PendingRecoveryTest.Memory m)throws Exception{return RelockReceiptTest.prepared(new Pending(m),RelockReceiptTest.order(),"2");}
    static void store(PendingRecoveryTest.Memory m,JSONObject row)throws Exception{m.data=new JSONArray().put(row).toString();}
    static void refused(JSONObject row)throws Exception{PendingIntegrityTest.refusesWithoutRewrite(new JSONArray().put(row).toString());}
    @Test public void modernCancellationCannotForgetItsOriginalFundingExpectation()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending.Row row=cancel(m);JSONObject damaged=row.json();damaged.remove("cancelSource");store(m,damaged);String original=m.data;
        Order5 changed=Order5.from(new JSONObject(RelockReceiptTest.order().sourceJson()).put("amount","99"));AtomicInteger settled=new AtomicInteger();
        new Pending(m).reconcile(new DexHistory(CancellationReceiptTest.history(changed,true)),Collections.emptyMap(),120,o->true,CancellationReceiptTest.settled(settled));
        assertEquals(0,settled.get());assertEquals(original,m.data);assertFalse(new Pending(m).healthy());
    }
    @Test public void modernEditCannotUseRoundedLegacyPriceAfterExactFieldsDisappear()throws Exception {
        Pending.Row row=edit(new PendingRecoveryTest.Memory());row.editSource="";row.editWant="";
        assertFalse(Pending.editMatches(row,RelockReceiptTest.proof(RelockReceiptTest.order(),"2",3),RelockReceiptTest.order()));
    }
    @Test public void missingModernEditFieldsCannotRetireForACompetingRefund()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending.Row row=edit(m);JSONObject damaged=row.json();damaged.remove("editSource");damaged.remove("editWant");store(m,damaged);String original=m.data;
        AtomicInteger count=new AtomicInteger();Pending.Listener listener=new Pending.Listener(){public void onLive(Pending.Row r){count.incrementAndGet();}public void onSettled(Pending.Row r){count.incrementAndGet();}public void onCancelledInstead(Pending.Row r){count.incrementAndGet();}};
        new Pending(m).reconcile(new DexHistory(CancellationReceiptTest.history(RelockReceiptTest.order(),true)),Collections.emptyMap(),120,o->true,listener);
        assertEquals(0,count.get());assertEquals(original,m.data);
    }
    @Test public void eachSurvivingJournalMarkerPreventsLegacyDowngrade()throws Exception {
        for(String field:new String[]{"phase","transactionHandle","postedId"}) {
            Pending.Row row=PendingRecoveryTest.row(RelockReceiptTest.order().orderId);row.kind=Pending.CANCEL;row.coinid=RelockReceiptTest.order().coinid;
            if(field.equals("phase"))row.phase="UNKNOWN";if(field.equals("transactionHandle"))row.transactionHandle="cancel_fixture";if(field.equals("postedId"))row.postedId="0xabcd";
            assertFalse(Pending.cancelSourceMatches(row,RelockReceiptTest.order()));refused(row.json());
        }
    }
    @Test public void crossKindPayloadsAreNotValidIntentRecords()throws Exception {
        Pending.Row row=cancel(new PendingRecoveryTest.Memory());
        refused(row.json().put("kind",Pending.PLACE));refused(row.json().put("kind",Pending.EDIT));
        refused(row.json().put("editWant","2"));refused(row.json().put("creation",new JSONObject()));
        Pending.Row edit=edit(new PendingRecoveryTest.Memory());refused(edit.json().put("cancelSource",row.cancelSource));
    }
    @Test public void journalledCreationCannotBecomeAnOlderReceiptByLosingEvidence()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();row.phase="PREPARED";row.transactionHandle="create_fixture";JSONObject damaged=row.json();damaged.remove("creation");refused(damaged);
    }
    @Test public void savedOwnerSourceMustBeCompleteJsonAndMatchReceiptIdentity()throws Exception {
        Pending.Row row=cancel(new PendingRecoveryTest.Memory());
        for(String suffix:new String[]{" trailing"," /*hidden*/"," []"})refused(row.json().put("cancelSource",row.cancelSource+suffix));
        refused(row.json().put("orderId","0xffff"));refused(row.json().put("coinid","0xffff"));refused(row.json().put("buy",!row.buy));
    }
    @Test public void exactEditAmountAndSourceMustBePresentTogetherAndUsable()throws Exception {
        Pending.Row row=edit(new PendingRecoveryTest.Memory());
        for(String amount:new String[]{"","garbage","0","-1","0.000000001"})refused(row.json().put("editWant",amount));
        refused(row.json().put("editSource",""));refused(row.json().put("editSource","{}"));
    }
    @Test public void invalidNewIntentCannotBeCommittedAsHealthy()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending p=new Pending(m);Pending.Row row=cancel(m);String original=m.data;row.cancelSource="";
        assertThrows(IllegalStateException.class,()->p.add(row));assertEquals(original,m.data);assertTrue(p.healthy());
    }
    @Test public void originalLegacyOwnerReceiptsRetainTheirExistingProofPaths()throws Exception {
        Order5 order=RelockReceiptTest.order();Pending.Row row=PendingRecoveryTest.row(order.orderId);row.kind=Pending.CANCEL;row.coinid=order.coinid;
        assertTrue(Pending.cancelSourceMatches(row,order));PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();new Pending(m).add(row);assertTrue(new Pending(m).healthy());
        row.kind=Pending.EDIT;row.price=new BigDecimal("0.02");assertTrue(Pending.editMatches(row,RelockReceiptTest.proof(order,"2",3),order));
        new Pending(m).add(row);assertTrue(new Pending(m).healthy());
    }
}

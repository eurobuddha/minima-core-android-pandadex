package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class DexProcessorRecoveryTest {
    static Order5 order(){return Order5.from(TransactionHardeningTest.orderCoin());}
    static long due(){return order().created+DexContract.RENEW_AT;}
    static long expired(){return order().created+DexContract.EXPIRY_BLOCKS+1;}
    static final class Notices implements DexProcessor.Listener {
        int failed,posted;final List<String> paused=new ArrayList<>();
        public void onRenewed(Order5 o){posted++;}public void onRenewFailed(Order5 o,String why){failed++;}
        public void onPaused(String why){paused.add(why);}
    }
    static final class Txn extends DexTxn {
        final Pending journal;int renews,refunds;String mode="unknown";Result held;
        Txn(Pending p){super(null,null);journal=p;}
        public void relock(Order5 o,BigDecimal want,Result cb){renews++;dispatch(journal.relockResult(o,o.wantAmt,due(),cb));}
        public void collectExpired(Order5 o,Result cb){refunds++;dispatch(journal.cancellationResult(Collections.singletonList(o),expired(),cb));}
        void dispatch(Result receipt){
            held=receipt;assertTrue(receipt.onPrepared("upkeep_"+(renews+refunds)));
            if(mode.equals("not")){receipt.onFailed("stopped before posting");return;}
            assertTrue(receipt.beforePost());
            if(mode.equals("unknown"))receipt.onFailed("reply lost");
            if(mode.equals("posted"))receipt.onPosted("0xeeee");
        }
    }
    static final class Fixture {
        final MakerWithdrawalDurabilityTest.Memory markers=new MakerWithdrawalDurabilityTest.Memory();
        final PendingRecoveryTest.Memory receipts=new PendingRecoveryTest.Memory();
        final Pending pending=new Pending(receipts);final Txn tx=new Txn(pending);final Notices notices=new Notices();
        DexProcessor processor=new DexProcessor(markers.prefs(),pending,tx);
        void run(long block){run(Collections.singletonMap(order().coinid,order()),block);}
        void run(Map<String,Order5> book,long block){processor.process(book,Collections.singleton(order().ownerPk),Collections.singleton(order().wantAddr),Collections.emptySet(),block,notices);}
        void restart(){markers.visible.clear();markers.visible.putAll(markers.disk);processor=new DexProcessor(markers.prefs(),new Pending(receipts),tx);}
    }
    @Test public void lostRenewalReplyPreventsRetryPastSixBlocksAndAfterRestart(){
        Fixture f=new Fixture();f.run(due());assertEquals(1,f.tx.renews);assertEquals("UNKNOWN",f.pending.rows().get(0).phase);
        f.run(due()+20);f.restart();f.run(due()+30);assertEquals(1,f.tx.renews);assertEquals(1,f.pending.rows().size());assertFalse(f.notices.paused.isEmpty());
    }
    @Test public void acceptedReceiptSurvivesPacingExpiryAndTemporaryBookAbsence(){
        Fixture f=new Fixture();f.tx.mode="posted";f.run(due());assertEquals(1,f.notices.posted);
        f.run(Collections.emptyMap(),due()+1);assertTrue(f.markers.disk.isEmpty());assertEquals("SUBMITTED",f.pending.rows().get(0).phase);
        f.run(due()+20);assertEquals(1,f.tx.renews);assertEquals(1,f.pending.rows().size());
    }
    @Test public void definitivelyUnsubmittedAttemptMayRetry(){
        Fixture f=new Fixture();f.tx.mode="not";f.run(due());assertEquals("NOT_SUBMITTED",f.pending.rows().get(0).phase);
        f.tx.mode="unknown";f.run(due()+1);assertEquals(2,f.tx.renews);assertEquals(2,f.pending.rows().size());
    }
    @Test public void pendingOwnerCancellationBlocksAutomaticRenewalAndExpiredRefund(){
        Fixture f=new Fixture();DexTxn.Result cb=f.pending.cancellationResult(Collections.singletonList(order()),due(),CreationEvidenceTest.callback());
        assertTrue(cb.onPrepared("cancel_pending"));assertTrue(cb.beforePost());f.run(due());f.run(expired());
        assertEquals(0,f.tx.renews);assertEquals(0,f.tx.refunds);assertEquals(1,f.notices.paused.size());
    }
    @Test public void lostExpiredRefundReplyIsJournalledAndNotRetriedAfterRestart(){
        Fixture f=new Fixture();f.run(expired());assertEquals(1,f.tx.refunds);assertEquals(Pending.CANCEL,f.pending.rows().get(0).kind);
        assertEquals("UNKNOWN",f.pending.rows().get(0).phase);f.restart();f.run(expired()+20);assertEquals(1,f.tx.refunds);
    }
    @Test public void failedMarkerCommitPreventsAnyTransaction(){
        Fixture f=new Fixture();f.markers.failAt=1;f.run(due());assertEquals(0,f.tx.renews);assertTrue(f.pending.rows().isEmpty());
        assertTrue(f.markers.disk.isEmpty());assertEquals(1,f.notices.paused.size());
    }
    @Test public void failedCallbackMarkerRemovalRetainsUnknownReceiptAndDoesNotCrash(){
        Fixture f=new Fixture();f.markers.failAt=2;f.run(due());assertEquals(1,f.tx.renews);assertEquals("UNKNOWN",f.pending.rows().get(0).phase);
        assertEquals(1,f.notices.failed);assertEquals(1,f.notices.paused.size());f.restart();f.run(due()+20);assertEquals(1,f.tx.renews);
    }
    @Test public void unreadableReceiptsOrMarkerTypesPauseWithoutOverwriting(){
        Fixture f=new Fixture();f.receipts.data="broken";f.run(due());assertEquals("broken",f.receipts.data);assertEquals(0,f.tx.renews);
        Fixture g=new Fixture();g.markers.visible.put(order().coinid,true);g.markers.disk.putAll(g.markers.visible);g.run(due());
        assertEquals(Boolean.TRUE,g.markers.disk.get(order().coinid));assertEquals(0,g.tx.renews);assertEquals(0,g.markers.writes);
    }
    @Test public void legacyAndMalformedOwnerReceiptsDoNotAllowBlindRetry()throws Exception{
        Fixture f=new Fixture();Pending.Row r=PendingRecoveryTest.row(order().orderId);r.kind=Pending.EDIT;r.coinid=order().coinid.toUpperCase(java.util.Locale.ROOT).replace("0X","0x");
        f.pending.add(r);f.run(due());assertEquals(0,f.tx.renews);
        JSONArray rows=new JSONArray(f.receipts.data);rows.getJSONObject(0).put("coinid","");f.receipts.data=rows.toString();f.run(due()+1);
        assertEquals(0,f.tx.renews);assertEquals(1,f.pending.rows().size());
    }
    @Test public void otherCoinsCanRenewWithExistingLimitsAndBothOwnershipFactors()throws Exception{
        Fixture f=new Fixture();Map<String,Order5> book=new LinkedHashMap<>();
        for(int i=0;i<4;i++){JSONObject raw=TransactionHardeningTest.orderCoin().put("coinid","0xa"+i);raw.getJSONObject("state").put("4","0xc"+i);Order5 o=Order5.from(raw);book.put(o.coinid,o);}
        f.run(book,due());assertEquals(2,f.tx.renews);
        Fixture g=new Fixture();g.processor.process(book,Collections.singleton(order().ownerPk),Collections.emptySet(),Collections.emptySet(),due(),g.notices);
        assertEquals(0,g.tx.renews);
        Fixture h=new Fixture();h.processor.process(Collections.singletonMap(order().coinid,order()),Collections.singleton(order().ownerPk),Collections.singleton(order().wantAddr),Collections.singleton(order().orderId),due(),h.notices);
        assertEquals(0,h.tx.renews);
    }
    @Test public void realExpiredRefundEntryPointRequiresReceiptStoreBeforeNodeAccess(){
        int[] failed={0};new DexTxn(null,null).collectExpired(order(),new DexTxn.Result(){public void onPosted(String id){fail("no receipt store");}
            public void onFailed(String message){assertTrue(message.contains("receipt"));failed[0]++;}});assertEquals(1,failed[0]);
    }
}

package com.eurobuddha.pandadex;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiptPresentationTest {
    static Pending.Row receipt(String kind,String phase,long time) {
        Pending.Row r=PendingRecoveryTest.row("0xaa");r.kind=kind;r.phase=phase;r.coinid="0xCAFE";r.submitMs=time;return r;
    }
    @Test public void recentLegacyOwnerReceiptDoesNotPromiseTheNextBlock() {
        for(String kind:new String[]{Pending.CANCEL,Pending.EDIT}) {
            String status=receipt(kind,"",System.currentTimeMillis()).status(100);
            assertTrue(status,status.contains("Submission outcome unknown"));
            assertFalse(status.contains("next block"));assertFalse(status.contains("~50s"));
        }
    }
    @Test public void missingOrFutureReceiptTimeDoesNotInventAnElapsedAge() {
        for(long time:new long[]{0,Long.MAX_VALUE}) {
            String status=receipt(Pending.CANCEL,"SUBMITTED",time).status(100);
            assertTrue(status,status.contains("time unavailable"));
            assertTrue(status.contains("Node accepted submission"));
        }
    }
    @Test public void elapsedTimeNeverTurnsLegacyUncertaintyIntoSubmission() {
        String status=receipt(Pending.EDIT,"",System.currentTimeMillis()-3_600_000).status(100);
        assertTrue(status,status.contains("Submission outcome unknown"));
        assertFalse(status.contains("Node accepted"));
    }

    @Test public void bothOwnerKindsUseTheExistingRetainedIntentGuard() {
        for(String kind:new String[]{Pending.CANCEL,Pending.EDIT}) {
            for(String phase:new String[]{"","PREPARED","POSTING","SUBMITTED","UNKNOWN","NOT_SUBMITTED"}) {
                Pending.Row r=receipt(kind,phase,1);
                java.util.Set<String> held=Pending.unresolvedOwnerCoins(java.util.Collections.singletonList(r));
                assertEquals(!"NOT_SUBMITTED".equals(phase),held.contains("0xcafe"));
            }
        }
        assertTrue(Pending.unresolvedOwnerCoins(java.util.Collections.singletonList(receipt(Pending.PLACE,"SUBMITTED",1))).isEmpty());
    }
    @Test public void failedAttemptDoesNotOverrideAnotherUnresolvedAttempt() {
        assertTrue(Pending.unresolvedOwnerCoins(java.util.Arrays.asList(receipt(Pending.CANCEL,"NOT_SUBMITTED",1),
                receipt(Pending.EDIT,"UNKNOWN",1))).contains("0xcafe"));
    }
    @Test public void receiptDescriptionsIdentifyActionsAndStorageErrors() {
        for(String[] item:new String[][]{{Pending.CANCEL,"Cancel order"},{Pending.EDIT,"Update order"},{Pending.PLACE,"Place order"}})
            assertTrue(receipt(item[0],"",0).description().startsWith(item[1]+" · "));
        Pending.Row error=new Pending.Row();error.kind="RECOVERY_ERROR";
        assertEquals("Receipt storage problem",error.description());
        assertTrue(Pending.unresolvedOwnerCoins(java.util.Collections.singletonList(error)).isEmpty());
    }
    @Test public void unknownAndEarlierChainTipsDoNotBecomeZeroAge() throws Exception {
        Order5 order=Order5.from(TransactionHardeningTest.orderCoin().put("created",100));
        assertEquals("age unavailable",order.ageLabel(0,true));
        assertEquals("age unavailable",order.ageLabel(99,true));
        assertEquals("age 0 blk",order.ageLabel(100,true));
        assertEquals("age 20 blk at last check",order.ageLabel(120,false));
        assertEquals("age 20 blk",order.ageLabel(120,true));
        Order5 unknown=Order5.from(TransactionHardeningTest.orderCoin().put("created",0));
        assertEquals("age unavailable",unknown.ageLabel(120,true));
    }
}

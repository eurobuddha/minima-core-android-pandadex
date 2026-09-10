package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Cancel-all invokes the same durable maker stop on the UI/service boundary, before visible cancels. */
public class MakerCancelAllTest {
    @After public void reset(){MakerConfig.resetStorageForTests();}
    private static final class Deferred extends DexTxn {
        Result reply;String id;
        Deferred(){super(null,null);}
        public String createOrder(boolean buy,BigDecimal size,BigDecimal price,boolean gtc,BigDecimal rem,String id,Result cb){this.id=id;reply=cb;return id;}
    }
    private static MakerConfig configured(MakerWithdrawalDurabilityTest.Memory m) {
        MakerConfig c=new MakerConfig(m.prefs());c.pegged=false;c.armed=true;
        c.asks.add(new MakerLadder.Level(new BigDecimal("0.05"),BigDecimal.TEN));assertTrue(c.save());return c;
    }
    @Test public void invisibleAcceptedSlotIsTrackedBeforeVisibleCancelsStart() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=configured(m);
        c.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);AtomicInteger ready=new AtomicInteger();
        new MakerEngine(c,new Deferred()).stopForCancelAll(101,null,()->{
            MakerConfig disk=m.restart();assertFalse(disk.armed);assertEquals("0xaabb",disk.orderIdFor("A1"));
            assertTrue(disk.cancelTombstones.containsKey("0xaabb"));ready.incrementAndGet();
        });assertEquals(1,ready.get());
    }
    @Test public void preparedOnlyIntentIsRetainedAndCondemnedEvenWithNoVisibleOrders() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=configured(m);
        assertTrue(c.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));AtomicInteger ready=new AtomicInteger();
        new MakerEngine(c,new Deferred()).stopForCancelAll(101,null,ready::incrementAndGet);
        MakerConfig disk=m.restart();assertFalse(disk.armed);assertEquals("0xaabb",disk.preparedOrderId());
        assertTrue(disk.cancelTombstones.containsKey("0xaabb"));assertEquals(1,ready.get());
    }
    @Test public void failedPauseAndTrackingWriteCannotStartCancelAll() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=configured(m);
        c.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);Map<String,Object> before=new HashMap<>(m.disk);m.failAt=m.writes+1;
        AtomicInteger ready=new AtomicInteger();List<String> messages=new ArrayList<>();
        new MakerEngine(c,new Deferred()).stopForCancelAll(101,messages::add,ready::incrementAndGet);
        assertEquals(0,ready.get());assertEquals(before,m.disk);assertFalse(MakerConfig.storageHealthy());
        assertTrue(messages.get(0).contains("not started"));
    }
    @Test public void unreadableMakerRecordsCannotBeClearedToStartCancelAll() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=configured(m);
        m.disk.put("slots2","broken");m.visible.put("slots2","broken");Map<String,Object> before=new HashMap<>(m.disk);
        AtomicInteger ready=new AtomicInteger();new MakerEngine(c,new Deferred()).stopForCancelAll(101,null,ready::incrementAndGet);
        assertEquals(0,ready.get());assertEquals(before,m.disk);assertFalse(c.readable());
    }
    private void handoff(boolean uncertain,boolean failFinalTracking)throws Exception {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig background=configured(m);
        MakerConfig foreground=new MakerConfig(m.prefs());Deferred tx=new Deferred();MakerEngine active=new MakerEngine(background,tx);
        active.onBook(Collections.emptyMap(),Collections.emptySet(),100,null);assertNotNull(tx.reply);
        AtomicInteger ready=new AtomicInteger();List<String> messages=new ArrayList<>();
        try {
            assertTrue(tx.reply.onPrepared("fixture"));
            new MakerEngine(foreground,new Deferred()).stopForCancelAll(101,messages::add,ready::incrementAndGet);
            int firstStop=m.committed.size()-1;
            assertEquals(0,ready.get());assertFalse(m.restart().armed);assertTrue(m.restart().cancelTombstones.containsKey(tx.id));
            if(failFinalTracking)m.failAt=m.writes+3; // accepted-slot save, terminal bookkeeping, then queued stop
            if(uncertain)tx.reply.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);else tx.reply.onPosted("0xaabb");
            for(int i=firstStop;i<m.committed.size();i++) {
                Map<String,Object> state=m.committed.get(i);
                assertEquals("no intermediate re-arm",Boolean.FALSE,state.get("armed"));
                assertTrue("withdrawal identity survives every acknowledged save",new JSONObject((String)state.get("tombstones")).has(tx.id));
            }
            assertEquals(failFinalTracking?0:1,ready.get());assertFalse(active.isWorking());
            assertEquals(tx.id,m.restart().orderIdFor("A1"));assertTrue(m.restart().cancelTombstones.containsKey(tx.id));
            if(failFinalTracking){assertFalse(MakerConfig.storageHealthy());assertTrue(messages.get(messages.size()-1).contains("not started"));}
        }finally{if(active.isWorking())tx.reply.onFailed("fixture cleanup");}
    }
    @Test public void acceptedBackgroundCreateKeepsTrackingUntilCancelAllCanRun()throws Exception {handoff(false,false);}
    @Test public void uncertainBackgroundReplyCannotOverwriteForegroundWithdrawal()throws Exception {handoff(true,false);}
    @Test public void failedPostHandoffTrackingDoesNotStartVisibleCancellation()throws Exception {handoff(false,true);}
    @Test public void stopReloadsAnotherHostsNewerSlotBeforeSaving() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig a=configured(m),b=new MakerConfig(m.prefs());
        a.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);
        assertTrue(b.stopAndTrackWithdrawal(101));MakerConfig disk=m.restart();
        assertFalse(disk.armed);assertEquals("0xaabb",disk.orderIdFor("A1"));assertTrue(disk.cancelTombstones.containsKey("0xaabb"));
    }
}

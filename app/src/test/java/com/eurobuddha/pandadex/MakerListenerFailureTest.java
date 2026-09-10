package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MakerListenerFailureTest {
    // Isolate failing regression runs: a broken callback must not poison unrelated tests.
    @After public void reset() throws Exception {
        java.lang.reflect.Field working=MakerEngine.class.getDeclaredField("working");working.setAccessible(true);working.setBoolean(null,false);
        java.lang.reflect.Field idle=MakerEngine.class.getDeclaredField("pendingOnIdle");idle.setAccessible(true);idle.set(null,null);
        java.lang.reflect.Field queue=MakerEngine.class.getDeclaredField("idleQueue");queue.setAccessible(true);queue.set(null,new SerialQueue());
        MakerConfig.resetStorageForTests();
    }
    static MakerConfig config(){MakerConfig c=new MakerConfig();c.armed=true;c.pegged=false;
        c.asks.add(new MakerLadder.Level(new BigDecimal("0.01"),BigDecimal.TEN));return c;}
    static MakerEngine.Listener throwing(String event){return new MakerEngine.Listener(){
        void failIf(String name){if(event.equals(name))throw new IllegalStateException("display unavailable");}
        public void onMakerState(String message){failIf("state");}
        public void onCreateSent(MakerLadder.Slot s,String id){failIf("create");}
        public void onCancelSent(Order5 o){failIf("cancel");}
        public void onRelockSent(Order5 o,BigDecimal p){failIf("relock");}
    };}
    static Order5 order(){return Order5.from(TransactionHardeningTest.orderCoin());}
    @Test public void asynchronousCreateDisplayFailureCannotStrandEngineOrQueuedWork(){
        MakerConfig c=config();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCreates=true;
        MakerEngine engine=new MakerEngine(c,tx);AtomicInteger idle=new AtomicInteger();
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,throwing("create"));assertTrue(engine.isWorking());
        engine.runWhenIdle(idle::incrementAndGet);tx.parked.onPosted("0xaabb");
        assertFalse(engine.isWorking());assertEquals(1,idle.get());assertEquals(1,c.slots.size());assertEquals(1,tx.calls.size());
        tx.parked.onFailed("late duplicate");assertEquals(1,idle.get());assertEquals(1,c.slots.size());
    }
    @Test public void stateDisplayFailureCannotPreventStartOrCompletion(){
        MakerConfig c=config();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=new MakerEngine(c,tx);
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,throwing("state"));
        assertEquals(1,tx.calls.size());assertEquals(1,c.slots.size());assertFalse(engine.isWorking());
    }
    @Test public void uncertainReplyStillDisarmsAndDrainsWhenPauseMessageThrows(){
        MakerConfig c=config();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCreates=true;
        MakerEngine engine=new MakerEngine(c,tx);AtomicInteger idle=new AtomicInteger();
        MakerEngine.Listener listener=message->{if(message.startsWith("Maker paused:"))throw new IllegalStateException("display");};
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,listener);engine.runWhenIdle(idle::incrementAndGet);
        tx.parked.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
        assertFalse(c.armed);assertFalse(engine.isWorking());assertEquals(1,idle.get());assertEquals(1,c.slots.size());
    }
    @Test public void rejectedActionStillCompletesWhenFailureMessageThrows(){
        MakerConfig c=config();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCreates=true;
        MakerEngine engine=new MakerEngine(c,tx);AtomicInteger idle=new AtomicInteger();
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,message->{if(message.contains("failed"))throw new IllegalStateException("display");});
        engine.runWhenIdle(idle::incrementAndGet);tx.parked.onFailed("fixture rejection");
        assertFalse(engine.isWorking());assertEquals(1,idle.get());assertTrue(c.slots.isEmpty());
    }
    @Test public void batchCancelDisplayFailureCannotSkipRemainingSourcesOrIdleWork()throws Exception{
        MakerConfig c=config();c.armed=false;MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCancels=true;
        MakerEngine engine=new MakerEngine(c,tx);List<Order5> orders=new ArrayList<>();
        for(int i=0;i<SweepPlanner.MAX_ORDERS+1;i++){
            org.json.JSONObject raw=TransactionHardeningTest.orderCoin().put("coinid",String.format("0x%04x",i+1));
            raw.getJSONObject("state").put("4",String.format("0x%04x",i+100));orders.add(Order5.from(raw));
        }
        AtomicInteger idle=new AtomicInteger();engine.cancelAllLadder(orders,0,200,throwing("cancel"));engine.runWhenIdle(idle::incrementAndGet);
        DexTxn.Result first=tx.parked;first.onPosted("0xaaaa");assertEquals(2,tx.calls.size());assertTrue(engine.isWorking());
        tx.parked.onPosted("0xbbbb");assertFalse(engine.isWorking());assertEquals(1,idle.get());
        assertEquals(orders.size(),c.cancelTombstones.size());for(Order5 o:orders)assertEquals(200,c.cancelTombstones.get(o.orderId).lastAttemptBlock);
    }
    @Test public void lateOrderCancelStillReleasesQueuedWorkWhenDisplayThrows(){
        MakerConfig c=config();c.armed=false;Order5 o=order();c.tombstone(o.orderId,100,0);
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCancels=true;MakerEngine engine=new MakerEngine(c,tx);AtomicInteger idle=new AtomicInteger();
        engine.sweepTombstones(Collections.singletonMap(o.coinid,o),Collections.singleton(o.ownerPk),200,throwing("cancel"));
        engine.runWhenIdle(idle::incrementAndGet);tx.parked.onPosted("0xaaaa");assertFalse(engine.isWorking());assertEquals(1,idle.get());assertTrue(c.cancelTombstones.containsKey(o.orderId));
    }
    @Test public void terminalWithdrawalMessageCannotConsumeDeferredWithdrawal(){
        MakerConfig c=config();c.armed=false;Order5 o=order();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCancels=true;
        MakerEngine engine=new MakerEngine(c,tx);AtomicInteger idle=new AtomicInteger();
        engine.cancelAllLadder(Collections.singletonList(o),0,200,throwing("state"));engine.runWhenIdle(idle::incrementAndGet);
        tx.parked.onPosted("0xaaaa");assertFalse(engine.isWorking());assertEquals(1,idle.get());assertTrue(c.cancelTombstones.containsKey(o.orderId));
    }
    @Test public void repriceDisplayFailureCannotStrandEngineAfterSlotBookkeeping(){
        MakerConfig c=config();c.asks.clear();c.asks.add(new MakerLadder.Level(new BigDecimal("0.02"),new BigDecimal("100")));
        Order5 o=order();c.rememberSlot("A1",o.orderId,new BigDecimal("100"),100,new BigDecimal("100"),"0x00");
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=new MakerEngine(c,tx);
        engine.onBook(Collections.singletonMap(o.coinid,o),Collections.singleton(o.ownerPk),200,throwing("relock"));
        assertEquals(1,tx.calls.size());assertTrue(tx.calls.get(0).startsWith("RELOCK"));
        assertFalse(engine.isWorking());assertEquals(200,c.slots.get("A1").lastActionBlock);
    }
    @Test public void stopAndCancelAllHandoffSurvivesAThrowingPauseNotification(){
        MakerConfig c=config();MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();tx.deferCreates=true;
        MakerEngine engine=new MakerEngine(c,tx);AtomicInteger ready=new AtomicInteger();
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        engine.stopForCancelAll(201,throwing("state"),ready::incrementAndGet);assertEquals(0,ready.get());
        tx.parked.onPosted("0xaabb");assertFalse(engine.isWorking());assertFalse(c.armed);assertEquals(1,ready.get());
        assertEquals(1,c.slots.size());assertTrue(c.cancelTombstones.containsKey(c.orderIdFor("A1")));
    }

}

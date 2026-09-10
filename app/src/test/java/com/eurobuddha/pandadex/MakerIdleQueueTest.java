package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import java.util.*;
import static org.junit.Assert.*;
public class MakerIdleQueueTest {
    @After public void reset()throws Exception {
        new MakerListenerFailureTest().reset();
        try{java.lang.reflect.Field field=MakerEngine.class.getDeclaredField("idleQueue");field.setAccessible(true);field.set(null,new SerialQueue());}catch(NoSuchFieldException baseline){}
    }
    static MakerEngine start(MakerEngineTest.StubTxn tx) {
        tx.deferCancels=true;MakerEngine engine=new MakerEngine(MakerListenerFailureTest.config(),tx);
        engine.cancelAllLadder(Collections.singletonList(MakerListenerFailureTest.order()),0,200,null);assertTrue(engine.isWorking());return engine;
    }
    @Test public void everyDeferredRequestRunsInArrivalOrder() {
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=start(tx);List<Integer> calls=new ArrayList<>();
        engine.runWhenIdle(()->calls.add(1));engine.runWhenIdle(()->calls.add(2));engine.runWhenIdle(()->calls.add(3));
        assertTrue(calls.isEmpty());tx.parked.onPosted("0xabcd");assertEquals(Arrays.asList(1,2,3),calls);assertFalse(engine.isWorking());
    }
    @Test public void nextDeferredRequestWaitsForAsyncWorkStartedByPreviousRequest() {
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=start(tx);List<String> calls=new ArrayList<>();DexTxn.Result first=tx.parked;
        engine.runWhenIdle(()->{calls.add("second chain");engine.cancelAllLadder(Collections.singletonList(MakerListenerFailureTest.order()),0,201,null);});
        engine.runWhenIdle(()->calls.add("after chain"));first.onPosted("0xabcd");
        assertEquals(Collections.singletonList("second chain"),calls);assertTrue(engine.isWorking());assertEquals(2,tx.calls.size());
        first.onFailed("duplicate");assertEquals(1,calls.size());tx.parked.onPosted("0xabce");
        assertEquals(Arrays.asList("second chain","after chain"),calls);assertFalse(engine.isWorking());
    }
    @Test public void nestedRequestRunsAfterItsSubmittingActionReturns() {
        MakerEngine engine=new MakerEngine(MakerListenerFailureTest.config(),new MakerEngineTest.StubTxn());List<String> calls=new ArrayList<>();
        engine.runWhenIdle(()->{calls.add("start");engine.runWhenIdle(()->calls.add("nested"));calls.add("return");});
        assertEquals(Arrays.asList("start","return","nested"),calls);
    }
    @Test public void throwingDeferredCallbackDoesNotConsumeFollowingRequests() {
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=start(tx);List<String> calls=new ArrayList<>();
        engine.runWhenIdle(()->{calls.add("failed");throw new IllegalStateException("view gone");});engine.runWhenIdle(()->calls.add("next"));
        assertThrows(IllegalStateException.class,()->tx.parked.onPosted("0xabcd"));
        assertEquals(Arrays.asList("failed","next"),calls);assertFalse(engine.isWorking());
        engine.runWhenIdle(()->calls.add("later"));assertEquals(3,calls.size());
    }
    @Test public void twoStopRequestsRetainBothReadyCallbacks() {
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine engine=start(tx);List<Integer> ready=new ArrayList<>();
        engine.stopForCancelAll(201,null,()->ready.add(1));engine.stopForCancelAll(202,null,()->ready.add(2));
        tx.parked.onPosted("0xabcd");assertEquals(Arrays.asList(1,2),ready);assertFalse(engine.isWorking());
    }
    @Test public void differentMakerHostsShareDeferredOrder() {
        MakerEngineTest.StubTxn tx=new MakerEngineTest.StubTxn();MakerEngine first=start(tx),other=new MakerEngine(MakerListenerFailureTest.config(),new MakerEngineTest.StubTxn());List<Integer> calls=new ArrayList<>();
        first.runWhenIdle(()->calls.add(1));other.runWhenIdle(()->calls.add(2));tx.parked.onPosted("0xabcd");assertEquals(Arrays.asList(1,2),calls);
    }
}

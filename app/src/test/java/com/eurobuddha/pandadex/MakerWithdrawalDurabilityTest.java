package com.eurobuddha.pandadex;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.After;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

/** Commit-failure and crash-boundary tests using the real MakerConfig serializer.
 * Proxy seam follows the SDK Android audit; failed commit changes memory, not durable state. */
public class MakerWithdrawalDurabilityTest {
    @After public void clearFailure(){MakerConfig.resetStorageForTests();}
    static final class Memory {
        final Map<String,Object> disk=new HashMap<>(),visible=new HashMap<>();int writes,failAt=-1;
        final List<Map<String,Object>> committed=new ArrayList<>();
        SharedPreferences prefs(){return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            String n=m.getName();
            if(n.equals("edit")){Map<String,Object> changed=new HashMap<>();Set<String> removed=new HashSet<>();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(e,em,ea)->{
                    String en=em.getName();
                    if(en.startsWith("put")){changed.put((String)ea[0],ea[1]);return e;}
                    if(en.equals("remove")){removed.add((String)ea[0]);return e;}
                    if(en.equals("commit")){writes++;for(String k:removed)visible.remove(k);visible.putAll(changed);
                        if(writes==failAt)return false;disk.clear();disk.putAll(visible);committed.add(new HashMap<>(disk));return true;}
                    throw new UnsupportedOperationException(en);
                });
            }
            if(n.equals("contains"))return visible.containsKey(a[0]);
            if(n.equals("getAll"))return new HashMap<>(visible);
            if(n.startsWith("get"))return visible.getOrDefault(a[0],a[1]);
            throw new UnsupportedOperationException(n);
        });}
        MakerConfig restart(){visible.clear();visible.putAll(disk);return new MakerConfig(prefs());}
    }
    static final class Cancel extends DexTxn {
        int calls,creates;Result pending;
        Cancel(){super(null,null);}
        @Override public String createOrder(boolean buy,BigDecimal size,BigDecimal price,boolean gtc,BigDecimal rem,String id,Result cb){creates++;cb.onFailed("fixture rejects create");return id;}
        @Override public void cancelBatch(List<Order5> orders,Result cb){calls++;pending=cb;}
    }
    private static Order5 order() {
        Order5 o=Order5.from(TransactionHardeningTest.orderCoin());assertNotNull(o);return o;
    }
    private static MakerConfig seeded(Memory memory,Order5 order) {
        MakerConfig cfg=new MakerConfig(memory.prefs());cfg.pegged=false;
        cfg.rememberSlot("A1",order.orderId,BigDecimal.TEN,100);return cfg;
    }
    @Test public void lostCancelReplyStillLeavesDurableWithdrawalIdentity() {
        Memory memory=new Memory();Order5 o=order();MakerConfig cfg=seeded(memory,o);Cancel tx=new Cancel();MakerEngine engine=new MakerEngine(cfg,tx);
        engine.cancelAllLadder(Collections.singletonList(o),0,101,null);
        try {
            assertEquals(1,tx.calls);MakerConfig restarted=memory.restart();
            assertTrue(restarted.cancelTombstones.containsKey(o.orderId));assertTrue(restarted.hasRecordedOrders());
            assertEquals(0,restarted.cancelTombstones.get(o.orderId).lastAttemptBlock);
        }finally{tx.pending.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);}
    }
    @Test public void failedIntentCommitPreventsCancellationAndKeepsOriginalSlot() {
        Memory memory=new Memory();Order5 o=order();MakerConfig cfg=seeded(memory,o);memory.failAt=memory.writes+1;
        Cancel tx=new Cancel();MakerEngine engine=new MakerEngine(cfg,tx);List<String> messages=new ArrayList<>();
        engine.cancelAllLadder(Collections.singletonList(o),0,101,messages::add);
        assertEquals(0,tx.calls);assertFalse(engine.isWorking());assertTrue(cfg.slots.containsKey("A1"));
        assertEquals(o.orderId,memory.restart().orderIdFor("A1"));assertTrue(messages.get(0).contains("not sent"));
    }
    @Test public void failedSlotRemovalCannotLoseAlreadyDurableWithdrawal() {
        Memory memory=new Memory();Order5 o=order();MakerConfig cfg=seeded(memory,o);memory.failAt=memory.writes+2;
        Cancel tx=new Cancel();MakerEngine engine=new MakerEngine(cfg,tx);engine.cancelAllLadder(Collections.singletonList(o),0,101,null);
        try {
            assertEquals(1,tx.calls);MakerConfig restarted=memory.restart();
            assertEquals(o.orderId,restarted.orderIdFor("A1"));assertTrue(restarted.cancelTombstones.containsKey(o.orderId));
        }finally{tx.pending.onFailed("test completed");}
    }
    @Test public void interruptedCreateAloneRemainsReachableByWithdrawal() {
        Memory memory=new Memory();MakerConfig cfg=new MakerConfig(memory.prefs());
        assertTrue(cfg.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));assertTrue(cfg.slots.isEmpty());assertTrue(cfg.hasRecordedOrders());
        Cancel tx=new Cancel();new MakerEngine(cfg,tx).withdrawAll(Collections.emptyMap(),Collections.emptySet(),101,null);
        MakerConfig restarted=memory.restart();assertTrue(restarted.preparedCreate.isEmpty());
        assertTrue(restarted.cancelTombstones.containsKey("0xaabb"));assertTrue(restarted.hasRecordedOrders());assertEquals(0,tx.calls);
    }
    @Test public void failedPreparedWithdrawalRetainsTheOriginalIntent() {
        Memory memory=new Memory();MakerConfig cfg=new MakerConfig(memory.prefs());cfg.prepareCreate("A1","0xaabb",BigDecimal.TEN,100);
        memory.failAt=memory.writes+1;Cancel tx=new Cancel();
        new MakerEngine(cfg,tx).withdrawAll(Collections.emptyMap(),Collections.emptySet(),101,null);
        assertEquals("0xaabb",memory.restart().preparedOrderId());assertEquals(0,tx.calls);assertFalse(cfg.preparedCreate.isEmpty());
    }
    @Test public void corruptPreparedIntentIsNotErasedOrReportedWithdrawn() {
        MakerConfig cfg=new MakerConfig();cfg.preparedCreate="broken";Cancel tx=new Cancel();List<String> messages=new ArrayList<>();
        new MakerEngine(cfg,tx).withdrawAll(Collections.emptyMap(),Collections.emptySet(),101,messages::add);
        assertEquals("broken",cfg.preparedCreate);assertEquals(0,tx.calls);assertTrue(messages.get(0).contains("Cannot read"));
    }
    @Test public void serializationFailureCannotOverwriteDurableTrackingWithPartialJson() {
        Memory memory=new Memory();Order5 o=order();MakerConfig cfg=seeded(memory,o);Map<String,Object> before=new HashMap<>(memory.disk);
        cfg.slots.put("bad",null);assertFalse(cfg.save());assertEquals(before,memory.disk);
    }    @Test public void failedCommitVisibleInMemoryCannotArmAutomaticQuoting() {
        Memory memory=new Memory();MakerConfig cfg=new MakerConfig(memory.prefs());cfg.pegged=false;
        cfg.asks.add(new MakerLadder.Level(new BigDecimal("0.05"),BigDecimal.TEN));cfg.armed=true;
        memory.failAt=memory.writes+1;assertFalse(cfg.save());assertEquals(Boolean.TRUE,memory.visible.get("armed"));
        Cancel tx=new Cancel();MakerEngine engine=new MakerEngine(cfg,tx);engine.onBook(Collections.emptyMap(),Collections.emptySet(),100,null);
        assertEquals(0,tx.creates);assertFalse(MakerConfig.storageHealthy());
        assertTrue(cfg.save());assertFalse("automatic bookkeeping cannot clear a failed user save",MakerConfig.storageHealthy());
        assertTrue(cfg.saveUserAction());assertTrue(MakerConfig.storageHealthy());
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),101,null);assertEquals(1,tx.creates);
    }

}

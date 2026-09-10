package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
public class MakerPreparedIdentityTest {
    @After public void reset()throws Exception{new MakerListenerFailureTest().reset();}
    static final class Deferred extends DexTxn {
        String orderId;Result pending;int calls;
        Deferred(){super(null,null);}
        @Override public String createOrder(boolean buy,BigDecimal size,BigDecimal price,boolean gtc,BigDecimal rem,String id,Result cb){orderId=id;pending=cb;calls++;return id;}
    }
    static MakerEngine start(MakerConfig c,Deferred tx){MakerEngine e=new MakerEngine(c,tx);e.onBook(Collections.emptyMap(),Collections.emptySet(),100,null);assertNotNull(tx.pending);return e;}
    @Test public void existingPreparedIntentCannotBeReplacedEvenByFreshWriter(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);
        assertTrue(c.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));Map<String,Object> saved=new HashMap<>(m.disk);
        MakerConfig fresh=new MakerConfig(m.prefs());assertFalse(fresh.prepareCreate("A2","0xccdd",BigDecimal.TEN,101));assertEquals(saved,m.disk);
        assertFalse(c.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));assertEquals(saved,m.disk);
    }
    @Test public void finishedMakerCallbackCannotPrepareOrAuthorizeAgain(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();MakerEngine e=start(c,tx);
        tx.pending.onFailed("fixture rejects before preparation");assertFalse(e.isWorking());Map<String,Object> saved=new HashMap<>(m.disk);
        assertFalse(tx.pending.beforePost());assertFalse(tx.pending.onPrepared("obsolete"));assertEquals(saved,m.disk);
    }
    @Test public void failureBeforeOwnPreparationCannotClearDifferentIntent(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();MakerEngine e=start(c,tx);
        MakerConfig other=new MakerConfig(m.prefs());assertTrue(other.prepareCreate("A2","0xccdd",BigDecimal.TEN,101));String original=other.preparedCreate;
        tx.pending.onFailed("fixture rejected");assertEquals(original,m.restart().preparedCreate);assertFalse(e.isWorking());
    }
    @Test public void acceptedCreateRetainsDifferentIntentAndItsOwnAcceptedSlot(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();MakerEngine e=start(c,tx);
        MakerConfig other=new MakerConfig(m.prefs());assertTrue(other.prepareCreate("A2","0xccdd",BigDecimal.TEN,101));String original=other.preparedCreate;
        tx.pending.onPosted("0xaabb");MakerConfig reopened=m.restart();assertEquals(original,reopened.preparedCreate);assertEquals(tx.orderId,reopened.orderIdFor("A1"));assertFalse(e.isWorking());
    }
    @Test public void matchingFailureClearsOnlyItsOwnPreparedIdentity(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();start(c,tx);
        assertTrue(tx.pending.onPrepared("current"));assertEquals(tx.orderId,m.restart().preparedOrderId());tx.pending.onFailed("fixture rejected");
        assertTrue(m.restart().preparedCreate.isEmpty());assertTrue(m.restart().slots.isEmpty());
    }
    @Test public void matchingAcceptanceAtomicallyReplacesPreparedIntentWithAcceptedSlot(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();start(c,tx);
        assertTrue(tx.pending.onPrepared("current"));int writes=m.writes;tx.pending.onPosted("0xaabb");
        assertTrue(m.writes>writes);
        for(int i=writes;i<m.committed.size();i++) {
            Map<String,Object> committed=m.committed.get(i);assertEquals("",committed.get("prepared_create"));
            assertTrue(((String)committed.get("slots2")).contains(tx.orderId));
        }
        MakerConfig reopened=m.restart();assertTrue(reopened.preparedCreate.isEmpty());assertEquals(tx.orderId,reopened.orderIdFor("A1"));
    }
    @Test public void failedAcceptanceCommitNeverLosesBothPreparedAndAcceptedIdentity(){
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);Deferred tx=new Deferred();start(c,tx);
        assertTrue(tx.pending.onPrepared("current"));m.failAt=m.writes+1;tx.pending.onPosted("0xaabb");
        assertFalse(MakerConfig.storageHealthy());MakerConfig reopened=m.restart();assertTrue(tx.orderId.equals(reopened.preparedOrderId())||tx.orderId.equals(reopened.orderIdFor("A1")));
    }
}

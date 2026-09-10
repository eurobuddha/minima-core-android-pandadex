package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

/** Real MakerConfig serialization with restart-visible preferences and hostile stored fields. */
public class MakerConfigRecoveryTest {
    @After public void clearFailure(){MakerConfig.resetStorageForTests();}
    private static MakerWithdrawalDurabilityTest.Memory memory(){return new MakerWithdrawalDurabilityTest.Memory();}
    private static MakerConfig seeded(MakerWithdrawalDurabilityTest.Memory memory) {
        MakerConfig c=new MakerConfig(memory.prefs());c.pegged=false;c.armed=true;
        c.asks.add(new MakerLadder.Level(new BigDecimal("0.05"),BigDecimal.TEN));
        c.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);assertTrue(c.save());return c;
    }
    private static void damage(MakerWithdrawalDurabilityTest.Memory m,String key,Object value) {
        m.disk.put(key,value);m.visible.put(key,value);
    }
    private static void protectedStore(MakerWithdrawalDurabilityTest.Memory m) {
        Map<String,Object> before=new HashMap<>(m.disk);int writes=m.writes;
        MakerConfig c=m.restart();assertFalse(c.readable());assertFalse(c.armed);assertTrue(c.hasRecordedOrders());
        c.armed=true;assertFalse(c.saveUserAction());assertFalse(c.prepareCreate("A2","0xbbcc",BigDecimal.TEN,102));
        assertEquals(before,m.disk);assertEquals(writes,m.writes);assertFalse(MakerConfig.storageHealthy());
        MakerWithdrawalDurabilityTest.Cancel tx=new MakerWithdrawalDurabilityTest.Cancel();
        new MakerEngine(c,tx).onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        assertEquals(0,tx.creates);assertEquals(before,m.disk);
    }
    @Test public void corruptSnapshotsCannotBecomeEmptyArmedLaddersOrOverwriteEvidence() {
        String[][] broken={{"slots2","{"},{"slots2","{\"A1\":false}"},{"slots2","{\"A1\":{\"id\":\"\",\"size\":\"10\"}}"},
            {"tombstones","{\"0xaabb\":false}"},{"tombstones","{\"0xaabb\":{\"made\":1,\"try\":\"1.5\"}}"},
            {"asks","[{\"p\":\"0.05\",\"a\":\"10\"},false]"},{"asks","[]junk"},{"slots2","{}\0junk"},
            {"prepared_create","broken"},{"step","1e99999999"},{"armed","true"},{"slots","[]"}};
        for(String[] row:broken){MakerConfig.resetStorageForTests();MakerWithdrawalDurabilityTest.Memory m=memory();seeded(m);damage(m,row[0],row[1]);protectedStore(m);}
    }
    @Test public void failedReloadKeepsPreviousCompleteTrackingInMemory() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);
        c.tombstone("0xbbcc",101);damage(m,"slots2","{\"B1\":{\"id\":\"0xccdd\",\"size\":\"10\"},\"A1\":false}");
        c.reload();assertFalse(c.readable());assertFalse(c.armed);assertEquals("0xaabb",c.orderIdFor("A1"));
        assertNull(c.orderIdFor("B1"));assertTrue(c.cancelTombstones.containsKey("0xbbcc"));assertFalse(c.save());
    }
    @Test public void staleHostCannotOverwriteNewlyUnreadableRecords() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);damage(m,"tombstones","bad");
        Map<String,Object> before=new HashMap<>(m.disk);c.asks.clear();assertFalse(c.save());assertEquals(before,m.disk);
        assertFalse(c.readable());assertFalse(c.armed);
    }
    @Test public void legacyMigrationKeepsOrderAndCancelIdentities() {
        MakerWithdrawalDurabilityTest.Memory m=memory();
        damage(m,"levels","[{\"off\":\"0.2\",\"size\":\"12.5\"}]");
        damage(m,"slots","{\"A1\":\"0xaabb\"}");damage(m,"slotsizes","{\"A1\":\"12.5\"}");
        damage(m,"tombstones","{\"0xbbcc\":101}");
        MakerConfig c=m.restart();assertTrue(c.readable());assertEquals("0xaabb",c.orderIdFor("A1"));
        assertEquals(new BigDecimal("12.5"),c.asks.get(0).sizeMinima);assertEquals(101,c.cancelTombstones.get("0xbbcc").lastAttemptBlock);
        assertTrue(c.save());assertFalse(m.disk.containsKey("slots"));MakerConfig n=m.restart();
        assertTrue(n.readable());assertEquals("0xaabb",n.orderIdFor("A1"));assertTrue(n.cancelTombstones.containsKey("0xbbcc"));
    }
    @Test public void malformedFundingCannotFallBackToLegacySellSize()throws Exception {
        for(String locked:new String[]{"nonsense","1e99",""}) {
            MakerConfig.resetStorageForTests();MakerWithdrawalDurabilityTest.Memory m=memory();seeded(m);
            JSONObject all=new JSONObject((String)m.disk.get("slots2"));all.getJSONObject("A1").put("locked",locked).put("lock_token",Util.MINIMA_TOKENID);
            damage(m,"slots2",all.toString());protectedStore(m);
        }
    }
    @Test public void incompleteOutgoingRowsNeverCommitPartialState() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);Map<String,Object> before=new HashMap<>(m.disk);int writes=m.writes;
        c.asks.add(null);assertFalse(c.save());assertEquals(before,m.disk);assertEquals(writes,m.writes);
        c.asks.remove(1);c.slots.put("B1",new MakerConfig.SlotRec("",BigDecimal.TEN,100,0));assertFalse(c.save());assertEquals(before,m.disk);
    }
    @Test public void invalidNumbersAndTimesAreRejectedWithoutChangingDisk() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);Map<String,Object> before=new HashMap<>(m.disk);
        c.slots.get("A1").lastActionBlock=-1;assertFalse(c.save());assertEquals(before,m.disk);
        c.slots.get("A1").lastActionBlock=0;c.stepPct=new BigDecimal("1e45");assertFalse(c.save());assertEquals(before,m.disk);
    }
    @Test public void repairedReadableDataStillNeedsExplicitRecoveryAcknowledgement() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);String original=(String)m.disk.get("slots2");
        damage(m,"slots2","bad");c.reload();assertFalse(c.readable());
        damage(m,"slots2",original);c.reload();assertTrue(c.readable());assertFalse(MakerConfig.storageHealthy());
        assertTrue(c.saveUserAction());assertTrue(MakerConfig.storageHealthy());
    }
    @Test public void wrongNativePreferenceTypePausesWithoutCrashing() {
        for(String key:new String[]{"armed","pegged","nlevels","asks"}) {
            MakerConfig.resetStorageForTests();MakerWithdrawalDurabilityTest.Memory m=memory();seeded(m);
            damage(m,key,new HashSet<String>());protectedStore(m);
        }
    }
    @Test public void malformedPreparedOutputCannotReplaceExistingEvidence() {
        MakerWithdrawalDurabilityTest.Memory m=memory();MakerConfig c=seeded(m);
        Map<String,Object> before=new HashMap<>(m.disk);int writes=m.writes;
        assertFalse(c.prepareCreate("A2","",BigDecimal.TEN,100));assertEquals(before,m.disk);assertEquals(writes,m.writes);
        assertFalse(c.prepareCreate("A2","0xbbcc",BigDecimal.TEN,-1));assertEquals(before,m.disk);
        assertFalse(c.prepareCreate("A2","0xbbcc",BigDecimal.TEN,100,BigDecimal.ONE,""));assertEquals(before,m.disk);
    }

}

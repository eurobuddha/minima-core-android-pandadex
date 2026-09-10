package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
public class MakerSnapshotWriteTest {
    @After public void reset(){MakerConfig.resetStorageForTests();}
    static MakerConfig seed(MakerWithdrawalDurabilityTest.Memory m){MakerConfig c=new MakerConfig(m.prefs());c.armed=true;c.pegged=false;c.asks.add(new MakerLadder.Level(new BigDecimal("0.01"),BigDecimal.TEN));assertTrue(c.saveUserAction());return c;}
    @Test public void staleHostCannotUndoAcknowledgedPause() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig first=seed(m),stale=new MakerConfig(m.prefs());
        first.armed=false;assertTrue(first.saveUserAction());Map<String,Object> saved=new HashMap<>(m.disk);
        stale.stepPct=new BigDecimal("0.4");assertFalse(stale.save());assertEquals(saved,m.disk);assertFalse(MakerConfig.storageHealthy());
    }
    @Test public void staleHostCannotEraseNewSlotAndWithdrawalIdentity() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig first=seed(m),stale=new MakerConfig(m.prefs());
        first.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);assertTrue(first.stopAndTrackWithdrawal(101));Map<String,Object> saved=new HashMap<>(m.disk);
        assertFalse(stale.saveUserAction());assertEquals(saved,m.disk);MakerConfig reopened=m.restart();assertEquals("0xaabb",reopened.orderIdFor("A1"));assertTrue(reopened.cancelTombstones.containsKey("0xaabb"));
    }
    @Test public void stalePrepareCannotReplaceAnotherPreparedCreate() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig first=seed(m),stale=new MakerConfig(m.prefs());
        assertTrue(first.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));Map<String,Object> saved=new HashMap<>(m.disk);
        assertFalse(stale.prepareCreate("A2","0xccdd",BigDecimal.TEN,101));assertEquals(saved,m.disk);
    }
    @Test public void staleExplicitPriceSaveCannotReplaceNewerRungs() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig first=seed(m),stale=new MakerConfig(m.prefs());
        first.asks.clear();first.asks.add(new MakerLadder.Level(new BigDecimal("0.02"),BigDecimal.TEN));assertTrue(first.saveUserAction());
        Map<String,Object> saved=new HashMap<>(m.disk);stale.skewPct=new BigDecimal("1");assertFalse(stale.saveUserAction());assertEquals(saved,m.disk);
    }
    @Test public void reloadThenExplicitSaveUsesLatestTrackingWithoutAutomaticResume() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig first=seed(m),stale=new MakerConfig(m.prefs());
        first.armed=false;assertTrue(first.saveUserAction());assertFalse(stale.save());stale.reload();assertFalse(stale.armed);assertFalse(MakerConfig.storageHealthy());
        stale.stepPct=new BigDecimal("0.3");assertTrue(stale.saveUserAction());assertTrue(MakerConfig.storageHealthy());assertFalse(m.restart().armed);
    }
    @Test public void ownPreparedWritesAndCommitRetryRemainCompatible() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=seed(m);
        assertTrue(c.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));assertTrue(c.save());
        m.failAt=m.writes+1;c.armed=false;assertFalse(c.save());assertFalse(MakerConfig.storageHealthy());
        assertTrue(c.saveUserAction());assertFalse(m.restart().armed);assertEquals("0xaabb",m.restart().preparedOrderId());
    }
    @Test public void unrelatedPreferenceUpdatesAreNotLostOrTreatedAsSettingsConflicts() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=seed(m);
        m.visible.put("audit_extra","keep");m.disk.put("audit_extra","keep");c.stepPct=new BigDecimal("0.3");assertTrue(c.save());assertEquals("keep",m.disk.get("audit_extra"));
    }
}

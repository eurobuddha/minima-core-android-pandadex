package com.eurobuddha.pandadex;
import org.junit.Test;
import org.junit.After;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
public class MakerAuthorizationRevisionTest {
    @After public void reset(){MakerConfig.resetStorageForTests();}
    private static final MakerLadder.Action CREATE=new MakerLadder.Action(MakerLadder.Kind.CREATE,null,null,"fixture");
    private static final MakerLadder.Action RELOCK=new MakerLadder.Action(MakerLadder.Kind.RELOCK,null,null,"fixture");
    private static final MakerLadder.Action CANCEL=new MakerLadder.Action(MakerLadder.Kind.CANCEL,null,null,"fixture");
    private static MakerQuoteGuard guard(MakerConfig c){return new MakerQuoteGuard(c,new BigDecimal("0.01"));}
    @Test public void pauseAndRearmCannotReviveOldCreateOrRelock() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);MakerQuoteGuard old=guard(c);
        assertTrue(old.allows(c,CREATE));c.armed=false;assertTrue(c.saveUserAction());c.armed=true;assertTrue(c.saveUserAction());
        MakerConfig reopened=m.restart();assertFalse(old.allows(reopened,CREATE));assertFalse(old.allows(reopened,RELOCK));assertTrue(guard(reopened).allows(reopened,CREATE));assertTrue(old.allows(reopened,CANCEL));
    }
    @Test public void editAndRestoreCannotReviveOldQuote() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);MakerQuoteGuard old=guard(c);
        c.asks.set(0,new MakerLadder.Level(new BigDecimal("0.02"),BigDecimal.TEN));assertTrue(c.save());
        c.asks.set(0,new MakerLadder.Level(new BigDecimal("0.01"),BigDecimal.TEN));assertTrue(c.save());assertFalse(old.allows(m.restart(),CREATE));
    }
    @Test public void staleHostCannotWriteAfterPauseAndIdenticalRearm() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m),stale=new MakerConfig(m.prefs());
        c.armed=false;assertTrue(c.saveUserAction());c.armed=true;assertTrue(c.saveUserAction());Map<String,Object> saved=new HashMap<>(m.disk);
        assertFalse(stale.save());assertEquals(saved,m.disk);
    }
    @Test public void bookkeepingAndEquivalentDecimalsKeepCurrentAuthorization() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);MakerQuoteGuard old=guard(c);
        c.asks.set(0,new MakerLadder.Level(new BigDecimal("0.010"),new BigDecimal("10.00")));assertTrue(c.save());
        c.lastActedMid=new BigDecimal("0.01");c.rememberSlot("A1","0xaabb",BigDecimal.TEN,100);c.noteSlotAction("A1",101);
        assertTrue(c.prepareCreate("A2","0xccdd",BigDecimal.TEN,102));assertTrue(c.save());assertTrue(old.allows(m.restart(),CREATE));
    }
    @Test public void failedPauseThenExplicitRearmStillInvalidatesEarlierWork() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerConfig c=MakerSnapshotWriteTest.seed(m);MakerQuoteGuard old=guard(c);
        m.failAt=m.writes+1;c.armed=false;assertFalse(c.saveUserAction());assertFalse(old.allows(c,CREATE));
        c.armed=true;assertTrue(c.saveUserAction());assertFalse(old.allows(m.restart(),CREATE));
    }
    @Test public void legacySettingsRemainReadableAndGainProtectionOnChange() {
        MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerSnapshotWriteTest.seed(m);
        m.visible.remove("quote_revision");m.disk.remove("quote_revision");MakerConfig legacy=m.restart();assertTrue(legacy.readable());MakerQuoteGuard old=guard(legacy);
        assertTrue(legacy.save());assertTrue(old.allows(legacy,CREATE));legacy.armed=false;assertTrue(legacy.save());legacy.armed=true;assertTrue(legacy.saveUserAction());assertFalse(old.allows(m.restart(),CREATE));
    }
    @Test public void invalidStoredRevisionCannotBeOverwritten() {
        for(Object bad:new Object[]{Boolean.TRUE,17,"not-a-revision"}) {
            MakerConfig.resetStorageForTests();MakerWithdrawalDurabilityTest.Memory m=new MakerWithdrawalDurabilityTest.Memory();MakerSnapshotWriteTest.seed(m);
            m.visible.put("quote_revision",bad);m.disk.put("quote_revision",bad);MakerConfig c=m.restart();Map<String,Object> saved=new HashMap<>(m.disk);
            assertFalse(c.readable());assertFalse(c.saveUserAction());assertEquals(saved,m.disk);
        }
    }
}

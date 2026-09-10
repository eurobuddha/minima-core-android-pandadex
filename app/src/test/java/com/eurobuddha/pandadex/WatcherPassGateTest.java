package com.eurobuddha.pandadex;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.pandadex.WatcherPassGate.Action.*;

public class WatcherPassGateTest {
    private final WatcherPassGate gate = new WatcherPassGate(300_000);
    @Test public void coldRegistrationDoesNotDelayFirstPairedScan() {
        assertEquals(REGISTER, gate.next(0,true,false,false));
        assertEquals(SCAN, gate.next(1,true,false,true));
    }
    @Test public void duplicatePairingAndHeartbeatCannotDuplicateScan() {
        assertEquals(SCAN, gate.next(10,true,false,true));
        assertEquals(NONE, gate.next(10,true,false,true));
        assertEquals(NONE, gate.next(300_009,true,false,true));
        assertEquals(SCAN, gate.next(300_010,true,false,true));
    }
    @Test public void repeatedUnpairedStartsAreBoundedButPairingStillResumes() {
        assertEquals(REGISTER, gate.next(0,true,false,false));
        assertEquals(NONE, gate.next(1,true,false,false));
        assertEquals(REGISTER, gate.next(300_000,true,false,false));
        assertEquals(SCAN, gate.next(300_001,true,false,true));
    }
    @Test public void foregroundPairingConsumesNeitherAllowance() {
        assertEquals(NONE, gate.next(0,true,true,false));
        assertEquals(NONE, gate.next(1,true,true,true));
        assertEquals(REGISTER, gate.next(2,true,false,false));
        assertEquals(SCAN, gate.next(3,true,false,true));
    }
    @Test public void callbacksBeforeStartupAndAfterShutdownCannotRun() {
        assertEquals(NONE, gate.next(0,false,false,true));
        assertEquals(SCAN, gate.next(1,true,false,true));
        assertEquals(NONE, gate.next(400_000,false,false,true));
        assertEquals(NONE, gate.next(400_001,false,false,false));
    }
    @Test public void reconnectDoesNotBypassRecentSuccessfulScan() {
        assertEquals(SCAN, gate.next(0,true,false,true));
        assertEquals(REGISTER, gate.next(1,true,false,false));
        assertEquals(NONE, gate.next(2,true,false,true));
        assertEquals(SCAN, gate.next(300_000,true,false,true));
    }
    @Test public void elapsedClockRollbackRecoversAndNegativeTimeCannotStart() {
        assertEquals(NONE, gate.next(-1,true,false,true));
        assertEquals(SCAN, gate.next(500_000,true,false,true));
        assertEquals(SCAN, gate.next(0,true,false,true));
        assertEquals(NONE, gate.next(1,true,false,true));
    }
}

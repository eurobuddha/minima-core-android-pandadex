package com.eurobuddha.pandadex;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The serial signing gate — the guarantee that this app never has two signing operations in flight.
 *
 * Signing one Minima key concurrently makes the node issue the SAME one-time leaf for two different
 * transactions, which leaks that leaf's private key. Confirmed on a live node: 7 of 64 default keys.
 *
 * These run on the JVM without a Looper, so they exercise the queue's ordering contract directly rather
 * than the Android timing. The watchdog is Handler-based and is covered on device instead.
 */
public class SignGateTest {

    /** A stand-in for a signing chain: records when it starts and completes. */
    private static final class Op {
        final String name; final List<String> log;
        SignGate.Release gate;
        Op(String name, List<String> log) { this.name = name; this.log = log; }
        void start(SignGate.Release r) { gate = r; log.add("start " + name); }
        void finish() { log.add("end " + name); gate.free(); }
    }

    @Test public void asecondOperationWaitsForTheFirstToFinish() {
        List<String> log = new ArrayList<>();
        Op a = new Op("A", log), b = new Op("B", log);

        SignGate.submit(a::start);
        SignGate.submit(b::start);

        // B must NOT have started while A holds the gate — that overlap is the bug.
        assertEquals("[start A]", log.toString());

        a.finish();
        assertEquals("[start A, end A, start B]", log.toString());

        b.finish();
        assertEquals("[start A, end A, start B, end B]", log.toString());
    }

    @Test public void operationsNeverOverlap() {
        List<String> log = new ArrayList<>();
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < 8; i++) ops.add(new Op("op" + i, log));   // the fan-out that caused this

        for (Op o : ops) SignGate.submit(o::start);
        for (Op o : ops) o.finish();

        int open = 0;
        for (String e : log) {
            if (e.startsWith("start")) open++; else open--;
            assertTrue("two signing operations were in flight at once", open <= 1);
        }
        assertEquals(16, log.size());
    }

    @Test public void releaseIsIdempotent() {
        List<String> log = new ArrayList<>();
        Op a = new Op("A", log), b = new Op("B", log);
        SignGate.submit(a::start);
        SignGate.submit(b::start);

        a.gate.free();
        a.gate.free();          // a chain with several exit paths may free twice
        a.gate.free();

        // B started exactly once; the extra frees must not have pulled anything else off the queue
        assertEquals("[start A, start B]", log.toString());
        b.finish();
    }

    @Test public void anEmptyQueueLetsTheNextOperationRunImmediately() {
        List<String> log = new ArrayList<>();
        Op a = new Op("A", log);
        SignGate.submit(a::start);
        assertEquals("[start A]", log.toString());
        a.finish();

        Op b = new Op("B", log);
        SignGate.submit(b::start);
        assertEquals("[start A, end A, start B]", log.toString());
        b.finish();
    }
}

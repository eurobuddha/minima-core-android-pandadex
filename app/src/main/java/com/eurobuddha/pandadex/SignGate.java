package com.eurobuddha.pandadex;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * SERIAL SIGNING. Only one signing operation from this app may be in flight at a time.
 *
 * Minima signatures are stateful: each key is a tree of one-time (Winternitz) signatures and the node
 * picks the next leaf by reading, incrementing and writing a per-key {@code uses} counter. Two
 * transactions signing the same key at once both read the same value and both sign the SAME leaf over
 * DIFFERENT data — a reused one-time signature, which leaks that leaf's private key. This is not
 * theoretical: 7 of 64 default keys on a live node were confirmed re-used, witness-exact.
 *
 * PandaDEX supplies that concurrency in two ways:
 *   • the maker engine posts several actions per cycle, and
 *   • MainActivity and DexKeepAliveService each construct their OWN {@code DexTxn}, so the only thing
 *     separating them is a snapshot {@code MainActivity.FOREGROUND} boolean read once at the top of a
 *     pipeline that then runs for minutes.
 *
 * Every path that signs must pass through here — the {@code txnsign} chains AND the bare {@code send}
 * commands that create orders, because {@code send} signs internally too.
 *
 * The node has since been fixed to synchronize its own signing, but this gate stays: the app also runs
 * against nodes we don't control, and serialising is correct anyway.
 *
 * Static, so the two engines in this process genuinely share one queue. Everything runs on the main
 * thread ({@link NodeApi} funnels every node callback back to it), so no locking is needed.
 */
public final class SignGate {

    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static boolean busy = false;
    private static Runnable watchdog = null;

    /** Longer than NodeApi's write timeout, so this only fires for a genuinely lost callback and never
     *  for a chain that is merely slow (PoW on a phone is not quick). */
    private static final long MAX_HOLD_MS = 200_000;

    private SignGate() {}

    /** Lazily resolved so the queue itself works without a Looper — the serialisation logic is plain
     *  Java and is unit-tested on the JVM. Only the lost-callback watchdog needs Android; without a
     *  Looper it is simply absent, which is correct for a test. */
    private static Handler main;
    private static boolean mainResolved = false;
    private static Handler main() {
        if (!mainResolved) {
            mainResolved = true;
            // Off-device, android.jar's stub THROWS rather than returning null, so catch broadly.
            try { Looper l = Looper.getMainLooper(); if (l != null) main = new Handler(l); }
            catch (Throwable noAndroidRuntime) { main = null; }
        }
        return main;
    }

    /** Queue a signing operation. It must call the supplied release exactly once, however it ends. */
    public static void submit(final Op op) {
        QUEUE.add(() -> op.run(new Release()));
        if (!busy) next();
    }

    public interface Op { void run(Release release); }

    /** Idempotent — a chain with several exit paths can call this from all of them. */
    public static final class Release {
        private boolean done = false;
        public void free() {
            if (done) return;
            done = true;
            if (watchdog != null) { Handler h = main(); if (h != null) h.removeCallbacks(watchdog); watchdog = null; }
            next();
        }
    }

    private static void next() {
        Runnable r = QUEUE.poll();
        if (r == null) { busy = false; return; }
        busy = true;
        Handler h = main();
        if (h != null) {
            watchdog = () -> { watchdog = null; next(); };
            h.postDelayed(watchdog, MAX_HOLD_MS);
        }
        r.run();
    }
}

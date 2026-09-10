package com.eurobuddha.pandadex;

/** Main-thread watcher throttle, extracted from DexKeepAliveService's elapsed-time gate.
 * Registration does not count as a scan; a successful pairing can start the first scan immediately.
 * A repeated pairing callback must still obey the existing scan interval. */
final class WatcherPassGate {
    enum Action { NONE, REGISTER, SCAN }
    private final long gapMs;
    private long lastRegisterMs = -1, lastScanMs = -1;
    private boolean waitingForKeys;
    WatcherPassGate(long gapMs) {
        if (gapMs <= 0) throw new IllegalArgumentException("Positive watcher interval required");
        this.gapMs = gapMs;
    }
    Action next(long now, boolean started, boolean foreground, boolean enabled) {
        if (!started || foreground || now < 0) return Action.NONE;
        if (!enabled) {
            if (recent(now, lastRegisterMs)) return Action.NONE;
            lastRegisterMs = now;
            return Action.REGISTER;
        }
        if (recent(now, lastScanMs)) return Action.NONE;
        lastScanMs = now;
        waitingForKeys = true;
        return Action.SCAN;
    }
    /** Consume the current pass once, after the complete ownership snapshot is ready. */
    boolean resumeAfterKeys(boolean canWatch) {
        if (!waitingForKeys) return false;
        waitingForKeys = false;
        return canWatch;
    }
    void invalidate() { waitingForKeys = false; }
    private boolean recent(long now, long previous) {
        return previous >= 0 && now >= previous && now - previous < gapMs;
    }
}

package com.eurobuddha.pandadex;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** Single-flight pool liquidity cache mirroring PandaDEX's last-good book-cache discipline. */
public final class PoolLiquidityRepository {

    public interface Listener {
        void onPools(List<Pool> pools, boolean syncing);
    }

    interface Scanner { void scan(PoolBook.Listener cb); }
    interface Scheduler { void after(long delayMs, Runnable r); }
    interface Clock { long now(); }

    private static final long DEFAULT_MIN_INTERVAL_MS = 8_000;

    private final Scanner scanner;
    private final Scheduler scheduler;
    private final Clock clock;
    private final long minIntervalMs;
    private final List<Listener> listeners = new ArrayList<>();
    private List<Pool> cached = new ArrayList<>();
    private boolean haveLive = false;
    private boolean scanning = false;
    private boolean pendingRescan = false;
    private long lastScanMs = 0;
    private int emptyScans = 0;
    static final int EMPTY_CONFIRM = 2;

    public PoolLiquidityRepository(NodeApi node) {
        PoolBook book = new PoolBook(node);
        this.scanner = book::scan;
        Handler ui = new Handler(Looper.getMainLooper());
        this.scheduler = (delayMs, r) -> ui.postDelayed(r, delayMs);
        this.clock = System::currentTimeMillis;
        this.minIntervalMs = DEFAULT_MIN_INTERVAL_MS;
    }

    PoolLiquidityRepository(Scanner scanner) {
        this(scanner, 0, null, System::currentTimeMillis);
    }

    PoolLiquidityRepository(Scanner scanner, long minIntervalMs, Scheduler scheduler, Clock clock) {
        this.scanner = scanner;
        this.scheduler = scheduler;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.minIntervalMs = minIntervalMs;
    }

    public List<Pool> pools() { return cached; }

    public void subscribe(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        if (l != null) l.onPools(cached, !haveLive);
    }

    public void refresh() {
        if (scanning) { pendingRescan = true; return; }
        long now = clock.now();
        long wait = lastScanMs == 0 ? 0 : lastScanMs + minIntervalMs - now;
        if (wait > 0) {
            if (!pendingRescan) {
                pendingRescan = true;
                if (scheduler != null) {
                    scheduler.after(wait, () -> {
                        if (pendingRescan && !scanning) { pendingRescan = false; refresh(); }
                    });
                }
            }
            return;
        }
        scanning = true;
        lastScanMs = now;
        pendingRescan = false;
        scanner.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) {
                scanning = false;
                if (pools == null) pools = new ArrayList<>();
                boolean emptyAgainstCache = pools.isEmpty() && !cached.isEmpty();
                if (emptyAgainstCache) emptyScans++; else emptyScans = 0;
                boolean believable = !emptyAgainstCache || !haveLive || emptyScans >= EMPTY_CONFIRM;
                if (believable) {
                    cached = pools == null ? new ArrayList<>() : pools;
                    haveLive = true;
                }
                notifyAllListeners(!haveLive);
                runPendingRescan();
            }
            @Override public void onError(String msg) {
                scanning = false;
                notifyAllListeners(!haveLive);
                runPendingRescan();
            }
        });
    }

    private void runPendingRescan() {
        if (!pendingRescan) return;
        pendingRescan = false;
        refresh();
    }

    private void notifyAllListeners(boolean syncing) {
        for (Listener l : new ArrayList<>(listeners)) l.onPools(cached, syncing);
    }
}

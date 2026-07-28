package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic fallback (ported from minimaSwap's SwapWorker): if the OS kills {@link DexKeepAliveService},
 * WorkManager re-launches it so the pool keep-alive keeps running while the app is closed. The worker does NO
 * node work itself — the foreground service + the exact-alarm heartbeat do that; this just ensures the service
 * is alive. Replaces the old ReAnnounceWorker, which tried to do the keep-alive work directly from a
 * Doze-throttled periodic job.
 */
public class DexWatchWorker extends Worker {

    private static final String UNIQUE = "pandadex_watch";

    public DexWatchWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull @Override public Result doWork() {
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), DexKeepAliveService.class));
        } catch (Exception ignored) {}
        return Result.success();
    }

    /** Schedule the ~15-minute fallback (WorkManager's minimum period). */
    public static void schedule(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        // Retire the pre-0.9.13 periodic work — its worker class (ReAnnounceWorker) is deleted (its keep-alive work
        // now lives in DexKeepAliveService), so a persisted "pandadex_legacy" entry would retry a missing class forever.
        wm.cancelUniqueWork("pandadex_legacy");
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                DexWatchWorker.class, 15, TimeUnit.MINUTES).build();
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req);
    }
}

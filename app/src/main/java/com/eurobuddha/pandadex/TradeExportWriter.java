package com.eurobuddha.pandadex;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Background writer for the PandaDEX personal trade reconciliation export. */
public final class TradeExportWriter {

    public interface Cb {
        void onDone(TradeExportFiles.Prepared prepared);
        void onError(String message);
    }

    private static final java.util.concurrent.ThreadPoolExecutor EXEC = new java.util.concurrent.ThreadPoolExecutor(1,1,0,
            java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),r -> {
                Thread t=new Thread(r,"pandadex-export");t.setDaemon(true);return t;
            },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final java.util.concurrent.atomic.AtomicBoolean SAVING = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean BUILDING = new java.util.concurrent.atomic.AtomicBoolean();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private TradeExportWriter() {}

    public static boolean run(android.content.Context context, final TradeExport.Snapshot base, final Cb cb) {
        if (!BUILDING.compareAndSet(false, true)) return false;
        try {
            final android.content.Context app = context.getApplicationContext();
            EXEC.execute(() -> {
                try {
                    TradeExportFiles.Prepared prepared=TradeExportFiles.prepare(app.getCacheDir(),base,(metadata,sink) -> {
                        // This helper belongs to the snapshot, never to a replaceable Activity.
                        try(DexDb raw=new DexDb(app)){raw.captureExport(metadata,sink);}
                    },ExplorerVerifier::lookup);
                    UI.post(() -> {
                        try {cb.onDone(prepared);} catch(RuntimeException failure){prepared.close();cb.onError("Could not open the prepared export");}
                    });
                } catch (Throwable t) {
                    String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    UI.post(() -> cb.onError(msg));
                } finally { BUILDING.set(false); }
            });
            return true;
        } catch (RuntimeException failure) { BUILDING.set(false); throw failure; }
    }

    static TradeExport.Snapshot snapshotFromUi(MainActivity act) {
        TradeExport.Snapshot s = new TradeExport.Snapshot();
        s.exportedAtMs = System.currentTimeMillis();
        s.fromMs = 0;
        s.toMs = Long.MAX_VALUE;
        s.windowLabel = "All time";
        try {
            s.appVersion = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception ignore) {}
        s.freeMinima = nz(act.minimaSendable());
        s.pendingMinima = nz(act.minimaPending());
        s.freeUsdt = nz(act.usdtSendable());
        s.pendingUsdt = nz(act.usdtPending());
        s.bookMid = act.bookMid();

        BigDecimal lockedM = BigDecimal.ZERO;
        BigDecimal lockedU = BigDecimal.ZERO;
        List<Order5> orders = new ArrayList<>(act.book().values());
        for (Order5 o : orders) {
            if (!o.isMine(act.keys(), act.addrs())) continue;
            if (o.sell) lockedM = lockedM.add(o.locked);
            else lockedU = lockedU.add(o.locked);
        }
        s.lockedMinima = lockedM;
        s.lockedUsdt = lockedU;
        return s;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Provider writes are bounded and off the Activity thread; close failures are failures. */
    public static void save(android.content.Context supplied, Uri uri, TradeExportFiles.Prepared prepared, java.util.function.Consumer<Boolean> done) {
        if(!SAVING.compareAndSet(false,true))throw new IllegalStateException("Another export is still saving");
        android.content.Context context=supplied.getApplicationContext();
        try {
            prepared.claim();
            EXEC.execute(() -> {
                boolean ok;
                try {ok=writeTo(context,uri,prepared.zip);}
                finally {prepared.saved();SAVING.set(false);}
                final boolean result=ok;
                UI.post(() -> done.accept(result));
            });
        }catch(RuntimeException failure){prepared.saved();SAVING.set(false);throw failure;}
    }

    static boolean writeTo(android.content.Context context, Uri uri, java.io.File file) {
        // SAF returns content URIs. A forged file URI must never overwrite private app data.
        if(uri==null||!"content".equals(uri.getScheme()))return false;
        try(java.io.InputStream in=new java.io.FileInputStream(file);
            OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")) {
            if(out==null)return false;
            TradeExportFiles.copy(in,out);
            return true; // try-with-resources closes successfully before the caller sees true.
        }catch(Exception failure){return false;}
    }

    public static String describe(TradeExport.Report r) {
        return r.tradeCount + " personal trade record" + (r.tradeCount == 1 ? "" : "s")
                + " · net " + r.totals.netMinima.stripTrailingZeros().toPlainString()
                + " MINIMA · net " + r.totals.netUsdt.stripTrailingZeros().toPlainString() + " mxUSDT";
    }
}

package com.eurobuddha.pandadex;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Background writer for the PandaDEX personal trade reconciliation export. */
public final class TradeExportWriter {

    public interface Cb {
        void onDone(byte[] zip, String filename, TradeExport.Report report);
        void onError(String message);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandadex-export");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private TradeExportWriter() {}

    public static void run(final MainActivity act, final Cb cb) {
        final TradeExport.Snapshot base = snapshotFromUi(act);
        final DexDb raw = act.db().raw();
        EXEC.execute(() -> {
            try {
                base.rows.addAll(raw.myTradesAll(base.fromMs, base.toMs));
                TradeExport.Report report = TradeExport.build(TradeExport.verifiedCopy(base));
                byte[] zip = zip(report);
                String filename = TradeExport.filename(base.exportedAtMs);
                UI.post(() -> cb.onDone(zip, filename, report));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                UI.post(() -> cb.onError(msg));
            }
        });
    }

    private static TradeExport.Snapshot snapshotFromUi(MainActivity act) {
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

    private static byte[] zip(TradeExport.Report r) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, TradeExport.FILE_SUMMARY, r.summaryTxt);
            put(z, TradeExport.FILE_TRADES, r.tradesCsv);
            put(z, TradeExport.FILE_RECONCILIATION, r.reconciliationCsv);
            put(z, TradeExport.FILE_VERIFICATION, r.verificationCsv);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    public static boolean writeTo(MainActivity act, Uri uri, byte[] data) {
        try (OutputStream os = act.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) return false;
            os.write(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String describe(TradeExport.Report r) {
        return r.tradeCount + " confirmed personal trade" + (r.tradeCount == 1 ? "" : "s")
                + " · net " + r.totals.netMinima.stripTrailingZeros().toPlainString()
                + " MINIMA · net " + r.totals.netUsdt.stripTrailingZeros().toPlainString() + " mxUSDT";
    }
}

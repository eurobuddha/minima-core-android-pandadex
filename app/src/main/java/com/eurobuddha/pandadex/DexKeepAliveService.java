package com.eurobuddha.pandadex;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Resident host for unattended GTC renewal + fill detection while the app is closed. Ported
 * from PandaPools' proven Doze-proof stack: a foreground service keeps the process alive, the
 * exact allow-while-idle {@link HeartbeatReceiver} alarm wakes it every ~15 min, and
 * {@link DexWatchWorker} + {@link BootReceiver} + onTaskRemoved relaunch it after an OS kill,
 * reboot or app swipe. A plain WorkManager job is Doze-throttled and would let orders expire
 * overnight — the exact bug that cost PandaPools its pools and Limit a GTC order.
 *
 * Stands down entirely while the Activity is foreground, so exactly ONE actor ever posts.
 */
public class DexKeepAliveService extends Service {

    private static final String CH_FG = "pandadex_keepalive";
    private static final int FG_ID = 7401;
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.pandadex.HEARTBEAT";
    private static final long PASS_GAP_MS = 5 * 60_000;

    private NodeApi node;
    private DexDb db;
    private DexTxn txn;
    private DexProcessor processor;
    private MakerConfig makerCfg;
    private MakerEngine maker;
    private KeySet keySet;
    private FillTape tape;
    private boolean started = false;
    private long lastPassMs = 0;
    private long chainBlock = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        if (!startForegroundCompat()) { stopSelf(); return; }
        node = new NodeApi(getApplicationContext(), null);
        db = new DexDb(getApplicationContext());
        txn = new DexTxn(node, db);
        processor = new DexProcessor(getApplicationContext(), txn);
        makerCfg = new MakerConfig(getApplicationContext());
        maker = new MakerEngine(makerCfg, txn);
        keySet = new KeySet(getApplicationContext(), null);
        tape = new FillTape(new FillTape.CancelLog() {
            @Override public void note(String coinid) { db.noteCancelled(coinid); }
            @Override public boolean consume(String coinid) { return db.wasCancelled(coinid); }
        });
        HeartbeatReceiver.schedule(this);
        started = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started || node == null) return START_STICKY;
        pass();
        return START_STICKY;
    }

    /** One unattended pass: identity → block → book → renew/sweep + record fills. */
    private void pass() {
        long now = System.currentTimeMillis();
        if (now - lastPassMs < PASS_GAP_MS) return;
        if (MainActivity.FOREGROUND) return;          // the Activity is driving — stand down
        lastPassMs = now;
        if (!node.isEnabled()) { node.reRegister(); return; }
        acquireTimedWakelock();

        keySet.refresh(node);
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r == null) return;
                keySet.addExtra(r.optString("publickey", ""));
                txn.setIdentity(r.optString("publickey", ""), r.optString("address", ""));
                readBlockThenBook();
            }
            @Override public void onError(String message) { readBlockThenBook(); }
        });
    }

    private void readBlockThenBook() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) chainBlock = Util.dec(r.optString("block", "0")).longValue();
                txn.setChainBlock(chainBlock);
                scanBook();
            }
            @Override public void onError(String message) {}
        });
    }

    private void scanBook() {
        if (chainBlock <= 0) return;
        BookScanner.scan(node, (orders, truncated, raw) -> {
            if (truncated) return;                     // never act on a failed scan
            tape.ingest(orders, false, chainBlock, this::onFill);
            if (!keySet.ready()) return;               // never renew on a blind key set
            processor.process(orders, keySet.keys(), chainBlock, new DexProcessor.Listener() {
                @Override public void onRenewed(Order5 o) { /* silent — routine upkeep */ }
                @Override public void onRenewFailed(Order5 o, String why) {
                    Notifier.alert(getApplicationContext(), "Couldn't renew an order",
                            "Order @ " + PriceMath.fmtPrice(o.price()) + " — will retry. (" + why + ")");
                }
            });
            driveMaker(orders);
        });
    }

    /**
     * Keep a PUBLISHED ladder honest while the app is closed.
     *
     * This is a safety obligation, not a convenience: the publish dialog promises that a pegged
     * ladder withdraws itself when the price feed dies, and GTC renewal above actively keeps
     * those orders ALIVE on the book. Without this the app could be swiped away and leave real
     * funds quoting a price nobody was tracking any more.
     *
     * Exactly ONE actor drives the maker: pass() has already returned if the Activity is
     * foreground. Reload first — the Activity owns the same prefs and our in-memory slot map
     * would otherwise be stale enough to re-post rungs it already placed.
     */
    private void driveMaker(java.util.Map<String, Order5> orders) {
        // RE-CHECK, don't trust pass()'s gate: identity → block → book is three async node
        // round-trips, so that check is seconds stale and the user may have opened the app in
        // the meantime. Two engines have separate `working` flags and separate in-memory slot
        // maps, so both would read the same rung as missing and both would post it.
        if (MainActivity.FOREGROUND) return;
        makerCfg.reload();
        MakerEngine.Listener l = message -> { /* nobody is watching — the Maker tab replays it */ };
        maker.sweepTombstones(orders, keySet.keys(), chainBlock, l);
        maker.onBook(orders, keySet.keys(), chainBlock, l);
    }

    private void onFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                        boolean takerBuy, boolean partial) {
        boolean mine = order.isMine(keySet.keys());
        boolean isNew = db.addFill(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                takerBuy, partial, mine);
        if (isNew && mine) {
            db.addMyTrade(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                    !order.sell, true, order.orderId);
            Notifier.fill(getApplicationContext(), order.sell, size, price, partial);
        }
    }

    private void acquireTimedWakelock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pandadex:heartbeat");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60_000);
        } catch (Exception ignored) {}
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try { DexWatchWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try { HeartbeatReceiver.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getForegroundService(getApplicationContext(), 21,
                    new Intent(getApplicationContext(), DexKeepAliveService.class),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (node != null) { try { node.onDestroy(); } catch (Exception ignored) {} node = null; }
    }

    @Override public void onTimeout(int startId) {
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CH_FG, "Order watcher", NotificationManager.IMPORTANCE_LOW));
            Notifier.ensureChannels(this);
        }
    }

    private Notification fgNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 24,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("PandaDEX")
                .setContentText("Watching the order book — keeping your GTC orders alive")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        try {
            Notification n = fgNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

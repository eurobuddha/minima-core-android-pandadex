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
    private FillVerifier verifier;
    private DexHistory history;
    private FillSettler settler;
    private KeySet keySet;
    private FillTape tape;
    private boolean started = false;
    private final WatcherPassGate passGate = new WatcherPassGate(PASS_GAP_MS);
    private long chainBlock = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        if (!startForegroundCompat()) { stopSelf(); return; }
        node = new NodeApi(getApplicationContext(), this::onPaired);
        db = new DexDb(getApplicationContext());
        txn = new DexTxn(node, db);
        processor = new DexProcessor(getApplicationContext(), txn);
        makerCfg = new MakerConfig(getApplicationContext());
        maker = new MakerEngine(makerCfg, txn, o -> keySet != null && keySet.ready() && keySet.owns(o));
        verifier = new FillVerifier(node);
        history = new DexHistory(node, db);
        keySet = new KeySet(getApplicationContext(), this::onKeysReady);
        // The staleness ceiling must clear OUR polling gap, or every pass re-seeds and this
        // service can never record a fill (nor fire the "order filled" notification).
        tape = new FillTape(new FillTape.CancelLog() {
            @Override public void note(String coinid) { db.noteCancelled(coinid); }
            @Override public boolean consume(String coinid) { return db.wasCancelled(coinid); }
        }, PASS_GAP_MS * 2 + 60_000);
        // Same three-layer settlement as the Activity — history, then exclusive payout evidence
        // over the whole scan. Both must agree, or whichever sees a coin vanish first decides.
        settler = new FillSettler(history, () -> keySet.ready() ? chainBlock : 0, new FillSettler.Outcome() {
            @Override public void record(String spentCoin, Order5 order, java.math.BigDecimal size,
                                         java.math.BigDecimal price, boolean takerBuy,
                                         boolean partial, String txpowid, String evidence, String note) {
                recordFill(spentCoin, order, size, price, takerBuy, partial, txpowid, evidence, note);
            }
            @Override public void recordAt(String spentCoin, Order5 order, BigDecimal size,
                                           BigDecimal price, boolean takerBuy, boolean partial,
                                           String txpowid, String evidence, String note, long timeMs, long block) {
                recordFill(spentCoin, order, size, price, takerBuy, partial, txpowid, evidence, note, timeMs, block);
            }
            @Override public void recordVerified(FillSettler.Entry entry, DexHistory.Spend spend, Order5 order,
                                                  BigDecimal size, BigDecimal price, boolean takerBuy, boolean partial,
                                                  String note, long timeMs, long block) {
                recordFill(entry.coinid, order, size, price, takerBuy, partial, spend.txpowid,
                        FillSettler.CHAIN_VERIFIED, note, timeMs, block, entry, spend);
            }
            @Override public void cancelledVerified(FillSettler.Entry entry, DexHistory.Spend spend) {
                db.completeNonTrade(entry, spend);
            }
            @Override public void cancelled(String spentCoin) { db.noteCancelled(spentCoin); }
            @Override public void recoveryError(String message) { Notifier.alert(getApplicationContext(), "Trade history needs attention", message); }
        }, db);
        HeartbeatReceiver.schedule(this);
        started = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started || node == null) return START_STICKY;
        pass();
        return START_STICKY;
    }

    /** Registration is asynchronous; resume a cold start when the node becomes available. */
    private void onPaired(boolean enabled) {
        if (!started || node == null) return;
        if (!enabled) { passGate.invalidate(); keySet.invalidate(); return; }
        pass();
    }

    private boolean canWatch() {
        return started && node != null && node.isEnabled() && keySet != null
                && keySet.ready() && !MainActivity.FOREGROUND;
    }

    /** One unattended pass: identity → block → book → renew/sweep + record fills. */
    private void pass() {
        long now = android.os.SystemClock.elapsedRealtime();
        WatcherPassGate.Action action = passGate.next(now, started && node != null,
                MainActivity.FOREGROUND, node != null && node.isEnabled());
        if (action == WatcherPassGate.Action.NONE) return;
        if (action == WatcherPassGate.Action.REGISTER) { node.reRegister(); return; }
        acquireTimedWakelock();

        keySet.refresh(node);
    }

    /** Read a fresh block/book only after all wallet addresses have been derived. */
    private void onKeysReady() {
        if (!passGate.resumeAfterKeys(canWatch())) return;
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (!json.optBoolean("status", false) || r == null || !canWatch()) return;
                keySet.addExtra(r.optString("publickey", ""));
                keySet.addExtraAddr(r.optString("address", ""));
                txn.setIdentity(r.optString("publickey", ""), r.optString("address", ""));
                DexContract.ensureScript(node, new DexContract.Ready() {
                    public void ok() { if (canWatch()) readBlockThenBook(); }
                    public void failed(String why) {
                        Notifier.alert(getApplicationContext(), "Order watcher paused", "Covenant check failed: " + why);
                    }
                });
            }
            @Override public void onError(String message) { }
        });
    }

    private void readBlockThenBook() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (!json.optBoolean("status", false) || r == null || !canWatch()) return;
                if (r != null) chainBlock = Util.dec(r.optString("block", "0")).longValue();
                txn.setChainBlock(chainBlock);
                scanBook();
            }
            @Override public void onError(String message) {}
        });
    }

    private void scanBook() {
        if (!canWatch() || chainBlock <= 0) return;
        BookScanner.scan(node, (orders, truncated, raw) -> {
            if (truncated || !canWatch()) return;                     // never act on a failed scan
            try {
                tape.ingest(orders, false, chainBlock, settler);
            } catch (RuntimeException e) { return; }
            if (!keySet.ready()) return;               // never renew on a blind key set
            makerCfg.reload();   // the maker's rungs are its own to renew — see driveMaker
            processor.process(orders, keySet.keys(), keySet.addrs(),
                    maker.ownedOrderIds(), chainBlock, new DexProcessor.Listener() {
                @Override public void onPaused(String why) {
                    Notifier.alert(getApplicationContext(), "Order upkeep needs attention", why);
                }
                @Override public void onRenewed(Order5 o) { /* silent — routine upkeep */ }
                @Override public void onRenewFailed(Order5 o, String why) {
                    Notifier.alert(getApplicationContext(), "Order upkeep needs attention",
                            "Order @ " + PriceMath.fmtPrice(o.price()) + " — " + why);
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
        if (!canWatch()) return;
        makerCfg.reload();
        MakerEngine.Listener l = message -> { /* nobody is watching — the Maker tab replays it */ };
        maker.sweepTombstones(orders, keySet.keys(), chainBlock, l);
        maker.onBook(orders, keySet.keys(), chainBlock, l);
    }

    private void recordFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                            boolean takerBuy, boolean partial, String txpowid, String evidence, String note) {
        recordFill(spentCoin, order, size, price, takerBuy, partial, txpowid, evidence,
                note + ChainEvidence.OBSERVED_TIME_NOTE, System.currentTimeMillis(), chainBlock);
    }

    private void recordFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                            boolean takerBuy, boolean partial, String txpowid, String evidence, String note,
                            long timeMs, long block) {
        recordFill(spentCoin,order,size,price,takerBuy,partial,txpowid,evidence,note,timeMs,block,null,null);
    }

    private void recordFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                            boolean takerBuy, boolean partial, String txpowid, String evidence, String note,
                            long timeMs, long block, FillSettler.Entry entry, DexHistory.Spend spend) {
        boolean mine = order.isMine(keySet.keys(), keySet.addrs());
        boolean isNew = entry == null
                ? db.recordVerifiedFill(spentCoin, timeMs, block, price, size, takerBuy, partial, mine, order, txpowid, evidence, note)
                : db.completeFill(entry, spend, order, timeMs, block, price, size, takerBuy, partial, mine, evidence, note);
        if (isNew && mine && FillSettler.recentForNotification(timeMs, System.currentTimeMillis())) {
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
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        started = false; // Ignore late pairing/scan callbacks before releasing the transport.
        passGate.invalidate();
        super.onDestroy();
        if (keySet != null) keySet.close();
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

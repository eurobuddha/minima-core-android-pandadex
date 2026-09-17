package com.eurobuddha.pandadex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * PandaDEX host. The snappiness contract lives here:
 *   - first paint from SQLite BEFORE the NodeApi exists (never "connecting…" on a cold open)
 *   - our OWN 30s poll clock, with NEWBLOCK/NEWBALANCE as accelerators (not the only trigger)
 *   - immediate post-action refresh; the shared BookRepository coalesces + throttles
 *   - only the visible tab renders; inputs are never rebuilt under the user
 */
public class MainActivity extends AppCompatActivity {

    public static volatile boolean FOREGROUND = false;

    private static final long POLL_MS = 30_000;
    private static final int TAB_TRADE = 0, TAB_CHART = 1, TAB_TAPE = 2, TAB_ORDERS = 3,
            TAB_ASSETS = 4, TAB_MAKER = 5;
    private static final String[] TAB_NAMES = {"TRADE", "CHART", "TRADES", "ORDERS", "ASSETS", "MAKER"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private NodeApi node;
    private DexDb db;
    private DexStats stats;
    private BookRepository repo;
    private PoolLiquidityRepository poolRepo;
    private DexTxn txn;
    private KeySet keySet;
    private FillVerifier verifier;
    private DexHistory history;
    private FillSettler settler;
    private Pending pending;
    private DexProcessor processor;
    private boolean keepAliveAsked = false;
    private boolean scriptReady = false;   // covenant verified+registered on this node
    private DexHistory.Spend verifiedTakerSpend;
    private java.util.Map<String,DexHistory.Spend> verifiedTakerSpends=java.util.Collections.emptyMap();
    private String awaitingPayout = "";
    private String awaitingIntent = "";
    private volatile boolean busy = false;          // a spend is in flight — lock the CTA
    /** Cancels posted but not yet confirmed, so the "funds are back" line waits for the coins
     *  to actually leave the book rather than for the transactions to be sent. */
    private int awaitingCancels = 0;
    private Runnable restQueue;                     // limit balance to place once a sweep lands
    private java.util.List<String> restQueueCoins;  // the swept coins we're waiting to vanish
    private long restQueueBlock = 0;                // when we started waiting
    /** After this delay, stop the dependent resting order; keep checking the unresolved trade. */
    private static final int SWEEP_DEADLINE_BLOCKS = 6;
    /** Order coins this device is currently taking — shown as FILLING on the ladder. */
    private final java.util.Set<String> filling = new java.util.HashSet<>();
    private java.util.List<String> awaitingFill;    // selected source coins; confirmation also requires exact transaction and payout evidence
    private boolean awaitingBuy;
    private BigDecimal awaitingMinima = BigDecimal.ZERO, awaitingPrice = BigDecimal.ZERO;
    private BigDecimal awaitingProceeds = BigDecimal.ZERO;
    private String awaitingProceedsTok = "";
    private long awaitingFillBlock = 0;
    private String awaitingTxpowid = "";
    private String awaitingSourceKind = "";
    private boolean checkingAwaitingFill = false;
    private boolean checkingRestQueue = false;
    private BroadcastReceiver notifyReceiver;
    private ActivityResultLauncher<String> saveTradeExportLauncher;
    private TradeExportSession tradeExportSession;
    private boolean exportNoticeShowing;

    private TradeView trade;
    private ChartTab chartTab;
    private TapeTab tapeTab;
    private OrdersTab ordersTab;
    private AssetsTab assetsTab;
    private MakerTab makerTab;
    private MakerConfig makerCfg;
    private MakerEngine maker;
    private FrameLayout content;
    private LinearLayout tabBar;
    private TextView pairPill, blockPill, footer;
    private int tab = TAB_TRADE;
    private boolean paired = false, pairingKnown = false;
    private String minimaBalanceError = "", usdtBalanceError = "";
    private Object pairingAttempt, pairingConnection;
    private boolean inputFocused = false;
    private long chainBlock = 0;
    private BigDecimal minimaSendable = BigDecimal.ZERO, usdtSendable = BigDecimal.ZERO;
    private BigDecimal minimaConfirmed = BigDecimal.ZERO, usdtConfirmed = BigDecimal.ZERO;
    private BigDecimal minimaUnconfirmed = BigDecimal.ZERO, usdtUnconfirmed = BigDecimal.ZERO;
    private int minimaCoins = 0, usdtCoins = 0;
    private long minimaBalanceAtMs = 0, usdtBalanceAtMs = 0;
    private String receiveAddr = "";
    private boolean makerSplitPending = false;
    private String makerSplitPendingToken = "";
    private long makerSplitPendingBlock = 0;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            poll(true);
            ui.postDelayed(this, POLL_MS);
        }
    };

    /** Repaint once a second WHILE something is in flight, so the pending clock and the stage
     *  line actually move. Costs nothing when idle — it renders from memory, no node calls. */
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (!pending.rows().isEmpty() || !stage().isEmpty()) repaint();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        restoreActivityLog(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        db = new DexDb(this);
        stats = new DexStats(db);
        pending = db.pendingReceipts();
        restoreAwaitingFill();
        keySet = new KeySet(this, this::onWalletKeysReady);
        tradeExportSession = new androidx.lifecycle.ViewModelProvider(this).get(TradeExportSession.class);
        saveTradeExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), tradeExportSession::selected);

        setContentView(buildChrome());
        tradeExportSession.state.observe(this, phase -> renderExportSession());

        // node wiring AFTER first paint (local-first)
        node = new NodeApi(this, enabled -> {
            paired = enabled;
            pairingKnown = true;
            scriptReady = false;
            pairingAttempt = null;
            if (txn != null) txn.invalidateIdentity();
            receiveAddr = "";
            keySet.invalidate();
            repaint();
            if (enabled) onPaired();
        });
        repo = new BookRepository(node, db);
        poolRepo = new PoolLiquidityRepository(node, db::rememberMarketPool);
        verifier = new FillVerifier(node);
        history = new DexHistory(node, db);
        txn = new DexTxn(node, db);
        processor = new DexProcessor(this, txn);
        maker = new MakerEngine(makerCfg, txn, o -> keySet.ready() && keySet.owns(o));
        // History first, then EXCLUSIVE payout evidence over the whole scan at once — a payout
        // coin can only be evidence for one order, which cannot be decided one order at a time.
        settler = new FillSettler(history, () -> keySet.ready() ? chainBlock : 0, new FillSettler.Outcome() {
            @Override public void record(String spentCoin, Order5 order, BigDecimal size,
                                         BigDecimal price, boolean takerBuy, boolean partial,
                                         String txpowid, String evidence, String note) {
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
                stats.invalidate();
            }
            @Override public void cancelled(String spentCoin) { db.noteCancelled(spentCoin); }
            @Override public void recoveryError(String message) { setStage(message); }
            @Override public void receiptsRepaired() {stats.invalidate();repaint();}
        }, db);
        repo.setFillSink(settler);
        repo.subscribe((orders, syncing) -> {
            if (isFinishing() || isDestroyed()) return;
            if (syncing || !paired || !keySet.ready()) { repaint(); return; }
            pending.reconcile(history, orders, chainBlock, o -> keySet.ready() && keySet.owns(o), new Pending.Listener() {
                @Override public void onRecoveryError(String message) { setStage(message); }
                @Override public void onLive(Pending.Row r) {
                    // Creation proof does not imply that the output is still unspent.
                    String what = (r.buy ? "Buy " : "Sell ") + PriceMath.fmt(r.minima)
                            + " MINIMA @ " + PriceMath.fmtPrice(r.price);
                    setStage(what + " — creation verified on-chain. Check Orders for its current status.");
                    Notifier.alert(MainActivity.this, "Order creation verified", what + " was created on-chain");
                }
                @Override public void onSettled(Pending.Row r) {
                    if (!Pending.CANCEL.equals(r.kind)) { setStage(r.isRenewal()?"Order renewal verified on-chain":"Price update verified on-chain"); return; }
                    // Pending has matched the included spending transaction and its refund
                    if (awaitingCancels > 1 && --awaitingCancels > 0) {
                        setStage(awaitingCancels + " cancel" + (awaitingCancels == 1 ? "" : "s")
                                + " still confirming…");
                        return;
                    }
                    awaitingCancels = 0;
                    setStage("Order cancelled — funds back in your wallet");
                }
                @Override public void onCancelledInstead(Pending.Row r) {
                    setStage((r.isRenewal()?"The renewal":"The price edit")+" did not take effect: the source order was refunded on-chain.");
                    repaint();
                }
                @Override public void onFilledInstead(Pending.Row r) {
                    setStage("That order filled before the requested change. The spending transaction contains the expected maker payment. Check the trade records for its receipt.");
                    repaint();
                }
                @Override public void onGaveUp(Pending.Row r) {
                    setStage(Pending.PLACE.equals(r.kind)
                            ? "Order creation is still unresolved. Its receipt is retained while historical transactions are checked."
                            : "Still no confirmation for that request — check Orders.");
                }
            });
            reconcileSweep(orders);
            reconcileTakerFill(orders);
            // the market maker rides the same book updates as everything else; it rate-limits
            // itself internally because every adjustment costs proof-of-work
            // FOREGROUND gate: while we are paused the background service drives the maker, and
            // two actors posting from the same slot map would duplicate rungs — the same
            // single-actor discipline DexProcessor uses for renewals.
            if (paired && scriptReady && keySet.ready() && chainBlock > 0 && !busy && FOREGROUND) {
                // tombstone sweep runs ARMED OR NOT: cancelled-but-unconfirmed orders and
                // late-confirming orphans must be chased even after a withdraw disarmed us
                maker.sweepTombstones(orders, keySet.keys(), chainBlock, makerListener);
                maker.onBook(orders, keySet.keys(), chainBlock, makerListener);
            }
            filling.retainAll(orders.keySet());   // never let a marker stick
            // NOTE: the `myorder` table has no reader — the ORDERS recovery path it was meant
            // to feed was never built, and it stores an empty `json` so it could not serve one
            // anyway. Writing it on every book callback was a main-thread SQLite insert per
            // order per poll for nothing. Left OFF deliberately; wire the reader first.
            // the foreground Activity owns renewals while it's up (the service stands down)
            if (paired && scriptReady && keySet.ready() && chainBlock > 0 && FOREGROUND) {
                processor.process(orders, keySet.keys(), keySet.addrs(),
                        maker.ownedOrderIds(), chainBlock, new DexProcessor.Listener() {
                    @Override public void onPaused(String why) { setStage(why); }
                    @Override public void onRenewed(Order5 o) {}
                    @Override public void onRenewFailed(Order5 o, String why) {
                        toast("Order upkeep needs checking @ " + PriceMath.fmtPrice(o.price()) + " — " + why);
                    }
                });
            }
            repaint();
        });
        poolRepo.subscribe((pools, syncing) -> repaint());

        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String event = intent.getStringExtra("event");
                if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) poll(eventRefreshesPools(event));
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(NodeTransportService.EVENT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

        // Reveal exactly one tab. MUST run — the tab views are added GONE above.
        if (savedInstanceState != null) tab = savedInstanceState.getInt(KEY_TAB, TAB_TRADE);
        selectTab(tab);
        if (awaitingFill != null) setStage("Trade receipt restored · checking on-chain");
    }

    private static final String KEY_TAB = "tab";

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_TAB, tab);
        out.putStringArrayList("activity_log", new java.util.ArrayList<>(logLines));
    }

    private void onPaired() {
        scriptReady = false;
        pairingAttempt = new Object();
        pairingConnection = NodeApi.connectionGeneration();
        txn.invalidateIdentity();
        receiveAddr = "";
        keySet.invalidate();
        keySet.refresh(node);
        repaint();
        poll(true);
        maybeStartKeepAlive();
    }

    private boolean currentPairing(Object attempt) {
        return attempt != null && pairingAttempt == attempt && paired && pairingConnection == NodeApi.connectionGeneration()
                && !isFinishing() && !isDestroyed();
    }

    /** Reuse the watcher's keys -> address -> covenant ordering for this pairing only. */
    private void onWalletKeysReady() {
        repaint();
        final Object attempt = pairingAttempt;
        if (!currentPairing(attempt) || !keySet.ready()) return;
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!currentPairing(attempt)) return;
                JSONObject r = json.optJSONObject("response");
                String key = r == null ? "" : r.optString("publickey", "");
                String address = r == null ? "" : r.optString("address", "");
                if (!TxValidation.truthy(json, "status") || !FundingCoins.hex(key) || !FundingCoins.hex(address)) {
                    retryIdentity(attempt); return;
                }
                keySet.addExtra(key);
                keySet.addExtraAddr(address);
                txn.setIdentity(key, address);
                receiveAddr = r.optString("miniaddress", address);
                repaint();
                // Registration lives in the node, so every new pairing must verify it again.
                DexContract.ensureScript(node, new DexContract.Ready() {
                    @Override public void ok() {
                        if (!currentPairing(attempt)) return;
                        scriptReady = true;
                        db.putMeta("tracked_v2", "1");
                        repo.refresh();
                        if (poolRepo != null) poolRepo.refresh();
                        repaint();
                    }
                    @Override public void failed(String why) {
                        if (!currentPairing(attempt)) return;
                        scriptReady = false;
                        toast("Covenant check failed: " + why);
                    }
                });
            }
            @Override public void onError(String message) { retryIdentity(attempt); }
        });
    }

    private void retryIdentity(Object attempt) {
        if (!currentPairing(attempt)) return;
        ui.postDelayed(() -> { if (currentPairing(attempt)) onPaired(); }, 5_000);
    }

    /**
     * Arm the unattended watcher once the user actually has skin in the game (an open order):
     * a pure browser runs no background PoW. Also asks once for notifications + battery-opt
     * exemption, without which Samsung Doze kills the renewal loop overnight.
     */
    private void maybeStartKeepAlive() {
        if (keepAliveAsked) return;
        keepAliveAsked = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 91);
            }
        } catch (Exception ignored) {}
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())
                    && !"1".equals(db.meta("battopt_asked", ""))) {
                db.putMeta("battopt_asked", "1");
                startActivity(new Intent(android.provider.Settings
                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
        try {
            DexWatchWorker.schedule(this);
            HeartbeatReceiver.schedule(this);
            ContextCompat.startForegroundService(this, new Intent(this, DexKeepAliveService.class));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ chrome

    /**
     * Flip the palette and rebuild.
     *
     * recreate() rather than a restyle pass: every view bakes its colours at construction and
     * the tabs are deliberately built once (rebuilding them under the user eats whatever they
     * are typing), so there is no full-restyle path to call. The Activity already survives a
     * rebuild — the selected tab is in onSaveInstanceState and the maker reloads from prefs.
     *
     * NOT while a transaction is in flight: recreate() destroys the NodeApi, and a create whose
     * onPosted callback is lost leaves a real on-chain order with no slot record — an orphan
     * nothing can find. Same reasoning as the withdraw-while-working guard.
     */
    private void toggleTheme() {
        if (busy || (maker != null && maker.isWorking())) {
            toast("Finish the current transaction first — then switch");
            return;
        }
        Design.toggle(this);
        recreate();
    }

    private android.view.View buildChrome() {
        int pad = Design.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG());
        // UTXO / MinimaCore KeyboardInsets: reserve the greater of IME and navigation
        // space, never add both. The weighted ScrollView then shrinks above the keyboard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            boolean compact = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                    && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            // MinimaCore KeyboardInsets: recover form space on short landscape screens.
            if (headerChrome != null) headerChrome.setVisibility(compact ? android.view.View.GONE : android.view.View.VISIBLE);
            if (tabBar != null) tabBar.setVisibility(compact ? android.view.View.GONE : android.view.View.VISIBLE);
            boolean typing = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            // Casino's one-line ticker while typing leaves space for the entry fields.
            if (logText != null) logText.setMaxLines(typing ? 1 : 4);
            if (logTitle != null) logTitle.setVisibility(typing ? android.view.View.GONE : android.view.View.VISIBLE);
            if (ime.bottom > 0 && ime.bottom != keyboardBottom && trade != null) {
                // Salon's post-layout focus reveal; include the rest of the entry panel.
                trade.post(trade::revealEntryAboveKeyboard);
            }
            keyboardBottom = ime.bottom;
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        headerChrome = header;
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, pad, pad, Design.dp(this, 6));
        TextView logo = new TextView(this);
        logo.setText("◆ PANDADEX");
        logo.setTextColor(Design.TEXT());
        logo.setTypeface(Design.monoBold());
        logo.setTextSize(15f);
        header.addView(logo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // The build version lives HERE, not buried in the footer: a silently-failed update
        // (an over-the-top install that doesn't take) once looked like an app bug for a whole
        // round of testing because two phones were running different builds.
        TextView verPill = Design.pill(this, "v" + BuildConfig.VERSION_NAME,
                Design.SURFACE2(), Design.DIM2());
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vp.rightMargin = Design.dp(this, 6);
        header.addView(verPill, vp);

        // Light/dark switch. Both palettes were built long ago — every colour resolves through
        // Design.pick() and there is even a matching light dialog theme — but nothing ever
        // called Design.toggle, so the light mode was unreachable.
        TextView themePill = Design.pill(this, Design.isDark() ? "☀" : "☾",
                Design.SURFACE2(), Design.DIM());
        themePill.setOnClickListener(v -> toggleTheme());
        Design.pressable(themePill);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.rightMargin = Design.dp(this, 6);
        header.addView(themePill, tp);

        pairPill = Design.pill(this, "PAIRING…", Design.SURFACE2(), Design.DIM());
        header.addView(pairPill);
        blockPill = Design.pill(this, "# —", Design.SURFACE2(), Design.DIM());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Design.dp(this, 6);
        header.addView(blockPill, bp);
        root.addView(header);

        ScrollView scroller = new ScrollView(this);
        content = new FrameLayout(this);
        scroller.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // All five tabs share ONE FrameLayout, so exactly one may ever be visible. They are
        // added GONE and selectTab() (called at the end of onCreate) reveals the current one.
        // Without that call every tab painted stacked on top of the others — the chart and
        // assets text printed straight through the order panel while the user was typing.
        trade = new TradeView(this);
        chartTab = new ChartTab(this);
        tapeTab = new TapeTab(this);
        ordersTab = new OrdersTab(this);
        assetsTab = new AssetsTab(this);
        makerCfg = new MakerConfig(this);
        makerTab = new MakerTab(this, makerCfg);
        for (android.view.View v : new android.view.View[]{trade, chartTab, tapeTab, ordersTab, assetsTab, makerTab}) {
            v.setVisibility(android.view.View.GONE);
            content.addView(v);
        }

        // Casino's bounded, timestamped ticker/log, outside the scrolling form.
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setPadding(pad, Design.dp(this, 6), pad, Design.dp(this, 6));
        logBox.setBackgroundColor(Design.SURFACE2());
        logTitle = new TextView(this);
        logTitle.setTextSize(10f); logTitle.setTypeface(Design.sansBold()); logTitle.setTextColor(Design.ACCENT());
        logText = new TextView(this);
        logText.setTextSize(10f); logText.setTypeface(Design.mono()); logText.setTextColor(Design.TEXT());
        logText.setMaxLines(4); logText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        logText.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        logBox.addView(logTitle); logBox.addView(logText);
        logBox.setOnClickListener(v -> showActivityLog());
        logBox.setVisibility(android.view.View.GONE);
        root.addView(logBox);

        tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(Design.SURFACE());
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]);
            t.setTextSize(10f);
            t.setTypeface(Design.sansBold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11));
            t.setOnClickListener(v -> selectTab(idx));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar);

        footer = new TextView(this);
        footer.setText("v" + BuildConfig.VERSION_NAME);
        footer.setTextColor(Design.DIM2());
        footer.setTypeface(Design.mono());
        footer.setTextSize(9f);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(pad, 0, pad, Design.dp(this, 4));
        root.addView(footer);
        return root;
    }

    private void selectTab(int idx) {
        tab = idx;
        inputFocused = false;
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == idx ? Design.ACCENT() : Design.DIM2());
            t.setBackgroundColor(i == idx ? Design.ACCENT_SOFT() : Design.SURFACE());
        }
        trade.setVisibility(idx == TAB_TRADE ? android.view.View.VISIBLE : android.view.View.GONE);
        chartTab.setVisibility(idx == TAB_CHART ? android.view.View.VISIBLE : android.view.View.GONE);
        tapeTab.setVisibility(idx == TAB_TAPE ? android.view.View.VISIBLE : android.view.View.GONE);
        ordersTab.setVisibility(idx == TAB_ORDERS ? android.view.View.VISIBLE : android.view.View.GONE);
        assetsTab.setVisibility(idx == TAB_ASSETS ? android.view.View.VISIBLE : android.view.View.GONE);
        makerTab.setVisibility(idx == TAB_MAKER ? android.view.View.VISIBLE : android.view.View.GONE);
        repaint();
    }

    /** Repaint the VISIBLE tab only. */
    private void repaint() {
        if (pairPill == null || isFinishing() || isDestroyed()) return;
        renderActivityLog();
        pairPill.setText(nodeLabel(paired, pairingKnown));
        pairPill.setTextColor(paired ? Design.IN() : Design.ACCENT());
        blockPill.setText("# " + (chainBlock > 0 ? chainBlock : "—"));
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == tab ? Design.ACCENT() : Design.DIM2());
            t.setBackgroundColor(i == tab ? Design.ACCENT_SOFT() : Design.SURFACE());
        }
        if (repo == null) return;
        switch (tab) {
            case TAB_TRADE:  trade.render(repo.book(), !paired || !repo.current(), chainBlock, pending.rows()); break;
            case TAB_CHART:  chartTab.render(); break;
            case TAB_TAPE:   tapeTab.render(); break;
            case TAB_ORDERS: ordersTab.render(); break;
            case TAB_ASSETS: assetsTab.render(); break;
            case TAB_MAKER:  makerTab.render(); break;
        }
    }

    /** Every precondition for spending funds: paired, identity read, covenant registered. */
    private boolean ready() {
        if (isFinishing() || isDestroyed()) return false;
        if (busy) { toast("A transaction is already in progress"); return false; }
        if (pending != null && !pending.healthy()) { toast("Receipt storage needs recovery. Preserve app data; do not retry trades."); return false; }
        if (!paired) { toast("Pair with your node first"); return false; }
        if (!currentPairing(pairingAttempt)) { toast("Node connection changed. Wait for wallet verification before trading."); return false; }
        if (txn == null || txn.pubkey().isEmpty() || txn.hexAddr().isEmpty()) {
            toast("Still reading your wallet identity — try again in a moment");
            return false;
        }
        if (!keySet.ready()) { toast("Verifying wallet ownership — please wait"); return false; }
        if (node.hasInterruptedWrite()) { toast(NodeApi.ERR_WRITE_UNCERTAIN); return false; }
        if (!scriptReady) {
            toast("The order-book contract isn't registered on your node yet");
            return false;
        }
        return true;
    }

    /**
     * The most recent observed trade as [price, ageMs], or null if none.
     *
     * The headline price is ALWAYS the last trade — it never substitutes the mid. Each price
     * slot in the UI has exactly one meaning: the big number is the last trade, the number
     * between the bid and the ask is the mid. An earlier build switched the headline between
     * the two depending on recency, which meant the same slot showed different quantities at
     * different moments and needed a label to explain itself.
     */
    public Object[] lastTrade() {
        Object[] lf = stats == null ? null : stats.lastFill();
        if (lf == null) return null;
        long age = System.currentTimeMillis() - (Long) lf[0];
        if (age < 0) age = 0;
        return new Object[]{(BigDecimal) lf[1], age};
    }

    /** Mid-price of the live book (best bid/ask) — orders only, so it is identical on every
     *  device looking at the same book. Never falls back to a trade price. */
    public BigDecimal bookMid() {
        BigDecimal bestAsk = null, bestBid = null;
        for (Order5 o : book().values()) {
            if (o.expired(chainBlock)) continue;
            BigDecimal p = o.price();
            if (o.sell) { if (bestAsk == null || p.compareTo(bestAsk) < 0) bestAsk = p; }
            else { if (bestBid == null || p.compareTo(bestBid) > 0) bestBid = p; }
        }
        if (bestAsk != null && bestBid != null) {
            return bestAsk.add(bestBid).divide(new BigDecimal(2), PriceMath.PRICE_DP,
                    java.math.RoundingMode.HALF_UP);
        }
        // one-sided book: show that side rather than inventing a mid; NEVER substitute a
        // trade price here — Assets and the ladder must always agree on what "mid" means
        return bestAsk != null ? bestAsk : bestBid;
    }

    public String receiveAddress() { return receiveAddr; }

    public void repaintTrade() { repaint(); }

    // ---- the one place the app narrates what it is doing -------------------------------
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();
    private LinearLayout logBox;
    private android.view.View headerChrome;
    private int keyboardBottom;
    private TextView logTitle, logText, logDialogText;
    private AlertDialog activityLogDialog;
    private String lastLogMessage = "";
    private String stage = "";
    private long stageAtMs = 0;
    private static final long STAGE_HOLD_MS = 45_000;

    /** Say what is happening RIGHT NOW. Blocks take ~50s, so silence during that wait reads
     *  as a broken app — this is the running commentary for both placing and filling. */
    public void setStage(String s) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> setStage(s)); return;
        }
        stage = s == null ? "" : s;
        stageAtMs = System.currentTimeMillis();
        if (!stage.isEmpty() && !stage.equals(lastLogMessage)) {
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date(stageAtMs));
            logLines.addFirst(stamp + "  " + (stage.length() > 512 ? stage.substring(0,512) : stage));
            while (logLines.size() > 60) logLines.removeLast();
            lastLogMessage = stage;
        }
        repaint();
    }

    public String stage() {
        if (stage.isEmpty()) return "";
        if (!busy && awaitingFill == null && System.currentTimeMillis() - stageAtMs > STAGE_HOLD_MS) return "";
        return stage;
    }

    private void restoreActivityLog(Bundle saved) {
        if (saved == null) return;
        java.util.ArrayList<String> lines = saved.getStringArrayList("activity_log");
        if (lines == null) return;
        for (String line : lines) {
            if (line != null && line.length() <= 530) logLines.addLast(line);
            if (logLines.size() == 60) break;
        }
    }

    private void renderActivityLog() {
        if (logBox == null) return;
        logBox.setVisibility(logLines.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        logTitle.setText((busy ? "TRANSACTION IN PROGRESS" : awaitingFill != null ? "CHECKING ON-CHAIN" : "ACTIVITY LOG")
                + " · tap for log");
        StringBuilder recent = new StringBuilder();
        int count = 0;
        for (String line : logLines) {
            if (count++ == 3) break;
            if (recent.length() > 0) recent.append('\n');
            recent.append(line);
        }
        logText.setText(recent.toString());
        if (logDialogText != null) {
            String full = android.text.TextUtils.join("\n\n", logLines);
            if (!android.text.TextUtils.equals(logDialogText.getText(), full)) logDialogText.setText(full);
        }
    }

    private void showActivityLog() {
        if (activityLogDialog != null && activityLogDialog.isShowing()) return;
        TextView text = new TextView(this);
        text.setText(android.text.TextUtils.join("\n\n", logLines));
        text.setTypeface(Design.mono()); text.setTextSize(12f); text.setTextColor(Design.TEXT());
        text.setTextIsSelectable(true);
        int pad = Design.dp(this,16); text.setPadding(pad,pad,pad,pad);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        text.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        AlertDialog dialog = new AlertDialog.Builder(this,Design.dialogTheme()).setTitle("Activity log")
                .setView(scroll).setPositiveButton("Close",null).create();
        activityLogDialog = dialog;
        logDialogText = text;
        dialog.setOnDismissListener(closed -> {
            if (activityLogDialog == dialog) {
                activityLogDialog = null;
                logDialogText = null;
            }
        });
        dialog.show();
    }

    private void hideTradeKeyboard() {
        android.view.View focused = getCurrentFocus();
        if (focused == null) return;
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(),0);
        focused.clearFocus();
    }

    // ------------------------------------------------------------------ polling

    private void poll() { poll(true); }

    static boolean eventRefreshesPools(String event) {
        return "NEWBLOCK".equals(event);
    }

    private void poll(boolean includePools) {
        if (node == null || isFinishing() || isDestroyed()) return;
        if (awaitingFill == null) restoreAwaitingFill();
        if (!node.isEnabled()) { node.reRegister(); return; }
        // NOTE: polling must NEVER be gated on input focus. It used to be — inherited from an
        // app whose refresh rebuilt the whole form and ate in-progress typing — but this
        // screen builds its inputs ONCE and only re-renders read-only sections, so there is
        // nothing to protect. The gate meant that after placing an order (which leaves the
        // amount field focused) the maker's own phone stopped reading the chain entirely:
        // block height froze, the book never refreshed, and the order stayed on "Sending…"
        // while a REMOTE phone — not typing, so still polling — saw and traded the order
        // first. Your own order must never appear on someone else's device before yours.
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                long tip = ChainEvidence.tipBlock(json);
                if (tip <= 0) return; // Retain the last valid observation; never coerce bad data into a height.
                chainBlock = tip;
                if (repo != null) repo.setChainBlock(chainBlock);
                if (txn != null) txn.setChainBlock(chainBlock);
                repaint();
            }
            @Override public void onError(String message) {}
        });
        balances();
        if (repo != null) repo.refresh();
        if (includePools && poolRepo != null) poolRepo.refresh();
    }

    static String nodeLabel(boolean paired, boolean known) {
        return paired ? "NODE ✓" : known ? "PAIR IN MINIMA → APPS" : "CONNECTING…";
    }

    static String balanceMessage(boolean paired, boolean known, boolean failed) {
        if (!paired) return known ? "Open MinimaCore → Apps and check that PandaDEX is enabled."
                : "Connecting to MinimaCore…";
        return failed ? "Could not read this balance. Tap to retry." : "Loading balance from MinimaCore…";
    }

    String balanceMessage(boolean minima) {
        return balanceMessage(paired, pairingKnown, !(minima ? minimaBalanceError : usdtBalanceError).isEmpty());
    }

    /** Placeholder for an empty receive address: only claims "loading" while actually paired. */
    static String receiveLoadingMessage(boolean paired, boolean known) {
        return paired ? "Loading receive address from MinimaCore…" : balanceMessage(false, known, false);
    }

    String receiveLoadingMessage() { return receiveLoadingMessage(paired, pairingKnown); }

    /** Placeholder for an empty open-orders list while makerBookReady() is false: the book
     *  can be "loading" only once paired — an unpaired user is told to pair, not to wait. */
    static String ordersWaitingMessage(boolean paired, boolean known) {
        return paired ? OrdersTab.WAITING_ORDERS : balanceMessage(false, known, false);
    }

    String ordersWaitingMessage() { return ordersWaitingMessage(paired, pairingKnown); }

    void retryBalances() {
        if (node == null) return;
        if (!paired) { node.reRegister(); return; }
        minimaBalanceError = ""; usdtBalanceError = "";
        repaint(); balances();
    }

    private void balances() {
        node.cmd("balance tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                BalanceMeta b = balanceMeta(json, Util.MINIMA_TOKENID);
                if(b.atMs<=0) { minimaBalanceError="Invalid balance reply"; repaint(); return; }
                minimaBalanceError=""; // Keep exact stock precision; format only at the view.
                minimaSendable = b.sendable;
                minimaConfirmed = b.confirmed;
                minimaUnconfirmed = b.unconfirmed;
                minimaCoins = b.coins;
                minimaBalanceAtMs = b.atMs;
                maybeClearMakerSplitPending(Util.MINIMA_TOKENID, b);
                repaint();
            }
            @Override public void onError(String message) { minimaBalanceError=message; repaint(); }
        });
        node.cmd("balance tokenid:" + DexContract.USDT_ID, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                BalanceMeta b = balanceMeta(json, DexContract.USDT_ID);
                if(b.atMs<=0) { usdtBalanceError="Invalid balance reply"; repaint(); return; }
                usdtBalanceError=""; // A failed read is not a fresh zero balance.
                usdtSendable = b.sendable;
                usdtConfirmed = b.confirmed;
                usdtUnconfirmed = b.unconfirmed;
                usdtCoins = b.coins;
                usdtBalanceAtMs = b.atMs;
                maybeClearMakerSplitPending(DexContract.USDT_ID, b);
                repaint();
            }
            @Override public void onError(String message) { usdtBalanceError=message; repaint(); }
        });
    }

    static final class BalanceMeta {
        BigDecimal sendable = BigDecimal.ZERO;
        BigDecimal confirmed = BigDecimal.ZERO;
        BigDecimal unconfirmed = BigDecimal.ZERO;
        int coins = 0;
        long atMs = 0;
        BigDecimal locked() {
            BigDecimal locked = confirmed.subtract(sendable);
            return locked.signum() < 0 ? BigDecimal.ZERO : locked;
        }
    }

    static BalanceMeta balanceMeta(JSONObject json) { return balanceMeta(json,null); }

    /** Parse one token-scoped stock balance response, preserving unknown versus observed zero. */
    static BalanceMeta balanceMeta(JSONObject json,String expectedToken) {
        BalanceMeta out = new BalanceMeta();
        if (json == null || !TxValidation.truthy(json, "status")) return out;
        try {
            if(expectedToken!=null&&!FundingCoins.hex(expectedToken))return out;
            Object resp=json.opt("response");JSONObject row;
            if(resp instanceof JSONArray) {
                JSONArray rows=(JSONArray)resp;
                // Stock balance always emits native MINIMA. An absent non-native token
                // emits [] for its scoped query and is a genuine zero observation.
                if(rows.length()==0) {
                    if(expectedToken!=null&&!Util.MINIMA_TOKENID.equalsIgnoreCase(expectedToken))out.atMs=System.currentTimeMillis();
                    return out;
                }
                if(rows.length()!=1)return out;
                row=rows.getJSONObject(0);
            }else if(resp instanceof JSONObject)row=(JSONObject)resp;
            else return out;
            if(expectedToken!=null&&!expectedToken.equalsIgnoreCase(MakerConfig.jsonString(row,"tokenid","")))return out;
            BigDecimal confirmed=Util.balanceDecimal(row.get("confirmed"));
            BigDecimal sendable=Util.balanceDecimal(row.get("sendable"));
            BigDecimal unconfirmed=Util.balanceDecimal(row.has("unconfirmed")?row.get("unconfirmed"):"0");
            long coins=MakerConfig.storedBlock(row.has("coins")?row.get("coins"):row.has("coinamount")?row.get("coinamount"):0);
            if(confirmed==null||sendable==null||unconfirmed==null||confirmed.signum()<0||sendable.signum()<0||unconfirmed.signum()<0||coins>Integer.MAX_VALUE)return out;
            out.confirmed=confirmed;out.sendable=sendable;out.unconfirmed=unconfirmed;out.coins=(int)coins;
            out.atMs=System.currentTimeMillis();
        }catch(org.json.JSONException|RuntimeException invalid){ /* Unknown, not a fresh zero. */ }
        return out;
    }

    private void maybeClearMakerSplitPending(String tokenid, BalanceMeta b) {
        if (!makerSplitPending || tokenid == null || !tokenid.equalsIgnoreCase(makerSplitPendingToken)) return;
        if (chainBlock <= makerSplitPendingBlock) return;
        if (b != null && b.sendable.signum() > 0 && b.coins >= SelfSplit.COUNT) {
            makerSplitPending = false;
            makerSplitPendingToken = "";
            makerSplitPendingBlock = 0;
            setStage("Funding split confirmed — maker funds are sendable.");
        }
    }

    // ------------------------------------------------------------------ actions

    public void placeOrder(boolean buy, BigDecimal minima, BigDecimal price, boolean gtc, BigDecimal minRem) {
        if (!ready()) return;
        busy = true;
        hideTradeKeyboard();
        setStage("Order request accepted · selecting wallet coins");
        String orderId = txn.createOrder(buy, minima, price, gtc, minRem, new DexTxn.Result() {
            @Override public void onProgress(String message) { setStage(message); }
            @Override public void onPosted(String txpowid) {
                busy = false;
                setStage("Order posted · checking its creation on-chain");
                repo.refresh();
                repaint();
            }
            @Override public void onFailed(String message) {
                busy = false;
                setStage("Order status: " + message);
                toast(message);
                repaint();
            }
        });
        if (orderId == null) { busy = false; repaint(); return; }
        repaint();
    }

    /**
     * Taker path: show what the sweep will actually execute (best-price rows, VWAP, and the
     * balance that will rest as a limit order), then post ONE sweep txn.
     */
    public void confirmSweep(SweepPlanner.Plan plan, boolean buy, BigDecimal amount,
                             BigDecimal price, boolean gtc, BigDecimal minRem) {
        if (!ready()) return;
        BigDecimal rest = amount.subtract(plan.totalMinima).max(BigDecimal.ZERO);
        StringBuilder sb = new StringBuilder();
        sb.append(buy ? "Buying " : "Selling ").append(PriceMath.fmt(plan.totalMinima))
          .append(" MINIMA now from ").append(plan.takes.size())
          .append(plan.takes.size() == 1 ? " order" : " orders").append("\n");
        sb.append("Avg price ").append(PriceMath.fmt(SweepPlanner.avgPrice(plan)))
          .append("  ·  ").append(PriceMath.fmt(plan.totalUsdt)).append(" mxUSDT\n");
        for (SweepPlanner.Take t : plan.takes) {
            sb.append("  • ").append(PriceMath.fmt(t.minima)).append(" @ ")
              .append(PriceMath.fmtPrice(t.order.price())).append(t.partial ? "  (partial)" : "").append("\n");
        }
        if (rest.signum() > 0) {
            sb.append("\nResting ").append(PriceMath.fmt(rest)).append(" MINIMA @ ")
              .append(PriceMath.fmtPrice(price)).append(" as a limit order");
        }
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle(buy ? "Confirm buy" : "Confirm sell")
                .setMessage(sb.toString())
                .setPositiveButton("Execute", (d, w) -> {
                    if (!ready() || awaitingFill != null) {
                        if (awaitingFill != null) toast("A trade is still being checked. Review ASSETS first.");
                        return;
                    }
                    final String tradePayout = txn.hexAddr();
                    busy = true;
                    hideTradeKeyboard();
                    // mark the rows we're taking so the ladder shows them as in-flight
                    filling.clear();
                    for (SweepPlanner.Take t : plan.takes) filling.add(t.order.coinid);
                    setStage((buy ? "Buy" : "Sell") + " request accepted · selecting wallet coins");
                    txn.fillSweep(plan, new DexTxn.Result() {
                        @Override public void onProgress(String message) { setStage(message); }
                        @Override public boolean onPrepared(String handle) {
                            if (!getSharedPreferences("pandadex_taker", MODE_PRIVATE).getString("pending", "").isEmpty()) return false;
                            awaitingIntent = handle;
                            awaitingFill = new java.util.ArrayList<>(filling);
                            awaitingBuy = buy;
                            awaitingMinima = plan.totalMinima;
                            awaitingPrice = SweepPlanner.avgPrice(plan);
                            awaitingProceeds = buy ? plan.totalMinima : plan.totalUsdt;
                            awaitingProceedsTok = buy ? Util.MINIMA_TOKENID : DexContract.USDT_ID;
                            awaitingFillBlock = chainBlock;
                            awaitingTxpowid = handle;
                            awaitingPayout = tradePayout;
                            awaitingSourceKind = "BOOK";
                            return saveAwaitingFill();
                        }

                        @Override public void onPosted(String txpowid) {
                            busy = false;
                            setStage("Posted — waiting for a block to confirm your "
                                    + (buy ? "buy" : "sell") + " of "
                                    + PriceMath.fmt(plan.totalMinima) + " MINIMA");
                            awaitingFill = new java.util.ArrayList<>(filling);
                            awaitingBuy = buy;
                            awaitingMinima = plan.totalMinima;
                            awaitingPrice = SweepPlanner.avgPrice(plan);
                            awaitingProceeds = buy ? plan.totalMinima : plan.totalUsdt;
                            awaitingProceedsTok = buy ? Util.MINIMA_TOKENID : DexContract.USDT_ID;
                            awaitingFillBlock = chainBlock;
                            awaitingTxpowid = txpowid;
                            awaitingPayout = tradePayout;
                            awaitingSourceKind = "BOOK";
                            if(!saveAwaitingFill()) setStage("Node accepted the trade · updated receipt details could not be saved; recovery record retained");
                            repo.refresh();
                            // The resting balance is queued, NOT placed now: the sweep has
                            // already committed these funds, so an immediate `send` would
                            // fail "insufficient funds". The queue fires only after the exact included transaction
                            // and its expected payout have been matched.
                            if (rest.signum() > 0) {
                                restQueue = new Runnable() {
                                    @Override public void run() { placeOrder(buy, rest, price, gtc, minRem); }
                                };
                                restQueueCoins = new java.util.ArrayList<>();
                                for (SweepPlanner.Take t : plan.takes) restQueueCoins.add(t.order.coinid);
                                restQueueBlock = chainBlock;
                            }
                        }
                        @Override public void onFailed(String message) {
                            busy = false;
                            filling.clear();
                            if (NodeApi.ERR_WRITE_UNCERTAIN.equals(message)) {
                                setStage("Trade outcome unknown — receipt retained. Review ASSETS.");
                            } else {
                                if (clearAwaitingFillState(true)) setStage("Trade failed — " + message);
                                else setStage("Trade failed; pending-state update failed, so trading remains paused");
                            }
                            toast(message);
                            repaint();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void confirmComposite(CompositeRouter.Plan plan, boolean buy, BigDecimal amount,
                                 BigDecimal price, boolean gtc, BigDecimal minRem) {
        if (!ready()) return;
        BigDecimal rest = amount.subtract(plan.totalMinima).max(BigDecimal.ZERO);
        StringBuilder sb = new StringBuilder();
        sb.append(buy ? "Buying " : "Selling ").append(PriceMath.fmt(plan.totalMinima))
          .append(" MINIMA now\n");
        sb.append("Effective ").append(PriceMath.fmtPrice(plan.effectivePrice))
          .append("  ·  ").append(PriceMath.fmt(plan.totalUsdt)).append(" mxUSDT\n");
        if (plan.orderMinima.signum() > 0) {
            sb.append("BOOK ").append(PriceMath.fmt(plan.orderMinima)).append(" MINIMA / ")
              .append(PriceMath.fmt(plan.orderUsdt)).append(" mxUSDT across ")
              .append(plan.orderTakes.size()).append(plan.orderTakes.size() == 1 ? " order\n" : " orders\n");
        }
        if (plan.poolMinima.signum() > 0) {
            sb.append("POOL ").append(PriceMath.fmt(plan.poolMinima)).append(" MINIMA / ")
              .append(PriceMath.fmt(plan.poolUsdt)).append(" mxUSDT across ")
              .append(plan.poolCount()).append(plan.poolCount() == 1 ? " pool\n" : " pools\n");
            String impact = poolImpact(plan);
            if (!impact.isEmpty()) sb.append("Price impact ").append(impact).append("\n");
        }
        if (plan.worstMarginalPrice.signum() > 0)
            sb.append("Worst marginal ").append(PriceMath.fmtPrice(plan.worstMarginalPrice)).append("\n");
        for (SweepPlanner.Take t : plan.orderTakes) {
            sb.append("  • BOOK ").append(PriceMath.fmt(t.minima)).append(" @ ")
              .append(PriceMath.fmtPrice(t.order.price())).append(t.partial ? "  (partial)" : "").append("\n");
        }
        if (plan.poolRoute != null) for (PoolRouter.Alloc a : plan.poolRoute.allocs) {
            BigDecimal m = buy ? a.quote.outAmount : a.quote.inAmount;
            BigDecimal u = buy ? a.quote.inAmount : a.quote.outAmount;
            sb.append("  • POOL ").append(PriceMath.fmt(m)).append(" MINIMA / ")
              .append(PriceMath.fmt(u)).append(" mxUSDT\n");
        }
        if (rest.signum() > 0) {
            sb.append("\nResting ").append(PriceMath.fmt(rest)).append(" MINIMA @ ")
              .append(PriceMath.fmtPrice(price)).append(" as a limit order");
        }
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle(buy ? "Confirm buy" : "Confirm sell")
                .setMessage(sb.toString())
                .setPositiveButton("Execute", (d, w) -> {
                    if (!ready() || awaitingFill != null) {
                        if (awaitingFill != null) toast("A trade is still being checked. Review ASSETS first.");
                        return;
                    }
                    final String tradePayout = txn.hexAddr();
                    busy = true;
                    hideTradeKeyboard();
                    filling.clear();
                    for (SweepPlanner.Take t : plan.orderTakes) filling.add(t.order.coinid);
                    setStage((buy ? "Buy" : "Sell") + " request accepted · selecting wallet coins for " + plan.poolCount() + " pool(s) and " + plan.orderTakes.size() + " limit order(s)");
                    txn.fillComposite(plan, buy, new DexTxn.Result() {
                        @Override public void onProgress(String message) { setStage(message); }
                        @Override public boolean onPrepared(String handle) {
                            if (!getSharedPreferences("pandadex_taker", MODE_PRIVATE).getString("pending", "").isEmpty()) return false;
                            awaitingIntent = handle;
                            awaitingFill = new java.util.ArrayList<>(plan.sourceCoinIds);
                            awaitingBuy = buy;
                            awaitingMinima = plan.totalMinima;
                            awaitingPrice = plan.effectivePrice;
                            awaitingProceeds = buy ? plan.totalMinima : plan.totalUsdt;
                            awaitingProceedsTok = buy ? Util.MINIMA_TOKENID : DexContract.USDT_ID;
                            awaitingFillBlock = chainBlock;
                            awaitingTxpowid = handle;
                            awaitingPayout = tradePayout;
                            awaitingSourceKind = plan.poolCount() > 0 && !plan.orderTakes.isEmpty()
                                    ? "BOOK+POOL" : (plan.poolCount() > 0 ? "POOL" : "BOOK");
                            return saveAwaitingFill();
                        }

                        @Override public void onPosted(String txpowid) {
                            busy = false;
                            setStage("Posted — waiting for a block to confirm your "
                                    + (buy ? "buy" : "sell") + " of "
                                    + PriceMath.fmt(plan.totalMinima) + " MINIMA");
                            awaitingFill = new java.util.ArrayList<>(plan.sourceCoinIds);
                            awaitingBuy = buy;
                            awaitingMinima = plan.totalMinima;
                            awaitingPrice = plan.effectivePrice;
                            awaitingProceeds = buy ? plan.totalMinima : plan.totalUsdt;
                            awaitingProceedsTok = buy ? Util.MINIMA_TOKENID : DexContract.USDT_ID;
                            awaitingFillBlock = chainBlock;
                            awaitingTxpowid = txpowid;
                            awaitingPayout = tradePayout;
                            awaitingSourceKind = plan.poolCount() > 0 && !plan.orderTakes.isEmpty()
                                    ? "BOOK+POOL" : (plan.poolCount() > 0 ? "POOL" : "BOOK");
                            if(!saveAwaitingFill()) setStage("Node accepted the trade · updated receipt details could not be saved; recovery record retained");
                            repo.refresh();
                            if (poolRepo != null) poolRepo.refresh();
                            if (rest.signum() > 0) {
                                restQueue = new Runnable() {
                                    @Override public void run() { placeOrder(buy, rest, price, gtc, minRem); }
                                };
                                restQueueCoins = new java.util.ArrayList<>(plan.sourceCoinIds);
                                restQueueBlock = chainBlock;
                            }
                        }
                        @Override public void onFailed(String message) {
                            busy = false;
                            filling.clear();
                            if (NodeApi.ERR_WRITE_UNCERTAIN.equals(message)) {
                                setStage("Trade outcome unknown — receipt retained. Review ASSETS.");
                            } else {
                                if (clearAwaitingFillState(true)) setStage("Trade failed — " + message);
                                else setStage("Trade failed; pending-state update failed, so trading remains paused");
                            }
                            toast(message);
                            repaint();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Record MY taker fills — but ONLY once the swept coins are actually gone from the book.
     * A consensus-rejected sweep posts without error and simply never mines, so recording on
     * "posted" would write a permanent phantom trade (and, keyed by coinid with
     * CONFLICT_IGNORE, would block the real record if that coin later filled for real).
     */
    /** Our own taker fill has no book-diff signature of its own (the coins we consumed just
     *  vanish), so watch for exactly that and tell the user — otherwise the trade completes in
     *  total silence and they have to go and check their balance to find out. */
    private void reconcileTakerFill(Map<String, Order5> book) {
        if (awaitingFill == null) return;
        for (String coinid : awaitingFill) {
            if (sourceStillLive(book, coinid)) {
                if (awaitingFillBlock > 0 && chainBlock - awaitingFillBlock > SWEEP_DEADLINE_BLOCKS) {
                    failAwaitingFill();
                }
                return;        // at least one leg still live
            }
        }
        if (checkingAwaitingFill) return;
        java.util.List<String> done = new java.util.ArrayList<>(awaitingFill);
        checkingAwaitingFill = true;
        allSourcesSpent(done, spent -> {
            if (awaitingFill == null || !awaitingFill.equals(done)) {
                checkingAwaitingFill = false;
                return;
            }
            if (!spent) {
                checkingAwaitingFill = false;
                repo.refresh();
                if (poolRepo != null) poolRepo.refresh();
                return;
            }
            takerProceedsPresent(paid -> {
                checkingAwaitingFill = false;
                if (awaitingFill == null || !awaitingFill.equals(done)) return;
                if (!paid) {
                    if (awaitingFillBlock > 0 && chainBlock - awaitingFillBlock > SWEEP_DEADLINE_BLOCKS) {
                        failAwaitingFill();
                    } else {
                        repo.refresh();
                        if (poolRepo != null) poolRepo.refresh();
                    }
                    return;
                }
                completeTakerFill(done);
                repaint();
            });
        });
    }

    private void completeTakerFill(java.util.List<String> done) {
        DexHistory.Spend proof=verifiedTakerSpend;
        if(done.isEmpty() || !TakerEvidence.readyToRecord(proof,done.get(0))) {
            setStage("Trade found · verifying its inclusion block time before saving the receipt"); return;
        }
        boolean buy=awaitingBuy; BigDecimal minima=awaitingMinima, price=awaitingPrice;
        final boolean newlyRecorded;
        try {
            JSONObject expected=awaitingFillJson().put("transactionid",proof.transactionId);
            newlyRecorded=db.recordTakerFill(TakerReceipt.capture(expected,verifiedTakerSpends));
            stats.invalidate();
            if(!clearAwaitingFillState(false)) {
                setStage("Trade receipt saved · pending-state update failed; trading remains paused"); return;
            }
        } catch(Exception failure) {
            setStage("Trade receipt could not be saved safely · pending evidence retained; trading remains paused"); return;
        }
        filling.removeAll(done);
        String msg=(buy ? "Bought " : "Sold ")+PriceMath.fmt(minima)+" MINIMA @ "+PriceMath.fmtPrice(price);
        setStage("✓ "+msg+" — proceeds are confirming, see ASSETS");
        if(newlyRecorded) Notifier.alert(this,"Trade complete",msg+". Funds are confirming and will show as available shortly.");
        stats.invalidate();
        // All dependent actions happen only after the row and receipt cleanup are acknowledged.
        if(restQueue!=null) placeRestQueue();
    }

    private JSONObject awaitingFillJson() throws org.json.JSONException {
            JSONObject row = new JSONObject();
            JSONArray ids = new JSONArray();
            if (awaitingFill != null) for (String id : awaitingFill) ids.put(id);
            row.put("sources", ids).put("buy", awaitingBuy).put("minima", awaitingMinima.toPlainString())
                    .put("price", awaitingPrice.toPlainString()).put("proceeds", awaitingProceeds.toPlainString())
                    .put("token", awaitingProceedsTok).put("block", awaitingFillBlock)
                    .put("txpowid", awaitingTxpowid).put("payout", awaitingPayout).put("source", awaitingSourceKind)
                    .put("intent", awaitingIntent);
            return row;
    }

    private boolean saveAwaitingFill() {
        try { return SubmissionIds.savePending(this,awaitingFillJson()); }
        catch(Exception invalid) {return false;}
    }

    private void restoreAwaitingFill() {
        try {
            String saved = getSharedPreferences("pandadex_taker", MODE_PRIVATE).getString("pending", "");
            if (saved.isEmpty()) return;
            JSONObject row = new JSONObject(saved);
            JSONArray ids = row.getJSONArray("sources");
            java.util.List<String> sources = new java.util.ArrayList<>();
            if (ids.length() == 0 || ids.length() > 20) return;
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i); if (!FundingCoins.hex(id)) return; sources.add(id);
            }
            awaitingFill = sources;
            awaitingBuy = row.optBoolean("buy");
            awaitingMinima = Util.dec(row.optString("minima"));
            awaitingPrice = Util.dec(row.optString("price"));
            awaitingProceeds = Util.dec(row.optString("proceeds"));
            awaitingProceedsTok = row.optString("token");
            awaitingFillBlock = row.optLong("block");
            awaitingTxpowid = row.optString("txpowid");
            awaitingIntent = row.optString("intent", "");
            awaitingPayout = row.optString("payout");
            awaitingSourceKind = row.optString("source");
            filling.addAll(sources);
        } catch (Exception invalid) { android.util.Log.w("PandaDexChain", "Stored trade receipt could not be loaded"); }
    }

    public boolean hasUnresolvedTrade() { return awaitingFill != null; }
    public void reviewUnresolvedTrade() {
        if (awaitingFill == null) return;
        new AlertDialog.Builder(this, Design.dialogTheme()).setTitle("Trade still being checked")
                .setMessage("Transaction reference: " + awaitingTxpowid
                        + "\n\nThe app has not yet matched this trade to a confirmed transaction and its payout. "
                        + "It will keep checking; a saved intent does not by itself prove submission. Any remaining limit order is not restored after an app restart.")
                .setPositiveButton("Check now", (d, w) -> { if (repo != null) repo.refresh(); })
                .setNegativeButton("Close", null).show();
    }

    private void failAwaitingFill() {
        // A deadline is not rejection evidence. Keep the receipt and keep checking it.
        setStage("Trade outcome unresolved — checking the chain. Review ASSETS before retrying.");
        if (restQueue != null) failRestQueue();
    }

    private boolean clearAwaitingFillState(boolean clearFilling) {
        try {
            String saved = getSharedPreferences("pandadex_taker", MODE_PRIVATE).getString("pending", "");
            if (!saved.isEmpty()) {
                if (awaitingFill == null || !awaitingIntent.equals(new JSONObject(saved).optString("intent"))) return false;
            }
            // A failed commit may already have changed SharedPreferences' in-memory map.
            // Retry the disk acknowledgement even when that map now appears empty.
            if (!getSharedPreferences("pandadex_taker", MODE_PRIVATE).edit().remove("pending").commit()) return false;
        } catch (Exception invalid) { return false; }
        awaitingIntent = "";
        awaitingFill = null;
        awaitingFillBlock = 0;
        awaitingProceeds = BigDecimal.ZERO;
        awaitingProceedsTok = "";
        awaitingTxpowid = "";
        awaitingPayout = "";
        awaitingSourceKind = "";
        verifiedTakerSpend = null;
        verifiedTakerSpends = java.util.Collections.emptyMap();
        if (clearFilling) filling.clear();
        return true;
    }

    private void reconcileSweep(Map<String, Order5> book) {
        if (restQueue != null && restQueueBlock > 0 && chainBlock - restQueueBlock > SWEEP_DEADLINE_BLOCKS)
            failRestQueue();
    }

    private void placeRestQueue() {
        Runnable q = restQueue;
        restQueue = null;
        restQueueCoins = null;
        restQueueBlock = 0;
        if (q != null) q.run();
    }

    private void failRestQueue() {
        restQueue = null;
        restQueueCoins = null;
        restQueueBlock = 0;
        setStage("Trade outcome unresolved. The remaining limit order was not placed; the trade is still being checked.");
        toast("Remaining limit order not placed — review the trade first");
        repaint();
    }

    private boolean sourceStillLive(Map<String, Order5> book, String coinid) {
        if (book.containsKey(coinid)) return true;
        if (poolRepo != null) for (Pool p : poolRepo.pools()) {
            if (coinid.equals(p.coinidM) || coinid.equals(p.coinidT)) return true;
        }
        return false;
    }

    private interface SourcesCb { void done(boolean allSpent); }

    private void allSourcesSpent(java.util.List<String> coinids, SourcesCb cb) {
        allSourcesSpent(coinids, 0, cb);
    }

    private void allSourcesSpent(java.util.List<String> coinids, int idx, SourcesCb cb) {
        if (node == null) { cb.done(false); return; }
        if (coinids == null || idx >= coinids.size()) { cb.done(true); return; }
        String coinid = coinids.get(idx);
        if (coinid == null || coinid.isEmpty()) { cb.done(false); return; }
        node.cmd("coins simplestate:true coinid:" + coinid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Boolean present = coinPresent(json);
                if (present == null) { cb.done(false); return; }
                if (present) { cb.done(false); return; }
                if (idx == coinids.size() - 1) cb.done(true);
                else allSourcesSpent(coinids, idx + 1, cb);
            }
            @Override public void onError(String message) { cb.done(false); }
        });
    }

    static Boolean coinPresent(JSONObject json) {
        if (json == null || !json.optBoolean("status", false)) return null;
        Object resp = json == null ? null : json.opt("response");
        if (resp instanceof JSONArray) return ((JSONArray) resp).length() > 0;
        if (resp instanceof JSONObject) return !((JSONObject) resp).optString("coinid", "").isEmpty();
        return null;
    }

    private interface PaidCb { void done(boolean paid); }

    private void takerProceedsPresent(PaidCb cb) {
        String payAddr = awaitingPayout;
        if (node == null || payAddr == null || payAddr.isEmpty()
                || awaitingProceeds == null || awaitingProceeds.signum() <= 0
                || awaitingProceedsTok == null || awaitingProceedsTok.isEmpty()) {
            cb.done(false);
            return;
        }
        final java.util.List<String> sources = awaitingFill == null ? null : new java.util.ArrayList<>(awaitingFill);
        final String submitted = awaitingTxpowid;
        final String immutable = SubmissionIds.transactionFor(this, submitted);
        final String token = awaitingProceedsTok;
        final BigDecimal amount = awaitingProceeds;
        if (sources == null || immutable.isEmpty()) { cb.done(false); return; }
        history.findSpends(sources, found -> {
            if (awaitingFill == null || !awaitingFill.equals(sources) || !submitted.equals(awaitingTxpowid)) {
                cb.done(false); return;
            }
            DexHistory.Spend match = TakerEvidence.match(found, sources, immutable, payAddr, token, amount);
            verifiedTakerSpend = match;
            verifiedTakerSpends = match==null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(found);
            cb.done(match != null);
        });
    }

    static boolean proceedsPresent(JSONObject json, String tokenid, BigDecimal amount, long minBlock) {
        if (json == null || !json.optBoolean("status", false)
                || tokenid == null || tokenid.isEmpty() || amount == null || amount.signum() <= 0) {
            return false;
        }
        Object resp = json.opt("response");
        if (!(resp instanceof JSONArray)) return false;
        JSONArray arr = (JSONArray) resp;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            if (minBlock > 0 && c.optLong("created", 0) < minBlock) continue;
            String tok = c.optString("tokenid", Util.MINIMA_TOKENID);
            if (!tok.equalsIgnoreCase(tokenid)) continue;
            String raw = Util.MINIMA_TOKENID.equalsIgnoreCase(tokenid)
                    ? c.optString("amount", "0")
                    : c.optString("tokenamount", c.optString("amount", "0"));
            if (Util.dec(raw).compareTo(amount) == 0) return true;
        }
        return false;
    }

    private static String poolImpact(CompositeRouter.Plan plan) {
        if (plan == null || plan.poolRoute == null || !plan.poolRoute.ok
                || plan.poolRoute.spotBefore.signum() <= 0 || plan.effectivePrice.signum() <= 0) {
            return "";
        }
        BigDecimal pct = plan.effectivePrice.subtract(plan.poolRoute.spotBefore)
                .abs()
                .multiply(new BigDecimal("100"), PriceMath.MC)
                .divide(plan.poolRoute.spotBefore, 2, java.math.RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }

    /**
     * Cancel every open order. Also the market maker's withdraw path.
     *
     * Issued STRICTLY SEQUENTIALLY: the node executes one command at a time and each cancel
     * grinds proof-of-work, so firing a dozen at once would swamp its single command thread
     * and time everything out. Progress is reported as it goes, and a partial result is
     * reported honestly — the chain decides what actually happened, not this loop.
     */
    public void cancelAll(Runnable onDone) {
        if (!ready()) return;
        java.util.List<Order5> mine = new java.util.ArrayList<>();
        for (Order5 o : book().values()) if (o.isMine(keySet.keys(), keySet.addrs())) mine.add(o);
        makerCfg.reload();
        boolean makerWork=makerCfg.armed||makerCfg.hasRecordedOrders()||(maker!=null&&maker.isWorking());
        if (mine.isEmpty()&&!makerWork) { toast("No open orders in this snapshot"); if (onDone != null) onDone.run(); return; }

        BigDecimal totalMinima = BigDecimal.ZERO, totalUsdt = BigDecimal.ZERO;
        for (Order5 o : mine) {
            if (o.sell) totalMinima = totalMinima.add(o.locked);
            else totalUsdt = totalUsdt.add(o.locked);
        }
        // How many of these belong to the published ladder? Cancelling those without stopping
        // the maker is futile: it would read each rung as missing and rebuild the whole ladder
        // within minutes, spending proof-of-work to re-commit the funds just freed.
        int rungs = 0;
        if (makerCfg.armed && maker != null) {
            java.util.Set<String> owned = maker.ownedOrderIds();
            for (Order5 o : mine) if (owned.contains(o.orderId)) rungs++;
        }
        final boolean stopMaker = makerWork;
        final Object connection = pairingAttempt;

        String msg = mine.isEmpty()
                ? "Stop the maker and request withdrawal of its recorded orders? No open orders are visible in this snapshot, but a submission may still be in progress."
                : "Request cancellation of all " + mine.size() + " visible order" + (mine.size()==1?"":"s")
                    + "?\n\nThese orders lock " + PriceMath.fmt(totalMinima) + " MINIMA and "
                    + PriceMath.fmt(totalUsdt) + " mxUSDT. Funds return after successful cancellation. "
                    + "Cancellations are submitted in batches; orders can still fill until their cancellation confirms.";
        if(stopMaker)msg+="\n\nThe maker will be paused too"
                +(rungs>0?" ("+rungs+" visible maker orders)":"")
                +". Orders still being submitted will remain tracked for cancellation when they appear. Check Orders for confirmed outcomes.";
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Cancel all orders")
                .setMessage(msg)
                .setPositiveButton(stopMaker ? "Cancel all & stop" : "Cancel them", (d, w) -> {
                    if (!currentPairing(connection) || !ready()) { toast("Connection changed or is not ready. Review the order list again."); return; }
                    Runnable start=() -> {
                        if (!currentPairing(connection)) { toast("Connection changed. Review the order list again."); if(onDone!=null)onDone.run(); return; }
                        repaint();
                        if(mine.isEmpty()) {
                            String status="Maker withdrawal instructions retained. No orders were visible in this snapshot; check Orders for on-chain outcomes.";
                            setStage(status);toast(status);repo.refresh();
                            if(onDone!=null)onDone.run();return;
                        }
                        cancelSequentially(connection,mine,0,0,0,onDone);
                    };
                    // Re-check on confirmation; a maker may have started since the dialog opened.
                    if(maker!=null)maker.stopForCancelAll(chainBlock,makerListener,start);
                    else start.run();
                })
                .setNegativeButton("Keep them", null)
                .show();
    }

    private void cancelSequentially(Object connection, java.util.List<Order5> list, int idx, int ok, int failed,
                                    Runnable onDone) {
        if (!currentPairing(connection)) {
            busy = false;
            setStage("Connection changed. Remaining cancellation batches stopped; check retained receipts before retrying.");
            repaint();
            if (onDone != null) onDone.run();
            return;
        }
        if (idx >= list.size()) {
            busy = false;
            // SENT, not done. Every cancel is a transaction: until it mines the order is still
            // resting and can still be filled, so announcing "cancelled" here told the user the
            // funds were back while they were demonstrably still at risk — and contradicted the
            // Orders tab, which shows those same rows as CANCELLING at that moment. The honest
            // completion is reported from Pending.onSettled, when the coins actually go.
            awaitingCancels = ok;
            String summary = failed == 0
                    ? "Cancel sent for " + ok + " order" + (ok == 1 ? "" : "s")
                            + " — each leaves the book when a block confirms it, and can still "
                            + "be filled until then"
                    : "Cancel requests accepted for " + ok + "; " + failed + " requests failed or have unknown outcomes. Check Orders and retained receipts.";
            setStage(summary);
            toast(summary);
            repo.refresh();
            repaint();
            if (onDone != null) onDone.run();
            return;
        }
        busy = true;
        // BATCH: several cancels ride in ONE transaction (the covenant's cancel branch is
        // index-matched), so 12 orders cost 3 rounds of proof-of-work instead of 12.
        final java.util.List<Order5> chunk = new java.util.ArrayList<>(
                list.subList(idx, Math.min(idx + SweepPlanner.MAX_ORDERS, list.size())));
        final int next = idx + chunk.size();
        setStage("Cancelling " + next + " of " + list.size() + "…");
        txn.cancelBatch(chunk, new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                // Pending already retained the submission. No legacy-table write may
                // interrupt this batch continuation after the node accepted it.
                cancelSequentially(connection, list, next, ok + chunk.size(), failed, onDone);
            }
            @Override public void onFailed(String message) {
                // atomic — the whole chunk failed together
                cancelSequentially(connection, list, next, ok, failed + chunk.size(), onDone);
            }
        });
    }

    /** Maker events refresh the visible status; DexTxn owns the durable pre-submission
     * receipts shared by foreground and background hosts. */
    private final MakerEngine.Listener makerListener = new MakerEngine.Listener() {
        @Override public void onMakerState(String message) { setStage(message); }

        @Override public void onCreateSent(MakerLadder.Slot slot, String orderId) {
            // DexTxn saved the exact creation receipt before signing, including in the service.
            repaint();
        }

        @Override public void onCancelSent(Order5 o) {
            // DexTxn journalled all cancellation sources before signing, including service calls.
            repaint();
        }

        @Override public void onRelockSent(Order5 o, BigDecimal newPrice) {
            // DexTxn retained the exact replacement amount before signing.
            repaint();
        }
    };

    /** Publish the ladder — after an affordability check and showing what will be committed. */
    public void publishMaker(java.util.List<MakerLadder.Slot> desired, boolean pegged) {
        if (!ready()) return;
        if (makerSplitPending) {
            new AlertDialog.Builder(this, Design.dialogTheme())
                    .setTitle("Funding split is confirming")
                    .setMessage("Wait for the next block and balance refresh before publishing. "
                            + "The split outputs are not spendable yet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (desired == null || desired.isEmpty()) {
            toast("No rungs to publish — set a price and size first");
            return;
        }
        int nAsks = 0, nBids = 0;
        for (MakerLadder.Slot s : desired) { if (s.sell) nAsks++; else nBids++; }
        MakerLadder.Commitments c = MakerLadder.commitments(desired);

        // ---- affordability FIRST: an unfunded rung fails as an invisible async reject
        // (observed live — 4 bids sent, 1 funded), so refuse to publish what can't be paid for
        StringBuilder lack = new StringBuilder();
        if (c.askMinima.compareTo(minimaSendable) > 0) {
            lack.append("Asks need ").append(PriceMath.fmt(c.askMinima))
                    .append(" MINIMA — you have ").append(PriceMath.fmt(minimaSendable))
                    .append(" sendable");
            appendUnavailable(lack, minimaLockedNode(), minimaUnconfirmed);
            lack.append(".\n");
        }
        if (c.bidUsdt.compareTo(usdtSendable) > 0) {
            lack.append("Bids need ").append(PriceMath.fmt(c.bidUsdt))
                    .append(" mxUSDT — you have ").append(PriceMath.fmt(usdtSendable))
                    .append(" sendable");
            appendUnavailable(lack, usdtLockedNode(), usdtUnconfirmed);
            lack.append(".\n");
        }
        if (lack.length() > 0) {
            new AlertDialog.Builder(this, Design.dialogTheme())
                    .setTitle("Not enough available funds")
                    .setMessage(lack + "\nShrink the rung sizes or free up funds, then publish.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String sides = (nAsks > 0 ? nAsks + " ask" + (nAsks == 1 ? "" : "s") + " locking "
                        + PriceMath.fmt(c.askMinima) + " MINIMA" : "no asks")
                + " and "
                + (nBids > 0 ? nBids + " bid" + (nBids == 1 ? "" : "s") + " locking "
                        + PriceMath.fmt(c.bidUsdt) + " mxUSDT" : "no bids");
        String fundingHint = makerFundingHint(nAsks, nBids);
        AlertDialog.Builder b = new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Publish the ladder")
                .setMessage("This puts " + (nBids + nAsks) + " orders on the book — " + sides + ".\n\n"
                        + (pegged
                        ? "PEGGED: prices track the MEXC mid and reprice when it moves past your "
                        + "threshold; your per-rung sizes are kept. If the feed goes stale the "
                        + "app widens its quotes, then requests withdrawal. Existing orders remain tradeable until cancellation confirms."
                        : "NOT pegged: the book gets YOUR exact prices — never repriced, never "
                        + "withdrawn, even if the price feed dies.")
                        + "\n\nRungs post ONE PER SIDE per cycle — each is an on-chain "
                        + "transaction your phone does proof-of-work for, and each one's change "
                        + "has to confirm before the next on that side can be funded — so a full "
                        + "ladder takes a few minutes to build and a rung may retry before it "
                        + "sticks. The Maker tab shows each rung's progress.\n\n"
                        + "Background checks are normally requested every 15 minutes. Android may delay or stop them, "
                        + "so automatic withdrawal is not a guaranteed price limit. Keep PandaDEX and MinimaCore "
                        + "running while monitoring the ladder; check Orders for confirmed cancellations."
                        + (fundingHint.isEmpty() ? "" : "\n\n" + fundingHint))
                .setPositiveButton("Publish", (d, w) -> {
                    makerCfg.armed = true;
                    makerCfg.lastActedMid = null;      // act on the next cycle
                    if(!makerCfg.saveUserAction()) {
                        makerCfg.armed=false;
                        toast(MakerConfig.storageFailureMessage()+" Publishing was not started.");repaint();return;
                    }
                    setStage("Ladder publishing — posting the first rungs");
                    repo.refresh();
                    repaint();
                })
                .setNegativeButton("Not yet", null);
        if (!fundingHint.isEmpty()) b.setNeutralButton("Split funds first", (d, w) -> prepareMakerFundingUtxos());
        b.show();
    }

    private String makerFundingHint(int nAsks, int nBids) {
        StringBuilder sb = new StringBuilder();
        if (nAsks > 1 && minimaCoins > 0 && minimaCoins < nAsks) {
            sb.append("MINIMA funding looks thin: ").append(minimaCoins)
                    .append(" coin").append(minimaCoins == 1 ? "" : "s")
                    .append(" for ").append(nAsks).append(" ask rungs.");
        }
        if (nBids > 1 && usdtCoins > 0 && usdtCoins < nBids) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("mxUSDT funding looks thin: ").append(usdtCoins)
                    .append(" coin").append(usdtCoins == 1 ? "" : "s")
                    .append(" for ").append(nBids).append(" bid rungs.");
        }
        if (sb.length() == 0) return "";
        return sb.append("\nUse split:").append(SelfSplit.COUNT)
                .append(" first to prepare funding coins, then wait for them to confirm before publishing.").toString();
    }

    /** Split either side's sendable wallet balance into ten self-pay coins before publishing. */
    public void prepareMakerFundingUtxos() {
        if (!ready()) return;
        if (busy) { toast("A transaction is already in flight"); return; }
        if (makerCfg != null && makerCfg.armed) {
            toast("Withdraw the live ladder before splitting maker funding coins");
            return;
        }
        String[] items = {
                "MINIMA - " + PriceMath.fmt(minimaSendable) + " sendable, "
                        + minimaCoins + " coin" + (minimaCoins == 1 ? "" : "s"),
                "mxUSDT - " + PriceMath.fmt(usdtSendable) + " sendable, "
                        + usdtCoins + " coin" + (usdtCoins == 1 ? "" : "s")
        };
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Split maker funding")
                .setMessage("This sends the selected sendable balance back to your own wallet "
                        + "with split:" + SelfSplit.COUNT + ". The new coins are unconfirmed "
                        + "until a block mines; publish the ladder after ASSETS shows them "
                        + "sendable.")
                .setItems(items, (d, which) -> {
                    if (which == 0) splitMakerFunding(Util.MINIMA_TOKENID, "MINIMA", minimaSendable);
                    else splitMakerFunding(DexContract.USDT_ID, "mxUSDT", usdtSendable);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void splitMakerFunding(String tokenid, String label, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            toast("No sendable " + label + " to split");
            return;
        }
        if (busy) { toast("A transaction is already in flight"); return; }
        if (receiveAddr != null && !receiveAddr.isEmpty()) {
            postMakerSelfSplit(tokenid, label, amount, receiveAddr);
            return;
        }
        busy = true;
        setStage("Fetching your wallet address...");
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (addr.isEmpty()) {
                    busy = false;
                    setStage("Split failed - could not get your wallet address");
                    toast("Could not get your wallet address");
                    return;
                }
                receiveAddr = addr;
                postMakerSelfSplit(tokenid, label, amount, addr);
            }
            @Override public void onError(String message) {
                busy = false;
                setStage("Split failed - " + message);
                toast("Split failed: " + message);
            }
        });
    }

    private void postMakerSelfSplit(String tokenid, String label, BigDecimal amount, String address) {
        busy = true;
        setStage("Splitting " + PriceMath.fmt(amount) + " " + label + " into " + SelfSplit.COUNT + " wallet coins…");
        txn.splitFunding(tokenid, amount, new DexTxn.Result() {
            public void onPosted(String txpowid) {
                makerSplitPending = true;
                makerSplitPendingToken = tokenid;
                makerSplitPendingBlock = chainBlock;
                busy = false;
                setStage("Split submitted — waiting for spendable wallet coins");
                poll();
            }
            public void onFailed(String message) {
                busy = false;
                setStage(NodeApi.ERR_WRITE_UNCERTAIN.equals(message) ? "Split outcome unknown — review Assets" : "Split failed — " + message);
                toast(message); repaint();
            }
        });
        repaint();
    }

    /**
     * Apply ladder edits to the LIVE book without tearing it down.
     *
     * A price change is one re-lock — the covenant's owner branch changes the price in a single
     * transaction and the funds never leave the book, so the rung is never missing. An AMOUNT
     * change cannot work that way (the locked value is fixed), so that rung has to be cancelled
     * and reposted, and it does leave the book for a block or two. The dialog says which is
     * which, because those are very different promises.
     */
    public void applyMakerEdits(BigDecimal mid) {
        if (!ready() || maker == null) return;
        if (!makerCfg.armed) { toast("Publish the ladder first"); return; }
        if (maker.isWorking()) { toast("Mid-adjustment — try again in a moment"); return; }

        int[] p = maker.previewEdits(book(), keys(), chainBlock, mid);
        int relocks = p[0], reposts = p[1], creates = p[2], cancels = p[3];
        if (relocks + reposts + creates + cancels == 0) {
            toast("No automatic edits available — review the rung statuses for positions that need attention");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (relocks > 0) sb.append("• ").append(relocks).append(relocks == 1 ? " rung re-prices" : " rungs re-price")
                .append(" in place — one transaction each, funds never leave the book.\n");
        if (reposts > 0) sb.append("• ").append(reposts).append(reposts == 1 ? " rung changes" : " rungs change")
                .append(" AMOUNT — locked funds can't be resized, so each is cancelled and "
                        + "reposted and leaves the book for a block or two.\n");
        if (creates > 0) sb.append("• ").append(creates).append(creates == 1 ? " new rung" : " new rungs")
                .append(" posted.\n");
        if (cancels > 0) sb.append("• ").append(cancels).append(cancels == 1 ? " rung removed" : " rungs removed")
                .append(".\n");
        sb.append("\nRungs post one per side per cycle, so a large batch lands over a few "
                + "minutes. The status panel shows each one.");

        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Apply edits to the live ladder")
                .setMessage(sb.toString())
                .setPositiveButton("Apply", (d, w) -> {
                    maker.nudge();          // don't wait out the cycle gate for a deliberate edit
                    setStage("Applying ladder edits…");
                    repo.refresh();
                    repaint();
                })
                .setNegativeButton("Not yet", null)
                .show();
    }

    /** Withdraw the ladder: disarm, cancel everything it owns, chase what hasn't confirmed. */
    public void withdrawLadder() {
        if (maker == null || repo == null) return;
        // Disarm FIRST so no new cycle starts behind us, then refuse to overlap an in-flight
        // one. Two chains issuing transactions at once breaks the sequential guarantee the
        // engine is built on, and a reprice completing after we cleared the slot map would
        // leave a live order the ladder no longer knows about.
        makerCfg.armed = false;
        if(!makerCfg.saveUserAction()) {
            toast(MakerConfig.storageFailureMessage()+" No cancellation started; saved settings may still be enabled.");
            repaint();return;
        }
        repaint();
        if (maker.isWorking()) {
            // queue it: once disarmed, onBook stops running, so nothing else would ever come
            // back to finish this and the ladder would sit on the book despite the request
            toast("Finishing the current adjustment; withdrawal will then be attempted. Check Orders for confirmation.");
            maker.runWhenIdle(this::withdrawLadder);
            return;
        }
        if (!makerCfg.hasRecordedOrders()) {
            toast("No ladder orders to withdraw");
            return;
        }
        setStage("Withdrawing the ladder — cancels leave the book as blocks confirm them");
        maker.withdrawAll(book(), keys(), chainBlock, makerListener);
    }

    public void cancelOrder(Order5 o) {
        if (!ready() || !keySet.owns(o)) return;
        txn.cancel(o, new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                toast("Cancel sent — the order leaves the book when a block confirms it");
                repo.refresh();
                repaint();
            }
            @Override public void onFailed(String message) { toast("Cancel failed: " + message); }
        });
    }

    /** Atomic in-place reprice — ONE transaction, the order never leaves the book. */
    public void editOrder(Order5 o) {
        if (!ready() || !keySet.owns(o)) return;
        final Object connection = pairingAttempt;
        EditText in = new EditText(this);
        in.setHint("New price (mxUSDT per MINIMA)");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        in.setTransformationMethod(null);
        in.setFilters(new android.text.InputFilter[]{(src, start, end, dest, dstart, dend) -> {
            String value = dest.toString().substring(0, dstart) + src.subSequence(start, end) + dest.toString().substring(dend);
            return value.length() <= 44 && (value.isEmpty() || value.matches("[0-9]*\\.?[0-9]*")) ? null : "";
        }});
        in.setText(o.price().stripTrailingZeros().toPlainString());
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Edit order price")
                .setView(in)
                .setPositiveButton("Update", (d, w) -> {
                    if (!currentPairing(connection) || !ready() || !keySet.owns(o)) { toast("Connection or ownership changed. Reopen this order before editing."); return; }
                    BigDecimal np = Util.dec(in.getText().toString());
                    if (np.signum() <= 0) { toast("Bad price"); return; }
                    BigDecimal newWant = o.sell
                            ? PriceMath.up(o.locked.multiply(np, PriceMath.MC), PriceMath.USDT_DP)
                            : PriceMath.down(o.locked.divide(np, PriceMath.MINIMA_DP, java.math.RoundingMode.DOWN),
                                    PriceMath.MINIMA_DP);
                    txn.relock(o, newWant, new DexTxn.Result() {
                        @Override public void onPosted(String txpowid) {
                            toast("Reprice posted");
                            repo.refresh();
                            repaint();
                        }
                        @Override public void onFailed(String message) { toast("Reprice failed: " + message); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        boolean mine = order.isMine(keys(), addrs());
        boolean isNew = entry == null
                ? db.recordVerifiedFill(spentCoin, timeMs, block, price, size, takerBuy, partial, mine, order, txpowid, evidence, note)
                : db.completeFill(entry, spend, order, timeMs, block, price, size, takerBuy, partial, mine, evidence, note);
        if (isNew && mine && FillSettler.recentForNotification(timeMs, System.currentTimeMillis())) {
            toast((partial ? "Partial fill: " : "Filled: ") + PriceMath.fmt(size) + " MINIMA @ "
                    + PriceMath.fmtPrice(price));
            Notifier.fill(this, order.sell, size, price, partial);
        }
        stats.invalidate();
    }

    private static String joinIds(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(id);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ accessors for views

    public DexStats db() { return stats; }
    public Set<String> keys() { return keySet.keys(); }
    /** My wallet addresses — the second ownership factor; see {@link KeySet#owns}. */
    public Set<String> addrs() { return keySet.addrs(); }
    public Map<String, Order5> book() { return repo == null ? new java.util.LinkedHashMap<>() : repo.book(); }
    public java.util.List<Pool> pools() { return poolRepo == null || !poolRepo.current() ? java.util.Collections.emptyList() : poolRepo.pools(); }
    /** Observation status only; a book snapshot is not transaction-inclusion proof. */
    public boolean makerBookReady() {
        return paired && keySet != null && keySet.ready() && repo != null && repo.current() && chainBlock > 0;
    }
    public long chainBlock() { return chainBlock; }
    public java.util.List<Pending.Row> pendingRows() { return pending.rows(); }

    /** Order ids belonging to the published ladder, so the Orders tab can say which rows the
     *  maker owns — cancelling one of those by hand is a different act from cancelling a
     *  hand-placed order. Empty when nothing is published. */
    public java.util.Set<String> makerOrderIds() {
        return (maker == null || !makerCfg.armed) ? java.util.Collections.emptySet()
                                                  : maker.ownedOrderIds();
    }
    public BigDecimal minimaSendable() { return minimaSendable; }
    public BigDecimal usdtSendable() { return usdtSendable; }
    public BigDecimal minimaConfirmed() { return minimaConfirmed; }
    public BigDecimal usdtConfirmed() { return usdtConfirmed; }
    public BigDecimal minimaUnconfirmed() { return minimaUnconfirmed; }
    public BigDecimal usdtUnconfirmed() { return usdtUnconfirmed; }
    public BigDecimal minimaLockedNode() { return minimaConfirmed.subtract(minimaSendable).max(BigDecimal.ZERO); }
    public BigDecimal usdtLockedNode() { return usdtConfirmed.subtract(usdtSendable).max(BigDecimal.ZERO); }
    public int minimaCoins() { return minimaCoins; }
    public int usdtCoins() { return usdtCoins; }
    public long minimaBalanceAtMs() { return minimaBalanceAtMs; }
    public long usdtBalanceAtMs() { return usdtBalanceAtMs; }
    public BigDecimal minimaPending() { return minimaLockedNode().add(minimaUnconfirmed); }
    public BigDecimal usdtPending() { return usdtLockedNode().add(usdtUnconfirmed); }
    public void setInputFocused(boolean f) { inputFocused = f; }
    public boolean isBusy() { return busy; }
    public java.util.Set<String> filling() { return filling; }

    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public void exportTradeReconciliation() {
        if(tradeExportSession==null||saveTradeExportLauncher==null){toast("Export is not ready yet");return;}
        try {
            if(!tradeExportSession.start(TradeExportWriter.snapshotFromUi(this))) {
                if(tradeExportSession.phase()==TradeExportSession.Phase.NOTICE)renderExportSession();
                else toast("An export is already being prepared or saved");
            }
        }catch(RuntimeException failure){toast("Could not read the export snapshot; please try again");}
    }

    private void renderExportSession() {
        TradeExportSession.Phase phase=tradeExportSession.phase();
        if(phase==TradeExportSession.Phase.BUILDING)setStage("Building export · optional explorer checks take up to 30 seconds…");
        else if(phase==TradeExportSession.Phase.SAVING)setStage("Saving trade export…");
        else setStage("");
        if(phase==TradeExportSession.Phase.READY) {
            String filename=tradeExportSession.claimPicker();
            if(filename!=null)try{saveTradeExportLauncher.launch(filename);}
            catch(RuntimeException failure){tradeExportSession.pickerFailed();}
        }else if(phase==TradeExportSession.Phase.NOTICE&&!exportNoticeShowing) {
            exportNoticeShowing=true;
            new AlertDialog.Builder(this,Design.dialogTheme()).setTitle("Trade export")
                    .setMessage(tradeExportSession.notice()).setCancelable(false)
                    .setPositiveButton("OK",(dialog,which)->{
                        exportNoticeShowing=false;
                        if(!tradeExportSession.acknowledge()) {
                            toast(tradeExportSession.notice());
                        }
                    }).show();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        inputFocused = false;
        // The service may have posted or cancelled rungs while we were away — take over from
        // what is on disk, never from our stale in-memory copy (saving that would orphan them).
        if (makerCfg != null) makerCfg.reload();
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
        ui.removeCallbacks(uiTick);
        ui.postDelayed(uiTick, 1000);
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        ui.removeCallbacks(pollTask);
        ui.removeCallbacks(uiTick);
    }

    @Override protected void onDestroy() {
        if (activityLogDialog != null) activityLogDialog.dismiss();
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (repo != null) repo.close();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (keySet != null) keySet.close();
        if (node != null) node.onDestroy();
    }

    public boolean interruptedWrite() { return node != null && node.hasInterruptedWrite(); }

    /** Same explicit restart-and-reconcile recovery as PandaPools WalletTools. */
    public void resolveInterruptedWrite() {
        if (!interruptedWrite()) { toast("No interrupted write to resolve"); return; }
        new AlertDialog.Builder(this, Design.dialogTheme()).setTitle("Resolve interrupted write")
                .setMessage("A node write lost its reply and may have completed. First restart MinimaCore to stop old commands, "
                        + "then check Orders, Tape and your balances. Clearing this pause neither undoes nor retries a transaction. "
                        + "The maker will stay disarmed until you review and publish it again."
                        + "\n\nInterrupted command:\n" + node.interruptedWriteDetails())
                .setNegativeButton("Keep paused", null)
                .setNeutralButton("Copy details", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Interrupted PandaDEX write", node.interruptedWriteDetails()));
                })
                .setPositiveButton("I restarted and checked", (dialog, which) -> {
                    makerCfg.reload(); makerCfg.armed = false; makerCfg.save();
                    toast(node.acknowledgeInterruptedWrite() ? "Safety pause cleared. Review your orders and balances." : "A write is still active. Keep paused.");
                    repaint();
                }).show();
    }

    private static void appendUnavailable(StringBuilder sb, BigDecimal locked, BigDecimal unconfirmed) {
        if ((locked == null || locked.signum() <= 0) && (unconfirmed == null || unconfirmed.signum() <= 0)) return;
        sb.append(" (");
        boolean any = false;
        if (locked != null && locked.signum() > 0) {
            sb.append(PriceMath.fmt(locked)).append(" confirmed locked");
            any = true;
        }
        if (unconfirmed != null && unconfirmed.signum() > 0) {
            if (any) sb.append(", ");
            sb.append(PriceMath.fmt(unconfirmed)).append(" unconfirmed");
        }
        sb.append(")");
    }

    static String nodeReplyMessage(JSONObject json, String fallback) {
        if (json == null) return fallback;
        String msg = json.optString("message", "");
        if (msg.isEmpty()) msg = json.optString("error", "");
        Object resp = json.opt("response");
        if (msg.isEmpty() && resp instanceof String) msg = (String) resp;
        return msg.isEmpty() ? fallback : msg;
    }
}

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
    private static final int TAB_TRADE = 0, TAB_CHART = 1, TAB_TAPE = 2, TAB_ORDERS = 3, TAB_ASSETS = 4;
    private static final String[] TAB_NAMES = {"TRADE", "CHART", "TRADES", "ORDERS", "ASSETS"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private NodeApi node;
    private DexDb db;
    private DexStats stats;
    private BookRepository repo;
    private DexTxn txn;
    private KeySet keySet;
    private Pending pending;
    private DexProcessor processor;
    private boolean keepAliveAsked = false;
    private boolean scriptReady = true;   // covenant verified+registered on this node
    private volatile boolean busy = false;          // a spend is in flight — lock the CTA
    private Runnable restQueue;                     // limit balance to place once a sweep lands
    private java.util.List<String> restQueueCoins;  // the swept coins we're waiting to vanish
    private BroadcastReceiver notifyReceiver;

    private TradeView trade;
    private ChartTab chartTab;
    private TapeTab tapeTab;
    private OrdersTab ordersTab;
    private AssetsTab assetsTab;
    private FrameLayout content;
    private LinearLayout tabBar;
    private TextView pairPill, blockPill, footer;
    private int tab = TAB_TRADE;
    private boolean paired = false;
    private boolean inputFocused = false;
    private long chainBlock = 0;
    private BigDecimal minimaSendable = BigDecimal.ZERO, usdtSendable = BigDecimal.ZERO;
    private String receiveAddr = "";

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            poll();
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        db = new DexDb(this);
        stats = new DexStats(db);
        pending = new Pending(this);
        keySet = new KeySet(this, this::repaint);

        setContentView(buildChrome());

        // node wiring AFTER first paint (local-first)
        node = new NodeApi(this, enabled -> {
            paired = enabled;
            repaint();
            if (enabled) onPaired();
        });
        repo = new BookRepository(node, db);
        txn = new DexTxn(node, db);
        processor = new DexProcessor(this, txn);
        repo.setFillSink(this::onFillObserved);
        repo.subscribe((orders, syncing) -> {
            pending.resolve(orders, chainBlock);
            reconcileSweep(orders);
            // remember my own live orders so they can still be found after they age out of
            // the node's searchable window (the recovery path in ORDERS)
            for (Order5 o : orders.values()) {
                if (o.isMine(keySet.keys())) db.rememberMyOrder(o.coinid, o.orderId, "", o.created);
            }
            // the foreground Activity owns renewals while it's up (the service stands down)
            if (paired && keySet.ready() && chainBlock > 0) {
                processor.process(orders, keySet.keys(), chainBlock, new DexProcessor.Listener() {
                    @Override public void onRenewed(Order5 o) {}
                    @Override public void onRenewFailed(Order5 o, String why) {
                        toast("Renewal failed for order @ " + PriceMath.fmt(o.price()) + " — will retry");
                    }
                });
            }
            repaint();
        });

        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(MainActivity.this, intent)) return;
                String data = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                if (data == null) return;
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) poll();
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        repaint();
    }

    private void onPaired() {
        keySet.refresh(node);
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r == null) return;
                keySet.addExtra(r.optString("publickey", ""));
                txn.setIdentity(r.optString("publickey", ""), r.optString("address", ""));
                receiveAddr = r.optString("miniaddress", r.optString("address", ""));
                repaint();
            }
            @Override public void onError(String message) {
                // identity is required before ANY spend — retry rather than leaving the user
                // with an opaque failure and a stuck optimistic row
                ui.postDelayed(() -> { if (paired) onPaired(); }, 5_000);
            }
        });
        // Verify + register the covenant on EVERY pairing, not once per install: the flag
        // lives in the app's DB but the registration lives in the NODE's wallet, so a node
        // reinstall/resync would otherwise leave the script unregistered — and then cancels,
        // relocks and sweeps all fail the scripts gate while `send` still happily creates
        // orders the user cannot cancel. The key is versioned so the trackall:false change
        // re-registers on existing installs.
        DexContract.ensureScript(node, new DexContract.Ready() {
            @Override public void ok() { scriptReady = true; db.putMeta("tracked_v2", "1"); repo.refresh(); }
            @Override public void failed(String why) {
                scriptReady = false;
                toast("Covenant check failed: " + why);
            }
        });
        poll();
        maybeStartKeepAlive();
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

    private android.view.View buildChrome() {
        int pad = Design.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG());
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, pad, pad, Design.dp(this, 6));
        TextView logo = new TextView(this);
        logo.setText("◆ PANDADEX");
        logo.setTextColor(Design.TEXT());
        logo.setTypeface(Design.monoBold());
        logo.setTextSize(15f);
        header.addView(logo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

        trade = new TradeView(this);
        chartTab = new ChartTab(this);
        tapeTab = new TapeTab(this);
        ordersTab = new OrdersTab(this);
        assetsTab = new AssetsTab(this);
        content.addView(trade);
        content.addView(chartTab);
        content.addView(tapeTab);
        content.addView(ordersTab);
        content.addView(assetsTab);

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
        repaint();
    }

    /** Repaint the VISIBLE tab only. */
    private void repaint() {
        if (pairPill == null) return;
        pairPill.setText(paired ? "NODE ✓" : "PAIR IN MINIMA → APPS");
        pairPill.setTextColor(paired ? Design.IN() : Design.ACCENT());
        blockPill.setText("# " + (chainBlock > 0 ? chainBlock : "—"));
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == tab ? Design.ACCENT() : Design.DIM2());
            t.setBackgroundColor(i == tab ? Design.ACCENT_SOFT() : Design.SURFACE());
        }
        if (repo == null) return;
        switch (tab) {
            case TAB_TRADE:  trade.render(repo.book(), !paired, chainBlock, pending.rows()); break;
            case TAB_CHART:  chartTab.render(); break;
            case TAB_TAPE:   tapeTab.render(); break;
            case TAB_ORDERS: ordersTab.render(); break;
            case TAB_ASSETS: assetsTab.render(); break;
        }
    }

    /** Every precondition for spending funds: paired, identity read, covenant registered. */
    private boolean ready() {
        if (!paired) { toast("Pair with your node first"); return false; }
        if (txn == null || txn.pubkey().isEmpty() || txn.hexAddr().isEmpty()) {
            toast("Still reading your wallet identity — try again in a moment");
            return false;
        }
        if (!scriptReady) {
            toast("The order-book contract isn't registered on your node yet");
            return false;
        }
        return true;
    }

    /** Mid-price of the live book (best bid/ask), or the last trade, or null. */
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
        BigDecimal[] s = stats.stats24h();
        if (s[0] != null) return s[0];
        return bestAsk != null ? bestAsk : bestBid;
    }

    public String receiveAddress() { return receiveAddr; }

    public void repaintTrade() { repaint(); }

    // ------------------------------------------------------------------ polling

    private void poll() {
        if (node == null) return;
        if (!node.isEnabled()) { node.reRegister(); return; }
        if (inputFocused) return;   // never yank the ground from under a typing user
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) chainBlock = Util.dec(r.optString("block", "0")).longValue();
                if (repo != null) repo.setChainBlock(chainBlock);
                if (txn != null) txn.setChainBlock(chainBlock);
                repaint();
            }
            @Override public void onError(String message) {}
        });
        balances();
        if (repo != null) repo.refresh();
    }

    private void balances() {
        node.cmd("balance tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                minimaSendable = firstSendable(json, false);
                repaint();
            }
            @Override public void onError(String message) {}
        });
        node.cmd("balance tokenid:" + DexContract.USDT_ID, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                usdtSendable = firstSendable(json, true);
                repaint();
            }
            @Override public void onError(String message) {}
        });
    }

    private static BigDecimal firstSendable(JSONObject json, boolean token) {
        Object resp = json.opt("response");
        JSONObject row = null;
        if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) {
            row = ((JSONArray) resp).optJSONObject(0);
        } else if (resp instanceof JSONObject) {
            row = (JSONObject) resp;
        }
        if (row == null) return BigDecimal.ZERO;
        // `balance` reports TOKEN units in sendable/confirmed for both native and tokens
        return Util.dec(row.optString("sendable", row.optString("confirmed", "0")));
    }

    // ------------------------------------------------------------------ actions

    public void placeOrder(boolean buy, BigDecimal minima, BigDecimal price, boolean gtc, BigDecimal minRem) {
        if (!ready()) return;
        // optimistic row FIRST — the UI moves instantly
        Pending.Row row = new Pending.Row();
        row.kind = Pending.PLACE;
        row.buy = buy;
        row.minima = minima;
        row.price = price;
        row.submitMs = System.currentTimeMillis();
        row.submitBlock = chainBlock;
        row.orderId = "";
        pending.add(row);
        busy = true;
        repaint();
        txn.createOrder(buy, minima, price, gtc, minRem, new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                busy = false;
                toast("Order posted");
                repo.refresh();
                repaint();
            }
            @Override public void onFailed(String message) {
                busy = false;
                toast("Order failed: " + message);
                repaint();
            }
        });
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
              .append(PriceMath.fmt(t.order.price())).append(t.partial ? "  (partial)" : "").append("\n");
        }
        if (rest.signum() > 0) {
            sb.append("\nResting ").append(PriceMath.fmt(rest)).append(" MINIMA @ ")
              .append(PriceMath.fmt(price)).append(" as a limit order");
        }
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle(buy ? "Confirm buy" : "Confirm sell")
                .setMessage(sb.toString())
                .setPositiveButton("Execute", (d, w) -> {
                    busy = true;
                    txn.fillSweep(plan, new DexTxn.Result() {
                        @Override public void onPosted(String txpowid) {
                            busy = false;
                            toast("Sweep posted");
                            repo.refresh();
                            // The resting balance is queued, NOT placed now: the sweep has
                            // already committed these funds, so an immediate `send` would
                            // fail "insufficient funds". The queue fires once the swept order
                            // coins are actually gone from the book.
                            if (rest.signum() > 0) {
                                restQueue = new Runnable() {
                                    @Override public void run() { placeOrder(buy, rest, price, gtc, minRem); }
                                };
                                restQueueCoins = new java.util.ArrayList<>();
                                for (SweepPlanner.Take t : plan.takes) restQueueCoins.add(t.order.coinid);
                            }
                        }
                        @Override public void onFailed(String message) {
                            busy = false;
                            toast("Sweep failed: " + message);
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
    private void reconcileSweep(Map<String, Order5> book) {
        if (restQueueCoins == null) return;
        for (String coinid : restQueueCoins) {
            if (book.containsKey(coinid)) return;         // sweep hasn't landed yet
        }
        Runnable q = restQueue;
        restQueue = null;
        restQueueCoins = null;
        if (q != null) q.run();
    }

    public void cancelOrder(Order5 o) {
        Pending.Row row = new Pending.Row();
        row.kind = Pending.CANCEL;
        row.orderId = o.orderId;
        row.coinid = o.coinid;
        row.buy = !o.sell;
        row.minima = o.minimaAmount();
        row.price = o.price();
        row.submitMs = System.currentTimeMillis();
        row.submitBlock = chainBlock;
        pending.add(row);
        repo.tape().noteMyCancel(o.coinid);   // persisted — the bg service's tape reads it too
        db.forgetMyOrder(o.coinid);
        repaint();
        txn.cancel(o, new DexTxn.Result() {
            @Override public void onPosted(String txpowid) { toast("Cancel posted"); repo.refresh(); }
            @Override public void onFailed(String message) { toast("Cancel failed: " + message); }
        });
    }

    /** Atomic in-place reprice — ONE transaction, the order never leaves the book. */
    public void editOrder(Order5 o) {
        EditText in = new EditText(this);
        in.setHint("New price (mxUSDT per MINIMA)");
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setKeyListener(android.text.method.DigitsKeyListener.getInstance(java.util.Locale.US, false, true));
        in.setText(o.price().stripTrailingZeros().toPlainString());
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Edit order price")
                .setView(in)
                .setPositiveButton("Update", (d, w) -> {
                    BigDecimal np = Util.dec(in.getText().toString());
                    if (np.signum() <= 0) { toast("Bad price"); return; }
                    BigDecimal newWant = o.sell
                            ? PriceMath.up(o.locked.multiply(np, PriceMath.MC), PriceMath.USDT_DP)
                            : PriceMath.down(o.locked.divide(np, PriceMath.MINIMA_DP, java.math.RoundingMode.DOWN),
                                    PriceMath.MINIMA_DP);
                    Pending.Row row = new Pending.Row();
                    row.kind = Pending.EDIT;
                    row.orderId = o.orderId;
                    row.coinid = o.coinid;
                    row.buy = !o.sell;
                    row.minima = o.minimaAmount();
                    row.price = np;
                    row.submitMs = System.currentTimeMillis();
                    row.submitBlock = chainBlock;
                    pending.add(row);
                    repaint();
                    txn.relock(o, newWant, new DexTxn.Result() {
                        @Override public void onPosted(String txpowid) { toast("Reprice posted"); repo.refresh(); }
                        @Override public void onFailed(String message) { toast("Reprice failed: " + message); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onFillObserved(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                                boolean takerBuy, boolean partial) {
        boolean mine = order.isMine(keys());
        boolean isNew = db.addFill(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                takerBuy, partial, mine);
        if (isNew && mine) {
            db.addMyTrade(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                    !order.sell, true, order.orderId);
            toast((partial ? "Partial fill: " : "Filled: ") + PriceMath.fmt(size) + " MINIMA @ "
                    + PriceMath.fmt(price));
            Notifier.fill(this, order.sell, size, price, partial);
        }
        if (isNew) stats.invalidate();
    }

    // ------------------------------------------------------------------ accessors for views

    public DexStats db() { return stats; }
    public Set<String> keys() { return keySet.keys(); }
    public Map<String, Order5> book() { return repo == null ? new java.util.LinkedHashMap<>() : repo.book(); }
    public long chainBlock() { return chainBlock; }
    public BigDecimal minimaSendable() { return minimaSendable; }
    public BigDecimal usdtSendable() { return usdtSendable; }
    public void setInputFocused(boolean f) { inputFocused = f; }
    public boolean isBusy() { return busy; }

    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        inputFocused = false;
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        ui.removeCallbacks(pollTask);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(pollTask);
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
    }
}

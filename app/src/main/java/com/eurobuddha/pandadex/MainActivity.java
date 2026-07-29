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
    private static final int TAB_TRADE = 0, TAB_CHART = 1, TAB_TAPE = 2, TAB_ORDERS = 3,
            TAB_ASSETS = 4, TAB_MAKER = 5;
    private static final String[] TAB_NAMES = {"TRADE", "CHART", "TRADES", "ORDERS", "ASSETS", "MAKER"};

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
    /** Order coins this device is currently taking — shown as FILLING on the ladder. */
    private final java.util.Set<String> filling = new java.util.HashSet<>();
    private java.util.List<String> awaitingFill;    // coins whose disappearance = our fill landed
    private boolean awaitingBuy;
    private BigDecimal awaitingMinima = BigDecimal.ZERO, awaitingPrice = BigDecimal.ZERO;
    private BroadcastReceiver notifyReceiver;

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
    private boolean paired = false;
    private boolean inputFocused = false;
    private long chainBlock = 0;
    private BigDecimal minimaSendable = BigDecimal.ZERO, usdtSendable = BigDecimal.ZERO;
    /** Funds that have arrived but aren't spendable yet — the gap between "sold" and seeing
     *  the money, which otherwise looks like the trade didn't pay out. */
    private BigDecimal minimaPending = BigDecimal.ZERO, usdtPending = BigDecimal.ZERO;
    private String receiveAddr = "";

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            poll();
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
        maker = new MakerEngine(makerCfg, txn);
        repo.setFillSink(this::onFillObserved);
        repo.subscribe((orders, syncing) -> {
            pending.resolve(orders, chainBlock, new Pending.Listener() {
                @Override public void onLive(Pending.Row r) {
                    // The order is on the book — fillable and cancellable from this instant.
                    String what = (r.buy ? "Buy " : "Sell ") + PriceMath.fmt(r.minima)
                            + " MINIMA @ " + PriceMath.fmtPrice(r.price);
                    setStage(what + " is LIVE on the book");
                    Notifier.alert(MainActivity.this, "Order live", what + " is on the order book");
                }
                @Override public void onSettled(Pending.Row r) {
                    setStage(Pending.CANCEL.equals(r.kind) ? "Order cancelled — funds back in your wallet"
                                                           : "New price is live on the book");
                }
                @Override public void onGaveUp(Pending.Row r) {
                    setStage(Pending.PLACE.equals(r.kind)
                            ? "Never saw that order rest on the book — it may have been taken "
                              + "immediately. Check Orders and your balance."
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
            if (paired && keySet.ready() && chainBlock > 0 && !busy && FOREGROUND) {
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
            if (paired && keySet.ready() && chainBlock > 0) {
                processor.process(orders, keySet.keys(), keySet.addrs(),
                        maker.ownedOrderIds(), chainBlock, new DexProcessor.Listener() {
                    @Override public void onRenewed(Order5 o) {}
                    @Override public void onRenewFailed(Order5 o, String why) {
                        toast("Renewal failed for order @ " + PriceMath.fmtPrice(o.price()) + " — will retry");
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

        // Reveal exactly one tab. MUST run — the tab views are added GONE above.
        if (savedInstanceState != null) tab = savedInstanceState.getInt(KEY_TAB, TAB_TRADE);
        selectTab(tab);
    }

    private static final String KEY_TAB = "tab";

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_TAB, tab);
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
        // The build version lives HERE, not buried in the footer: a silently-failed update
        // (an over-the-top install that doesn't take) once looked like an app bug for a whole
        // round of testing because two phones were running different builds.
        TextView verPill = Design.pill(this, "v" + BuildConfig.VERSION_NAME,
                Design.SURFACE2(), Design.DIM2());
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vp.rightMargin = Design.dp(this, 6);
        header.addView(verPill, vp);

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
            case TAB_MAKER:  makerTab.render(); break;
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
    private String stage = "";
    private long stageAtMs = 0;
    private static final long STAGE_HOLD_MS = 45_000;

    /** Say what is happening RIGHT NOW. Blocks take ~50s, so silence during that wait reads
     *  as a broken app — this is the running commentary for both placing and filling. */
    public void setStage(String s) {
        stage = s == null ? "" : s;
        stageAtMs = System.currentTimeMillis();
        repaint();
    }

    public String stage() {
        if (stage.isEmpty()) return "";
        if (System.currentTimeMillis() - stageAtMs > STAGE_HOLD_MS) return "";
        return stage;
    }

    // ------------------------------------------------------------------ polling

    private void poll() {
        if (node == null) return;
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
                minimaPending = pendingOf(json);
                repaint();
            }
            @Override public void onError(String message) {}
        });
        node.cmd("balance tokenid:" + DexContract.USDT_ID, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                usdtSendable = firstSendable(json, true);
                usdtPending = pendingOf(json);
                repaint();
            }
            @Override public void onError(String message) {}
        });
    }

    /** Funds present but not yet spendable: what the node is still confirming. Shown so a
     *  completed trade doesn't look unpaid while the coins mature. */
    private static BigDecimal pendingOf(JSONObject json) {
        Object resp = json.opt("response");
        JSONObject row = null;
        if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) {
            row = ((JSONArray) resp).optJSONObject(0);
        } else if (resp instanceof JSONObject) {
            row = (JSONObject) resp;
        }
        if (row == null) return BigDecimal.ZERO;
        BigDecimal unconfirmed = Util.dec(row.optString("unconfirmed", "0"));
        BigDecimal confirmed = Util.dec(row.optString("confirmed", "0"));
        BigDecimal sendable = Util.dec(row.optString("sendable", confirmed.toPlainString()));
        // anything confirmed-but-not-yet-spendable is also still maturing
        BigDecimal maturing = confirmed.subtract(sendable);
        if (maturing.signum() < 0) maturing = BigDecimal.ZERO;
        return unconfirmed.add(maturing);
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
        busy = true;
        String orderId = txn.createOrder(buy, minima, price, gtc, minRem, new DexTxn.Result() {
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
        // Only show the optimistic row once the send was actually accepted for posting, and
        // carry the REAL order id so Pending.resolve can match it against the live book —
        // an empty id can never match, leaving a good order stuck on "PLACING…" and then
        // falsely warning "NOT CONFIRMED — check funds".
        if (orderId == null) { busy = false; repaint(); return; }
        row.orderId = orderId;
        pending.add(row);
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
                    busy = true;
                    // mark the rows we're taking so the ladder shows them as in-flight
                    filling.clear();
                    for (SweepPlanner.Take t : plan.takes) filling.add(t.order.coinid);
                    setStage("Building transaction… selecting coins and signing");
                    txn.fillSweep(plan, new DexTxn.Result() {
                        @Override public void onPosted(String txpowid) {
                            busy = false;
                            setStage("Posted — waiting for a block to confirm your "
                                    + (buy ? "buy" : "sell") + " of "
                                    + PriceMath.fmt(plan.totalMinima) + " MINIMA");
                            awaitingFill = new java.util.ArrayList<>(filling);
                            awaitingBuy = buy;
                            awaitingMinima = plan.totalMinima;
                            awaitingPrice = SweepPlanner.avgPrice(plan);
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
                            filling.clear();
                            setStage("Trade failed — " + message);
                            toast("Trade failed: " + message);
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
            if (book.containsKey(coinid)) return;        // at least one leg still resting
        }
        java.util.List<String> done = awaitingFill;
        awaitingFill = null;
        filling.removeAll(done);
        String msg = (awaitingBuy ? "Bought " : "Sold ") + PriceMath.fmt(awaitingMinima)
                + " MINIMA @ " + PriceMath.fmtPrice(awaitingPrice);
        // Say where the money is. The proceeds are on-chain the moment the trade mines, but
        // they are not SPENDABLE until the node has confirmed them, and that gap previously
        // read as "the trade completed but I wasn't paid".
        setStage("✓ " + msg + " — proceeds are confirming, see ASSETS");
        Notifier.alert(this, "Trade complete", msg + ". Funds are confirming and will show as "
                + "available shortly.");
        for (String coinid : done) {
            db.addMyTrade(coinid, System.currentTimeMillis(), chainBlock, awaitingPrice,
                    awaitingMinima, awaitingBuy, false, "");
        }
        stats.invalidate();
    }

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
        if (mine.isEmpty()) { toast("No open orders"); if (onDone != null) onDone.run(); return; }

        BigDecimal totalMinima = BigDecimal.ZERO, totalUsdt = BigDecimal.ZERO;
        for (Order5 o : mine) {
            if (o.sell) totalMinima = totalMinima.add(o.locked);
            else totalUsdt = totalUsdt.add(o.locked);
        }
        String msg = "Cancel all " + mine.size() + " open order" + (mine.size() == 1 ? "" : "s")
                + "?\n\nThis returns " + PriceMath.fmt(totalMinima) + " MINIMA and "
                + PriceMath.fmt(totalUsdt) + " mxUSDT to your wallet.\n\nEach cancel is a "
                + "separate transaction, so this takes a moment.";
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Cancel all orders")
                .setMessage(msg)
                .setPositiveButton("Cancel them", (d, w) -> cancelSequentially(mine, 0, 0, 0, onDone))
                .setNegativeButton("Keep them", null)
                .show();
    }

    private void cancelSequentially(java.util.List<Order5> list, int idx, int ok, int failed,
                                    Runnable onDone) {
        if (idx >= list.size()) {
            busy = false;
            String summary = failed == 0
                    ? "Cancelled " + ok + " order" + (ok == 1 ? "" : "s")
                    : "Cancelled " + ok + ", " + failed + " could not be cancelled — they may "
                            + "have just been filled. Check your open orders.";
            setStage(summary);
            toast(summary);
            repo.refresh();
            repaint();
            if (onDone != null) onDone.run();
            return;
        }
        busy = true;
        Order5 o = list.get(idx);
        setStage("Cancelling " + (idx + 1) + " of " + list.size() + "…");
        // an optimistic CANCEL row per order, so the Orders tab shows "CANCELLING — waiting
        // for a block" instead of an unchanged book for minutes (the 0.2.6 complaint)
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
        repo.tape().noteMyCancel(o.coinid);
        db.forgetMyOrder(o.coinid);
        txn.cancel(o, new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                cancelSequentially(list, idx + 1, ok + 1, failed, onDone);
            }
            @Override public void onFailed(String message) {
                cancelSequentially(list, idx + 1, ok, failed + 1, onDone);
            }
        });
    }

    /** Maker events → the stage line AND optimistic Pending rows, so every rung's journey
     *  (mining → live, cancelling → gone) is visible on the Trade/Orders/Maker surfaces —
     *  0.2.6 routed everything into one 45s status line only the Trade tab rendered. */
    private final MakerEngine.Listener makerListener = new MakerEngine.Listener() {
        @Override public void onMakerState(String message) { setStage(message); }

        @Override public void onCreateSent(MakerLadder.Slot slot, String orderId) {
            Pending.Row row = new Pending.Row();
            row.kind = Pending.PLACE;
            row.orderId = orderId;
            row.buy = !slot.sell;
            row.minima = slot.sizeMinima;
            row.price = slot.price;
            row.submitMs = System.currentTimeMillis();
            row.submitBlock = chainBlock;
            pending.add(row);
            repaint();
        }

        @Override public void onCancelSent(Order5 o) {
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
        }

        @Override public void onRelockSent(Order5 o, BigDecimal newPrice) {
            Pending.Row row = new Pending.Row();
            row.kind = Pending.EDIT;
            row.orderId = o.orderId;
            row.coinid = o.coinid;
            row.buy = !o.sell;
            row.minima = o.minimaAmount();
            row.price = newPrice;
            row.submitMs = System.currentTimeMillis();
            row.submitBlock = chainBlock;
            pending.add(row);
            repaint();
        }
    };

    /** Publish the ladder — after an affordability check and showing what will be committed. */
    public void publishMaker(java.util.List<MakerLadder.Slot> desired, boolean pegged) {
        if (!ready()) return;
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
            if (minimaPending.signum() > 0) lack.append(" (").append(PriceMath.fmt(minimaPending))
                    .append(" more still confirming)");
            lack.append(".\n");
        }
        if (c.bidUsdt.compareTo(usdtSendable) > 0) {
            lack.append("Bids need ").append(PriceMath.fmt(c.bidUsdt))
                    .append(" mxUSDT — you have ").append(PriceMath.fmt(usdtSendable))
                    .append(" sendable");
            if (usdtPending.signum() > 0) lack.append(" (").append(PriceMath.fmt(usdtPending))
                    .append(" more still confirming)");
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
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Publish the ladder")
                .setMessage("This puts " + (nBids + nAsks) + " orders on the book — " + sides + ".\n\n"
                        + (pegged
                        ? "PEGGED: prices track the MEXC mid and reprice when it moves past your "
                        + "threshold; your per-rung sizes are kept. If the feed goes stale the "
                        + "ladder quotes wider, then withdraws."
                        : "NOT pegged: the book gets YOUR exact prices — never repriced, never "
                        + "withdrawn, even if the price feed dies.")
                        + "\n\nRungs post ONE PER SIDE per cycle — each is an on-chain "
                        + "transaction your phone does proof-of-work for, and each one's change "
                        + "has to confirm before the next on that side can be funded — so a full "
                        + "ladder takes a few minutes to build and a rung may retry before it "
                        + "sticks. The Maker tab shows each rung's progress.\n\n"
                        + "With the app CLOSED the ladder is still maintained, but only every "
                        + "few minutes — so it builds slower and a stale-feed withdrawal can "
                        + "lag by that much. Keep the app open while it builds.")
                .setPositiveButton("Publish", (d, w) -> {
                    makerCfg.armed = true;
                    makerCfg.lastActedMid = null;      // act on the next cycle
                    makerCfg.save();
                    setStage("Ladder publishing — posting the first rungs");
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
        makerCfg.save();
        repaint();
        if (maker.isWorking()) {
            // queue it: once disarmed, onBook stops running, so nothing else would ever come
            // back to finish this and the ladder would sit on the book despite the request
            toast("Finishing the current adjustment — the ladder comes off right after");
            maker.runWhenIdle(this::withdrawLadder);
            return;
        }
        if (makerCfg.slots.isEmpty() && makerCfg.cancelTombstones.isEmpty()) {
            toast("No ladder orders to withdraw");
            return;
        }
        setStage("Withdrawing the ladder — cancels leave the book as blocks confirm them");
        maker.withdrawAll(book(), keys(), chainBlock, makerListener);
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
        boolean mine = order.isMine(keys(), addrs());
        boolean isNew = db.addFill(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                takerBuy, partial, mine);
        if (isNew && mine) {
            db.addMyTrade(spentCoin, System.currentTimeMillis(), chainBlock, price, size,
                    !order.sell, true, order.orderId);
            toast((partial ? "Partial fill: " : "Filled: ") + PriceMath.fmt(size) + " MINIMA @ "
                    + PriceMath.fmtPrice(price));
            Notifier.fill(this, order.sell, size, price, partial);
        }
        if (isNew) stats.invalidate();
    }

    // ------------------------------------------------------------------ accessors for views

    public DexStats db() { return stats; }
    public Set<String> keys() { return keySet.keys(); }
    /** My wallet addresses — the second ownership factor; see {@link KeySet#owns}. */
    public Set<String> addrs() { return keySet.addrs(); }
    public Map<String, Order5> book() { return repo == null ? new java.util.LinkedHashMap<>() : repo.book(); }
    public long chainBlock() { return chainBlock; }
    public java.util.List<Pending.Row> pendingRows() { return pending.rows(); }
    public BigDecimal minimaSendable() { return minimaSendable; }
    public BigDecimal usdtSendable() { return usdtSendable; }
    public BigDecimal minimaPending() { return minimaPending; }
    public BigDecimal usdtPending() { return usdtPending; }
    public void setInputFocused(boolean f) { inputFocused = f; }
    public boolean isBusy() { return busy; }
    public java.util.Set<String> filling() { return filling; }

    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
        super.onDestroy();
        ui.removeCallbacks(pollTask);
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
    }
}

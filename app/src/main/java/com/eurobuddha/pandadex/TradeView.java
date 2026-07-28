package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The MEXC-style spot screen: ticker → depth ladder → order panel → open orders.
 * Inputs are built ONCE and never rebuilt (Limit's hard-won lesson); read-only sections are
 * re-rendered per book update (bounded row counts, no jitter — mono/tabular numerals).
 */
@SuppressLint("ViewConstructor")
public final class TradeView extends LinearLayout {

    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ticker
    private TextView lastPriceTv, deltaTv, highTv, lowTv, volTv, syncDot;
    private BigDecimal shownLast = null;

    // ladder
    private LinearLayout asksBox, bidsBox;
    private TextView centerPriceTv;
    /**
     * Price-level grouping. Index 0 is NO GROUPING (null tick) and is the DEFAULT: two orders
     * at different prices must never be silently merged into one level — a maker who posts at
     * 0.05150 and 0.05200 has to see two rows. Coarser ticks are opt-in for deep books, and
     * the choice is persisted.
     */
    private static final BigDecimal[] GROUPS = {null, new BigDecimal("0.0001"),
            new BigDecimal("0.001"), new BigDecimal("0.01")};
    private static final String[] GROUP_LABELS = {"exact", "0.0001", "0.001", "0.01"};
    private static final String PREFS = "pandadex_ui";
    private static final String KEY_GROUP = "ladder_group";
    private int groupIdx = 0;
    private LinearLayout groupRow;

    // order panel (build-once)
    private boolean buyMode = true;
    private TextView buyTab, sellTab, totalTv, ctaBtn, gtcTv, advTv;
    private EditText priceIn, amountIn, minFillIn;
    private View advBox;
    private boolean gtcOn = true;
    private final Runnable totalTask = this::updateTotal;

    // open orders
    private LinearLayout ordersBox;
    // running commentary (placing an order / taking a fill)
    private TextView stageTv;

    public TradeView(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        buildStage();
        buildTicker();
        buildLadder();
        buildOrderPanel();
        buildOrders();
    }

    private int dp(int v) { return Design.dp(getContext(), v); }

    private TextView tv(String s, float size, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = dp(12);
        c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        addView(c, lp);
        return c;
    }

    /** One always-visible line telling the user what the app is doing right now. Blocks take
     *  ~50s; without this the app looks frozen between tapping and settling. */
    private void buildStage() {
        stageTv = tv("", 11.5f, Design.ACCENT(), Design.sansBold());
        stageTv.setPadding(dp(12), dp(9), dp(12), dp(9));
        stageTv.setBackground(Design.roundBg(getContext(), Design.ACCENT_SOFT(), 10));
        stageTv.setVisibility(GONE);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        addView(stageTv, lp);
    }

    // ------------------------------------------------------------------ ticker

    private void buildTicker() {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv("MINIMA / mxUSDT", 13f, Design.TEXT(), Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        syncDot = tv("● syncing", 10f, Design.DIM(), Design.sans());
        top.addView(syncDot);
        c.addView(top);

        LinearLayout mid = new LinearLayout(getContext());
        mid.setGravity(Gravity.CENTER_VERTICAL);
        lastPriceTv = tv("—", 26f, Design.TEXT(), Design.monoBold());
        mid.addView(lastPriceTv);
        deltaTv = tv("", 12f, Design.DIM(), Design.mono());
        LayoutParams dl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dl.leftMargin = dp(10);
        mid.addView(deltaTv, dl);
        c.addView(mid);

        LinearLayout stats = new LinearLayout(getContext());
        highTv = stat(stats, "24h High");
        lowTv = stat(stats, "24h Low");
        volTv = stat(stats, "24h Vol (MINIMA)");
        c.addView(stats);
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(tv(label, 9.5f, Design.DIM2(), Design.sans()));
        TextView v = tv("—", 12f, Design.DIM(), Design.mono());
        col.addView(v);
        row.addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    // ------------------------------------------------------------------ ladder

    private void buildLadder() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(getContext());
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("ORDER BOOK", 10f, Design.DIM(), Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        groupIdx = getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_GROUP, 0);
        if (groupIdx < 0 || groupIdx >= GROUPS.length) groupIdx = 0;
        groupRow = new LinearLayout(getContext());
        for (int i = 0; i < GROUPS.length; i++) {
            final int idx = i;
            TextView g = tv(GROUP_LABELS[i], 10f,
                    i == groupIdx ? Design.ON_ACCENT() : Design.DIM(), Design.mono());
            // a real tap target — the old 6x2dp chips were nearly unhittable
            g.setPadding(dp(9), dp(6), dp(9), dp(6));
            g.setOnClickListener(v -> {
                groupIdx = idx;
                getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                        .edit().putInt(KEY_GROUP, idx).apply();
                restyleGroups();
                act.repaintTrade();
            });
            Design.pressable(g);
            LayoutParams glp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            glp.leftMargin = dp(4);
            groupRow.addView(g, glp);
        }
        head.addView(groupRow);
        restyleGroups();
        c.addView(head);

        LinearLayout legend = new LinearLayout(getContext());
        legend.addView(tv("Price (mxUSDT)", 9f, Design.DIM2(), Design.sans()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
        TextView a = tv("Amount (MINIMA)", 9f, Design.DIM2(), Design.sans());
        a.setGravity(Gravity.END);
        legend.addView(a, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView t2 = tv("Total", 9f, Design.DIM2(), Design.sans());
        t2.setGravity(Gravity.END);
        legend.addView(t2, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        c.addView(legend);

        asksBox = new LinearLayout(getContext());
        asksBox.setOrientation(VERTICAL);
        c.addView(asksBox);

        centerPriceTv = tv("—", 16f, Design.TEXT(), Design.monoBold());
        centerPriceTv.setGravity(Gravity.CENTER);
        centerPriceTv.setPadding(0, dp(6), 0, dp(6));
        c.addView(centerPriceTv);

        bidsBox = new LinearLayout(getContext());
        bidsBox.setOrientation(VERTICAL);
        c.addView(bidsBox);
    }

    private void restyleGroups() {
        for (int i = 0; i < groupRow.getChildCount(); i++) {
            TextView g = (TextView) groupRow.getChildAt(i);
            boolean on = i == groupIdx;
            g.setTextColor(on ? Design.ON_ACCENT() : Design.DIM());
            g.setBackground(on ? Design.roundBg(getContext(), Design.ACCENT(), 8)
                               : Design.roundBg(getContext(), Design.SURFACE2(), 8));
        }
    }

    /** One ladder row with a right-anchored translucent depth bar BEHIND the numbers. */
    private View ladderRow(BigDecimal price, BigDecimal amount, BigDecimal total,
                           float depthFrac, boolean ask, boolean mine, boolean filling,
                           BigDecimal exactPrice) {
        FrameLayout f = new FrameLayout(getContext());
        View bar = new View(getContext());
        int barColor = ask ? (Design.RED() & 0x00FFFFFF) | 0x22000000
                           : (Design.IN() & 0x00FFFFFF) | 0x22000000;
        bar.setBackgroundColor(barColor);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        bl.gravity = Gravity.END;
        f.addView(bar, bl);
        f.post(() -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
            p.width = (int) (f.getWidth() * Math.min(1f, depthFrac));
            bar.setLayoutParams(p);
        });

        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView p = tv((mine ? "• " : "") + PriceMath.fmtPrice(price), 11f,
                ask ? Design.RED() : Design.IN(), Design.mono());
        row.addView(p, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
        TextView am = tv(PriceMath.fmt(amount), 11f, Design.TEXT(), Design.mono());
        am.setGravity(Gravity.END);
        row.addView(am, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView to = tv(filling ? "FILLING…" : PriceMath.fmt(total.setScale(4, RoundingMode.HALF_UP)),
                11f, filling ? Design.ACCENT() : Design.DIM(), Design.mono());
        to.setGravity(Gravity.END);
        row.addView(to, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        f.addView(row);
        BigDecimal prefill = exactPrice != null ? exactPrice : price;
        f.setOnClickListener(v -> priceIn.setText(prefill.stripTrailingZeros().toPlainString()));
        Design.pressable(f);
        return f;
    }

    // ------------------------------------------------------------------ order panel

    private void buildOrderPanel() {
        LinearLayout c = card();

        LinearLayout tabs = new LinearLayout(getContext());
        buyTab = tv("BUY", 13f, Design.ON_ACCENT(), Design.sansBold());
        sellTab = tv("SELL", 13f, Design.DIM(), Design.sansBold());
        for (TextView t : new TextView[]{buyTab, sellTab}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            tabs.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        buyTab.setOnClickListener(v -> setMode(true));
        sellTab.setOnClickListener(v -> setMode(false));
        c.addView(tabs);

        priceIn = input("Price (mxUSDT per MINIMA)");
        amountIn = input("Amount (MINIMA)");
        c.addView(priceIn);
        c.addView(amountIn);

        LinearLayout pct = new LinearLayout(getContext());
        for (int p : new int[]{25, 50, 75, 100}) {
            TextView b = tv(p + "%", 10f, Design.DIM(), Design.mono());
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(5), 0, dp(5));
            b.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
            final int fp = p;
            b.setOnClickListener(v -> applyPercent(fp));
            Design.pressable(b);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), dp(6), dp(2), dp(2));
            pct.addView(b, lp);
        }
        c.addView(pct);

        totalTv = tv("Total: —", 11f, Design.DIM(), Design.mono());
        totalTv.setPadding(0, dp(6), 0, 0);
        c.addView(totalTv);

        LinearLayout opts = new LinearLayout(getContext());
        opts.setGravity(Gravity.CENTER_VERTICAL);
        gtcTv = tv("∞ GTC on", 11f, Design.ACCENT(), Design.sans());
        gtcTv.setOnClickListener(v -> {
            gtcOn = !gtcOn;
            gtcTv.setText(gtcOn ? "∞ GTC on" : "GTC off");
            gtcTv.setTextColor(gtcOn ? Design.ACCENT() : Design.DIM2());
        });
        opts.addView(gtcTv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        advTv = tv("Advanced ▾", 10f, Design.DIM2(), Design.sans());
        advTv.setOnClickListener(v -> {
            advBox.setVisibility(advBox.getVisibility() == VISIBLE ? GONE : VISIBLE);
            advTv.setText(advBox.getVisibility() == VISIBLE ? "Advanced ▴" : "Advanced ▾");
        });
        opts.addView(advTv);
        opts.setPadding(0, dp(6), 0, 0);
        c.addView(opts);

        minFillIn = input("Min unfilled remainder (MINIMA, anti-dust)");
        minFillIn.setText("1");
        LinearLayout adv = new LinearLayout(getContext());
        adv.setOrientation(VERTICAL);
        adv.addView(minFillIn);
        adv.setVisibility(GONE);
        advBox = adv;
        c.addView(adv);

        ctaBtn = tv("BUY MINIMA", 14f, Design.ON_ACCENT(), Design.sansBold());
        ctaBtn.setGravity(Gravity.CENTER);
        ctaBtn.setPadding(0, dp(12), 0, dp(12));
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(8);
        c.addView(ctaBtn, cl);
        ctaBtn.setOnClickListener(v -> submit());
        Design.pressable(ctaBtn);
        setMode(true);

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int a2, int a3) {}
            @Override public void onTextChanged(CharSequence s, int a1, int a2, int a3) {}
            @Override public void afterTextChanged(Editable s) {
                // debounced — NOTHING runs synchronously per keystroke (Samsung IME race)
                ui.removeCallbacks(totalTask);
                ui.postDelayed(totalTask, 300);
            }
        };
        priceIn.addTextChangedListener(w);
        amountIn.addTextChangedListener(w);
    }

    private EditText input(String hint) {
        EditText e = new EditText(getContext());
        e.setHint(hint);
        e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT());
        e.setTypeface(Design.mono());
        e.setTextSize(13f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setKeyListener(android.text.method.DigitsKeyListener.getInstance(java.util.Locale.US, false, true));
        e.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        e.setPadding(dp(10), dp(9), dp(10), dp(9));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        e.setOnFocusChangeListener((v, has) -> act.setInputFocused(has));
        return e;
    }

    private void setMode(boolean buy) {
        buyMode = buy;
        buyTab.setBackground(Design.roundBg(getContext(), buy ? Design.IN() : Design.SURFACE2(), 10));
        buyTab.setTextColor(buy ? Color.WHITE : Design.DIM());
        sellTab.setBackground(Design.roundBg(getContext(), !buy ? Design.RED() : Design.SURFACE2(), 10));
        sellTab.setTextColor(!buy ? Color.WHITE : Design.DIM());
        ctaBtn.setText(buy ? "BUY MINIMA" : "SELL MINIMA");
        ctaBtn.setBackground(Design.ripple(Design.roundBg(getContext(), buy ? Design.IN() : Design.RED(), 12)));
        updateTotal();
    }

    private void applyPercent(int pct) {
        BigDecimal price = Util.dec(priceIn.getText().toString());
        if (buyMode) {
            if (price.signum() <= 0) return;
            BigDecimal usdt = act.usdtSendable().multiply(new BigDecimal(pct))
                    .divide(new BigDecimal(100), PriceMath.USDT_DP, RoundingMode.DOWN);
            amountIn.setText(usdt.divide(price, PriceMath.MINIMA_DP, RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString());
        } else {
            BigDecimal m = act.minimaSendable().multiply(new BigDecimal(pct))
                    .divide(new BigDecimal(100), PriceMath.MINIMA_DP, RoundingMode.DOWN);
            amountIn.setText(m.stripTrailingZeros().toPlainString());
        }
        updateTotal();
    }

    private void updateTotal() {
        BigDecimal p = Util.dec(priceIn.getText().toString());
        BigDecimal a = Util.dec(amountIn.getText().toString());
        if (p.signum() > 0 && a.signum() > 0) {
            totalTv.setText("Total: " + PriceMath.fmt(
                    PriceMath.up(a.multiply(p, PriceMath.MC), PriceMath.USDT_DP)) + " mxUSDT");
        } else {
            totalTv.setText("Total: —");
        }
    }

    private void submit() {
        if (act.isBusy()) { act.toast("A transaction is already in flight"); return; }
        BigDecimal price = Util.dec(priceIn.getText().toString());
        BigDecimal amount = Util.dec(amountIn.getText().toString());
        BigDecimal minRem = Util.dec(minFillIn.getText().toString());
        if (price.signum() <= 0 || amount.signum() <= 0) {
            act.toast("Enter a price and amount");
            return;
        }
        // MARKETABLE LIMIT: if the order crosses the book, take the resting liquidity first
        // (that's what makes a "market order" possible without any AMM) and only rest the
        // unfilled balance. This is the taker path — one sweep txn, ≤1 partial.
        SweepPlanner.Plan plan = SweepPlanner.plan(act.book().values(), buyMode, amount, price, act.chainBlock());
        if (!plan.isEmpty()) {
            act.confirmSweep(plan, buyMode, amount, price, gtcOn, minRem.max(BigDecimal.ZERO));
        } else {
            act.placeOrder(buyMode, amount, price, gtcOn, minRem.max(BigDecimal.ZERO));
        }
        amountIn.setText("");
    }

    // ------------------------------------------------------------------ open orders

    private void buildOrders() {
        LinearLayout c = card();
        c.addView(tv("OPEN ORDERS", 10f, Design.DIM(), Design.sansBold()));
        ordersBox = new LinearLayout(getContext());
        ordersBox.setOrientation(VERTICAL);
        c.addView(ordersBox);
    }

    // ------------------------------------------------------------------ render

    /** Re-render every read-only section. Inputs are NEVER touched here. */
    public void render(Map<String, Order5> book, boolean syncing, long chainBlock,
                       List<Pending.Row> pending) {
        String st = act.stage();
        stageTv.setText(st);
        stageTv.setVisibility(st.isEmpty() ? GONE : VISIBLE);

        syncDot.setText(syncing ? "● syncing" : "● live");
        syncDot.setTextColor(syncing ? Design.DIM() : Design.IN());

        // ticker from the local tape
        BigDecimal[] s = act.db().stats24h();
        if (s[0] != null) {
            String txt = PriceMath.fmtPrice(s[0]);
            if (shownLast != null && s[0].compareTo(shownLast) != 0) {
                boolean up = s[0].compareTo(shownLast) > 0;
                lastPriceTv.setTextColor(up ? Design.IN() : Design.RED());
                lastPriceTv.setText(txt + (up ? " ▲" : " ▼"));
                Design.pulse(lastPriceTv, up ? Design.IN() : Design.RED());
            } else {
                lastPriceTv.setText(txt);
            }
            shownLast = s[0];
            if (s[1] != null) {
                boolean up = s[1].signum() >= 0;
                deltaTv.setText((up ? "+" : "") + s[1].toPlainString() + "%");
                deltaTv.setTextColor(up ? Design.IN() : Design.RED());
            }
            highTv.setText(PriceMath.fmtPrice(s[2]));
            lowTv.setText(PriceMath.fmtPrice(s[3]));
            volTv.setText(PriceMath.fmt(s[4]));
        }

        ctaBtn.setAlpha(act.isBusy() ? 0.5f : 1f);
        ctaBtn.setEnabled(!act.isBusy());
        renderLadder(book, chainBlock);
        renderOrders(book, chainBlock, pending);
    }

    private void renderLadder(Map<String, Order5> book, long chainBlock) {
        BigDecimal tick = GROUPS[groupIdx];
        TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>();
        TreeMap<BigDecimal, BigDecimal> bids = new TreeMap<>((a, b) -> b.compareTo(a));
        TreeMap<BigDecimal, Boolean> mineAt = new TreeMap<>();
        TreeMap<BigDecimal, Boolean> fillingAt = new TreeMap<>();
        // the exact price of the best order behind each level — tapping a row must prefill
        // what you would actually trade at, not the rounded label
        TreeMap<BigDecimal, BigDecimal> exactAt = new TreeMap<>();
        for (Order5 o : book.values()) {
            if (o.expired(chainBlock)) continue;
            BigDecimal g = levelPrice(o, tick);
            (o.sell ? asks : bids).merge(g, o.minimaAmount(), BigDecimal::add);
            if (o.isMine(act.keys())) mineAt.merge(g, true, (x, y) -> true);
            if (act.filling().contains(o.coinid)) fillingAt.merge(g, true, (x, y) -> true);
            exactAt.merge(g, o.price(), (x, y) -> o.sell ? x.min(y) : x.max(y));
        }

        asksBox.removeAllViews();
        bidsBox.removeAllViews();
        int MAX_ROWS = 10;

        // asks render top-down from HIGH to LOW so the best ask sits just above center
        List<Map.Entry<BigDecimal, BigDecimal>> askList = new ArrayList<>(asks.entrySet());
        askList = askList.subList(0, Math.min(MAX_ROWS, askList.size()));
        BigDecimal askMax = cumMax(askList);
        BigDecimal running = BigDecimal.ZERO;
        List<View> askRows = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> e : askList) {
            running = running.add(e.getValue());
            askRows.add(ladderRow(e.getKey(), e.getValue(),
                    PriceMath.up(e.getKey().multiply(e.getValue(), PriceMath.MC), 4),
                    askMax.signum() == 0 ? 0 : running.divide(askMax, 4, RoundingMode.HALF_UP).floatValue(),
                    true, mineAt.containsKey(e.getKey()), fillingAt.containsKey(e.getKey()),
                    exactAt.get(e.getKey())));
        }
        for (int i = askRows.size() - 1; i >= 0; i--) asksBox.addView(askRows.get(i));

        List<Map.Entry<BigDecimal, BigDecimal>> bidList = new ArrayList<>(bids.entrySet());
        bidList = bidList.subList(0, Math.min(MAX_ROWS, bidList.size()));
        BigDecimal bidMax = cumMax(bidList);
        running = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> e : bidList) {
            running = running.add(e.getValue());
            bidsBox.addView(ladderRow(e.getKey(), e.getValue(),
                    PriceMath.up(e.getKey().multiply(e.getValue(), PriceMath.MC), 4),
                    bidMax.signum() == 0 ? 0 : running.divide(bidMax, 4, RoundingMode.HALF_UP).floatValue(),
                    false, mineAt.containsKey(e.getKey()), fillingAt.containsKey(e.getKey()),
                    exactAt.get(e.getKey())));
        }

        BigDecimal bestAsk = askList.isEmpty() ? null : askList.get(0).getKey();
        BigDecimal bestBid = bidList.isEmpty() ? null : bidList.get(0).getKey();
        if (shownLast != null) {
            centerPriceTv.setText(PriceMath.fmtPrice(shownLast));
        } else if (bestAsk != null && bestBid != null) {
            centerPriceTv.setText(PriceMath.fmtPrice(
                    bestAsk.add(bestBid).divide(new BigDecimal(2), PriceMath.PRICE_DP, RoundingMode.HALF_UP)));
        } else {
            centerPriceTv.setText("—");
        }
    }

    /**
     * The price level an order belongs to. With no tick selected (the default) the level is
     * the order's own price rounded only to what the UI actually shows — so two orders merge
     * only when they are genuinely indistinguishable on screen, never because of a grouping
     * bucket the user didn't ask for. With a tick, asks round UP and bids round DOWN so a
     * level never flatters the side it's on.
     */
    static BigDecimal levelPrice(Order5 o, BigDecimal tick) {
        BigDecimal p = o.price();
        if (tick == null) {
            return p.setScale(PriceMath.DISPLAY_DP, RoundingMode.HALF_UP);
        }
        return o.sell ? p.divide(tick, 0, RoundingMode.CEILING).multiply(tick)
                      : p.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
    }

    private static BigDecimal cumMax(List<Map.Entry<BigDecimal, BigDecimal>> list) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> e : list) sum = sum.add(e.getValue());
        return sum;
    }

    private void renderOrders(Map<String, Order5> book, long chainBlock, List<Pending.Row> pending) {
        ordersBox.removeAllViews();
        for (Pending.Row r : pending) {
            LinearLayout row = orderRowShell();
            row.addView(tv((r.buy ? "BUY " : "SELL ") + PriceMath.fmt(r.minima) + " @ "
                            + PriceMath.fmtPrice(r.price), 11f,
                    r.buy ? Design.IN() : Design.RED(), Design.mono()),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView st = tv(r.status(chainBlock), 9.5f, Design.ACCENT(), Design.sans());
            row.addView(st);
            ordersBox.addView(row);
        }
        boolean any = !pending.isEmpty();
        for (Order5 o : book.values()) {
            if (!o.isMine(act.keys())) continue;
            any = true;
            LinearLayout row = orderRowShell();
            String label = (o.sell ? "SELL " : "BUY ") + PriceMath.fmt(o.minimaAmount())
                    + " @ " + PriceMath.fmtPrice(o.price()) + (o.gtc ? "  ∞" : "");
            row.addView(tv(label, 11f, o.sell ? Design.RED() : Design.IN(), Design.mono()),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (o.expired(chainBlock)) {
                row.addView(tv("EXPIRED", 9.5f, Design.DIM2(), Design.sans()));
            }
            TextView edit = tv(" ✎ ", 13f, Design.DIM(), Design.sans());
            edit.setOnClickListener(v -> act.editOrder(o));
            row.addView(edit);
            TextView cancel = tv(" ✕ ", 13f, Design.RED(), Design.sans());
            cancel.setOnClickListener(v -> act.cancelOrder(o));
            row.addView(cancel);
            ordersBox.addView(row);
        }
        if (!any) {
            ordersBox.addView(tv("No open orders", 11f, Design.DIM2(), Design.sans()));
        }
    }

    private LinearLayout orderRowShell() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }
}

package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MARKET MAKER — a ladder of bids and offers tracked to the MEXC mid.
 *
 * Built ONCE and only restyled afterwards: this screen is full of number fields, and a
 * re-render that rebuilt them would eat whatever the user was typing (the lesson that took
 * four attempts to learn in the Limit app).
 */
@SuppressLint("ViewConstructor")
public final class MakerTab extends LinearLayout {

    private final MainActivity act;
    private final MakerConfig cfg;

    private TextView stateTv, feedTv, midTv, armBtn;
    private final List<EditText[]> levelRows = new ArrayList<>();   // [offset, size] per rung
    private EditText skewIn, repriceIn;
    private LinearLayout levelsBox;
    private boolean built = false;

    public MakerTab(MainActivity act, MakerConfig cfg) {
        super(act);
        this.act = act;
        this.cfg = cfg;
        setOrientation(VERTICAL);
        int p = dp(12);
        setPadding(p, p, p, p);
        build();
    }

    private int dp(int v) { return Design.dp(getContext(), v); }

    private TextView tv(String s, float size, int colour, android.graphics.Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setTypeface(tf);
        return t;
    }

    private EditText num(String value) {
        EditText e = new EditText(getContext());
        e.setText(value);
        e.setTextColor(Design.TEXT());
        e.setTypeface(Design.mono());
        e.setTextSize(13f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setKeyListener(android.text.method.DigitsKeyListener.getInstance(
                java.util.Locale.US, false, true));
        e.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        e.setPadding(dp(8), dp(7), dp(8), dp(7));
        e.setOnFocusChangeListener((v, has) -> {
            act.setInputFocused(has);
            if (!has) commit();
        });
        return e;
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

    private void build() {
        if (built) return;
        built = true;

        // ---- status ----
        LinearLayout head = card();
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv("MARKET MAKER", 13f, Design.TEXT(), Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        stateTv = tv("DISARMED", 10f, Design.DIM(), Design.sansBold());
        top.addView(stateTv);
        head.addView(top);
        midTv = tv("—", 20f, Design.TEXT(), Design.monoBold());
        head.addView(midTv);
        feedTv = tv("no price feed", 9.5f, Design.DIM2(), Design.sans());
        head.addView(feedTv);
        head.addView(tv("Your ladder tracks the MEXC mid for MINIMA/mxUSDT. Orders, matching "
                + "and settlement stay entirely on-chain — the feed is only the reference "
                + "price. If it goes stale the ladder quotes wider, then withdraws itself.",
                9.5f, Design.DIM2(), Design.sans()));

        // ---- ladder ----
        LinearLayout lc = card();
        lc.addView(tv("LADDER", 10f, Design.DIM(), Design.sansBold()));
        LinearLayout legend = new LinearLayout(getContext());
        legend.setPadding(0, dp(6), 0, dp(2));
        legend.addView(tv("level", 9f, Design.DIM2(), Design.sans()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.7f));
        legend.addView(tv("offset %", 9f, Design.DIM2(), Design.sans()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        legend.addView(tv("size (MINIMA)", 9f, Design.DIM2(), Design.sans()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        lc.addView(legend);

        levelsBox = new LinearLayout(getContext());
        levelsBox.setOrientation(VERTICAL);
        lc.addView(levelsBox);
        buildLevelRows();

        LinearLayout rowBtns = new LinearLayout(getContext());
        rowBtns.setPadding(0, dp(8), 0, 0);
        rowBtns.addView(chip("− level", v -> { changeLevels(-1); }));
        rowBtns.addView(chip("+ level", v -> { changeLevels(1); }));
        rowBtns.addView(chip("fill sizes…", v -> fillAll()));
        lc.addView(rowBtns);

        // ---- tuning ----
        LinearLayout tc = card();
        tc.addView(tv("TUNING", 10f, Design.DIM(), Design.sansBold()));

        tc.addView(tv("Skew %", 11f, Design.TEXT(), Design.sansBold()));
        skewIn = num(cfg.skewPct.toPlainString());
        tc.addView(skewIn);
        tc.addView(tv("Shifts the whole ladder. Positive quotes higher on both sides — use it "
                + "when you'd rather end up longer.", 9.5f, Design.DIM2(), Design.sans()));

        tc.addView(tv("Reprice threshold %", 11f, Design.TEXT(), Design.sansBold()));
        repriceIn = num(cfg.repricePct.toPlainString());
        tc.addView(repriceIn);
        tc.addView(tv("Nothing is repriced until the mid moves this far. Every adjustment is a "
                + "transaction your phone has to do proof-of-work for, so a small number here "
                + "means a hot battery.", 9.5f, Design.DIM2(), Design.sans()));

        // ---- arm ----
        armBtn = tv("ARM MARKET MAKER", 14f, Design.ON_ACCENT(), Design.sansBold());
        armBtn.setGravity(Gravity.CENTER);
        armBtn.setPadding(0, dp(13), 0, dp(13));
        armBtn.setOnClickListener(v -> toggleArm());
        Design.pressable(armBtn);
        LayoutParams al = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        al.bottomMargin = dp(8);
        addView(armBtn, al);

        TextView withdraw = tv("Withdraw ladder now", 11f, Design.RED(), Design.sans());
        withdraw.setGravity(Gravity.CENTER);
        withdraw.setPadding(0, dp(10), 0, dp(10));
        withdraw.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        withdraw.setOnClickListener(v -> act.withdrawLadder());
        Design.pressable(withdraw);
        addView(withdraw, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private TextView chip(String label, OnClickListener onClick) {
        TextView t = tv(label, 10f, Design.DIM(), Design.mono());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        t.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        t.setOnClickListener(onClick);
        Design.pressable(t);
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildLevelRows() {
        levelsBox.removeAllViews();
        levelRows.clear();
        for (int i = 0; i < cfg.levels.size(); i++) {
            MakerLadder.Level lv = cfg.levels.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            row.addView(tv((i + 1) + "", 11f, Design.DIM(), Design.mono()),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.7f));
            EditText off = num(lv.offsetPct.toPlainString());
            EditText size = num(lv.sizeMinima.toPlainString());
            row.addView(off, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(size, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            levelRows.add(new EditText[]{off, size});
            levelsBox.addView(row);
        }
    }

    private void changeLevels(int delta) {
        commit();
        int n = cfg.levels.size() + delta;
        if (n < 1 || n > MakerLadder.MAX_LEVELS) {
            act.toast(n > MakerLadder.MAX_LEVELS
                    ? "6 levels a side is the cap — each one is a separate on-chain order"
                    : "At least one level");
            return;
        }
        if (delta > 0) {
            MakerLadder.Level last = cfg.levels.get(cfg.levels.size() - 1);
            cfg.levels.add(new MakerLadder.Level(
                    last.offsetPct.add(new BigDecimal("0.20")), last.sizeMinima));
        } else {
            cfg.levels.remove(cfg.levels.size() - 1);
        }
        cfg.save();
        buildLevelRows();
    }

    private void fillAll() {
        commit();
        if (cfg.levels.isEmpty()) return;
        BigDecimal size = cfg.levels.get(0).sizeMinima;
        List<MakerLadder.Level> out = new ArrayList<>();
        for (MakerLadder.Level l : cfg.levels) out.add(new MakerLadder.Level(l.offsetPct, size));
        cfg.levels.clear();
        cfg.levels.addAll(out);
        cfg.save();
        buildLevelRows();
        act.toast("All levels set to " + PriceMath.fmt(size) + " MINIMA");
    }

    /** Read the fields back into the config. Called on blur, on arm, and before restructuring. */
    public void commit() {
        for (int i = 0; i < levelRows.size() && i < cfg.levels.size(); i++) {
            EditText[] r = levelRows.get(i);
            BigDecimal off = Util.dec(r[0].getText().toString());
            BigDecimal size = Util.dec(r[1].getText().toString());
            if (off.signum() <= 0) off = new BigDecimal("0.20");
            cfg.levels.set(i, new MakerLadder.Level(off, size));
        }
        if (skewIn != null) cfg.skewPct = Util.dec(skewIn.getText().toString());
        if (repriceIn != null) {
            BigDecimal r = Util.dec(repriceIn.getText().toString());
            cfg.repricePct = r.signum() > 0 ? r : new BigDecimal("0.25");
        }
        cfg.save();
    }

    private void toggleArm() {
        commit();
        if (!cfg.armed) {
            BigDecimal total = BigDecimal.ZERO;
            for (MakerLadder.Level l : cfg.levels) total = total.add(l.sizeMinima);
            act.armMaker(cfg.levels.size(), total);
        } else {
            act.disarmMaker();
        }
    }

    /** Restyle only — never rebuilds the inputs. */
    public void render() {
        double mid = MarketPrice.mid();
        midTv.setText(mid > 0 ? PriceMath.fmtPrice(BigDecimal.valueOf(mid)) : "—");
        feedTv.setText(MarketPrice.stateLabel());
        feedTv.setTextColor(MarketPrice.mustWithdraw() ? Design.RED()
                : MarketPrice.fresh() ? Design.DIM2() : Design.ACCENT());

        boolean armed = cfg.armed;
        stateTv.setText(armed ? (MarketPrice.mustWithdraw() ? "WITHDRAWN" : "ARMED") : "DISARMED");
        stateTv.setTextColor(armed ? (MarketPrice.mustWithdraw() ? Design.RED() : Design.IN())
                                   : Design.DIM());
        armBtn.setText(armed ? "DISARM (cancels the ladder)" : "ARM MARKET MAKER");
        armBtn.setBackground(Design.ripple(Design.roundBg(getContext(),
                armed ? Design.RED() : Design.IN(), 12)));

        MarketPrice.refreshAsync();
    }
}

package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MARKET MAKER — configured exactly the way AtomiX's order editor works (editOrderDialog):
 *
 *   - the ladder is EXPLICIT rungs — a price and a MINIMA amount per rung, asks laid out
 *     order-book style (highest at the top, best ask at the bottom, next to the bids);
 *   - the AUTO-FILL row (mid · step % · levels, then independent ask/bid sizes) SEEDS the
 *     rungs and regenerates them on every parameter edit — no Generate button; any rung can
 *     be hand-edited afterwards; a side whose size is blank/0 is not quoted (one-sided);
 *   - the PEG switch quotes around the live MEXC mid instead of a typed one (the mid field
 *     greys out), with skew ±% and a reprice ≥ % threshold;
 *   - a live preview shows level counts, best prices, side totals and a crossed-market warning.
 *
 * Built ONCE and only restyled afterwards: this screen is full of number fields, and a
 * re-render that rebuilt them would eat whatever the user was typing (the lesson that took
 * four attempts to learn in the Limit app). The rung rows are only ever setText'd by the
 * auto-fill in response to the user editing a seed parameter — same as AtomiX.
 */
@SuppressLint("ViewConstructor")
public final class MakerTab extends LinearLayout {

    private final MainActivity act;
    private final MakerConfig cfg;

    private TextView stateTv, feedTv, midTv, armBtn, previewTv, pegPxTv;
    private SwitchCompat pegSw;
    private EditText midIn, stepIn, levelsIn, askSizeIn, bidSizeIn, skewIn, repriceIn;
    private final EditText[][] askRows = new EditText[MakerLadder.MAX_LEVELS][];
    private final EditText[][] bidRows = new EditText[MakerLadder.MAX_LEVELS][];
    private boolean built = false;
    /** Programmatic setText during auto-fill must not re-trigger the param watchers. */
    private boolean filling = false;
    /** Peg turned on before the first price landed — fill the moment it does. */
    private boolean pegAwaitFill = false;

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

    /**
     * Decimal entry the Samsung keyboard can't scramble — copied from AtomiX. The
     * "0.0054 → 0.0045 / cursor jumps" bug is the IME's composing/predictive region on a
     * numberDecimal field; TYPE_TEXT_VARIATION_VISIBLE_PASSWORD forces immediate-commit with
     * no composing/autocorrect, and the InputFilter keeps it to one decimal number.
     */
    private EditText num(String value, String hint, boolean allowNegative) {
        EditText e = new EditText(getContext());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTransformationMethod(null);   // visible-password must still show the digits, not dots
        final String pattern = allowNegative ? "-?[0-9]*\\.?[0-9]*" : "[0-9]*\\.?[0-9]*";
        e.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
            String r = dest.toString().substring(0, dstart)
                    + source.subSequence(start, end)
                    + dest.toString().substring(dend);
            return (r.isEmpty() || r.matches(pattern)) ? null : "";
        }});
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT());
        e.setTypeface(Design.mono());
        e.setTextSize(13f);
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

    private TextView sectionLabel(String s) {
        TextView t = tv(s, 10f, Design.DIM(), Design.sansBold());
        t.setLetterSpacing(0.03f);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** A rung row: label + price + amount, AtomiX's numRow2. Returns {price, amount}. */
    private EditText[] rungRow(LinearLayout parent, String label, MakerLadder.Level seed, int labelColour) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(tv(label, 11f, labelColour, Design.mono()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.5f));
        EditText price = num(seed == null ? "" : trim(seed.price), "price", false);
        EditText amount = num(seed == null ? "" : trim(seed.sizeMinima), "MINIMA", false);
        LayoutParams l1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = dp(6);
        row.addView(price, l1);
        row.addView(amount, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return new EditText[]{price, amount};
    }

    private static String trim(BigDecimal v) {
        if (v == null || v.signum() == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }

    private static TextWatcher onChange(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { r.run(); }
        };
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

        // ---- the ladder editor (AtomiX editOrderDialog, inline) ----
        LinearLayout lc = card();
        lc.addView(tv("MY MARKET / LADDER", 10f, Design.DIM(), Design.sansBold()));
        TextView hint = tv("Your depth ladder for MINIMA / mxUSDT  (price = mxUSDT per MINIMA):\n"
                + "•  ASKS — where YOU SELL MINIMA (higher).   BIDS — where YOU BUY (lower).\n"
                + "•  Each rung is a separate on-chain order. Leave rows blank to skip them.",
                9.5f, Design.DIM2(), Design.sans());
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(4), 0, dp(6));
        lc.addView(hint);

        // ---- auto-MM: peg the ladder to the live MEXC MINIMA/USDT market ----
        lc.addView(sectionLabel("AUTO MARKET-MAKE — PEG TO MEXC"));
        pegSw = new SwitchCompat(getContext());
        pegSw.setText("Peg ladder to MEXC (auto-reprice)");
        pegSw.setTextColor(Design.DIM());
        pegSw.setChecked(cfg.pegged);
        lc.addView(pegSw);
        pegPxTv = tv("", 10f, Design.DIM(), Design.mono());
        pegPxTv.setPadding(0, dp(2), 0, dp(2));
        lc.addView(pegPxTv);
        TextView pegHint = tv("While pegged, the ladder regenerates around the MEXC mid "
                + "(± step %, your size per rung) and reprices when the market moves ≥ your "
                + "threshold. If the feed goes stale it quotes wider, then withdraws itself. "
                + "MEXC's MINIMA market is THIN — keep step % above its typical spread and "
                + "rung sizes small; you auto-trade at these prices. Every adjustment is a "
                + "transaction your phone does proof-of-work for.",
                9.5f, Design.DIM2(), Design.sans());
        pegHint.setLineSpacing(dp(2), 1f);
        pegHint.setPadding(0, 0, 0, dp(4));
        lc.addView(pegHint);
        LinearLayout pegRow = new LinearLayout(getContext());
        pegRow.setGravity(Gravity.CENTER_VERTICAL);
        pegRow.setPadding(0, dp(2), 0, dp(2));
        skewIn = num(trim(cfg.skewPct), "skew ±%", true);
        repriceIn = num(cfg.repricePct.toPlainString(), "reprice ≥ %", false);
        LayoutParams pr1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        pr1.rightMargin = dp(6);
        pegRow.addView(skewIn, pr1);
        pegRow.addView(repriceIn, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        lc.addView(pegRow);

        // ---- auto-fill: seeds the rungs; rows regenerate as these change (no Generate button) ----
        lc.addView(sectionLabel("AUTO-FILL (mid · step % · levels, then ask/bid size — seeds the rungs; edit any rung after while unpegged)"));
        LinearLayout gen = new LinearLayout(getContext());
        gen.setGravity(Gravity.CENTER_VERTICAL);
        gen.setPadding(0, dp(4), 0, dp(2));
        midIn = num(trim(cfg.manualMid), "mid", false);
        stepIn = num(trim(cfg.stepPct), "step %", false);
        levelsIn = num(String.valueOf(cfg.levelCount), "levels", false);
        LayoutParams g1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        g1.rightMargin = dp(6);
        LayoutParams g2 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        g2.rightMargin = dp(6);
        gen.addView(midIn, g1);
        gen.addView(stepIn, g2);
        gen.addView(levelsIn, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.6f));
        lc.addView(gen);
        // independent per-side sizes — a blank/zero side is NOT seeded or quoted (one-sided market)
        LinearLayout genS = new LinearLayout(getContext());
        genS.setGravity(Gravity.CENTER_VERTICAL);
        genS.setPadding(0, dp(2), 0, dp(2));
        askSizeIn = num(trim(cfg.askSize), "ask size", false);
        bidSizeIn = num(trim(cfg.bidSize), "bid size", false);
        LayoutParams gs1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        gs1.rightMargin = dp(6);
        genS.addView(askSizeIn, gs1);
        genS.addView(bidSizeIn, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        lc.addView(genS);

        // ---- ASKS (you SELL, higher) — order-book style: highest (A6) at the top, best ask
        // A1 at the BOTTOM adjacent to the bids, so the spread sits in the middle. askRows[]
        // stays indexed A1..A6 (A1 = best); only the visual insertion order is reversed.
        TextView ah = tv("ASKS — you SELL MINIMA (higher price)", 11f, Design.RED(), Design.sansBold());
        ah.setLetterSpacing(0.03f);
        ah.setPadding(0, dp(12), 0, dp(2));
        lc.addView(ah);
        for (int i = MakerLadder.MAX_LEVELS - 1; i >= 0; i--) {
            askRows[i] = rungRow(lc, "A" + (i + 1),
                    i < cfg.asks.size() ? cfg.asks.get(i) : null, Design.RED());
        }

        // ---- BIDS (you BUY, lower) ----
        TextView bh = tv("BIDS — you BUY MINIMA (lower price)", 11f, Design.IN(), Design.sansBold());
        bh.setLetterSpacing(0.03f);
        bh.setPadding(0, dp(12), 0, dp(2));
        lc.addView(bh);
        for (int i = 0; i < MakerLadder.MAX_LEVELS; i++) {
            bidRows[i] = rungRow(lc, "B" + (i + 1),
                    i < cfg.bids.size() ? cfg.bids.get(i) : null, Design.IN());
        }

        // ---- live ladder summary: level counts, best prices, side totals, crossed warning ----
        previewTv = tv("", 10.5f, Design.DIM(), Design.sans());
        previewTv.setLineSpacing(dp(2), 1f);
        previewTv.setPadding(0, dp(10), 0, dp(2));
        lc.addView(previewTv);

        // Watchers attach AFTER all initial seeding, so opening the tab never stomps a saved
        // (possibly hand-tuned) ladder; only an actual edit regenerates — same as AtomiX.
        TextWatcher pw = onChange(this::updatePreview);
        for (EditText[] row : askRows) { row[0].addTextChangedListener(pw); row[1].addTextChangedListener(pw); }
        for (EditText[] row : bidRows) { row[0].addTextChangedListener(pw); row[1].addTextChangedListener(pw); }
        updatePreview();

        // AUTO-GENERATE: any seed-param edit rebuilds the 6+6 rows. repriceIn isn't watched
        // (the reprice threshold doesn't shape the rows).
        TextWatcher gw = onChange(this::autoGen);
        midIn.addTextChangedListener(gw);
        stepIn.addTextChangedListener(gw);
        levelsIn.addTextChangedListener(gw);
        askSizeIn.addTextChangedListener(gw);
        bidSizeIn.addTextChangedListener(gw);
        skewIn.addTextChangedListener(gw);

        pegModeUi();
        pegSw.setOnCheckedChangeListener((btn, on) -> {
            // Persist the flip IMMEDIATELY: tapping a switch blurs no field, so without this
            // an ARMED engine kept running in the old mode until some unrelated blur committed.
            // A targeted write, not commit() — the fields may hold a half-typed value.
            cfg.pegged = on;
            cfg.save();
            pegModeUi();
            if (on) {
                MarketPrice.refreshAsync();
                if (MarketPrice.fresh()) fillFromPeg();
                else { pegAwaitFill = true; act.toast("Fetching MEXC price…"); }
            }
        });

        // ---- arm / withdraw ----
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

    // ---------------------------------------------------------------- auto-fill (AtomiX)

    /** While pegged the oracle owns the mid AND the rungs — grey out everything it writes.
     *  A hand-edited rung would be silently ignored by the engine and stomped by the next
     *  fill, so the rows are only editable unpegged. */
    private void pegModeUi() {
        boolean on = pegSw.isChecked();
        midIn.setEnabled(!on);
        midIn.setAlpha(on ? 0.5f : 1f);
        for (EditText[][] side : new EditText[][][]{askRows, bidRows}) {
            for (EditText[] row : side) {
                if (row == null) continue;
                for (EditText e : row) {
                    e.setEnabled(!on);
                    e.setAlpha(on ? 0.5f : 1f);
                }
            }
        }
    }

    private void autoGen() {
        if (filling) return;
        if (pegSw.isChecked()) {
            if (MarketPrice.fresh()) fillFromPeg();
            else { pegAwaitFill = true; MarketPrice.refreshAsync(); }   // the tick fills once the price lands
        } else {
            genRows(Util.dec(midIn.getText().toString()));
        }
    }

    /** Seed the rows from the live MEXC mid (AtomiX fillFromPeg). */
    private void fillFromPeg() {
        double m = MarketPrice.mid();
        if (!(m > 0) || !MarketPrice.fresh()) return;
        pegAwaitFill = false;
        genRows(BigDecimal.valueOf(m));
    }

    /**
     * Regenerate the 6+6 rows around {@code mid} — skew shifts the quoted mid, rung i sits at
     * ±(i+1)·step %, each side at its own size, and a side whose size is blank/0 is blanked
     * entirely (one-sided market). Silent: this fires per keystroke, so invalid/partial params
     * just pause row updates rather than toasting on every character.
     */
    private void genRows(BigDecimal mid) {
        BigDecimal step = Util.dec(stepIn.getText().toString());
        BigDecimal askSize = Util.dec(askSizeIn.getText().toString());
        BigDecimal bidSize = Util.dec(bidSizeIn.getText().toString());
        BigDecimal skew = clampSkew(Util.dec(skewIn.getText().toString()));
        if (mid == null || mid.signum() <= 0) return;
        BigDecimal hundred = new BigDecimal(100);
        BigDecimal quoted = mid.multiply(BigDecimal.ONE.add(skew.divide(hundred, PriceMath.MC)), PriceMath.MC);
        filling = true;
        try {
            if (pegSw.isChecked()) midIn.setText(trim(quoted.setScale(PriceMath.DISPLAY_DP,
                    java.math.RoundingMode.HALF_UP)));
            if (step.signum() <= 0 || (askSize.signum() <= 0 && bidSize.signum() <= 0)) return;
            int n = levelsClamped();
            for (int i = 0; i < MakerLadder.MAX_LEVELS; i++) {
                boolean askOn = i < n && askSize.signum() > 0;
                boolean bidOn = i < n && bidSize.signum() > 0;
                BigDecimal off = step.multiply(new BigDecimal(i + 1), PriceMath.MC).divide(hundred, PriceMath.MC);
                askRows[i][0].setText(askOn ? trim(quoted.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.UP)) : "");
                askRows[i][1].setText(askOn ? trim(askSize) : "");
                bidRows[i][0].setText(bidOn ? trim(quoted.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.DOWN)) : "");
                bidRows[i][1].setText(bidOn ? trim(bidSize) : "");
            }
        } finally {
            filling = false;
        }
        updatePreview();
    }

    private int levelsClamped() {
        BigDecimal n = Util.dec(levelsIn.getText().toString());
        return Math.max(1, Math.min(MakerLadder.MAX_LEVELS, n.intValue() == 0 ? 1 : n.intValue()));
    }

    private static BigDecimal clampSkew(BigDecimal v) {
        BigDecimal cap = new BigDecimal(20);
        if (v.compareTo(cap) > 0) return cap;
        if (v.compareTo(cap.negate()) < 0) return cap.negate();
        return v;
    }

    // ---------------------------------------------------------------- preview (AtomiX)

    private void updatePreview() {
        int nA = 0, nB = 0;
        BigDecimal bestAsk = null, bestBid = null, sumA = BigDecimal.ZERO, sumB = BigDecimal.ZERO;
        for (EditText[] row : askRows) {
            BigDecimal px = Util.dec(row[0].getText().toString());
            BigDecimal am = Util.dec(row[1].getText().toString());
            if (px.signum() > 0 && am.signum() > 0) {
                nA++; sumA = sumA.add(am);
                if (bestAsk == null || px.compareTo(bestAsk) < 0) bestAsk = px;
            }
        }
        for (EditText[] row : bidRows) {
            BigDecimal px = Util.dec(row[0].getText().toString());
            BigDecimal am = Util.dec(row[1].getText().toString());
            if (px.signum() > 0 && am.signum() > 0) {
                nB++; sumB = sumB.add(am);
                if (bestBid == null || px.compareTo(bestBid) > 0) bestBid = px;
            }
        }
        if (nA > 0 && nB > 0 && bestBid.compareTo(bestAsk) >= 0) {
            previewTv.setText("⚠ Crossed — best bid " + PriceMath.fmtPrice(bestBid) + " ≥ best ask "
                    + PriceMath.fmtPrice(bestAsk) + ": you'd sell cheaper than you buy");
            previewTv.setTextColor(Design.RED());
            return;
        }
        String bidS = nB > 0 ? (nB + " lvl · best " + PriceMath.fmtPrice(bestBid) + " · " + PriceMath.fmt(sumB) + " M") : "none";
        String askS = nA > 0 ? (nA + " lvl · best " + PriceMath.fmtPrice(bestAsk) + " · " + PriceMath.fmt(sumA) + " M") : "none";
        previewTv.setText("BIDS  " + bidS + "\nASKS  " + askS);
        previewTv.setTextColor(Design.DIM());
    }

    // ---------------------------------------------------------------- commit / arm

    /** Read the fields back into the config. Called on blur, on arm, and before restructuring. */
    public void commit() {
        cfg.asks.clear();
        cfg.bids.clear();
        for (EditText[] row : askRows) {
            cfg.asks.add(new MakerLadder.Level(Util.dec(row[0].getText().toString()),
                    Util.dec(row[1].getText().toString())));
        }
        for (EditText[] row : bidRows) {
            cfg.bids.add(new MakerLadder.Level(Util.dec(row[0].getText().toString()),
                    Util.dec(row[1].getText().toString())));
        }
        cfg.pegged = pegSw.isChecked();
        cfg.manualMid = Util.dec(midIn.getText().toString());
        cfg.stepPct = Util.dec(stepIn.getText().toString());
        cfg.levelCount = levelsClamped();
        cfg.askSize = Util.dec(askSizeIn.getText().toString());
        cfg.bidSize = Util.dec(bidSizeIn.getText().toString());
        cfg.skewPct = clampSkew(Util.dec(skewIn.getText().toString()));
        BigDecimal r = Util.dec(repriceIn.getText().toString());
        cfg.repricePct = r.signum() > 0 ? r : new BigDecimal("0.25");
        cfg.save();   // sanitize lives in save(): drops blanks/invalids, caps 6, sorts best-first
    }

    private void toggleArm() {
        commit();
        if (cfg.armed) {
            act.disarmMaker();
            return;
        }
        int nAsks, nBids;
        BigDecimal totalAsk, totalBid;
        if (cfg.pegged) {
            if (cfg.stepPct.signum() <= 0 || (cfg.askSize.signum() <= 0 && cfg.bidSize.signum() <= 0)) {
                act.toast("Peg needs a step % and an ask or bid size");
                return;
            }
            nAsks = cfg.askSize.signum() > 0 ? cfg.levelCount : 0;
            nBids = cfg.bidSize.signum() > 0 ? cfg.levelCount : 0;
            totalAsk = cfg.askSize.multiply(new BigDecimal(nAsks));
            totalBid = cfg.bidSize.multiply(new BigDecimal(nBids));
        } else {
            nAsks = cfg.asks.size();
            nBids = cfg.bids.size();
            totalAsk = BigDecimal.ZERO;
            totalBid = BigDecimal.ZERO;
            for (MakerLadder.Level l : cfg.asks) totalAsk = totalAsk.add(l.sizeMinima);
            for (MakerLadder.Level l : cfg.bids) totalBid = totalBid.add(l.sizeMinima);
            if (nAsks == 0 && nBids == 0) {
                act.toast("No levels set — add a bid or ask rung first");
                return;
            }
            if (MakerLadder.crossed(cfg.asks, cfg.bids)) {
                act.toast("⚠ Your best bid ≥ best ask (crossed market)");
            }
        }
        act.armMaker(nBids, nAsks, totalBid, totalAsk, cfg.pegged);
    }

    // ---------------------------------------------------------------- render / peg tick

    /** Poll the peg price line while visible (AtomiX polls its dialog every 2s). */
    private final Runnable pegTick = new Runnable() {
        @Override public void run() {
            // Stops itself while hidden — setVisibility(VISIBLE) restarts it.
            if (!isAttachedToWindow() || getVisibility() != VISIBLE) return;
            pegPxTv.setText(pegLine());
            if (pegSw.isChecked()) {
                MarketPrice.refreshAsync();
                if (pegAwaitFill) fillFromPeg();   // fill once the first price lands
            }
            postDelayed(this, 2000);
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(pegTick, 1000);
    }

    @Override public void setVisibility(int visibility) {
        boolean wasVisible = getVisibility() == VISIBLE;
        super.setVisibility(visibility);
        if (visibility == VISIBLE && !wasVisible && isAttachedToWindow()) {
            removeCallbacks(pegTick);
            post(pegTick);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(pegTick);
        super.onDetachedFromWindow();
    }

    private String pegLine() {
        double m = MarketPrice.mid();
        return (m > 0 ? "MEXC mid " + PriceMath.fmtPrice(BigDecimal.valueOf(m)) + " · " : "MEXC: ")
                + MarketPrice.stateLabel();
    }

    /** Restyle only — never rebuilds or rewrites the inputs. */
    public void render() {
        double mid = MarketPrice.mid();
        midTv.setText(mid > 0 ? PriceMath.fmtPrice(BigDecimal.valueOf(mid)) : "—");
        feedTv.setText(MarketPrice.stateLabel());
        feedTv.setTextColor(MarketPrice.mustWithdraw() ? Design.RED()
                : MarketPrice.fresh() ? Design.DIM2() : Design.ACCENT());
        pegPxTv.setText(pegLine());

        boolean armed = cfg.armed;
        stateTv.setText(armed ? (cfg.pegged && MarketPrice.mustWithdraw() ? "WITHDRAWN" : "ARMED")
                              : "DISARMED");
        stateTv.setTextColor(armed ? (cfg.pegged && MarketPrice.mustWithdraw() ? Design.RED() : Design.IN())
                                   : Design.DIM());
        armBtn.setText(armed ? "DISARM (cancels the ladder)" : "ARM MARKET MAKER");
        armBtn.setBackground(Design.ripple(Design.roundBg(getContext(),
                armed ? Design.RED() : Design.IN(), 12)));

        MarketPrice.refreshAsync();
    }
}

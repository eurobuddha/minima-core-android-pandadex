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

    private TextView stateTv, feedTv, midTv, armBtn, prepBtn, previewTv, pegPxTv, slotsTv, stageTv, applyBtn;
    private SwitchCompat pegSw;
    private EditText midIn, stepIn, levelsIn, askSizeIn, bidSizeIn, skewIn, repriceIn;
    private final EditText[][] askRows = new EditText[MakerLadder.MAX_LEVELS][];
    private final EditText[][] bidRows = new EditText[MakerLadder.MAX_LEVELS][];
    private boolean built = false;
    /** Programmatic setText during auto-fill must not re-trigger the param watchers. */
    private boolean filling = false;
    /** An edit made before a usable price arrived, held until one does — WITH the operation it
     *  is waiting to perform, because a level-count change and a size change are not the same
     *  thing and running the wrong one rewrites amounts the user typed. */
    private Runnable pendingApply = null;
    private String pendingEdit = null;
    /** Cache for the status panel's my-orders lookup, keyed on book identity. */
    private java.util.Map<String, Order5> bookSeen, myById;

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

    /** A rung row: label + price + amount + ✕ clear, AtomiX's numRow2. Returns {price, amount}. */
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
        TextView clr = tv("✕", 12f, Design.DIM2(), Design.sans());
        clr.setGravity(Gravity.CENTER);
        clr.setPadding(dp(8), dp(4), dp(2), dp(4));
        clr.setOnClickListener(v -> { price.setText(""); amount.setText(""); });
        Design.pressable(clr);
        row.addView(clr);
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

        prepBtn = tv("SPLIT FUNDS INTO 10 UTXOS", 12f, Design.ACCENT(), Design.sansBold());
        prepBtn.setGravity(Gravity.CENTER);
        prepBtn.setPadding(0, dp(10), 0, dp(10));
        prepBtn.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        prepBtn.setOnClickListener(v -> act.prepareMakerFundingUtxos());
        Design.pressable(prepBtn);
        LayoutParams pp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(4);
        pp.bottomMargin = dp(8);
        lc.addView(prepBtn, pp);

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
        lc.addView(sectionLabel("AUTO-FILL (mid · step % · levels, then ask/bid size — seeds the rungs; edit any rung after)"));
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

        // ---- per-rung on-chain status: the story 0.2.6 never told (mining/live/failed) ----
        lc.addView(sectionLabel("ON-CHAIN STATUS"));
        slotsTv = tv("", 10.5f, Design.DIM(), Design.mono());
        slotsTv.setLineSpacing(dp(2), 1f);
        slotsTv.setPadding(0, dp(2), 0, dp(2));
        lc.addView(slotsTv);
        stageTv = tv("", 9.5f, Design.DIM2(), Design.sans());
        stageTv.setPadding(0, dp(2), 0, dp(2));
        lc.addView(stageTv);

        // Watchers attach AFTER all initial seeding, so opening the tab never stomps a saved
        // (possibly hand-tuned) ladder; only an actual edit regenerates — same as AtomiX.
        TextWatcher pw = onChange(this::updatePreview);
        for (EditText[] row : askRows) { row[0].addTextChangedListener(pw); row[1].addTextChangedListener(pw); }
        for (EditText[] row : bidRows) { row[0].addTextChangedListener(pw); row[1].addTextChangedListener(pw); }
        updatePreview();

        // AUTO-GENERATE, split by what the edit means for the user's per-rung sizes:
        //  - size/levels seed edits REBUILD the rows (that's what the user asked for);
        //  - mid/step/skew edits touch PRICES ONLY — hand-tuned rung sizes must survive.
        // repriceIn isn't watched (the reprice threshold doesn't shape the rows).
        // ONE FIELD, ONE MEANING. Routing all three through a single rebuild meant the level
        // count could only work when the per-side SEED size fields were filled — which a user
        // who types amounts into the rungs legitimately leaves empty — and, when it did work,
        // it overwrote every hand-typed amount with the uniform seed size.
        levelsIn.addTextChangedListener(onChange(this::countGen));
        TextWatcher seedW = onChange(this::seedGen);
        askSizeIn.addTextChangedListener(seedW);
        bidSizeIn.addTextChangedListener(seedW);
        TextWatcher priceW = onChange(this::priceGen);
        midIn.addTextChangedListener(priceW);
        stepIn.addTextChangedListener(priceW);
        skewIn.addTextChangedListener(priceW);

        pegModeUi();
        pegSw.setOnCheckedChangeListener((btn, on) -> {
            // COMMIT FIRST. Unpegging promotes the rows on screen to the authoritative prices,
            // but the peg fills them by setText only — nothing commits until a field blurs, and
            // a switch blurs nothing. Without this the engine reads whatever was committed last
            // (stale prices, or zeros from a machine-filled ladder) and either reprices live
            // orders to numbers the user never saw or silently abandons the ladder.
            commit();
            cfg.pegged = on;
            cfg.save();
            pegModeUi();
            if (on) {
                MarketPrice.refreshAsync();
                boolean haveSizes = anyRowSized();
                if (MarketPrice.fresh()) {
                    // sized rows are the user's — pegging on refreshes PRICES only;
                    // an empty ladder seeds fresh from the auto-fill fields
                    if (haveSizes) refreshPrices(BigDecimal.valueOf(MarketPrice.mid()));
                    else seedGen();
                } else {
                    // no price yet: an empty ladder wants seeding once one lands; a ladder that
                    // already has sizes only wants its prices refreshed, never a reseed
                    deferUntilPrice(haveSizes ? () -> refreshPrices(livePeggedMid())
                                              : () -> seedFill(livePeggedMid()));
                    act.toast("Fetching MEXC price…");
                }
            }
        });

        // ---- publish / withdraw: ONE button, honest words (0.2.6 had "DISARM (cancels the
        // ladder)" AND "Withdraw ladder now" — the same action twice under different names) ----
        armBtn = tv("PUBLISH LADDER", 14f, Design.ON_ACCENT(), Design.sansBold());
        armBtn.setGravity(Gravity.CENTER);
        armBtn.setPadding(0, dp(13), 0, dp(13));
        armBtn.setOnClickListener(v -> togglePublish());
        Design.pressable(armBtn);
        LayoutParams al = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        al.bottomMargin = dp(8);
        addView(armBtn, al);

        // ---- apply edits in place: the engine already reprices a rung with a single re-lock
        // that never moves the funds, so tearing the whole ladder down to change a price was
        // never necessary — there was just no way to ask for it ----
        applyBtn = tv("APPLY EDITS TO THE LIVE LADDER", 12f, Design.ACCENT(), Design.sansBold());
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setPadding(0, dp(11), 0, dp(11));
        applyBtn.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        applyBtn.setOnClickListener(v -> applyEdits());
        Design.pressable(applyBtn);
        LayoutParams ap = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ap.bottomMargin = dp(8);
        addView(applyBtn, ap);
    }

    /** Commit the fields and ask the engine to reconcile the live ladder to them. */
    private void applyEdits() {
        commit();
        if (cfg.pegged) {
            double m = MarketPrice.mid();
            if (!(m > 0) || !MarketPrice.fresh()) {
                MarketPrice.refreshAsync();
                act.toast("Waiting for the MEXC price — try again in a few seconds");
                return;
            }
        }
        act.applyMakerEdits(cfg.pegged ? BigDecimal.valueOf(MarketPrice.mid()) : null);
    }

    // ---------------------------------------------------------------- auto-fill (AtomiX)

    /** While pegged the engine owns the PRICES — the mid and the rung price cells grey out.
     *  Every rung's AMOUNT stays editable at all times (AtomiX parity, and the whole point
     *  of per-rung sizes): the engine reads sizes from these rows and keeps them across
     *  reprices. */
    private void pegModeUi() {
        boolean on = pegSw.isChecked();
        midIn.setEnabled(!on);
        midIn.setAlpha(on ? 0.5f : 1f);
        for (EditText[][] side : new EditText[][][]{askRows, bidRows}) {
            for (EditText[] row : side) {
                if (row == null) continue;
                row[0].setEnabled(!on);          // price — engine-owned while pegged
                row[0].setAlpha(on ? 0.5f : 1f);
            }
        }
    }

    private BigDecimal liveOrManualMid() {
        if (pegSw.isChecked()) {
            double m = MarketPrice.mid();
            return (m > 0 && MarketPrice.fresh()) ? BigDecimal.valueOf(m) : null;
        }
        BigDecimal m = Util.dec(midIn.getText().toString());
        return m.signum() > 0 ? m : null;
    }

    private boolean anyRowSized() {
        for (EditText[] row : askRows) if (Util.dec(row[1].getText().toString()).signum() > 0) return true;
        for (EditText[] row : bidRows) if (Util.dec(row[1].getText().toString()).signum() > 0) return true;
        return false;
    }

    /** A per-side SIZE edit — that field means "every rung this side", so rewrite them. */
    private void seedGen() {
        if (filling) return;
        BigDecimal mid = liveOrManualMid();
        if (mid == null) { deferUntilPrice(() -> seedFill(livePeggedMid())); return; }
        seedFill(mid);
    }

    /** A LEVEL COUNT edit — add or remove rungs, never touch an existing rung's amount. */
    private void countGen() {
        if (filling) return;
        BigDecimal mid = liveOrManualMid();
        if (mid == null) { deferUntilPrice(() -> applyCount(livePeggedMid())); return; }
        applyCount(mid);
    }

    private static BigDecimal livePeggedMid() {
        double m = MarketPrice.mid();
        return m > 0 ? BigDecimal.valueOf(m) : null;
    }

    /**
     * The edit can't be applied until a usable price arrives — SAY SO and remember what to do.
     *
     * Returning quietly is what made the levels field look broken: the fetch limiter allows one
     * attempt every 30s and a book too thin to quote fails outright, so the edit could sit dead
     * for half a minute or more with nothing on screen explaining it.
     */
    private void deferUntilPrice(Runnable apply) {
        if (!pegSw.isChecked()) return;               // unpegged just needs a mid typed in
        pendingApply = apply;
        pendingEdit = "waiting for a MEXC price to apply that…";
        MarketPrice.refreshAsync();
        pegPxTv.setText(pendingEdit);
    }

    /**
     * Resize the ladder to the level count, keeping every amount the user typed.
     * {@link MakerLadder#applyCount} decides the amounts; this only writes the cells.
     */
    private void applyCount(BigDecimal mid) {
        BigDecimal step = Util.dec(stepIn.getText().toString());
        BigDecimal skew = clampSkew(Util.dec(skewIn.getText().toString()));
        if (mid == null || mid.signum() <= 0 || step.signum() <= 0) return;
        int n = levelsClamped();
        List<BigDecimal> askAmts = MakerLadder.applyCount(readSide(askRows), n,
                Util.dec(askSizeIn.getText().toString()));
        List<BigDecimal> bidAmts = MakerLadder.applyCount(readSide(bidRows), n,
                Util.dec(bidSizeIn.getText().toString()));

        BigDecimal hundred = new BigDecimal(100);
        BigDecimal quoted = mid.multiply(BigDecimal.ONE.add(skew.divide(hundred, PriceMath.MC)), PriceMath.MC);
        pendingEdit = null;
        filling = true;
        try {
            if (pegSw.isChecked()) midIn.setText(trim(quoted.setScale(PriceMath.DISPLAY_DP,
                    java.math.RoundingMode.HALF_UP)));
            for (int i = 0; i < MakerLadder.MAX_LEVELS; i++) {
                BigDecimal off = step.multiply(new BigDecimal(i + 1), PriceMath.MC).divide(hundred, PriceMath.MC);
                writeRung(askRows[i], askAmts.get(i),
                        quoted.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                                .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.UP));
                writeRung(bidRows[i], bidAmts.get(i),
                        quoted.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                                .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.DOWN));
            }
        } finally {
            filling = false;
        }
        updatePreview();
    }

    private void writeRung(EditText[] row, BigDecimal amount, BigDecimal price) {
        boolean on = amount != null && amount.signum() > 0;
        row[0].setText(on ? trim(price) : "");
        row[1].setText(on ? trim(amount) : "");
    }

    /** The rung rows as a Level list, so the pure helper can reason about them. */
    private List<MakerLadder.Level> readSide(EditText[][] rows) {
        List<MakerLadder.Level> out = new ArrayList<>();
        for (EditText[] row : rows) {
            out.add(new MakerLadder.Level(Util.dec(row[0].getText().toString()),
                    Util.dec(row[1].getText().toString())));
        }
        return out;
    }

    /** A mid/step/skew edit — reprice the rows, never touch a rung's size. */
    private void priceGen() {
        if (filling) return;
        BigDecimal mid = liveOrManualMid();
        if (mid == null) {
            if (pegSw.isChecked()) MarketPrice.refreshAsync();
            return;
        }
        refreshPrices(mid);
    }

    /**
     * Seed the 6+6 rows around {@code mid} — skew shifts the quoted mid, rung i sits at
     * ±(i+1)·step %, each side at its seed size, and a side whose seed size is blank/0 is
     * blanked entirely (one-sided market). Silent: this fires per keystroke, so
     * invalid/partial params just pause row updates rather than toasting per character.
     */
    private void seedFill(BigDecimal mid) {
        BigDecimal step = Util.dec(stepIn.getText().toString());
        BigDecimal askSize = Util.dec(askSizeIn.getText().toString());
        BigDecimal bidSize = Util.dec(bidSizeIn.getText().toString());
        BigDecimal skew = clampSkew(Util.dec(skewIn.getText().toString()));
        if (mid == null || mid.signum() <= 0) return;
        BigDecimal hundred = new BigDecimal(100);
        BigDecimal quoted = mid.multiply(BigDecimal.ONE.add(skew.divide(hundred, PriceMath.MC)), PriceMath.MC);
        pendingEdit = null;
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

    /** Rewrite ONLY the price cells of sized rows — the per-rung amounts are the user's. */
    private void refreshPrices(BigDecimal mid) {
        BigDecimal step = Util.dec(stepIn.getText().toString());
        BigDecimal skew = clampSkew(Util.dec(skewIn.getText().toString()));
        if (mid == null || mid.signum() <= 0 || step.signum() <= 0) return;
        BigDecimal hundred = new BigDecimal(100);
        BigDecimal quoted = mid.multiply(BigDecimal.ONE.add(skew.divide(hundred, PriceMath.MC)), PriceMath.MC);
        filling = true;
        try {
            if (pegSw.isChecked()) midIn.setText(trim(quoted.setScale(PriceMath.DISPLAY_DP,
                    java.math.RoundingMode.HALF_UP)));
            for (int i = 0; i < MakerLadder.MAX_LEVELS; i++) {
                BigDecimal off = step.multiply(new BigDecimal(i + 1), PriceMath.MC).divide(hundred, PriceMath.MC);
                if (Util.dec(askRows[i][1].getText().toString()).signum() > 0) {
                    askRows[i][0].setText(trim(quoted.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                            .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.UP)));
                }
                if (Util.dec(bidRows[i][1].getText().toString()).signum() > 0) {
                    bidRows[i][0].setText(trim(quoted.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                            .setScale(PriceMath.DISPLAY_DP, java.math.RoundingMode.DOWN)));
                }
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
        cfg.save();   // POSITIONAL — rows persist as typed; position is a rung's identity now
    }

    private void togglePublish() {
        commit();
        if (cfg.armed) {
            act.withdrawLadder();
            return;
        }
        java.util.List<MakerLadder.Slot> desired;
        if (cfg.pegged) {
            if (cfg.stepPct.signum() <= 0) {
                act.toast("Peg needs a step %");
                return;
            }
            if (!MakerLadder.hasSizedRung(cfg.asks) && !MakerLadder.hasSizedRung(cfg.bids)) {
                act.toast("Set a MINIMA amount on at least one rung");
                return;
            }
            double m = MarketPrice.mid();
            if (!(m > 0) || !MarketPrice.fresh()) {
                MarketPrice.refreshAsync();
                act.toast("Waiting for the MEXC price — try again in a few seconds");
                return;
            }
            desired = MakerLadder.desired(BigDecimal.valueOf(m), cfg.toLadderConfig(), BigDecimal.ONE);
        } else {
            desired = MakerLadder.desired(null, cfg.toLadderConfig(), BigDecimal.ONE);
            if (desired.isEmpty()) {
                act.toast("No levels set — add a bid or ask rung first");
                return;
            }
            if (MakerLadder.crossed(cfg.asks, cfg.bids)) {
                act.toast("⚠ Your best bid ≥ best ask (crossed market)");
            }
        }
        act.publishMaker(desired, cfg.pegged);
    }

    // ---------------------------------------------------------------- render / peg tick

    /** Poll the peg price + status lines while visible (AtomiX polls its dialog every 2s). */
    private final Runnable pegTick = new Runnable() {
        @Override public void run() {
            // Stops itself while hidden — setVisibility(VISIBLE) restarts it.
            if (!isAttachedToWindow() || getVisibility() != VISIBLE) return;
            pegPxTv.setText(pendingEdit != null ? pendingEdit : pegLine());
            updateStatus();
            if (pegSw.isChecked()) {
                MarketPrice.refreshAsync();
                if (MarketPrice.fresh()) {
                    if (pendingApply != null) {
                        // run the operation the user actually asked for, not a generic refill
                        Runnable r = pendingApply;
                        pendingApply = null;
                        r.run();
                    } else {
                        refreshPrices(BigDecimal.valueOf(MarketPrice.mid()));   // sizes untouched
                    }
                }
            }
            postDelayed(this, 2000);
        }
    };

    /** The per-rung on-chain story + the engine's latest message, both refreshed cheaply. */
    private void updateStatus() {
        stageTv.setText(act.stage());
        if (!cfg.armed) {
            // Not published: nothing is being reconciled, so don't narrate rungs as though
            // something were acting on them. Records may survive an interrupted withdraw.
            int n = cfg.slots.size(), t = cfg.cancelTombstones.size();
            slotsTv.setText(n == 0 && t == 0 ? "nothing on-chain"
                    : "not published — " + n + " rung" + (n == 1 ? "" : "s") + " still recorded"
                    + (t > 0 ? ", " + t + " awaiting cancellation" : ""));
            return;
        }
        java.util.List<MakerStatus.Line> lines = MakerStatus.lines(desiredNow(), cfg.slots,
                cfg.cancelTombstones, myOrdersById(), act.chainBlock());
        if (lines.isEmpty()) {
            slotsTv.setText("waiting for the first cycle…");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (MakerStatus.Line l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.tone == MakerStatus.OK ? "✓ " : l.tone == MakerStatus.FAIL ? "✗ " : "⏳ ");
            if (!l.slotId.isEmpty()) sb.append(l.slotId).append("  ");
            sb.append(l.text);
        }
        slotsTv.setText(sb.toString());
    }

    /** My orders keyed by orderId, rebuilt only when the book actually changes — the status
     *  panel refreshes every 2s and the book is the same object between scans. */
    private java.util.Map<String, Order5> myOrdersById() {
        java.util.Map<String, Order5> book = act.book();
        if (book == bookSeen && myById != null) return myById;
        java.util.Map<String, Order5> mine = new java.util.HashMap<>();
        for (Order5 o : book.values()) if (o.isMine(act.keys(), act.addrs())) mine.put(o.orderId, o);
        bookSeen = book;
        myById = mine;
        return mine;
    }

    private java.util.List<MakerLadder.Slot> desiredNow() {
        if (cfg.pegged) {
            double m = MarketPrice.mid();
            if (!(m > 0)) return java.util.Collections.emptyList();
            return MakerLadder.desired(BigDecimal.valueOf(m), cfg.toLadderConfig(), BigDecimal.ONE);
        }
        return MakerLadder.desired(null, cfg.toLadderConfig(), BigDecimal.ONE);
    }

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
        stateTv.setText(armed ? (cfg.pegged && MarketPrice.mustWithdraw()
                        ? "WITHDRAWN — STALE FEED" : "PUBLISHED")
                              : "NOT PUBLISHED");
        stateTv.setTextColor(armed ? (cfg.pegged && MarketPrice.mustWithdraw() ? Design.RED() : Design.IN())
                                   : Design.DIM());
        // only meaningful against a live ladder
        applyBtn.setVisibility(armed ? VISIBLE : GONE);
        prepBtn.setVisibility(armed ? GONE : VISIBLE);
        armBtn.setText(armed ? "WITHDRAW LADDER (cancels all rungs)" : "PUBLISH LADDER");
        armBtn.setBackground(Design.ripple(Design.roundBg(getContext(),
                armed ? Design.RED() : Design.IN(), 12)));
        updateStatus();

        MarketPrice.refreshAsync();
    }
}

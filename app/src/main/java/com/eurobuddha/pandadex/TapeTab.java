package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;

/** TRADES tab: defaults to this wallet's confirmed fills; market tape is separate. */
@SuppressLint("ViewConstructor")
public final class TapeTab extends LinearLayout {

    private static final int CAP = 120;

    private final MainActivity act;
    private final LinearLayout seg;
    private final LinearLayout rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
    private int sel = 0;

    public TapeTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        seg = new LinearLayout(act);
        String[] names = {"MY TRADES", "MARKET TAPE"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = tv(names[i], 11f, Design.DIM(), Design.sansBold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 8));
            t.setOnClickListener(v -> { sel = idx; restyle(); render(); });
            seg.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(seg);

        rows = new LinearLayout(act);
        rows.setOrientation(VERTICAL);
        rows.setPadding(0, Design.dp(act, 10), 0, 0);
        addView(rows);
        restyle();
    }

    private void restyle() {
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            t.setTextColor(i == sel ? Design.ON_ACCENT() : Design.DIM());
            t.setBackground(i == sel ? Design.roundBg(getContext(), Design.ACCENT(), 10)
                    : Design.roundBg(getContext(), Design.SURFACE2(), 10));
        }
    }

    private TextView tv(String s, float size, int color, android.graphics.Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private TextView mono(String s, int color, float size) {
        return tv(s, size, color, Design.mono());
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 10));
        int p = Design.dp(getContext(), 10);
        c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Design.dp(getContext(), 9);
        rows.addView(c, lp);
        return c;
    }

    public void render() {
        rows.removeAllViews();
        if (sel == 0) renderMine(); else renderMarket();
    }

    private void renderMine() {
        List<TradeExport.TradeRow> mine = act.db().raw().myTradeRows(CAP);
        LinearLayout summary = card();
        summary.addView(tv("MY TRADES", 10f, Design.DIM(), Design.sansBold()));
        if (mine.isEmpty()) {
            summary.addView(tv("No confirmed personal trades yet.", 11f, Design.DIM2(), Design.sans()));
        } else {
            Totals totals = totals(mine);
            summary.addView(mono("Rows " + mine.size()
                    + "   Net " + PriceMath.fmt(totals.netM) + " MINIMA"
                    + "   " + PriceMath.fmt(totals.netU) + " mxUSDT", Design.TEXT(), 10.5f));
            summary.addView(tv("Only wallet-owned confirmed fills are shown here. Market tape rows are excluded.",
                    9.5f, Design.DIM2(), Design.sans()));
        }

        TextView export = mono("EXPORT + RECONCILE", Design.ACCENT(), 10.5f);
        export.setGravity(Gravity.CENTER);
        export.setPadding(0, Design.dp(getContext(), 9), 0, Design.dp(getContext(), 9));
        export.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        export.setOnClickListener(v -> act.exportTradeReconciliation());
        Design.pressable(export);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        el.topMargin = Design.dp(getContext(), 8);
        summary.addView(export, el);

        for (TradeExport.TradeRow r : mine) {
            LinearLayout c = card();
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(mono((r.buy ? "BUY " : "SELL ") + PriceMath.fmt(r.sizeMinima),
                    r.buy ? Design.IN() : Design.RED(), 11f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView price = mono(PriceMath.fmtPrice(r.price), Design.TEXT(), 11f);
            price.setGravity(Gravity.END);
            top.addView(price);
            c.addView(top);
            c.addView(mono(fmt.format(new Date(r.timeMs)) + "   "
                    + (r.maker ? "MAKER" : "TAKER") + "   "
                    + label(r.sourceKind) + "   " + label(r.verificationStatus),
                    Design.DIM(), 9.5f));
            String evidence = !r.txpowid.isEmpty() ? r.txpowid : r.spentCoin;
            if (!evidence.isEmpty()) c.addView(mono(evidence, Design.DIM2(), 8.5f));
        }
    }

    private void renderMarket() {
        LinearLayout head = card();
        head.addView(tv("MARKET TAPE", 10f, Design.DIM(), Design.sansBold()));
        head.addView(tv("Network-observed order-book fills only. Ambiguous full disappearances are not inserted as normal trades.",
                9.5f, Design.DIM2(), Design.sans()));

        List<Object[]> tape = act.db().raw().tapeRows(CAP);
        if (tape.isEmpty()) {
            rows.addView(tv("No verified market trades observed yet.", 11f, Design.DIM2(), Design.sans()));
            return;
        }
        for (Object[] r : tape) {
            long t = (Long) r[0];
            BigDecimal price = (BigDecimal) r[1];
            BigDecimal size = (BigDecimal) r[2];
            boolean buy = (Boolean) r[3];
            boolean mine = (Boolean) r[4];

            LinearLayout row = new LinearLayout(getContext());
            row.setPadding(0, Design.dp(getContext(), 4), 0, Design.dp(getContext(), 4));
            row.addView(mono(fmt.format(new Date(t)) + (mine ? "  YOU" : ""),
                    mine ? Design.ACCENT() : Design.DIM2(), 9.5f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.15f));
            TextView p = mono(PriceMath.fmtPrice(price), buy ? Design.IN() : Design.RED(), 10.5f);
            p.setGravity(Gravity.END);
            row.addView(p, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView s = mono(PriceMath.fmt(size), Design.TEXT(), 10.5f);
            s.setGravity(Gravity.END);
            row.addView(s, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            rows.addView(row);
        }
    }

    private static String label(String s) {
        return s == null || s.isEmpty() ? "LOCAL_VERIFIED" : s;
    }

    private static final class Totals {
        BigDecimal netM = BigDecimal.ZERO;
        BigDecimal netU = BigDecimal.ZERO;
    }

    private static Totals totals(List<TradeExport.TradeRow> rows) {
        Totals t = new Totals();
        for (TradeExport.TradeRow r : rows) {
            BigDecimal notional = TradeExport.usdtNotional(r);
            t.netM = t.netM.add(r.buy ? r.sizeMinima : r.sizeMinima.negate());
            t.netU = t.netU.add(r.buy ? notional.negate() : notional);
        }
        return t;
    }
}

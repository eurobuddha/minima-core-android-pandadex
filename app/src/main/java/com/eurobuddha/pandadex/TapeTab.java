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
        String[] names = {"MY TRADES", "MARKET TAPE", "CORRECTIONS"};
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
        if (sel == 0) renderMine(); else if (sel == 1) renderMarket(); else renderCorrections();
    }

    private void renderMine() {
        List<TradeExport.TradeRow> mine = act.db().raw().myTradeRows(CAP);
        LinearLayout summary = card();
        summary.addView(tv("MY TRADES", 10f, Design.DIM(), Design.sansBold()));
        if (mine.isEmpty()) {
            summary.addView(tv("No personal trade records yet.", 11f, Design.DIM2(), Design.sans()));
        } else {
            Totals totals = totals(mine);
            summary.addView(mono("Rows " + mine.size()
                    + "   Net " + PriceMath.fmt(totals.netM) + " MINIMA"
                    + "   " + PriceMath.fmt(totals.netU) + " mxUSDT", Design.TEXT(), 10.5f));
            summary.addView(tv("Rows awaiting recheck stay visible and are excluded from net totals.", 9.5f, Design.DIM2(), Design.sans()));
            summary.addView(tv("Wallet trade records; older rows may lack chain proof. Times use the inclusion block when verified; otherwise they show this device’s observation.",
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
            c.addView(mono(TradeExport.timeLabel(r) + " " + fmt.format(new Date(r.timeMs)) + "   "
                    + (r.maker ? "MAKER" : "TAKER") + "   "
                    + label(r.sourceKind) + "   " + label(r.verificationStatus),
                    Design.DIM(), 9.5f));
            String evidence = !r.txpowid.isEmpty() ? r.txpowid : r.spentCoin;
            if (!evidence.isEmpty()) c.addView(mono(evidence, Design.DIM2(), 8.5f));
            if (!r.verificationNote.isEmpty()) c.addView(tv(r.verificationNote, 9.5f, Design.DIM2(), Design.sans()));
        }
    }

    private void renderMarket() {
        LinearLayout head = card();
        head.addView(tv("MARKET TAPE", 10f, Design.DIM(), Design.sansBold()));
        head.addView(tv("Device-observed order-book records. New fills require a linked on-chain spend; older rows have not all been rechecked.",
                9.5f, Design.DIM2(), Design.sans()));

        List<Object[]> tape = act.db().raw().tapeRows(CAP);
        if (tape.isEmpty()) {
            rows.addView(tv("No market trade records yet.", 11f, Design.DIM2(), Design.sans()));
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
            if (r.length > 5) rows.addView(tv((String) r[5], 9f, Design.DIM2(), Design.sans()));
        }
    }

    private void renderCorrections() {
        LinearLayout head=card();
        head.addView(tv("RECEIPT CORRECTIONS",10f,Design.DIM(),Design.sansBold()));
        head.addView(tv("Earlier market and personal records are preserved here. Personal exports include the full correction evidence.",9.5f,Design.DIM2(),Design.sans()));
        try {
            org.json.JSONArray corrections=new org.json.JSONArray(act.db().raw().correctionArchive(CAP));
            if(corrections.length()==0) head.addView(tv("No receipt corrections recorded.",11f,Design.DIM2(),Design.sans()));
            for(int i=0;i<corrections.length();i++) {
                org.json.JSONObject correction=corrections.getJSONObject(i);
                LinearLayout c=card();
                c.addView(tv(correction.getString("reason"),11f,Design.TEXT(),Design.sansBold()));
                c.addView(mono("Corrected "+fmt.format(new Date(correction.getLong("corrected_at"))),Design.DIM(),9.5f));
                org.json.JSONObject evidence=correction.getJSONObject("evidence");
                org.json.JSONObject original=evidence.optJSONObject("personal_trade");
                if(original==null) original=evidence.optJSONObject("public_trade");
                if(original!=null) c.addView(tv("Earlier record: "+original.optString("size","unknown")+" MINIMA @ "+original.optString("price","unknown")
                        +" · "+(original.optLong("timems",0)>0?fmt.format(new Date(original.optLong("timems"))):"timestamp not recorded"),10f,Design.DIM(),Design.sans()));
                TextView ids=mono("Source "+correction.getString("coinid")+"\nEarlier TxPoW "+correction.getString("old_txpowid")
                        +"\nVerified replacement "+correction.getString("new_txpowid"),Design.DIM2(),8.5f);
                ids.setTextIsSelectable(true);c.addView(ids);
            }
        } catch(Exception invalid) {
            head.addView(tv("Correction history could not be read. Keep app data for recovery.",11f,Design.ACCENT(),Design.sans()));
        }
    }

    private static String label(String s) {
        if (s == null || s.isEmpty() || "LOCAL_VERIFIED".equals(s)) return "Legacy — not rechecked";
        if (s.startsWith("SUPERSEDED_")) return "Corrected earlier record — excluded from totals; see Corrections";
        if (!ChainReview.accounted(s)) return "Recheck required — excluded from totals";
        return "CHAIN_VERIFIED".equals(s) ? "On-chain when checked" : s;
    }

    private static final class Totals {
        BigDecimal netM = BigDecimal.ZERO;
        BigDecimal netU = BigDecimal.ZERO;
    }

    private static Totals totals(List<TradeExport.TradeRow> rows) {
        Totals t = new Totals();
        for (TradeExport.TradeRow r : rows) {
            if (!ChainReview.accounted(r.verificationStatus)) continue;
            BigDecimal notional = TradeExport.usdtNotional(r);
            t.netM = t.netM.add(r.buy ? r.sizeMinima : r.sizeMinima.negate());
            t.netU = t.netU.add(r.buy ? notional.negate() : notional);
        }
        return t;
    }
}

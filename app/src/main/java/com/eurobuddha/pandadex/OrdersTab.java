package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** ORDERS tab: Open (manage) / My trades (fills + P&L vs book mid). */
@SuppressLint("ViewConstructor")
public final class OrdersTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout seg, body;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
    private int sel = 0;

    public OrdersTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        seg = new LinearLayout(act);
        String[] names = {"OPEN", "MY TRADES"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(act);
            t.setText(names[i]);
            t.setTextSize(11f);
            t.setTypeface(Design.sansBold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 8));
            t.setOnClickListener(v -> { sel = idx; restyle(); render(); });
            seg.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(seg);

        body = new LinearLayout(act);
        body.setOrientation(VERTICAL);
        body.setPadding(0, Design.dp(act, 10), 0, 0);
        addView(body);
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

    private TextView line(String s, int color, float size) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Design.mono());
        t.setTextSize(size);
        return t;
    }

    public void render() {
        body.removeAllViews();
        if (sel == 0) renderOpen(); else renderTrades();
    }

    private void renderOpen() {
        Map<String, Order5> book = act.book();
        long block = act.chainBlock();
        boolean any = false;
        for (Order5 o : book.values()) {
            if (!o.isMine(act.keys())) continue;
            any = true;
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(VERTICAL);
            card.setBackground(Design.card(getContext(), 10));
            int p = Design.dp(getContext(), 10);
            card.setPadding(p, p, p, p);

            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(line((o.sell ? "SELL " : "BUY ") + PriceMath.fmt(o.minimaAmount())
                            + " MINIMA @ " + PriceMath.fmtPrice(o.price()), o.sell ? Design.RED() : Design.IN(), 12f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView edit = line(" ✎ ", Design.DIM(), 14f);
            edit.setOnClickListener(v -> act.editOrder(o));
            top.addView(edit);
            TextView cancel = line(" ✕ ", Design.RED(), 14f);
            cancel.setOnClickListener(v -> act.cancelOrder(o));
            top.addView(cancel);
            card.addView(top);

            String meta = (o.gtc ? "GTC ∞" : "expires") + "  ·  age " + o.age(block) + " blk"
                    + "  ·  min remainder " + PriceMath.fmt(o.minRem)
                    + "  ·  total " + PriceMath.fmt(o.usdtAmount()) + " mxUSDT";
            card.addView(line(meta, Design.DIM2(), 9.5f));
            if (o.expired(block)) card.addView(line("EXPIRED — refundable to your wallet", Design.ACCENT(), 9.5f));

            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Design.dp(getContext(), 8);
            body.addView(card, lp);
        }
        if (!any) body.addView(line("No open orders", Design.DIM2(), 11f));
    }

    private void renderTrades() {
        List<Object[]> trades = act.db().raw().myTrades(200);
        if (trades.isEmpty()) {
            body.addView(line("No fills yet", Design.DIM2(), 11f));
            return;
        }
        BigDecimal mid = act.bookMid();
        BigDecimal vol = BigDecimal.ZERO, notional = BigDecimal.ZERO, pnl = BigDecimal.ZERO;
        for (Object[] t : trades) {
            BigDecimal price = (BigDecimal) t[1];
            BigDecimal size = (BigDecimal) t[2];
            boolean buy = (Boolean) t[3];
            vol = vol.add(size);
            notional = notional.add(size.multiply(price, PriceMath.MC));
            if (mid != null && mid.signum() > 0) {
                BigDecimal diff = buy ? mid.subtract(price) : price.subtract(mid);
                pnl = pnl.add(diff.multiply(size, PriceMath.MC));
            }
        }
        LinearLayout summary = new LinearLayout(getContext());
        summary.setOrientation(VERTICAL);
        summary.setBackground(Design.card(getContext(), 10));
        int p = Design.dp(getContext(), 10);
        summary.setPadding(p, p, p, p);
        summary.addView(line("Fills " + trades.size() + "   ·   Volume " + PriceMath.fmt(vol) + " MINIMA",
                Design.TEXT(), 11f));
        summary.addView(line("Notional " + PriceMath.fmt(notional.setScale(6, java.math.RoundingMode.HALF_UP))
                + " mxUSDT", Design.DIM(), 10f));
        if (mid != null && mid.signum() > 0) {
            summary.addView(line("P&L vs book mid " + (pnl.signum() >= 0 ? "+" : "")
                            + PriceMath.fmt(pnl.setScale(6, java.math.RoundingMode.HALF_UP)) + " mxUSDT",
                    pnl.signum() >= 0 ? Design.IN() : Design.RED(), 10f));
        }
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sl.bottomMargin = Design.dp(getContext(), 10);
        body.addView(summary, sl);

        for (Object[] t : trades) {
            long time = (Long) t[0];
            BigDecimal price = (BigDecimal) t[1];
            BigDecimal size = (BigDecimal) t[2];
            boolean buy = (Boolean) t[3];
            boolean maker = (Boolean) t[4];
            LinearLayout row = new LinearLayout(getContext());
            row.setPadding(0, Design.dp(getContext(), 4), 0, Design.dp(getContext(), 4));
            row.addView(line(fmt.format(new Date(time)), Design.DIM2(), 9.5f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.1f));
            TextView side = line((buy ? "BUY " : "SELL ") + PriceMath.fmt(size),
                    buy ? Design.IN() : Design.RED(), 10.5f);
            row.addView(side, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
            TextView pr = line(PriceMath.fmtPrice(price) + (maker ? "  M" : "  T"), Design.TEXT(), 10.5f);
            pr.setGravity(Gravity.END);
            row.addView(pr, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
            body.addView(row);
        }
    }
}

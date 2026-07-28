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

/** TRADES tab: the live market tape — every fill this node observed on-chain. */
@SuppressLint("ViewConstructor")
public final class TapeTab extends LinearLayout {

    private static final int CAP = 120;

    private final MainActivity act;
    private final LinearLayout rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public TapeTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        TextView title = new TextView(act);
        title.setText("MARKET TRADES");
        title.setTextColor(Design.DIM());
        title.setTypeface(Design.sansBold());
        title.setTextSize(10f);
        addView(title);

        LinearLayout legend = new LinearLayout(act);
        legend.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 4));
        legend.addView(col("Time", 0.9f, Gravity.START));
        legend.addView(col("Price (mxUSDT)", 1.2f, Gravity.END));
        legend.addView(col("Amount (MINIMA)", 1.2f, Gravity.END));
        addView(legend);

        rows = new LinearLayout(act);
        rows.setOrientation(VERTICAL);
        addView(rows);
    }

    private TextView col(String s, float weight, int gravity) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Design.DIM2());
        t.setTypeface(Design.sans());
        t.setTextSize(9f);
        t.setGravity(gravity);
        t.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, weight));
        return t;
    }

    public void render() {
        rows.removeAllViews();
        List<Object[]> tape = act.db().raw().tapeRows(CAP);
        if (tape.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No trades observed yet.\n\nYour node watches the on-chain order book "
                    + "directly — fills appear here within a block of happening anywhere on the network.");
            empty.setTextColor(Design.DIM2());
            empty.setTypeface(Design.sans());
            empty.setTextSize(11f);
            rows.addView(empty);
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

            TextView time = new TextView(getContext());
            time.setText(fmt.format(new Date(t)) + (mine ? "  YOU" : ""));
            time.setTextColor(mine ? Design.ACCENT() : Design.DIM2());
            time.setTypeface(Design.mono());
            time.setTextSize(10f);
            row.addView(time, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f));

            TextView p = new TextView(getContext());
            p.setText(PriceMath.fmtPrice(price));
            p.setTextColor(buy ? Design.IN() : Design.RED());
            p.setTypeface(Design.mono());
            p.setTextSize(11f);
            p.setGravity(Gravity.END);
            row.addView(p, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));

            TextView s = new TextView(getContext());
            s.setText(PriceMath.fmt(size));
            s.setTextColor(Design.TEXT());
            s.setTypeface(Design.mono());
            s.setTextSize(11f);
            s.setGravity(Gravity.END);
            row.addView(s, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));

            rows.addView(row);
        }
    }
}

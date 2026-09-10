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

/** CHART tab: interval pills + candles + a crosshair OHLCV readout. */
@SuppressLint("ViewConstructor")
public final class ChartTab extends LinearLayout {

    private static final long[] INTERVALS = {Candles.M15, Candles.H1, Candles.H4, Candles.D1};
    private static final String[] LABELS = {"15m", "1H", "4H", "1D"};

    private final MainActivity act;
    private final CandleView chart;
    private final TextView readout;
    private final LinearLayout pills;
    private int sel = 1;

    public ChartTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("MINIMA / mxUSDT");
        title.setTextColor(Design.TEXT());
        title.setTypeface(Design.sansBold());
        title.setTextSize(13f);
        head.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        pills = new LinearLayout(act);
        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            TextView t = new TextView(act);
            t.setText(LABELS[i]);
            t.setTextSize(10f);
            t.setTypeface(Design.mono());
            t.setPadding(Design.dp(act, 8), Design.dp(act, 4), Design.dp(act, 8), Design.dp(act, 4));
            t.setOnClickListener(v -> { sel = idx; restyle(); render(); });
            pills.addView(t);
        }
        head.addView(pills);
        addView(head);

        readout = new TextView(act);
        readout.setTextColor(Design.DIM());
        readout.setTypeface(Design.mono());
        readout.setTextSize(10f);
        readout.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 6));
        addView(readout);

        chart = new CandleView(act);
        chart.setOnSelect(this::showReadout);
        addView(chart, new LayoutParams(LayoutParams.MATCH_PARENT, Design.dp(act, 300)));

        TextView note = new TextView(act);
        note.setText("Charts use this device's saved trade records. Recovery may add trades from history retained by your node; "
                + "older or pruned transactions may be missing. Export records for their time basis and chain evidence.");
        note.setTextColor(Design.DIM2());
        note.setTypeface(Design.sans());
        note.setTextSize(10f);
        note.setPadding(0, Design.dp(act, 10), 0, 0);
        addView(note);

        restyle();
    }

    private void restyle() {
        for (int i = 0; i < pills.getChildCount(); i++) {
            TextView t = (TextView) pills.getChildAt(i);
            t.setTextColor(i == sel ? Design.ON_ACCENT() : Design.DIM2());
            t.setBackground(i == sel ? Design.roundBg(getContext(), Design.ACCENT(), 8) : null);
        }
    }

    private void showReadout(Candles.Candle c) {
        if (c == null) { readout.setText(""); return; }
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
        readout.setText(f.format(new Date(c.openMs))
                + "   O " + PriceMath.fmt(c.open) + "   H " + PriceMath.fmt(c.high)
                + "   L " + PriceMath.fmt(c.low) + "   C " + PriceMath.fmt(c.close)
                + "   V " + PriceMath.fmt(c.volume));
    }

    public void render() {
        long interval = INTERVALS[sel];
        long window = interval * 120;              // ~120 candles of history
        long since = System.currentTimeMillis() - window;
        List<Candles.Fill> fills = act.db().raw().fills(since);
        List<Candles.Candle> cs = Candles.aggregate(fills, interval);
        if (cs.size() > 120) cs = cs.subList(cs.size() - 120, cs.size());
        chart.setData(cs, interval);
        if (!cs.isEmpty() && chart.selected() == null) showReadout(cs.get(cs.size() - 1));
    }
}

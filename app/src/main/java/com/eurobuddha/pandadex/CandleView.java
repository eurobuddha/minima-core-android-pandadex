package com.eurobuddha.pandadex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled OHLC candle chart with a volume subchart and a touch crosshair. No charting
 * library (the app family keeps builds self-contained); no external data — every candle is
 * bucketed from fills THIS device observed on-chain.
 */
public final class CandleView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<Candles.Candle> candles = java.util.Collections.emptyList();
    private long intervalMs = Candles.H1;
    private int crosshair = -1;

    public CandleView(Context c) {
        super(c);
        text.setTypeface(Design.mono());
        text.setTextSize(Design.dp(c, 9));
        setClickable(true);
    }

    public void setData(List<Candles.Candle> cs, long intervalMs) {
        this.candles = cs;
        this.intervalMs = intervalMs;
        crosshair = -1;
        invalidate();
    }

    /** The candle under the last touch, or null. */
    public Candles.Candle selected() {
        return crosshair >= 0 && crosshair < candles.size() ? candles.get(crosshair) : null;
    }

    public interface OnSelect { void onSelect(Candles.Candle c); }
    private OnSelect onSelect;
    public void setOnSelect(OnSelect s) { onSelect = s; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (candles.isEmpty()) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float padL = Design.dp(getContext(), 4), padR = Design.dp(getContext(), 44);
            float usable = Math.max(1, getWidth() - padL - padR);
            float slot = usable / candles.size();
            int idx = (int) ((e.getX() - padL) / slot);
            crosshair = Math.max(0, Math.min(candles.size() - 1, idx));
            if (onSelect != null) onSelect.onSelect(candles.get(crosshair));
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float padL = Design.dp(getContext(), 4), padR = Design.dp(getContext(), 44);
        float padT = Design.dp(getContext(), 8), padB = Design.dp(getContext(), 16);
        float volH = h * 0.22f;
        float priceH = h - padT - padB - volH;

        if (candles.isEmpty()) {
            text.setColor(Design.DIM2());
            c.drawText("No trades observed yet — the tape fills as the network trades",
                    padL + Design.dp(getContext(), 8), h / 2f, text);
            return;
        }

        BigDecimal hi = candles.get(0).high, lo = candles.get(0).low, volMax = BigDecimal.ZERO;
        for (Candles.Candle k : candles) {
            if (k.high.compareTo(hi) > 0) hi = k.high;
            if (k.low.compareTo(lo) < 0) lo = k.low;
            if (k.volume.compareTo(volMax) > 0) volMax = k.volume;
        }
        double top = hi.doubleValue(), bot = lo.doubleValue();
        double span = Math.max(1e-12, top - bot);
        top += span * 0.06;
        bot -= span * 0.06;
        span = top - bot;

        float usable = Math.max(1, w - padL - padR);
        float slot = usable / candles.size();
        float body = Math.max(1.5f, Math.min(slot * 0.62f, Design.dp(getContext(), 10)));

        // horizontal grid + right-hand price axis
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(Design.BORDER());
        text.setColor(Design.DIM2());
        for (int i = 0; i <= 3; i++) {
            float y = padT + priceH * i / 3f;
            c.drawLine(padL, y, w - padR, y, p);
            double v = top - span * i / 3.0;
            c.drawText(trim(v), w - padR + Design.dp(getContext(), 4), y + Design.dp(getContext(), 3), text);
        }

        for (int i = 0; i < candles.size(); i++) {
            Candles.Candle k = candles.get(i);
            float cx = padL + slot * i + slot / 2f;
            boolean up = k.close.compareTo(k.open) >= 0;
            int col = up ? Design.IN() : Design.RED();

            float yHigh = (float) (padT + priceH * (top - k.high.doubleValue()) / span);
            float yLow = (float) (padT + priceH * (top - k.low.doubleValue()) / span);
            float yOpen = (float) (padT + priceH * (top - k.open.doubleValue()) / span);
            float yClose = (float) (padT + priceH * (top - k.close.doubleValue()) / span);

            p.setColor(col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, body * 0.16f));
            c.drawLine(cx, yHigh, cx, yLow, p);

            p.setStyle(Paint.Style.FILL);
            float t = Math.min(yOpen, yClose), b = Math.max(yOpen, yClose);
            if (b - t < 1.2f) b = t + 1.2f;                    // doji stays visible
            c.drawRect(cx - body / 2f, t, cx + body / 2f, b, p);

            // volume bar
            if (volMax.signum() > 0) {
                float vh = (float) (volH * k.volume.doubleValue() / volMax.doubleValue());
                p.setAlpha(120);
                c.drawRect(cx - body / 2f, h - padB - vh, cx + body / 2f, h - padB, p);
                p.setAlpha(255);
            }
        }

        // last-price dashed line
        Candles.Candle last = candles.get(candles.size() - 1);
        float yLast = (float) (padT + priceH * (top - last.close.doubleValue()) / span);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Design.ACCENT());
        p.setStrokeWidth(1f);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{6, 6}, 0));
        path.reset();
        path.moveTo(padL, yLast);
        path.lineTo(w - padR, yLast);
        c.drawPath(path, p);
        p.setPathEffect(null);
        text.setColor(Design.ACCENT());
        c.drawText(trim(last.close.doubleValue()), w - padR + Design.dp(getContext(), 4),
                yLast + Design.dp(getContext(), 3), text);

        // crosshair
        if (crosshair >= 0 && crosshair < candles.size()) {
            float cx = padL + slot * crosshair + slot / 2f;
            p.setColor(Design.DIM());
            p.setStrokeWidth(1f);
            c.drawLine(cx, padT, cx, h - padB, p);
        }

        // time axis: first / mid / last bucket
        text.setColor(Design.DIM2());
        SimpleDateFormat fmt = new SimpleDateFormat(intervalMs >= Candles.D1 ? "dd MMM" : "dd HH:mm", Locale.US);
        int[] marks = {0, candles.size() / 2, candles.size() - 1};
        for (int m : marks) {
            if (m < 0 || m >= candles.size()) continue;
            float cx = padL + slot * m + slot / 2f;
            c.drawText(fmt.format(new Date(candles.get(m).openMs)), Math.max(padL, cx - Design.dp(getContext(), 14)),
                    h - Design.dp(getContext(), 4), text);
        }
    }

    private static String trim(double v) {
        BigDecimal b = BigDecimal.valueOf(v);
        return b.setScale(v < 0.01 ? 6 : 4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}

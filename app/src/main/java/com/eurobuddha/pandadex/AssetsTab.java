package com.eurobuddha.pandadex;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** ASSETS tab: balances split into free / locked-in-orders, portfolio value at book mid,
 *  receive address, and the mxUSDT bridge pointer. */
@SuppressLint("ViewConstructor")
public final class AssetsTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout body;

    public AssetsTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        body = new LinearLayout(act);
        body.setOrientation(VERTICAL);
        addView(body);
    }

    private TextView t(String s, int color, float size, android.graphics.Typeface tf) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setTypeface(tf);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = Design.dp(getContext(), 12);
        c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Design.dp(getContext(), 10);
        body.addView(c, lp);
        return c;
    }

    public void render() {
        body.removeAllViews();

        // locked in my resting orders
        BigDecimal lockedMinima = BigDecimal.ZERO, lockedUsdt = BigDecimal.ZERO;
        for (Order5 o : act.book().values()) {
            if (!o.isMine(act.keys())) continue;
            if (o.sell) lockedMinima = lockedMinima.add(o.locked);
            else lockedUsdt = lockedUsdt.add(o.locked);
        }

        BigDecimal mid = act.bookMid();
        BigDecimal freeM = act.minimaSendable(), freeU = act.usdtSendable();
        BigDecimal totalM = freeM.add(lockedMinima), totalU = freeU.add(lockedUsdt);

        LinearLayout head = card();
        head.addView(t("PORTFOLIO", Design.DIM(), 10f, Design.sansBold()));
        if (mid != null && mid.signum() > 0) {
            BigDecimal value = totalU.add(totalM.multiply(mid, PriceMath.MC));
            head.addView(t("≈ " + PriceMath.fmt(value.setScale(4, RoundingMode.HALF_UP)) + " mxUSDT",
                    Design.TEXT(), 22f, Design.monoBold()));
            head.addView(t("valued at the current book mid " + PriceMath.fmt(mid),
                    Design.DIM2(), 9.5f, Design.sans()));
        } else {
            head.addView(t("—", Design.TEXT(), 22f, Design.monoBold()));
            head.addView(t("no book mid yet", Design.DIM2(), 9.5f, Design.sans()));
        }

        assetCard("MINIMA", freeM, lockedMinima, totalM);
        assetCard("mxUSDT", freeU, lockedUsdt, totalU);

        LinearLayout recv = card();
        recv.addView(t("RECEIVE", Design.DIM(), 10f, Design.sansBold()));
        String addr = act.receiveAddress();
        TextView a = t(addr.isEmpty() ? "…" : addr, Design.TEXT(), 10f, Design.mono());
        recv.addView(a);
        TextView copy = t("Tap to copy", Design.ACCENT(), 9.5f, Design.sans());
        recv.addView(copy);
        recv.setOnClickListener(v -> {
            if (addr.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("address", addr));
            act.toast("Address copied");
        });
        Design.pressable(recv);

        LinearLayout bridge = card();
        bridge.addView(t("NEED mxUSDT?", Design.DIM(), 10f, Design.sansBold()));
        bridge.addView(t("mxUSDT is the wrapped-USDT token on Minima. Bridge in at mxusd.global, "
                + "or swap ERC20 USDT ↔ mxUSDT with usdtSwap.", Design.DIM2(), 10f, Design.sans()));
    }

    private void assetCard(String symbol, BigDecimal free, BigDecimal locked, BigDecimal total) {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(t(symbol, Design.TEXT(), 14f, Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView tot = t(PriceMath.fmt(total), Design.TEXT(), 15f, Design.monoBold());
        top.addView(tot);
        c.addView(top);

        LinearLayout split = new LinearLayout(getContext());
        split.setPadding(0, Design.dp(getContext(), 6), 0, 0);
        col(split, "Available", PriceMath.fmt(free), Design.IN());
        col(split, "In orders", PriceMath.fmt(locked), Design.ACCENT());
        c.addView(split);
    }

    private void col(LinearLayout row, String label, String value, int color) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.addView(t(label, Design.DIM2(), 9f, Design.sans()));
        c.addView(t(value, color, 11.5f, Design.mono()));
        row.addView(c, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }
}

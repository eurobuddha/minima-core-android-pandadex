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

/** ASSETS tab: node wallet balances using sendable / confirmed / locked / unconfirmed,
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
            if (!o.isMine(act.keys(), act.addrs())) continue;
            if (o.sell) lockedMinima = lockedMinima.add(o.locked);
            else lockedUsdt = lockedUsdt.add(o.locked);
        }

        BigDecimal freeM = act.minimaSendable(), freeU = act.usdtSendable();
        BigDecimal mid = act.bookMid();

        LinearLayout head = card();
        head.addView(t("AVAILABLE TO TRADE", Design.DIM(), 10f, Design.sansBold()));
        if (mid != null && mid.signum() > 0) {
            BigDecimal value = freeU.add(freeM.multiply(mid, PriceMath.MC));
            head.addView(t("≈ " + PriceMath.fmt(value.setScale(4, RoundingMode.HALF_UP)) + " mxUSDT",
                    Design.TEXT(), 22f, Design.monoBold()));
            head.addView(t("sendable funds only, valued at book mid " + PriceMath.fmtPrice(mid),
                    Design.DIM2(), 9.5f, Design.sans()));
        } else {
            head.addView(t("—", Design.TEXT(), 22f, Design.monoBold()));
            head.addView(t("no book mid yet", Design.DIM2(), 9.5f, Design.sans()));
        }

        assetCard("MINIMA · available to trade", freeM, act.minimaConfirmed(),
                act.minimaLockedNode(), act.minimaUnconfirmed(), act.minimaCoins(),
                act.minimaBalanceAtMs(), lockedMinima);
        assetCard("mxUSDT · available to trade", freeU, act.usdtConfirmed(),
                act.usdtLockedNode(), act.usdtUnconfirmed(), act.usdtCoins(),
                act.usdtBalanceAtMs(), lockedUsdt);

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
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("address", addr));
            act.toast("Address copied");
        });
        Design.pressable(recv);

        LinearLayout bridge = card();
        bridge.addView(t("NEED mxUSDT?", Design.DIM(), 10f, Design.sansBold()));
        bridge.addView(t("mxUSDT is the wrapped-USDT token on Minima. Bridge in at mxusd.global, "
                + "or swap ERC20 USDT ↔ mxUSDT with AtomiX.", Design.DIM2(), 10f, Design.sans()));
    }

    private void assetCard(String title, BigDecimal sendable, BigDecimal confirmed,
                           BigDecimal locked, BigDecimal unconfirmed, int coins,
                           long updatedAtMs, BigDecimal inDexOrders) {
        LinearLayout c = card();
        c.addView(t(title, Design.DIM(), 10f, Design.sansBold()));
        c.addView(t(PriceMath.fmt(sendable), Design.IN(), 18f, Design.monoBold()));
        c.addView(t("confirmed " + PriceMath.fmt(confirmed)
                + "  ·  locked ≈ " + PriceMath.fmt(locked)
                + "  ·  unconfirmed " + PriceMath.fmt(unconfirmed)
                + "  ·  " + coins + " coins"
                + "  ·  updated " + age(updatedAtMs),
                Design.DIM2(), 9.5f, Design.mono()));
        if (inDexOrders.signum() > 0) {
            c.addView(t("in PandaDEX orders " + PriceMath.fmt(inDexOrders),
                    Design.ACCENT(), 9.5f, Design.mono()));
        }
    }

    private static String age(long updatedAtMs) {
        if (updatedAtMs <= 0) return "never";
        long sec = Math.max(0, (System.currentTimeMillis() - updatedAtMs) / 1000);
        if (sec < 60) return sec + "s ago";
        long min = sec / 60;
        if (min < 60) return min + "m ago";
        return (min / 60) + "h ago";
    }
}

package com.eurobuddha.pandadex;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/**
 * PandaDEX — fully-decentralized MEXC-style limit-order exchange for MINIMA⇄mxUSDT.
 *
 * M0 shell: local-first chrome (first paint happens BEFORE the NodeApi exists — the
 * minimaSwap pattern), pairing self-heal loop, live block pill. The trade screens land in
 * M3/M4; this activity already establishes the app's snappiness contract: never block first
 * paint on the node, poll on our own clock, re-register while unpaired.
 */
public class MainActivity extends AppCompatActivity {

    /** Foreground flag so the background service (M5) can stand down while the UI drives. */
    public static volatile boolean FOREGROUND = false;

    private static final long POLL_MS = 30_000;   // own clock — never block-gated

    private final Handler ui = new Handler(Looper.getMainLooper());
    private NodeApi node;

    private TextView pairPill, blockPill, body;
    private String block = "—";
    private boolean paired = false;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            poll();
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);

        // ---- first paint: zero node dependency ----
        setContentView(buildChrome());
        render();

        // ---- node wiring fills in afterwards ----
        node = new NodeApi(this, enabled -> {
            paired = enabled;
            render();
            if (enabled) poll();
        });
    }

    private android.view.View buildChrome() {
        int pad = Design.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG());

        // header: ◆ PANDADEX · pairing pill · block pill
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, pad, pad, pad);

        TextView logo = new TextView(this);
        logo.setText("◆ PANDADEX");
        logo.setTextColor(Design.TEXT());
        logo.setTypeface(Design.monoBold());
        logo.setTextSize(16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(logo, lp);

        pairPill = Design.pill(this, "PAIRING…", Design.SURFACE2(), Design.DIM());
        header.addView(pairPill);

        blockPill = Design.pill(this, "# —", Design.SURFACE2(), Design.DIM());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Design.dp(this, 6);
        header.addView(blockPill, bp);

        root.addView(header);

        // body placeholder (M3/M4 replace this with the trade terminal)
        ScrollView scroller = new ScrollView(this);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        body = new TextView(this);
        body.setPadding(pad, pad, pad, pad);
        body.setTextColor(Design.DIM());
        body.setTypeface(Design.sans());
        body.setTextSize(13f);
        scroller.addView(body, fill);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // footer: real build version (never hardcode — Limit lesson)
        TextView footer = new TextView(this);
        footer.setText("v" + BuildConfig.VERSION_NAME);
        footer.setTextColor(Design.DIM2());
        footer.setTypeface(Design.mono());
        footer.setTextSize(10f);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(pad, 0, pad, pad);
        root.addView(footer);

        return root;
    }

    private void render() {
        if (pairPill == null) return;
        if (paired) {
            pairPill.setText("NODE ✓");
            pairPill.setTextColor(Design.IN());
        } else {
            pairPill.setText("PAIR IN MINIMA → APPS");
            pairPill.setTextColor(Design.ACCENT());
        }
        pairPill.setBackground(Design.roundBg(this, Design.SURFACE2(), 14));
        blockPill.setText("# " + block);
        body.setText(paired
                ? "Connected to your Minima node.\n\nThe trading terminal is under construction — order book, candles and trades land in the next milestones."
                : "PandaDEX talks to the Minima Core node app on this device.\n\nOpen Minima Core → Apps and enable PandaDEX, then return here.");
    }

    private void poll() {
        if (node == null) return;
        if (!node.isEnabled()) {
            // The one-shot REGISTER broadcast is lost if the node wasn't running when we launched —
            // keep re-sending until paired (minimaSwap self-heal).
            node.reRegister();
            return;
        }
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) block = r.optString("block", block);
                render();
            }
            @Override public void onError(String message) { /* keep last-known block */ }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        ui.removeCallbacks(pollTask);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(pollTask);
        if (node != null) node.onDestroy();
    }
}

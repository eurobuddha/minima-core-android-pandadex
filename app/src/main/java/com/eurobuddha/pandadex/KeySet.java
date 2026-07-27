package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The node's wallet public keys — the basis of chain-derived order ownership. Ported from
 * Limit's hardened KeySet (v0.3.1 fix): seeds SYNCHRONOUSLY from a prefs cache, retries the
 * live `keys` load with backoff, NEVER shrinks on failure, full-replaces on success, and
 * fires a callback so the UI re-renders when real keys land. A silently-collapsed key set
 * hid the user's own live order from Limit — this class is why that can't recur.
 */
public final class KeySet {

    public interface Listener { void onKeysReady(); }

    private static final String PREFS = "pandadex_keys";
    private static final String KEY = "node_keys";
    private static final long[] BACKOFF_MS = {10_000, 30_000, 90_000, 300_000};

    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> keys = new HashSet<>();
    private final Listener listener;
    private boolean fresh = false;   // a live load has succeeded this process
    private int attempt = 0;

    public KeySet(Context ctx, Listener l) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        listener = l;
        String cached = prefs.getString(KEY, "");
        if (!cached.isEmpty()) {
            try {
                JSONArray a = new JSONArray(cached);
                for (int i = 0; i < a.length(); i++) keys.add(a.optString(i));
            } catch (Exception ignore) {}
        }
    }

    public Set<String> keys() { return Collections.unmodifiableSet(keys); }

    public boolean ready() { return fresh || !keys.isEmpty(); }

    /** Union in the current getaddress pubkey (cheap early ownership signal). */
    public void addExtra(String pubkey) {
        if (pubkey != null && !pubkey.isEmpty()) keys.add(pubkey);
    }

    /** Load the full key set from the node; retry with backoff; never shrink on failure. */
    public void refresh(NodeApi node) {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Set<String> fresh0 = new HashSet<>();
                Object resp = json.opt("response");
                JSONArray arr = null;
                if (resp instanceof JSONArray) arr = (JSONArray) resp;
                else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject k = arr.optJSONObject(i);
                        String pk = k == null ? arr.optString(i, "") : k.optString("publickey", "");
                        if (!pk.isEmpty()) fresh0.add(pk);
                    }
                }
                if (fresh0.isEmpty()) { scheduleRetry(node); return; }   // never shrink
                keys.clear();
                keys.addAll(fresh0);
                fresh = true;
                attempt = 0;
                prefs.edit().putString(KEY, new JSONArray(fresh0).toString()).apply();
                if (listener != null) listener.onKeysReady();
            }
            @Override public void onError(String message) { scheduleRetry(node); }
        });
    }

    private void scheduleRetry(NodeApi node) {
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        attempt++;
        ui.postDelayed(() -> refresh(node), delay);
    }
}

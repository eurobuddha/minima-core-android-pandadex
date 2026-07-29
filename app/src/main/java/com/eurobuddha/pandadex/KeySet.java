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
    private static final String ADDRS = "node_addrs";
    private static final long[] BACKOFF_MS = {10_000, 30_000, 90_000, 300_000};

    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> keys = new HashSet<>();
    /** My wallet ADDRESSES — the second ownership factor. Port 0 (the owner pubkey) is
     *  published in plaintext on the book, so anyone can author a covenant coin carrying a
     *  victim's pubkey; only port 1, the payout address, says who actually gets paid. */
    private final Set<String> addrs = new HashSet<>();
    private final Listener listener;
    private boolean fresh = false;   // a live load has succeeded this process
    private int attempt = 0;

    public KeySet(Context ctx, Listener l) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        listener = l;
        readInto(prefs.getString(KEY, ""), keys);
        readInto(prefs.getString(ADDRS, ""), addrs);
    }

    private static void readInto(String cached, Set<String> out) {
        if (cached == null || cached.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(cached);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignore) {}
    }

    public Set<String> keys() { return Collections.unmodifiableSet(keys); }

    public Set<String> addrs() { return Collections.unmodifiableSet(addrs); }

    /**
     * Is this order genuinely MINE — both spend authority AND payout?
     *
     * The pubkey alone is not ownership: it is published on the book with every order the user
     * places, so a stranger can `send` a covenant coin carrying it and point port 1 at their
     * own address. The app would then renew that order with the victim's proof-of-work (and,
     * capped at two renewals a pass, starve the victim's REAL orders into expiry), sweep it to
     * the stranger, and file its fills in the victim's trade history.
     *
     * Fails SAFE: until the address set has loaded, fall back to the key check. A false
     * negative here is its own catastrophe — the maker would read its own live rungs as
     * missing and post the ladder a second time.
     */
    public boolean owns(Order5 o) {
        if (o == null || !o.isMine(keys)) return false;
        return addrs.isEmpty() || addrs.contains(o.wantAddr);
    }

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
                refreshAddrs(node);
                if (listener != null) listener.onKeysReady();
            }
            @Override public void onError(String message) { scheduleRetry(node); }
        });
    }

    /** Load my wallet ADDRESSES — `scripts` carries them, `keys` does not. Same discipline as
     *  the key set: never shrink on failure, full-replace on success, cached for cold start. */
    private void refreshAddrs(NodeApi node) {
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Set<String> found = new HashSet<>();
                Object resp = json.opt("response");
                JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                        : resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("scripts") : null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.optJSONObject(i);
                        if (s == null) continue;
                        String a = s.optString("address", "");
                        if (!a.isEmpty()) found.add(a);
                    }
                }
                if (found.isEmpty()) return;                  // keep what we had — never shrink
                addrs.clear();
                addrs.addAll(found);
                prefs.edit().putString(ADDRS, new JSONArray(found).toString()).apply();
                if (listener != null) listener.onKeysReady();
            }
            @Override public void onError(String message) { /* keep the cached set */ }
        });
    }

    private void scheduleRetry(NodeApi node) {
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        attempt++;
        ui.postDelayed(() -> refresh(node), delay);
    }
}

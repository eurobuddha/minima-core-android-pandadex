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
    private final PoolLiquidityRepository.Scheduler scheduler;
    private final Runnable cancelRetry;
    private final Set<String> keys = new HashSet<>();
    /** My wallet ADDRESSES — the second ownership factor. Port 0 (the owner pubkey) is
     *  published in plaintext on the book, so anyone can author a covenant coin carrying a
     *  victim's pubkey; only port 1, the payout address, says who actually gets paid. */
    private final Set<String> addrs = new HashSet<>();
    private final Listener listener;
    private boolean closed;
    private Object activeLoad, pendingRetry;
    private boolean fresh = false;   // a live load has succeeded this process
    private int attempt = 0;

    public KeySet(Context ctx, Listener l) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE), l,
                new Handler(Looper.getMainLooper()));
    }

    private KeySet(SharedPreferences prefs, Listener l, Handler ui) {
        this(prefs, l, (delay, task) -> ui.postDelayed(task, delay),
                () -> ui.removeCallbacksAndMessages(null));
    }

    /** Same command/scheduler seams used by FundingCoins and PoolLiquidityRepository. */
    KeySet(SharedPreferences prefs, Listener l, PoolLiquidityRepository.Scheduler scheduler, Runnable cancelRetry) {
        this.prefs = prefs;
        this.scheduler = scheduler;
        this.cancelRetry = cancelRetry;
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
     * Both factors are required. Cached sets may render ownership, but automation must also
     * require ready(): a complete live load for this connection, never a key-only fallback.
     */
    public boolean owns(Order5 o) {
        if (o == null || !o.isMine(keys)) return false;
        return addrs.contains(o.wantAddr);
    }

    public boolean ready() { return fresh && !closed; }
    public void invalidate() {
        fresh = false;
        activeLoad = null;
        pendingRetry = null;
        cancelRetry.run();
    }
    public void close() { closed = true; invalidate(); }
    private boolean current(Object load) { return !closed && activeLoad == load; }

    /** Union in the current getaddress pubkey (cheap early ownership signal). */
    public void addExtra(String pubkey) {
        if (FundingCoins.hex(pubkey)) keys.add(pubkey);
    }

    /**
     * Union in the address we actually PAY OURSELVES TO — the one written into port 1 of every
     * order this app creates.
     *
     * Without this the two-factor check has a catastrophic failure mode: a populated address
     * set that happens to omit our own payout address makes our own live orders read as
     * strangers', and the maker could then mistake its own live rungs for missing ones.
     * There is no key-only fallback; automation also requires the complete live key load.
     */
    public void addExtraAddr(String address) {
        if (FundingCoins.hex(address)) addrs.add(address);
    }

    /** Load the full key set; retain the last complete snapshot on failure. Main-thread only. */
    public void refresh(NodeApi node) { refresh(node::cmd); }

    void refresh(FundingCoins.Command node) {
        if (closed || activeLoad != null) return;
        cancelRetry.run();
        pendingRetry = null;
        final Object load = new Object();
        activeLoad = load;
        final boolean previouslyFresh = fresh;
        fresh = false;
        node.run("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!current(load)) return;
                if (!TxValidation.truthy(json, "status")) { scheduleRetry(node, load); return; }
                Set<String> loaded = new HashSet<>();
                Object resp = json.opt("response");
                JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                        : resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject k = arr.optJSONObject(i);
                        String pk = k == null ? arr.optString(i, "") : k.optString("publickey", "");
                        if (!FundingCoins.hex(pk)) { scheduleRetry(node, load); return; }
                        loaded.add(pk);
                    }
                }
                if (loaded.isEmpty()) { scheduleRetry(node, load); return; }
                if (previouslyFresh && loaded.equals(keys)) {
                    complete(load, loaded, new HashSet<>(addrs));
                    return;
                }
                deriveAddresses(node, load, new java.util.ArrayList<>(loaded), 0, new HashSet<>());
            }
            @Override public void onError(String message) { scheduleRetry(node, load); }
        });
    }

    /** FundingCoins' standard wallet-address derivation; staged results cannot authorize work. */
    private void deriveAddresses(FundingCoins.Command node, Object load,
                                 java.util.List<String> pubkeys, int i, Set<String> found) {
        if (!current(load)) return;
        if (i == pubkeys.size()) {
            complete(load, new HashSet<>(pubkeys), found);
            return;
        }
        node.run("runscript script:" + Util.scriptArg("RETURN SIGNEDBY(" + pubkeys.get(i) + ")"), new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                if (!current(load)) return;
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                JSONObject script = r == null ? null : r.optJSONObject("script");
                String address = script == null ? "" : script.optString("address");
                if (!TxValidation.truthy(reply, "status") || !TxValidation.truthy(r, "parseok") || !FundingCoins.hex(address)) {
                    scheduleRetry(node, load); return;
                }
                found.add(address);
                deriveAddresses(node, load, pubkeys, i + 1, found);
            }
            public void onError(String message) { scheduleRetry(node, load); }
        });
    }

    private void complete(Object load, Set<String> loaded, Set<String> addresses) {
        if (!current(load)) return;
        // Cache both factors together before publishing the corresponding in-memory snapshot.
        prefs.edit().putString(KEY, new JSONArray(loaded).toString())
                .putString(ADDRS, new JSONArray(addresses).toString()).apply();
        keys.clear(); keys.addAll(loaded);
        addrs.clear(); addrs.addAll(addresses);
        activeLoad = null;
        fresh = true;
        attempt = 0;
        if (listener != null) listener.onKeysReady();
    }

    private void scheduleRetry(FundingCoins.Command node, Object load) {
        if (!current(load)) return;
        activeLoad = null;
        fresh = false;
        cancelRetry.run();
        final Object retry = new Object();
        pendingRetry = retry;
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        if (attempt < BACKOFF_MS.length - 1) attempt++;
        scheduler.after(delay, () -> {
            if (!closed && pendingRetry == retry) { pendingRetry = null; refresh(node); }
        });
    }
}

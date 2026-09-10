package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Unattended owner-order upkeep. Atomic relocks and expired refunds use DexTxn's
 * durable Pending journal. Persisted markers only pace attempts; age/book absence do not
 * prove transaction failure or success. Both hosts use the same receipt source before retrying. */
public final class DexProcessor {

    public interface Listener {
        void onRenewed(Order5 order);
        void onRenewFailed(Order5 order, String why);
        default void onPaused(String why) {}
    }

    private static final String PREFS = "pandadex_processor";
    private static final int MAX_PER_PASS = 2;          // bound unattended PoW per pass
    private static final int INFLIGHT_BLOCKS = 6;       // pacing only; unresolved receipts still prevent retry

    private final DexTxn txn;
    private final SharedPreferences prefs;
    private final Pending pending;
    private String lastPause = "";
    /** coinid -> block the renewal was posted (persisted so fg/bg handoff can't double-post). */
    private final Map<String, Long> inflight = new HashMap<>();

    public DexProcessor(Context ctx, DexTxn txn) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                new Pending(ctx), txn);
    }

    DexProcessor(SharedPreferences prefs, Pending pending, DexTxn txn) {
        this.prefs = prefs;
        this.pending = pending;
        this.txn = txn;
    }

    private void pause(Listener listener, String why) {
        if (why.equals(lastPause)) return;
        lastPause = why;
        if (listener != null) listener.onPaused(why);
    }

    private void load() {
        Map<String, Long> loaded = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!FundingCoins.hex(entry.getKey()) || !(entry.getValue() instanceof String))
                throw new IllegalStateException("Unreadable upkeep marker");
            long block = Long.parseLong((String) entry.getValue());
            if (block <= 0) throw new IllegalStateException("Invalid upkeep block");
            loaded.put(entry.getKey(), block);
        }
        inflight.clear(); inflight.putAll(loaded);
    }

    private void mark(String coinid, long block) {
        if (!prefs.edit().putString(coinid, String.valueOf(block)).commit())
            throw new IllegalStateException("Upkeep marker was not saved");
        inflight.put(coinid, block);
    }

    private void clear(String coinid) {
        if (!prefs.edit().remove(coinid).commit())
            throw new IllegalStateException("Upkeep marker update was not saved");
        inflight.remove(coinid);
    }

    private void failed(Order5 order, String message, Listener listener) {
        try { clear(order.coinid); }
        catch (RuntimeException storage) { pause(listener, "Order upkeep paused: tracking could not be saved. Preserve app data and check Orders."); }
        if (listener != null) listener.onRenewFailed(order, message);
    }

    /** One pass over the live book. Re-reads persisted state first so the foreground and
     *  background instances coordinate through it (Limit v0.2.5 lesson). */
    public void process(Map<String, Order5> book, Set<String> myKeys, long chainBlock, Listener l) {
        process(book, myKeys, null, null, chainBlock, l);
    }

    /**
     * @param myAddrs   my wallet addresses — ownership needs the payout port too, or a stranger
     *                  can plant an order carrying my pubkey and have me renew it forever with
     *                  my own proof-of-work (starving my real orders, which then expire)
     * @param skipIds   orderIds owned by another actor (the market maker relocks its own rungs;
     *                  two owners relocking one coin double-spend it and waste the work)
     */
    public void process(Map<String, Order5> book, Set<String> myKeys, Set<String> myAddrs,
                        Set<String> skipIds, long chainBlock, Listener l) {
        if (chainBlock <= 0 || book == null) return;
        Set<String> unresolved;
        try {
            unresolved = pending.unresolvedOwnerCoins();
            load();
            // Retire pacing hints only. The separate receipt remains until linked chain proof.
            for (String coinid : new java.util.ArrayList<>(inflight.keySet())) {
                Long at = inflight.get(coinid);
                if (!book.containsKey(coinid) || (at != null && chainBlock - at > INFLIGHT_BLOCKS)) clear(coinid);
            }
        } catch (RuntimeException storage) {
            pause(l, "Order upkeep paused: stored tracking or receipts could not be read or saved. Preserve app data and check Orders.");
            return;
        }
        if (unresolved.isEmpty()) lastPause = "";

        int renewed = 0, swept = 0;
        for (Order5 o : book.values()) {
            if (!o.isMine(myKeys, myAddrs)) continue;
            if (skipIds != null && skipIds.contains(o.orderId)) continue;   // the maker's rung
            if (unresolved.contains(o.coinid.toLowerCase(java.util.Locale.ROOT))) {
                pause(l, "Some orders have unresolved requests. Automatic renewal/refund waits for receipt checks; review Orders.");
                continue;
            }

            if (o.gtc && o.renewDue(chainBlock) && !o.expired(chainBlock)) {
                if (inflight.containsKey(o.coinid)) continue;
                if (renewed >= MAX_PER_PASS) continue;
                renewed++;
                try { mark(o.coinid, chainBlock); }
                catch (RuntimeException storage) {
                    pause(l, "Order upkeep paused: tracking could not be saved. Nothing further was sent; preserve app data.");
                    return;
                }
                txn.relock(o, null, new DexTxn.Result() {
                    @Override public void onPosted(String txpowid) { if (l != null) l.onRenewed(o); }
                    @Override public void onFailed(String message) {
                        failed(o, message, l); // Durable unknown receipts still prevent automatic retry.
                    }
                });
                continue;
            }

            // my own expired orders: sweep the funds home (anyone may, but I care most)
            if (o.expired(chainBlock) && swept < MAX_PER_PASS && !inflight.containsKey(o.coinid)) {
                swept++;
                try { mark(o.coinid, chainBlock); }
                catch (RuntimeException storage) {
                    pause(l, "Order upkeep paused: tracking could not be saved. Nothing further was sent; preserve app data.");
                    return;
                }
                txn.collectExpired(o, new DexTxn.Result() {
                    @Override public void onPosted(String txpowid) {}
                    @Override public void onFailed(String message) { failed(o, message, l); }
                });
            }
        }
    }
}

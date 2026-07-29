package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The unattended brain: keeps MY GTC orders alive and notices fills. Runs in the foreground
 * Activity OR the background service — never both (FOREGROUND gate), so only one actor posts.
 *
 * Renewal is ONE atomic owner-signed re-lock (V5's owner branch, proven Phase B chunk D):
 * the order coin is spent and recreated at the same address with the same state and a fresh
 * coinage. That replaces Limit's entire two-txn cancel→recreate state machine — no funds
 * round-trip the wallet, so there is no stranded-funds window, no fill-during-renewal race
 * to reconcile (a concurrent taker fill simply double-spends the coin and the node mines
 * exactly one), and no persisted Pending machinery. All this class needs is:
 *   - don't re-post while a renewal for that coin is still unconfirmed (in-flight set +
 *     block-based expiry, so a lost txn eventually retries)
 *   - sweep MY expired non-GTC orders back to my wallet (book hygiene)
 */
public final class DexProcessor {

    public interface Listener {
        void onRenewed(Order5 order);
        void onRenewFailed(Order5 order, String why);
    }

    private static final String PREFS = "pandadex_processor";
    private static final int MAX_PER_PASS = 2;          // bound unattended PoW per pass
    private static final int INFLIGHT_BLOCKS = 6;       // retry a renewal that never landed

    private final Context ctx;
    private final DexTxn txn;
    private final SharedPreferences prefs;
    /** coinid -> block the renewal was posted (persisted so fg/bg handoff can't double-post). */
    private final Map<String, Long> inflight = new HashMap<>();

    public DexProcessor(Context ctx, DexTxn txn) {
        this.ctx = ctx.getApplicationContext();
        this.txn = txn;
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        inflight.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try { inflight.put(e.getKey(), Long.parseLong(String.valueOf(e.getValue()))); }
            catch (Exception ignore) {}
        }
    }

    private void mark(String coinid, long block) {
        inflight.put(coinid, block);
        prefs.edit().putString(coinid, String.valueOf(block)).apply();
    }

    private void clear(String coinid) {
        inflight.remove(coinid);
        prefs.edit().remove(coinid).apply();
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
        load();

        // a renewal whose coin is gone (mined — the coin was spent) is done
        for (String coinid : new java.util.ArrayList<>(inflight.keySet())) {
            Long at = inflight.get(coinid);
            if (!book.containsKey(coinid)) { clear(coinid); continue; }
            if (at != null && chainBlock - at > INFLIGHT_BLOCKS) clear(coinid);   // never landed → allow retry
        }

        int renewed = 0, swept = 0;
        for (Order5 o : book.values()) {
            if (!o.isMine(myKeys, myAddrs)) continue;
            if (skipIds != null && skipIds.contains(o.orderId)) continue;   // the maker's rung

            if (o.gtc && o.renewDue(chainBlock) && !o.expired(chainBlock)) {
                if (inflight.containsKey(o.coinid)) continue;
                if (renewed >= MAX_PER_PASS) continue;
                renewed++;
                mark(o.coinid, chainBlock);
                txn.relock(o, null, new DexTxn.Result() {
                    @Override public void onPosted(String txpowid) { if (l != null) l.onRenewed(o); }
                    @Override public void onFailed(String message) {
                        clear(o.coinid);                       // allow an immediate retry next pass
                        if (l != null) l.onRenewFailed(o, message);
                    }
                });
                continue;
            }

            // my own expired orders: sweep the funds home (anyone may, but I care most)
            if (o.expired(chainBlock) && swept < MAX_PER_PASS && !inflight.containsKey(o.coinid)) {
                swept++;
                mark(o.coinid, chainBlock);
                txn.collectExpired(o, new DexTxn.Result() {
                    @Override public void onPosted(String txpowid) {}
                    @Override public void onFailed(String message) { clear(o.coinid); }
                });
            }
        }
    }
}

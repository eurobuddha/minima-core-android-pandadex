package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The decentralized market tape: classifies fills by DIFFING consecutive book scans
 * (GlobalFeed pattern, adapted to a limit book where the diff is EXACT for partials):
 *
 *  - PARTIAL fill  — the same orderId reappears under a NEW coinid with a SMALLER locked
 *    amount → fill size = delta, price = the ORDER's enforced price. Exact.
 *  - FULL fill     — an order coin disappears before expiry (and isn't a renewal/edit,
 *    which reappear under the same orderId, or one of MY cancels). For foreign orders a
 *    cancel is indistinguishable from a full fill by book-diff alone — counted as a fill
 *    (cancels are rare vs fills; honest limitation, documented).
 *  - RENEW/EDIT    — same orderId reappears with the SAME locked amount → NOT a trade.
 *
 * Flap guards ported from GlobalFeed: the first scan of a process SEEDS silently (no replay
 * storm), a truncated scan is never diffed, and disappearances need MISS_GRACE consecutive
 * absent scans (a coin can be absent for one scan mid-reorg).
 */
public final class FillTape {

    public interface Sink {
        /** A market fill was observed. spentCoin = the consumed order coin (exactly-once key). */
        void onFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                    boolean takerBuy, boolean partial);
    }

    private static final int MISS_GRACE = 2;
    /** Consecutive confirmations demanded when the scan looked unreliable (book emptied, or
     *  most of it vanished at once). Higher bar, same outcome — a real trade is still recorded,
     *  it just has to prove itself. */
    private static final int MISS_GRACE_SUSPECT = 4;
    /** Above this many simultaneous disappearances (AND more than half the book), the scan is
     *  treated as unreliable rather than as a wave of trades. Two orders filling in one block
     *  is ordinary; the whole book vanishing at once is a bad read. */
    private static final int MAX_VANISH_PER_SCAN = 2;
    /** Below the node's visibility ceiling: past this age a coin leaves the searchable tree,
     *  so its disappearance says nothing about whether it traded. */
    private static final int VANISH_AGE = DexContract.HORIZON_BLOCKS - 80;

    /** A diff is only meaningful between two CONSECUTIVE observations. The background
     *  service stands down entirely while the Activity is up, so its previous book can be
     *  half an hour stale — diffing across that gap invents fills for everything that
     *  legitimately traded, expired or was renewed meanwhile. Re-seed instead. */
    private static final long STALE_PREV_MS = 4 * 60_000;
    private long prevAtMs = 0;

    private Map<String, Order5> prev = null;            // coinid -> order (last good scan)
    private final Map<String, Integer> missing = new java.util.HashMap<>();
    private final CancelLog cancels;

    public FillTape(CancelLog cancels) { this.cancels = cancels; }

    /** A cancel/relock this DEVICE initiated, shared across the Activity and the background
     *  service (they hold separate FillTape instances but must agree on what was cancelled —
     *  otherwise whichever sees the coin vanish first records a phantom fill). */
    public interface CancelLog {
        void note(String coinid);
        boolean consume(String coinid);
    }

    /** Mark a coin as cancelled by THIS app so its disappearance isn't a phantom fill. */
    public void noteMyCancel(String coinid) { if (cancels != null) cancels.note(coinid); }

    public void ingest(Map<String, Order5> book, boolean truncated, long chainBlock, Sink sink) {
        if (truncated) return;                           // never diff a failed scan
        if (chainBlock <= 0) return;                     // no chain height = age guards are blind
        long now = System.currentTimeMillis();
        boolean stale = prevAtMs > 0 && now - prevAtMs > STALE_PREV_MS;
        if (prev == null || stale) {                     // first sighting / stale gap: seed silently
            prev = new java.util.HashMap<>(book);
            prevAtMs = now;
            missing.clear();
            return;
        }

        // ---- SANITY GATE ------------------------------------------------------------
        // The tape is this app's ONLY source of price truth (ticker, 24h stats, candles,
        // P&L), so a false entry is worse than a missing one. A disappearance is only
        // evidence of a trade if the REST of the book was seen intact in the same scan —
        // otherwise we are looking at a bad scan, not a filled order.
        //
        // `truncated` does not catch this: a scan that returns an empty or partial JSON
        // array parses perfectly and looks like "the book emptied". That is how a session
        // with nothing but resting orders ended up reporting a 24h high/low/volume made
        // entirely out of those orders' own prices and sizes.
        int vanished = 0;
        for (String coinid : prev.keySet()) if (!book.containsKey(coinid)) vanished++;
        boolean bookEmptied = book.isEmpty() && !prev.isEmpty();
        boolean massVanish = vanished > MAX_VANISH_PER_SCAN
                && vanished * 2 > prev.size();           // over half the book gone at once
        // A suspicious scan means we need MORE EVIDENCE — never that we throw the evidence
        // away. The first cut of this guard re-seeded and returned, which silently discarded
        // a real trade whenever the LAST resting order filled (in a thin market that empties
        // the book, which is exactly the shape of a genuine fill). Instead, demand that the
        // disappearance survives more consecutive scans: a bad read corrects itself within a
        // scan or two and still mints nothing, while a book that really did empty is
        // confirmed and recorded.
        int needed = (bookEmptied || massVanish) ? MISS_GRACE_SUSPECT : MISS_GRACE;

        // Index the new book by order IDENTITY, not orderId alone: orderId is maker-chosen
        // and an attacker can copy a victim's, which would let a stranger's coin masquerade
        // as the successor of the victim's order (mislabelling a real fill as a renewal, or
        // attributing someone else's partial to the victim at the wrong price/size).
        Map<String, Order5> byIdentity = new java.util.HashMap<>();
        for (Order5 o : book.values()) byIdentity.put(identity(o), o);

        for (Map.Entry<String, Order5> e : prev.entrySet()) {
            String coinid = e.getKey();
            Order5 old = e.getValue();
            if (book.containsKey(coinid)) continue;      // still resting

            Order5 successor = byIdentity.get(identity(old));
            if (successor != null && successor.coinid.equals(coinid)) continue;

            if (successor != null) {
                missing.remove(coinid);
                int cmp = successor.locked.compareTo(old.locked);
                if (cmp < 0) {
                    // PARTIAL: exact delta at the order's enforced price
                    BigDecimal size = minimaDelta(old, successor);
                    sink.onFill(coinid, old, size, old.price(), old.sell, true);
                }                                        // same amount = renewal/edit, not a trade
                continue;
            }

            int misses = missing.merge(coinid, 1, Integer::sum);
            if (misses < needed) continue;
            missing.remove(coinid);

            if (cancels != null && cancels.consume(coinid)) continue;   // this device cancelled it
            if (old.expired(chainBlock)) continue;       // expiry sweep, not a trade
            // A coin near the node's visibility ceiling leaves the searchable tree whether or
            // not it traded — treating that as a fill invents trades, poisons the candles and
            // fires "order filled" alerts for orders that simply aged out.
            if (old.age(chainBlock) >= VANISH_AGE) continue;

            // FULL fill (or a foreign cancel — indistinguishable; counted as fill)
            sink.onFill(coinid, old, old.minimaAmount(), old.price(), old.sell, false);
        }

        // counters exist only while a coin is absent — a reappeared coin's counter dies here
        missing.keySet().removeAll(book.keySet());
        // next prev = the new book PLUS pending-missing coins (they must stay diffable until
        // their grace matures — otherwise the counter orphans and the fill is never emitted)
        Map<String, Order5> next = new java.util.HashMap<>(book);
        for (String id : missing.keySet()) {
            Order5 gone = prev.get(id);
            if (gone != null) next.put(id, gone);
        }
        prev = next;
        prevAtMs = now;
    }

    /** Order identity for successor matching: the maker-chosen id is not trustworthy alone,
     *  so bind it to the maker's key, payout address and side. */
    private static String identity(Order5 o) {
        return o.orderId + "|" + o.ownerPk + "|" + o.wantAddr + "|" + (o.sell ? "s" : "b");
    }

    /** The MINIMA-side size of a partial fill between an order and its remainder. */
    private static BigDecimal minimaDelta(Order5 old, Order5 successor) {
        if (old.sell) {
            return old.locked.subtract(successor.locked);            // MINIMA locked shrank
        }
        // buy: locked is mxUSDT; convert the taken usdt to MINIMA at the order price
        BigDecimal usdtDelta = old.locked.subtract(successor.locked);
        BigDecimal price = old.price();
        if (price.signum() == 0) return BigDecimal.ZERO;
        return usdtDelta.divide(price, PriceMath.MINIMA_DP, RoundingMode.DOWN);
    }
}

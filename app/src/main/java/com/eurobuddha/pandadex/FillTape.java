package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
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
        /**
         * A market fill was observed. spentCoin = the consumed order coin (exactly-once key).
         *
         * @param sinceBlock the last chain height at which this order was seen RESTING. Evidence
         *                   older than that cannot explain its disappearance, so it is the
         *                   tightest floor available to the verifier — tighter than the fixed
         *                   lookback, which let an older wallet coin of the same size stand in.
         */
        void onFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                    boolean takerBuy, boolean partial, long sinceBlock);

        /**
         * This scan has emitted everything it is going to. Whole-coin disappearances must be
         * adjudicated as a BATCH — a payout coin can only be evidence for one order, and that
         * cannot be decided one order at a time — so the sink buffers during the diff and settles
         * here. Nothing to do for a sink that records immediately.
         */
        default void onScanComplete() {}
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
    public static final long STALE_PREV_MS = 4 * 60_000;
    private long prevAtMs = 0;

    private Map<String, Order5> prev = null;            // coinid -> order (last good scan)
    private final Map<String, Integer> missing = new java.util.HashMap<>();
    /** coinid -> the last chain height at which the coin was seen resting. The verifier's
     *  evidence floor: a coin created before this cannot explain the disappearance. */
    private final Map<String, Long> lastSeen = new java.util.HashMap<>();
    private final CancelLog cancels;
    /** How old the previous observation may be and still be diffable. MUST exceed the owner's
     *  polling gap: the background service polls every PASS_GAP_MS, so a 4-minute ceiling made
     *  its every pass "stale", which re-seeded and returned — its fill detection, and the
     *  "your order filled" notification with it, could never once fire. */
    private final long staleMs;

    public FillTape(CancelLog cancels) { this(cancels, STALE_PREV_MS); }

    public FillTape(CancelLog cancels, long staleMs) {
        this.cancels = cancels;
        this.staleMs = staleMs;
    }

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
        boolean stale = prevAtMs > 0 && now - prevAtMs > staleMs;
        if (prev == null || stale) {                     // first sighting / stale gap: seed silently
            prev = new java.util.HashMap<>(book);
            prevAtMs = now;
            missing.clear();
            lastSeen.clear();
            for (String coinid : book.keySet()) lastSeen.put(coinid, chainBlock);
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

        // A MASS disappearance is never evidence of trading, however many times it repeats.
        // A resyncing node answers `coins` with status:true and an empty array (it has no tip
        // yet), which parses perfectly — `truncated` is false and the age/expiry guards don't
        // fire — so raising the grace count alone only delays the damage: a couple of minutes
        // of resync at a 30s poll and EVERY resting order is minted as a full fill,
        // permanently, into the one source of price truth this app has.
        //
        // The discriminator is whether the scan returned ANY evidence. A book that still holds
        // orders proves the node answered properly, so orders missing FROM it really did go
        // (that case is recorded, just held to a higher confirmation bar). A book that came
        // back completely empty proves nothing at all — and a resync empties it wholesale,
        // where genuine trading empties it one or two orders at a time, which is how the last
        // order in a thin market still gets recorded.
        if (bookEmptied && vanished > MAX_VANISH_PER_SCAN) {
            // Keep the LAST BELIEVABLE observation, don't fold the empty one in. Merging would
            // leave prev holding only the few coins already mid-grace, so once the node
            // recovered there would be nothing left to diff against and any order that really
            // did trade during the outage would be lost — the other half of this file's
            // contract. Holding prev means the recovered book adjudicates every coin at once.
            prevAtMs = now;                // not stale — we are deliberately waiting
            return;
        }

        // Index the new book by order IDENTITY, not orderId alone: orderId is maker-chosen
        // and an attacker can copy a victim's, which would let a stranger's coin masquerade
        // as the successor of the victim's order (mislabelling a real fill as a renewal, or
        // attributing someone else's partial to the victim at the wrong price/size).
        // AMBIGUOUS identities are unusable, not "last one wins": every component of the
        // identity is readable off the victim's own resting order, so a stranger can mint a
        // coin carrying all four. Silently preferring whichever the map iterated last let that
        // copy decide whether a real fill was recorded (same locked = "renewal", so the fill
        // vanishes) or fabricated (smaller locked = a partial at a size the stranger chose).
        Map<String, List<Order5>> byIdentity = new java.util.HashMap<>();
        for (Order5 o : book.values()) {
            byIdentity.computeIfAbsent(identity(o), k -> new java.util.ArrayList<>()).add(o);
        }

        int emitted = 0;
        for (Map.Entry<String, Order5> e : prev.entrySet()) {
            String coinid = e.getKey();
            Order5 old = e.getValue();
            if (book.containsKey(coinid)) continue;      // still resting

            List<Order5> candidates = byIdentity.get(identity(old));
            // more than one coin claiming to be this order's successor proves nothing about
            // either — fall through to the miss-grace path rather than believing one
            Order5 successor = (candidates != null && candidates.size() == 1)
                    ? candidates.get(0) : null;
            // A successor is the OUTPUT of spending the old coin, so it cannot be a coin that
            // was already resting last scan. Without this, a stranger who copies the identity
            // and simply sits on the book is treated as the remainder of the victim's order —
            // fabricating a partial at a size the stranger chose, or masking a real fill as a
            // renewal. The ambiguity check above only catches the case where both are visible.
            if (successor != null
                    && (prev.containsKey(successor.coinid) || successor.created < old.created)) {
                successor = null;
            }
            if (successor != null && successor.coinid.equals(coinid)) continue;

            if (successor != null) {
                missing.remove(coinid);
                int cmp = successor.locked.compareTo(old.locked);
                if (cmp < 0) {
                    // PARTIAL: exact delta at the order's enforced price
                    BigDecimal size = minimaDelta(old, successor);
                    sink.onFill(coinid, old, size, old.price(), old.sell, true, seenAt(coinid));
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

            // HARD CAP, independent of the grace counters: a genuine block takes a couple of
            // orders, so a wave of "fills" is a bad read however many times it repeats. The
            // grace count only decides how long we wait — this decides how much a single
            // ingest may ever assert. Coins over the cap keep their counters and are
            // re-examined next scan, so a real wave is still recorded, just spread out.
            if (emitted >= MAX_VANISH_PER_SCAN) {
                missing.put(coinid, needed);        // stay ripe for the next scan
                continue;
            }
            emitted++;
            // FULL fill (or a foreign cancel — indistinguishable; counted as fill)
            sink.onFill(coinid, old, old.minimaAmount(), old.price(), old.sell, false, seenAt(coinid));
        }

        // counters exist only while a coin is absent — a reappeared coin's counter dies here
        missing.keySet().removeAll(book.keySet());
        for (String coinid : book.keySet()) lastSeen.put(coinid, chainBlock);
        prev = mergePrev(book, prev);
        prevAtMs = now;
        // Everything this diff had to say. The sink settles its batch now — see Sink.onScanComplete.
        sink.onScanComplete();
        // A coin that is neither resting nor mid-grace can never be asked about again.
        lastSeen.keySet().retainAll(prev.keySet());
    }

    /** Falls back to the order's own creation block when we have no sighting — never to 0, which
     *  would widen the evidence window rather than narrow it. */
    private long seenAt(String coinid) {
        Long at = lastSeen.get(coinid);
        return at == null ? 0 : at;
    }

    /** next prev = the new book PLUS pending-missing coins (they must stay diffable until
     *  their grace matures — otherwise the counter orphans and the fill is never emitted). */
    private Map<String, Order5> mergePrev(Map<String, Order5> book, Map<String, Order5> old) {
        Map<String, Order5> next = new java.util.HashMap<>(book);
        for (String id : missing.keySet()) {
            Order5 gone = old.get(id);
            if (gone != null) next.put(id, gone);
        }
        return next;
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

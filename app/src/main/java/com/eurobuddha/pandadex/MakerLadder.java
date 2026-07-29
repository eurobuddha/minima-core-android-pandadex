package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The market maker's brain: turn the configured ladder into the rungs we want on the book,
 * then work out the SMALLEST set of on-chain actions that moves the live book to match.
 *
 * The ladder model is AtomiX's (Order.Level / editOrderDialog), not an offset table: a rung is
 * an explicit PRICE + AMOUNT. When PEGGED only the PRICES are engine-owned — rung i quotes at
 * quoted-mid × (1 ± (i+1)·step%) — while every rung's SIZE is the user's own, read positionally
 * from the rung rows and PRESERVED across reprices (a deliberate deviation from AtomiX's
 * applyPeg, which rebuilds uniform sizes; chosen by the user for per-rung control). A rung with
 * size 0 is a gap, a side with no sized rungs is not quoted (one-sided market). When NOT pegged
 * the rungs are quoted exactly as typed — price and size — and never repriced.
 *
 * Pure and side-effect free so it can be unit-tested exhaustively — every decision here spends
 * real money (each action is a transaction with proof-of-work on a phone), so the arithmetic
 * and the diffing are worth proving separately from the plumbing that executes them.
 *
 * The reconciliation exploits something AtomiX could not do: PandaDEX's V5 covenant lets an
 * owner RE-LOCK an order in place, changing its price in ONE transaction without the funds
 * ever leaving the book. So a reprice is a RELOCK, not a cancel-then-repost — there is no
 * window where the level is missing and no way for a failed re-post to leave the maker out
 * of the market.
 */
public final class MakerLadder {

    /** Hard cap per side (AtomiX's MAX_LEVELS). Every level is a separate on-chain order. */
    public static final int MAX_LEVELS = 6;

    // ---------------------------------------------------------------- config

    /** One rung: an absolute price and the MINIMA size to show there. */
    public static final class Level {
        public final BigDecimal price;        // mxUSDT per MINIMA
        public final BigDecimal sizeMinima;

        public Level(BigDecimal price, BigDecimal sizeMinima) {
            this.price = price;
            this.sizeMinima = sizeMinima;
        }
    }

    public static final class Config {
        public final boolean pegged;          // regenerate rung PRICES around the live mid
        public final BigDecimal stepPct;      // pegged: spacing per rung out (e.g. 0.20 = 0.20%)
        /** The rung rows. Pegged: POSITIONAL — index i is rung i±1 out from the mid, its size
         *  is authoritative, its price is ignored (engine-generated). Manual: explicit rungs,
         *  sanitized/sorted inside desired(). The seed size fields never reach the engine. */
        public final List<Level> asks;
        public final List<Level> bids;
        public final BigDecimal skewPct;      // + shifts the whole ladder UP (bullish)
        public final BigDecimal repricePct;   // don't touch anything until the mid moves this far

        public Config(boolean pegged, BigDecimal stepPct,
                      List<Level> asks, List<Level> bids,
                      BigDecimal skewPct, BigDecimal repricePct) {
            this.pegged = pegged;
            this.stepPct = stepPct;
            this.asks = asks;
            this.bids = bids;
            this.skewPct = skewPct;
            this.repricePct = repricePct;
        }
    }

    /** Positional rung size — 0 for a missing/blank/invalid row (a gap in the ladder). */
    static BigDecimal sizeAt(List<Level> rungs, int i) {
        if (rungs == null || i >= rungs.size()) return BigDecimal.ZERO;
        Level l = rungs.get(i);
        return (l == null || l.sizeMinima == null || l.sizeMinima.signum() <= 0)
                ? BigDecimal.ZERO : l.sizeMinima;
    }

    /** Does this side quote anything at all? (Any rung with a positive size.) */
    public static boolean hasSizedRung(List<Level> rungs) {
        for (int i = 0; i < MAX_LEVELS; i++) if (sizeAt(rungs, i).signum() > 0) return true;
        return false;
    }

    /**
     * THE single normalizer (AtomiX's Order.sanitize): valid levels only (price and size > 0),
     * at most MAX_LEVELS, asks sorted price-ASC / bids price-DESC so index 0 is the best rung —
     * slot ids stay dense and stable.
     */
    public static void sanitize(List<Level> levels, boolean asks) {
        if (levels == null) return;
        for (Iterator<Level> it = levels.iterator(); it.hasNext(); ) {
            Level l = it.next();
            if (l == null || l.price == null || l.sizeMinima == null
                    || l.price.signum() <= 0 || l.sizeMinima.signum() <= 0) it.remove();
        }
        Collections.sort(levels, (a, b) -> asks ? a.price.compareTo(b.price)
                                               : b.price.compareTo(a.price));
        while (levels.size() > MAX_LEVELS) levels.remove(levels.size() - 1);
    }

    /** Crossed market: best bid at-or-above best ask — you'd sell cheaper than you buy. Warn-only. */
    public static boolean crossed(List<Level> asks, List<Level> bids) {
        List<Level> a = new ArrayList<>(asks == null ? Collections.emptyList() : asks);
        List<Level> b = new ArrayList<>(bids == null ? Collections.emptyList() : bids);
        sanitize(a, true);
        sanitize(b, false);
        return !a.isEmpty() && !b.isEmpty() && b.get(0).price.compareTo(a.get(0).price) >= 0;
    }

    // ---------------------------------------------------------------- desired state

    /** A rung we want on the book: a stable slot id, a side, a price and a size. */
    public static final class Slot {
        public final String id;              // "B1".."B6" / "A1".."A6" — stable across reprices
        public final boolean sell;
        public final BigDecimal price;
        public final BigDecimal sizeMinima;

        Slot(String id, boolean sell, BigDecimal price, BigDecimal sizeMinima) {
            this.id = id;
            this.sell = sell;
            this.price = price;
            this.sizeMinima = sizeMinima;
        }
    }

    private MakerLadder() {}

    /**
     * Build the rungs we want on the book.
     *
     * PEGGED — the quoted mid is the reference mid shifted by skew, rung i sits at
     * quoted × (1 ± (i+1)·step%), asks above / bids below, and each rung carries ITS OWN size
     * read positionally from the rung rows (preserved across reprices — the user's per-rung
     * control). A zero-size row is a gap with stable slot ids; a side with no sized rows is
     * not quoted. Widening multiplies the step — used to quote worse as the price feed ages
     * rather than blindly standing on a stale number.
     *
     * MANUAL — the explicit rungs, exactly as typed. No mid, no skew, no widening: the prices
     * do not depend on the feed, so they are never repriced and never withdrawn for staleness.
     */
    public static List<Slot> desired(BigDecimal mid, Config cfg, BigDecimal widenFactor) {
        List<Slot> out = new ArrayList<>();
        if (cfg == null) return out;
        BigDecimal hundred = new BigDecimal(100);

        if (!cfg.pegged) {
            List<Level> asks = new ArrayList<>(cfg.asks == null ? Collections.emptyList() : cfg.asks);
            List<Level> bids = new ArrayList<>(cfg.bids == null ? Collections.emptyList() : cfg.bids);
            sanitize(asks, true);
            sanitize(bids, false);
            for (int i = 0; i < asks.size(); i++)
                out.add(new Slot("A" + (i + 1), true, asks.get(i).price, asks.get(i).sizeMinima));
            for (int i = 0; i < bids.size(); i++)
                out.add(new Slot("B" + (i + 1), false, bids.get(i).price, bids.get(i).sizeMinima));
            return out;
        }

        if (mid == null || mid.signum() <= 0) return out;
        if (cfg.stepPct == null || cfg.stepPct.signum() <= 0) return out;
        if (!hasSizedRung(cfg.asks) && !hasSizedRung(cfg.bids)) return out;

        BigDecimal widen = (widenFactor == null || widenFactor.signum() <= 0)
                ? BigDecimal.ONE : widenFactor;
        BigDecimal skewed = mid.multiply(BigDecimal.ONE.add(
                cfg.skewPct.divide(hundred, PriceMath.MC)), PriceMath.MC);

        for (int i = 0; i < MAX_LEVELS; i++) {
            BigDecimal off = cfg.stepPct.multiply(new BigDecimal(i + 1), PriceMath.MC)
                    .multiply(widen, PriceMath.MC).divide(hundred, PriceMath.MC);
            BigDecimal bidSz = sizeAt(cfg.bids, i);
            BigDecimal askSz = sizeAt(cfg.asks, i);
            if (bidSz.signum() > 0) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.DOWN);
                if (p.signum() > 0) out.add(new Slot("B" + (i + 1), false, p, bidSz));
            }
            if (askSz.signum() > 0) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.UP);
                out.add(new Slot("A" + (i + 1), true, p, askSz));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- commitments

    /** What publishing this ladder locks up, per token side. */
    public static final class Commitments {
        public final BigDecimal askMinima;   // Σ ask sizes — locked MINIMA
        public final BigDecimal bidUsdt;     // Σ bid size × price — locked mxUSDT

        Commitments(BigDecimal askMinima, BigDecimal bidUsdt) {
            this.askMinima = askMinima;
            this.bidUsdt = bidUsdt;
        }
    }

    public static Commitments commitments(List<Slot> desired) {
        BigDecimal askM = BigDecimal.ZERO, bidU = BigDecimal.ZERO;
        if (desired != null) {
            for (Slot s : desired) {
                if (s == null) continue;
                if (s.sell) askM = askM.add(s.sizeMinima);
                else bidU = bidU.add(PriceMath.up(
                        s.sizeMinima.multiply(s.price, PriceMath.MC), PriceMath.USDT_DP));
            }
        }
        return new Commitments(askM, bidU);
    }

    // ---------------------------------------------------------------- reconciliation

    public enum Kind { CREATE, RELOCK, CANCEL }

    /** One thing to do on-chain. RELOCK carries the existing order plus its new price. */
    public static final class Action {
        public final Kind kind;
        public final Slot slot;              // null for CANCEL
        public final Order5 order;           // null for CREATE
        public final String reason;

        Action(Kind kind, Slot slot, Order5 order, String reason) {
            this.kind = kind;
            this.slot = slot;
            this.order = order;
            this.reason = reason;
        }
    }

    /**
     * Work out the minimal set of actions to bring the live book in line with the desired
     * ladder.
     *
     * {@code liveBySlot} maps our slot ids to the order we last placed for them, so a rung
     * keeps its identity across reprices instead of being cancelled and recreated.
     *
     * Rules, in order of preference — the cheapest correct action wins, because every action
     * costs proof-of-work:
     *   - the rung is missing            → CREATE
     *   - the price moved past threshold → RELOCK (atomic, funds stay on the book)
     *   - the rung is no longer wanted   → CANCEL
     *   - the SIZE was changed           → CANCEL + CREATE (a re-lock cannot change the locked
     *     amount), lowest priority — and only for rungs that are NOT partially filled
     *   - the rung has been PARTIALLY filled → leave it alone; the remainder is still working
     *     and cancelling it would throw away a live position for a cosmetic price improvement
     *
     * {@code repricePct <= 0} selects EXACT mode (the manual ladder: "quoted exactly as typed"):
     * any price difference at {@link PriceMath#DISPLAY_DP} relocks. The comparison is on the
     * ROUNDED prices deliberately — a posted order's reconstructed price carries amount-rounding
     * noise below display precision, and comparing raw values would relock every cycle forever.
     */
    /** The cycle's action allowance. Creates are additionally capped PER SIDE because they fund
     *  via wallet `send`s — two bid creates in one cycle fight over the same mxUSDT coins and
     *  the second fails unfunded until the first's change confirms (observed live). Bids and
     *  asks fund from different tokens, so one of each never contends; relocks and cancels
     *  spend only the order coin itself and never contend at all. */
    public static final class Budget {
        public final int maxActions;         // 0 = unlimited
        public final int maxCreatesPerSide;  // per cycle; Integer.MAX_VALUE = uncapped

        public Budget(int maxActions, int maxCreatesPerSide) {
            this.maxActions = maxActions;
            this.maxCreatesPerSide = maxCreatesPerSide;
        }
    }

    public static List<Action> reconcile(List<Slot> desired, Map<String, Order5> liveBySlot,
                                         BigDecimal repricePct, Set<String> partiallyFilled,
                                         int maxActions) {
        return reconcile(desired, liveBySlot, null, repricePct, partiallyFilled, null,
                new Budget(maxActions, Integer.MAX_VALUE));
    }

    /** As above, with the sizes we originally posted per slot id — enables size-change repairs. */
    public static List<Action> reconcile(List<Slot> desired, Map<String, Order5> liveBySlot,
                                         BigDecimal repricePct, Set<String> partiallyFilled,
                                         Map<String, BigDecimal> postedSizes, int maxActions) {
        return reconcile(desired, liveBySlot, null, repricePct, partiallyFilled, postedSizes,
                new Budget(maxActions, Integer.MAX_VALUE));
    }

    /**
     * Full form. {@code settlingSlots} are slots with an action already in flight (a create
     * still mining, a relock whose old coin is still visible, a fresh cancel) — they are
     * neither created, relocked, nor resized this cycle; touching them would double-spend
     * the work or duplicate the order.
     */
    public static List<Action> reconcile(List<Slot> desired, Map<String, Order5> liveBySlot,
                                         Set<String> settlingSlots,
                                         BigDecimal repricePct, Set<String> partiallyFilled,
                                         Map<String, BigDecimal> postedSizes, Budget budget) {
        // Collected by KIND so the cap below drops the least urgent work first.
        List<Action> relocks = new ArrayList<>();
        List<Action> creates = new ArrayList<>();
        List<Action> cancels = new ArrayList<>();
        List<Action> resizes = new ArrayList<>();
        Map<String, Slot> want = new HashMap<>();
        for (Slot s : desired) want.put(s.id, s);

        // rungs we no longer want
        for (Map.Entry<String, Order5> e : liveBySlot.entrySet()) {
            if (!want.containsKey(e.getKey()) && e.getValue() != null) {
                cancels.add(new Action(Kind.CANCEL, null, e.getValue(), "level removed"));
            }
        }

        int bidCreates = 0, askCreates = 0;
        for (Slot s : desired) {
            if (settlingSlots != null && settlingSlots.contains(s.id)) {
                continue;                    // action already in flight — hands off this cycle
            }
            Order5 live = liveBySlot.get(s.id);
            if (live == null) {
                int used = s.sell ? askCreates : bidCreates;
                if (used < budget.maxCreatesPerSide) {
                    creates.add(new Action(Kind.CREATE, s, null, "level missing"));
                    if (s.sell) askCreates++; else bidCreates++;
                }
                continue;
            }
            if (partiallyFilled != null && partiallyFilled.contains(live.coinid)) {
                continue;                    // working remainder — don't disturb it
            }
            // A deliberate size change can't be re-locked (the funds stay locked) — repost.
            // Compared against the size we POSTED, not the live amount: a buy order's on-chain
            // amount carries conversion rounding that would read as a phantom size change.
            BigDecimal posted = postedSizes == null ? null : postedSizes.get(s.id);
            if (posted != null && posted.compareTo(s.sizeMinima) != 0) {
                // The repost CREATE funds via a wallet send too — it shares the per-side
                // contention budget. Over budget → whole pair waits for a later cycle.
                int used = s.sell ? askCreates : bidCreates;
                if (used < budget.maxCreatesPerSide) {
                    resizes.add(new Action(Kind.CANCEL, null, live, "size changed"));
                    resizes.add(new Action(Kind.CREATE, s, null, "size changed"));
                    if (s.sell) askCreates++; else bidCreates++;
                }
                continue;
            }
            BigDecimal livePrice = live.price();
            if (livePrice.signum() <= 0) continue;
            boolean move;
            String reason;
            if (repricePct == null || repricePct.signum() <= 0) {
                // exact mode — the price is the user's explicit instruction, honour any
                // difference visible at display precision
                move = s.price.setScale(PriceMath.DISPLAY_DP, RoundingMode.HALF_UP).compareTo(
                        livePrice.setScale(PriceMath.DISPLAY_DP, RoundingMode.HALF_UP)) != 0;
                reason = "price edited";
            } else {
                BigDecimal movePct = s.price.subtract(livePrice).abs()
                        .divide(livePrice, PriceMath.MC).multiply(new BigDecimal(100));
                move = movePct.compareTo(repricePct) >= 0;
                reason = "moved " + movePct.setScale(3, RoundingMode.HALF_UP) + "%";
            }
            if (move) relocks.add(new Action(Kind.RELOCK, s, live, reason));
        }

        // Priority when the cycle budget is tight: fix MISPRICED quotes first (they are the
        // live risk — someone can trade against them right now), then restore MISSING rungs,
        // then tidy up rungs we no longer want, and only last the size-change reposts (a
        // cosmetic tune of an order that is otherwise working fine). Appending cancels first
        // would let a handful of them starve every create, tearing the ladder down without
        // rebuilding it. If the cap splits a resize pair after its CANCEL, the next cycle sees
        // the rung as missing and CREATEs it at the new size — self-healing.
        List<Action> actions = new ArrayList<>();
        actions.addAll(relocks);
        actions.addAll(creates);
        actions.addAll(cancels);
        actions.addAll(resizes);

        if (budget.maxActions > 0 && actions.size() > budget.maxActions) {
            return new ArrayList<>(actions.subList(0, budget.maxActions));
        }
        return actions;
    }

    /** True when the reference mid has moved far enough from the last one we acted on to be
     *  worth spending any proof-of-work at all. */
    public static boolean worthRepricing(BigDecimal lastMid, BigDecimal newMid, BigDecimal pct) {
        if (lastMid == null || lastMid.signum() <= 0) return true;
        if (newMid == null || newMid.signum() <= 0) return false;
        BigDecimal move = newMid.subtract(lastMid).abs()
                .divide(lastMid, PriceMath.MC).multiply(new BigDecimal(100));
        return move.compareTo(pct) >= 0;
    }
}

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
 * an explicit PRICE + AMOUNT. When PEGGED the rungs are regenerated around the live MEXC mid
 * from the same seed parameters AtomiX uses — step % spacing, a level count, and INDEPENDENT
 * ask/bid sizes (a side with size 0 is simply not quoted — a one-sided market). When NOT
 * pegged the rungs are quoted exactly as typed and never repriced.
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
        public final boolean pegged;          // regenerate the rungs around the live mid
        public final BigDecimal stepPct;      // pegged: spacing per rung out (e.g. 0.20 = 0.20%)
        public final int levels;              // pegged: rungs per side (1..MAX_LEVELS)
        public final BigDecimal askSize;      // pegged: MINIMA per ask rung; 0 = no asks
        public final BigDecimal bidSize;      // pegged: MINIMA per bid rung; 0 = no bids
        public final List<Level> asks;        // manual: explicit rungs, best (lowest) first
        public final List<Level> bids;        // manual: explicit rungs, best (highest) first
        public final BigDecimal skewPct;      // + shifts the whole ladder UP (bullish)
        public final BigDecimal repricePct;   // don't touch anything until the mid moves this far

        public Config(boolean pegged, BigDecimal stepPct, int levels,
                      BigDecimal askSize, BigDecimal bidSize,
                      List<Level> asks, List<Level> bids,
                      BigDecimal skewPct, BigDecimal repricePct) {
            this.pegged = pegged;
            this.stepPct = stepPct;
            this.levels = levels;
            this.askSize = askSize;
            this.bidSize = bidSize;
            this.asks = asks;
            this.bids = bids;
            this.skewPct = skewPct;
            this.repricePct = repricePct;
        }
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
     * PEGGED — AtomiX's fillFromPeg arithmetic: the quoted mid is the reference mid shifted by
     * skew, rung i sits at quoted × (1 ± (i+1)·step%), asks above / bids below, each side at its
     * own uniform size, and a side whose size is zero is not quoted at all. Widening multiplies
     * the step — used to quote worse as the price feed ages rather than blindly standing on a
     * stale number.
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
        boolean asksOn = cfg.askSize != null && cfg.askSize.signum() > 0;
        boolean bidsOn = cfg.bidSize != null && cfg.bidSize.signum() > 0;
        if (!asksOn && !bidsOn) return out;

        BigDecimal widen = (widenFactor == null || widenFactor.signum() <= 0)
                ? BigDecimal.ONE : widenFactor;
        BigDecimal skewed = mid.multiply(BigDecimal.ONE.add(
                cfg.skewPct.divide(hundred, PriceMath.MC)), PriceMath.MC);

        int n = Math.max(1, Math.min(cfg.levels, MAX_LEVELS));
        for (int i = 0; i < n; i++) {
            BigDecimal off = cfg.stepPct.multiply(new BigDecimal(i + 1), PriceMath.MC)
                    .multiply(widen, PriceMath.MC).divide(hundred, PriceMath.MC);
            if (bidsOn) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.DOWN);
                if (p.signum() > 0) out.add(new Slot("B" + (i + 1), false, p, cfg.bidSize));
            }
            if (asksOn) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.UP);
                out.add(new Slot("A" + (i + 1), true, p, cfg.askSize));
            }
        }
        return out;
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
     *   - the rung has been PARTIALLY filled → leave it alone; the remainder is still working
     *     and cancelling it would throw away a live position for a cosmetic price improvement
     */
    public static List<Action> reconcile(List<Slot> desired, Map<String, Order5> liveBySlot,
                                         BigDecimal repricePct, Set<String> partiallyFilled,
                                         int maxActions) {
        // Collected by KIND so the cap below drops the least urgent work first.
        List<Action> relocks = new ArrayList<>();
        List<Action> creates = new ArrayList<>();
        List<Action> cancels = new ArrayList<>();
        Map<String, Slot> want = new HashMap<>();
        for (Slot s : desired) want.put(s.id, s);

        // rungs we no longer want
        for (Map.Entry<String, Order5> e : liveBySlot.entrySet()) {
            if (!want.containsKey(e.getKey()) && e.getValue() != null) {
                cancels.add(new Action(Kind.CANCEL, null, e.getValue(), "level removed"));
            }
        }

        for (Slot s : desired) {
            Order5 live = liveBySlot.get(s.id);
            if (live == null) {
                creates.add(new Action(Kind.CREATE, s, null, "level missing"));
                continue;
            }
            if (partiallyFilled != null && partiallyFilled.contains(live.coinid)) {
                continue;                    // working remainder — don't disturb it
            }
            BigDecimal livePrice = live.price();
            if (livePrice.signum() <= 0) continue;
            BigDecimal movePct = s.price.subtract(livePrice).abs()
                    .divide(livePrice, PriceMath.MC).multiply(new BigDecimal(100));
            if (movePct.compareTo(repricePct) >= 0) {
                relocks.add(new Action(Kind.RELOCK, s, live,
                        "moved " + movePct.setScale(3, RoundingMode.HALF_UP) + "%"));
            }
        }

        // Priority when the cycle budget is tight: fix MISPRICED quotes first (they are the
        // live risk — someone can trade against them right now), then restore MISSING rungs,
        // and only then tidy up rungs we no longer want. Appending cancels first would let a
        // handful of them starve every create, tearing the ladder down without rebuilding it.
        List<Action> actions = new ArrayList<>();
        actions.addAll(relocks);
        actions.addAll(creates);
        actions.addAll(cancels);

        if (maxActions > 0 && actions.size() > maxActions) {
            return new ArrayList<>(actions.subList(0, maxActions));
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

package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The market maker's brain: turn a reference mid into a desired ladder, then work out the
 * SMALLEST set of on-chain actions that moves the live book to match it.
 *
 * Pure and side-effect free so it can be unit-tested exhaustively — every decision here spends
 * real money (each action is a transaction with proof-of-work on a phone), so the arithmetic
 * and the diffing are worth proving separately from the plumbing that executes them.
 *
 * The reconciliation exploits something the apps this is modelled on could not do: PandaDEX's
 * V5 covenant lets an owner RE-LOCK an order in place, changing its price in ONE transaction
 * without the funds ever leaving the book. So a reprice is a RELOCK, not a cancel-then-repost —
 * there is no window where the level is missing and no way for a failed re-post to leave the
 * maker out of the market.
 */
public final class MakerLadder {

    /** Hard cap per side. Every level is a separate on-chain order to post, renew and reprice. */
    public static final int MAX_LEVELS = 6;

    // ---------------------------------------------------------------- config

    /** One rung: how far from the mid, and how much size to show there. */
    public static final class Level {
        public final BigDecimal offsetPct;   // e.g. 0.20 = 0.20% away from mid
        public final BigDecimal sizeMinima;

        public Level(BigDecimal offsetPct, BigDecimal sizeMinima) {
            this.offsetPct = offsetPct;
            this.sizeMinima = sizeMinima;
        }
    }

    public static final class Config {
        public final List<Level> levels;     // innermost first
        public final BigDecimal skewPct;     // + shifts the whole ladder UP (bullish)
        public final BigDecimal repricePct;  // don't touch anything until the mid moves this far
        public final boolean bids, asks;

        public Config(List<Level> levels, BigDecimal skewPct, BigDecimal repricePct,
                      boolean bids, boolean asks) {
            this.levels = levels;
            this.skewPct = skewPct;
            this.repricePct = repricePct;
            this.bids = bids;
            this.asks = asks;
        }
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
     * Build the ladder around a reference mid.
     *
     * A bid sits BELOW the mid and an ask ABOVE it; skew shifts both together, so a positive
     * skew quotes higher on both sides (you want to end up longer). Widening multiplies every
     * offset — used to quote worse as the price feed ages rather than blindly standing on a
     * stale number.
     */
    public static List<Slot> desired(BigDecimal mid, Config cfg, BigDecimal widenFactor) {
        List<Slot> out = new ArrayList<>();
        if (mid == null || mid.signum() <= 0 || cfg == null) return out;
        BigDecimal widen = (widenFactor == null || widenFactor.signum() <= 0)
                ? BigDecimal.ONE : widenFactor;
        BigDecimal hundred = new BigDecimal(100);
        BigDecimal skewed = mid.multiply(BigDecimal.ONE.add(
                cfg.skewPct.divide(hundred, PriceMath.MC)), PriceMath.MC);

        int n = Math.min(cfg.levels.size(), MAX_LEVELS);
        for (int i = 0; i < n; i++) {
            Level lv = cfg.levels.get(i);
            if (lv.sizeMinima.signum() <= 0) continue;
            BigDecimal off = lv.offsetPct.multiply(widen, PriceMath.MC)
                    .divide(hundred, PriceMath.MC);
            if (cfg.bids) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.subtract(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.DOWN);
                if (p.signum() > 0) out.add(new Slot("B" + (i + 1), false, p, lv.sizeMinima));
            }
            if (cfg.asks) {
                BigDecimal p = skewed.multiply(BigDecimal.ONE.add(off), PriceMath.MC)
                        .setScale(PriceMath.DISPLAY_DP, RoundingMode.UP);
                out.add(new Slot("A" + (i + 1), true, p, lv.sizeMinima));
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

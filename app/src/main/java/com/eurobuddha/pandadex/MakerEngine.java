package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the market-maker cycle: read the reference price, decide the minimal set of on-chain
 * actions, execute them one at a time.
 *
 * Two things bound this, and both are about real cost rather than tidiness:
 *  - every action is a transaction with proof-of-work ground out on the phone, so cycles are
 *    rate-limited, capped, and skipped entirely until the reference mid has actually moved;
 *  - actions are issued STRICTLY SEQUENTIALLY because the node executes one command at a time.
 *
 * The safety property that makes it acceptable to leave running unattended: when the price feed
 * goes stale the ladder comes OFF the book rather than standing on a number we no longer
 * believe. Quoting on a stale price is how a market maker gets picked off.
 */
public final class MakerEngine {

    /** Never run the cycle more often than this, however excited the price feed gets. */
    private static final long MIN_CYCLE_MS = 60_000;
    /** Most on-chain actions in one cycle — the rest wait for the next pass. */
    private static final int MAX_ACTIONS_PER_CYCLE = 4;

    public interface Listener {
        void onMakerState(String message);
    }

    private final MakerConfig cfg;
    private final DexTxn txn;
    private long lastCycleMs = 0;
    private boolean working = false;
    /** Deferred work — a withdraw asked for while a chain was mid-flight. Without this the
     *  request is silently dropped: onBook won't run once disarmed, so the ladder would stay
     *  on the book after the user pressed Withdraw. */
    private Runnable pendingOnIdle;

    public MakerEngine(MakerConfig cfg, DexTxn txn) {
        this.cfg = cfg;
        this.txn = txn;
    }

    public boolean isWorking() { return working; }

    /** Run {@code r} now if idle, otherwise the instant the current chain finishes. */
    public void runWhenIdle(Runnable r) {
        if (r == null) return;
        if (!working) { r.run(); return; }
        pendingOnIdle = r;
    }

    /** Release any work that was waiting for the chain to finish. */
    private void drainIdle() {
        Runnable r = pendingOnIdle;
        pendingOnIdle = null;
        if (r != null) r.run();
    }

    /** Called on every book update. Cheap and returns immediately unless there is work to do. */
    public void onBook(Map<String, Order5> book, Set<String> myKeys, long chainBlock, Listener l) {
        if (!cfg.armed || working) return;
        long now = System.currentTimeMillis();
        if (now - lastCycleMs < MIN_CYCLE_MS) return;

        BigDecimal mid = BigDecimal.ZERO;
        BigDecimal widen = BigDecimal.ONE;
        if (cfg.pegged) {
            // A degenerate pegged config (no step, or no size on either side) means
            // MISCONFIGURED, never "cancel everything". commit() runs on field blur, so a
            // cleared field mid-edit reaches us as a zero — treating that as an empty desired
            // ladder would tear the live ladder down. Deliberate teardown is Disarm/Withdraw.
            if (cfg.stepPct == null || cfg.stepPct.signum() <= 0
                    || (cfg.askSize.signum() <= 0 && cfg.bidSize.signum() <= 0)) return;

            // The feed only matters while PEGGED — a manual ladder quotes exactly what was
            // typed, so its prices can neither go stale nor need repricing to a moving mid.
            MarketPrice.refreshAsync();

            // ---- feed too old to quote on: take the ladder off the book ----
            if (MarketPrice.mustWithdraw()) {
                List<Order5> live = liveLadderOrders(book, myKeys);
                if (!live.isEmpty()) {
                    lastCycleMs = now;
                    if (l != null) l.onMakerState("Price feed stale — withdrawing the ladder");
                    cancelAllLadder(live, 0, l);
                }
                return;
            }

            mid = BigDecimal.valueOf(MarketPrice.mid());
            if (mid.signum() <= 0) return;

            // ---- nothing to do until the reference actually moved ----
            boolean haveLadder = !liveLadderOrders(book, myKeys).isEmpty();
            if (haveLadder && !MakerLadder.worthRepricing(cfg.lastActedMid, mid, cfg.repricePct)) return;

            widen = BigDecimal.valueOf(MarketPrice.widenFactor());
        }
        List<MakerLadder.Slot> desired = MakerLadder.desired(mid, cfg.toLadderConfig(), widen);

        Map<String, Order5> liveBySlot = new HashMap<>();
        Map<String, Order5> byOrderId = new HashMap<>();
        for (Order5 o : book.values()) if (o.isMine(myKeys)) byOrderId.put(o.orderId, o);
        for (Map.Entry<String, String> e : cfg.slotOrderIds.entrySet()) {
            Order5 o = byOrderId.get(e.getValue());
            if (o != null) liveBySlot.put(e.getKey(), o);
        }

        // A rung that has been partly taken is a working position — leave it be.
        // This MUST compare against the size we posted: an order cannot tell you it shrank,
        // only what it holds now. Comparing `locked` to `minimaAmount()` (as this once did)
        // is trivially equal for sells and never equal for buys, which silently froze the
        // entire bid side of the ladder.
        Set<String> partial = new HashSet<>();
        Map<String, BigDecimal> postedSizes = new HashMap<>();
        for (Map.Entry<String, Order5> e : liveBySlot.entrySet()) {
            BigDecimal posted = cfg.postedSizeFor(e.getKey());
            Order5 o = e.getValue();
            if (posted != null) {
                postedSizes.put(e.getKey(), posted);
                if (o.minimaAmount().compareTo(posted) < 0) partial.add(o.coinid);
            }
        }

        // Unpegged the rungs are the user's EXPLICIT prices — the reprice threshold is a peg
        // concept, and filtering typed prices through it silently ignores small edits. A zero
        // threshold selects reconcile's exact mode (compares at display precision).
        BigDecimal threshold = cfg.pegged ? cfg.repricePct : BigDecimal.ZERO;
        List<MakerLadder.Action> actions = MakerLadder.reconcile(desired, liveBySlot,
                threshold, partial, postedSizes, MAX_ACTIONS_PER_CYCLE);
        if (actions.isEmpty()) return;

        lastCycleMs = now;
        if (l != null) l.onMakerState("Maker: " + actions.size() + " adjustment"
                + (actions.size() == 1 ? "" : "s")
                + (mid.signum() > 0 ? " at mid " + PriceMath.fmtPrice(mid) : ""));
        // lastActedMid is committed in run()'s terminal branch, and only if something actually
        // posted — recording it up front meant a cycle where every action failed still counted
        // as "acted at this mid", suppressing retries until the market moved again.
        run(actions, 0, mid, 0, l);
    }

    /**
     * Execute one action at a time — the node runs a single command at a time and each of these
     * grinds proof-of-work.
     *
     * Every path advances EXACTLY ONCE. That is not a stylistic preference: DexTxn's validation
     * failures both invoke the callback AND return a null order id, so a caller that reacts to
     * both signals starts two concurrent chains down the same list. Each subsequent action then
     * runs twice, and a duplicated CREATE posts a second on-chain order whose id is immediately
     * overwritten in the slot map — leaving real funds committed to an order withdraw can never
     * find. The `advanced` latch makes the double signal harmless.
     */
    private void run(List<MakerLadder.Action> actions, int idx, BigDecimal mid, int posted,
                     Listener l) {
        if (idx >= actions.size()) {
            working = false;
            if (posted > 0) {
                // A manual (unpegged) cycle carries no reference mid — don't record a zero,
                // it would read as "never acted" and defeat the reprice gate after a re-peg.
                if (mid != null && mid.signum() > 0) cfg.lastActedMid = mid;
                cfg.save();
            }
            if (l != null) l.onMakerState(posted > 0
                    ? "Maker: ladder up to date"
                    : "Maker: no adjustment could be posted — will retry");
            drainIdle();
            return;
        }
        working = true;
        MakerLadder.Action a = actions.get(idx);
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                if (advanced[0]) return;
                advanced[0] = true;
                run(actions, idx + 1, mid, posted + 1, l);
            }
            @Override public void onFailed(String message) {
                if (advanced[0]) return;
                advanced[0] = true;
                // one rung failing must not stall the rest — the next cycle retries it
                if (l != null) l.onMakerState("Maker: " + a.kind + " failed — " + message);
                run(actions, idx + 1, mid, posted, l);
            }
        };

        // An exception escaping here would leave `working` stuck true, and onBook refuses to
        // run while working — the maker would be dead until the process restarted, with a
        // ladder still live on the book. Treat a throw as this rung failing and carry on.
        try {
            switch (a.kind) {
                case CREATE: {
                    String orderId = txn.createOrder(!a.slot.sell, a.slot.sizeMinima, a.slot.price,
                            true, minRemainderFor(a.slot), once);
                    // A null id means createOrder rejected it synchronously — and it already
                    // called onFailed, which advanced us. Only record a slot that was accepted.
                    if (orderId != null) cfg.rememberSlot(a.slot.id, orderId, a.slot.sizeMinima);
                    break;
                }
                case RELOCK: {
                    // atomic reprice: the funds never leave the book, so a rung can never be
                    // dropped by a failed re-post the way a cancel-then-recreate could
                    Order5 o = a.order;
                    BigDecimal newWant = o.sell
                            ? PriceMath.up(o.locked.multiply(a.slot.price, PriceMath.MC), PriceMath.USDT_DP)
                            : PriceMath.down(o.locked.divide(a.slot.price, PriceMath.MINIMA_DP,
                                    java.math.RoundingMode.DOWN), PriceMath.MINIMA_DP);
                    txn.relock(o, newWant, once);
                    break;
                }
                case CANCEL: {
                    cfg.forgetSlotByOrderId(a.order.orderId);
                    txn.cancel(a.order, once);
                    break;
                }
            }
        } catch (Throwable t) {
            if (!advanced[0]) {
                advanced[0] = true;
                if (l != null) l.onMakerState("Maker: " + a.kind + " errored — " + t);
                run(actions, idx + 1, mid, posted, l);
            }
        }
    }

    /**
     * The anti-dust floor to post with a rung, scaled to its size.
     *
     * Capped at HALF the rung so a partial fill is always possible. A flat 1 MINIMA was
     * rejected outright for any rung at or below that size, and merely raising the floor to
     * the rung's own size would have replaced that with a quieter surprise: an order that
     * posts fine but can only ever be taken whole, with nothing on screen saying so.
     */
    static BigDecimal minRemainderFor(MakerLadder.Slot slot) {
        BigDecimal fivePct = slot.sizeMinima.multiply(new BigDecimal("0.05"), PriceMath.MC);
        BigDecimal floor = fivePct.max(PriceMath.MIN_ORDER_MINIMA);
        BigDecimal half = slot.sizeMinima.divide(new BigDecimal(2), PriceMath.MINIMA_DP,
                java.math.RoundingMode.DOWN);
        return PriceMath.down(floor.min(half), PriceMath.MINIMA_DP);
    }

    /** Cancel every rung we still have on the book (withdraw / disarm). */
    public void cancelAllLadder(List<Order5> live, int idx, Listener l) {
        if (idx >= live.size()) {
            working = false;
            cfg.clearSlots();
            if (l != null) l.onMakerState("Maker: ladder withdrawn");
            drainIdle();
            return;
        }
        working = true;
        Order5 o = live.get(idx);
        cfg.forgetSlotByOrderId(o.orderId);
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String t) {
                if (advanced[0]) return; advanced[0] = true;
                cancelAllLadder(live, idx + 1, l);
            }
            @Override public void onFailed(String m) {
                if (advanced[0]) return; advanced[0] = true;
                cancelAllLadder(live, idx + 1, l);
            }
        };
        try {
            txn.cancel(o, once);
        } catch (Throwable t) {
            // a throw must not strand the withdraw half-done with `working` stuck true
            if (!advanced[0]) { advanced[0] = true; cancelAllLadder(live, idx + 1, l); }
        }
    }

    public List<Order5> liveLadderOrders(Map<String, Order5> book, Set<String> myKeys) {
        List<Order5> out = new ArrayList<>();
        Set<String> ids = new HashSet<>(cfg.slotOrderIds.values());
        for (Order5 o : book.values()) {
            if (o.isMine(myKeys) && ids.contains(o.orderId)) out.add(o);
        }
        return out;
    }

}

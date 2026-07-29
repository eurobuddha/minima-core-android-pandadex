package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the market-maker cycle: read the reference price, decide the minimal set of on-chain
 * actions, execute them one at a time.
 *
 * The slot LIFECYCLE is what makes this safe on a chain with ~50s blocks. An order the node
 * accepted takes PoW + mempool + at least one block to surface in the confirmed book, so a
 * recorded slot whose order is not visible yet is IN FLIGHT, not missing:
 *
 *   DESIRED → (create sent, node accepted) → SENT → (seen in book) → LIVE
 *      ↑______ (patience expired: forget & retry) ____|      | (relock sent → SETTLING)
 *                                                           ↓ (cancel sent → tombstoned)
 *
 * 0.2.6 had no such memory of time: every not-yet-visible order read as "missing", was
 * re-created, and the fresh id overwrote the slot record — orphaning real on-chain funds
 * that withdraw could never find again (observed live, twice). Slots are recorded only in
 * the ASYNC success callback: a send the node rejected (typically unfunded — see the
 * per-side create budget in {@link MakerLadder.Budget}) records nothing and simply retries.
 *
 * Cancel tombstones are the same discipline for the reverse direction: every orderId we have
 * asked to cancel — or decided must die while it was still unconfirmed — stays tombstoned
 * until its coin is verifiably gone, and {@link #sweepTombstones} keeps re-cancelling it
 * whenever it (re)surfaces. That is what finally catches late-confirming orphans.
 *
 * Two things bound the cycle, both about real cost: every action is a transaction with
 * proof-of-work ground out on the phone, so cycles are rate-limited and capped; and actions
 * are issued STRICTLY SEQUENTIALLY because the node executes one command at a time.
 *
 * The safety property that makes it acceptable to leave running unattended: when the price
 * feed goes stale a PEGGED ladder comes OFF the book rather than standing on a number we no
 * longer believe. Quoting on a stale price is how a market maker gets picked off.
 */
public final class MakerEngine {

    /** Never run the cycle more often than this, however excited the price feed gets. */
    private static final long MIN_CYCLE_MS = 60_000;
    /** Most on-chain actions in one cycle — the rest wait for the next pass. */
    private static final int MAX_ACTIONS_PER_CYCLE = 4;
    /** Creates per SIDE per cycle: a create funds via a wallet `send`, and two same-side
     *  sends in one cycle fight over the same funding coins until change confirms. */
    static final int MAX_CREATES_PER_SIDE = 1;
    /** How long (blocks, ~50s each) an accepted send may stay invisible before we call it
     *  dead and retry. Send + PoW + mempool + 1 block + scan throttle fits comfortably. */
    static final long PATIENCE_BLOCKS = 4;
    /** A tombstoned orderId absent from the book this long after the last cancel attempt is
     *  finished business — the cancel mined (or the send never made an order). */
    static final long TOMBSTONE_EXPIRE_BLOCKS = 10;

    public interface Listener {
        void onMakerState(String message);
        /** A create the node ACCEPTED (mining now) — drive optimistic UI from these. */
        default void onCreateSent(MakerLadder.Slot slot, String orderId) {}
        default void onCancelSent(Order5 order) {}
        default void onRelockSent(Order5 order, BigDecimal newPrice) {}
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
            // A degenerate pegged config (no step, or no sized rung anywhere — e.g. a field
            // cleared mid-edit, committed on blur) means MISCONFIGURED, never "cancel
            // everything". Deliberate teardown is the Withdraw button.
            if (cfg.stepPct == null || cfg.stepPct.signum() <= 0
                    || (!MakerLadder.hasSizedRung(cfg.asks) && !MakerLadder.hasSizedRung(cfg.bids)))
                return;

            // The feed only matters while PEGGED — a manual ladder quotes exactly what was
            // typed, so its prices can neither go stale nor need repricing to a moving mid.
            MarketPrice.refreshAsync();

            // ---- feed too old to quote on: take the ladder off the book ----
            // withdrawAll, NOT cancelAllLadder: this fires unattended, and a rung still mining
            // is invisible to liveLadderOrders. Cancelling only what we can see and then
            // clearing the slot map would orphan the in-flight ones — the exact failure the
            // tombstones exist to prevent, in the one path nobody is watching.
            if (MarketPrice.mustWithdraw()) {
                if (!cfg.slots.isEmpty()) {
                    lastCycleMs = now;
                    if (l != null) l.onMakerState("Price feed stale — withdrawing the ladder");
                    withdrawAll(book, myKeys, chainBlock, l);
                }
                return;
            }

            mid = BigDecimal.valueOf(MarketPrice.mid());
            if (mid.signum() <= 0) return;
            widen = BigDecimal.valueOf(MarketPrice.widenFactor());
        }
        List<MakerLadder.Slot> desired = MakerLadder.desired(mid, cfg.toLadderConfig(), widen);

        // ---- slot bookkeeping: stamp legacy records, expire the patient dead ----
        // Runs BEFORE the empty-ladder bail-out: a config that currently quotes nothing still
        // has records that must age out honestly, or they linger forever as phantom rungs.
        Map<String, Order5> byOrderId = new HashMap<>();
        for (Order5 o : book.values()) {
            if (o.isMine(myKeys) && !cfg.cancelTombstones.containsKey(o.orderId)) {
                byOrderId.put(o.orderId, o);
            }
        }
        boolean dirty = false;
        for (Iterator<Map.Entry<String, MakerConfig.SlotRec>> it = cfg.slots.entrySet().iterator();
             it.hasNext(); ) {
            MakerConfig.SlotRec r = it.next().getValue();
            if (r.sentBlock <= 0) {
                // legacy/restart record of unknown age — grant a fresh patience window rather
                // than guessing: neither duplicates (waits) nor orphans (expires eventually)
                r.sentBlock = chainBlock;
                dirty = true;
                continue;
            }
            if (!byOrderId.containsKey(r.orderId)
                    && chainBlock - r.sentBlock >= PATIENCE_BLOCKS) {
                // the send died (or the order filled before we ever saw it) — either way the
                // record is history; the rung reads DESIRED again and is re-created cleanly
                it.remove();
                dirty = true;
            }
        }
        if (dirty) cfg.save();
        // Nothing to quote (every rung blank, or a manual ladder with no valid prices) is
        // MISCONFIGURED, not "cancel everything" — deliberate teardown is the Withdraw button.
        if (desired.isEmpty()) return;

        Map<String, Order5> liveBySlot = new HashMap<>();
        Set<String> settling = new HashSet<>();
        for (Map.Entry<String, MakerConfig.SlotRec> e : cfg.slots.entrySet()) {
            MakerConfig.SlotRec r = e.getValue();
            Order5 o = byOrderId.get(r.orderId);
            if (o == null) {
                settling.add(e.getKey());        // SENT — in flight, emphatically not missing
            } else {
                liveBySlot.put(e.getKey(), o);
                if (r.lastActionBlock > 0 && chainBlock - r.lastActionBlock < PATIENCE_BLOCKS) {
                    settling.add(e.getKey());    // relock in flight — old coin still visible
                }
            }
        }

        // A rung that has been partly taken is a working position — leave it be.
        // This MUST compare against the size we posted: an order cannot tell you it shrank,
        // only what it holds now.
        Set<String> partial = new HashSet<>();
        Set<String> renew = new HashSet<>();
        Map<String, BigDecimal> postedSizes = new HashMap<>();
        for (Map.Entry<String, Order5> e : liveBySlot.entrySet()) {
            BigDecimal posted = cfg.postedSizeFor(e.getKey());
            if (posted != null) {
                postedSizes.put(e.getKey(), posted);
                if (e.getValue().minimaAmount().compareTo(posted) < 0) partial.add(e.getValue().coinid);
            }
            // the maker renews its OWN rungs — DexProcessor now skips them, so nothing else will
            if (e.getValue().renewDue(chainBlock)) renew.add(e.getKey());
        }

        // ---- the reprice gate guards COMPLETE ladders only. A half-posted, failed or
        // settling ladder must keep cycling to finish itself — 0.2.6 gated on "any rung
        // live", which froze a 1-of-8 ladder until the market moved a whole threshold.
        boolean complete = settling.isEmpty() && cfg.cancelTombstones.isEmpty();
        if (complete) {
            for (MakerLadder.Slot s : desired) {
                if (!liveBySlot.containsKey(s.id)) { complete = false; break; }
            }
        }
        if (cfg.pegged && complete
                && !MakerLadder.worthRepricing(cfg.lastActedMid, mid, cfg.repricePct)) return;

        // Unpegged the rungs are the user's EXPLICIT prices — a zero threshold selects
        // reconcile's exact mode (any difference at display precision relocks).
        BigDecimal threshold = cfg.pegged ? cfg.repricePct : BigDecimal.ZERO;
        List<MakerLadder.Action> actions = MakerLadder.reconcile(desired, liveBySlot, settling,
                renew, threshold, partial, postedSizes,
                new MakerLadder.Budget(MAX_ACTIONS_PER_CYCLE, MAX_CREATES_PER_SIDE));
        if (actions.isEmpty()) return;

        lastCycleMs = now;
        if (l != null) l.onMakerState("Maker: " + actions.size() + " adjustment"
                + (actions.size() == 1 ? "" : "s")
                + (mid.signum() > 0 ? " at mid " + PriceMath.fmtPrice(mid) : ""));
        // lastActedMid is committed in run()'s terminal branch, and only if something actually
        // posted — recording it up front meant a cycle where every action failed still counted
        // as "acted at this mid", suppressing retries until the market moved again.
        run(actions, 0, mid, 0, chainBlock, l);
    }

    /**
     * Execute one action at a time — the node runs a single command at a time and each of these
     * grinds proof-of-work.
     *
     * Every path advances EXACTLY ONCE. That is not a stylistic preference: DexTxn's validation
     * failures both invoke the callback AND return a null order id, so a caller that reacts to
     * both signals starts two concurrent chains down the same list. The `advanced` latch makes
     * the double signal harmless.
     *
     * ALL bookkeeping lives in the ASYNC success callback: a slot is recorded (CREATE), its
     * settling window restarted (RELOCK) or its tombstone raised (CANCEL) only once the node
     * ACCEPTED the transaction. A rejected send leaves no record and the next cycle retries.
     */
    private void run(List<MakerLadder.Action> actions, int idx, BigDecimal mid, int posted,
                     long chainBlock, Listener l) {
        if (idx >= actions.size()) {
            working = false;
            if (posted > 0) {
                // A manual (unpegged) cycle carries no reference mid — don't record a zero,
                // it would read as "never acted" and defeat the reprice gate after a re-peg.
                if (mid != null && mid.signum() > 0) cfg.lastActedMid = mid;
                cfg.save();
            }
            if (l != null) l.onMakerState(posted > 0
                    ? "Maker: " + posted + " action" + (posted == 1 ? "" : "s") + " accepted — mining now"
                    : "Maker: no adjustment could be posted — will retry");
            drainIdle();
            return;
        }
        working = true;
        MakerLadder.Action a = actions.get(idx);
        final String createOid = a.kind == MakerLadder.Kind.CREATE ? DexTxn.newOrderId() : null;
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String txpowid) {
                if (advanced[0]) return;
                advanced[0] = true;
                switch (a.kind) {
                    case CREATE:
                        cfg.rememberSlot(a.slot.id, createOid, a.slot.sizeMinima, chainBlock);
                        if (l != null) l.onCreateSent(a.slot, createOid);
                        break;
                    case RELOCK:
                        cfg.noteSlotAction(a.slot.id, chainBlock);
                        if (l != null) l.onRelockSent(a.order, a.slot.price);
                        break;
                    case CANCEL:
                        cfg.forgetSlotByOrderId(a.order.orderId);
                        cfg.tombstone(a.order.orderId, chainBlock);
                        if (l != null) l.onCancelSent(a.order);
                        break;
                }
                run(actions, idx + 1, mid, posted + 1, chainBlock, l);
            }
            @Override public void onFailed(String message) {
                if (advanced[0]) return;
                advanced[0] = true;
                // one rung failing must not stall the rest — the next cycle retries it
                if (l != null) l.onMakerState("Maker: " + a.kind + " failed — " + message);
                run(actions, idx + 1, mid, posted, chainBlock, l);
            }
        };

        // An exception escaping here would leave `working` stuck true, and onBook refuses to
        // run while working — the maker would be dead until the process restarted, with a
        // ladder still live on the book. Treat a throw as this rung failing and carry on.
        try {
            switch (a.kind) {
                case CREATE:
                    txn.createOrder(!a.slot.sell, a.slot.sizeMinima, a.slot.price,
                            true, minRemainderFor(a.slot), createOid, once);
                    break;
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
                case CANCEL:
                    txn.cancel(a.order, once);
                    break;
            }
        } catch (Throwable t) {
            if (!advanced[0]) {
                advanced[0] = true;
                if (l != null) l.onMakerState("Maker: " + a.kind + " errored — " + t);
                run(actions, idx + 1, mid, posted, chainBlock, l);
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

    // ---------------------------------------------------------------- withdraw

    /**
     * Withdraw the whole ladder: cancel every rung visible on the book AND tombstone every
     * record whose order has not surfaced yet — when a late-confirming order finally appears,
     * {@link #sweepTombstones} cancels it. 0.2.6 only cancelled what it could see, which is
     * exactly how orphans survived a withdraw.
     */
    public void withdrawAll(Map<String, Order5> book, Set<String> myKeys, long chainBlock,
                            Listener l) {
        List<Order5> live = liveLadderOrders(book, myKeys);
        Set<String> liveIds = new HashSet<>();
        for (Order5 o : live) liveIds.add(o.orderId);
        for (MakerConfig.SlotRec r : cfg.slots.values()) {
            // lastAttempt 0 = never tried, so the sweep acts the instant it surfaces — and the
            // expiry clock still runs from NOW, giving it the full window to confirm
            if (!liveIds.contains(r.orderId)) cfg.tombstone(r.orderId, chainBlock, 0);
        }
        cancelAllLadder(live, 0, chainBlock, l);
    }

    /** Cancel every rung we still have on the book (withdraw / stale-feed retreat). */
    public void cancelAllLadder(List<Order5> live, int idx, long chainBlock, Listener l) {
        if (idx >= live.size()) {
            working = false;
            cfg.clearSlots();
            if (l != null) l.onMakerState(live.isEmpty()
                    ? "Maker: nothing on the book to cancel"
                    : "Maker: " + live.size() + " cancel" + (live.size() == 1 ? "" : "s")
                    + " sent — orders leave the book as blocks confirm them");
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
                cfg.tombstone(o.orderId, chainBlock, chainBlock);   // sent — pace the next try
                if (l != null) l.onCancelSent(o);
                cancelAllLadder(live, idx + 1, chainBlock, l);
            }
            @Override public void onFailed(String m) {
                if (advanced[0]) return; advanced[0] = true;
                // condemn it anyway, with no attempt recorded — the sweep retries immediately
                cfg.tombstone(o.orderId, chainBlock, 0);
                cancelAllLadder(live, idx + 1, chainBlock, l);
            }
        };
        try {
            txn.cancel(o, once);
        } catch (Throwable t) {
            // a throw must not strand the withdraw half-done with `working` stuck true
            if (!advanced[0]) {
                advanced[0] = true;
                cfg.tombstone(o.orderId, chainBlock, 0);
                cancelAllLadder(live, idx + 1, chainBlock, l);
            }
        }
    }

    /**
     * Chase tombstoned orders on EVERY book update, armed or not. A tombstoned orderId that
     * (re)surfaces gets a fresh cancel — at most one per pass, re-sent only after patience —
     * and a tombstone whose coin has stayed gone long enough is finished business.
     */
    public void sweepTombstones(Map<String, Order5> book, Set<String> myKeys, long chainBlock,
                                Listener l) {
        if (working || cfg.cancelTombstones.isEmpty()) return;
        Map<String, Order5> mineById = new HashMap<>();
        for (Order5 o : book.values()) if (o.isMine(myKeys)) mineById.put(o.orderId, o);

        List<String> done = new ArrayList<>();
        Order5 target = null;
        for (Map.Entry<String, MakerConfig.Tomb> e : cfg.cancelTombstones.entrySet()) {
            MakerConfig.Tomb t = e.getValue();
            Order5 o = mineById.get(e.getKey());
            if (o == null) {
                // absent long enough after CONDEMNATION (not after the last attempt) that the
                // cancel must have mined — or the order never existed
                if (chainBlock - t.createdBlock >= TOMBSTONE_EXPIRE_BLOCKS) done.add(e.getKey());
            } else if (target == null
                    && (t.lastAttemptBlock <= 0 || chainBlock - t.lastAttemptBlock >= PATIENCE_BLOCKS)) {
                target = o;                      // never tried, or the last try had its chance
            }
        }
        for (String id : done) cfg.clearTombstone(id);
        if (target == null) return;

        final Order5 o = target;
        cfg.tombstone(o.orderId, chainBlock, chainBlock);   // pace the next attempt
        if (l != null) l.onMakerState("Maker: cancelling a late order (" +
                (o.sell ? "ask" : "bid") + " " + PriceMath.fmtPrice(o.price()) + ")");
        working = true;
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String t) {
                if (advanced[0]) return; advanced[0] = true;
                working = false;
                if (l != null) l.onCancelSent(o);
                drainIdle();
            }
            @Override public void onFailed(String m) {
                if (advanced[0]) return; advanced[0] = true;
                working = false;
                drainIdle();
            }
        };
        try {
            txn.cancel(o, once);
        } catch (Throwable t) {
            if (!advanced[0]) { advanced[0] = true; working = false; drainIdle(); }
        }
    }

    /** The orderIds this engine owns — the generic renewer must leave them alone. */
    public Set<String> ownedOrderIds() {
        Set<String> ids = new HashSet<>();
        for (MakerConfig.SlotRec r : cfg.slots.values()) ids.add(r.orderId);
        ids.addAll(cfg.cancelTombstones.keySet());   // condemned: never renew what we're killing
        return ids;
    }

    public List<Order5> liveLadderOrders(Map<String, Order5> book, Set<String> myKeys) {
        List<Order5> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (MakerConfig.SlotRec r : cfg.slots.values()) ids.add(r.orderId);
        for (Order5 o : book.values()) {
            if (o.isMine(myKeys) && ids.contains(o.orderId)) out.add(o);
        }
        return out;
    }

}

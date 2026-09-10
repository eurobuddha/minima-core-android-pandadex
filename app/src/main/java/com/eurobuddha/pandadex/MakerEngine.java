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
 *      ↑______ (verified resolution or manual review) ____|      | (relock sent → SETTLING)
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

    /** As in Notifier.alert, display failure must not own financial completion. Catch only
     * observer RuntimeExceptions: journal/configuration/transaction failures stay outside. */
    private static void observe(Listener listener, java.util.function.Consumer<Listener> event) {
        if (listener == null) return;
        try { event.accept(listener); }
        catch (RuntimeException displayUnavailable) { /* Next render reads the retained state. */ }
    }
    private static void notifyState(Listener listener, String message) {
        observe(listener, target -> target.onMakerState(message));
    }
    private static void notifyCreate(Listener listener, MakerLadder.Slot slot, String orderId) {
        observe(listener, target -> target.onCreateSent(slot, orderId));
    }
    private static void notifyCancel(Listener listener, Order5 order) {
        observe(listener, target -> target.onCancelSent(order));
    }
    private static void notifyRelock(Listener listener, Order5 order, BigDecimal price) {
        observe(listener, target -> target.onRelockSent(order, price));
    }

    private final MakerConfig cfg;
    private final DexTxn txn;
    private long lastCycleMs = -1;
    private final java.util.function.LongSupplier clock;
    private static boolean working = false;
    /** Deferred work — a withdraw asked for while a chain was mid-flight. Without this the
     *  request is silently dropped: onBook won't run once disarmed, so the ladder would stay
     *  on the book after the user pressed Withdraw. */
    private static Runnable pendingOnIdle;
    private static SerialQueue idleQueue = new SerialQueue();

    private final java.util.function.Predicate<Order5> ownership;

    /** Pure test adapter; production hosts supply freshly derived wallet ownership. */
    MakerEngine(MakerConfig cfg, DexTxn txn) { this(cfg, txn, null, System::currentTimeMillis); }

    public MakerEngine(MakerConfig cfg, DexTxn txn, java.util.function.Predicate<Order5> ownership) {
        this(cfg,txn,ownership,android.os.SystemClock::elapsedRealtime);
    }
    MakerEngine(MakerConfig cfg,DexTxn txn,java.util.function.Predicate<Order5> ownership,
                java.util.function.LongSupplier clock) {
        this.clock=clock;
        this.ownership = ownership;
        this.cfg = cfg;
        this.txn = txn;
    }

    private boolean owns(Order5 o, Set<String> keys) {
        return ownership == null ? o.isMine(keys) : ownership.test(o);
    }

    public boolean isWorking() { return working; }

    /** Let the next book update act immediately instead of waiting out the cycle gate. For a
     *  DELIBERATE edit only — the gate exists to stop the maker burning proof-of-work on every
     *  tick, so nothing automatic may call this. */
    public void nudge() { lastCycleMs = -1; }

    /**
     * What applying the current config to the live book would cost, as {relocks, reposts,
     * creates, cancels}. Uses the real reconciliation with no budget, so the preview cannot
     * drift from what the engine will actually do.
     */
    public int[] previewEdits(Map<String, Order5> book, Set<String> myKeys, long chainBlock,
                              BigDecimal mid) {
        List<MakerLadder.Slot> desired = MakerLadder.desired(mid, cfg.toLadderConfig(),
                BigDecimal.ONE);
        Map<String, Order5> byOrderId = new HashMap<>();
        for (Order5 o : book.values()) {
            if (owns(o, myKeys) && !cfg.cancelTombstones.containsKey(o.orderId)) {
                byOrderId.put(o.orderId, o);
            }
        }
        Map<String, Order5> liveBySlot = new HashMap<>();
        Map<String, BigDecimal> postedSizes = new HashMap<>();
        Set<String> protectedPositions = new HashSet<>();
        for (Map.Entry<String, MakerConfig.SlotRec> e : cfg.slots.entrySet()) {
            Order5 o = byOrderId.get(e.getValue().orderId);
            if (o == null) continue;
            liveBySlot.put(e.getKey(), o);
            if(MakerPosition.preserve(e.getValue(),o))protectedPositions.add(o.coinid);
            BigDecimal posted = cfg.postedSizeFor(e.getKey());
            if (posted != null) postedSizes.put(e.getKey(), posted);
        }
        BigDecimal threshold = cfg.pegged ? cfg.repricePct : BigDecimal.ZERO;
        List<MakerLadder.Action> actions = MakerLadder.reconcile(desired, liveBySlot, null, null,
                threshold, protectedPositions, postedSizes,
                new MakerLadder.Budget(0, Integer.MAX_VALUE));
        // Count by REASON, not by kind: a resize emits a CANCEL+CREATE pair for one rung, and
        // pairing them by arithmetic would miscount an unrelated new rung against an unrelated
        // removed one. reconcile() tags the pair "size changed".
        int relocks = 0, reposts = 0, creates = 0, cancels = 0;
        for (MakerLadder.Action a : actions) {
            boolean resize = "size changed".equals(a.reason);
            if (a.kind == MakerLadder.Kind.RELOCK) relocks++;
            else if (resize) { if (a.kind == MakerLadder.Kind.CREATE) reposts++; }
            else if (a.kind == MakerLadder.Kind.CREATE) creates++;
            else cancels++;
        }
        return new int[]{relocks, reposts, creates, cancels};
    }

    /** Preserve deferred requests across both hosts; each owns the maker until its chain ends. */
    public void runWhenIdle(Runnable r) {
        if (r == null) return;
        idleQueue.submit(finish -> {
            Runnable start = () -> {
                try { r.run(); }
                finally {
                    // A deferred request can start another asynchronous maker chain. Its
                    // successor must wait for that chain's existing drainIdle boundary.
                    if (working) pendingOnIdle = finish;
                    else finish.run();
                }
            };
            if (working) pendingOnIdle = start;
            else start.run();
        });
    }

    /** Release any work that was waiting for the chain to finish. */
    private void drainIdle() {
        Runnable r = pendingOnIdle;
        pendingOnIdle = null;
        if (r != null) r.run();
    }

    /** Called on every book update. Cheap and returns immediately unless there is work to do. */
    public void onBook(Map<String, Order5> book, Set<String> myKeys, long chainBlock, Listener l) {
        if (working) return;
        cfg.reload();
        if (working) return;
        if (!cfg.preparedCreate.isEmpty()) {
            cfg.armed = false; cfg.save();
            if (l != null) notifyState(l, "Maker paused: an interrupted create needs review. Withdraw the recorded intent before publishing again.");
            return;
        }
        if (!cfg.armed) return;
        long now = clock.getAsLong();
        if (lastCycleMs >= 0 && now >= lastCycleMs && now - lastCycleMs < MIN_CYCLE_MS) return;

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
                    if (l != null) notifyState(l, "Price feed stale — withdrawing the ladder");
                    withdrawAll(book, myKeys, chainBlock, l);
                }
                return;
            }

            mid = BigDecimal.valueOf(MarketPrice.mid());
            if (mid.signum() <= 0) return;
            widen = BigDecimal.valueOf(MarketPrice.widenFactor());
        }
        if(!MakerConfig.storageHealthy()) {
            if(l!=null)notifyState(l, "Maker paused: a settings write failed. Check storage, then explicitly Publish or Withdraw again.");
            return;
        }
        List<MakerLadder.Slot> desired = MakerLadder.desired(mid, cfg.toLadderConfig(), widen);

        // ---- slot bookkeeping: stamp legacy records, expire the patient dead ----
        // Runs BEFORE the empty-ladder bail-out: a config that currently quotes nothing still
        // has records that must age out honestly, or they linger forever as phantom rungs.
        Map<String, Order5> byOrderId = new HashMap<>();
        for (Order5 o : book.values()) {
            if (owns(o, myKeys) && !cfg.cancelTombstones.containsKey(o.orderId)) {
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
                // Absence and elapsed time cannot distinguish a delayed post from a fill.
                // Keep its identity for withdrawal/recovery and require review before funding
                // a replacement. Never duplicate an order just because mining was slow.
                cfg.armed = false;
                cfg.save();
                if (l != null) notifyState(l, "Maker paused: a recorded order is absent. Review Orders and Tape before replacing it.");
                return;
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

        // Preserve changed/unknown funded positions. A buy's requested MINIMA amount
        // changes on repricing; only its actual locked asset is a comparable baseline.
        Set<String> partial = new HashSet<>();
        Set<String> renew = new HashSet<>();
        Map<String, BigDecimal> postedSizes = new HashMap<>();
        for (Map.Entry<String, Order5> e : liveBySlot.entrySet()) {
            BigDecimal posted = cfg.postedSizeFor(e.getKey());
            if (posted != null) {
                postedSizes.put(e.getKey(), posted);
            }
            if (MakerPosition.preserve(cfg.slots.get(e.getKey()),e.getValue())) partial.add(e.getValue().coinid);
            // the maker renews its OWN rungs — DexProcessor now skips them, so nothing else will
            if (e.getValue().renewDue(chainBlock)) renew.add(e.getKey());
        }

        // The per-order reconciler already applies the price threshold and cycle budget.
        // A midpoint-only shortcut would suppress due renewals and stale-spread widening.
        // Unpegged the rungs are the user's EXPLICIT prices — a zero threshold selects
        // reconcile's exact mode (any difference at display precision relocks).
        BigDecimal threshold = cfg.pegged ? cfg.repricePct : BigDecimal.ZERO;
        List<MakerLadder.Action> actions = MakerLadder.reconcile(desired, liveBySlot, settling,
                renew, threshold, partial, postedSizes,
                new MakerLadder.Budget(MAX_ACTIONS_PER_CYCLE, MAX_CREATES_PER_SIDE));
        if (actions.isEmpty()) return;

        lastCycleMs = now;
        if (l != null) notifyState(l, "Maker: " + actions.size() + " adjustment"
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
        run(actions,idx,mid,posted,chainBlock,l,new MakerQuoteGuard(cfg,mid));
    }

    private void run(List<MakerLadder.Action> actions, int idx, BigDecimal mid, int posted,
                     long chainBlock, Listener l, MakerQuoteGuard quoteGuard) {
        cfg.reload();
        if (idx >= actions.size() || !cfg.armed) {
            working = false;
            if (posted > 0) {
                // A manual (unpegged) cycle carries no reference mid — don't record a zero,
                // it would read as "never acted" and defeat the reprice gate after a re-peg.
                if (mid != null && mid.signum() > 0) cfg.lastActedMid = mid;
                cfg.save();
            }
            if (l != null) notifyState(l, posted > 0
                    ? "Maker: " + posted + " action" + (posted == 1 ? "" : "s") + " accepted — mining now"
                    : "Maker: no adjustment could be posted — will retry");
            drainIdle();
            return;
        }
        working = true;
        MakerLadder.Action a = actions.get(idx);
        if(!quoteGuard.allows(cfg,a)) {
            lastCycleMs=-1; // Let the next book update replan or withdraw a stale ladder.
            run(actions,actions.size(),mid,posted,chainBlock,l,quoteGuard);
            if(l!=null)notifyState(l, "Maker: prices or settings changed; remaining adjustments were not submitted");
            return;
        }
        final String createOid = a.kind == MakerLadder.Kind.CREATE ? DexTxn.newOrderId() : null;
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public boolean beforePost() {
                if(advanced[0])return false;
                cfg.reload();
                return quoteGuard.allows(cfg,a);
            }
            @Override public boolean onPrepared(String handle) {
                if(advanced[0])return false;
                cfg.reload();
                if(!quoteGuard.allows(cfg,a))return false;
                return a.kind != MakerLadder.Kind.CREATE
                        || cfg.prepareCreate(a.slot.id, createOid, a.slot.sizeMinima, chainBlock,
                                DexTxn.orderLockedAmount(!a.slot.sell,a.slot.sizeMinima,a.slot.price),
                                a.slot.sell?Util.MINIMA_TOKENID:DexContract.USDT_ID);
            }
            @Override public void onPosted(String txpowid) {
                if (advanced[0]) return;
                advanced[0] = true;
                cfg.reload();
                switch (a.kind) {
                    case CREATE:
                        // Reuse Pending's identity-scoped cleanup: retain another intent.
                        // Clear and accepted-slot recording remain in the same save below.
                        if(createOid.equals(cfg.preparedOrderId()))cfg.preparedCreate = "";
                        cfg.rememberSlot(a.slot.id, createOid, a.slot.sizeMinima, chainBlock,
                                DexTxn.orderLockedAmount(!a.slot.sell,a.slot.sizeMinima,a.slot.price),
                                a.slot.sell?Util.MINIMA_TOKENID:DexContract.USDT_ID);
                        if (l != null) notifyCreate(l, a.slot, createOid);
                        break;
                    case RELOCK:
                        cfg.noteSlotAction(a.slot.id, chainBlock);
                        if (l != null) notifyRelock(l, a.order, a.slot.price);
                        break;
                    case CANCEL:
                        cfg.forgetSlotByOrderId(a.order.orderId);
                        cfg.tombstone(a.order.orderId, chainBlock);
                        if (l != null) notifyCancel(l, a.order);
                        break;
                }
                run(actions, idx + 1, mid, posted + 1, chainBlock, l, quoteGuard);
            }
            @Override public void onFailed(String message) {
                if (advanced[0]) return;
                advanced[0] = true;
                // A different host may have persisted a withdrawal while this reply was pending.
                cfg.reload();
                if (NodeApi.ERR_WRITE_UNCERTAIN.equals(message)) {
                    if (a.kind == MakerLadder.Kind.CREATE) cfg.rememberSlot(a.slot.id, createOid, a.slot.sizeMinima, chainBlock,
                                DexTxn.orderLockedAmount(!a.slot.sell,a.slot.sizeMinima,a.slot.price),
                                a.slot.sell?Util.MINIMA_TOKENID:DexContract.USDT_ID);
                    cfg.armed = false; cfg.save();
                    if (l != null) notifyState(l, "Maker paused: " + message);
                    working = false; drainIdle(); return;
                }
                if (a.kind == MakerLadder.Kind.CREATE && createOid.equals(cfg.preparedOrderId())) {
                    cfg.preparedCreate = ""; cfg.save();
                }
                // one rung failing must not stall the rest — the next cycle retries it
                if (l != null) notifyState(l, "Maker: " + a.kind + " failed — " + message);
                run(actions, idx + 1, mid, posted, chainBlock, l, quoteGuard);
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
                if (l != null) notifyState(l, "Maker: " + a.kind + " errored — " + t);
                run(actions, idx + 1, mid, posted, chainBlock, l, quoteGuard);
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

    /** Pause and durably track every maker identity before the all-orders cancellation loop.
     * An active maker callback may add a slot; repeat the tracking step after ownership ends. */
    public void stopForCancelAll(long block,Listener listener,Runnable ready) {
        if(!cfg.stopAndTrackWithdrawal(block)) {
            if(listener!=null)notifyState(listener, "Could not save maker withdrawal tracking. Cancel all was not started; records retained.");
            return;
        }
        if(!working){ready.run();return;}
        if(listener!=null)notifyState(listener, "Maker pause saved. Waiting for the current adjustment before cancelling; orders remain fillable until confirmation.");
        runWhenIdle(() -> stopForCancelAll(block,listener,ready));
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
        if (working) { runWhenIdle(() -> { cfg.reload(); withdrawAll(book, myKeys, chainBlock, l); }); return; }
        cfg.reload();
        List<Order5> live = liveLadderOrders(book, myKeys);
        String interrupted = cfg.preparedOrderId();
        if(!cfg.preparedCreate.isEmpty()&&interrupted.isEmpty()) {
            if(l!=null)notifyState(l, "Cannot read the interrupted order intent. Records retained; preserve app data for recovery.");
            return;
        }
        Set<String> targets=new HashSet<>();
        for(Order5 o:live)targets.add(o.orderId);
        for(MakerConfig.SlotRec r:cfg.slots.values())targets.add(r.orderId);
        if(!interrupted.isEmpty())targets.add(interrupted);
        if(!cfg.prepareWithdrawal(targets,chainBlock)) {
            if(l!=null)notifyState(l, "Could not save withdrawal intent. No cancellation was sent; original records retained.");
            return;
        }
        if (!interrupted.isEmpty()) {
            for (Order5 o : book.values()) if (owns(o, myKeys) && interrupted.equals(o.orderId)
                    && !live.contains(o)) live.add(o);
            cfg.preparedCreate = ""; cfg.save();
        }
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
            if (l != null) notifyState(l, cfg.hasRecordedOrders()
                    ? "Withdrawal instructions retained. Check Orders for on-chain outcomes; absent orders are not proof of cancellation."
                    : "No recorded maker orders were found in this snapshot. Check Orders before assuming funds are free.");
            drainIdle();
            return;
        }
        // BATCH: the covenant's cancel branch is index-matched, so several rungs go in one
        // transaction — one round of proof-of-work instead of one per rung. A 12-rung ladder
        // withdraws in 3 transactions rather than 12.
        final List<Order5> chunk = new ArrayList<>(
                live.subList(idx, Math.min(idx + SweepPlanner.MAX_ORDERS, live.size())));
        final int next = idx + chunk.size();
        List<String> identities=new ArrayList<>();for(Order5 c:chunk)identities.add(c.orderId);
        if(!cfg.prepareWithdrawal(identities,chainBlock)) {
            working=false;
            if(l!=null)notifyState(l, "Could not save withdrawal intent. This cancellation batch was not sent; records retained.");
            drainIdle();return;
        }
        for (Order5 c : chunk) cfg.forgetSlotByOrderId(c.orderId);
        working=true;
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String t) {
                if (advanced[0]) return; advanced[0] = true;
                for (Order5 c : chunk) {
                    cfg.tombstone(c.orderId, chainBlock, chainBlock);   // sent — pace the retry
                    if (l != null) notifyCancel(l, c);
                }
                cancelAllLadder(live, next, chainBlock, l);
            }
            @Override public void onFailed(String m) {
                if (advanced[0]) return; advanced[0] = true;
                // the batch is atomic — NONE of these cancelled. Condemn them all with no
                // attempt recorded so the tombstone sweep retries them immediately.
                for (Order5 c : chunk) cfg.tombstone(c.orderId, chainBlock, 0);
                cancelAllLadder(live, next, chainBlock, l);
            }
        };
        try {
            txn.cancelBatch(chunk, once);
        } catch (Throwable t) {
            // a throw must not strand the withdraw half-done with `working` stuck true
            if (!advanced[0]) {
                advanced[0] = true;
                for (Order5 c : chunk) cfg.tombstone(c.orderId, chainBlock, 0);
                cancelAllLadder(live, next, chainBlock, l);
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
        if (working) return;
        cfg.reload();
        if (cfg.cancelTombstones.isEmpty()) return;
        Map<String, Order5> mineById = new HashMap<>();
        for (Order5 o : book.values()) if (owns(o, myKeys)) mineById.put(o.orderId, o);

        List<String> done = new ArrayList<>();
        Order5 target = null;
        for (Map.Entry<String, MakerConfig.Tomb> e : cfg.cancelTombstones.entrySet()) {
            MakerConfig.Tomb t = e.getValue();
            Order5 o = mineById.get(e.getKey());
            if (o == null) {
                // absent long enough after CONDEMNATION (not after the last attempt) that the
                // cancel must have mined — or the order never existed
                // Keep the cancellation instruction for any delayed order that resurfaces.
                // Only verified spend evidence or explicit user reconciliation can retire it.
            } else if (target == null
                    && (t.lastAttemptBlock <= 0 || chainBlock - t.lastAttemptBlock >= PATIENCE_BLOCKS)) {
                target = o;                      // never tried, or the last try had its chance
            }
        }
        for (String id : done) cfg.clearTombstone(id);
        if (target == null) return;

        final Order5 o = target;
        cfg.tombstone(o.orderId, chainBlock, chainBlock);   // pace the next attempt
        if (l != null) notifyState(l, "Maker: cancelling a late order (" +
                (o.sell ? "ask" : "bid") + " " + PriceMath.fmtPrice(o.price()) + ")");
        working = true;
        final boolean[] advanced = {false};
        DexTxn.Result once = new DexTxn.Result() {
            @Override public void onPosted(String t) {
                if (advanced[0]) return; advanced[0] = true;
                working = false;
                if (l != null) notifyCancel(l, o);
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
            if (owns(o, myKeys) && ids.contains(o.orderId)) out.add(o);
        }
        return out;
    }

}

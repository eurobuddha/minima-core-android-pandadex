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

    private final MainActivity act;
    private final MakerConfig cfg;
    private final DexTxn txn;
    private long lastCycleMs = 0;
    private boolean working = false;

    public MakerEngine(MainActivity act, MakerConfig cfg, DexTxn txn) {
        this.act = act;
        this.cfg = cfg;
        this.txn = txn;
    }

    public boolean isWorking() { return working; }

    /** Called on every book update. Cheap and returns immediately unless there is work to do. */
    public void onBook(Map<String, Order5> book, Set<String> myKeys, long chainBlock, Listener l) {
        if (!cfg.armed || working) return;
        long now = System.currentTimeMillis();
        if (now - lastCycleMs < MIN_CYCLE_MS) return;

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

        BigDecimal mid = BigDecimal.valueOf(MarketPrice.mid());
        if (mid.signum() <= 0) return;

        // ---- nothing to do until the reference actually moved ----
        boolean haveLadder = !liveLadderOrders(book, myKeys).isEmpty();
        if (haveLadder && !MakerLadder.worthRepricing(cfg.lastActedMid, mid, cfg.repricePct)) return;

        BigDecimal widen = BigDecimal.valueOf(MarketPrice.widenFactor());
        List<MakerLadder.Slot> desired = MakerLadder.desired(mid, cfg.toLadderConfig(), widen);

        Map<String, Order5> liveBySlot = new HashMap<>();
        Map<String, Order5> byOrderId = new HashMap<>();
        for (Order5 o : book.values()) if (o.isMine(myKeys)) byOrderId.put(o.orderId, o);
        for (Map.Entry<String, String> e : cfg.slotOrderIds.entrySet()) {
            Order5 o = byOrderId.get(e.getValue());
            if (o != null) liveBySlot.put(e.getKey(), o);
        }

        // a rung that has been partly taken is a working position — leave it be
        Set<String> partial = new HashSet<>();
        for (Order5 o : liveBySlot.values()) {
            if (o.locked.compareTo(o.minimaAmount()) != 0) partial.add(o.coinid);
        }

        List<MakerLadder.Action> actions = MakerLadder.reconcile(desired, liveBySlot,
                cfg.repricePct, partial, MAX_ACTIONS_PER_CYCLE);
        if (actions.isEmpty()) return;

        lastCycleMs = now;
        cfg.lastActedMid = mid;
        cfg.save();
        if (l != null) l.onMakerState("Maker: " + actions.size() + " adjustment"
                + (actions.size() == 1 ? "" : "s") + " at mid " + PriceMath.fmtPrice(mid));
        run(actions, 0, l);
    }

    /** Execute one action at a time — the node runs a single command at a time and each of
     *  these grinds proof-of-work. */
    private void run(List<MakerLadder.Action> actions, int idx, Listener l) {
        if (idx >= actions.size()) {
            working = false;
            if (l != null) l.onMakerState("Maker: ladder up to date");
            return;
        }
        working = true;
        MakerLadder.Action a = actions.get(idx);
        DexTxn.Result next = new DexTxn.Result() {
            @Override public void onPosted(String txpowid) { run(actions, idx + 1, l); }
            @Override public void onFailed(String message) {
                // one rung failing must not stall the rest — the next cycle retries it
                if (l != null) l.onMakerState("Maker: " + a.kind + " failed — " + message);
                run(actions, idx + 1, l);
            }
        };

        switch (a.kind) {
            case CREATE: {
                String orderId = txn.createOrder(!a.slot.sell, a.slot.sizeMinima, a.slot.price,
                        true, BigDecimal.ONE, next);
                if (orderId == null) { run(actions, idx + 1, l); return; }
                cfg.slotOrderIds.put(a.slot.id, orderId);
                cfg.save();
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
                txn.relock(o, newWant, next);
                break;
            }
            case CANCEL: {
                forgetSlotFor(a.order);
                txn.cancel(a.order, next);
                break;
            }
        }
    }

    /** Cancel every rung we still have on the book (withdraw / disarm). */
    public void cancelAllLadder(List<Order5> live, int idx, Listener l) {
        if (idx >= live.size()) {
            working = false;
            cfg.slotOrderIds.clear();
            cfg.save();
            if (l != null) l.onMakerState("Maker: ladder withdrawn");
            return;
        }
        working = true;
        Order5 o = live.get(idx);
        forgetSlotFor(o);
        txn.cancel(o, new DexTxn.Result() {
            @Override public void onPosted(String t) { cancelAllLadder(live, idx + 1, l); }
            @Override public void onFailed(String m) { cancelAllLadder(live, idx + 1, l); }
        });
    }

    public List<Order5> liveLadderOrders(Map<String, Order5> book, Set<String> myKeys) {
        List<Order5> out = new ArrayList<>();
        Set<String> ids = new HashSet<>(cfg.slotOrderIds.values());
        for (Order5 o : book.values()) {
            if (o.isMine(myKeys) && ids.contains(o.orderId)) out.add(o);
        }
        return out;
    }

    private void forgetSlotFor(Order5 o) {
        if (o == null) return;
        cfg.slotOrderIds.values().remove(o.orderId);
        cfg.save();
    }
}

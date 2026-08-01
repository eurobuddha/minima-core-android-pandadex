package com.eurobuddha.pandadex;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every transaction the app posts — mirrors the constructions PROVEN in contract/phaseB.py.
 * Fund-safety rails (non-negotiable, from the sibling apps' hard lessons):
 *
 *  - txncheck GATE before every txnpost: proceed only when valid.scripts && validamounts &&
 *    mmrproofs are ALL true (top-level `scripts` is a COUNT, never a verdict).
 *  - token coin VALUE = tokenamount (never raw amount).
 *  - all outgoing amounts pre-quantized via PriceMath (maker-favored UP, taker-facing DOWN).
 *  - inflight funding-coin reservation across posted-but-unconfirmed txns.
 *  - state values are hex/numbers, ports set individually (txnstate id: port: value:).
 *  - order coin(s) are inputs 0..k-1; payments are outputs 0..k-1; the single partial (if
 *    any) is the LAST order input with its remainder at output k (covenant shape).
 */
public class DexTxn {   // non-final so tests can stub the three order actions

    public interface Result {
        void onPosted(String txpowid);
        void onFailed(String message);
    }

    private final NodeApi node;
    private final DexDb db;
    private String myPubkey = "";
    private String myHexAddr = "";
    /**
     * Funding coins used by posted-but-unconfirmed txns — never double-select. Reservations
     * EXPIRE: a silently-rejected transaction (consensus drops it without an error) would
     * otherwise pin its coins forever and the user would see "insufficient funds" on a funded
     * wallet until the process restarted. Value = the block the reservation was made at.
     *
     * STATIC on purpose: MainActivity and DexKeepAliveService each build their own DexTxn, so an
     * instance map left the two engines free to reserve — and therefore spend and sign — the very same
     * coins. The only thing separating them was a MainActivity.FOREGROUND boolean read once at the top
     * of a pipeline that then runs for minutes.
     */
    private static final Map<String, Long> inflight = new ConcurrentHashMap<>();
    private static final long RESERVE_BLOCKS = 6;
    private volatile long chainBlock = 0;

    /** Keep the reservation clock honest; called from the host's block poll. */
    public void setChainBlock(long b) {
        chainBlock = b;
        if (b > 0) inflight.values().removeIf(at -> b - at > RESERVE_BLOCKS);
    }

    public DexTxn(NodeApi node, DexDb db) {
        this.node = node;
        this.db = db;
    }

    public void setIdentity(String pubkey, String hexAddr) {
        myPubkey = pubkey;
        myHexAddr = hexAddr;
    }

    public String pubkey() { return myPubkey; }

    public String hexAddr() { return myHexAddr; }

    // ------------------------------------------------------------------ create

    /**
     * Place an order. buy=true locks mxUSDT wanting MINIMA; sell locks MINIMA wanting mxUSDT.
     * One `send` — appears in the book next block; the caller adds the optimistic row.
     */
    public String createOrder(boolean buy, BigDecimal minimaAmount, BigDecimal price,
                              boolean gtc, BigDecimal minRemMinima, Result cb) {
        return createOrder(buy, minimaAmount, price, gtc, minRemMinima, newOrderId(), cb);
    }

    /** As above with a caller-supplied order id — the maker pre-generates it so a slot can be
     *  recorded in the ASYNC success callback (recording before the node accepts is how 0.2.6
     *  ended up with dead ids for orders that were never funded). */
    public String createOrder(boolean buy, BigDecimal minimaAmount, BigDecimal price,
                              boolean gtc, BigDecimal minRemMinima, String orderId, Result cb) {
        if (myPubkey.isEmpty() || myHexAddr.isEmpty()) {
            cb.onFailed("Still reading your wallet identity — try again in a moment");
            return null;
        }
        if (minimaAmount.compareTo(PriceMath.MIN_ORDER_MINIMA) < 0) {
            cb.onFailed("Below minimum order (" + PriceMath.MIN_ORDER_MINIMA + " MINIMA)");
            return null;
        }
        BigDecimal usdt = PriceMath.up(minimaAmount.multiply(price, PriceMath.MC), PriceMath.USDT_DP);
        // The covenant cross-multiplies amounts; MiniNumber rejects values over 1e9 outright,
        // and the overflow argument for the products assumes both legs stay inside that bound.
        if (minimaAmount.compareTo(PriceMath.MAX_ORDER) > 0 || usdt.compareTo(PriceMath.MAX_ORDER) > 0) {
            cb.onFailed("Order too large (max " + PriceMath.MAX_ORDER.toPlainString() + " per leg)");
            return null;
        }
        BigDecimal lock = buy ? usdt : PriceMath.down(minimaAmount, PriceMath.MINIMA_DP);
        BigDecimal want = buy ? PriceMath.down(minimaAmount, PriceMath.MINIMA_DP) : usdt;
        // Port 8 is compared on-chain against the remaining LOCKED amount, so a min-remainder
        // the user expressed in MINIMA has to be converted to the locked asset for a buy —
        // otherwise "1 MINIMA" silently means "1 mxUSDT" (≈200 MINIMA) and small buys become
        // fill-or-nothing without the user ever being told.
        BigDecimal minRem = buy
                ? PriceMath.up(minRemMinima.multiply(price, PriceMath.MC), PriceMath.USDT_DP)
                : PriceMath.down(minRemMinima, PriceMath.MINIMA_DP);
        if (minRem.compareTo(lock) > 0) {
            cb.onFailed("Minimum remainder is larger than the order itself");
            return null;
        }
        String state = "{\"0\":\"" + myPubkey + "\",\"1\":\"" + myHexAddr + "\","
                + "\"2\":\"" + want.toPlainString() + "\","
                + "\"3\":\"" + (buy ? "0x00" : DexContract.USDT_ID) + "\","
                + "\"4\":\"" + orderId + "\",\"5\":\"" + (buy ? "0" : "1") + "\","
                + "\"6\":\"" + price.toPlainString() + "\",\"7\":\"" + (gtc ? "1" : "0") + "\","
                + "\"8\":\"" + minRem.toPlainString() + "\"}";
        String cmd = "send amount:" + lock.toPlainString() + " address:" + DexContract.ADDR_V5
                + (buy ? " tokenid:" + DexContract.USDT_ID : "") + " state:" + state;
        // Behind the gate too: `send` signs internally, so it burns a key leaf exactly like a txnsign
        // chain does and must not overlap with one.
        SignGate.submit(gate -> node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                gate.free();
                if (json.optBoolean("status", false) || json.optBoolean("pending", false)) {
                    cb.onPosted(Util.extractTxpowid(json, orderId));
                } else {
                    cb.onFailed(json.optString("error", "send failed"));
                }
            }
            @Override public void onError(String message) { gate.free(); cb.onFailed(message); }
        }));
        // The caller needs this to match its optimistic row against the live book — without
        // it the row can never resolve and eventually cries "NOT CONFIRMED" on a good order.
        return orderId;
    }

    // ------------------------------------------------------------------ sweep fill

    /** Execute a planned sweep (proven shape: order inputs first, index-matched payments,
     *  single partial last with its remainder at output k). */
    public void fillSweep(SweepPlanner.Plan plan, Result cb) {
        if (plan.isEmpty()) { cb.onFailed("Nothing to fill"); return; }
        boolean takerBuys = plan.takes.get(0).order.sell;
        // taker pays USDT when buying (consuming sells); pays MINIMA when selling
        String payTok = takerBuys ? DexContract.USDT_ID : "0x00";
        BigDecimal needed = BigDecimal.ZERO;
        List<String[]> payments = new ArrayList<>();   // [amount, address, tokenid]
        SweepPlanner.Take partial = null;
        BigDecimal partialRem = null, partialNewWant = null;

        for (SweepPlanner.Take t : plan.takes) {
            Order5 o = t.order;
            BigDecimal lockedTake = !t.partial ? o.locked
                    : (o.sell ? t.minima
                              : PriceMath.up(t.minima.multiply(o.price(), PriceMath.MC), PriceMath.USDT_DP));
            BigDecimal pay = t.partial
                    ? PriceMath.payFor(o.wantAmt, o.locked, lockedTake,
                            o.sell ? PriceMath.USDT_DP : PriceMath.MINIMA_DP)
                    : o.wantAmt;
            payments.add(new String[]{pay.toPlainString(), o.wantAddr, o.wantTok});
            needed = needed.add(pay);
            if (t.partial) {
                partial = t;
                partialRem = o.locked.subtract(lockedTake);
                partialNewWant = PriceMath.newWantFor(o.wantAmt, o.locked, partialRem,
                        o.sell ? PriceMath.USDT_DP : PriceMath.MINIMA_DP);
            }
        }

        final SweepPlanner.Take fPartial = partial;
        final BigDecimal fRem = partialRem, fNewWant = partialNewWant, fNeeded = needed;
        findCoins(payTok, needed, coins -> {
            if (coins == null) {
                String why = takeFundError();
                cb.onFailed(why != null ? why : "Insufficient funds for sweep");
                return;
            }
            BigDecimal fundTotal = BigDecimal.ZERO;
            for (JSONObject c : coins) fundTotal = fundTotal.add(coinValue(c));
            String txid = "sweep_" + System.nanoTime();
            List<String> steps = new ArrayList<>();
            steps.add("txncreate id:" + txid);
            for (SweepPlanner.Take t : plan.takes) steps.add("txninput id:" + txid + " coinid:" + t.order.coinid);
            List<String> fundIds = new ArrayList<>();
            for (JSONObject c : coins) {
                steps.add("txninput id:" + txid + " coinid:" + c.optString("coinid"));
                fundIds.add(c.optString("coinid"));
            }
            for (String[] p : payments) {
                steps.add("txnoutput id:" + txid + " amount:" + p[0] + " address:" + p[1]
                        + ("0x00".equals(p[2]) ? "" : " tokenid:" + p[2]) + " storestate:false");
            }
            if (fPartial != null) {
                Order5 o = fPartial.order;
                steps.add("txnoutput id:" + txid + " amount:" + fRem.toPlainString()
                        + " address:" + DexContract.ADDR_V5
                        + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok)
                        + " storestate:true");
            }
            // taker proceeds: the locked asset of each consumed order
            BigDecimal proceeds = BigDecimal.ZERO;
            String proceedsTok = takerBuys ? "0x00" : DexContract.USDT_ID;
            for (SweepPlanner.Take t : plan.takes) {
                BigDecimal lockedTake = !t.partial ? t.order.locked
                        : (t.order.sell ? t.minima
                                        : PriceMath.up(t.minima.multiply(t.order.price(), PriceMath.MC), PriceMath.USDT_DP));
                proceeds = proceeds.add(lockedTake);
            }
            steps.add("txnoutput id:" + txid + " amount:" + proceeds.toPlainString()
                    + " address:" + myHexAddr
                    + ("0x00".equals(proceedsTok) ? "" : " tokenid:" + proceedsTok)
                    + " storestate:false");
            BigDecimal change = fundTotal.subtract(fNeeded);
            if (change.signum() > 0) {
                steps.add("txnoutput id:" + txid + " amount:" + change.toPlainString()
                        + " address:" + myHexAddr
                        + ("0x00".equals(payTok) ? "" : " tokenid:" + payTok) + " storestate:false");
            }
            if (fPartial != null) {
                // txn state = the remainder's new state (ports 0/1/3/4/5/7/8 verbatim, 2 scaled)
                Order5 o = fPartial.order;
                steps.add(stateStep(txid, 0, o.ownerPk));
                steps.add(stateStep(txid, 1, o.wantAddr));
                steps.add(stateStep(txid, 2, fNewWant.toPlainString()));
                steps.add(stateStep(txid, 3, o.wantTok));
                steps.add(stateStep(txid, 4, o.orderId));
                steps.add(stateStep(txid, 5, o.sell ? "1" : "0"));
                steps.add(stateStep(txid, 6, PriceMath.price(fNewWant, fRem).toPlainString()));
                steps.add(stateStep(txid, 7, o.gtc ? "1" : "0"));
                steps.add(stateStep(txid, 8, o.minRem.toPlainString()));
            }
            steps.add("txnsign id:" + txid + " publickey:auto");
            steps.add("txnbasics id:" + txid);
            postGated(txid, steps, fundIds, cb);
        });
    }

    /** Execute a blended order-book + PandaPools fill in one atomic transaction. */
    public void fillComposite(CompositeRouter.Plan plan, boolean takerBuys, Result cb) {
        if (plan == null || plan.isEmpty()) { cb.onFailed("Nothing to fill"); return; }
        CompositePrep prep = prepareComposite(plan, takerBuys);

        java.util.HashSet<String> exclude = new java.util.HashSet<>();
        if (prep.route != null) {
            for (String a : prep.route.pairAddresses) if (a != null) exclude.add(a.toLowerCase());
            for (PoolRouter.Alloc a : prep.route.allocs) {
                if (a.pool.address != null) exclude.add(a.pool.address.toLowerCase());
                if (a.pool.oadr != null) exclude.add(a.pool.oadr.toLowerCase());
            }
        }
        findCoins(prep.payTok, prep.needed, exclude, 8, coins -> {
            if (coins == null) {
                String why = takeFundError();
                cb.onFailed(why != null ? why : "Insufficient funds for trade");
                return;
            }
            ensureTrackedPools(prep.route == null ? new ArrayList<>() : prep.route.allocs, 0,
                    () -> buildComposite(plan, prep, takerBuys, coins, cb));
        });
    }

    static final class CompositePrep {
        PoolRouter.Route route;
        String payTok;
        BigDecimal needed = BigDecimal.ZERO;
        List<String[]> payments = new ArrayList<>();
        SweepPlanner.Take partial;
        BigDecimal partialRem, partialNewWant;
    }

    static CompositePrep prepareComposite(CompositeRouter.Plan plan, boolean takerBuys) {
        CompositePrep prep = new CompositePrep();
        prep.payTok = takerBuys ? DexContract.USDT_ID : Util.MINIMA_TOKENID;
        for (SweepPlanner.Take t : plan.orderTakes) {
            Order5 o = t.order;
            BigDecimal lockedTake = !t.partial ? o.locked
                    : (o.sell ? t.minima
                    : PriceMath.up(t.minima.multiply(o.price(), PriceMath.MC), PriceMath.USDT_DP));
            BigDecimal pay = t.partial
                    ? PriceMath.payFor(o.wantAmt, o.locked, lockedTake,
                    o.sell ? PriceMath.USDT_DP : PriceMath.MINIMA_DP)
                    : o.wantAmt;
            prep.payments.add(new String[]{pay.toPlainString(), o.wantAddr, o.wantTok});
            prep.needed = prep.needed.add(pay);
            if (t.partial) {
                prep.partial = t;
                prep.partialRem = o.locked.subtract(lockedTake);
                prep.partialNewWant = PriceMath.newWantFor(o.wantAmt, o.locked, prep.partialRem,
                        o.sell ? PriceMath.USDT_DP : PriceMath.MINIMA_DP);
            }
        }
        prep.route = plan.poolRoute;
        if (prep.route != null && prep.route.ok) prep.needed = prep.needed.add(prep.route.totalIn);
        return prep;
    }

    private void buildComposite(CompositeRouter.Plan plan, CompositePrep prep, boolean takerBuys,
                                List<JSONObject> coins, Result cb) {
        CompositeBuild built = buildCompositeSteps("combo_" + System.nanoTime(), myHexAddr, plan, prep, takerBuys, coins);
        postGated(built.txid, built.steps, built.fundIds, cb);
    }

    static final class CompositeBuild {
        String txid;
        List<String> steps = new ArrayList<>();
        List<String> fundIds = new ArrayList<>();
    }

    static CompositeBuild buildCompositeSteps(String txid, String myHexAddr, CompositeRouter.Plan plan,
                                              CompositePrep prep, boolean takerBuys,
                                              List<JSONObject> coins) {
        PoolRouter.Route route = prep.route;
        BigDecimal fundTotal = BigDecimal.ZERO;
        for (JSONObject c : coins) fundTotal = fundTotal.add(coinValue(c));
        CompositeBuild out = new CompositeBuild();
        out.txid = txid;
        List<String> steps = out.steps;
        steps.add("txncreate id:" + txid);
        if (route != null && route.ok) {
            for (PoolRouter.Alloc a : route.allocs) {
                steps.add("txninput id:" + txid + " coinid:" + a.pool.coinidM);
                steps.add("txninput id:" + txid + " coinid:" + a.pool.coinidT);
            }
        }
        for (SweepPlanner.Take t : plan.orderTakes) steps.add("txninput id:" + txid + " coinid:" + t.order.coinid);
        for (JSONObject c : coins) {
            steps.add("txninput id:" + txid + " coinid:" + c.optString("coinid"));
            out.fundIds.add(c.optString("coinid"));
        }
        if (route != null && route.ok) {
            for (PoolRouter.Alloc a : route.allocs) {
                steps.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newX)
                        + " address:" + a.pool.address + " storestate:false");
                steps.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newY)
                        + " address:" + a.pool.address + " tokenid:" + a.pool.tok + " storestate:false");
            }
        }
        for (String[] p : prep.payments) {
            steps.add("txnoutput id:" + txid + " amount:" + p[0] + " address:" + p[1]
                    + (Util.MINIMA_TOKENID.equals(p[2]) ? "" : " tokenid:" + p[2]) + " storestate:false");
        }
        if (prep.partial != null) {
            Order5 o = prep.partial.order;
            steps.add("txnoutput id:" + txid + " amount:" + prep.partialRem.toPlainString()
                    + " address:" + DexContract.ADDR_V5
                    + (Util.MINIMA_TOKENID.equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok)
                    + " storestate:true");
        }
        String proceedsTok = takerBuys ? Util.MINIMA_TOKENID : DexContract.USDT_ID;
        BigDecimal proceeds = takerBuys ? plan.totalMinima : plan.totalUsdt;
        steps.add("txnoutput id:" + txid + " amount:" + amt(proceeds)
                + " address:" + myHexAddr
                + (Util.MINIMA_TOKENID.equals(proceedsTok) ? "" : " tokenid:" + proceedsTok)
                + " storestate:false");
        BigDecimal change = fundTotal.subtract(prep.needed);
        if (change.signum() > 0) {
            steps.add("txnoutput id:" + txid + " amount:" + amt(change)
                    + " address:" + myHexAddr
                    + (Util.MINIMA_TOKENID.equals(prep.payTok) ? "" : " tokenid:" + prep.payTok) + " storestate:false");
        }
        if (prep.partial != null) {
            Order5 o = prep.partial.order;
            steps.add(stateStep(txid, 0, o.ownerPk));
            steps.add(stateStep(txid, 1, o.wantAddr));
            steps.add(stateStep(txid, 2, prep.partialNewWant.toPlainString()));
            steps.add(stateStep(txid, 3, o.wantTok));
            steps.add(stateStep(txid, 4, o.orderId));
            steps.add(stateStep(txid, 5, o.sell ? "1" : "0"));
            steps.add(stateStep(txid, 6, PriceMath.price(prep.partialNewWant, prep.partialRem).toPlainString()));
            steps.add(stateStep(txid, 7, o.gtc ? "1" : "0"));
            steps.add(stateStep(txid, 8, o.minRem.toPlainString()));
        }
        steps.add("txnsign id:" + txid + " publickey:auto");
        steps.add("txnbasics id:" + txid);
        return out;
    }

    private void ensureTrackedPools(List<PoolRouter.Alloc> allocs, int i, Runnable then) {
        if (i >= allocs.size()) { then.run(); return; }
        Pool p = allocs.get(i).pool;
        String script = p.covenantScript != null && !p.covenantScript.isEmpty()
                ? p.covenantScript : PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin);
        // Keep pool scripts known for transaction construction without making every reserve
        // coin at that address look wallet-owned/relevant. This is the same load-bearing
        // trackall:false rule as the V5 order covenant.
        node.cmd(poolScriptRegisterCommand(p, script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { ensureTrackedPools(allocs, i + 1, then); }
            @Override public void onError(String m) { ensureTrackedPools(allocs, i + 1, then); }
        });
    }

    static String poolScriptRegisterCommand(Pool p, String script) {
        return "newscript trackall:false script:" + Util.scriptArg(script);
    }

    private static String stateStep(String txid, int port, String value) {
        return "txnstate id:" + txid + " port:" + port + " value:" + value;
    }

    private static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    /**
     * Order ids must be unguessable AND collision-free: they key successor-matching in the
     * fill tape, and a wall-clock timestamp both collides between users placing in the same
     * millisecond and can be deliberately copied by an attacker to make their coin look like
     * the successor of someone else's order.
     */
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    static String newOrderId() {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ owner ops

    /** Cancel: owner-signed refund of the whole coin to the maker wallet (token-aware). */
    public void cancel(Order5 o, Result cb) {
        // Do NOT mark this as cancelled yet. `txnpost` only means the node accepted it to the
        // mempool; if it loses a double-spend race to a real fill, a premature marker would
        // permanently suppress that fill from the tape. The verifier records a cancellation
        // only after the refund is actually visible on-chain.
        String txid = "cancel_" + System.nanoTime();
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        steps.add("txninput id:" + txid + " coinid:" + o.coinid);
        steps.add("txnoutput id:" + txid + " amount:" + o.locked.toPlainString()
                + " address:" + o.wantAddr
                + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok) + " storestate:false");
        steps.add("txnsign id:" + txid + " publickey:" + o.ownerPk);
        steps.add("txnbasics id:" + txid);
        postGated(txid, steps, new ArrayList<>(), cb);
    }

    /**
     * Cancel SEVERAL orders in ONE transaction.
     *
     * The covenant's cancel branch is index-matched — input i is satisfied by
     * {@code VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE)}, i.e. output i must refund
     * that order's own payout address its full amount. So N order coins can be inputs to one
     * transaction with N refunds at the same indices, each script satisfying itself
     * independently. Per-token balance is automatic: every refund is the whole locked amount,
     * so no funding coins are needed at all (unlike a sweep).
     *
     * Same construction {@link #fillSweep} already proves on mainnet — order inputs first,
     * index-matched outputs — but strictly simpler: no partial, no payment arithmetic, no
     * counterparty. Withdrawing a 12-rung ladder costs 3 rounds of proof-of-work, not 12.
     *
     * ATOMIC: the batch either cancels every order in it or none of them, which is cleaner
     * than a sequential run failing partway and leaving an arbitrary subset cancelled.
     */
    public void cancelBatch(List<Order5> orders, Result cb) {
        if (orders == null || orders.isEmpty()) { cb.onFailed("Nothing to cancel"); return; }
        if (orders.size() > SweepPlanner.MAX_ORDERS) {
            cb.onFailed("Too many orders for one transaction");
            return;
        }
        if (orders.size() == 1) { cancel(orders.get(0), cb); return; }

        String txid = "cancelb_" + System.nanoTime();
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        // inputs first, in order — the outputs below must land at the SAME indices
        for (Order5 o : orders) {
            steps.add("txninput id:" + txid + " coinid:" + o.coinid);
        }
        for (Order5 o : orders) {
            steps.add("txnoutput id:" + txid + " amount:" + o.locked.toPlainString()
                    + " address:" + o.wantAddr
                    + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok)
                    + " storestate:false");
        }
        // one signature per DISTINCT owner key — normally just ours, but never assume
        List<String> signed = new ArrayList<>();
        for (Order5 o : orders) {
            if (o.ownerPk != null && !o.ownerPk.isEmpty() && !signed.contains(o.ownerPk)) {
                signed.add(o.ownerPk);
                steps.add("txnsign id:" + txid + " publickey:" + o.ownerPk);
            }
        }
        steps.add("txnbasics id:" + txid);
        postGated(txid, steps, new ArrayList<>(), cb);
    }

    /** Atomic in-place re-lock: GTC renew (newWant null) or edit (newWant set). ONE txn —
     *  the coin never leaves the book (the V5 owner branch; proven in Phase B chunk D). */
    public void relock(Order5 o, BigDecimal newWant, Result cb) {
        // A successful re-lock is recognised from its successor coin by FillTape. Do not add a
        // cancellation marker here: a transaction accepted to the mempool may still lose to a
        // taker fill, which must remain recordable.
        BigDecimal want = newWant == null ? o.wantAmt : newWant;
        String txid = "relock_" + System.nanoTime();
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        steps.add("txninput id:" + txid + " coinid:" + o.coinid);
        steps.add("txnoutput id:" + txid + " amount:" + o.locked.toPlainString()
                + " address:" + DexContract.ADDR_V5
                + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok) + " storestate:true");
        steps.add(stateStep(txid, 0, o.ownerPk));
        steps.add(stateStep(txid, 1, o.wantAddr));
        steps.add(stateStep(txid, 2, want.toPlainString()));
        steps.add(stateStep(txid, 3, o.wantTok));
        steps.add(stateStep(txid, 4, o.orderId));
        steps.add(stateStep(txid, 5, o.sell ? "1" : "0"));
        steps.add(stateStep(txid, 6, PriceMath.price(o.sell ? want : o.locked,
                o.sell ? o.locked : want).toPlainString()));
        steps.add(stateStep(txid, 7, o.gtc ? "1" : "0"));
        steps.add(stateStep(txid, 8, o.minRem.toPlainString()));
        steps.add("txnsign id:" + txid + " publickey:" + o.ownerPk);
        steps.add("txnbasics id:" + txid);
        postGated(txid, steps, new ArrayList<>(), cb);
    }

    /** Third-party sweep of an expired order back to its maker (book hygiene; COINAGE path). */
    public void collectExpired(Order5 o, Result cb) {
        String txid = "collect_" + System.nanoTime();
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        steps.add("txninput id:" + txid + " coinid:" + o.coinid);
        steps.add("txnoutput id:" + txid + " amount:" + o.locked.toPlainString()
                + " address:" + o.wantAddr
                + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok) + " storestate:false");
        steps.add("txnsign id:" + txid + " publickey:auto");
        steps.add("txnbasics id:" + txid);
        postGated(txid, steps, new ArrayList<>(), cb);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * txncheck gate → txnpost → txndelete. Reserves funding coins for the txn's lifetime.
     *
     * Runs behind {@link SignGate}: this app must never have two signing chains in flight at once.
     * Signing one key concurrently makes the node issue the SAME one-time leaf for two different
     * transactions, which leaks that leaf's private key — confirmed on a live node, 7 of 64 keys.
     */
    private void postGated(String txid, List<String> steps, List<String> fundIds, Result cb) {
        SignGate.submit(gate -> {
            final Result gated = new Result() {
                @Override public void onPosted(String txpowid) { gate.free(); cb.onPosted(txpowid); }
                @Override public void onFailed(String message) { gate.free(); cb.onFailed(message); }
            };
            for (String id : fundIds) inflight.put(id, chainBlock);
            CmdChain.run(node, new ArrayList<>(steps), "txndelete id:" + txid, new CmdChain.Done() {
                @Override public void ok(JSONObject last) {
                    node.cmd("txnexport id:" + txid, new NodeApi.Cb() {
                        @Override public void onResult(JSONObject exported) {
                            if (txnBytes(exported) > 60 * 1024) {
                                inflight.keySet().removeAll(fundIds);
                                node.cmd("txndelete id:" + txid, null);
                                gated.onFailed("Transaction is too large — reduce pools/orders or consolidate wallet coins");
                                return;
                            }
                            checkAndPost(txid, fundIds, gated);
                        }
                        @Override public void onError(String message) {
                            inflight.keySet().removeAll(fundIds);
                            node.cmd("txndelete id:" + txid, null);
                            gated.onFailed(message);
                        }
                    });
                }
                @Override public void fail(String message) {
                    inflight.keySet().removeAll(fundIds);
                    gated.onFailed(message);
                }
            });
        });
    }

    private void checkAndPost(String txid, List<String> fundIds, Result cb) {
        node.cmd("txncheck id:" + txid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject last) {
                JSONObject resp = last == null ? null : last.optJSONObject("response");
                JSONObject valid = resp == null ? null : resp.optJSONObject("valid");
                // gate on the VERDICT object (top-level `scripts` is a COUNT); validamounts
                // is read from whichever object carries it, defaulting true only when absent
                boolean scripts = valid != null && valid.optBoolean("scripts", false);
                boolean basic = valid != null && valid.optBoolean("basic", false);
                boolean mmr = valid != null && valid.optBoolean("mmrproofs", false);
                boolean amounts = true;
                if (valid != null && valid.has("validamounts")) amounts = valid.optBoolean("validamounts", false);
                else if (resp != null && resp.has("validamounts")) amounts = resp.optBoolean("validamounts", false);
                // txncheck's script run only feeds the signature KEYS into SIGNEDBY — it does
                // not verify the signatures themselves. Without this, an invalid or
                // key-exhausted signature sails through valid.scripts and we post a txn that
                // consensus silently drops (for a GTC relock that means we'd believe the order
                // was renewed and suppress the retry while it marches toward expiry).
                boolean sigs = resp == null || resp.optBoolean("allsignaturesvalid", true);
                if (!scripts || !basic || !amounts || !mmr || !sigs) {
                    inflight.keySet().removeAll(fundIds);
                    node.cmd("txndelete id:" + txid, null);
                    cb.onFailed("Transaction failed validation (scripts=" + scripts
                            + " basic=" + basic + " amounts=" + amounts + " mmr=" + mmr
                            + " sigs=" + sigs + ")");
                    return;
                }
                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        node.cmd("txndelete id:" + txid, null);
                        if (json.optBoolean("status", false) || json.optBoolean("pending", false)) {
                            cb.onPosted(Util.extractTxpowid(json, txid));
                        } else {
                            inflight.keySet().removeAll(fundIds);
                            cb.onFailed(json.optString("error", "txnpost failed"));
                        }
                    }
                    @Override public void onError(String message) {
                        // every txn error path deletes the pending txn — safe even if the post
                        // did land, since posting has already happened by this point
                        node.cmd("txndelete id:" + txid, null);
                        inflight.keySet().removeAll(fundIds);
                        cb.onFailed(message);
                    }
                });
            }
            @Override public void onError(String message) {
                inflight.keySet().removeAll(fundIds);
                node.cmd("txndelete id:" + txid, null);
                cb.onFailed(message);
            }
        });
    }

    private static int txnBytes(JSONObject exported) {
        JSONObject resp = exported == null ? null : exported.optJSONObject("response");
        String data = resp == null ? "" : resp.optString("data", "");
        if (data.startsWith("0x") || data.startsWith("0X")) data = data.substring(2);
        return data.length() / 2;
    }

    public interface CoinsCb { void found(List<JSONObject> coins); }

    private static BigDecimal coinValue(JSONObject c) {
        String tokenid = c.optString("tokenid", "0x00");
        String amt = "0x00".equals(tokenid) ? c.optString("amount", "0")
                : c.optString("tokenamount", c.optString("amount", "0"));
        return Util.dec(amt);
    }

    /** Greedy largest-first selection of confirmed, stateless, un-reserved wallet coins.
     *  `checkmempool:true` is what `send` itself uses — without it a coin already committed by
     *  a pending order placement (or by the background service) is freely selectable, and one
     *  of the two transactions dies silently while both report success. */
    public void findCoins(String tokenid, BigDecimal need, CoinsCb cb) {
        findCoins(tokenid, need, java.util.Collections.emptySet(), Integer.MAX_VALUE, cb);
    }

    public void findCoins(String tokenid, BigDecimal need, java.util.Set<String> excludeAddrsLower,
                          int maxInputs, CoinsCb cb) {
        lastFundError = null;
        node.cmd("coins relevant:true sendable:true checkmempool:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                FundingPick pick = selectFundingCoins(resp, need, excludeAddrsLower, maxInputs, inflight.keySet());
                inflight.keySet().retainAll(pick.presentCoinIds);   // drop reservations for spent coins
                if (pick.error != null) lastFundError = pick.error;
                cb.found(pick.coins);
            }
            @Override public void onError(String message) {
                // Distinguish "wallet too fragmented to enumerate" from "no funds" — this is
                // the ONE query whose size scales with the user's coin count, and reporting it
                // as "insufficient funds" on a fully funded wallet is undiagnosable.
                lastFundError = NodeApi.ERR_TOO_LONG.equals(message)
                        ? "Your wallet has too many coins to scan — consolidate them and retry."
                        : null;
                cb.found(null);
            }
        });
    }

    static final class FundingPick {
        final List<JSONObject> coins;
        final java.util.Set<String> presentCoinIds;
        final String error;

        FundingPick(List<JSONObject> coins, java.util.Set<String> presentCoinIds, String error) {
            this.coins = coins;
            this.presentCoinIds = presentCoinIds;
            this.error = error;
        }
    }

    static FundingPick selectFundingCoins(Object resp, BigDecimal need, java.util.Set<String> excludeAddrsLower,
                                          int maxInputs, java.util.Set<String> inflightIds) {
        java.util.Set<String> present = new java.util.LinkedHashSet<>();
        if (!(resp instanceof org.json.JSONArray)) return new FundingPick(null, present, null);
        org.json.JSONArray arr = (org.json.JSONArray) resp;
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String coinid = c.optString("coinid");
            present.add(coinid);
            Object st = c.opt("state");
            boolean hasState = st instanceof org.json.JSONArray
                    ? ((org.json.JSONArray) st).length() > 0
                    : st instanceof JSONObject && ((JSONObject) st).length() > 0;
            if (hasState) continue;
            String addr = c.optString("address", "");
            if (addr != null && excludeAddrsLower != null && excludeAddrsLower.contains(addr.toLowerCase())) continue;
            if (inflightIds != null && inflightIds.contains(coinid)) continue;
            candidates.add(c);
        }
        candidates.sort((a, b) -> coinValue(b).compareTo(coinValue(a)));
        List<JSONObject> pick = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (JSONObject c : candidates) {
            pick.add(c);
            sum = sum.add(coinValue(c));
            if (pick.size() > maxInputs) {
                return new FundingPick(null, present,
                        "Wallet funding needs more than " + maxInputs + " inputs — consolidate coins and retry.");
            }
            if (sum.compareTo(need) >= 0) return new FundingPick(pick, present, null);
        }
        return new FundingPick(null, present, null);
    }

    /** Set when the last findCoins failure had a specific cause worth showing the user. */
    private volatile String lastFundError;

    public String takeFundError() {
        String e = lastFundError;
        lastFundError = null;
        return e;
    }
}

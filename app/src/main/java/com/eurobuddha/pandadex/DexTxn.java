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
        /** Persist intent before signing. Returning false prevents submission. */
        default boolean onPrepared(String transactionHandle) { return true; }
        /** Persist the transition before asking the node to post. */
        default boolean beforePost() { return true; }
        void onPosted(String txpowid);
        void onFailed(String message);
    }

    private final NodeApi node;
    private final DexDb db;
    private String myPubkey = "";
    private String myHexAddr = "";
    /** Retained host API. Claims are process-wide and cannot be pruned by a token scan. */
    private long chainBlock;
    public void setChainBlock(long b) { chainBlock = b; CoinLock.prune(); }

    public DexTxn(NodeApi node, DexDb db) {
        this.node = node;
        this.db = db;
    }

    public void setIdentity(String pubkey, String hexAddr) {
        myPubkey = FundingCoins.hex(pubkey) ? pubkey : "";
        myHexAddr = FundingCoins.hex(hexAddr) ? hexAddr : "";
    }

    public String pubkey() { return myPubkey; }

    public String hexAddr() { return myHexAddr; }

    // ------------------------------------------------------------------ create

    /**
     * Place an order. buy=true locks mxUSDT wanting MINIMA; sell locks MINIMA wanting mxUSDT.
     * Bounded state-free funding, validated and posted through the shared signing gate.
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
        if (minimaAmount == null || price == null || minRemMinima == null
                || price.signum() <= 0 || minRemMinima.signum() < 0
                || Math.abs((long)price.scale()) > 44 || price.precision() > 44
                || Math.abs((long)minRemMinima.scale()) > 44 || minRemMinima.precision() > 44
                || minimaAmount.scale() > PriceMath.MINIMA_DP
                || !FundingCoins.hex(orderId)) {
            cb.onFailed("Invalid order amounts or identity. Nothing was sent.");
            return null;
        }
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
        BigDecimal lock = orderLockedAmount(buy,minimaAmount,price);
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
        final String payout = myHexAddr;
        final String token = buy ? DexContract.USDT_ID : Util.MINIMA_TOKENID;
        final String[] state = {myPubkey, payout, want.toPlainString(),
                buy ? Util.MINIMA_TOKENID : DexContract.USDT_ID, orderId, buy ? "0" : "1",
                price.toPlainString(), gtc ? "1" : "0", minRem.toPlainString()};
        findCoins(token, lock, coins -> {
            if (coins == null) {
                String why = takeFundError();
                cb.onFailed(why == null ? "Insufficient state-free funding for this order" : why);
                return;
            }
            String txid = "create_" + System.nanoTime();
            List<String> steps = createOrderSteps(txid, token, lock, payout, state, coins);
            List<String> ids = new ArrayList<>();
            for (JSONObject c : coins) ids.add(c.optString("coinid"));
            if (db == null) { cb.onFailed("Order receipt storage is unavailable. Nothing was signed."); return; }
            Pending.Row receipt = CreationEvidence.prepare(buy, minimaAmount, price, token, lock,
                    payout, state, coins, chainBlock);
            postGated(txid, steps, ids, db.pendingReceipts().creationResult(receipt, cb));
        });
        return orderId;
    }

    /** Shared exact funding amount for transaction construction and durable maker intent. */
    static BigDecimal orderLockedAmount(boolean buy,BigDecimal minima,BigDecimal price) {
        return buy ? PriceMath.up(minima.multiply(price,PriceMath.MC),PriceMath.USDT_DP)
                : PriceMath.down(minima,PriceMath.MINIMA_DP);
    }

    /** Same explicit funding/output/change pattern as fillSweep; change must never carry order state. */
    static List<String> createOrderSteps(String txid, String token, BigDecimal lock, String payout,
                                         String[] state, List<JSONObject> coins) {
        if (!FundingCoins.hex(token) || !FundingCoins.hex(payout) || !amountOk(lock)
                || state == null || state.length != 9 || coins == null || coins.isEmpty()
                || coins.size() > FundingCoins.SAFE_COINS)
            throw new IllegalArgumentException("Invalid order funding");
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        BigDecimal total = BigDecimal.ZERO;
        for (JSONObject c : coins) {
            FundingCoins.fundingCoin(c);
            if (!FundingCoins.hex(c.optString("coinid")) || !token.equalsIgnoreCase(c.optString("tokenid")))
                throw new IllegalArgumentException("Wrong order funding token or input identity");
            total = total.add(coinValue(c));
            steps.add("txninput id:" + txid + " coinid:" + c.optString("coinid"));
        }
        if (total.compareTo(lock) < 0) throw new IllegalArgumentException("Insufficient order funding");
        String tok = Util.isMinima(token) ? "" : " tokenid:" + token;
        steps.add("txnoutput id:" + txid + " amount:" + lock.toPlainString()
                + " address:" + DexContract.ADDR_V5 + tok + " storestate:true");
        BigDecimal change = total.subtract(lock);
        if (change.signum() > 0) steps.add("txnoutput id:" + txid + " amount:" + change.toPlainString()
                + " address:" + payout + tok + " storestate:false");
        for (int i = 0; i < state.length; i++) steps.add(stateStep(txid, i, state[i]));
        steps.add("txnbasics id:" + txid);
        steps.add("txnsign id:" + txid + " publickey:auto");
        return steps;
    }

    /** Funding preparation uses the same bounded, state-free selector as trades. */
    public void splitFunding(String token, BigDecimal amount, Result cb) {
        final String payout = myHexAddr;
        if (!FundingCoins.hex(payout) || !FundingCoins.hex(token) || !amountOk(amount)
                || amount.scale() > PriceMath.MINIMA_DP
                || amount.compareTo(new BigDecimal("0.0000001")) < 0) {
            cb.onFailed("Invalid wallet split amount or address. Nothing was sent."); return;
        }
        findCoins(token, amount, coins -> {
            if (coins == null) { String error = takeFundError(); cb.onFailed(error == null ? "Insufficient state-free funding" : error); return; }
            String txid = "split_" + System.nanoTime();
            List<String> ids = new ArrayList<>();
            for (JSONObject c : coins) ids.add(c.optString("coinid"));
            postGated(txid, splitFundingSteps(txid, token, amount, payout, coins), ids, cb);
        });
    }

    static List<String> splitFundingSteps(String txid, String token, BigDecimal amount, String payout, List<JSONObject> coins) {
        BigDecimal piece = amount.divide(new BigDecimal(SelfSplit.COUNT), PriceMath.MINIMA_DP, java.math.RoundingMode.DOWN);
        if (piece.signum() <= 0) throw new IllegalArgumentException("Split outputs would be dust");
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        BigDecimal total = BigDecimal.ZERO;
        for (JSONObject c : coins) {
            steps.add("txninput id:" + txid + " coinid:" + c.optString("coinid"));
            total = total.add(coinValue(c));
        }
        if (total.compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient split funding");
        String suffix = " address:" + payout + (Util.isMinima(token) ? "" : " tokenid:" + token) + " storestate:false";
        for (int i = 0; i < SelfSplit.COUNT; i++) {
            BigDecimal output = i == SelfSplit.COUNT - 1 ? amount.subtract(piece.multiply(new BigDecimal(SelfSplit.COUNT - 1))) : piece;
            steps.add("txnoutput id:" + txid + " amount:" + output.toPlainString() + suffix);
        }
        if (total.compareTo(amount) > 0) steps.add("txnoutput id:" + txid + " amount:" + total.subtract(amount).toPlainString() + suffix);
        steps.add("txnbasics id:" + txid);
        steps.add("txnsign id:" + txid + " publickey:auto");
        return steps;
    }

    // ------------------------------------------------------------------ sweep fill

    /** Execute a planned sweep (proven shape: order inputs first, index-matched payments,
     *  single partial last with its remainder at output k). */
    public void fillSweep(SweepPlanner.Plan plan, Result cb) {
        final String payoutAddress = myHexAddr;
        if (plan == null || plan.isEmpty()) { cb.onFailed("Nothing to fill"); return; }
        for (SweepPlanner.Take t : plan.takes) {
            if (t == null || !safeOrder(t.order) || !t.order.fillable()) {
                cb.onFailed("Invalid order data. Refresh the book before trading."); return;
            }
        }
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
                    + " address:" + payoutAddress
                    + ("0x00".equals(proceedsTok) ? "" : " tokenid:" + proceedsTok)
                    + " storestate:false");
            BigDecimal change = fundTotal.subtract(fNeeded);
            if (change.signum() > 0) {
                steps.add("txnoutput id:" + txid + " amount:" + change.toPlainString()
                        + " address:" + payoutAddress
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
        final String payoutAddress = myHexAddr;
        if (plan == null || plan.isEmpty()) { cb.onFailed("Nothing to fill"); return; }
        for (SweepPlanner.Take t : plan.orderTakes) {
            if (t == null || !safeOrder(t.order) || !t.order.fillable()) {
                cb.onFailed("Invalid order data. Refresh the book before trading."); return;
            }
        }
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
                    () -> buildComposite(plan, prep, takerBuys, coins, payoutAddress, cb), message -> {
                        CoinLock.release(coins); cb.onFailed(message);
                    });
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
                                List<JSONObject> coins, String payoutAddress, Result cb) {
        CompositeBuild built = buildCompositeSteps("combo_" + System.nanoTime(), payoutAddress, plan, prep, takerBuys, coins);
        postGated(built.txid, built.steps, built.fundIds, cb);
    }

    static final class CompositeBuild {
        String txid;
        List<String> steps = new ArrayList<>();
        List<String> fundIds = new ArrayList<>();
    }

    static CompositeBuild buildCompositeSteps(String txid, String payoutAddress, CompositeRouter.Plan plan,
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
                + " address:" + payoutAddress
                + (Util.MINIMA_TOKENID.equals(proceedsTok) ? "" : " tokenid:" + proceedsTok)
                + " storestate:false");
        BigDecimal change = fundTotal.subtract(prep.needed);
        if (change.signum() > 0) {
            steps.add("txnoutput id:" + txid + " amount:" + amt(change)
                    + " address:" + payoutAddress
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

    private void ensureTrackedPools(List<PoolRouter.Alloc> allocs, int i, Runnable then, java.util.function.Consumer<String> fail) {
        if (i >= allocs.size()) { then.run(); return; }
        Pool p = allocs.get(i).pool;
        if (!PoolCovenant.validParams(p.opk, p.oadr, p.tok, p.kmin) || !FundingCoins.hex(p.address)) {
            fail.accept("Invalid pool recipe. Nothing was signed."); return;
        }
        String script = PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin);
        if (p.covenantScript != null && !p.covenantScript.isEmpty() && !script.equals(p.covenantScript)) {
            fail.accept("Pool covenant does not match its recipe. Nothing was signed."); return;
        }
        // Keep pool scripts known for transaction construction without making every reserve
        // coin at that address look wallet-owned/relevant. This is the same load-bearing
        // trackall:false rule as the V5 order covenant.
        node.cmd(poolScriptRegisterCommand(p, script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j == null ? null : j.optJSONObject("response");
                if (!TxValidation.truthy(j, "status") || r == null || !p.address.equalsIgnoreCase(r.optString("address"))) {
                    fail.accept("Pool registration did not verify. Nothing was signed."); return;
                }
                ensureTrackedPools(allocs, i + 1, then, fail);
            }
            @Override public void onError(String m) { fail.accept(m); }
        });
    }

    static String poolScriptRegisterCommand(Pool p, String script) {
        return "newscript trackall:false script:" + Util.scriptArg(script);
    }

    private static String stateStep(String txid, int port, String value) {
        if (!FundingCoins.hex(value) && (value == null || value.length() > 100
                || !value.matches("[0-9]+(?:\\.[0-9]+)?")))
            throw new IllegalArgumentException("Invalid transaction state literal");
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
        if (!safeOrder(o)) { cb.onFailed("Invalid order data. Nothing was signed."); return; }
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
        postCancellation(txid,steps,java.util.Collections.singletonList(o),cb);
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
        for (Order5 o : orders) if (!safeOrder(o)) {
            cb.onFailed("Invalid order data. Nothing was signed."); return;
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
        postCancellation(txid,steps,orders,cb);
    }

    private void postCancellation(String txid,List<String> steps,List<Order5> orders,Result cb) {
        Result journal;
        try {
            if(db==null)throw new IllegalStateException("Receipt storage unavailable");
            journal=db.pendingReceipts().cancellationResult(orders,chainBlock,cb);
        }catch(RuntimeException failure){cb.onFailed("Could not prepare cancellation receipts. Nothing was signed.");return;}
        postGated(txid,steps,new ArrayList<>(),journal);
    }

    /** Atomic in-place re-lock: GTC renew (newWant null) or edit (newWant set). ONE txn —
     *  the coin never leaves the book (the V5 owner branch; proven in Phase B chunk D). */
    public void relock(Order5 o, BigDecimal newWant, Result cb) {
        if (!safeOrder(o)) { cb.onFailed("Invalid order data. Nothing was signed."); return; }
        // A successful re-lock is recognised from its successor coin by FillTape. Do not add a
        // cancellation marker here: a transaction accepted to the mempool may still lose to a
        // taker fill, which must remain recordable.
        BigDecimal want = newWant == null ? o.wantAmt : newWant;
        if (!amountOk(want) || want.stripTrailingZeros().scale() > 8) {
            cb.onFailed("Invalid replacement amount. Nothing was signed."); return;
        }
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
        Result journal;
        try {
            if(db==null)throw new IllegalStateException("Receipt storage unavailable");
            journal=db.pendingReceipts().relockResult(o,want,chainBlock,cb);
        }catch(RuntimeException failure){cb.onFailed("Could not prepare relock receipt. Nothing was signed.");return;}
        postGated(txid,steps,new ArrayList<>(),journal);
    }

    /** Third-party sweep of an expired order back to its maker (book hygiene; COINAGE path). */
    public void collectExpired(Order5 o, Result cb) {
        if (!safeOrder(o)) { cb.onFailed("Invalid order data. Nothing was signed."); return; }
        String txid = "collect_" + System.nanoTime();
        List<String> steps = new ArrayList<>();
        steps.add("txncreate id:" + txid);
        steps.add("txninput id:" + txid + " coinid:" + o.coinid);
        steps.add("txnoutput id:" + txid + " amount:" + o.locked.toPlainString()
                + " address:" + o.wantAddr
                + ("0x00".equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok) + " storestate:false");
        steps.add("txnsign id:" + txid + " publickey:auto");
        steps.add("txnbasics id:" + txid);
        postCancellation(txid, steps, java.util.Collections.singletonList(o), cb);
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
        List<String> inputs = new ArrayList<>();
        for (String command : steps) {
            String invalid = CommandSafety.failure(command);
            if (invalid != null) { cb.onFailed(invalid); return; }
            if (command.startsWith("txninput ")) {
                String id = "";
                for (String part : command.split("\\s+")) if (part.startsWith("coinid:")) id = part.substring(7);
                if (!FundingCoins.hex(id)) { cb.onFailed("Invalid transaction input. Nothing was signed."); return; }
                inputs.add(id);
            }
        }
        if (inputs.isEmpty() || inputs.size() > FundingCoins.MAX_INPUTS) {
            cb.onFailed("Too many transaction inputs. Consolidate funding or use fewer orders/pools."); return;
        }
        if (!cb.onPrepared(txid)) { cb.onFailed("Could not save transaction intent. Nothing was signed."); return; }
        if (!CoinLock.claimInputs(inputs)) {
            cb.onFailed("An input is already in another queued transaction or appears twice. Wait and refresh."); return;
        }
        SignGate.submit(gate -> {
            final Result gated = new Result() {
                private boolean completed;
                private void finish(String id, String error) {
                    if (completed) return;
                    completed = true;
                    CoinLock.finishInputs(inputs);
                    try { if (error == null) cb.onPosted(id); else cb.onFailed(error); }
                    finally { gate.free(); }
                }
                public boolean beforePost() { return cb.beforePost(); }
                public void onPosted(String id) { finish(id, null); }
                public void onFailed(String message) { finish(null, message); }
            };
            CmdChain.run(node, new ArrayList<>(steps), "txndelete id:" + txid, new CmdChain.Done() {
                public void ok(JSONObject last) { checkAndPost(txid, gated); }
                public void fail(String message) { gated.onFailed(message); }
            });
        });
    }

    private void checkAndPost(String txid, Result cb) {
        node.cmd("txncheck id:" + txid, new NodeApi.Cb() {
            public void onResult(JSONObject checked) {
                String invalid = TxValidation.checkFailure(checked);
                if (invalid != null) {
                    node.cmd("txndelete id:" + txid, null); cb.onFailed(invalid); return;
                }
                if (!cb.beforePost()) {
                    node.cmd("txndelete id:" + txid, null);
                    cb.onFailed("Could not save submission intent. The transaction was not posted."); return;
                }
                // txnexport is NOT the serialized TxPoW, and returning its hex over IPC is costly.
                // The stock node applies the chain's actual TxPoW size limit at txnpost.
                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                    public void onResult(JSONObject reply) {
                        if (reply.optBoolean("pending", false)) {
                            cb.onFailed("Awaiting node approval; this transaction has not been submitted."); return;
                        }
                        node.cmd("txndelete id:" + txid, null);
                        if (reply.optBoolean("status", false)) cb.onPosted(Util.extractTxpowid(reply, txid));
                        else cb.onFailed(TxValidation.postError(reply));
                    }
                    public void onError(String message) {
                        if (!NodeApi.ERR_WRITE_UNCERTAIN.equals(message)) node.cmd("txndelete id:" + txid, null);
                        cb.onFailed(message);
                    }
                });
            }
            public void onError(String message) {
                node.cmd("txndelete id:" + txid, null); cb.onFailed(message);
            }
        });
    }

    static boolean amountOk(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(PriceMath.MAX_ORDER) <= 0
                && Math.abs((long)value.scale()) <= 44 && value.precision() <= 44;
    }

    static boolean safeOrder(Order5 o) {
        return o != null && FundingCoins.hex(o.coinid) && FundingCoins.hex(o.ownerPk)
                && FundingCoins.hex(o.wantAddr) && FundingCoins.hex(o.wantTok)
                && FundingCoins.hex(o.orderId) && FundingCoins.hex(o.lockedTok)
                && amountOk(o.locked) && amountOk(o.wantAmt) && o.minRem != null
                && o.minRem.signum() >= 0 && o.minRem.scale() <= 44 && o.minRem.precision() <= 44;
    }

    public interface CoinsCb { void found(List<JSONObject> coins); }

    private static BigDecimal coinValue(JSONObject c) { return FundingCoins.coinValue(c); }

    public void findCoins(String tokenid, BigDecimal need, CoinsCb cb) {
        findCoins(tokenid, need, java.util.Collections.emptySet(), 8, cb);
    }

    public void findCoins(String tokenid, BigDecimal need, java.util.Set<String> excludeAddrsLower,
                          int maxInputs, CoinsCb cb) {
        lastFundError = null;
        FundingCoins.select(node::cmd, tokenid, need, excludeAddrsLower, maxInputs, new FundingCoins.Done() {
            public void ok(List<JSONObject> coins, BigDecimal sum) { cb.found(coins); }
            public void fail(String message) { lastFundError = message; cb.found(null); }
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
            try { FundingCoins.fundingCoin(c); }
            catch (IllegalArgumentException invalid) { return new FundingPick(null, present, invalid.getMessage()); }
            if (c.optBoolean("spent", false)) continue;
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

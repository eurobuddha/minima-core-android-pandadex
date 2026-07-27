package com.eurobuddha.pandadex;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public final class DexTxn {

    public interface Result {
        void onPosted(String txpowid);
        void onFailed(String message);
    }

    private final NodeApi node;
    private final DexDb db;
    private String myPubkey = "";
    private String myHexAddr = "";
    /** Funding coins used by posted-but-unconfirmed txns — never double-select. */
    private final java.util.Set<String> inflight = ConcurrentHashMap.newKeySet();

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
    public void createOrder(boolean buy, BigDecimal minimaAmount, BigDecimal price,
                            boolean gtc, BigDecimal minRemainder, Result cb) {
        if (minimaAmount.compareTo(PriceMath.MIN_ORDER_MINIMA) < 0) {
            cb.onFailed("Below minimum order (" + PriceMath.MIN_ORDER_MINIMA + " MINIMA)");
            return;
        }
        BigDecimal usdt = PriceMath.up(minimaAmount.multiply(price, PriceMath.MC), PriceMath.USDT_DP);
        BigDecimal lock = buy ? usdt : PriceMath.down(minimaAmount, PriceMath.MINIMA_DP);
        BigDecimal want = buy ? PriceMath.down(minimaAmount, PriceMath.MINIMA_DP) : usdt;
        String orderId = "0x" + Long.toHexString(System.currentTimeMillis()).toUpperCase();
        String state = "{\"0\":\"" + myPubkey + "\",\"1\":\"" + myHexAddr + "\","
                + "\"2\":\"" + want.toPlainString() + "\","
                + "\"3\":\"" + (buy ? "0x00" : DexContract.USDT_ID) + "\","
                + "\"4\":\"" + orderId + "\",\"5\":\"" + (buy ? "0" : "1") + "\","
                + "\"6\":\"" + price.toPlainString() + "\",\"7\":\"" + (gtc ? "1" : "0") + "\","
                + "\"8\":\"" + minRemainder.toPlainString() + "\"}";
        String cmd = "send amount:" + lock.toPlainString() + " address:" + DexContract.ADDR_V5
                + (buy ? " tokenid:" + DexContract.USDT_ID : "") + " state:" + state;
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (json.optBoolean("status", false) || json.optBoolean("pending", false)) {
                    cb.onPosted(Util.extractTxpowid(json, orderId));
                } else {
                    cb.onFailed(json.optString("error", "send failed"));
                }
            }
            @Override public void onError(String message) { cb.onFailed(message); }
        });
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
            if (coins == null) { cb.onFailed("Insufficient funds for sweep"); return; }
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

    private static String stateStep(String txid, int port, String value) {
        return "txnstate id:" + txid + " port:" + port + " value:" + value;
    }

    // ------------------------------------------------------------------ owner ops

    /** Cancel: owner-signed refund of the whole coin to the maker wallet (token-aware). */
    public void cancel(Order5 o, Result cb) {
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

    /** Atomic in-place re-lock: GTC renew (newWant null) or edit (newWant set). ONE txn —
     *  the coin never leaves the book (the V5 owner branch; proven in Phase B chunk D). */
    public void relock(Order5 o, BigDecimal newWant, Result cb) {
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

    /** txncheck gate → txnpost → txndelete. Reserves funding coins for the txn's lifetime. */
    private void postGated(String txid, List<String> steps, List<String> fundIds, Result cb) {
        inflight.addAll(fundIds);
        List<String> chain = new ArrayList<>(steps);
        chain.add("txncheck id:" + txid);
        CmdChain.run(node, chain, "txndelete id:" + txid, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
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
                if (!scripts || !basic || !amounts || !mmr) {
                    inflight.removeAll(fundIds);
                    node.cmd("txndelete id:" + txid, null);
                    cb.onFailed("Transaction failed validation (scripts=" + scripts
                            + " basic=" + basic + " amounts=" + amounts + " mmr=" + mmr + ")");
                    return;
                }
                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        node.cmd("txndelete id:" + txid, null);
                        if (json.optBoolean("status", false) || json.optBoolean("pending", false)) {
                            cb.onPosted(Util.extractTxpowid(json, txid));
                        } else {
                            inflight.removeAll(fundIds);
                            cb.onFailed(json.optString("error", "txnpost failed"));
                        }
                    }
                    @Override public void onError(String message) {
                        inflight.removeAll(fundIds);
                        cb.onFailed(message);
                    }
                });
            }
            @Override public void fail(String message) {
                inflight.removeAll(fundIds);
                cb.onFailed(message);
            }
        });
    }

    public interface CoinsCb { void found(List<JSONObject> coins); }

    private static BigDecimal coinValue(JSONObject c) {
        String tokenid = c.optString("tokenid", "0x00");
        String amt = "0x00".equals(tokenid) ? c.optString("amount", "0")
                : c.optString("tokenamount", c.optString("amount", "0"));
        return Util.dec(amt);
    }

    /** Greedy largest-first selection of confirmed, stateless, un-reserved wallet coins. */
    public void findCoins(String tokenid, BigDecimal need, CoinsCb cb) {
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                if (!(resp instanceof org.json.JSONArray)) { cb.found(null); return; }
                org.json.JSONArray arr = (org.json.JSONArray) resp;
                Map<String, JSONObject> present = new LinkedHashMap<>();
                List<JSONObject> candidates = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    present.put(c.optString("coinid"), c);
                    Object st = c.opt("state");
                    boolean hasState = st instanceof org.json.JSONArray
                            ? ((org.json.JSONArray) st).length() > 0
                            : st instanceof JSONObject && ((JSONObject) st).length() > 0;
                    if (hasState) continue;
                    if (inflight.contains(c.optString("coinid"))) continue;
                    candidates.add(c);
                }
                inflight.retainAll(present.keySet());   // prune stale reservations
                candidates.sort((a, b) -> coinValue(b).compareTo(coinValue(a)));
                List<JSONObject> pick = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (JSONObject c : candidates) {
                    pick.add(c);
                    sum = sum.add(coinValue(c));
                    if (sum.compareTo(need) >= 0) { cb.found(pick); return; }
                }
                cb.found(null);
            }
            @Override public void onError(String message) { cb.found(null); }
        });
    }
}

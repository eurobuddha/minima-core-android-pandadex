package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The strongest layer of fill adjudication: read the transaction that actually spent the order
 * coin, rather than guessing from what is left unspent at the payout address.
 *
 * Two properties are load-bearing and both are asserted here.
 *
 *  1. The verdict is ORDER-LINKED. A payout of the order's exact `wantAmt` in `wantTok` to its
 *     payout address is a fill; a refund of `locked` in `lockedTok` is a cancel. No coincidence of
 *     amounts between two unrelated orders can produce a verdict, which is the failure the
 *     UTXO-based layer is vulnerable to.
 *  2. Paging is bounded. An oversized reply exceeds MAX_MESSAGE_LEN and the Binder limit behind it
 *     and kills the client with no catchable error, so a bad page must HALVE and retry rather than
 *     be retried at the same size, and the whole walk must sit under a hard call budget.
 */
public class DexHistoryTest {

    private static final String PAYOUT = "0xPAYOUT11223344556677889900AABBCCDDEEFF00112233445566778899AABB";

    /** A resting SELL: 300 MINIMA locked, wanting 15.45 mxUSDT. */
    private static Order5 sellOrder() {
        return order("0xC1", "300", "0x00", "15.45", DexContract.USDT_ID, true);
    }

    /** A resting BUY: 15.45 mxUSDT locked, wanting 300 MINIMA. */
    private static Order5 buyOrder(String coinid) {
        return order(coinid, "15.45", DexContract.USDT_ID, "300", "0x00", false);
    }

    private static Order5 order(String coinid, String locked, String lockedTok,
                                String want, String wantTok, boolean sell) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", lockedTok);
            if ("0x00".equals(lockedTok)) c.put("amount", locked);
            else { c.put("amount", "0.000001"); c.put("tokenamount", locked); }
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xMAKER");
            st.put("1", PAYOUT);
            st.put("2", want);
            st.put("3", wantTok);
            st.put("4", "0xORD");
            st.put("5", sell ? "1" : "0");
            st.put("6", "0.0515");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject out(String address, String tokenid, String amount, String tokenamount) {
        try {
            JSONObject o = new JSONObject();
            o.put("address", address);
            o.put("tokenid", tokenid);
            o.put("amount", amount);
            if (tokenamount != null) o.put("tokenamount", tokenamount);
            return o;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONArray outs(JSONObject... rows) {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        return a;
    }

    // ---------------------------------------------------------------- verdicts

    @Test public void takerPayingTheWantedTokenIsAFill() {
        Order5 o = sellOrder();
        JSONArray outputs = outs(out(PAYOUT, DexContract.USDT_ID, "0.0000001545", "15.45"));
        assertEquals(FillVerifier.Verdict.FILLED, DexHistory.verdictFor(outputs, o));
    }

    @Test public void ownerTakingTheLockedTokenBackIsACancel() {
        Order5 o = sellOrder();
        JSONArray outputs = outs(out(PAYOUT, "0x00", "300", null));
        assertEquals(FillVerifier.Verdict.CANCELLED, DexHistory.verdictFor(outputs, o));
    }

    /** The whole point of reading the spending transaction: this is decided by WHICH coin the
     *  transaction produced, not by what happens to be unspent afterwards. */
    @Test public void aPayoutToSomebodyElseIsNotEvidence() {
        Order5 o = sellOrder();
        JSONArray outputs = outs(out("0xSOMEONEELSE", DexContract.USDT_ID, "0.0000001545", "15.45"));
        assertNull(DexHistory.verdictFor(outputs, o));
    }

    @Test public void theRightTokenAtTheWrongAmountIsNotEvidence() {
        Order5 o = sellOrder();
        JSONArray outputs = outs(out(PAYOUT, DexContract.USDT_ID, "0.0000001500", "15.00"));
        assertNull(DexHistory.verdictFor(outputs, o));
    }

    @Test public void noOutputsMeansNoVerdictRatherThanAGuess() {
        assertNull(DexHistory.verdictFor(new JSONArray(), sellOrder()));
        assertNull(DexHistory.verdictFor((JSONArray) null, sellOrder()));
    }

    /** Token coins carry the scaled figure in `tokenamount`; reading `amount` would compare the
     *  raw colour fraction against a human-scale order size and never match. */
    @Test public void tokenValueComesFromTokenamountAndMinimaFromAmount() {
        assertEquals(0, DexHistory.value(out(PAYOUT, DexContract.USDT_ID, "0.0000001545", "15.45"))
                .compareTo(new java.math.BigDecimal("15.45")));
        assertEquals(0, DexHistory.value(out(PAYOUT, "0x00", "300", null))
                .compareTo(new java.math.BigDecimal("300")));
    }

    @Test public void missingBodyOrTxnYieldsNoCoinsRatherThanThrowing() {
        assertEquals(0, DexHistory.coinsOf(null, "inputs").length());
        assertEquals(0, DexHistory.coinsOf(new JSONObject(), "outputs").length());
    }

    // ---------------------------------------------------------------- paging

    private static JSONObject txpow(String txpowid, String spentCoin, JSONArray outputs) {
        return txpow(txpowid, new String[]{spentCoin}, outputs);
    }

    private static JSONObject txpow(String txpowid, String[] spentCoins, JSONArray outputs) {
        try {
            JSONArray ins = new JSONArray();
            for (String spentCoin : spentCoins) {
                JSONObject in = new JSONObject();
                in.put("coinid", spentCoin);
                ins.put(in);
            }
            JSONObject txn = new JSONObject();
            txn.put("inputs", ins);
            txn.put("outputs", outputs == null ? new JSONArray() : outputs);
            JSONObject body = new JSONObject();
            body.put("txn", txn);
            JSONObject tx = new JSONObject();
            tx.put("txpowid", txpowid);
            tx.put("body", body);
            return tx;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject page(JSONObject... txs) {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject t : txs) a.put(t);
            JSONObject resp = new JSONObject();
            resp.put("txpows", a);
            JSONObject j = new JSONObject();
            j.put("response", resp);
            j.put("status", true);
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject obj(Object... pairs) {
        TestJson result = new TestJson();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]);
        return result;
    }

    private static boolean confirm(String command, NodeApi.Cb cb) {
        if (!command.startsWith("txpow onchain:")) return false;
        cb.onResult(obj("status", true, "response", obj("found", true, "confirmations", 0)));
        return true;
    }

    @Test public void mempoolEntryIsNotConfirmedAndDoesNotHideMinedReplacement() {
        DexHistory h = new DexHistory((command, cb) -> {
            if (command.equals("txpow onchain:0xAAAA"))
                cb.onResult(obj("status", true, "response", obj("found", false)));
            else if (confirm(command, cb)) return;
            else cb.onResult(page(txpow("0xAAAA", "0xC1", new JSONArray()),
                    txpow("0xBBBB", "0xC1", new JSONArray())));
        });
        h.findSpends(Collections.singletonList("0xC1"), found -> {
            assertEquals("0xBBBB", found.get("0xC1").txpowid);
            assertEquals(0, found.get("0xC1").confirmations);
        });
    }

    @Test public void failedHistoryDoesNotRetryAndFailedInclusionDoesNotProveSpend() {
        final int[] calls = {0};
        DexHistory failed = new DexHistory((command, cb) -> { calls[0]++; cb.onError("offline"); });
        failed.findSpends(Collections.singletonList("0xC1"), found -> assertTrue(found.isEmpty()));
        assertEquals(1, calls[0]);
        DexHistory unconfirmed = new DexHistory((command, cb) -> {
            if (command.startsWith("txpow ")) cb.onResult(obj("status", false,
                    "response", obj("found", true, "confirmations", 9)));
            else cb.onResult(page(txpow("0xAAAA", "0xC1", new JSONArray())));
        });
        unconfirmed.findSpends(Collections.singletonList("0xC1"), found -> assertTrue(found.isEmpty()));
    }

    private static int maxOf(String command) {
        int i = command.indexOf("max:"), j = command.indexOf(' ', i);
        return Integer.parseInt(command.substring(i + 4, j < 0 ? command.length() : j));
    }

    @Test public void findsTheSpendingTransactionAndStopsAsSoonAsNothingIsWanted() {
        List<String> issued = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> {
            issued.add(command);
            if (confirm(command, cb)) return;
            cb.onResult(page(txpow("0xAABB", "0xC1", outs(out(PAYOUT, "0x00", "300", null)))));
        });
        final Map<String, DexHistory.Spend>[] got = new Map[1];
        h.findSpends(Collections.singletonList("0xC1"), found -> got[0] = found);

        assertEquals(1, got[0].size());
        assertEquals("0xAABB", got[0].get("0xC1").txpowid);
        assertEquals(0, got[0].get("0xC1").inputIndex);
        assertEquals("one page and one inclusion check", 2, issued.size());
        assertEquals(FillVerifier.Verdict.CANCELLED,
                DexHistory.verdictFor(got[0].get("0xC1"), sellOrder()));
    }

    @Test public void spendingHistoryUsesTheMatchedInputOutputIndex() {
        DexHistory h = new DexHistory((command, cb) -> { if (confirm(command, cb)) return; cb.onResult(page(txpow("0xAABB",
                new String[]{"0xASK", "0xBID"},
                outs(out(PAYOUT, "0x00", "300", null),
                     out(PAYOUT, DexContract.USDT_ID, "0.0000001545", "15.45"))))); });

        final Map<String, DexHistory.Spend>[] got = new Map[1];
        h.findSpends(Arrays.asList("0xASK", "0xBID"), found -> got[0] = found);

        Order5 ask = order("0xASK", "300", "0x00", "15.45", DexContract.USDT_ID, true);
        Order5 bid = buyOrder("0xBID");
        assertEquals(0, got[0].get("0xASK").inputIndex);
        assertEquals(1, got[0].get("0xBID").inputIndex);
        assertEquals(FillVerifier.Verdict.CANCELLED, DexHistory.verdictFor(got[0].get("0xASK"), ask));
        assertEquals("the ask refund at output 0 must not prove the bid was filled",
                FillVerifier.Verdict.CANCELLED, DexHistory.verdictFor(got[0].get("0xBID"), bid));
    }

    @Test public void asksForNothingWhenThereIsNothingToAskAbout() {
        List<String> issued = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> issued.add(command));
        final boolean[] called = {false};
        h.findSpends(Collections.emptyList(), found -> { called[0] = true; assertTrue(found.isEmpty()); });
        assertTrue("the callback must still fire", called[0]);
        assertEquals(0, issued.size());
    }

    /** A page too big to deliver must come back SMALLER at the same offset. Retrying at the same
     *  size would loop on the same undeliverable page; skipping the offset would lose whatever it
     *  held. */
    @Test public void aBadPageHalvesAndRetriesTheSameOffset() {
        List<String> issued = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> {
            issued.add(command);
            if (confirm(command, cb)) return;
            if (maxOf(command) > 2) { cb.onError(NodeApi.ERR_TOO_LONG); return; }   // anything big is undeliverable
            cb.onResult(page(txpow("0xAABB", "0xC1", new JSONArray())));
        });
        h.findSpends(Collections.singletonList("0xC1"), found -> assertEquals(1, found.size()));

        assertEquals(Arrays.asList(8, 4, 2), Arrays.asList(
                maxOf(issued.get(0)), maxOf(issued.get(1)), maxOf(issued.get(2))));
        for (String c : issued.subList(0, 3)) assertTrue(c.endsWith("offset:0"));
    }

    /** At max:1 the bad page IS one transaction. Step over it rather than let it stall everything
     *  behind it — but only a few times, then give up with what we have. */
    @Test public void aSingleOversizedTransactionIsSteppedOver() {
        List<Integer> offsets = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> {
            int max = maxOf(command);
            offsets.add(Integer.parseInt(command.substring(command.indexOf("offset:") + 7)));
            if (max > 1) { cb.onError(NodeApi.ERR_TOO_LONG); return; }
            if (offsets.size() < 8) { cb.onError(NodeApi.ERR_TOO_LONG); return; }   // several huge txns in a row
            cb.onResult(page());
        });
        final boolean[] done = {false};
        h.findSpends(Collections.singletonList("0xMISSING"), found -> {
            done[0] = true;
            assertTrue("an unfound coin must fall through, not be invented", found.isEmpty());
        });
        assertTrue(done[0]);
        assertTrue("it must advance past the oversized transaction",
                offsets.get(offsets.size() - 1) > 0);
        assertTrue("it must stop skipping rather than walk forever",
                offsets.get(offsets.size() - 1) <= DexHistory.MAX_SKIP);
    }

    /** A node that always answers, always fully, and never holds the coin must not be walked
     *  forever — history is unbounded, the budget is not. */
    @Test public void theWalkStopsAtItsCallBudget() {
        List<String> issued = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> {
            issued.add(command);
            JSONObject[] full = new JSONObject[DexHistory.PAGE_MAX];
            for (int i = 0; i < full.length; i++) full[i] = txpow("0xT" + i, "0xOTHER" + i, new JSONArray());
            cb.onResult(page(full));
        });
        final boolean[] done = {false};
        h.findSpends(Collections.singletonList("0xNEVERTHERE"), found -> done[0] = true);
        assertTrue(done[0]);
        assertEquals(DexHistory.MAX_FETCHES, issued.size());
    }

    @Test public void aShortPageMeansTheEndOfHistory() {
        List<String> issued = new ArrayList<>();
        DexHistory h = new DexHistory((command, cb) -> {
            issued.add(command);
            cb.onResult(page(txpow("0xT", "0xOTHER", new JSONArray())));   // 1 < PAGE_MAX
        });
        h.findSpends(Collections.singletonList("0xNEVERTHERE"), found -> assertTrue(found.isEmpty()));
        assertEquals(1, issued.size());
    }
}

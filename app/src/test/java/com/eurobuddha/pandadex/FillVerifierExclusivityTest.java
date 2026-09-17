package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The phantom-trade bug, and the rule that kills it.
 *
 * Every order a wallet creates carries the SAME payout address, so one address holds every rung's
 * refund, every proceeds output and all the change. A cancelled ASK rung refunds exactly its size
 * in MINIMA; a BID rung of the same size WANTS exactly that much MINIMA. Judged one order at a
 * time, that single refund coin satisfies "payment" for every bid rung that vanished in the same
 * window — one cancellation, several fabricated trades, permanently, in the only source of price
 * truth this app has.
 *
 * The fix is exclusivity: a coin is evidence for at most ONE order.
 */
public class FillVerifierExclusivityTest {

    private static final String PAYOUT = "0xPAYOUT11223344556677889900AABBCCDDEEFF00112233445566778899AABB";

    /** sell: locks `minima` MINIMA, wants `usdt` MxUSD. */
    private static Order5 ask(String coinid, String minima, String usdt, long created) {
        return order(coinid, minima, "0x00", usdt, DexContract.USDT_ID, true, created);
    }

    /** buy: locks `usdt` MxUSD, wants `minima` MINIMA. */
    private static Order5 bid(String coinid, String usdt, String minima, long created) {
        return order(coinid, usdt, DexContract.USDT_ID, minima, "0x00", false, created);
    }

    private static Order5 order(String coinid, String lockedAmt, String lockedTok,
                                String wantAmt, String wantTok, boolean sell, long created) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("created", created);
            c.put("tokenid", lockedTok);
            if ("0x00".equals(lockedTok)) c.put("amount", lockedAmt);
            else { c.put("amount", "0.000001"); c.put("tokenamount", lockedAmt); }
            JSONObject st = new JSONObject();
            st.put("0", "0xMAKER");
            st.put("1", PAYOUT);
            st.put("2", wantAmt);
            st.put("3", wantTok);
            st.put("4", "ORD" + coinid);
            st.put("5", sell ? "1" : "0");
            st.put("6", "0.05");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coin(String coinid, String tokenid, String amount, long created) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", tokenid);
            c.put("created", created);
            if ("0x00".equals(tokenid)) c.put("amount", amount);
            else { c.put("amount", "0.000001"); c.put("tokenamount", amount); }
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, JSONArray> at(JSONObject... rows) {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        Map<String, JSONArray> m = new HashMap<>();
        m.put(PAYOUT, a);
        return m;
    }

    private static List<FillVerifier.Item> items(Order5... orders) {
        List<FillVerifier.Item> out = new ArrayList<>();
        for (Order5 o : orders) out.add(new FillVerifier.Item(o.coinid, o, 0));
        return out;
    }

    /**
     * THE REGRESSION. One cancelled 300-MINIMA ask, three 300-MINIMA bids vanishing in the same
     * scan. Adjudicated independently every bid sees a 300-MINIMA coin at its payout address and
     * calls itself filled. Exclusivity gives that coin to the cancel that produced it, and the
     * three bids are left UNKNOWN — dropped, which is the correct outcome for evidence we do not
     * have.
     */
    @Test public void oneRefundCannotProvePaymentForEveryOrderOfTheSameSize() {
        Order5 cancelledAsk = ask("0xASK", "300", "15.45", 100);
        Order5 b1 = bid("0xB1", "15.00", "300", 100);
        Order5 b2 = bid("0xB2", "15.10", "300", 100);
        Order5 b3 = bid("0xB3", "15.20", "300", 100);

        Map<String, JSONArray> rows = at(coin("0xREFUND", "0x00", "300", 110));
        Map<String, FillVerifier.Verdict> v =
                FillVerifier.adjudicateBatch(rows, items(cancelledAsk, b1, b2, b3), 112);

        assertEquals(FillVerifier.Verdict.CANCELLED, v.get("0xASK"));
        assertEquals(FillVerifier.Verdict.UNKNOWN, v.get("0xB1"));
        assertEquals(FillVerifier.Verdict.UNKNOWN, v.get("0xB2"));
        assertEquals(FillVerifier.Verdict.UNKNOWN, v.get("0xB3"));
    }

    /** Two genuine payments, two genuine fills: exclusivity must not starve real evidence. */
    @Test public void distinctPaymentsStillProveDistinctFills() {
        Order5 a1 = ask("0xA1", "300", "15.45", 100);
        Order5 a2 = ask("0xA2", "400", "20.60", 100);
        Map<String, JSONArray> rows = at(
                coin("0xP1", DexContract.USDT_ID, "15.45", 110),
                coin("0xP2", DexContract.USDT_ID, "20.60", 110));

        Map<String, FillVerifier.Verdict> v = FillVerifier.adjudicateBatch(rows, items(a1, a2), 112);
        assertEquals(FillVerifier.Verdict.FILLED, v.get("0xA1"));
        assertEquals(FillVerifier.Verdict.FILLED, v.get("0xA2"));
    }

    /** Two identical orders, ONE payment coin. One of them filled; there is no evidence which, so
     *  exactly one fill may be recorded — never two off a single coin. */
    @Test public void oneCoinRecordsAtMostOneFill() {
        Order5 a1 = ask("0xA1", "300", "15.45", 100);
        Order5 a2 = ask("0xA2", "300", "15.45", 100);
        Map<String, JSONArray> rows = at(coin("0xP1", DexContract.USDT_ID, "15.45", 110));

        Map<String, FillVerifier.Verdict> v = FillVerifier.adjudicateBatch(rows, items(a1, a2), 112);
        int filled = 0;
        for (FillVerifier.Verdict verdict : v.values()) if (verdict == FillVerifier.Verdict.FILLED) filled++;
        assertEquals(1, filled);
    }

    /** Native's precedence, kept deliberately: an order with BOTH shapes present is ambiguous, and
     *  a payment wins. Preserving it is what makes the batch path a safe drop-in for the old
     *  single-order one. */
    @Test public void paymentStillWinsForAGenuinelyAmbiguousSingleOrder() {
        Order5 a = ask("0xA1", "300", "15.45", 100);
        Map<String, JSONArray> rows = at(
                coin("0xREFUND", "0x00", "300", 110),
                coin("0xPAY", DexContract.USDT_ID, "15.45", 110));

        assertEquals(FillVerifier.Verdict.FILLED,
                FillVerifier.adjudicateBatch(rows, items(a), 112).get("0xA1"));
    }

    /** Evidence must be newer than the last sighting of the order RESTING. A coin that already
     *  existed while the order was still on the book cannot be what removed it. */
    @Test public void evidenceOlderThanTheLastSightingIsNotEvidence() {
        Order5 a = ask("0xA1", "300", "15.45", 100);
        Map<String, JSONArray> rows = at(coin("0xOLD", DexContract.USDT_ID, "15.45", 105));

        List<FillVerifier.Item> seenAt108 = new ArrayList<>();
        seenAt108.add(new FillVerifier.Item(a.coinid, a, 108));
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicateBatch(rows, seenAt108, 112).get("0xA1"));

        // ...and with no last-sighting to go on, the fixed lookback still admits it
        assertEquals(FillVerifier.Verdict.FILLED,
                FillVerifier.adjudicateBatch(rows, items(a), 112).get("0xA1"));
    }

    @Test public void theLastSightingIsTheTightestOfTheThreeFloors() {
        Order5 a = ask("0xA1", "300", "15.45", 100);
        assertEquals(108, FillVerifier.earliest(a, 112, 108));      // last sighting wins
        assertEquals(100, FillVerifier.earliest(a, 108, 0));        // else the order's creation
        assertEquals(200, FillVerifier.earliest(a, 212, 0));        // else the fixed lookback
    }

    /** An unreadable reply is "we do not know", and must never be read as "there is no evidence" —
     *  which would silently convert every vanish into a recorded fill. */
    @Test public void anAddressWithNoRowsAtAllYieldsUnknown() {
        Order5 a = ask("0xA1", "300", "15.45", 100);
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicateBatch(new HashMap<>(), items(a), 112).get("0xA1"));
    }

    /** Rows always carry a coinid in practice; a synthetic one without must not collapse every
     *  claim onto a single key and starve the rest. */
    @Test public void rowsWithoutACoinidAreStillClaimedIndividually() {
        Order5 a1 = ask("0xA1", "300", "15.45", 100);
        Order5 a2 = ask("0xA2", "300", "15.45", 100);
        JSONArray rows = new JSONArray();
        try {
            rows.put(new JSONObject().put("tokenid", DexContract.USDT_ID)
                    .put("tokenamount", "15.45").put("amount", "0.000001").put("created", 110));
            rows.put(new JSONObject().put("tokenid", DexContract.USDT_ID)
                    .put("tokenamount", "15.45").put("amount", "0.000001").put("created", 110));
        } catch (Exception e) { throw new RuntimeException(e); }
        Map<String, JSONArray> byAddr = new HashMap<>();
        byAddr.put(PAYOUT, rows);

        Map<String, FillVerifier.Verdict> v = FillVerifier.adjudicateBatch(byAddr, items(a1, a2), 112);
        assertEquals(FillVerifier.Verdict.FILLED, v.get("0xA1"));
        assertEquals(FillVerifier.Verdict.FILLED, v.get("0xA2"));
    }

    @Test public void findUnclaimedSkipsWhatAnotherOrderAlreadyTook() {
        JSONArray rows = new JSONArray();
        rows.put(coin("0xP1", "0x00", "300", 110));
        rows.put(coin("0xP2", "0x00", "300", 110));
        java.util.Set<String> claimed = new HashSet<>();

        String first = FillVerifier.findUnclaimed(rows, claimed, "0x00", new java.math.BigDecimal("300"), 0);
        assertEquals("0xP1", first);
        claimed.add(first);
        assertEquals("0xP2", FillVerifier.findUnclaimed(rows, claimed, "0x00", new java.math.BigDecimal("300"), 0));
        claimed.add("0xP2");
        assertEquals(null, FillVerifier.findUnclaimed(rows, claimed, "0x00", new java.math.BigDecimal("300"), 0));
    }
}

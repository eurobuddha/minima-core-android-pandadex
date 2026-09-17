package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Resting orders are authored by STRANGERS. A malformed one must never reach a sweep: the
 * covenant rejects it silently (the txn posts and simply never mines), so a single hostile
 * order could otherwise block every sweep on the book. Regression cover for review finding M1.
 */
public class OrderValidationTest {

    private static final String GOOD_ADDR =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    /** A well-formed SELL: MINIMA locked, MxUSD wanted. */
    private static Order5 order(String coinid, boolean sell, String locked, String lockedTok,
                                String want, String wantTok, String wantAddr, String minRem) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            if ("0x00".equals(lockedTok)) c.put("amount", locked);
            else { c.put("tokenamount", locked); c.put("amount", "0.0000000000001"); }
            c.put("tokenid", lockedTok);
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xAA");
            st.put("1", wantAddr);
            st.put("2", want);
            st.put("3", wantTok);
            st.put("4", "0xAABB01");
            st.put("5", sell ? "1" : "0");
            st.put("6", "0");
            st.put("7", "1");
            st.put("8", minRem);
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Order5 goodSell() {
        return order("0xC1", true, "100", "0x00", "0.575", DexContract.USDT_ID, GOOD_ADDR, "1");
    }

    @Test public void wellFormedOrderIsFillable() {
        assertTrue(goodSell().fillable());
    }

    @Test public void sellWantingMinimaIsRejected() {
        // renders as an absurdly cheap ask, gets picked first, and would make the app emit a
        // MINIMA payment it never funded → validamounts false → every sweep dies
        Order5 o = order("0xC2", true, "100", "0x00", "0.575", "0x00", GOOD_ADDR, "1");
        assertFalse(o.fillable());
    }

    @Test public void buyWantingUsdtIsRejected() {
        Order5 o = order("0xC3", false, "0.5", DexContract.USDT_ID, "100", DexContract.USDT_ID, GOOD_ADDR, "1");
        assertFalse(o.fillable());
    }

    @Test public void payoutToTheBookAddressIsRejected() {
        // would turn the PRECEDING order's payment output into a "remainder" and flip that
        // input into the partial branch against an output built as a full-fill payment
        Order5 o = order("0xC4", true, "100", "0x00", "0.575", DexContract.USDT_ID,
                DexContract.ADDR_V5, "1");
        assertFalse(o.fillable());
    }

    @Test public void malformedPayoutAddressIsRejected() {
        assertFalse(order("0xC5", true, "100", "0x00", "0.575", DexContract.USDT_ID, "0xBB", "1").fillable());
        assertFalse(order("0xC6", true, "100", "0x00", "0.575", DexContract.USDT_ID, "", "1").fillable());
    }

    @Test public void subGrainAmountsAreRejected() {
        // the full-fill VERIFYOUT demands an exact amount that FLOORS to the grain on-chain
        Order5 o = order("0xC7", true, "100", "0x00", "0.1234567891", DexContract.USDT_ID, GOOD_ADDR, "1");
        assertFalse(o.fillable());
    }

    @Test public void subGrainMinRemainderIsRejected() {
        Order5 o = order("0xC8", true, "100", "0x00", "0.575", DexContract.USDT_ID, GOOD_ADDR, "0.1234567891");
        assertFalse(o.fillable());
    }

    @Test public void hostileOrderCannotEnterASweep() {
        List<Order5> book = new ArrayList<>();
        // the poison is the CHEAPEST ask, so an unguarded planner takes it first
        book.add(order("0xBAD", true, "100", "0x00", "0.0001", "0x00", GOOD_ADDR, "1"));
        book.add(goodSell());
        SweepPlanner.Plan p = SweepPlanner.plan(book, true, new BigDecimal("50"), null, 200);
        assertEquals(1, p.takes.size());
        assertEquals("0xC1", p.takes.get(0).order.coinid);
    }

    @Test public void ordersNearExpiryAreSkipped() {
        List<Order5> book = new ArrayList<>();
        book.add(goodSell());   // created at block 100
        // @COINAGE is evaluated at MINING time; within the margin the order may take the
        // refund branch by then and reject the whole sweep
        long nearExpiry = 100 + DexContract.EXPIRY_BLOCKS - SweepPlanner.EXPIRY_MARGIN + 1;
        assertTrue(SweepPlanner.plan(book, true, new BigDecimal("50"), null, nearExpiry).isEmpty());
        assertFalse(SweepPlanner.plan(book, true, new BigDecimal("50"), null, 200).isEmpty());
    }

    @Test public void expiryStaysInsideTheNodeVisibilityHorizon() {
        // the whole point of the re-freeze: an order must be sweepable BEFORE it ages out of
        // the node's searchable window, or nobody can ever recover it
        assertTrue(DexContract.EXPIRY_BLOCKS < DexContract.HORIZON_BLOCKS);
        assertTrue(DexContract.SCAN_DEPTH <= DexContract.HORIZON_BLOCKS);
        assertTrue(DexContract.RENEW_AT < DexContract.EXPIRY_BLOCKS);
    }
}

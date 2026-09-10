package com.eurobuddha.pandadex;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChainHeightTest {
    @Test public void fractionalCreationHeightCannotInventAnExpiredOrder() throws Exception {
        Order5 order = Order5.from(TransactionHardeningTest.orderCoin().put("created", "100.9"));
        assertNotNull(order);
        assertEquals(0, order.created);
        assertFalse(order.expired(100 + DexContract.EXPIRY_BLOCKS + 1));
        assertFalse(order.renewDue(100 + DexContract.RENEW_AT));
        assertEquals("age unavailable", order.ageLabel(200, true));
    }
    @Test public void malformedCreationHeightsStayUnknown() throws Exception {
        for (Object value : new Object[]{"9223372036854775808", "18446744073709551716",
                "-1", "1e2", "100.0", 100.5, true, JSONObject.NULL, " 100 ", new TestJson()}) {
            Order5 order = Order5.from(TransactionHardeningTest.orderCoin().put("created", value));
            assertNotNull(order);
            assertEquals(String.valueOf(value), 0, order.created);
        }
    }
    @Test public void validCreationHeightsPreserveExpiryBoundaries() throws Exception {
        for (Object value : new Object[]{100, 100L, "100"}) {
            Order5 order = Order5.from(TransactionHardeningTest.orderCoin().put("created", value));
            assertNotNull(order);
            assertEquals(100, order.created);
            assertFalse(order.expired(100 + DexContract.EXPIRY_BLOCKS));
            assertTrue(order.expired(101 + DexContract.EXPIRY_BLOCKS));
            assertFalse(order.renewDue(99 + DexContract.RENEW_AT));
            assertTrue(order.renewDue(100 + DexContract.RENEW_AT));
        }
    }

    private static JSONObject tip(Object value) {
        return new TestJson().put("status", true).put("response", new TestJson().put("block", value));
    }
    @Test public void validTipsPreserveExactLongValuesIncludingDecreasingHeights() {
        for (long height : new long[]{1, 2500000, 2499999, 9007199254740993L, Long.MAX_VALUE}) {
            assertEquals(height, ChainEvidence.tipBlock(tip(Long.toString(height))));
            assertEquals(height, ChainEvidence.tipBlock(tip(height)));
        }
    }
    @Test public void badTipNumbersCannotTruncateWrapOrUseExponentNotation() {
        for (Object value : new Object[]{"100.9", "100.0", 100.5, "9223372036854775808",
                "18446744073709551716", "1e2", "1e999999", "-1", "0", 0, true,
                JSONObject.NULL, " 100 ", "+100", new TestJson(), new org.json.JSONArray()}) {
            assertEquals(String.valueOf(value), 0, ChainEvidence.tipBlock(tip(value)));
        }
    }
    @Test public void failedRepliesCannotSupplyEvenAValidLookingTip() throws Exception {
        for (Object status : new Object[]{false, 0, "false", JSONObject.NULL, new TestJson()}) {
            assertEquals(0, ChainEvidence.tipBlock(tip("2500000").put("status", status)));
        }
        JSONObject missing = tip("2500000"); missing.remove("status");
        assertEquals(0, ChainEvidence.tipBlock(missing));
    }
    @Test public void missingAndWrongShapeRepliesStayUnknown() {
        assertEquals(0, ChainEvidence.tipBlock(null));
        assertEquals(0, ChainEvidence.tipBlock(new TestJson().put("status", true)));
        for (Object response : new Object[]{JSONObject.NULL, "2500000", new TestJson(), new org.json.JSONArray()}) {
            assertEquals(0, ChainEvidence.tipBlock(new TestJson().put("status", true).put("response", response)));
        }
    }
}

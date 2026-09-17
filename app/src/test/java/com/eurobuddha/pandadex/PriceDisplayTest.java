package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Regression cover for the first device-test round (2026-07-28 mainnet session).
 *
 * D1: a maker's orders at 0.0515 and 0.0520 collapsed into ONE ladder row.
 * D2: a taker saw the maker's remainder as its own order after a partial fill.
 */
public class PriceDisplayTest {

    private static final String ADDR =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static Order5 sellAt(String coinid, String pricePerMinima, String minima, String ownerPk) {
        try {
            BigDecimal locked = new BigDecimal(minima);
            BigDecimal want = PriceMath.up(locked.multiply(new BigDecimal(pricePerMinima),
                    PriceMath.MC), PriceMath.USDT_DP);
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked.toPlainString());
            c.put("tokenid", "0x00");
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", ownerPk);
            st.put("1", ADDR);
            st.put("2", want.toPlainString());
            st.put("3", DexContract.USDT_ID);
            st.put("4", "0xAAA1");
            st.put("5", "1");
            st.put("6", pricePerMinima);
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------------- D1: distinct prices must never merge ----------------

    @Test public void distinctPricesGetDistinctLevelsAtFinestTick() {
        // the exact case from the device test
        Order5 a = sellAt("0xC1", "0.0515", "300", "0xAA");
        Order5 b = sellAt("0xC2", "0.0520", "300", "0xAA");
        BigDecimal finest = new BigDecimal("0.00001");
        BigDecimal la = TradeView.levelPrice(a, finest);
        BigDecimal lb = TradeView.levelPrice(b, finest);
        assertNotEquals("0.0515 and 0.0520 must be separate ladder rows", la, lb);
        assertNotEquals(PriceMath.fmtPrice(la), PriceMath.fmtPrice(lb));
    }

    @Test public void veryClosePricesStillSeparateAtFinestTick() {
        Order5 a = sellAt("0xC1", "0.05150", "100", "0xAA");
        Order5 b = sellAt("0xC2", "0.05151", "100", "0xAA");
        BigDecimal finest = new BigDecimal("0.00001");
        assertNotEquals(TradeView.levelPrice(a, finest), TradeView.levelPrice(b, finest));
    }

    @Test public void coarseTickStillGroupsWhenExplicitlyChosen() {
        // grouping is opt-in, and when opted into it must still work
        Order5 a = sellAt("0xC1", "0.0515", "100", "0xAA");
        Order5 b = sellAt("0xC2", "0.0520", "100", "0xAA");
        BigDecimal tick = new BigDecimal("0.001");
        assertEquals(TradeView.levelPrice(a, tick), TradeView.levelPrice(b, tick));
    }

    @Test public void theMidIsAlwaysTheMidpointOfBestBidAndBestAsk() {
        // the centre of the book has ONE meaning and never borrows the last trade
        BigDecimal bestBid = new BigDecimal("0.050500");
        BigDecimal bestAsk = new BigDecimal("0.051000");
        BigDecimal mid = bestBid.add(bestAsk).divide(new BigDecimal(2),
                PriceMath.PRICE_DP, java.math.RoundingMode.HALF_UP);
        assertEquals("0.050750", PriceMath.fmtPrice(mid));
    }

    @Test public void asksRoundUpBidsRoundDownSoALevelNeverFlattersItsSide() {
        Order5 ask = sellAt("0xC1", "0.05151", "100", "0xAA");
        BigDecimal tick = new BigDecimal("0.0001");
        // an ask at 0.05151 must be shown at 0.0516, never 0.0515 (which would look cheaper)
        assertEquals(0, new BigDecimal("0.0516").compareTo(TradeView.levelPrice(ask, tick)));
    }

    // ---------------- D1: fixed-width price rendering ----------------

    @Test public void pricesAlwaysShowSixDecimals() {
        // six, not five: at ~0.05 MxUSD a five-decimal tick is too coarse to tell two real
        // orders apart or to show what you're actually about to trade at
        assertEquals(6, PriceMath.DISPLAY_DP);
        assertEquals("0.052000", PriceMath.fmtPrice(new BigDecimal("0.052")));
        assertEquals("0.051500", PriceMath.fmtPrice(new BigDecimal("0.0515")));
        assertEquals("0.051750", PriceMath.fmtPrice(new BigDecimal("0.05175")));
        assertEquals("1.000000", PriceMath.fmtPrice(BigDecimal.ONE));
    }

    @Test public void sixDecimalsSeparatesPricesFiveWouldMerge() {
        // 0.051750 vs 0.051751 — indistinguishable at 5dp, distinct now
        assertNotEquals(PriceMath.fmtPrice(new BigDecimal("0.051750")),
                        PriceMath.fmtPrice(new BigDecimal("0.051751")));
    }

    @Test public void trailingZerosAreNotStrippedForPrices() {
        // "0.052" vs "0.0520" read as different numbers in a ladder — the old fmt() did this
        assertEquals("0.052000", PriceMath.fmtPrice(new BigDecimal("0.0520")));
        assertEquals(PriceMath.fmtPrice(new BigDecimal("0.052")),
                PriceMath.fmtPrice(new BigDecimal("0.0520")));
    }

    @Test public void amountsStillStripTrailingZeros() {
        // amounts keep the tidy formatter — only PRICES are fixed-width
        assertEquals("300", PriceMath.fmt(new BigDecimal("300.00")));
    }

    @Test public void displayAmountsCanBeTruncatedWithoutOverstating() {
        assertEquals("12.34", PriceMath.fmtDown(new BigDecimal("12.349999"), 2));
        assertEquals("12.34", PriceMath.fmtDown(new BigDecimal("12.340001"), 2));
        assertEquals("12.00", PriceMath.fmtDown(new BigDecimal("12.009"), 2));
        assertEquals("0.00", PriceMath.fmtDown(new BigDecimal("0.009"), 2));
        assertEquals("5.00", PriceMath.fmtDown(new BigDecimal("5"), 2));
    }

    @Test public void displayAmountsCutDecimalsRatherThanFlooringNegatives() {
        assertEquals("-1.23", PriceMath.fmtDown(new BigDecimal("-1.239"), 2));
    }

    @Test public void nullPriceRendersAsDash() {
        assertEquals("—", PriceMath.fmtPrice(null));
    }

    // ---------------- D2: a stranger's order is never "mine" ----------------

    @Test public void foreignOrderIsNeverMineEvenWhenNodeMarksItRelevant() {
        Order5 makersRemainder = sellAt("0xREM", "0.0515", "150", "0xMAKERKEY");
        // simulate the node reporting the coin as relevant (what trackall:true used to do
        // for EVERY coin at the book address — it made the maker's remainder show up in the
        // taker's own open orders, on the opposite side to the trade they had just done)
        makersRemainder.markRelevant();
        Set<String> takerKeys = new HashSet<>();
        takerKeys.add("0xTAKERKEY");
        assertFalse("node relevance must NEVER stand in for ownership",
                makersRemainder.isMine(takerKeys));
        assertTrue(makersRemainder.nodeRelevant());
    }

    @Test public void ownOrderIsStillMine() {
        Order5 mine = sellAt("0xC1", "0.0515", "150", "0xMYKEY");
        Set<String> keys = new HashSet<>();
        keys.add("0xMYKEY");
        assertTrue(mine.isMine(keys));
    }

    @Test public void emptyKeySetOwnsNothing() {
        Order5 o = sellAt("0xC1", "0.0515", "150", "0xMYKEY");
        o.markRelevant();
        assertFalse(o.isMine(new HashSet<>()));
        assertFalse(o.isMine(null));
    }
}

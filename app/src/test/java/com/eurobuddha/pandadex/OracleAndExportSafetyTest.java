package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.JSONArray;
import static org.junit.Assert.*;
public class OracleAndExportSafetyTest {
    @Test public void jumpNeedsTwoConsistentReadingsAndRejectsNonfiniteValues() {
        MarketPrice.testSnapshot(1, System.currentTimeMillis());
        assertFalse(MarketPrice.acceptMid(10));
        assertEquals(1, MarketPrice.mid(), 0);
        assertFalse(MarketPrice.acceptMid(Double.NaN));
        assertFalse(MarketPrice.acceptMid(Double.POSITIVE_INFINITY));
        assertFalse("invalid readings break corroboration", MarketPrice.acceptMid(10.1));
        assertTrue(MarketPrice.acceptMid(10.1));
        assertEquals(10.1, MarketPrice.mid(), 0);
        MarketPrice.testSnapshot(0, 0);
    }
    @Test public void unconfirmedLargeMoveQuarantinesOldPriceUntilSuccessfulEvidence() {
        MarketPrice.testSnapshot(1,System.currentTimeMillis());
        assertFalse(MarketPrice.acceptMid(10));
        assertEquals(1,MarketPrice.mid(),0);
        assertFalse("old cached quote must not fund new orders",MarketPrice.fresh());
        assertTrue(MarketPrice.mustWithdraw());assertTrue(MarketPrice.quote().unavailable);
        assertTrue(MarketPrice.stateLabel().contains("unconfirmed"));
        assertFalse(MarketPrice.acceptMid(Double.NaN));
        assertTrue("a failed reading cannot clear quarantine",MarketPrice.mustWithdraw());
        assertFalse(MarketPrice.acceptMid(10));assertTrue(MarketPrice.mustWithdraw());
        assertTrue(MarketPrice.acceptMid(10.1));assertTrue(MarketPrice.fresh());assertFalse(MarketPrice.mustWithdraw());
        assertFalse(MarketPrice.acceptMid(1));assertTrue(MarketPrice.mustWithdraw());
        assertTrue("successful return to old range clears suspect move",MarketPrice.acceptMid(10));
        assertTrue(MarketPrice.fresh());MarketPrice.testSnapshot(0,0);
    }
    @Test public void invalidDepthCannotMakeAUsablePrice() throws Exception {
        assertEquals(0, MarketPrice.effectiveLevel(new JSONArray("[[\"1\",\"Infinity\"]]"), true), 0);
        assertEquals(0, MarketPrice.effectiveLevel(new JSONArray("[[\"1\",\"1\"],[\"2\",\"20\"]]"), true), 0);
        assertEquals(0.9, MarketPrice.effectiveLevel(new JSONArray("[[\"1\",\"1\"],[\"0.9\",\"30\"]]"), true), 0);
    }
    @Test public void spreadsheetTextCannotBecomeAFormula() {
        assertEquals("\"'=cmd|' /C calc'!A1\"", TradeExport.csvText("=cmd|' /C calc'!A1"));
        assertEquals("\"'  =1+1\"", TradeExport.csvText("  =1+1"));
        assertEquals("\"say \"\"hi\"\"\"", TradeExport.csvText("say \"hi\""));
    }
}

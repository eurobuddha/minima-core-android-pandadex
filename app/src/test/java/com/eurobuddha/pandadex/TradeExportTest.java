package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;

public class TradeExportTest {

    @Test public void exportSummarisesRealMoneyInAndOutFromConfirmedRowsOnly() {
        TradeExport.Snapshot s = new TradeExport.Snapshot();
        s.exportedAtMs = 1_700_000_000_000L;
        s.appVersion = "0.3.5";
        s.freeMinima = new BigDecimal("12");
        s.pendingMinima = new BigDecimal("1");
        s.lockedMinima = new BigDecimal("2");
        s.freeUsdt = new BigDecimal("3");
        s.pendingUsdt = new BigDecimal("0.5");
        s.lockedUsdt = new BigDecimal("1.5");
        s.bookMid = new BigDecimal("0.01000000");
        s.rows.add(new TradeExport.TradeRow("0xBUY", 1_700_000_001_000L, 100,
                new BigDecimal("0.01000000"), new BigDecimal("100"), true, false, ""));
        s.rows.add(new TradeExport.TradeRow("0xSELL", 1_700_000_002_000L, 101,
                new BigDecimal("0.02000000"), new BigDecimal("25"), false, true, "0xORDER"));

        TradeExport.Report r = TradeExport.build(s);

        assertEquals(2, r.tradeCount);
        assertEquals(0, new BigDecimal("100").compareTo(r.totals.minimaBought));
        assertEquals(0, new BigDecimal("25").compareTo(r.totals.minimaSold));
        assertEquals(0, new BigDecimal("1.00000000").compareTo(r.totals.usdtPaid));
        assertEquals(0, new BigDecimal("0.50000000").compareTo(r.totals.usdtReceived));
        assertEquals(0, new BigDecimal("75").compareTo(r.totals.netMinima));
        assertEquals(0, new BigDecimal("-0.50000000").compareTo(r.totals.netUsdt));
        assertEquals(0, new BigDecimal("15").compareTo(r.totals.holdingsMinima));
        assertEquals(0, new BigDecimal("5.0").compareTo(r.totals.holdingsUsdt));
        assertTrue(r.summaryTxt.contains("confirmed personal trade rows only"));
        assertTrue(r.tradesCsv.contains("\"0xBUY\""));
        assertTrue(r.tradesCsv.contains("BUY,TAKER"));
        assertTrue(r.tradesCsv.contains("SELL,MAKER"));
        assertTrue(r.tradesCsv.contains("verification_status"));
        assertTrue(r.verificationCsv.contains("verification_status"));
        assertTrue(r.reconciliationCsv.contains("\"mxUSDT paid for buys\""));
    }

    @Test public void mxusdtNotionalIsCutDownSoItNeverOverstates() {
        TradeExport.TradeRow row = new TradeExport.TradeRow("0xCUT", 0, 0,
                new BigDecimal("0.333333333"), new BigDecimal("3"), true, false, "");
        assertEquals(0, new BigDecimal("0.99999999").compareTo(TradeExport.usdtNotional(row)));
    }

    @Test public void rowsWithoutTxpowRemainLocalOnlyForVerificationExport() {
        TradeExport.Snapshot s = new TradeExport.Snapshot();
        s.rows.add(new TradeExport.TradeRow("0xLOCAL", 1_700_000_001_000L, 100,
                new BigDecimal("0.01000000"), new BigDecimal("10"), true, false, "",
                "", "POOL", "0xA 0xB", "", "LOCAL_VERIFIED", "local proof", 100));
        TradeExport.Report r = TradeExport.build(TradeExport.verifiedCopy(s));
        assertTrue(r.verificationCsv.contains("\"LOCAL_VERIFIED\""));
        assertTrue(r.verificationCsv.contains("\"POOL\""));
    }
}

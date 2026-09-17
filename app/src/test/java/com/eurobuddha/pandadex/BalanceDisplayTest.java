package com.eurobuddha.pandadex;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

public class BalanceDisplayTest {

    @Test public void balanceMetaKeepsAtomiXSendableConfirmedLockedUnconfirmedSeparation() throws Exception {
        JSONObject row = new JSONObject();
        row.put("sendable", "7.5");
        row.put("confirmed", "10");
        row.put("unconfirmed", "2.25");
        row.put("coins", 4);
        JSONObject reply = new JSONObject();
        reply.put("status", true);
        reply.put("response", row);

        MainActivity.BalanceMeta b = MainActivity.balanceMeta(reply);

        assertEquals(0, new BigDecimal("7.5").compareTo(b.sendable));
        assertEquals(0, new BigDecimal("10").compareTo(b.confirmed));
        assertEquals(0, new BigDecimal("2.5").compareTo(b.locked()));
        assertEquals(0, new BigDecimal("2.25").compareTo(b.unconfirmed));
        assertEquals(4, b.coins);
    }

    @Test public void balanceMetaTreatsMissingResponseAsZero() {
        MainActivity.BalanceMeta b = MainActivity.balanceMeta(null);

        assertEquals(0, BigDecimal.ZERO.compareTo(b.sendable));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.confirmed));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.locked()));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.unconfirmed));
        assertEquals(0, b.coins);
    }
    @Test public void failedOrIncompleteBalanceCannotClaimConfirmedFundsAreSpendable() throws Exception {
        JSONObject row=new JSONObject().put("confirmed","100");
        JSONObject reply=new JSONObject().put("status",true).put("response",row);
        assertEquals(0,MainActivity.balanceMeta(reply).sendable.signum());
        row.put("sendable","100"); reply.put("status",false);
        assertEquals(0,MainActivity.balanceMeta(reply).sendable.signum());
    }
    @Test public void malformedOrNegativeNumbersCannotBecomeFreshZeroBalances()throws Exception {
        for(String field:new String[]{"confirmed","sendable","unconfirmed","coins"}) {
            for(Object bad:new Object[]{"bad","-1",true,new JSONObject(),"1e999999"}) {
                JSONObject row=new JSONObject().put("confirmed","10").put("sendable","8").put("unconfirmed","0").put("coins","2").put(field,bad);
                assertEquals(field+"="+bad,0,MainActivity.balanceMeta(new JSONObject().put("status",true).put("response",row)).atMs);
            }
        }
    }
    @Test public void fractionalOrOverflowCoinCountsAreNotSilentlyTruncated()throws Exception {
        for(Object bad:new Object[]{"1.5","2147483648"}) {
            JSONObject row=new JSONObject().put("confirmed","10").put("sendable","8").put("coins",bad);
            assertEquals(0,MainActivity.balanceMeta(new JSONObject().put("status",true).put("response",row)).atMs);
        }
    }
    @Test public void zeroAndLegacyCoinCountRemainValidObservations()throws Exception {
        JSONObject row=new JSONObject().put("confirmed","0").put("sendable","0").put("unconfirmed","0").put("coinamount","0");
        MainActivity.BalanceMeta b=MainActivity.balanceMeta(new JSONObject().put("status",true).put("response",row));
        assertTrue(b.atMs>0);assertEquals(0,b.sendable.signum());assertEquals(0,b.coins);
    }

    @Test public void scopedStockEmptyTokenReplyIsZeroButMissingNativeReplyIsUnknown()throws Exception {
        JSONObject reply=new JSONObject().put("status",true).put("response",new org.json.JSONArray());
        MainActivity.BalanceMeta token=MainActivity.balanceMeta(reply,DexContract.USDT_ID);assertTrue(token.atMs>0);assertEquals(0,token.sendable.signum());
        assertEquals(0,MainActivity.balanceMeta(reply,Util.MINIMA_TOKENID).atMs);assertEquals(0,MainActivity.balanceMeta(reply).atMs);
        reply.put("status",false);assertEquals(0,MainActivity.balanceMeta(reply,DexContract.USDT_ID).atMs);
    }
    @Test public void scopedBalanceMustMatchItsRequestedToken()throws Exception {
        JSONObject row=new JSONObject().put("tokenid","0x00").put("confirmed","10").put("sendable","8").put("unconfirmed","0").put("coins","2");
        JSONObject reply=new JSONObject().put("status",true).put("response",new org.json.JSONArray().put(row));
        assertTrue(MainActivity.balanceMeta(reply,"0x00").atMs>0);assertEquals(0,MainActivity.balanceMeta(reply,DexContract.USDT_ID).atMs);
        row.remove("tokenid");assertEquals(0,MainActivity.balanceMeta(reply,"0x00").atMs);
    }
    @Test public void ambiguousScopedRowsCannotSelectFirstTokenSilently()throws Exception {
        JSONObject row=new JSONObject().put("tokenid","0x00").put("confirmed","10").put("sendable","8");
        JSONObject reply=new JSONObject().put("status",true).put("response",new org.json.JSONArray().put(row).put(row));
        assertEquals(0,MainActivity.balanceMeta(reply,"0x00").atMs);
    }

    @Test public void s10StockNativeDustBalanceMustLoadExactly() throws Exception {
        // Read-only `balance tokenid:0x00`, stock S10 Plus MinimaCore 1.1.2.3, 2026-09-11.
        String confirmed="2241.24000043009800000003000000000000099989999929";
        String sendable="1003.00000043009800000000000000000000099989999929";
        JSONObject row=new JSONObject().put("tokenid","0x00").put("confirmed",confirmed)
                .put("sendable",sendable).put("unconfirmed","0").put("coins","38");
        MainActivity.BalanceMeta b=MainActivity.balanceMeta(new JSONObject().put("status",true)
                .put("response",new org.json.JSONArray().put(row)),"0x00");
        assertTrue("A valid stock balance with 44 decimal places must load",b.atMs>0);
        assertEquals(new BigDecimal(confirmed),b.confirmed);
        assertEquals(new BigDecimal(sendable),b.sendable);
        assertEquals(0,new BigDecimal("1238.24000000000000000003").compareTo(b.locked()));
        assertEquals(38,b.coins);
        assertNull("Transaction parsing must keep its existing stricter limit",Util.decOr(sendable,null));
    }

    @Test public void stockBalancePrecisionIsBoundedWithoutRounding() throws Exception {
        String max="12345678901234567890."+"1".repeat(44);
        assertEquals(new BigDecimal(max),Util.balanceDecimal(max));
        for(Object bad:new Object[]{max+"1","1e999999","1e-999999","1".repeat(101),true,new JSONObject(),"NaN"})
            assertNull(String.valueOf(bad),Util.balanceDecimal(bad));
        assertNull(Util.decOr(max,null));
    }
    @Test public void startupAndConnectedLoadingDoNotAskForPairing() {
        assertEquals("CONNECTING…",MainActivity.nodeLabel(false,false));
        assertEquals("NODE ✓",MainActivity.nodeLabel(true,true));
        assertEquals("PAIR IN MINIMA → APPS",MainActivity.nodeLabel(false,true));
        assertEquals("Connecting to MinimaCore…",MainActivity.balanceMessage(false,false,false));
        assertEquals("Loading balance from MinimaCore…",MainActivity.balanceMessage(true,true,false));
        assertEquals("Could not read this balance. Tap to retry.",MainActivity.balanceMessage(true,true,true));
        assertTrue(MainActivity.balanceMessage(false,true,false).contains("enabled"));
    }

    /** 0.4.17 fixed the paired-but-loading wording and silently broke its neighbour: an unpaired
     *  user was told orders were "loading". Every placeholder must walk the whole pairing matrix. */
    @Test public void emptyPlaceholdersOnlyClaimLoadingWhilePaired() {
        String pair=MainActivity.balanceMessage(false,true,false), connecting=MainActivity.balanceMessage(false,false,false);
        assertTrue(pair.contains("enabled")); assertEquals("Connecting to MinimaCore…",connecting);
        assertEquals(connecting,MainActivity.ordersWaitingMessage(false,false));
        assertEquals(pair,MainActivity.ordersWaitingMessage(false,true));
        assertEquals(OrdersTab.WAITING_ORDERS,MainActivity.ordersWaitingMessage(true,true));
        assertFalse(OrdersTab.WAITING_ORDERS.toLowerCase().contains("connect"));
        assertEquals(connecting,MainActivity.receiveLoadingMessage(false,false));
        assertEquals(pair,MainActivity.receiveLoadingMessage(false,true));
        assertEquals("Loading receive address from MinimaCore…",MainActivity.receiveLoadingMessage(true,true));
    }

}

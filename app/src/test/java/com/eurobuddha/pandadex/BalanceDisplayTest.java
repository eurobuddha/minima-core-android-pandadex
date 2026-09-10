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

}

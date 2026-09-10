package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

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
}

package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

/**
 * A whole order coin vanishing means one of two very different things, and the tape cannot
 * tell them apart on its own. The chain can: both outcomes pay the maker's payout address,
 * but a cancel refunds the LOCKED token while a fill pays the WANTED one.
 *
 * The load-bearing rule: only positive proof of a refund suppresses a trade. Absent or
 * unreadable evidence still records the fill, because a lost trade is invisible to the user
 * while a phantom one is not.
 */
public class FillVerifierTest {

    /** A resting SELL: 300 MINIMA locked, wanting 15.45 mxUSDT. */
    private static Order5 sellOrder() {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", "0xC1");
            c.put("amount", "300");
            c.put("tokenid", "0x00");
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xMAKER");
            st.put("1", "0xPAYOUT11223344556677889900AABBCCDDEEFF00112233445566778899AABB");
            st.put("2", "15.45");
            st.put("3", DexContract.USDT_ID);
            st.put("4", "0xORD");
            st.put("5", "1");
            st.put("6", "0.0515");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coins(JSONObject... rows) {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject r : rows) a.put(r);
            JSONObject j = new JSONObject();
            j.put("response", a);
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coin(String amount, String tokenid, long created) {
        try {
            JSONObject c = new JSONObject();
            c.put("amount", amount);
            c.put("tokenid", tokenid);
            c.put("created", created);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void aRefundOfTheLockedAmountProvesACancel() {
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("300", "0x00", 105));      // the locked MINIMA came back
        assertEquals(FillVerifier.Verdict.CANCELLED,
                FillVerifier.adjudicate(reply, o, 105));
    }

    @Test public void aPaymentInTheWantedTokenProvesAFill() {
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("15.45", DexContract.USDT_ID, 105));
        assertEquals(FillVerifier.Verdict.FILLED,
                FillVerifier.adjudicate(reply, o, 105));
    }

    @Test public void noEvidenceStillCountsAsAFill() {
        // proceeds already spent, or an address holding nothing relevant — a real trade must
        // never be dropped just because the evidence moved on
        Order5 o = sellOrder();
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicate(coins(), o, 105));
    }

    @Test public void anUnreadableReplyCountsAsAFill() {
        Order5 o = sellOrder();
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicate(new JSONObject(), o, 105));
    }

    @Test public void coinsOlderThanTheOrderCannotDecideIt() {
        // a coin that was already sitting at the payout address before the order even existed
        // is not the product of spending it
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("300", "0x00", 99));
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicate(reply, o, 105));
    }

    @Test public void aMatchingCoinBeforeTheDisappearanceWindowCannotMaskAFill() {
        // Same amount and token, but it arrived long before this order vanished. The old
        // order-created cutoff called this a cancel and lost the genuine fill.
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("300", "0x00", 105));
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicate(reply, o, 120));
    }

    @Test public void paymentWinsWhenBothAreSomehowPresent() {
        // a maker who happens to hold a matching plain coin must not mask a real fill
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("300", "0x00", 105),
                                 coin("15.45", DexContract.USDT_ID, 105));
        assertEquals(FillVerifier.Verdict.FILLED,
                FillVerifier.adjudicate(reply, o, 105));
    }

    @Test public void aDifferentAmountIsNotEvidenceEitherWay() {
        Order5 o = sellOrder();
        JSONObject reply = coins(coin("299", "0x00", 105),
                                 coin("15.44", DexContract.USDT_ID, 105));
        assertEquals(FillVerifier.Verdict.UNKNOWN,
                FillVerifier.adjudicate(reply, o, 105));
    }
}

package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

/** Evidence rules for recording this device's own taker fills. */
public class TakerConfirmationTest {

    private static JSONObject reply(Object response) {
        try {
            JSONObject j = new JSONObject();
            j.put("status", true);
            j.put("response", response);
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject failedReply() {
        try {
            JSONObject j = new JSONObject();
            j.put("status", false);
            j.put("response", "command failed");
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coin(String coinid, String tokenid, String amount,
                                   String tokenamount, long created) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", tokenid);
            c.put("amount", amount);
            if (tokenamount != null) c.put("tokenamount", tokenamount);
            c.put("created", created);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONArray coins(JSONObject... cs) {
        JSONArray a = new JSONArray();
        for (JSONObject c : cs) a.put(c);
        return a;
    }

    @Test public void sourceCoinPresenceTreatsBadRepliesAsUnknownNotSpent() {
        assertNull(MainActivity.coinPresent(null));
        assertNull(MainActivity.coinPresent(failedReply()));
        assertNull(MainActivity.coinPresent(reply("not an array")));
    }

    @Test public void sourceCoinPresenceParsesValidCoinReplies() {
        assertEquals(Boolean.FALSE, MainActivity.coinPresent(reply(coins())));
        assertEquals(Boolean.TRUE, MainActivity.coinPresent(reply(coins(
                coin("0xC1", Util.MINIMA_TOKENID, "1", null, 100)))));
    }

    @Test public void proceedsEvidenceRequiresGoodReplyAndExpectedOutput() {
        assertFalse(MainActivity.proceedsPresent(failedReply(), Util.MINIMA_TOKENID,
                new BigDecimal("10"), 100));
        assertFalse(MainActivity.proceedsPresent(reply(coins()), Util.MINIMA_TOKENID,
                new BigDecimal("10"), 100));
        assertTrue(MainActivity.proceedsPresent(reply(coins(
                coin("0xP1", Util.MINIMA_TOKENID, "10", null, 101))),
                Util.MINIMA_TOKENID, new BigDecimal("10"), 100));
    }

    @Test public void proceedsEvidenceRejectsOldOrWrongAmountOutputs() {
        assertFalse(MainActivity.proceedsPresent(reply(coins(
                coin("0xOLD", Util.MINIMA_TOKENID, "10", null, 99))),
                Util.MINIMA_TOKENID, new BigDecimal("10"), 100));
        assertFalse(MainActivity.proceedsPresent(reply(coins(
                coin("0xWRONG", Util.MINIMA_TOKENID, "9.99999999", null, 101))),
                Util.MINIMA_TOKENID, new BigDecimal("10"), 100));
    }

    @Test public void tokenProceedsUseTokenAmountNotRawMinimaAmount() {
        assertTrue(MainActivity.proceedsPresent(reply(coins(
                coin("0xT1", DexContract.USDT_ID, "0.00000001", "12.34", 101))),
                DexContract.USDT_ID, new BigDecimal("12.34"), 100));
        assertFalse(MainActivity.proceedsPresent(reply(coins(
                coin("0xT2", DexContract.USDT_ID, "12.34", "0.00000001", 101))),
                DexContract.USDT_ID, new BigDecimal("12.34"), 100));
    }
}

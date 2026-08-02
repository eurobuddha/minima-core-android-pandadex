package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

public class SelfSplitTest {

    @Test public void minimaSelfSplitOmitsTokenid() {
        assertEquals("send address:MxSELF amount:12.5 split:10",
                SelfSplit.command("MxSELF", Util.MINIMA_TOKENID, new BigDecimal("12.5000")));
    }

    @Test public void tokenSelfSplitIncludesTokenidBeforeSplit() {
        assertEquals("send address:MxSELF amount:44 tokenid:" + DexContract.USDT_ID + " split:10",
                SelfSplit.command("MxSELF", DexContract.USDT_ID, new BigDecimal("44.00000000")));
    }

    @Test public void nodeReplyMessageUsesMessageBeforeGenericFallback() throws Exception {
        JSONObject json = new JSONObject();
        json.put("status", false);
        json.put("message", "Insufficient funds");

        assertEquals("Insufficient funds", MainActivity.nodeReplyMessage(json, "split failed"));
    }
}

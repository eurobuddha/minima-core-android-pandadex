package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class FillSettlerTest {

    private static final String PAYOUT = "0xPAYOUT11223344556677889900AABBCCDDEEFF00112233445566778899AABB";

    private static final class Row {
        String spentCoin, txpowid, evidence, note;
        FillVerifier.Verdict cancelled;
        BigDecimal size, price;
    }

    private static Order5 ask(String coinid) {
        return order(coinid, "300", "0x00", "15.45", DexContract.USDT_ID, true);
    }

    private static Order5 order(String coinid, String locked, String lockedTok,
                                String want, String wantTok, boolean sell) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", lockedTok);
            if ("0x00".equals(lockedTok)) c.put("amount", locked);
            else { c.put("amount", "0.000001"); c.put("tokenamount", locked); }
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xMAKER");
            st.put("1", PAYOUT);
            st.put("2", want);
            st.put("3", wantTok);
            st.put("4", "0xORD" + coinid);
            st.put("5", sell ? "1" : "0");
            st.put("6", "0.0515");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject out(String address, String tokenid, String amount, String tokenamount) {
        try {
            JSONObject o = new JSONObject();
            o.put("address", address);
            o.put("tokenid", tokenid);
            o.put("amount", amount);
            if (tokenamount != null) o.put("tokenamount", tokenamount);
            return o;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coin(String coinid, String tokenid, String amount, long created) {
        try {
            JSONObject c = out(PAYOUT, tokenid, amount, "0x00".equals(tokenid) ? null : amount);
            c.put("coinid", coinid);
            c.put("created", created);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONArray arr(JSONObject... rows) {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        return a;
    }

    private static JSONObject page(JSONObject... txs) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("txpows", arr(txs));
            JSONObject j = new JSONObject();
            j.put("response", resp);
            j.put("status", true);
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject coins(JSONObject... rows) {
        try {
            JSONObject j = new JSONObject();
            j.put("response", arr(rows));
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONObject txpow(String txpowid, String spentCoin, JSONArray outputs) {
        try {
            JSONObject in = new JSONObject();
            in.put("coinid", spentCoin);
            JSONObject txn = new JSONObject();
            txn.put("inputs", arr(in));
            txn.put("outputs", outputs);
            JSONObject body = new JSONObject();
            body.put("txn", txn);
            JSONObject tx = new JSONObject();
            tx.put("txpowid", txpowid);
            tx.put("body", body);
            return tx;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static FillSettler settler(JSONObject historyPage, JSONObject payoutReply, Row row) {
        DexHistory history = new DexHistory((command, cb) -> cb.onResult(command.startsWith("txpow ")
                ? new TestJson().put("status", true).put("response", new TestJson().put("found", true).put("confirmations", 3))
                : historyPage));
        FillVerifier verifier = new FillVerifier((command, cb) -> cb.onResult(payoutReply));
        return new FillSettler(history, () -> 112, new FillSettler.Outcome() {
            @Override public void record(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                                         boolean takerBuy, boolean partial, String txpowid,
                                         String evidence, String note) {
                row.spentCoin = spentCoin;
                row.size = size; row.price = price;
                row.txpowid = txpowid;
                row.evidence = evidence;
                row.note = note;
            }

            @Override public void cancelled(String spentCoin) {
                row.cancelled = FillVerifier.Verdict.CANCELLED;
            }
        }, new FillRecoveryTest.MemoryStore());
    }

    @Test public void chainHistoryVerdictCarriesTxpowidAndChainStatus() {
        Row row = new Row();
        FillSettler s = settler(page(txpow("0xAABB", "0xASK",
                arr(out(PAYOUT, DexContract.USDT_ID, "0.0000001545", "15.45")))),
                new JSONObject(), row);

        s.onFill("0xASK", ask("0xASK"), new BigDecimal("300"),
                new BigDecimal("0.0515"), true, false, 108);
        s.onScanComplete();

        assertEquals("0xASK", row.spentCoin);
        assertEquals("0xAABB", row.txpowid);
        assertEquals(FillSettler.CHAIN_VERIFIED, row.evidence);
        org.junit.Assert.assertTrue(row.note.startsWith(FillSettler.NOTE_HISTORY));
    }

    @Test public void unrelatedPayoutCannotOverrideTheActualSpendingTransaction() {
        Row row = new Row();
        FillSettler s = settler(page(txpow("0xAABB", "0xASK",
                arr(out(PAYOUT, DexContract.USDT_ID, "0.0000001500", "15.00")))),
                coins(coin("0xPAY", DexContract.USDT_ID, "15.45", 110)),
                row);

        s.onFill("0xASK", ask("0xASK"), new BigDecimal("300"),
                new BigDecimal("0.0515"), true, false, 108);
        s.onScanComplete();

        org.junit.Assert.assertNull(row.spentCoin);
        org.junit.Assert.assertNull(row.evidence);
    }
    @Test public void copiedSuccessorDoesNotProvePartialWithoutSpendingHistory() {
        Row row = new Row();
        FillSettler s = settler(page(), new JSONObject(), row);
        s.onFill("0xASK", ask("0xASK"), new BigDecimal("100"),
                new BigDecimal("0.0515"), true, true, 108);
        s.onScanComplete();
        org.junit.Assert.assertNull(row.spentCoin);
    }

    @Test public void partialMustPayMakerAndLeaveTheActualRemainderAtTheCovenantIndex() {
        DexHistory.Spend spend = new DexHistory.Spend("0xAABB", 0, arr(
                out(PAYOUT, DexContract.USDT_ID, "0", "5.15"),
                out(DexContract.ADDR_V5, "0x00", "200", null)), "0xCCDD", 3);
        org.junit.Assert.assertTrue(FillSettler.partialMatches(spend, ask("0xASK"), new BigDecimal("100")));
        org.junit.Assert.assertFalse(FillSettler.partialMatches(spend, ask("0xASK"), new BigDecimal("150")));
    }

    @Test public void roundedBuyPartialRecordsActualPaymentRatherThanDroppingTheTrade() {
        Row row=new Row();Order5 buy=order("0xBUY","3",DexContract.USDT_ID,"1","0x00",false);
        FillSettler s=settler(page(txpow("0xAABB","0xBUY",arr(
                out(PAYOUT,"0x00","0.33333334",null),
                out(DexContract.ADDR_V5,DexContract.USDT_ID,"0","2")))),new JSONObject(),row);
        // ceil(1*2/3) leaves want=.66666667, so the book diff says .33333333.
        // The actual maker payment is ceil(1*1/3)=.33333334.
        s.onFill("0xBUY",buy,new BigDecimal("0.33333333"),new BigDecimal("3"),false,true,108);
        s.onScanComplete();
        assertEquals("0xBUY",row.spentCoin);
        assertEquals(0,new BigDecimal("0.33333334").compareTo(row.size));
        assertEquals(FillSettler.CHAIN_VERIFIED,row.evidence);
    }
    @Test public void actualPartialSpendOverridesAFullDisappearanceHint() {
        Row row=new Row();FillSettler s=settler(page(txpow("0xAABB","0xASK",arr(
                out(PAYOUT,DexContract.USDT_ID,"0","5.15"),
                out(DexContract.ADDR_V5,"0x00","200",null)))),new JSONObject(),row);
        s.onFill("0xASK",ask("0xASK"),new BigDecimal("300"),new BigDecimal("999"),true,false,108);
        s.onScanComplete();assertEquals(0,new BigDecimal("100").compareTo(row.size));
        assertEquals(0,new BigDecimal("0.0515").compareTo(row.price));
    }
    @Test public void fullRequestedPaymentWithARemainderIsStillAPartialFill() {
        Row row = new Row();
        FillSettler s = settler(page(txpow("0xAABB", "0xASK", arr(
                out(PAYOUT, DexContract.USDT_ID, "0", "15.45"),
                out(DexContract.ADDR_V5, "0x00", "200", null)))), new JSONObject(), row);
        s.onFill("0xASK", ask("0xASK"), new BigDecimal("300"), BigDecimal.ONE, true, false, 108);
        s.onScanComplete();
        assertEquals(0, new BigDecimal("100").compareTo(row.size));
        assertEquals(0, new BigDecimal("0.1545").compareTo(row.price));
        org.junit.Assert.assertTrue(row.note.startsWith(FillSettler.NOTE_PARTIAL));
    }
    @Test public void copiedEqualOrLargerSuccessorCannotHideTheOriginalFullFill() throws Exception {
        for (String copiedAmount : new String[]{"300", "600"}) {
            Order5 original = ask("0xASK");
            JSONObject copy = new JSONObject(original.sourceJson()).put("coinid", "0xCOPY")
                    .put("created", 109).put("amount", copiedAmount);
            Row row = new Row();
            FillSettler settler = settler(page(txpow("0xAABB", original.coinid,
                    arr(out(PAYOUT, DexContract.USDT_ID, "0", "15.45")))), new JSONObject(), row);
            FillTape tape = new FillTape(null);
            tape.ingest(java.util.Collections.singletonMap(original.coinid, original), false, 108, settler);
            tape.ingest(java.util.Collections.singletonMap("0xCOPY", Order5.from(copy)), false, 109, settler);
            assertEquals("0xASK", row.spentCoin);
            assertEquals(0, new BigDecimal("300").compareTo(row.size));
            assertEquals(FillSettler.CHAIN_VERIFIED, row.evidence);
        }
    }

    @Test public void includedOwnerRelockRetiresWithoutRecordingAFill() throws Exception {
        Order5 original = ask("0xASK");
        JSONObject raw = new JSONObject(original.sourceJson());
        JSONObject output = new JSONObject(raw.toString()).put("coinid", "0xNEW")
                .put("address", DexContract.ADDR_V5).put("storestate", true).put("state", new JSONArray());
        JSONArray state = new JSONArray(); JSONObject source = raw.getJSONObject("state");
        for (java.util.Iterator<String> it = source.keys(); it.hasNext();) {
            String key = it.next();
            state.put(new JSONObject().put("port", Integer.parseInt(key))
                    .put("data", key.equals("2") ? "12" : source.getString(key)));
        }
        JSONObject tx = txpow("0xAABB", original.coinid, arr(output));
        tx.getJSONObject("body").getJSONObject("txn").put("state", state);
        Row row = new Row(); FillSettler settler = settler(page(tx), new JSONObject(), row);
        settler.onFill(original.coinid, original, original.minimaAmount(), original.price(), true, false, 108);
        settler.onScanComplete();
        org.junit.Assert.assertNull(row.spentCoin);
        assertEquals(FillVerifier.Verdict.CANCELLED, row.cancelled);
    }
}

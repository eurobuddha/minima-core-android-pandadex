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
        DexHistory history = new DexHistory((command, cb) -> cb.onResult(historyPage));
        FillVerifier verifier = new FillVerifier((command, cb) -> cb.onResult(payoutReply));
        return new FillSettler(history, verifier, () -> 112, new FillSettler.Outcome() {
            @Override public void record(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                                         boolean takerBuy, boolean partial, String txpowid,
                                         String evidence, String note) {
                row.spentCoin = spentCoin;
                row.txpowid = txpowid;
                row.evidence = evidence;
                row.note = note;
            }

            @Override public void cancelled(String spentCoin) {
                row.cancelled = FillVerifier.Verdict.CANCELLED;
            }
        });
    }

    @Test public void chainHistoryVerdictCarriesTxpowidAndChainStatus() {
        Row row = new Row();
        FillSettler s = settler(page(txpow("0xTX", "0xASK",
                arr(out(PAYOUT, DexContract.USDT_ID, "0.0000001545", "15.45")))),
                new JSONObject(), row);

        s.onFill("0xASK", ask("0xASK"), new BigDecimal("300"),
                new BigDecimal("0.0515"), true, false, 108);
        s.onScanComplete();

        assertEquals("0xASK", row.spentCoin);
        assertEquals("0xTX", row.txpowid);
        assertEquals(FillSettler.CHAIN_VERIFIED, row.evidence);
        assertEquals(FillSettler.NOTE_HISTORY, row.note);
    }

    @Test public void fallbackEvidenceIsNotMislabelledAsChainVerified() {
        Row row = new Row();
        FillSettler s = settler(page(txpow("0xTX", "0xASK",
                arr(out(PAYOUT, DexContract.USDT_ID, "0.0000001500", "15.00")))),
                coins(coin("0xPAY", DexContract.USDT_ID, "15.45", 110)),
                row);

        s.onFill("0xASK", ask("0xASK"), new BigDecimal("300"),
                new BigDecimal("0.0515"), true, false, 108);
        s.onScanComplete();

        assertEquals("0xASK", row.spentCoin);
        assertEquals("", row.txpowid);
        assertEquals(FillSettler.LOCAL_VERIFIED, row.evidence);
        assertEquals(FillSettler.NOTE_PAYOUT, row.note);
    }
}

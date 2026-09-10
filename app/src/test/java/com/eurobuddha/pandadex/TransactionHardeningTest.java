package com.eurobuddha.pandadex;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class TransactionHardeningTest {
    private static JSONObject checked() {
        return new TestJson().put("status", true).put("response", new TestJson()
                .put("valid", new TestJson().put("scripts", true).put("basic", true).put("mmrproofs", true))
                .put("validamounts", true).put("allsignaturesvalid", true).put("validtransaction", true));
    }
    static JSONObject orderCoin() {
        return new TestJson().put("coinid", "0xaa").put("tokenid", "0x00").put("amount", "100").put("created", 100)
                .put("state", new TestJson().put("0", "0xbb").put("1", "0x" + "12".repeat(32))
                        .put("2", "1").put("3", DexContract.USDT_ID).put("4", "0xcc")
                        .put("5", "1").put("7", "1").put("8", "1"));
    }
    @Test public void eachMandatoryVerdictFailsClosedForMissingFalseAndWrongTypes() throws Exception {
        assertNull(TxValidation.checkFailure(checked()));
        for (String key : Arrays.asList("status", "scripts", "basic", "mmrproofs", "validamounts", "allsignaturesvalid", "validtransaction")) {
            for (Object value : Arrays.asList(JSONObject.NULL, false, 0, 1.5, "garbage")) {
                JSONObject j = checked();
                JSONObject target = key.equals("status") ? j : Arrays.asList("scripts", "basic", "mmrproofs").contains(key)
                        ? j.getJSONObject("response").getJSONObject("valid") : j.getJSONObject("response");
                target.put(key, value); assertNotNull(key + "=" + value, TxValidation.checkFailure(j));
                target.remove(key); assertNotNull(key, TxValidation.checkFailure(j));
            }
        }
        JSONObject j=checked(); j.getJSONObject("response").put("scripts", 100);
        j.getJSONObject("response").getJSONObject("valid").remove("scripts");
        assertNotNull(TxValidation.checkFailure(j));
    }
    @Test public void multiCommandsAreRejectedEvenInsideQuotesOrJson() {
        for (String value : Arrays.asList("txnstate id:x port:4 value:0xaa; send amount:1", "runscript script:\"RETURN TRUE;sign data:0xaa\"", "send state:{\"4\":\"0xaa;send amount:1\"}", "keys\n;send amount:1", "keys\u0000"))
            assertNotNull(value, CommandSafety.failure(value));
        assertNull(CommandSafety.failure("newscript trackall:false script:" + Util.scriptArg(DexContract.SCRIPT_V5)));
        assertNull(CommandSafety.failure("consolidate tokenid:0x00"));
    }
    @Test public void hostileOrderFieldsNeverReachTransactionBuilding() throws Exception {
        assertTrue(Order5.from(orderCoin()).fillable());
        for (String port : Arrays.asList("0", "1", "3", "4")) {
            JSONObject raw=orderCoin(); raw.getJSONObject("state").put(port,"0xaa;send amount:1");
            Order5 o=Order5.from(raw); assertFalse(DexTxn.safeOrder(o));
            final List<String> failures = new ArrayList<>();
            new DexTxn(null, null).relock(o, null, new DexTxn.Result() {
                public void onPosted(String id) { fail("must not post"); }
                public void onFailed(String error) { failures.add(error); }
            });
            assertEquals(1, failures.size());
        }
    }
    @Test public void tokenValuesAndStateNumbersCannotDefaultOrExpandWithoutBound() throws Exception {
        JSONObject c=orderCoin().put("tokenid", DexContract.USDT_ID);
        assertNull(Order5.from(c));
        c.put("tokenamount", "2"); assertNotNull(Order5.from(c));
        for (String value : Arrays.asList("1e-2147483647", "1e2147483647", "1,000", "garbage")) {
            c=orderCoin(); c.getJSONObject("state").put("2", value); assertNull(value, Order5.from(c));
        }
        c=orderCoin(); c.getJSONObject("state").remove("8"); assertNull(Order5.from(c));
    }
    @Test public void ownershipNeedsPayoutEvidenceAsWellAsPublicKey() {
        Order5 o=Order5.from(orderCoin());
        assertFalse(o.isMine(Collections.singleton(o.ownerPk), Collections.emptySet()));
        assertFalse(o.isMine(Collections.singleton(o.ownerPk), Collections.singleton("0xff")));
        assertTrue(o.isMine(Collections.singleton(o.ownerPk), Collections.singleton(o.wantAddr)));
    }
    @Test public void hostilePoolLiteralsNeverChangeTheCovenant() {
        for (String bad : Arrays.asList("0xaa;send amount:1", "0xaa) OR TRUE", "0xaa publickey:auto")) {
            assertFalse(PoolCovenant.validParams(bad,"0xbb",DexContract.USDT_ID,"1"));
            assertThrows(IllegalArgumentException.class, () -> PoolCovenant.script("0xaa",bad,DexContract.USDT_ID,"1"));
        }
        for (String bad : Arrays.asList("0", "-1", "1 OR TRUE", "1e999999999", "18446744073709551615"))
            assertFalse(PoolCovenant.validParams("0xaa","0xbb",DexContract.USDT_ID,bad));
    }
    @Test public void incompleteRepliesNeverClearWriteSafetyAndActualSizeIsPreserved() {
        assertFalse(NodeApi.isCompleteReply(new TestJson().put("transporterror", "lost")));
        for (String verb : Arrays.asList("send", "sign", "txnsign", "txnpost", "consolidate", "tokencreate")) assertTrue(NodeApi.writesFunds(verb));
        assertFalse(NodeApi.writesFunds("txnbasics id:x"));
        assertTrue(TxValidation.postError(new TestJson().put("error","TxPoW size too large.. 70000/65536")).contains("70000/65536"));
    }
}

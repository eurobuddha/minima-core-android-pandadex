package com.eurobuddha.pandadex;

import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ExplorerVerifierTest {
    private static final String ID="0xaabb", HOST="https://explorer.minima.global";
    private JSONObject indexed() throws Exception {return new JSONObject().put("id",ID).put("txpow_id",ID).put("block_number",100);}
    private String envelope(JSONObject data) throws Exception {
        return new JSONArray().put(new JSONObject().put("result",new JSONObject().put("data",new JSONObject().put("json",data)))).toString();
    }
    private ExplorerVerifier.Result parse(JSONObject data) throws Exception {return ExplorerVerifier.classify(ID,HOST,envelope(data));}
    private void unverified(ExplorerVerifier.Result result) {
        assertFalse(result.confirms(ID));assertEquals(0,result.block);assertEquals("",result.txpowid);
    }
    @Test public void archivedPublicHomepageResponseMatchesExactRequestedTransaction() throws Exception {
        String id="0x00000455BB756B1CBB5C3252E0B62E258D84A674B07D89B1E273D43BD9BB053B";
        try(InputStream in=getClass().getResourceAsStream("/explorer-public-txpow.json")) {
            assertNotNull(in);
            String body=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            ExplorerVerifier.Result result=ExplorerVerifier.classify(id,HOST,body);
            assertTrue(result.confirms(id));assertEquals(2299696,result.block);
            assertFalse(result.confirms("0xdead"));
            unverified(ExplorerVerifier.classify(ID,HOST,body));
        }
    }
    @Test public void bothIndexedIdentityFieldsMustMatchAndCaseDifferencesAreAllowed() throws Exception {
        assertTrue(parse(indexed().put("id","0xAABB").put("txpow_id","0xAABB")).confirms(ID));
        for(String field:new String[]{"id","txpow_id"}) {
            for(Object wrong:new Object[]{"0xdead","",JSONObject.NULL,123,new JSONObject(),new JSONArray()}) {
                unverified(parse(indexed().put(field,wrong)));
            }
            JSONObject data=indexed();data.remove(field);unverified(parse(data));
        }
    }
    @Test public void incompleteAmbiguousAndErrorEnvelopesCannotCorroborate() throws Exception {
        for(String body:new String[]{"null","{}","[]","[null]","[1]","[{}]","not JSON",
                "[{\"result\":{\"data\":{\"json\":\"wrong type\"}}}]",
                "[{\"result\":{\"data\":{}}}]"}) unverified(ExplorerVerifier.classify(ID,HOST,body));
        JSONArray a=new JSONArray(envelope(indexed()));a.put(a.get(0));unverified(ExplorerVerifier.classify(ID,HOST,a.toString()));
        a=new JSONArray(envelope(indexed()));a.getJSONObject(0).put("error",JSONObject.NULL);
        unverified(ExplorerVerifier.classify(ID,HOST,a.toString()));
    }
    @Test public void heightsMustBeExactPositiveBoundedIntegers() throws Exception {
        for(Object bad:new Object[]{0,-1,100.5,true,JSONObject.NULL,"1.5","1e2","-5","9223372036854775808","99999999999999999999999999",new JSONArray()})
            unverified(parse(indexed().put("block_number",bad)));
        assertEquals(100,parse(indexed().put("block_number","100")).block);
        JSONObject missing=indexed();missing.remove("block_number");unverified(parse(missing));
    }
    @Test public void invalidRequestedIdsNeverBecomeVerifiedResultsOrNetworkLookups() throws Exception {
        for(String bad:new String[]{null,"","0xa","0xaa;send amount:1","https://elsewhere.example","0xaa\n"}) {
            unverified(ExplorerVerifier.classify(bad,HOST,envelope(indexed())));
            assertEquals("LOCAL_ONLY",ExplorerVerifier.lookup(bad).status);
        }
    }
    private TradeExport.TradeRow row(long verifiedBlock,String status) {
        return new TradeExport.TradeRow("0xcc",1700000000000L,100,new BigDecimal("0.01"),BigDecimal.TEN,true,true,"0xdd",
                ID,"BOOK","0xcc","",status,"Original node proof"+ChainEvidence.BLOCK_TIME_NOTE,verifiedBlock);
    }
    private TradeExport.Snapshot exported(TradeExport.TradeRow row,ExplorerVerifier.Result result) {
        TradeExport.Snapshot s=new TradeExport.Snapshot();s.rows.add(row);return TradeExport.verifiedCopy(s,id->result);
    }
    @Test public void differentExplorerHeightIsVisibleWithoutRewritingLocalEvidence() throws Exception {
        TradeExport.TradeRow original=row(100,"CHAIN_VERIFIED");
        TradeExport.TradeRow copy=exported(original,parse(indexed().put("block_number",123))).rows.get(0);
        assertEquals(100,copy.verifiedBlock);assertEquals(original.block,copy.block);assertEquals(original.timeMs,copy.timeMs);
        assertEquals(original.price,copy.price);assertEquals(original.sizeMinima,copy.sizeMinima);
        assertEquals("CHAIN_VERIFIED+EXPLORER_HEIGHT_CONFLICT",copy.verificationStatus);
        assertTrue(copy.verificationNote.contains("123 disagrees with stored verification height 100"));
        assertEquals("Block time",TradeExport.timeLabel(copy));
        assertEquals("CHAIN_VERIFIED",original.verificationStatus);
    }
    @Test public void failureOrWrongIdentityCannotChangeTheVerifiedBlockEvenWithPositiveBlockField() {
        for(String status:new String[]{"EXPLORER_ERROR","EXPLORER_UNAVAILABLE","EXPLORER_OK"}) {
            ExplorerVerifier.Result wrong=new ExplorerVerifier.Result();wrong.status=status;wrong.block=999;wrong.txpowid="0xdead";
            TradeExport.TradeRow copy=exported(row(100,"CHAIN_VERIFIED"),wrong).rows.get(0);
            assertEquals(100,copy.verifiedBlock);assertEquals("CHAIN_VERIFIED",copy.verificationStatus);
            assertEquals("Block time",TradeExport.timeLabel(copy));
        }
        ExplorerVerifier.Result error=new ExplorerVerifier.Result();error.status="EXPLORER_ERROR";error.block=999;error.txpowid=ID;
        assertEquals(100,exported(row(100,"CHAIN_VERIFIED"),error).rows.get(0).verifiedBlock);
    }
    @Test public void explorerPresenceDoesNotFabricateLocalHeightOrTradeEffectProof() throws Exception {
        TradeExport.TradeRow copy=exported(row(0,"LOCAL_VERIFIED"),parse(indexed())).rows.get(0);
        assertEquals(0,copy.verifiedBlock);assertEquals("LOCAL_VERIFIED+EXPLORER_OK",copy.verificationStatus);
        assertTrue(copy.verificationNote.contains("at block 100"));
    }
    @Test public void externalPresenceOrHeightConflictCannotRestoreExcludedAccounting() throws Exception {
        for(String status:new String[]{"RECHECK_REQUIRED","SUPERSEDED_NONTRADE","SUPERSEDED_UNATTRIBUTED"}) {
            for(int block:new int[]{100,123}) {
                TradeExport.Report report=TradeExport.build(exported(row(100,status),parse(indexed().put("block_number",block))));
                assertEquals(1,report.totals.excludedRechecks);assertEquals(BigDecimal.ZERO,report.totals.minimaBought);
                assertTrue(report.tradesCsv.contains(",false\n"));
            }
        }
    }
}

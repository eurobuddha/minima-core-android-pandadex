package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TakerReceiptTest {
    static JSONObject expected() throws Exception {
        return new JSONObject().put("intent","sweep_1").put("txpowid","0xaabb")
                .put("sources",new JSONArray().put("0xaa").put("0xbb")).put("transactionid","0xccdd")
                .put("payout","0xee").put("token","0x00").put("proceeds","100").put("minima","100")
                .put("price","0.01").put("buy",true).put("source","BOOK+POOL");
    }
    static Map<String,DexHistory.Spend> proof() throws Exception {
        Map<String,DexHistory.Spend> map=new LinkedHashMap<>();
        JSONArray outputs=new JSONArray().put(new JSONObject().put("address","0xee").put("tokenid","0x00").put("amount","100"));
        for(int i=0;i<2;i++) {
            String id=i==0?"0xaa":"0xbb";DexHistory.Spend s=new DexHistory.Spend("0xaabb",i,outputs,"0xccdd",4);
            s.input=new JSONObject().put("coinid",id).put("amount","50");s.inputCount=3;
            s.inclusionBlock=100;s.inclusionBlockId="0xbeef";s.inclusionTimeMs=1700000000000L;
            s.proofOrder=1;s.proofTimeMs=1700000000001L;map.put(id,s);
        }
        return map;
    }
    @Test public void completeArchiveRetainsExpectationsSelectedInputsAndExactProof() throws Exception {
        TakerReceipt r=TakerReceipt.capture(expected(),proof());JSONObject saved=new JSONObject(r.json);
        assertEquals("sweep_1",saved.getJSONObject("expected").getString("intent"));
        JSONObject p=saved.getJSONObject("proof");assertEquals(2,p.getJSONArray("inputs").length());
        assertEquals(3,p.getInt("input_count"));assertEquals("0xbb",p.getJSONArray("inputs").getJSONObject(1).getJSONObject("coin").getString("coinid"));
        assertEquals(1700000000000L,p.getLong("timems"));assertEquals("0xccdd",p.getString("transactionid"));
        assertEquals(100,p.getLong("block"));assertEquals("0xbeef",p.getString("blockid"));
        assertEquals(ChainEvidence.PROOF_EPOCH,p.getString("proof_epoch"));assertEquals(1,p.getLong("proof_order"));
    }
    @Test public void mutableCallerDataCannotChangeArchivedBytes() throws Exception {
        JSONObject e=expected();Map<String,DexHistory.Spend> found=proof();TakerReceipt r=TakerReceipt.capture(e,found);String bytes=r.json;
        e.put("price","99");found.get("0xaa").input.put("amount","999");found.get("0xaa").outputs.getJSONObject(0).put("amount","999");
        assertEquals(bytes,r.json);assertEquals("100",new JSONObject(r.json).getJSONObject("proof").getJSONArray("outputs").getJSONObject(0).getString("amount"));
    }
    @Test public void absentOrConflictingLegAndMissingBlockTimeCannotBeArchived() throws Exception {
        for(int fault=0;fault<4;fault++) {
            JSONObject e=expected();Map<String,DexHistory.Spend> found=proof();
            if(fault==0)found.remove("0xbb");
            if(fault==1)found.get("0xbb").inclusionTimeMs=0;
            if(fault==2)found.get("0xbb").inputCount=4;
            if(fault==3)e.put("transactionid","0xffff");
            assertThrows(IllegalArgumentException.class,()->TakerReceipt.capture(e,found));
        }
    }
    @Test public void wrongDirectionAndUnexpectedPayoutCannotBecomeCompletedEvidence() throws Exception {
        for(int fault=0;fault<5;fault++) {
            JSONObject e=expected();
            if(fault==0)e.put("buy","true");if(fault==1)e.put("buy",false);
            if(fault==2)e.put("minima","99");if(fault==3)e.put("payout","0xff");if(fault==4)e.put("source","UNKNOWN");
            Map<String,DexHistory.Spend> found=proof();assertThrows(IllegalArgumentException.class,()->TakerReceipt.capture(e,found));
        }
    }
    @Test public void sameIntentPermitsNumericFormattingButRejectsChangedEconomicsOrIdentity() throws Exception {
        TakerReceipt r=TakerReceipt.capture(expected(),proof());
        JSONObject saved=new JSONObject(r.json);saved.getJSONObject("expected").put("price","0.0100").put("minima","100.00");
        assertTrue(r.sameIntent(saved.toString()));
        for(String field:Arrays.asList("price","payout","transactionid","intent","proceeds")) {
            JSONObject different=new JSONObject(r.json);different.getJSONObject("expected").put(field,field.equals("price") || field.equals("proceeds")?"99":"0xffff");
            assertFalse(field,r.sameIntent(different.toString()));
        }
        assertFalse(r.sameIntent("corrupt original"));
    }
    @Test public void oversizedReceiptLeavesCompletionUnprovedRatherThanTruncatingEvidence() throws Exception {
        Map<String,DexHistory.Spend> found=proof();found.get("0xaa").input.put("state","x".repeat(TakerReceipt.MAX_BYTES));
        JSONObject e=expected();assertThrows(IllegalArgumentException.class,()->TakerReceipt.capture(e,found));
    }
    @Test public void exportAndOptionalCorroborationPreserveOriginalTakerEvidence() throws Exception {
        String json=new JSONArray().put(new JSONObject(TakerReceipt.capture(expected(),proof()).json)).toString();
        TradeExport.Snapshot s=new TradeExport.Snapshot();s.takerReceiptsJson=json;
        TradeExport.Snapshot checked=TradeExport.verifiedCopy(s,id->{throw new AssertionError("no trade rows to check");});
        assertEquals(json,checked.takerReceiptsJson);assertEquals(json,TradeExport.build(checked).takerReceiptsJson);
    }
    @Test public void marketReconstructionPreservesSelectedInputIndicesAndWalletGaps()throws Exception {
        Map<String,DexHistory.Spend> found=proof();DexHistory.Spend old=found.get("0xbb");
        DexHistory.Spend shifted=new DexHistory.Spend(old.txpowid,2,old.outputs,old.transactionId,old.confirmations);
        shifted.input=old.input;shifted.inputCount=old.inputCount;shifted.inclusionBlock=old.inclusionBlock;shifted.inclusionBlockId=old.inclusionBlockId;
        shifted.inclusionTimeMs=old.inclusionTimeMs;shifted.proofOrder=old.proofOrder;shifted.proofTimeMs=old.proofTimeMs;found.put("0xbb",shifted);
        JSONObject tx=TakerReceipt.capture(expected(),found).marketTransaction();JSONArray inputs=DexHistory.coinsOf(tx,"inputs");
        assertEquals(3,inputs.length());assertTrue(inputs.isNull(1));assertEquals("0xaa",inputs.getJSONObject(0).getString("coinid"));
        assertEquals("0xbb",inputs.getJSONObject(2).getString("coinid"));assertEquals("100",DexHistory.coinsOf(tx,"outputs").getJSONObject(0).getString("amount"));
    }

}

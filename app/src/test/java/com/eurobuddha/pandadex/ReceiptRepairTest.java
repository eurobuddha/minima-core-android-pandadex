package com.eurobuddha.pandadex;
import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class ReceiptRepairTest {
    static FillSettler.Entry entry(){return new FillSettler.Entry("source","{}");}
    static DexHistory.Spend spend(){
        DexHistory.Spend s=new DexHistory.Spend("0xaabb",0,new JSONArray(),"0xccdd",4);
        s.input=new TestJson().put("coinid","source");s.inclusionBlock=100;s.inclusionTimeMs=InclusionTimeTest.BLOCK_TIME;
        s.inclusionBlockId="0xbeef";s.proofOrder=20;return s;
    }
    @Test public void replacementRequiresMissingOldWinnerAndLaterProofInTheSameProcess(){
        DexHistory.Spend s=spend();String epoch=ChainEvidence.PROOF_EPOCH;
        assertTrue(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.MISSING,epoch,19));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.CURRENT,epoch,19));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.MISSING,"previous process",19));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.MISSING,epoch,20));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.MISSING,epoch,21));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xdead",ChainReview.MISSING,epoch,0));
        assertFalse(ReceiptRepair.allowed(entry(),s,"0xAABB",ChainReview.MISSING,epoch,19));
    }
    @Test public void incompleteTimeBlockOrSourceProofCannotRewriteAnOldReceipt() throws Exception {
        DexHistory.Spend s=spend();assertTrue(ReceiptRepair.usable(entry(),s));
        s.inclusionTimeMs=0;assertFalse(ReceiptRepair.usable(entry(),s));s.inclusionTimeMs=1;
        s.inclusionBlock=0;assertFalse(ReceiptRepair.usable(entry(),s));s.inclusionBlock=100;
        s.inclusionBlockId="wrong";assertFalse(ReceiptRepair.usable(entry(),s));s.inclusionBlockId="0xbeef";
        s.input.put("coinid","different");assertFalse(ReceiptRepair.usable(entry(),s));
        DexHistory.Spend unconfirmed=new DexHistory.Spend("0xaabb",0,new JSONArray(),"0xccdd",-1);
        unconfirmed.input=s.input;unconfirmed.inclusionTimeMs=1;unconfirmed.inclusionBlock=100;unconfirmed.inclusionBlockId="0xbeef";
        assertFalse(ReceiptRepair.usable(entry(),unconfirmed));
    }
    @Test public void delayedBlockReplyCannotMakeAnOlderInclusionCheckLookNewer(){
        NodeApi.Cb[] delayed={null};Map<String,DexHistory.Spend> found=new HashMap<>();
        DexHistory history=new DexHistory((cmd,cb)->{
            if(cmd.startsWith("history "))cb.onResult(InclusionTimeTest.historyPage());
            else if(cmd.startsWith("txpow onchain:"))cb.onResult(InclusionTimeTest.inclusion());
            else delayed[0]=cb;
        });
        history.findSpends(Collections.singleton("source"),found::putAll);
        ChainReview.Evidence missing=ChainReview.evidence(new TestJson().put("status",true).put("response",new TestJson().put("found",false)));
        delayed[0].onResult(InclusionTimeTest.block());
        assertFalse(ReceiptRepair.allowed(entry(),found.get("source"),"0xdead",missing.state,ChainEvidence.PROOF_EPOCH,missing.proofOrder));
        new DexHistory((cmd,cb)->cb.onResult(cmd.startsWith("history ")?InclusionTimeTest.historyPage()
                :cmd.startsWith("txpow onchain:")?InclusionTimeTest.inclusion():InclusionTimeTest.block()))
                .findSpends(Collections.singleton("source"),found::putAll);
        assertTrue(ReceiptRepair.allowed(entry(),found.get("source"),"0xdead",missing.state,ChainEvidence.PROOF_EPOCH,missing.proofOrder));
    }
    @Test public void correctedNonTradeStaysExcludedEvenIfEarlierTxpowCheckChanges(){
        TradeExport.TradeRow old=new TradeExport.TradeRow("source",123,100,BigDecimal.ONE,BigDecimal.TEN,true,true,"0xaa",
                "0xdead","BOOK","source","","SUPERSEDED_NONTRADE","original corrected record",100);
        for(String state:new String[]{ChainReview.MISSING,ChainReview.CURRENT}){
            TradeExport.TradeRow displayed=ChainReview.decorate(old,state,4,500,"");
            assertEquals("SUPERSEDED_NONTRADE",displayed.verificationStatus);assertFalse(ChainReview.accounted(displayed.verificationStatus));
        }
        assertFalse(ChainReview.accounted("SUPERSEDED_UNATTRIBUTED"));
    }
    @Test public void exportedCorrectionEvidenceSurvivesExplorerCopyAndAccounting(){
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.correctionsJson="[{\"revision\":1,\"earlier_amount\":\"100\"}]";
        TradeExport.Report report=TradeExport.build(TradeExport.verifiedCopy(snapshot,id->{fail("no txs");return null;}));
        assertEquals(snapshot.correctionsJson,report.correctionsJson);assertTrue(report.summaryTxt.contains("corrections.json"));
    }
    @Test public void includedProofReachesAtomicSettlementHookAndFailureRetainsCandidate() throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();Order5 order=Order5.from(raw);
        JSONObject page=InclusionTimeTest.historyPage();JSONObject tx=page.getJSONObject("response").getJSONArray("txpows").getJSONObject(0);
        tx.getJSONObject("body").getJSONObject("txn").put("inputs",new JSONArray().put(raw))
                .put("outputs",new JSONArray().put(new TestJson().put("address",order.wantAddr).put("tokenid",order.wantTok).put("amount","0").put("tokenamount",order.wantAmt.toPlainString())));
        FillRecoveryTest.MemoryStore store=new FillRecoveryTest.MemoryStore();store.enqueue(order);int[] calls={0};
        FillSettler.Outcome outcome=new FillSettler.Outcome(){
            public void record(String id,Order5 o,BigDecimal size,BigDecimal price,boolean b,boolean partial,String txid,String e,String n){fail("bypassed atomic hook");}
            public void recordVerified(FillSettler.Entry entry,DexHistory.Spend spend,Order5 o,BigDecimal size,BigDecimal price,boolean b,boolean partial,String note,long time,long block){
                assertEquals(order.coinid,entry.coinid);assertEquals("0xaabb",spend.txpowid);assertTrue(spend.proofOrder>0);assertEquals(InclusionTimeTest.BLOCK_TIME,time);calls[0]++;throw new IllegalStateException("atomic write failed");
            }
            public void cancelled(String id){fail("not a refund");}
        };
        new FillSettler(new DexHistory((cmd,cb)->cb.onResult(cmd.startsWith("history ")?page:cmd.startsWith("txpow onchain:")?InclusionTimeTest.inclusion():InclusionTimeTest.block())),()->104,outcome,store).onScanComplete();
        assertEquals(1,calls[0]);assertEquals(1,store.rows.size());
    }
    @Test public void returningWinnerReplacesItsOldMissingProofAndBlocksAnOlderCycle(){
        DexHistory.Spend latest=spend();latest.proofOrder=30;
        assertTrue(ReceiptRepair.shouldAdopt(latest,ChainEvidence.PROOF_EPOCH,10));
        assertFalse(ReceiptRepair.allowed(entry(),spend(),"0xdead",ChainReview.CURRENT,ChainEvidence.PROOF_EPOCH,30));
        assertFalse(ReceiptRepair.shouldAdopt(spend(),ChainEvidence.PROOF_EPOCH,30));
        assertTrue(ReceiptRepair.superseded(spend(),ChainReview.MISSING,ChainEvidence.PROOF_EPOCH,30,"0xbeef"));
        assertTrue(ReceiptRepair.superseded(spend(),ChainReview.CURRENT,ChainEvidence.PROOF_EPOCH,30,"0xother"));
        assertFalse(ReceiptRepair.superseded(spend(),ChainReview.CURRENT,ChainEvidence.PROOF_EPOCH,30,"0xbeef"));
    }

    @Test public void reinclusionRequiresChangedValidBlockCoordinates() {
        assertFalse(ReceiptRepair.moved(100,"0xbeef",100,"0xBEEF"));
        assertTrue(ReceiptRepair.moved(100,"0xbeef",101,"0xbeef"));
        assertTrue(ReceiptRepair.moved(100,"0xbeef",100,"0xdead"));
        assertTrue(ReceiptRepair.moved(0,"",100,"0xbeef"));
        assertFalse(ReceiptRepair.moved(100,"0xbeef",0,"0xdead"));
        assertFalse(ReceiptRepair.moved(100,"0xbeef",101,"not a block ID"));
    }
    @Test public void reinclusionCannotBypassSourceTimeAndNewerProofGuards() {
        DexHistory.Spend latest=spend();latest.inclusionBlockId="0xdead";
        assertTrue(ReceiptRepair.moved(100,"0xbeef",latest.inclusionBlock,latest.inclusionBlockId));
        latest.inclusionTimeMs=0;assertFalse(ReceiptRepair.usable(entry(),latest));
        latest.inclusionTimeMs=InclusionTimeTest.BLOCK_TIME;
        assertTrue(ReceiptRepair.superseded(latest,ChainReview.CURRENT,ChainEvidence.PROOF_EPOCH,latest.proofOrder+1,"0xbeef"));
    }

}

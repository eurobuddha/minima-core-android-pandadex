package com.eurobuddha.pandadex;
import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class ChainReviewTest {
    static class Memory implements ChainReview.Store {
        final List<ChainReview.Entry> entries=new ArrayList<>();
        final Map<String,ChainReview.Evidence> proof=new HashMap<>();
        final Map<String,Long> revisions=new HashMap<>();
        long sequence;boolean fail;int writes;
        Memory(int count){for(int i=0;i<count;i++){String id=String.format("0x%04x",i);entries.add(new ChainReview.Entry(id,0));revisions.put(id,0L);}}
        public List<ChainReview.Entry> reviewBatch(long tip){List<ChainReview.Entry> list=new ArrayList<>();for(ChainReview.Entry e:entries)list.add(new ChainReview.Entry(e.txpowid,revisions.get(e.txpowid)));return list;}
        public void reviewed(ChainReview.Entry entry,ChainReview.Evidence evidence,long at){
            if(fail)throw new IllegalStateException("disk full");
            if(revisions.get(entry.txpowid)!=entry.revision)return;
            revisions.put(entry.txpowid,++sequence);writes++;
            if(!evidence.state.isEmpty())proof.put(entry.txpowid,evidence);
        }
    }
    static JSONObject included(){return new TestJson().put("status",true).put("response",new TestJson()
            .put("found",true).put("confirmations",4).put("block",100).put("blockid","0xaabb"));}
    @Test public void repliesNeedExplicitFoundAndExactValidDepthAndInclusionCoordinates() throws Exception {
        assertEquals(ChainReview.CURRENT,ChainReview.evidence(included()).state);
        JSONObject j=included();j.getJSONObject("response").put("confirmations",0);assertEquals(0,ChainReview.evidence(j).depth);
        for(Object bad:new Object[]{-1,"1.2","2147483648",true,"NaN"}){
            j=included();j.getJSONObject("response").put("confirmations",bad);assertEquals("",ChainReview.evidence(j).state);
        }
        j=included();j.getJSONObject("response").remove("blockid");assertEquals("",ChainReview.evidence(j).state);
        j=included();j.getJSONObject("response").put("block","100.2");assertEquals("",ChainReview.evidence(j).state);
        j=included();j.getJSONObject("response").put("found",false);assertEquals(ChainReview.MISSING,ChainReview.evidence(j).state);
        j.put("status",false);assertEquals("",ChainReview.evidence(j).state);
        assertEquals("",ChainReview.evidence(new TestJson().put("status",true)).state);
    }
    @Test public void passIsSerialAndCappedEvenIfStoreReturnsMoreThanRequested(){
        Memory store=new Memory(20);List<String> commands=new ArrayList<>();int[] done={0};
        new ChainReview((c,cb)->{commands.add(c);cb.onResult(included());cb.onResult(included());},store).run(110,()->done[0]++);
        assertEquals(8,commands.size());assertEquals(8,store.writes);assertEquals(1,done[0]);
    }
    @Test public void nodeMissSurvivesOfflineRestartAndValidReturnRestoresCorroboration(){
        Memory store=new Memory(1);new ChainReview((c,cb)->cb.onResult(included()),store).run(110,()->{});
        new ChainReview((c,cb)->cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("found",false))),store).run(111,()->{});
        assertEquals(ChainReview.MISSING,store.proof.get("0x0000").state);
        new ChainReview((c,cb)->cb.onError("offline"),store).run(112,()->{});
        assertEquals(ChainReview.MISSING,store.proof.get("0x0000").state);
        new ChainReview((c,cb)->cb.onResult(included()),store).run(113,()->{});
        assertEquals(ChainReview.CURRENT,store.proof.get("0x0000").state);assertEquals(1,store.entries.size());
    }
    @Test public void firstUnavailableReplyStopsTimeoutCascadeAndPreservesPriorProof(){
        Memory store=new Memory(12);List<String> calls=new ArrayList<>();int[] done={0};
        new ChainReview((c,cb)->{calls.add(c);cb.onError("offline");},store).run(110,()->done[0]++);
        assertEquals(1,calls.size());assertEquals(1,done[0]);assertTrue(store.proof.isEmpty());
    }
    @Test public void storageFailureCompletesAndReleasesTheProcessGate(){
        Memory store=new Memory(1);store.fail=true;int[] done={0};
        ChainReview failed=new ChainReview((c,cb)->cb.onResult(included()),store);failed.run(110,()->done[0]++);
        assertFalse(failed.error().isEmpty());assertEquals(1,done[0]);store.fail=false;
        new ChainReview((c,cb)->cb.onResult(included()),store).run(111,()->done[0]++);
        assertEquals(2,done[0]);assertEquals(1,store.writes);
    }
    @Test public void concurrentHostsDoNotDuplicateAnActiveBatch(){
        Memory store=new Memory(1);NodeApi.Cb[] held={null};int[] done={0};
        new ChainReview((c,cb)->held[0]=cb,store).run(110,()->done[0]++);
        new ChainReview((c,cb)->fail("second active batch"),store).run(110,()->done[0]++);
        assertEquals(1,done[0]);held[0].onResult(included());assertEquals(2,done[0]);assertEquals(1,store.writes);
    }
    @Test public void aStaleCallbackCannotOverwriteAChangedRevision(){
        Memory store=new Memory(1);NodeApi.Cb[] held={null};new ChainReview((c,cb)->held[0]=cb,store).run(110,()->{});
        store.revisions.put("0x0000",12L);held[0].onResult(included());assertEquals(0,store.writes);
    }
    @Test public void missingCorroborationRetainsOriginalRowsButExcludesThemFromAccounting(){
        TradeExport.TradeRow original=new TradeExport.TradeRow("0xcc",123,100,new BigDecimal("0.01"),new BigDecimal("100"),true,true,"0xaa",
                "0xaabb","BOOK","0xcc","","CHAIN_VERIFIED","saved proof"+ChainEvidence.BLOCK_TIME_NOTE,100);
        TradeExport.TradeRow unresolved=ChainReview.decorate(original,ChainReview.MISSING,-1,456,"");
        assertEquals("RECHECK_REQUIRED",unresolved.verificationStatus);assertEquals(123,unresolved.timeMs);
        assertEquals(original.price,unresolved.price);assertEquals(original.sizeMinima,unresolved.sizeMinima);
        assertTrue(unresolved.verificationNote.contains(original.verificationNote));assertEquals("Block time",TradeExport.timeLabel(unresolved));
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.rows.add(unresolved);TradeExport.Report report=TradeExport.build(snapshot);
        assertEquals(1,report.tradeCount);assertEquals(1,report.totals.excludedRechecks);assertEquals(BigDecimal.ZERO,report.totals.minimaBought);
        assertTrue(report.tradesCsv.contains("accounting_included"));assertTrue(report.tradesCsv.contains("0xcc"));assertTrue(report.tradesCsv.contains(",false\n"));
        TradeExport.TradeRow restored=ChainReview.decorate(original,ChainReview.CURRENT,10,789,"");
        assertEquals("CHAIN_VERIFIED",restored.verificationStatus);assertTrue(restored.verificationNote.contains("10 confirmations"));
        assertEquals(original.verificationStatus,"CHAIN_VERIFIED");assertEquals(original.timeMs,restored.timeMs);
    }
    @Test public void explorerCorroborationCannotOverrideAnUnresolvedNodeRecheck(){
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.rows.add(new TradeExport.TradeRow("0xcc",123,100,BigDecimal.ONE,BigDecimal.TEN,true,true,"0xaa",
                "0xaabb","BOOK","0xcc","","RECHECK_REQUIRED","node miss",100));
        ExplorerVerifier.Result external=new ExplorerVerifier.Result();external.status="EXPLORER_OK";external.block=100;external.txpowid="0xaabb";
        TradeExport.Report report=TradeExport.build(TradeExport.verifiedCopy(snapshot,id->external));
        assertEquals(1,report.totals.excludedRechecks);assertEquals(BigDecimal.ZERO,report.totals.minimaBought);
    }
    @Test public void positiveTxpowRecheckDoesNotPromoteLegacyTradeEffectEvidence(){
        TradeExport.TradeRow legacy=new TradeExport.TradeRow("0xcc",123,100,BigDecimal.ONE,BigDecimal.TEN,true,true,"0xaa");
        assertEquals("LOCAL_VERIFIED",ChainReview.decorate(legacy,ChainReview.CURRENT,10,789,"").verificationStatus);
    }
    @Test public void sharedSettlementPathRunsRepeatChecksBeforeCandidateRecovery(){
        class ReviewStore extends FillRecoveryTest.MemoryStore implements ChainReview.Store {
            final Memory checks=new Memory(1);
            public List<ChainReview.Entry> reviewBatch(long tip){return checks.reviewBatch(tip);}
            public void reviewed(ChainReview.Entry e,ChainReview.Evidence p,long at){checks.reviewed(e,p,at);}
        }
        ReviewStore store=new ReviewStore();List<String> calls=new ArrayList<>();
        DexHistory history=new DexHistory((command,cb)->{
            calls.add(command);
            if(command.startsWith("txpow onchain:")) cb.onResult(included());
            else cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray())));
        });
        FillSettler.Outcome outcome=new FillSettler.Outcome(){
            public void record(String c,Order5 o,BigDecimal s,BigDecimal p,boolean b,boolean partial,String id,String e,String note){fail("empty history");}
            public void cancelled(String c){fail("empty history");}
        };
        new FillSettler(history,()->110,outcome,store).onScanComplete();
        assertEquals(1,store.checks.writes);assertTrue(calls.get(0).startsWith("txpow onchain:"));
        assertTrue(calls.stream().anyMatch(c->c.startsWith("history ")));
    }

}

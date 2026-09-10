package com.eurobuddha.pandadex;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TakerRecoveryTest {
    static TakerRecovery.Entry entry() throws Exception {
        TakerReceipt r=TakerReceipt.capture(TakerReceiptTest.expected(),TakerReceiptTest.proof());
        return new TakerRecovery.Entry(r.intent.sources.get(0),r.spend.txpowid,r.spend.inclusionBlock,r.spend.inclusionBlockId,r.json);
    }
    static class Memory extends FillRecoveryTest.MemoryStore implements TakerRecovery.Store,ChainReview.Store {
        final List<TakerRecovery.Entry> entries=new ArrayList<>();int requested,repairs,batches;boolean fail;
        public List<TakerRecovery.Entry> takerBatch(int limit){requested=limit;batches++;return new ArrayList<>(entries);}
        public boolean repairTaker(TakerRecovery.Entry e,TakerReceipt receipt){repairs++;if(fail)throw new IllegalStateException("disk full");return true;}
        public List<ChainReview.Entry> reviewBatch(long tip){return Collections.emptyList();}
        public void reviewed(ChainReview.Entry e,ChainReview.Evidence proof,long at){throw new AssertionError("no rechecks queued");}
    }
    @Test public void repairsOnlyAfterTheFullSelectedSourceAndPayoutProof() throws Exception {
        Memory m=new Memory();m.entries.add(entry());Map<String,DexHistory.Spend> found=TakerReceiptTest.proof();
        final boolean[] done={false};
        new TakerRecovery(m,(ids,cb)->{assertEquals(new HashSet<>(Arrays.asList("0xaa","0xbb")),new HashSet<>(ids));cb.onSpends(found);})
                .run((attempted,changed,error)->{assertTrue(attempted);assertTrue(changed);assertEquals("",error);done[0]=true;});
        assertTrue(done[0]);assertEquals(1,m.repairs);
    }
    @Test public void missingLegPayoutOrInclusionTimeRetainsReceipt() throws Exception {
        for(int fault=0;fault<3;fault++) {
            Memory m=new Memory();m.entries.add(entry());Map<String,DexHistory.Spend> found=TakerReceiptTest.proof();
            if(fault==0)found.remove("0xbb");if(fault==1)found.get("0xaa").outputs.getJSONObject(0).put("amount","99");
            if(fault==2)found.get("0xbb").inclusionTimeMs=0;
            new TakerRecovery(m,(ids,cb)->cb.onSpends(found)).run((a,c,e)->{assertTrue(a);assertFalse(c);});
            assertEquals(0,m.repairs);assertEquals(1,m.entries.size());
        }
    }
    @Test public void capsUntrustedBatchAndSuppressesDuplicateReplies() throws Exception {
        Memory m=new Memory();for(int i=0;i<12;i++)m.entries.add(entry());Map<String,DexHistory.Spend> found=TakerReceiptTest.proof();int[] completions={0};
        new TakerRecovery(m,(ids,cb)->{cb.onSpends(found);cb.onSpends(found);}).run((a,c,e)->completions[0]++);
        assertEquals(4,m.requested);assertEquals(4,m.repairs);assertEquals(1,completions[0]);
    }
    @Test public void corruptEvidenceAndIndexMismatchCannotIssueHistoryLookup() throws Exception {
        for(String json:Arrays.asList("original corrupt receipt",entry().json)) {
            Memory m=new Memory();m.entries.add(new TakerRecovery.Entry("0xff","0xaabb",100,"0xbeef",json));
            new TakerRecovery(m,(ids,cb)->fail("invalid expectation used for lookup"))
                    .run((a,c,e)->{assertTrue(a);assertFalse(c);assertFalse(e.isEmpty());});
            assertEquals(json,m.entries.get(0).json);
        }
    }
    @Test public void concurrentHostsShareOneLookupAndReleaseAfterCallback() throws Exception {
        Memory first=new Memory(),second=new Memory();first.entries.add(entry());second.entries.add(entry());DexHistory.Cb[] held={null};
        new TakerRecovery(first,(ids,cb)->held[0]=cb).run((a,c,e)->{});
        new TakerRecovery(second,(ids,cb)->fail("overlapping lookup")).run((a,c,e)->assertFalse(a));
        assertEquals(0,second.batches);held[0].onSpends(TakerReceiptTest.proof());
        new TakerRecovery(second,(ids,cb)->cb.onSpends(Collections.emptyMap())).run((a,c,e)->assertTrue(a));
        assertEquals(1,second.batches);
    }
    @Test public void asyncLookupExceptionAndStorageFailureCannotWedgeRecovery() throws Exception {
        Memory m=new Memory();m.entries.add(entry());DexHistory.Cb[] held={null};int[] completed={0};
        new TakerRecovery(m,(ids,cb)->held[0]=cb).run((a,c,e)->{assertFalse(e.isEmpty());completed[0]++;});
        held[0].onSpends(new HashMap<String,DexHistory.Spend>(){@Override public DexHistory.Spend get(Object key){throw new IllegalStateException("bad lookup");}});
        assertEquals(1,completed[0]);m.fail=true;Map<String,DexHistory.Spend> found=TakerReceiptTest.proof();
        new TakerRecovery(m,(ids,cb)->cb.onSpends(found)).run((a,c,e)->{assertFalse(c);assertFalse(e.isEmpty());});
        assertEquals(1,m.repairs);assertEquals(1,m.entries.size());
    }
    @Test public void sharedSettlerAlternatesAggregateRecoveryWithPublicHistory() throws Exception {
        Memory m=new Memory(){boolean next=true;public boolean claimTakerTurn(){boolean take=next;next=!next;return take;}};
        m.entries.add(entry());List<String> calls=new ArrayList<>();
        DexHistory h=new DexHistory((cmd,cb)->{calls.add(cmd);cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray())));});
        FillSettler s=new FillSettler(h,()->100,new FillRecoveryTest.Outcome(),m);
        s.onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:true"));calls.clear();
        s=new FillSettler(h,()->100,new FillRecoveryTest.Outcome(),m);
        s.onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:false"));calls.clear();
        s.onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:true"));assertEquals(2,m.batches);
    }
    @Test public void pendingAggregateRemainsVisibleButExcludedDespiteCurrentTxpowCheck() {
        TradeExport.TradeRow row=new TradeExport.TradeRow("0xaa",123,100,new java.math.BigDecimal("0.01"),new java.math.BigDecimal("100"),true,false,"",
                "0xaabb","BOOK","0xaa","","CHAIN_VERIFIED","original"+ChainEvidence.BLOCK_TIME_NOTE,100);
        TradeExport.TradeRow pending=ChainReview.aggregatePending(ChainReview.decorate(row,ChainReview.CURRENT,4,456,""));
        assertFalse(ChainReview.accounted(pending.verificationStatus));assertEquals(123,pending.timeMs);assertEquals(row.sizeMinima,pending.sizeMinima);
        assertTrue(pending.verificationNote.contains("all selected inputs"));
        TradeExport.Snapshot s=new TradeExport.Snapshot();s.rows.add(pending);assertEquals(1,TradeExport.build(s).totals.excludedRechecks);
    }
    @Test public void emptyAggregateQueueStillAllowsMarketDiscoveryInSamePass() {
        Memory m=new Memory();List<String> calls=new ArrayList<>();
        DexHistory h=new DexHistory((cmd,cb)->{calls.add(cmd);cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray())));});
        new FillSettler(h,()->100,new FillRecoveryTest.Outcome(),m).onScanComplete();
        assertTrue(calls.get(0).startsWith("history relevant:false"));
    }
}

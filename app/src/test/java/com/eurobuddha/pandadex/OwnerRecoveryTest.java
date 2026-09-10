package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class OwnerRecoveryTest {
    static class Memory extends FillRecoveryTest.MemoryStore implements OwnerRecovery.Store {
        final List<OwnerRecovery.Entry> entries=new ArrayList<>();int repairs,batches;boolean fail;
        public List<OwnerRecovery.Entry> ownerBatch(int limit){assertEquals(4,limit);batches++;return new ArrayList<>(entries);}
        public boolean repairOwner(OwnerRecovery.Entry entry,OwnerReceipt receipt){if(fail)throw new IllegalStateException("disk full");repairs++;return true;}
    }
    static Pending.Row row()throws Exception{return CreationEvidenceTest.receipt();}
    static OwnerRecovery.Entry entry(Pending.Row row)throws Exception {
        OwnerReceipt r=OwnerReceipt.capture(row,OwnerRevisionTest.moved(row));return new OwnerRecovery.Entry(r.id,r.txpowid,r.block,r.blockid,r.json);
    }
    @Test public void recoversCompletedCreationFromEveryOriginalInput()throws Exception {
        Pending.Row row=row();Memory m=new Memory();m.entries.add(entry(row));
        new OwnerRecovery(m,(ids,cb)->{assertEquals(new HashSet<>(CreationEvidence.inputs(row)),new HashSet<>(ids));cb.onSpends(unchecked(row));})
            .run((attempted,changed,error)->{assertTrue(attempted);assertTrue(changed);assertEquals("",error);});
        assertEquals(1,m.repairs);
    }
    static Map<String,DexHistory.Spend> unchecked(Pending.Row row){try{return OwnerRevisionTest.moved(row);}catch(Exception invalid){throw new AssertionError(invalid);}}
    @Test public void missingOrWrongEffectKeepsCompletedOwnerEvidence()throws Exception {
        for(int fault=0;fault<2;fault++) {
            Pending.Row row=row();Memory m=new Memory();m.entries.add(entry(row));Map<String,DexHistory.Spend> found=unchecked(row);
            if(fault==0)found.remove(CreationEvidence.inputs(row).get(0));else found.values().iterator().next().outputs.getJSONObject(0).put("amount","999");
            new OwnerRecovery(m,(ids,cb)->cb.onSpends(found)).run((a,c,e)->assertFalse(c));assertEquals(0,m.repairs);assertEquals(1,m.entries.size());
        }
    }
    @Test public void boundedBatchAndDuplicateCallbackDoNotRepeatCompletion()throws Exception {
        Pending.Row row=row();Memory m=new Memory();for(int i=0;i<12;i++)m.entries.add(entry(row));int[] done={0};
        new OwnerRecovery(m,(ids,cb)->{cb.onSpends(unchecked(row));cb.onSpends(unchecked(row));}).run((a,c,e)->done[0]++);
        assertEquals(4,m.repairs);assertEquals(1,done[0]);
    }
    @Test public void malformedOrMismatchedArchiveCannotDispatchLookup()throws Exception {
        OwnerRecovery.Entry good=entry(row());
        for(OwnerRecovery.Entry e:Arrays.asList(new OwnerRecovery.Entry("other",good.txpowid,good.block,good.blockid,good.json),new OwnerRecovery.Entry(good.id,good.txpowid,good.block,good.blockid,good.json+" trailing"))) {
            Memory m=new Memory();m.entries.add(e);
            new OwnerRecovery(m,(ids,cb)->fail("invalid stored intent reached lookup")).run((a,c,error)->{assertTrue(a);assertFalse(c);assertFalse(error.isEmpty());});
            assertEquals(0,m.repairs);assertEquals(e.json,m.entries.get(0).json);
        }
    }
    @Test public void simultaneousHostsShareOneOwnerLookup()throws Exception {
        Pending.Row row=row();Memory first=new Memory(),second=new Memory();first.entries.add(entry(row));second.entries.add(entry(row));DexHistory.Cb[] held={null};
        new OwnerRecovery(first,(ids,cb)->held[0]=cb).run((a,c,e)->{});
        new OwnerRecovery(second,(ids,cb)->fail("overlapping owner lookup")).run((a,c,e)->assertFalse(a));assertEquals(0,second.batches);
        held[0].onSpends(Collections.emptyMap());new OwnerRecovery(second,(ids,cb)->cb.onSpends(Collections.emptyMap())).run((a,c,e)->assertTrue(a));assertEquals(1,second.batches);
    }
    @Test public void lookupAndStorageFailuresReleaseRecoveryAndRetainArchive()throws Exception {
        Pending.Row row=row();Memory m=new Memory();m.entries.add(entry(row));
        new OwnerRecovery(m,(ids,cb)->{throw new IllegalStateException("transport");}).run((a,c,e)->{assertFalse(c);assertFalse(e.isEmpty());});
        m.fail=true;new OwnerRecovery(m,(ids,cb)->cb.onSpends(unchecked(row))).run((a,c,e)->{assertFalse(c);assertFalse(e.isEmpty());});
        m.fail=false;new OwnerRecovery(m,(ids,cb)->cb.onSpends(unchecked(row))).run((a,c,e)->assertTrue(c));assertEquals(1,m.repairs);assertEquals(1,m.entries.size());
    }
    @Test public void ownerAlternationLeavesMarketHistoryEligible()throws Exception {
        Memory m=new Memory(){boolean turn=true;public boolean claimOwnerTurn(){boolean take=turn;turn=!turn;return take;}};m.entries.add(entry(row()));List<String> calls=new ArrayList<>();
        DexHistory history=new DexHistory((cmd,cb)->{calls.add(cmd);cb.onResult(emptyReply());});
        new FillSettler(history,()->100,new FillRecoveryTest.Outcome(),m).onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:true"));calls.clear();
        new FillSettler(history,()->100,new FillRecoveryTest.Outcome(),m).onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:false"));
    }
    static JSONObject emptyReply(){return new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray()));}
    @Test public void emptyOwnerQueueDoesNotDelayPublicHistory() {
        Memory m=new Memory();List<String> calls=new ArrayList<>();DexHistory h=new DexHistory((cmd,cb)->{calls.add(cmd);cb.onResult(emptyReply());});
        new FillSettler(h,()->100,new FillRecoveryTest.Outcome(),m).onScanComplete();assertTrue(calls.get(0).startsWith("history relevant:false"));
    }
}

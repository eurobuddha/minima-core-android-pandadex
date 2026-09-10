package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryProgressTest {
    static class Progress implements DexHistory.ProgressStore {
        final Map<String,Integer> positions = new HashMap<>();
        boolean fail;
        String key(boolean relevant, String id) { return relevant + ":" + id; }
        public int offset(boolean relevant, Collection<String> ids) {
            int result = Integer.MAX_VALUE;
            for (String id : ids) result = Math.min(result, positions.getOrDefault(key(relevant,id), 0));
            return result == Integer.MAX_VALUE ? 0 : result;
        }
        public void checkpoint(boolean relevant, Collection<String> pending, Collection<String> found, int offset) {
            if (fail) throw new IllegalStateException("disk full");
            for (String id : pending) positions.put(key(relevant,id), offset);
            for (String id : found) positions.remove(key(relevant,id));
        }
    }
    static class Node implements DexHistory.Cmd {
        final List<JSONObject> txs = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        boolean offline, unconfirmed;
        public void run(String command, NodeApi.Cb cb) {
            calls.add(command);
            if (offline) { cb.onError("offline"); return; }
            if (command.startsWith("txpow ")) {
                cb.onResult(new TestJson().put("status",true).put("response",new TestJson()
                        .put("found",!unconfirmed).put("confirmations",unconfirmed ? -1 : 4).put("block",100).put("blockid","0xbbaa")));
                return;
            }
            int max = number(command,"max"), offset = number(command,"offset");
            JSONArray rows = new JSONArray();
            for (int i=offset; i<txs.size() && i<offset+max; i++) rows.put(txs.get(i));
            cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",rows)));
        }
        static int number(String command,String key) {
            return Integer.parseInt(command.split(key+":")[1].split(" ")[0]);
        }
    }
    static JSONObject tx(int index, String coin) {
        return new TestJson().put("txpowid",String.format("0x%08x",index+1))
                .put("body",new TestJson().put("txn",new TestJson()
                        .put("inputs",new JSONArray().put(new TestJson().put("coinid",coin)))
                        .put("outputs",new JSONArray())));
    }
    static Node history(int count,int target) {
        Node node = new Node();
        for (int i=0;i<count;i++) node.txs.add(tx(i,i==target ? "target" : "other"+i));
        return node;
    }
    static Map<String,DexHistory.Spend> lookup(Node node,Progress progress,boolean relevant) {
        Map<String,DexHistory.Spend> result = new HashMap<>();
        DexHistory h = new DexHistory(node,progress);
        if (relevant) h.findSpends(Collections.singleton("target"),result::putAll);
        else h.findMarketSpends(Collections.singleton("target"),result::putAll);
        return result;
    }

    @Test public void restartedPagerEventuallyFindsSpendBeyondItsSinglePassBudget() {
        Node node=history(420,310);Progress progress=new Progress();Map<String,DexHistory.Spend> found=new HashMap<>();
        for(int pass=0;pass<8 && found.isEmpty();pass++) {
            node.calls.clear();found=lookup(node,progress,false);
            long pages=node.calls.stream().filter(c->c.startsWith("history ")).count();
            assertTrue(pages<=DexHistory.MAX_FETCHES);
        }
        assertTrue(found.containsKey("target"));assertEquals(4,found.get("target").confirmations);
        assertFalse(progress.positions.containsKey("false:target"));
    }
    @Test public void resumesWithOverlapInsteadOfStartingTheWholeWalkAgain() {
        Node node=history(420,-1);Progress progress=new Progress();lookup(node,progress,false);
        int saved=progress.offset(false,Collections.singleton("target"));assertTrue(saved>0 && saved<96);
        node.calls.clear();lookup(node,progress,false);
        assertEquals(0,Node.number(node.calls.get(0),"offset"));
        assertEquals(saved,Node.number(node.calls.get(1),"offset"));
        assertTrue(progress.offset(false,Collections.singleton("target"))>saved);
    }
    @Test public void newIncludedHeadTransactionIsFoundDuringAnOlderBackfill() {
        Node node=history(420,-1);Progress progress=new Progress();lookup(node,progress,false);
        node.txs.add(0,tx(999,"target"));node.calls.clear();
        assertTrue(lookup(node,progress,false).containsKey("target"));
        assertEquals(3,node.calls.size()); // History, inclusion, and the stock node's inclusion-block lookup.
        assertEquals("txpow txpowid:0xbbaa",node.calls.get(2));
    }
    @Test public void reachingTheEndCyclesWithoutDeclaringTheMissingReceiptFailed() {
        Node node=history(130,-1);Progress progress=new Progress();
        assertTrue(lookup(node,progress,false).isEmpty());
        assertTrue(lookup(node,progress,false).isEmpty());
        assertEquals(0,progress.offset(false,Collections.singleton("target")));
        node.txs.set(30,tx(999,"target"));
        assertTrue(lookup(node,progress,false).containsKey("target"));
    }
    @Test public void failedHeadReadPreservesExistingProgressAndReturns() {
        Node node=history(420,-1);Progress progress=new Progress();lookup(node,progress,false);
        int saved=progress.offset(false,Collections.singleton("target"));node.offline=true;node.calls.clear();
        assertTrue(lookup(node,progress,false).isEmpty());assertEquals(1,node.calls.size());
        assertEquals(saved,progress.offset(false,Collections.singleton("target")));
    }
    @Test public void publicAndWalletHistoriesHaveIndependentOffsets() {
        Node node=history(420,-1);Progress progress=new Progress();lookup(node,progress,false);
        assertEquals(0,progress.offset(true,Collections.singleton("target")));
        node.calls.clear();lookup(node,progress,true);
        assertTrue(node.calls.get(0).contains("relevant:true"));
        assertEquals(0,Node.number(node.calls.get(0),"offset"));
    }
    @Test public void unconfirmedMatchIsRetriedOnTheNextTraversal() {
        Node node=history(130,110);Progress progress=new Progress();node.unconfirmed=true;
        assertTrue(lookup(node,progress,false).isEmpty());assertTrue(lookup(node,progress,false).isEmpty());
        assertTrue(progress.positions.containsKey("false:target"));node.unconfirmed=false;
        assertTrue(lookup(node,progress,false).isEmpty());
        assertTrue(lookup(node,progress,false).containsKey("target"));
    }
    @Test public void progressWriteFailureStillDeliversVerifiedEvidenceAndDoesNotDropPriorState() {
        Node node=history(20,3);Progress progress=new Progress();progress.fail=true;
        DexHistory h=new DexHistory(node,progress);Map<String,DexHistory.Spend> result=new HashMap<>();
        h.findMarketSpends(Collections.singleton("target"),result::putAll);
        assertTrue(result.containsKey("target"));assertFalse(h.recoveryError().isEmpty());
    }
    @Test public void insertingPagesDuringBackfillDoesNotTurnAMissIntoFalseSuccess() {
        Node node=history(420,310);Progress progress=new Progress();lookup(node,progress,false);
        for(int i=0;i<20;i++) node.txs.add(0,tx(1000+i,"new"+i));
        Map<String,DexHistory.Spend> found=new HashMap<>();
        for(int pass=0;pass<8 && found.isEmpty();pass++) found=lookup(node,progress,false);
        assertEquals(String.format("0x%08x",311),found.get("target").txpowid);
    }
    @Test public void oversizedNewestTransactionDoesNotStarveAnOlderSavedSearch() {
        Progress progress=new Progress();progress.positions.put("false:target",88);
        Node node=new Node() {
            @Override public void run(String command,NodeApi.Cb cb) {
                if(command.startsWith("history ") && number(command,"offset")==0) {
                    calls.add(command); cb.onError(NodeApi.ERR_TOO_LONG); return;
                }
                super.run(command,cb);
            }
        };
        node.txs.addAll(history(130,90).txs);
        assertTrue(lookup(node,progress,false).containsKey("target"));
        assertTrue(node.calls.size()<=DexHistory.MAX_FETCHES+1);
    }
    @Test public void replyExceedingRequestedPageIsNotUsedToAdvanceOrVerify() {
        Progress progress=new Progress();Node node=new Node() {
            @Override public void run(String command,NodeApi.Cb cb) {
                calls.add(command);JSONArray oversized=new JSONArray();
                for(int i=0;i<9;i++) oversized.put(tx(i,"target"));
                cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",oversized)));
            }
        };
        assertTrue(lookup(node,progress,false).isEmpty());assertEquals(1,node.calls.size());
        assertEquals(0,progress.offset(false,Collections.singleton("target")));
    }
}

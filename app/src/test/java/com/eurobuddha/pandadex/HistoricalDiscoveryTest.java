package com.eurobuddha.pandadex;

import org.json.*;
import org.junit.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class HistoricalDiscoveryTest {
    static class Store extends FillRecoveryTest.MemoryStore {
        final Map<String,String> ledger=new HashMap<>();
        public void enqueueHistorical(Order5 order) {
            if(failEnqueue) throw new IllegalStateException("disk full");
            rows.putIfAbsent(order.coinid,new FillSettler.Entry(order.coinid,order.sourceJson(),true));
        }
        public boolean known(String id,String txid) {
            return rows.containsKey(id) || txid.equals(ledger.get(id));
        }
        public void retire(FillSettler.Entry entry,DexHistory.Spend spend) {
            ledger.put(entry.coinid,spend.txpowid);remove(entry.coinid);
        }
    }
    static class Result extends FillRecoveryTest.Outcome {
        final Map<String,Long> times=new HashMap<>();int recordedCalls;
        public void recordAt(String id,Order5 order,BigDecimal size,BigDecimal price,boolean buy,
                             boolean partial,String txid,String evidence,String note,long time,long block) {
            record(id,order,size,price,buy,partial,txid,evidence,note);
            times.put(id,time);recordedCalls++;
        }
    }
    static class Node extends HistoryProgressTest.Node {
        boolean blockUnavailable;
        public void run(String cmd,NodeApi.Cb cb) {
            if(cmd.startsWith("txpow onchain:")) {
                calls.add(cmd);
                try {JSONObject proof=InclusionTimeTest.inclusion();
                    if(unconfirmed) proof.getJSONObject("response").put("found",false);
                    cb.onResult(proof);
                }catch(Exception e){throw new AssertionError(e);}
                return;
            }
            if(cmd.startsWith("txpow txpowid:")) {
                calls.add(cmd);if(blockUnavailable){cb.onError("block unavailable");return;}
                try {JSONObject block=InclusionTimeTest.block();JSONArray ids=new JSONArray();
                    for(JSONObject tx:txs)ids.put(tx.optString("txpowid"));
                    block.getJSONObject("response").getJSONObject("body").put("txnlist",ids);cb.onResult(block);
                }catch(Exception e){throw new AssertionError(e);}
                return;
            }
            super.run(cmd,cb);
        }
    }
    static JSONObject tx(int i,boolean candidate) throws Exception {
        JSONObject raw=new JSONObject(TransactionHardeningTest.orderCoin().toString())
                .put("coinid",String.format("0x%08x",i+1000))
                .put("address",candidate?DexContract.ADDR_V5:"0xdead");
        Order5 order=Order5.from(raw);
        return new TestJson().put("txpowid",String.format("0x%08x",i+1))
                .put("body",new TestJson().put("txn",new TestJson().put("inputs",new JSONArray().put(raw))
                        .put("outputs",new JSONArray().put(new TestJson().put("address",order.wantAddr)
                                .put("tokenid",order.wantTok).put("amount","0").put("tokenamount","1")))));
    }
    static FillSettler settler(Node node,Store store,Result result,HistoryProgressTest.Progress progress) {
        return new FillSettler(new DexHistory(node,progress),()->104,result,store);
    }
    @Test public void coldStartDiscoversAndRecordsASpendWithoutAnyBookObservation() throws Exception {
        Node node=new Node();node.txs.add(tx(0,true));Store store=new Store();Result result=new Result();
        settler(node,store,result,new HistoryProgressTest.Progress()).onScanComplete();
        assertEquals(1,result.recordedCalls);assertEquals(Long.valueOf(InclusionTimeTest.BLOCK_TIME),result.times.get("0x000003e8"));
        assertEquals(1,store.ledger.size());assertTrue(store.rows.isEmpty());
    }
    @Test public void savedDiscoveryPositionFindsOlderSpendsAcrossRestarts() throws Exception {
        Node node=new Node();for(int i=0;i<420;i++)node.txs.add(tx(i,i==310));
        Store store=new Store();Result result=new Result();HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        for(int i=0;i<8 && result.recordedCalls==0;i++) {
            node.calls.clear();settler(node,store,result,progress).onScanComplete();
            assertTrue(node.calls.size()<=3*DexHistory.MAX_FETCHES);
        }
        assertEquals(1,result.recordedCalls);assertTrue(result.recorded.contains(String.format("0x%08x",1310)));
    }
    @Test public void missingTimestampStaysHistoricalAcrossRestartAndCannotInflateToday() throws Exception {
        Node node=new Node();node.txs.add(tx(0,true));node.blockUnavailable=true;
        Store store=new Store();Result result=new Result();HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        settler(node,store,result,progress).onScanComplete();
        assertEquals(0,result.recordedCalls);assertTrue(store.batch(1).get(0).historical);
        settler(node,store,result,progress).onScanComplete();assertEquals(0,result.recordedCalls);
        node.blockUnavailable=false;settler(node,store,result,progress).onScanComplete();
        assertEquals(Long.valueOf(InclusionTimeTest.BLOCK_TIME),result.times.get("0x000003e8"));
    }
    @Test public void ledgerPreventsImportAgainAfterRestartEvenIfTapeCacheHasBeenTrimmed() throws Exception {
        Node node=new Node();node.txs.add(tx(0,true));Store store=new Store();Result result=new Result();
        HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        settler(node,store,result,progress).onScanComplete();assertEquals(1,result.recordedCalls);
        store.rows.clear();node.calls.clear();settler(node,store,result,progress).onScanComplete();
        assertEquals(1,result.recordedCalls);assertTrue(node.calls.stream().noneMatch(c->c.startsWith("txpow ")));
    }
    @Test public void malformedWrongAddressAndUnconfirmedInputsAreNotImported() throws Exception {
        Node node=new Node();node.txs.add(tx(0,false));JSONObject bad=tx(1,true);
        bad.getJSONObject("body").getJSONObject("txn").getJSONArray("inputs").getJSONObject(0).getJSONObject("state").put("2","bad");
        node.txs.add(bad);Store store=new Store();Result result=new Result();HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        settler(node,store,result,progress).onScanComplete();assertEquals(0,result.recordedCalls);assertTrue(store.rows.isEmpty());
        node.txs.clear();node.txs.add(tx(2,true));node.unconfirmed=true;
        settler(node,store,result,progress).onScanComplete();assertTrue(store.rows.isEmpty());assertTrue(store.ledger.isEmpty());
    }
    @Test public void failedEnqueueStopsBeforeAdvancingPastUnreadCandidate() throws Exception {
        Node node=new Node();for(int i=0;i<150;i++)node.txs.add(tx(i,i==40));
        Store store=new Store();store.failEnqueue=true;Result result=new Result();HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        settler(node,store,result,progress).onScanComplete();
        assertTrue(progress.offset(false,Collections.singleton(DexHistory.DISCOVERY_CURSOR))<=40);
        assertTrue(store.rows.isEmpty());assertEquals(0,result.recordedCalls);
        store.failEnqueue=false;settler(node,store,result,progress).onScanComplete();assertEquals(1,result.recordedCalls);
    }
    @Test public void failedRecordingLeavesADurableHistoricalCandidateForRetry() throws Exception {
        Node node=new Node();node.txs.add(tx(0,true));Store store=new Store();Result result=new Result();result.fail=true;
        HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();settler(node,store,result,progress).onScanComplete();
        assertEquals(1,store.rows.size());assertTrue(store.ledger.isEmpty());assertTrue(store.batch(1).get(0).historical);
        result.fail=false;settler(node,store,result,progress).onScanComplete();assertEquals(1,result.recordedCalls);assertTrue(store.rows.isEmpty());
    }
    @Test public void oneLargeTransactionIsImportedInBoundedIdempotentChunks() throws Exception {
        Node node=new Node();JSONObject many=tx(0,true);JSONObject transaction=many.getJSONObject("body").getJSONObject("txn");
        JSONArray inputs=new JSONArray(),outputs=new JSONArray();
        for(int i=0;i<40;i++) {
            JSONObject part=tx(i,true).getJSONObject("body").getJSONObject("txn");
            inputs.put(part.getJSONArray("inputs").get(0));outputs.put(part.getJSONArray("outputs").get(0));
        }
        transaction.put("inputs",inputs).put("outputs",outputs);node.txs.add(many);
        Store store=new Store();Result result=new Result();HistoryProgressTest.Progress progress=new HistoryProgressTest.Progress();
        settler(node,store,result,progress).onScanComplete();assertEquals(32,result.recordedCalls);
        settler(node,store,result,progress).onScanComplete();assertEquals(40,result.recordedCalls);
        settler(node,store,result,progress).onScanComplete();assertEquals(40,result.recordedCalls);
        assertEquals(40,store.ledger.size());
    }
}

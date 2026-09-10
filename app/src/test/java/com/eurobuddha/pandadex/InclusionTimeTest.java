package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class InclusionTimeTest {
    static final long BLOCK_TIME = 1788000000123L;
    static JSONObject inclusion() {
        return new TestJson().put("status",true).put("response",new TestJson()
                .put("found",true).put("confirmations","4").put("block","100")
                .put("blockid","0xbeef").put("tip","104"));
    }
    static JSONObject block() {
        return new TestJson().put("status",true).put("response",new TestJson()
                .put("txpowid","0xbeef").put("isblock",true).put("istransaction",false)
                .put("header",new TestJson().put("block","100").put("timemilli",String.valueOf(BLOCK_TIME)))
                .put("body",new TestJson().put("txnlist",new JSONArray().put("0xaabb"))));
    }
    static JSONObject historyPage() {
        JSONObject tx = new TestJson().put("txpowid","0xaabb")
                .put("header",new TestJson().put("block","88").put("timemilli","123"))
                .put("body",new TestJson().put("txn",new TestJson()
                        .put("inputs",new JSONArray().put(new TestJson().put("coinid","source")))
                        .put("outputs",new JSONArray())));
        return new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray().put(tx)));
    }
    @Test public void readsTheLinkedInclusionBlockRatherThanTheTransactionHeaderTime() {
        List<String> calls = new ArrayList<>(); Map<String,DexHistory.Spend> found = new HashMap<>();
        DexHistory h = new DexHistory((cmd,cb) -> {
            calls.add(cmd);
            cb.onResult(cmd.startsWith("history ") ? historyPage() : cmd.startsWith("txpow onchain:") ? inclusion() : block());
        });
        h.findSpends(Collections.singleton("source"),found::putAll);
        assertEquals(BLOCK_TIME,found.get("source").inclusionTimeMs);
        assertEquals(100,found.get("source").inclusionBlock);
        assertEquals("0xbeef",found.get("source").inclusionBlockId);
        assertEquals("txpow txpowid:0xbeef",calls.get(2));assertEquals(3,calls.size());
    }
    @Test public void wrongBlockIdentityOrHeightCannotSupplyTheTime() throws Exception {
        JSONObject reply = block();reply.getJSONObject("response").put("txpowid","0xdead");
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),reply,"0xaabb"));
        reply = block();reply.getJSONObject("response").getJSONObject("header").put("block","101");
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),reply,"0xaabb"));
    }
    @Test public void unrelatedTransactionOrNonBlockReplyCannotSupplyTheTime() throws Exception {
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),block(),"0xdead"));
        JSONObject reply = block();reply.getJSONObject("response").put("isblock",false);
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),reply,"0xaabb"));
        reply = block();reply.put("status",false);
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),reply,"0xaabb"));
    }
    @Test public void aBlockCanCarryItsOwnTransaction() throws Exception {
        JSONObject reply = block();reply.getJSONObject("response").put("istransaction",true);
        assertEquals(BLOCK_TIME,ChainEvidence.inclusionTime(inclusion(),reply,"0xbeef"));
        reply.getJSONObject("response").put("istransaction",false);
        assertEquals(0,ChainEvidence.inclusionTime(inclusion(),reply,"0xbeef"));
    }
    @Test public void missingMalformedNegativeOrOverflowTimesStayUnknown() throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,"-1","1.5","1e999999","9223372036854775808",true,"0"}) {
            JSONObject reply = block();reply.getJSONObject("response").getJSONObject("header").put("timemilli",value);
            assertEquals(String.valueOf(value),0,ChainEvidence.inclusionTime(inclusion(),reply,"0xaabb"));
        }
    }
    @Test public void missingOrUnconfirmedInclusionCannotSupplyTheTime() throws Exception {
        JSONObject reply = inclusion();reply.getJSONObject("response").put("found",false);
        assertEquals(0,ChainEvidence.inclusionTime(reply,block(),"0xaabb"));
        reply = inclusion();reply.getJSONObject("response").put("confirmations",-1);
        assertEquals(0,ChainEvidence.inclusionTime(reply,block(),"0xaabb"));
        reply = inclusion();reply.getJSONObject("response").remove("blockid");
        assertEquals(0,ChainEvidence.inclusionTime(reply,block(),"0xaabb"));
    }
    @Test public void failedBlockFetchDoesNotEraseValidInclusionOrInventAClockTime() {
        Map<String,DexHistory.Spend> found = new HashMap<>();
        new DexHistory((cmd,cb) -> {
            if(cmd.startsWith("history ")) cb.onResult(historyPage());
            else if(cmd.startsWith("txpow onchain:")) cb.onResult(inclusion());
            else cb.onError("archive unavailable");
        }).findSpends(Collections.singleton("source"),found::putAll);
        assertEquals(4,found.get("source").confirmations);
        assertEquals(100,found.get("source").inclusionBlock);
        assertEquals(0,found.get("source").inclusionTimeMs);
    }
    @Test public void mismatchedBlockReplyStillLeavesTimestampUnknownInThePager() throws Exception {
        JSONObject wrong = block();wrong.getJSONObject("response").put("txpowid","0xdead");
        Map<String,DexHistory.Spend> found = new HashMap<>();
        new DexHistory((cmd,cb) -> cb.onResult(cmd.startsWith("history ") ? historyPage()
                : cmd.startsWith("txpow onchain:") ? inclusion() : wrong))
                .findSpends(Collections.singleton("source"),found::putAll);
        assertEquals(0,found.get("source").inclusionTimeMs);assertEquals(4,found.get("source").confirmations);
    }
    @Test public void verifiedTimeAndHeightReachTheFillRecordingBoundary() throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();Order5 order=Order5.from(raw);
        JSONObject page=historyPage();JSONObject tx=page.getJSONObject("response").getJSONArray("txpows").getJSONObject(0);
        JSONObject txn=tx.getJSONObject("body").getJSONObject("txn");
        txn.put("inputs",new JSONArray().put(raw));
        txn.put("outputs",new JSONArray().put(new TestJson().put("address",order.wantAddr)
                .put("tokenid",order.wantTok).put("amount","0").put("tokenamount",order.wantAmt.toPlainString())));
        DexHistory h=new DexHistory((cmd,cb)->cb.onResult(cmd.startsWith("history ")?page
                :cmd.startsWith("txpow onchain:")?inclusion():block()));
        long[] recorded={0,0};String[] note={""};
        FillSettler.Outcome outcome=new FillSettler.Outcome(){
            public void record(String id,Order5 o,java.math.BigDecimal size,java.math.BigDecimal price,
                               boolean buy,boolean partial,String txid,String evidence,String text){fail("timestamp bypassed");}
            public void recordAt(String id,Order5 o,java.math.BigDecimal size,java.math.BigDecimal price,
                                 boolean buy,boolean partial,String txid,String evidence,String text,long time,long height){
                recorded[0]=time;recorded[1]=height;note[0]=text;
            }
            public void cancelled(String id){fail("not a refund");}
        };
        FillSettler settler=new FillSettler(h,()->104,outcome,new FillRecoveryTest.MemoryStore());
        settler.onFill(order.coinid,order,order.minimaAmount(),order.price(),true,false,100);
        settler.onScanComplete();assertEquals(BLOCK_TIME,recorded[0]);assertEquals(100,recorded[1]);
        assertTrue(note[0].endsWith(ChainEvidence.BLOCK_TIME_NOTE));
    }
    @Test public void recoveredOldAndFutureDatedFillsDoNotGenerateFreshNotifications() {
        assertFalse(FillSettler.recentForNotification(BLOCK_TIME,BLOCK_TIME+3600000));
        assertFalse(FillSettler.recentForNotification(BLOCK_TIME,BLOCK_TIME-1));
        assertTrue(FillSettler.recentForNotification(BLOCK_TIME,BLOCK_TIME+60000));
        assertFalse(FillSettler.recentForNotification(0,BLOCK_TIME));
    }
    @Test public void legacyObservationLabelsAreNotUpgradedMerelyByChainVerificationStatus() {
        TradeExport.TradeRow legacy=new TradeExport.TradeRow("id",BLOCK_TIME,100,null,null,true,true,"order", "0xaabb","BOOK","id","","CHAIN_VERIFIED","Old proof",100);
        assertEquals("Observed",TradeExport.timeLabel(legacy));
        TradeExport.TradeRow timed=new TradeExport.TradeRow("id",BLOCK_TIME,100,null,null,true,true,"order", "0xaabb","BOOK","id","","CHAIN_VERIFIED","Proof"+ChainEvidence.BLOCK_TIME_NOTE,100);
        assertEquals("Block time",TradeExport.timeLabel(timed));
    }
}

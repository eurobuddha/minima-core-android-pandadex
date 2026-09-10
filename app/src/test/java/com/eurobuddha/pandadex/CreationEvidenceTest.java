package com.eurobuddha.pandadex;
import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class CreationEvidenceTest {
    static Pending.Row receipt() {
        return CreationEvidence.prepare(false,new BigDecimal("100"),new BigDecimal("0.01"),"0x00",
                new BigDecimal("100"),"0xbb",CreateFundingTest.state(),Arrays.asList(
                CreateFundingTest.coin("0x00","75"),new TestJson().put("coinid","0xee").put("tokenid","0x00").put("amount","50")),100);
    }
    static JSONObject transaction(Pending.Row row) throws Exception {
        JSONObject saved=row.creation;JSONArray inputs=new JSONArray(),states=new JSONArray();
        for(String id:CreationEvidence.inputs(row)) inputs.put(new TestJson().put("coinid",id));
        JSONArray expected=saved.getJSONArray("state");
        for(int i=0;i<9;i++) states.put(new TestJson().put("port",i).put("data",expected.getString(i)));
        String token=saved.getString("token");
        JSONObject order=new TestJson().put("coinid","0x00").put("address",DexContract.ADDR_V5)
                .put("tokenid",token).put("amount",saved.getString("lock")).put("storestate",true);
        JSONArray outputs=new JSONArray().put(order);
        if(!Util.isMinima(token)) order.put("amount","0.00000001").put("tokenamount",saved.getString("lock"));
        if(new BigDecimal(saved.getString("change")).signum()>0) {
            JSONObject change=new TestJson().put("coinid","0x00").put("address",saved.getString("payout"))
                    .put("tokenid",token).put("amount",saved.getString("change")).put("storestate",false);
            if(!Util.isMinima(token)) change.put("amount","0.00000001").put("tokenamount",saved.getString("change"));
            outputs.put(change);
        }
        return new TestJson().put("txpowid","0xaabb").put("body",new TestJson().put("txn",new TestJson()
                .put("transactionid","0xccdd").put("inputs",inputs).put("outputs",outputs).put("state",states)));
    }
    static Map<String,DexHistory.Spend> proof(Pending.Row row,JSONObject tx) {
        HistoryProgressTest.Node node=new HistoryProgressTest.Node();node.txs.add(tx);
        Map<String,DexHistory.Spend> found=new HashMap<>();new DexHistory(node).findSpends(CreationEvidence.inputs(row),found::putAll);return found;
    }
    static void reconcile(Pending pending,Pending.Row row,Pending.Listener listener) throws Exception {
        HistoryProgressTest.Node node=new HistoryProgressTest.Node();node.txs.add(transaction(row));
        pending.reconcile(new DexHistory(node),Collections.emptyMap(),120,o->true,listener);
    }
    static DexTxn.Result callback() {return new DexTxn.Result(){public void onPosted(String id){} public void onFailed(String e){}};}
    @Test public void exactIncludedFundingAndOutputsResolveWithoutEverSeeingLiveOrder() throws Exception {
        Pending.Row row=receipt();PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();
        Pending pending=new Pending(memory);DexTxn.Result cb=pending.creationResult(row,callback());
        assertTrue(cb.onPrepared("create_123"));assertTrue(cb.beforePost()); // Process dies, no reply.
        Pending restarted=new Pending(memory);int[] confirmed={0};
        reconcile(restarted,restarted.rows().get(0),PendingRecoveryTest.listener(()->confirmed[0]++,()->{}));
        assertEquals(1,confirmed[0]);assertTrue(restarted.rows().isEmpty());
        cb.onPosted("0xaabb");assertTrue(restarted.rows().isEmpty()); // Late reply cannot resurrect.
    }
    @Test public void everyInputMustBelongToTheSameIncludedTransaction() throws Exception {
        Pending.Row row=receipt();Map<String,DexHistory.Spend> found=proof(row,transaction(row));
        assertNotNull(CreationEvidence.match(row,found));
        DexHistory.Spend second=found.remove("0xee");assertNull(CreationEvidence.match(row,found));found.put("0xee",second);
        second.inputCount=3;assertNull(CreationEvidence.match(row,found));second.inputCount=2;
        second.input.put("coinid","0xffff");assertNull(CreationEvidence.match(row,found));second.input.put("coinid","0xee");
        DexHistory.Spend other=new DexHistory.Spend("0xdead",1,second.outputs,"0xccdd",4);
        other.input=second.input;other.inputCount=2;found.put("0xee",other);assertNull(CreationEvidence.match(row,found));
        DexHistory.Spend mempool=new DexHistory.Spend("0xaabb",1,second.outputs,"0xccdd",-1);
        mempool.input=second.input;mempool.inputCount=2;found.put("0xee",mempool);assertNull(CreationEvidence.match(row,found));
    }
    @Test public void allPinnedStateAndExactOutputsMustMatch() throws Exception {
        Pending.Row row=receipt();JSONObject tx=transaction(row);JSONObject txn=tx.getJSONObject("body").getJSONObject("txn");
        JSONArray states=txn.getJSONArray("state");
        for(int i=0;i<9;i++) {
            JSONObject field=states.getJSONObject(i);String before=field.getString("data");
            field.put("data",before.startsWith("0x")?"0xdead":"999");assertNull("port "+i,CreationEvidence.match(row,proof(row,tx)));field.put("data",before);
        }
        states.getJSONObject(8).put("port",7);assertNull(CreationEvidence.match(row,proof(row,tx)));states.getJSONObject(8).put("port",8);
        JSONObject output=txn.getJSONArray("outputs").getJSONObject(0),change=txn.getJSONArray("outputs").getJSONObject(1);
        output.put("amount","99");assertNull(CreationEvidence.match(row,proof(row,tx)));output.put("amount","100");
        output.put("address","0xdead");assertNull(CreationEvidence.match(row,proof(row,tx)));output.put("address",DexContract.ADDR_V5);
        output.put("storestate",false);assertNull(CreationEvidence.match(row,proof(row,tx)));output.put("storestate",true);
        change.put("storestate",true);assertNull(CreationEvidence.match(row,proof(row,tx)));change.put("storestate",false);
        change.put("amount","24");assertNull(CreationEvidence.match(row,proof(row,tx)));change.put("amount","25");
        change.put("address","0xdead");assertNull(CreationEvidence.match(row,proof(row,tx)));change.put("address","0xbb");
        assertNotNull(CreationEvidence.match(row,proof(row,tx)));
        txn.getJSONArray("outputs").put(new JSONObject(change.toString()));assertNull(CreationEvidence.match(row,proof(row,tx)));
    }
    @Test public void buyWithExactTokenFundingUsesTokenAmountsAndAllowsNoChange() throws Exception {
        String[] state={"0xaa","0xbb","100","0x00","0xcc","0","0.01","1","0.01"};
        Pending.Row row=CreationEvidence.prepare(true,new BigDecimal("100"),new BigDecimal("0.01"),DexContract.USDT_ID,
                BigDecimal.ONE,"0xbb",state,Collections.singletonList(CreateFundingTest.coin(DexContract.USDT_ID,"1")),100);
        JSONObject tx=transaction(row);assertNotNull(CreationEvidence.match(row,proof(row,tx)));
        tx.getJSONObject("body").getJSONObject("txn").getJSONArray("outputs").getJSONObject(0).remove("tokenamount");
        assertNull(CreationEvidence.match(row,proof(row,tx)));
    }
    @Test public void copiedPublicOrderIdCannotResolveLegacyCreation() {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending pending=new Pending(memory);
        pending.add(PendingRecoveryTest.row("0xcc"));Order5 copied=Order5.from(TransactionHardeningTest.orderCoin());
        pending.resolve(Collections.singletonMap(copied.coinid,copied),120,PendingRecoveryTest.listener(()->fail("copied"),()->{}));
        assertEquals(1,pending.rows().size());assertTrue(pending.rows().get(0).status(120).contains("funding details were not saved"));
    }
    @Test public void failedDurablePrepareOrPostingTransitionPreventsNextStep() {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending pending=new Pending(memory);
        DexTxn.Result first=pending.creationResult(receipt(),callback());memory.fail=true;
        assertFalse(first.onPrepared("create_123"));assertEquals("[]",memory.data);
        memory.fail=false;DexTxn.Result second=pending.creationResult(receipt(),callback());assertTrue(second.onPrepared("create_456"));
        memory.fail=true;assertFalse(second.beforePost());assertEquals("PREPARED",new Pending(memory).rows().get(0).phase);
    }
    @Test public void callerQuoteRefusalIsPreservedThroughCreationReceiptWrapper() {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending pending=new Pending(memory);
        DexTxn.Result guarded=pending.creationResult(receipt(),new DexTxn.Result(){
            public boolean beforePost(){return false;}
            public void onPosted(String id){fail("refused quote was posted");}
            public void onFailed(String e){}
        });
        assertTrue(guarded.onPrepared("create_123"));assertFalse(guarded.beforePost());
        assertEquals("PREPARED",pending.rows().get(0).phase);
        guarded.onFailed("quote changed; not posted");
        assertEquals("NOT_SUBMITTED",new Pending(memory).rows().get(0).phase);
    }
    @Test public void prePostFailureAndUncertainPostAreDistinguishedAndRetained() {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending pending=new Pending(memory);
        DexTxn.Result first=pending.creationResult(receipt(),callback());assertTrue(first.onPrepared("create_123"));
        first.onFailed("input claimed");assertEquals("NOT_SUBMITTED",pending.rows().get(0).phase);
        Pending.Row next=receipt();next.orderId="0xabcdef";DexTxn.Result second=pending.creationResult(next,callback());
        assertTrue(second.onPrepared("create_456"));assertTrue(second.beforePost());second.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
        assertEquals("UNKNOWN",new Pending(memory).rows().get(1).phase);
    }
    @Test public void duplicateOrderIdAndCallerRefusalCannotAuthorizeAnotherSubmission() {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending pending=new Pending(memory);
        assertTrue(pending.creationResult(receipt(),callback()).onPrepared("create_123"));
        assertFalse(new Pending(memory).creationResult(receipt(),callback()).onPrepared("create_456"));assertEquals(1,pending.rows().size());
        Pending.Row other=receipt();other.orderId="0xabcdef";
        assertFalse(pending.creationResult(other,new DexTxn.Result(){public boolean onPrepared(String id){return false;}
            public void onPosted(String id){} public void onFailed(String e){}}).onPrepared("create_789"));
        assertEquals(1,pending.rows().size());
    }
    @Test public void corruptDuplicateFundingEvidenceNeverMatches() throws Exception {
        Pending.Row row=receipt();JSONObject tx=transaction(row);row.creation.put("inputs",new JSONArray().put("0xdd").put("0xDD"));
        assertTrue(CreationEvidence.inputs(row).isEmpty());assertNull(CreationEvidence.match(row,proof(row,tx)));
    }
    @Test public void archivedDisposableNodeJsonMatchesTheIndependentCreationRequestShape() throws Exception {
        // Offline format regression only. These are archived unsigned/post candidates, not fresh
        // inclusion proof. The fake history node supplies inclusion for this parser-only test.
        java.nio.file.Path fixtures=java.nio.file.Paths.get("../contract/reviews/0.4.4");
        for(String side:new String[]{"False","True"}) {
            JSONObject request=new JSONObject(new String(java.nio.file.Files.readAllBytes(fixtures.resolve("java-create-"+side+".json")), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject tx=new JSONObject(new String(java.nio.file.Files.readAllBytes(fixtures.resolve("java-create-posted-"+side+".json")), java.nio.charset.StandardCharsets.UTF_8));
            String[] state=new String[9];for(int i=0;i<9;i++) state[i]=request.getJSONArray("state").getString(i);
            Pending.Row row=CreationEvidence.prepare(side.equals("True"),new BigDecimal("100"),new BigDecimal("0.00575"),
                    request.getString("token"),new BigDecimal(request.getString("lock")),request.getString("payout"),state,
                    Collections.singletonList(request.getJSONObject("coin")),225);
            // The disposable chain used a test-token covenant. Adapt only that address to the
            // production constant; keep the node's actual coin/state/amount serialization.
            tx.getJSONObject("body").getJSONObject("txn").getJSONArray("outputs").getJSONObject(0).put("address",DexContract.ADDR_V5);
            assertNotNull(side,CreationEvidence.match(row,proof(row,tx)));
        }
    }

    @Test public void visibleStatusUsesPersistedEvidenceAndNeverAssumesMining() {
        Pending.Row row=receipt();row.phase="PREPARED";assertTrue(row.status(100).contains("intent saved"));
        row.phase="POSTING";assertTrue(row.status(100).contains("Submission requested"));
        row.phase="UNKNOWN";assertTrue(row.status(100).contains("outcome unknown"));
        row.phase="SUBMITTED";assertTrue(row.status(100).contains("Node accepted submission"));
        row.phase="NOT_SUBMITTED";assertTrue(row.status(100).contains("No transaction was sent"));
    }

}

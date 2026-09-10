package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class FillRecoveryTest {
    static class MemoryStore implements FillSettler.Store {
        final LinkedHashMap<String, FillSettler.Entry> rows = new LinkedHashMap<>();
        boolean failEnqueue;
        public void enqueue(Order5 order) {
            if (failEnqueue) throw new IllegalStateException("disk full");
            rows.putIfAbsent(order.coinid, new FillSettler.Entry(order.coinid, order.sourceJson()));
        }
        public List<FillSettler.Entry> batch(int limit) {
            List<FillSettler.Entry> batch = new ArrayList<>(rows.values());
            return new ArrayList<>(batch.subList(0, Math.min(limit, batch.size())));
        }
        public void remove(String id) { rows.remove(id); }
        public void defer(List<String> ids) {
            for (String id : ids) {
                FillSettler.Entry row = rows.remove(id);
                if (row != null) rows.put(id, row);
            }
        }
    }
    static class Outcome implements FillSettler.Outcome {
        final Set<String> recorded = new HashSet<>();
        boolean fail;
        int errors;
        public void record(String coin, Order5 order, BigDecimal size, BigDecimal price,
                           boolean buy, boolean partial, String txid, String evidence, String note) {
            if (fail) throw new IllegalStateException("disk full");
            recorded.add(coin);
        }
        public void cancelled(String id) { }
        public void recoveryError(String message) { errors++; }
    }
    private static Order5 order(String id) {
        return Order5.from(new TestJson().put("coinid", id).put("tokenid", "0x00")
                .put("amount", "300").put("created", 100).put("state", new TestJson()
                .put("0", "0xMAKER").put("1", "0xPAYOUT").put("2", "15.45")
                .put("3", DexContract.USDT_ID).put("4", "0xORDER").put("5", "1")
                .put("6", "999").put("7", "1").put("8", "1")));
    }
    private static JSONObject page(String id) {
        JSONArray txs = new JSONArray();
        if (id != null) txs.put(new TestJson().put("txpowid", "0xAABB").put("body", new TestJson()
                .put("txn", new TestJson().put("inputs", new JSONArray().put(new TestJson().put("coinid", id)))
                .put("outputs", new JSONArray().put(new TestJson().put("address", "0xPAYOUT")
                        .put("tokenid", DexContract.USDT_ID).put("amount", "0").put("tokenamount", "15.45"))))));
        return new TestJson().put("status", true).put("response", new TestJson().put("txpows", txs));
    }
    private static FillSettler settler(MemoryStore store, Outcome outcome, String match, long[] block) {
        return new FillSettler(new DexHistory((cmd, cb) -> cb.onResult(cmd.startsWith("txpow ")
                ? new TestJson().put("status", true).put("response", new TestJson().put("found", true).put("confirmations", 3))
                : page(match))), () -> block[0], outcome, store);
    }
    private static void enqueue(FillSettler settler, String id) {
        settler.onFill(id, order(id), new BigDecimal("999"), BigDecimal.ONE, false, true, 108);
    }

    @Test public void freshSettlerRestoresTheExactSourceAndSettlesWithoutNewBookEvents() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        enqueue(settler(store, result, null, new long[]{112}), "0xAA");
        Order5 restored = store.batch(1).get(0).order();
        assertEquals("15.45", restored.wantAmt.toPlainString());
        assertEquals("300", restored.locked.toPlainString());
        settler(store, result, "0xAA", new long[]{112}).onScanComplete();
        assertEquals(Collections.singleton("0xAA"), result.recorded);
        assertTrue(store.rows.isEmpty());
    }
    @Test public void retainsMoreThan512CandidatesAndBoundsEachPass() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        FillSettler settler = settler(store, result, null, new long[]{112});
        for (int i = 0; i < 600; i++) enqueue(settler, "coin" + i);
        settler.onScanComplete();
        assertEquals(600, store.rows.size());
        assertEquals("coin32", store.batch(1).get(0).coinid);
    }
    @Test public void unresolvedOlderCandidatesDoNotStarveAFoundLaterFill() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        FillSettler settler = settler(store, result, "coin32", new long[]{112});
        for (int i = 0; i < 33; i++) enqueue(settler, "coin" + i);
        settler.onScanComplete(); assertTrue(result.recorded.isEmpty());
        settler.onScanComplete(); // Alternate a discovery pass so unseen trades cannot starve.
        settler.onScanComplete(); assertTrue(result.recorded.contains("coin32"));
        assertEquals(32, store.rows.size());
    }
    @Test public void failedRecordingRetainsCandidateForRestartRetry() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome(); result.fail = true;
        FillSettler settler = settler(store, result, "0xAA", new long[]{112});
        enqueue(settler, "0xAA"); settler.onScanComplete();
        assertEquals(1, store.rows.size()); assertEquals(1, result.errors);
        result.fail = false;
        settler(store, result, "0xAA", new long[]{112}).onScanComplete();
        assertTrue(store.rows.isEmpty()); assertTrue(result.recorded.contains("0xAA"));
    }
    @Test public void duplicateEnqueuePreservesOriginalAndTwoHostsCanReplay() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        FillSettler one = settler(store, result, "0xAA", new long[]{112});
        FillSettler two = settler(store, result, "0xAA", new long[]{112});
        enqueue(one, "0xAA"); FillSettler.Entry original = store.batch(1).get(0);
        enqueue(two, "0xAA"); assertSame(original, store.batch(1).get(0));
        one.onScanComplete(); two.onScanComplete(); assertEquals(1, result.recorded.size());
    }
    @Test public void corruptRowIsPreservedAndDoesNotStopLaterRows() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        store.rows.put("bad", new FillSettler.Entry("bad", "{broken"));
        FillSettler settler = settler(store, result, "0xAA", new long[]{112});
        enqueue(settler, "0xAA"); settler.onScanComplete();
        assertEquals("{broken", store.rows.get("bad").json);
        assertTrue(result.recorded.contains("0xAA")); assertEquals(1, result.errors);
    }
    @Test public void noFreshOwnershipMeansNoRetirement() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome(); long[] block = {0};
        FillSettler settler = settler(store, result, "0xAA", block);
        enqueue(settler, "0xAA"); settler.onScanComplete();
        assertEquals(1, store.rows.size()); assertTrue(result.recorded.isEmpty());
        block[0] = 112; settler.onScanComplete(); assertTrue(store.rows.isEmpty());
    }
    @Test public void failedEnqueueIsNotAcknowledgedToBookDiff() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome(); store.failEnqueue = true;
        try { enqueue(settler(store, result, null, new long[]{112}), "0xAA"); fail(); }
        catch (IllegalStateException expected) { assertEquals("disk full", expected.getMessage()); }
        assertTrue(store.rows.isEmpty()); assertEquals(1, result.errors);
    }
    @Test public void includedButUnclassifiedOutputKeepsItsCandidate() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome();
        JSONObject malformedPage = page("0xAA");
        try {
            malformedPage.getJSONObject("response").getJSONArray("txpows").getJSONObject(0)
                    .getJSONObject("body").getJSONObject("txn").put("outputs", new JSONArray());
        } catch (Exception e) { throw new AssertionError(e); }
        FillSettler settler = new FillSettler(new DexHistory((cmd, cb) -> cb.onResult(cmd.startsWith("txpow ")
                ? new TestJson().put("status", true).put("response", new TestJson().put("found", true).put("confirmations", 3))
                : malformedPage)), () -> 112, result, store);
        enqueue(settler, "0xAA"); settler.onScanComplete();
        assertEquals(1, store.rows.size()); assertTrue(result.recorded.isEmpty());
    }
    @Test public void seededAndNormalScansEachTriggerExactlyOneRecoveryPass() {
        FillTape tape = new FillTape(null); int[] passes = {0};
        FillTape.Sink sink = new FillTape.Sink() {
            public void onFill(String coin, Order5 order, BigDecimal size, BigDecimal price,
                               boolean buy, boolean partial, long block) { }
            public void onScanComplete() { passes[0]++; }
        };
        Map<String, Order5> book = Collections.singletonMap("0xAA", order("0xAA"));
        tape.ingest(book, false, 112, sink); assertEquals(1, passes[0]);
        tape.ingest(book, false, 113, sink); assertEquals(2, passes[0]);
        tape.ingest(book, true, 114, sink); assertEquals(3, passes[0]);
    }
    @Test public void failedProgressNotificationCannotLeaveRecoveryCheckingForever() {
        MemoryStore store = new MemoryStore(); Outcome result = new Outcome() {
            @Override public void recoveryError(String message) { throw new IllegalStateException("closed UI"); }
        };
        DexHistory.ProgressStore failedProgress = new DexHistory.ProgressStore() {
            public int offset(boolean relevant, Collection<String> ids) { return 0; }
            public void checkpoint(boolean relevant, Collection<String> pending, Collection<String> found, int offset) {
                throw new IllegalStateException("disk full");
            }
        };
        DexHistory history = new DexHistory((cmd, cb) -> cb.onResult(cmd.startsWith("txpow ")
                ? new TestJson().put("status", true).put("response", new TestJson().put("found", true).put("confirmations", 3))
                : page("0xAA")), failedProgress);
        FillSettler settler = new FillSettler(history, () -> 112, result, store);
        enqueue(settler, "0xAA"); settler.onScanComplete();
        assertTrue(result.recorded.contains("0xAA")); assertTrue(store.rows.isEmpty());
        enqueue(settler, "0xBB"); settler.onScanComplete();
        assertEquals(1, store.rows.size()); // New unresolved candidate was visited, not dropped.
    }
}

package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The engine spends money: every action it takes is an on-chain transaction. These tests drive
 * it with a stubbed DexTxn so the execution rules can be proven without a node.
 *
 * The headline case is {@link #eachActionIsAttemptedExactlyOnce()}. DexTxn's validation failures
 * both invoke the callback AND return a null order id; a caller that reacts to both starts two
 * concurrent chains down the same action list, and a duplicated CREATE posts a second on-chain
 * order whose id is immediately overwritten — real funds committed to an order the ladder can
 * never find again.
 */
public class MakerEngineTest {

    /** Records what was asked of it and lets each test choose how the node "responds". */
    private static final class StubTxn extends DexTxn {
        final List<String> calls = new ArrayList<>();
        boolean failCreates = false;
        /** mimic DexTxn's real contract: validation failures call onFailed AND return null */
        boolean rejectSynchronously = false;
        int nextId = 1;

        StubTxn() { super(null, null); }

        @Override public String createOrder(boolean buy, BigDecimal minima, BigDecimal price,
                                            boolean gtc, BigDecimal minRem, Result cb) {
            calls.add("CREATE " + PriceMath.fmtPrice(price));
            if (rejectSynchronously) {
                cb.onFailed("rejected before posting");
                return null;
            }
            String id = "0xORDER" + (nextId++);
            if (failCreates) { cb.onFailed("post failed"); return id; }
            cb.onPosted("0xTX");
            return id;
        }

        @Override public void relock(Order5 o, BigDecimal newWant, Result cb) {
            calls.add("RELOCK " + o.coinid);
            cb.onPosted("0xTX");
        }

        @Override public void cancel(Order5 o, Result cb) {
            calls.add("CANCEL " + o.coinid);
            cb.onPosted("0xTX");
        }
    }

    private MakerConfig cfg;
    private StubTxn txn;
    private MakerEngine engine;

    @Before public void setUp() {
        cfg = new MakerConfig();
        txn = new StubTxn();
        engine = new MakerEngine(cfg, txn);
    }

    private static MakerLadder.Slot slot(String id, boolean sell, String price, String size) {
        List<MakerLadder.Level> ls = Arrays.asList(
                new MakerLadder.Level(new BigDecimal("0.20"), new BigDecimal(size)));
        MakerLadder.Config c = new MakerLadder.Config(ls, BigDecimal.ZERO,
                new BigDecimal("0.1"), !sell, sell);
        List<MakerLadder.Slot> out = MakerLadder.desired(new BigDecimal(price), c, BigDecimal.ONE);
        return out.get(0);
    }

    // ---------------- the critical one ----------------

    @Test public void eachActionIsAttemptedExactlyOnce() throws Exception {
        txn.rejectSynchronously = true;   // the double-signal case
        List<MakerLadder.Action> actions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actions.add(newCreate(slot("A" + (i + 1), true, "0.05", "100")));
        }
        invokeRun(actions);
        assertEquals("a synchronous rejection must not start a second chain", 3, txn.calls.size());
    }

    @Test public void aRejectedCreateIsNotRecordedAsALadderSlot() throws Exception {
        txn.rejectSynchronously = true;
        invokeRun(Arrays.asList(newCreate(slot("A1", true, "0.05", "100"))));
        assertTrue("a slot that never posted must not be remembered", cfg.slotOrderIds.isEmpty());
        assertNull(cfg.postedSizeFor("A1"));
    }

    @Test public void anAcceptedCreateRecordsBothIdAndPostedSize() throws Exception {
        MakerLadder.Slot s = slot("A1", true, "0.05", "250");
        invokeRun(Arrays.asList(newCreate(s)));
        assertEquals(1, cfg.slotOrderIds.size());
        assertEquals(0, new BigDecimal("250").compareTo(cfg.postedSizeFor(s.id)));
    }

    // ---------------- lastActedMid only on success ----------------

    @Test public void aCycleWhereNothingPostedDoesNotClaimToHaveActed() throws Exception {
        txn.rejectSynchronously = true;
        cfg.lastActedMid = null;
        invokeRun(Arrays.asList(newCreate(slot("A1", true, "0.05", "100"))));
        assertNull("a failed cycle must stay retryable", cfg.lastActedMid);
    }

    @Test public void aCycleThatPostedRecordsTheMidItActedOn() throws Exception {
        invokeRun(Arrays.asList(newCreate(slot("A1", true, "0.05", "100"))));
        assertEquals(0, new BigDecimal("0.05").compareTo(cfg.lastActedMid));
    }

    // ---------------- min remainder scales with rung size ----------------

    @Test public void minRemainderNeverExceedsTheRungItself() {
        for (String size : new String[]{"0.02", "1", "100", "10000"}) {
            MakerLadder.Slot s = slot("A1", true, "0.05", size);
            BigDecimal minRem = MakerEngine.minRemainderFor(s);
            assertTrue("min remainder must fit inside the rung (size " + size + ")",
                    minRem.compareTo(s.sizeMinima) < 0);
            assertTrue(minRem.signum() > 0);
        }
    }

    // ---------------- withdraw ----------------

    @Test public void withdrawCancelsEveryRungAndForgetsThem() {
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"));
        cfg.rememberSlot("B1", "0xORDER2", new BigDecimal("100"));
        List<Order5> live = Arrays.asList(
                order("0xC1", "0xORDER1", "0.051", "100"),
                order("0xC2", "0xORDER2", "0.049", "100"));
        engine.cancelAllLadder(live, 0, m -> {});
        assertEquals(2, txn.calls.size());
        assertTrue(cfg.slotOrderIds.isEmpty());
        assertTrue(cfg.slotSizes.isEmpty());
        assertFalse(engine.isWorking());
    }

    // ---------------- helpers ----------------

    private MakerLadder.Action newCreate(MakerLadder.Slot s) throws Exception {
        java.lang.reflect.Constructor<MakerLadder.Action> c =
                MakerLadder.Action.class.getDeclaredConstructor(MakerLadder.Kind.class,
                        MakerLadder.Slot.class, Order5.class, String.class);
        c.setAccessible(true);
        return c.newInstance(MakerLadder.Kind.CREATE, s, null, "test");
    }

    /** run() is private by design — the engine's public surface is onBook/cancelAllLadder. */
    @SuppressWarnings("unchecked")
    private void invokeRun(List<MakerLadder.Action> actions) throws Exception {
        java.lang.reflect.Method m = MakerEngine.class.getDeclaredMethod("run",
                List.class, int.class, BigDecimal.class, int.class, MakerEngine.Listener.class);
        m.setAccessible(true);
        m.invoke(engine, actions, 0, new BigDecimal("0.05"), 0, (MakerEngine.Listener) msg -> {});
    }

    private static Order5 order(String coinid, String orderId, String price, String minima) {
        try {
            BigDecimal locked = new BigDecimal(minima);
            org.json.JSONObject c = new org.json.JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked.toPlainString());
            c.put("tokenid", "0x00");
            c.put("created", 10);
            org.json.JSONObject st = new org.json.JSONObject();
            st.put("0", "0xMINE");
            st.put("1", "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE");
            st.put("2", locked.multiply(new BigDecimal(price)).toPlainString());
            st.put("3", DexContract.USDT_ID);
            st.put("4", orderId);
            st.put("5", "1");
            st.put("6", price);
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

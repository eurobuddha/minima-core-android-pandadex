package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                                            boolean gtc, BigDecimal minRem, String orderId, Result cb) {
            calls.add("CREATE " + PriceMath.fmtPrice(price));
            if (throwOnCall) throw new IllegalStateException("node exploded");
            if (rejectSynchronously) {
                cb.onFailed("rejected before posting");
                return null;
            }
            nextId++;
            // mimics the real contract: the id comes BACK even when the node rejects the send
            // asynchronously, which is exactly why the engine records only in onPosted
            if (failCreates) { cb.onFailed("post failed"); return orderId; }
            cb.onPosted("0xTX");
            return orderId;
        }

        @Override public void relock(Order5 o, BigDecimal newWant, Result cb) {
            calls.add("RELOCK " + o.coinid);
            cb.onPosted("0xTX");
        }

        /** When set, cancel() parks its callback so the chain stays open — letting a test
         *  observe the engine while it is genuinely working. */
        boolean deferCancels = false;
        boolean throwOnCall = false;
        Result parked;

        @Override public void cancel(Order5 o, Result cb) {
            calls.add("CANCEL " + o.coinid);
            if (deferCancels) { parked = cb; return; }
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
        // one-sided pegged config: only the side under test has a sized rung, so desired()
        // yields exactly one rung (A1 or B1)
        List<MakerLadder.Level> rungs = Arrays.asList(
                new MakerLadder.Level(BigDecimal.ZERO, new BigDecimal(size)));
        MakerLadder.Config c = new MakerLadder.Config(true, new BigDecimal("0.20"),
                sell ? rungs : new ArrayList<>(), sell ? new ArrayList<>() : rungs,
                BigDecimal.ZERO, new BigDecimal("0.1"));
        List<MakerLadder.Slot> out = MakerLadder.desired(new BigDecimal(price), c, BigDecimal.ONE);
        return out.get(0);
    }

    /** Give a side one sized rung per level, the way the auto-fill seeds it. */
    private static void seedRungs(List<MakerLadder.Level> side, int levels, String size) {
        side.clear();
        for (int i = 0; i < levels; i++) {
            side.add(new MakerLadder.Level(BigDecimal.ZERO, new BigDecimal(size)));
        }
    }

    private static Map<String, Order5> bookOf(Order5... orders) {
        Map<String, Order5> m = new HashMap<>();
        for (Order5 o : orders) m.put(o.coinid, o);
        return m;
    }

    private static final Set<String> MY_KEYS = new HashSet<>(Arrays.asList("0xMINE"));

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
        assertTrue("a slot that never posted must not be remembered", cfg.slots.isEmpty());
        assertNull(cfg.postedSizeFor("A1"));
    }

    @Test public void anAsyncSendFailureRecordsNothing() throws Exception {
        // THE 0.2.6 BUG: the slot was recorded as soon as createOrder returned, so a send the
        // node rejected later (unfunded — the live mxUSDT contention case) left a dead id in
        // the map that could never resolve and was re-created + overwritten every cycle.
        txn.failCreates = true;
        invokeRun(Arrays.asList(newCreate(slot("B1", false, "0.05", "100"))));
        assertTrue("only an ACCEPTED send may be remembered", cfg.slots.isEmpty());
    }

    @Test public void anAcceptedCreateRecordsIdSizeAndBlock() throws Exception {
        MakerLadder.Slot s = slot("A1", true, "0.05", "250");
        invokeRun(Arrays.asList(newCreate(s)));
        assertEquals(1, cfg.slots.size());
        assertEquals(0, new BigDecimal("250").compareTo(cfg.postedSizeFor(s.id)));
        assertEquals("the send block is recorded for the patience window",
                100, cfg.slots.get("A1").sentBlock);
    }

    @Test public void theListenerLearnsTheOrderIdThatWasRecorded() throws Exception {
        final String[] seen = {null};
        MakerLadder.Slot s = slot("A1", true, "0.05", "100");
        invokeRun(Arrays.asList(newCreate(s)), new MakerEngine.Listener() {
            @Override public void onMakerState(String m) {}
            @Override public void onCreateSent(MakerLadder.Slot slot, String orderId) { seen[0] = orderId; }
        });
        assertEquals("the UI must track the SAME id the engine recorded",
                cfg.orderIdFor("A1"), seen[0]);
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

    @Test public void minRemainderAlwaysLeavesRoomForAPartialFill() {
        // including a rung at exactly the minimum order size, which previously produced a
        // floor equal to the rung — posting fine but silently fill-or-nothing
        for (String size : new String[]{"0.01", "0.02", "1", "100", "10000"}) {
            MakerLadder.Slot s = slot("A1", true, "0.05", size);
            BigDecimal minRem = MakerEngine.minRemainderFor(s);
            assertTrue("a partial fill must always be possible (size " + size + ")",
                    minRem.compareTo(s.sizeMinima) < 0);
            assertTrue("and the floor must be positive (size " + size + ")", minRem.signum() > 0);
        }
    }

    // ---------------- withdraw ----------------

    @Test public void withdrawCancelsEveryRungAndForgetsThem() {
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);
        cfg.rememberSlot("B1", "0xORDER2", new BigDecimal("100"), 100);
        List<Order5> live = Arrays.asList(
                order("0xC1", "0xORDER1", "0.051", "100"),
                order("0xC2", "0xORDER2", "0.049", "100"));
        engine.cancelAllLadder(live, 0, 100, m -> {});
        assertEquals(2, txn.calls.size());
        assertTrue(cfg.slots.isEmpty());
        assertFalse(engine.isWorking());
    }

    @Test public void withdrawTombstonesSentSlotsAndCancelsThemWhenTheyConfirm() {
        // 0.2.6 only cancelled what it could SEE, so an order still mining at withdraw time
        // surfaced afterwards as an orphan no button could ever reach.
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);   // live
        cfg.rememberSlot("B1", "0xORDER2", new BigDecimal("100"), 100);   // still mining
        Order5 liveOne = order("0xC1", "0xORDER1", "0.051", "100");
        engine.withdrawAll(bookOf(liveOne), MY_KEYS, 100, m -> {});
        assertEquals("only the visible rung can be cancelled now",
                Arrays.asList("CANCEL 0xC1"), txn.calls);
        assertTrue("the unconfirmed rung is marked for death",
                cfg.cancelTombstones.containsKey("0xORDER2"));

        // ...and when it finally confirms, the sweep kills it
        txn.calls.clear();
        Order5 late = order("0xC2", "0xORDER2", "0.049", "100");
        engine.sweepTombstones(bookOf(late), MY_KEYS, 101, m -> {});
        assertEquals("the late order is cancelled the moment it appears",
                Arrays.asList("CANCEL 0xC2"), txn.calls);
    }

    @Test public void aCondemnedOrderIsCancelledTheInstantItSurfaces() {
        // no attempt recorded yet — don't make a doomed order wait out a patience window
        cfg.tombstone("0xORDER1", 100, 0);
        Order5 o = order("0xC1", "0xORDER1", "0.051", "100");
        engine.sweepTombstones(bookOf(o), MY_KEYS, 100, m -> {});
        assertEquals(1, txn.calls.size());
    }

    @Test public void aTombstonedOrderIsNotReCancelledEveryScan() {
        // each cancel is proof-of-work; re-send only after the patience window
        cfg.tombstone("0xORDER1", 100, 100);
        Order5 o = order("0xC1", "0xORDER1", "0.051", "100");
        engine.sweepTombstones(bookOf(o), MY_KEYS, 101, m -> {});
        assertTrue("still within patience — no second cancel", txn.calls.isEmpty());
        engine.sweepTombstones(bookOf(o), MY_KEYS, 100 + MakerEngine.PATIENCE_BLOCKS, m -> {});
        assertEquals(1, txn.calls.size());
    }

    @Test public void aTombstoneRetiresOnceItsCoinIsGoneForGood() {
        cfg.tombstone("0xORDER1", 100, 100);
        engine.sweepTombstones(new HashMap<>(), MY_KEYS, 101, m -> {});
        assertTrue("gone but still recent — keep watching", cfg.cancelTombstones.containsKey("0xORDER1"));
        engine.sweepTombstones(new HashMap<>(), MY_KEYS,
                100 + MakerEngine.TOMBSTONE_EXPIRE_BLOCKS, m -> {});
        assertTrue("finished business", cfg.cancelTombstones.isEmpty());
    }

    @Test public void retryingACancelNeverShortensTheProtectionWindow() {
        // the two clocks must stay independent: attempts pace the re-send, but expiry is
        // measured from CONDEMNATION, so a slow-confirming order is still chased
        cfg.tombstone("0xORDER1", 100, 0);
        Order5 o = order("0xC1", "0xORDER1", "0.051", "100");
        for (long b = 100; b < 100 + MakerEngine.TOMBSTONE_EXPIRE_BLOCKS; b += MakerEngine.PATIENCE_BLOCKS) {
            engine.sweepTombstones(bookOf(o), MY_KEYS, b, m -> {});
        }
        assertEquals("condemned at block 100, never re-dated", 100,
                cfg.cancelTombstones.get("0xORDER1").createdBlock);
    }

    @Test public void reCondemningAnOrderDoesNotRewindItsCancelPacing() {
        // withdrawAll re-condemns with "not tried yet"; that must not undo a cancel we just
        // sent, or the sweep re-posts proof-of-work against an order already mining its cancel
        cfg.tombstone("0xORDER1", 100, 100);
        cfg.tombstone("0xORDER1", 100, 0);
        assertEquals("the attempt clock only advances", 100,
                cfg.cancelTombstones.get("0xORDER1").lastAttemptBlock);
        Order5 o = order("0xC1", "0xORDER1", "0.051", "100");
        engine.sweepTombstones(bookOf(o), MY_KEYS, 101, m -> {});
        assertTrue("still paced — no duplicate cancel", txn.calls.isEmpty());
    }

    @Test public void cancellingEveryOrderByHandDoesNotRebuildTheLadder() {
        // The live sequence: 12 rungs published, then Cancel All on the Orders page. Once the
        // cancels mined, each slot's order was absent, the record aged out, and the engine
        // rebuilt the whole ladder — spending work to re-commit funds just freed. Cancel-all
        // now disarms and clears the slots, so there is nothing left to restore.
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 2, "100");
        cfg.rememberSlot("B1", "0xORDER1", new BigDecimal("100"), 100);
        cfg.rememberSlot("B2", "0xORDER2", new BigDecimal("100"), 100);

        // what cancelAll now does before posting its cancels
        cfg.armed = false;
        cfg.clearSlots();

        forceNextCycle();
        engine.onBook(new HashMap<>(), MY_KEYS, 100 + MakerEngine.PATIENCE_BLOCKS, m -> {});
        assertTrue("a stopped maker must never re-post the ladder", txn.calls.isEmpty());
        assertTrue(cfg.slots.isEmpty());
    }

    @Test public void aPublishedLadderStillRebuildsARungThatVanishesOnItsOwn() {
        // the other side of that coin: while PUBLISHED, a rung that disappears (taken, or
        // cancelled behind the maker's back) is still the maker's job to restore
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 1, "100");
        cfg.rememberSlot("B1", "0xGONE", new BigDecimal("100"), 100);

        forceNextCycle();
        engine.onBook(new HashMap<>(), MY_KEYS, 100 + MakerEngine.PATIENCE_BLOCKS, m -> {});
        assertEquals("a published ladder heals itself", 1, txn.calls.size());
    }

    @Test public void theStaleFeedWithdrawAlsoChasesUnconfirmedRungs() {
        // the automatic retreat runs unattended — cancelling only what it can SEE and then
        // clearing the slot map would orphan whatever was still mining
        MarketPrice.testSnapshot(0.05, 1);      // ancient timestamp => mustWithdraw
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 2, "100");
        cfg.rememberSlot("B1", "0xORDER1", new BigDecimal("100"), 100);   // live
        cfg.rememberSlot("B2", "0xORDER2", new BigDecimal("100"), 100);   // still mining
        Order5 live = order("0xC1", "0xORDER1", "0.049", "100");

        engine.onBook(bookOf(live), MY_KEYS, 100, m -> {});
        assertEquals("the visible rung is cancelled", 1, txn.calls.size());
        assertTrue("and the in-flight one is condemned, not forgotten",
                cfg.cancelTombstones.containsKey("0xORDER2"));
    }

    @Test public void anIdleEngineRunsQueuedWorkImmediately() {
        final boolean[] ran = {false};
        engine.runWhenIdle(() -> ran[0] = true);
        assertTrue(ran[0]);
    }

    @Test public void aWithdrawRequestedMidCycleIsNotLost() {
        // Withdraw disarms the maker, which stops onBook running — so if the request is merely
        // dropped while a chain is in flight, the ladder stays on the book forever while the
        // user has been told it is coming off.
        txn.deferCancels = true;
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);
        List<Order5> live = Arrays.asList(order("0xC1", "0xORDER1", "0.051", "100"));
        engine.cancelAllLadder(live, 0, 100, m -> {});
        assertTrue("the chain is open, so the engine is working", engine.isWorking());

        final boolean[] ran = {false};
        engine.runWhenIdle(() -> ran[0] = true);
        assertFalse("must not run while a chain is still in flight", ran[0]);

        txn.parked.onPosted("0xTX");        // the in-flight cancel lands
        assertTrue("queued work must run the moment the chain finishes", ran[0]);
        assertFalse(engine.isWorking());
    }

    @Test public void aThrowingTransactionLayerDoesNotStrandTheEngine() throws Exception {
        // `working` stuck true would mean onBook refuses to run forever — the maker dead with
        // a ladder still live on the book
        txn.throwOnCall = true;
        List<MakerLadder.Action> actions = new ArrayList<>();
        for (int i = 0; i < 2; i++) actions.add(newCreate(slot("A" + (i + 1), true, "0.05", "100")));
        invokeRun(actions);
        assertEquals("the chain must keep moving", 2, txn.calls.size());
        assertFalse("the engine must be usable afterwards", engine.isWorking());
    }

    // ---------------- config edits while armed ----------------

    @Test public void aDegeneratePeggedConfigNeverTearsTheLadderDown() {
        // commit() runs on field blur, so a cleared step field reaches the engine as zero.
        // That must read as MISCONFIGURED (do nothing), never as "desired ladder is empty"
        // (cancel every live rung at proof-of-work cost).
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = BigDecimal.ZERO;              // the cleared field
        seedRungs(cfg.asks, 1, "100");
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);
        Map<String, Order5> book = bookOf(order("0xC1", "0xORDER1", "0.051", "100"));

        engine.onBook(book, MY_KEYS, 100, m -> {});
        assertTrue("a blank step field must not cancel the ladder", txn.calls.isEmpty());

        cfg.stepPct = new BigDecimal("0.20");
        cfg.asks.clear();                           // every rung amount cleared mid-edit
        cfg.bids.clear();
        engine.onBook(book, MY_KEYS, 100, m -> {});
        assertTrue("blank sizes must not cancel the ladder either", txn.calls.isEmpty());
    }

    // ---------------- slot lifecycle: the 0.2.6 duplicate/orphan bug ----------------

    @Test public void anUnconfirmedCreateIsNotDuplicatedOnTheNextCycle() {
        // THE headline bug: an accepted order takes a block+ to appear in the confirmed book.
        // 0.2.6 read that as "level missing", posted a SECOND order and overwrote the record —
        // orphaning real funds. Within the patience window the slot must simply wait.
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 1, "100");

        engine.onBook(new HashMap<>(), MY_KEYS, 100, m -> {});
        assertEquals("cycle 1 posts the rung", 1, txn.calls.size());
        String firstId = cfg.orderIdFor("B1");
        assertNotNull(firstId);

        forceNextCycle();
        engine.onBook(new HashMap<>(), MY_KEYS, 101, m -> {});   // still mining, book empty
        assertEquals("cycle 2 must NOT post it again", 1, txn.calls.size());
        assertEquals("and must not overwrite the record", firstId, cfg.orderIdFor("B1"));
    }

    @Test public void aSlotThatNeverSurfacesIsRetriedAfterThePatienceWindow() {
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 1, "100");
        cfg.rememberSlot("B1", "0xLOST", new BigDecimal("100"), 100);

        forceNextCycle();
        engine.onBook(new HashMap<>(), MY_KEYS, 100 + MakerEngine.PATIENCE_BLOCKS, m -> {});
        assertEquals("patience expired — the rung is re-created", 1, txn.calls.size());
        assertNotEquals("with a fresh id, the dead one dropped", "0xLOST", cfg.orderIdFor("B1"));
    }

    @Test public void theRepriceGateOnlyGuardsACompleteLadder() {
        // 0.2.6 gated on "any rung live", so a 1-of-4 ladder froze until the market moved a
        // whole threshold — the user's "only 2 of 10 posted, then nothing" report.
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        cfg.repricePct = new BigDecimal("5");        // huge: nothing would ever reprice
        seedRungs(cfg.bids, 2, "100");
        cfg.lastActedMid = new BigDecimal("0.05");   // "already acted at this exact mid"
        cfg.rememberSlot("B1", "0xORDER1", new BigDecimal("100"), 100);

        MakerLadder.Slot b1 = slot("B1", false, "0.05", "100");
        Order5 live = order("0xC1", "0xORDER1", b1.price.toPlainString(), "100");
        forceNextCycle();
        engine.onBook(bookOf(live), MY_KEYS, 100, m -> {});
        assertEquals("the missing B2 must still be posted", 1, txn.calls.size());
        assertNotNull(cfg.orderIdFor("B2"));

        // now the ladder IS complete — the gate applies and the cycle costs nothing
        txn.calls.clear();
        Order5 live2 = order("0xC2", cfg.orderIdFor("B2"),
                slot("B1", false, "0.05", "100").price.toPlainString(), "100");
        forceNextCycle();
        engine.onBook(bookOf(live, live2), MY_KEYS, 100, m -> {});
        assertTrue("a complete ladder at an unmoved mid does nothing", txn.calls.isEmpty());
    }

    @Test public void oneCreatePerSidePerCycleAvoidsFundingContention() {
        // two same-side sends in one cycle fight over the same wallet coins: the second fails
        // unfunded until the first's change confirms (observed live with mxUSDT bids)
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 3, "100");
        seedRungs(cfg.asks, 3, "100");

        engine.onBook(new HashMap<>(), MY_KEYS, 100, m -> {});
        assertEquals("exactly one bid and one ask", 2, txn.calls.size());
        assertNotNull(cfg.orderIdFor("B1"));
        assertNotNull(cfg.orderIdFor("A1"));
        assertNull(cfg.orderIdFor("B2"));
    }

    @Test public void aLegacySlotRecordGetsAFreshPatienceWindow() {
        // upgrading mid-flight: a record with no send block must neither duplicate (instant
        // re-create) nor orphan (never expire)
        MarketPrice.testSnapshot(0.05, System.currentTimeMillis());
        cfg.armed = true;
        cfg.pegged = true;
        cfg.stepPct = new BigDecimal("0.20");
        seedRungs(cfg.bids, 1, "100");
        cfg.slots.put("B1", new MakerConfig.SlotRec("0xLEGACY", new BigDecimal("100"), 0, 0));

        engine.onBook(new HashMap<>(), MY_KEYS, 500, m -> {});
        assertTrue("no immediate duplicate", txn.calls.isEmpty());
        assertEquals("stamped with the current block", 500, cfg.slots.get("B1").sentBlock);
    }

    @Test public void anUnpeggedPriceEditIsHonouredExactly() {
        // manual rungs are "quoted exactly as typed" — an edit below the peg's reprice
        // threshold (0.2% here, threshold 0.25%) must still relock the live order
        cfg.armed = true;
        cfg.pegged = false;
        cfg.repricePct = new BigDecimal("0.25");
        cfg.asks.add(new MakerLadder.Level(new BigDecimal("0.0501"), new BigDecimal("100")));
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);
        Map<String, Order5> book = bookOf(order("0xC1", "0xORDER1", "0.0500", "100"));

        engine.onBook(book, MY_KEYS, 100, m -> {});
        assertEquals(Arrays.asList("RELOCK 0xC1"), txn.calls);
    }

    @Test public void aRelockInFlightIsNotRelockedAgain() {
        // the old coin stays visible at its old price until the relock mines — without a
        // settling window every cycle would re-relock it and burn proof-of-work forever
        cfg.armed = true;
        cfg.pegged = false;
        cfg.asks.add(new MakerLadder.Level(new BigDecimal("0.0501"), new BigDecimal("100")));
        cfg.rememberSlot("A1", "0xORDER1", new BigDecimal("100"), 100);
        Map<String, Order5> book = bookOf(order("0xC1", "0xORDER1", "0.0500", "100"));

        engine.onBook(book, MY_KEYS, 100, m -> {});
        assertEquals(1, txn.calls.size());
        forceNextCycle();
        engine.onBook(book, MY_KEYS, 101, m -> {});
        assertEquals("still settling — no second relock", 1, txn.calls.size());
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
    private void invokeRun(List<MakerLadder.Action> actions) throws Exception {
        invokeRun(actions, msg -> {});
    }

    private void invokeRun(List<MakerLadder.Action> actions, MakerEngine.Listener l) throws Exception {
        java.lang.reflect.Method m = MakerEngine.class.getDeclaredMethod("run",
                List.class, int.class, BigDecimal.class, int.class, long.class,
                MakerEngine.Listener.class);
        m.setAccessible(true);
        m.invoke(engine, actions, 0, new BigDecimal("0.05"), 0, 100L, l);
    }

    /** onBook refuses to run twice inside MIN_CYCLE_MS — rewind its clock so a test can
     *  observe the NEXT cycle without sleeping a minute. */
    private void forceNextCycle() {
        try {
            java.lang.reflect.Field f = MakerEngine.class.getDeclaredField("lastCycleMs");
            f.setAccessible(true);
            f.setLong(engine, 0L);
        } catch (Exception e) { throw new RuntimeException(e); }
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

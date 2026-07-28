package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Every action the market maker takes costs a proof-of-work transaction, so the arithmetic and
 * the diffing are proven here, away from the plumbing that spends the money.
 *
 * The ladder model is AtomiX's: pegged generation from (step % · levels · independent ask/bid
 * sizes · skew) around a live mid, or explicit price+amount rungs quoted exactly as typed.
 */
public class MakerLadderTest {

    private static final BigDecimal MID = new BigDecimal("0.050000");

    /** A pegged config — AtomiX's quick-generate seed parameters. */
    private static MakerLadder.Config pegged(int levels, String step, String askSize,
                                             String bidSize, String skew, String reprice) {
        return new MakerLadder.Config(true, new BigDecimal(step), levels,
                new BigDecimal(askSize), new BigDecimal(bidSize),
                new ArrayList<>(), new ArrayList<>(),
                new BigDecimal(skew), new BigDecimal(reprice));
    }

    /** A manual config — explicit rungs, quoted as typed. */
    private static MakerLadder.Config manual(List<MakerLadder.Level> asks,
                                             List<MakerLadder.Level> bids) {
        return new MakerLadder.Config(false, BigDecimal.ZERO, 1,
                BigDecimal.ZERO, BigDecimal.ZERO, asks, bids,
                BigDecimal.ZERO, new BigDecimal("0.1"));
    }

    private static MakerLadder.Level lvl(String price, String size) {
        return new MakerLadder.Level(new BigDecimal(price), new BigDecimal(size));
    }

    private static MakerLadder.Slot find(List<MakerLadder.Slot> slots, String id) {
        for (MakerLadder.Slot s : slots) if (s.id.equals(id)) return s;
        return null;
    }

    // ---------------- pegged generation (AtomiX fillFromPeg arithmetic) ----------------

    @Test public void bidsSitBelowAndAsksAboveTheMid() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID,
                pegged(3, "0.20", "100", "100", "0", "0.1"), BigDecimal.ONE);
        assertEquals(6, slots.size());
        assertTrue(find(slots, "B1").price.compareTo(MID) < 0);
        assertTrue(find(slots, "A1").price.compareTo(MID) > 0);
        // further rungs are further out
        assertTrue(find(slots, "B2").price.compareTo(find(slots, "B1").price) < 0);
        assertTrue(find(slots, "A2").price.compareTo(find(slots, "A1").price) > 0);
    }

    @Test public void stepOffsetsAreExact() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID,
                pegged(1, "0.20", "100", "100", "0", "0.1"), BigDecimal.ONE);
        // 0.20% either side of 0.05 = 0.0499 / 0.0501
        assertEquals("0.049900", PriceMath.fmtPrice(find(slots, "B1").price));
        assertEquals("0.050100", PriceMath.fmtPrice(find(slots, "A1").price));
    }

    @Test public void eachSideUsesItsOwnSize() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID,
                pegged(2, "0.20", "150", "300", "0", "0.1"), BigDecimal.ONE);
        assertEquals(0, new BigDecimal("150").compareTo(find(slots, "A1").sizeMinima));
        assertEquals(0, new BigDecimal("300").compareTo(find(slots, "B2").sizeMinima));
    }

    @Test public void aZeroSizedSideIsNotQuoted() {
        // AtomiX: a blank/zero side is NOT seeded or published — a one-sided market
        List<MakerLadder.Slot> askOnly = MakerLadder.desired(MID,
                pegged(3, "0.20", "100", "0", "0", "0.1"), BigDecimal.ONE);
        assertEquals(3, askOnly.size());
        for (MakerLadder.Slot s : askOnly) assertTrue("only asks expected", s.sell);
        assertNull(find(askOnly, "B1"));

        List<MakerLadder.Slot> bidOnly = MakerLadder.desired(MID,
                pegged(3, "0.20", "0", "100", "0", "0.1"), BigDecimal.ONE);
        assertEquals(3, bidOnly.size());
        for (MakerLadder.Slot s : bidOnly) assertFalse("only bids expected", s.sell);
    }

    @Test public void bothSidesOffMeansNothingQuoted() {
        assertTrue(MakerLadder.desired(MID,
                pegged(3, "0.20", "0", "0", "0", "0.1"), BigDecimal.ONE).isEmpty());
    }

    @Test public void skewShiftsBothSidesTogether() {
        List<MakerLadder.Slot> flat = MakerLadder.desired(MID,
                pegged(1, "0.20", "100", "100", "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Slot> up = MakerLadder.desired(MID,
                pegged(1, "0.20", "100", "100", "1.0", "0.1"), BigDecimal.ONE);
        assertTrue("a positive skew lifts the bid", find(up, "B1").price.compareTo(find(flat, "B1").price) > 0);
        assertTrue("and lifts the ask too", find(up, "A1").price.compareTo(find(flat, "A1").price) > 0);
    }

    @Test public void wideningPushesBothSidesAwayFromTheMid() {
        List<MakerLadder.Slot> tight = MakerLadder.desired(MID,
                pegged(1, "0.20", "100", "100", "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Slot> wide = MakerLadder.desired(MID,
                pegged(1, "0.20", "100", "100", "0", "0.1"), new BigDecimal(3));
        assertTrue("bid quotes lower", find(wide, "B1").price.compareTo(find(tight, "B1").price) < 0);
        assertTrue("ask quotes higher", find(wide, "A1").price.compareTo(find(tight, "A1").price) > 0);
    }

    @Test public void levelsAreCappedAtSix() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID,
                pegged(12, "0.10", "50", "50", "0", "0.1"), BigDecimal.ONE);
        assertEquals(MakerLadder.MAX_LEVELS * 2, slots.size());
    }

    @Test public void aPeggedLadderNeedsAMidAndAStep() {
        assertTrue("no usable mid → quote nothing", MakerLadder.desired(BigDecimal.ZERO,
                pegged(3, "0.20", "100", "100", "0", "0.1"), BigDecimal.ONE).isEmpty());
        assertTrue("no step → quote nothing", MakerLadder.desired(MID,
                pegged(3, "0", "100", "100", "0", "0.1"), BigDecimal.ONE).isEmpty());
    }

    // ---------------- manual rungs (quoted exactly as typed) ----------------

    @Test public void manualRungsQuoteExactlyAsTyped() {
        MakerLadder.Config c = manual(
                Arrays.asList(lvl("0.052", "100"), lvl("0.055", "200")),
                Arrays.asList(lvl("0.048", "150")));
        // no mid needed: manual prices do not depend on the feed
        List<MakerLadder.Slot> slots = MakerLadder.desired(null, c, BigDecimal.ONE);
        assertEquals(3, slots.size());
        assertEquals(0, new BigDecimal("0.052").compareTo(find(slots, "A1").price));
        assertEquals(0, new BigDecimal("0.055").compareTo(find(slots, "A2").price));
        assertEquals(0, new BigDecimal("0.048").compareTo(find(slots, "B1").price));
        assertEquals(0, new BigDecimal("150").compareTo(find(slots, "B1").sizeMinima));
    }

    @Test public void manualRungsAreSanitizedBestFirst() {
        // unsorted input with a blank row: A1 must still be the BEST (lowest) ask, invalid dropped
        MakerLadder.Config c = manual(
                new ArrayList<>(Arrays.asList(lvl("0.060", "100"), lvl("0", "50"), lvl("0.052", "100"))),
                new ArrayList<>(Arrays.asList(lvl("0.045", "100"), lvl("0.048", "100"))));
        List<MakerLadder.Slot> slots = MakerLadder.desired(null, c, BigDecimal.ONE);
        assertEquals(4, slots.size());
        assertEquals(0, new BigDecimal("0.052").compareTo(find(slots, "A1").price));
        assertEquals(0, new BigDecimal("0.060").compareTo(find(slots, "A2").price));
        assertEquals("best (highest) bid first", 0,
                new BigDecimal("0.048").compareTo(find(slots, "B1").price));
    }

    @Test public void manualLaddersIgnoreWideningAndSkew() {
        // fixed prices must never move — widening/skew are peg concepts
        MakerLadder.Config c = new MakerLadder.Config(false, BigDecimal.ZERO, 1,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Arrays.asList(lvl("0.052", "100")), new ArrayList<>(),
                new BigDecimal("5"), new BigDecimal("0.1"));
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID, c, new BigDecimal(6));
        assertEquals(0, new BigDecimal("0.052").compareTo(find(slots, "A1").price));
    }

    @Test public void crossedMarketIsDetected() {
        assertTrue(MakerLadder.crossed(
                Arrays.asList(lvl("0.050", "100")), Arrays.asList(lvl("0.051", "100"))));
        assertFalse(MakerLadder.crossed(
                Arrays.asList(lvl("0.051", "100")), Arrays.asList(lvl("0.050", "100"))));
        assertFalse("one-sided can't cross", MakerLadder.crossed(
                Arrays.asList(lvl("0.050", "100")), new ArrayList<>()));
    }

    // ---------------- reconciliation ----------------

    private static Order5 order(String coinid, String price, String minima) {
        try {
            BigDecimal locked = new BigDecimal(minima);
            BigDecimal want = locked.multiply(new BigDecimal(price));
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked.toPlainString());
            c.put("tokenid", "0x00");
            c.put("created", 10);
            JSONObject st = new JSONObject();
            st.put("0", "0xMINE");
            st.put("1", "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE");
            st.put("2", want.toPlainString());
            st.put("3", DexContract.USDT_ID);
            st.put("4", "0xORD");
            st.put("5", "1");
            st.put("6", price);
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static List<MakerLadder.Slot> want(int levels) {
        return MakerLadder.desired(MID, pegged(levels, "0.20", "100", "100", "0", "0.1"),
                BigDecimal.ONE);
    }

    @Test public void missingRungsAreCreated() {
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want(2), new HashMap<>(),
                new BigDecimal("0.1"), new HashSet<>(), 0);
        assertEquals(4, acts.size());
        for (MakerLadder.Action a : acts) assertEquals(MakerLadder.Kind.CREATE, a.kind);
    }

    @Test public void aMovedPriceRelocksRatherThanCancelAndRepost() {
        // the whole point of the V5 owner re-lock: repricing must never drop the level
        List<MakerLadder.Slot> want = want(1);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", "0.060000", "100"));   // far from the desired 0.0501
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), new HashSet<>(), 0);
        boolean sawRelock = false;
        for (MakerLadder.Action a : acts) {
            if (a.kind == MakerLadder.Kind.RELOCK) { sawRelock = true; assertEquals("0xC1", a.order.coinid); }
            assertFalse("a reprice must never cancel the level", a.kind == MakerLadder.Kind.CANCEL);
        }
        assertTrue(sawRelock);
    }

    @Test public void nothingHappensBelowTheRepriceThreshold() {
        List<MakerLadder.Slot> want = want(1);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", find(want, "A1").price.toPlainString(), "100"));
        live.put("B1", order("0xC2", find(want, "B1").price.toPlainString(), "100"));
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.5"), new HashSet<>(), 0);
        assertTrue("an unchanged ladder must cost nothing", acts.isEmpty());
    }

    @Test public void partiallyFilledRungsAreLeftWorking() {
        List<MakerLadder.Slot> want = want(1);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", "0.070000", "100"));   // miles away, would normally relock
        HashSet<String> partial = new HashSet<>();
        partial.add("0xC1");
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), partial, 0);
        for (MakerLadder.Action a : acts) {
            assertFalse("a working remainder must not be touched",
                    a.order != null && "0xC1".equals(a.order.coinid));
        }
    }

    @Test public void removedRungsAreCancelled() {
        List<MakerLadder.Slot> want = want(1);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", find(want, "A1").price.toPlainString(), "100"));
        live.put("A2", order("0xC2", "0.052000", "100"));   // no longer in the ladder
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), new HashSet<>(), 0);
        boolean cancelledA2 = false;
        for (MakerLadder.Action a : acts) {
            if (a.kind == MakerLadder.Kind.CANCEL && "0xC2".equals(a.order.coinid)) cancelledA2 = true;
        }
        assertTrue(cancelledA2);
    }

    @Test public void actionsPerCycleAreBounded() {
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want(6), new HashMap<>(),
                new BigDecimal("0.1"), new HashSet<>(), 4);
        assertEquals("proof-of-work per action means cycles must be capped", 4, acts.size());
    }

    @Test public void mispricedRungsAreFixedBeforeTidyingUp() {
        // With a tight action budget, cancels must not starve creates/relocks — that would tear
        // the ladder down without rebuilding it, leaving the maker thin for minutes.
        List<MakerLadder.Slot> want = want(2);
        Map<String, Order5> live = new HashMap<>();
        // four rungs we no longer want
        for (int i = 3; i <= 6; i++) live.put("A" + i, order("0xOld" + i, "0.09", "100"));
        // and one that is badly mispriced
        live.put("A1", order("0xC1", "0.070000", "100"));
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), new HashSet<>(), 2);
        assertEquals(2, acts.size());
        assertEquals("the mispriced rung is the live risk — fix it first",
                MakerLadder.Kind.RELOCK, acts.get(0).kind);
        for (MakerLadder.Action a : acts) {
            assertFalse("cancels must not consume the whole budget",
                    a.kind == MakerLadder.Kind.CANCEL);
        }
    }

    // ---------------- exact mode (manual ladder — threshold ≤ 0) ----------------

    @Test public void exactModeHonoursAPriceEditBelowAnyPegThreshold() {
        // "quoted exactly as typed": a 0.2% nudge must relock even though the default 0.25%
        // peg threshold would have swallowed it silently
        MakerLadder.Config c = manual(Arrays.asList(lvl("0.0501", "100")), new ArrayList<>());
        List<MakerLadder.Slot> want = MakerLadder.desired(null, c, BigDecimal.ONE);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", "0.0500", "100"));
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                BigDecimal.ZERO, new HashSet<>(), 0);
        assertEquals(1, acts.size());
        assertEquals(MakerLadder.Kind.RELOCK, acts.get(0).kind);
    }

    @Test public void exactModeConvergesDespiteAmountRoundingNoise() {
        // A posted order's reconstructed price carries amount-rounding below display
        // precision. Exact mode must compare at DISPLAY_DP, or every cycle relocks forever —
        // an infinite proof-of-work loop.
        MakerLadder.Config c = manual(Arrays.asList(lvl("0.052", "100")), new ArrayList<>());
        List<MakerLadder.Slot> want = MakerLadder.desired(null, c, BigDecimal.ONE);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", "0.05200000049", "100"));   // sub-display noise only
        assertTrue("rounding noise must not trigger a relock",
                MakerLadder.reconcile(want, live, BigDecimal.ZERO, new HashSet<>(), 0).isEmpty());
    }

    // ---------------- size changes ----------------

    @Test public void aSizeChangeCancelsAndRepostsTheRungLast() {
        // a re-lock cannot change the locked amount, so a deliberate size edit needs a
        // repost — and it is the least urgent work, after relocks/creates/cancels
        List<MakerLadder.Slot> want = want(1);   // both sides, size 100
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", find(want, "A1").price.toPlainString(), "250"));
        Map<String, BigDecimal> posted = new HashMap<>();
        posted.put("A1", new BigDecimal("250"));   // we posted 250, the user now wants 100
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), new HashSet<>(), posted, 0);
        assertEquals(3, acts.size());
        assertEquals("the missing B1 comes first", MakerLadder.Kind.CREATE, acts.get(0).kind);
        assertEquals(MakerLadder.Kind.CANCEL, acts.get(1).kind);
        assertEquals("0xC1", acts.get(1).order.coinid);
        assertEquals(MakerLadder.Kind.CREATE, acts.get(2).kind);
        assertEquals("A1", acts.get(2).slot.id);
        assertEquals(0, new BigDecimal("100").compareTo(acts.get(2).slot.sizeMinima));
    }

    @Test public void aPartiallyFilledRungIsNeverResized() {
        // the shrunken remainder is a working position, not a config edit to repair
        List<MakerLadder.Slot> want = want(1);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", find(want, "A1").price.toPlainString(), "40"));
        Map<String, BigDecimal> posted = new HashMap<>();
        posted.put("A1", new BigDecimal("250"));
        HashSet<String> partial = new HashSet<>();
        partial.add("0xC1");
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.1"), partial, posted, 0);
        for (MakerLadder.Action a : acts) {
            assertFalse("a working remainder must not be cancelled for a size change",
                    a.order != null && "0xC1".equals(a.order.coinid));
        }
    }

    @Test public void repricingWaitsForTheMidToActuallyMove() {
        assertFalse(MakerLadder.worthRepricing(new BigDecimal("0.05"),
                new BigDecimal("0.050004"), new BigDecimal("0.1")));
        assertTrue(MakerLadder.worthRepricing(new BigDecimal("0.05"),
                new BigDecimal("0.0505"), new BigDecimal("0.1")));
        assertTrue("no previous mid means act", MakerLadder.worthRepricing(null,
                new BigDecimal("0.05"), new BigDecimal("0.1")));
    }
}

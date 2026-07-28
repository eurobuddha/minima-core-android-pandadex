package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Every action the market maker takes costs a proof-of-work transaction, so the arithmetic and
 * the diffing are proven here, away from the plumbing that spends the money.
 */
public class MakerLadderTest {

    private static final BigDecimal MID = new BigDecimal("0.050000");

    private static MakerLadder.Config cfg(int levels, String skew, String reprice) {
        List<MakerLadder.Level> ls = new ArrayList<>();
        for (int i = 1; i <= levels; i++) {
            ls.add(new MakerLadder.Level(new BigDecimal("0.20").multiply(new BigDecimal(i)),
                    new BigDecimal(100 * i)));
        }
        return new MakerLadder.Config(ls, new BigDecimal(skew), new BigDecimal(reprice), true, true);
    }

    private static MakerLadder.Slot find(List<MakerLadder.Slot> slots, String id) {
        for (MakerLadder.Slot s : slots) if (s.id.equals(id)) return s;
        return null;
    }

    @Test public void bidsSitBelowAndAsksAboveTheMid() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID, cfg(3, "0", "0.1"), BigDecimal.ONE);
        assertEquals(6, slots.size());
        assertTrue(find(slots, "B1").price.compareTo(MID) < 0);
        assertTrue(find(slots, "A1").price.compareTo(MID) > 0);
        // further rungs are further out
        assertTrue(find(slots, "B2").price.compareTo(find(slots, "B1").price) < 0);
        assertTrue(find(slots, "A2").price.compareTo(find(slots, "A1").price) > 0);
    }

    @Test public void offsetsAreExact() {
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
        // 0.20% either side of 0.05 = 0.0499 / 0.0501
        assertEquals("0.049900", PriceMath.fmtPrice(find(slots, "B1").price));
        assertEquals("0.050100", PriceMath.fmtPrice(find(slots, "A1").price));
    }

    @Test public void skewShiftsBothSidesTogether() {
        List<MakerLadder.Slot> flat = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Slot> up = MakerLadder.desired(MID, cfg(1, "1.0", "0.1"), BigDecimal.ONE);
        assertTrue("a positive skew lifts the bid", find(up, "B1").price.compareTo(find(flat, "B1").price) > 0);
        assertTrue("and lifts the ask too", find(up, "A1").price.compareTo(find(flat, "A1").price) > 0);
    }

    @Test public void wideningPushesBothSidesAwayFromTheMid() {
        List<MakerLadder.Slot> tight = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Slot> wide = MakerLadder.desired(MID, cfg(1, "0", "0.1"), new BigDecimal(3));
        assertTrue("bid quotes lower", find(wide, "B1").price.compareTo(find(tight, "B1").price) < 0);
        assertTrue("ask quotes higher", find(wide, "A1").price.compareTo(find(tight, "A1").price) > 0);
    }

    @Test public void levelsAreCappedAtSix() {
        List<MakerLadder.Level> ls = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ls.add(new MakerLadder.Level(new BigDecimal("0.1").multiply(new BigDecimal(i)), new BigDecimal(50)));
        }
        List<MakerLadder.Slot> slots = MakerLadder.desired(MID,
                new MakerLadder.Config(ls, BigDecimal.ZERO, new BigDecimal("0.1"), true, true),
                BigDecimal.ONE);
        assertEquals(MakerLadder.MAX_LEVELS * 2, slots.size());
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

    @Test public void missingRungsAreCreated() {
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(2, "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, new HashMap<>(),
                new BigDecimal("0.1"), new HashSet<>(), 0);
        assertEquals(4, acts.size());
        for (MakerLadder.Action a : acts) assertEquals(MakerLadder.Kind.CREATE, a.kind);
    }

    @Test public void aMovedPriceRelocksRatherThanCancelAndRepost() {
        // the whole point of the V5 owner re-lock: repricing must never drop the level
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
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
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(1, "0", "0.5"), BigDecimal.ONE);
        Map<String, Order5> live = new HashMap<>();
        live.put("A1", order("0xC1", find(want, "A1").price.toPlainString(), "100"));
        live.put("B1", order("0xC2", find(want, "B1").price.toPlainString(), "100"));
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, live,
                new BigDecimal("0.5"), new HashSet<>(), 0);
        assertTrue("an unchanged ladder must cost nothing", acts.isEmpty());
    }

    @Test public void partiallyFilledRungsAreLeftWorking() {
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
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
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(1, "0", "0.1"), BigDecimal.ONE);
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
        List<MakerLadder.Slot> want = MakerLadder.desired(MID, cfg(6, "0", "0.1"), BigDecimal.ONE);
        List<MakerLadder.Action> acts = MakerLadder.reconcile(want, new HashMap<>(),
                new BigDecimal("0.1"), new HashSet<>(), 4);
        assertEquals("proof-of-work per action means cycles must be capped", 4, acts.size());
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

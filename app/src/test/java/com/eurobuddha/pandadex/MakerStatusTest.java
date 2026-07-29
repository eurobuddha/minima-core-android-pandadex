package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-rung story the user could never see in 0.2.6 ("published a ladder, watched nothing
 * happen"). Each state has to read as a distinct, honest sentence.
 */
public class MakerStatusTest {

    private static List<MakerLadder.Slot> want(String... sizes) {
        List<MakerLadder.Level> bids = new ArrayList<>();
        for (String s : sizes) bids.add(new MakerLadder.Level(BigDecimal.ZERO, new BigDecimal(s)));
        return MakerLadder.desired(new BigDecimal("0.05"),
                new MakerLadder.Config(true, new BigDecimal("0.20"), new ArrayList<>(), bids,
                        BigDecimal.ZERO, new BigDecimal("0.1")),
                BigDecimal.ONE);
    }

    private static Order5 order(String coinid, String orderId, String price, String minima) {
        try {
            BigDecimal locked = new BigDecimal(minima);
            org.json.JSONObject c = new org.json.JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked.multiply(new BigDecimal(price)).toPlainString());
            c.put("tokenid", DexContract.USDT_ID);
            c.put("created", 10);
            org.json.JSONObject st = new org.json.JSONObject();
            st.put("0", "0xMINE");
            st.put("1", "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE");
            st.put("2", locked.toPlainString());
            st.put("3", "0x00");
            st.put("4", orderId);
            st.put("5", "0");
            st.put("6", price);
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String textFor(List<MakerStatus.Line> lines, String slotId) {
        for (MakerStatus.Line l : lines) if (slotId.equals(l.slotId)) return l.text;
        return null;
    }

    private static int toneFor(List<MakerStatus.Line> lines, String slotId) {
        for (MakerStatus.Line l : lines) if (slotId.equals(l.slotId)) return l.tone;
        return -1;
    }

    @Test public void aRungWithNoRecordIsWaitingToPost() {
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new HashMap<>(), 100);
        assertEquals(1, lines.size());
        assertTrue(textFor(lines, "B1").contains("waiting to post"));
        assertEquals(MakerStatus.WAIT, toneFor(lines, "B1"));
    }

    @Test public void anAcceptedButUnconfirmedRungReadsAsMining() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), new HashMap<>(), 102);
        String t = textFor(lines, "B1");
        assertTrue("says mining: " + t, t.contains("mining"));
        assertTrue("and how long it has been waiting: " + t, t.contains("2 blk"));
        assertEquals(MakerStatus.WAIT, toneFor(lines, "B1"));
    }

    @Test public void aRungOnTheBookReadsAsLiveWithItsPriceAndSize() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 103);
        String t = textFor(lines, "B1");
        assertTrue("says live: " + t, t.startsWith("live"));
        assertTrue("with the price: " + t, t.contains("0.049900"));
        assertEquals(MakerStatus.OK, toneFor(lines, "B1"));
    }

    @Test public void aPartlyTakenRungSaysWhatIsStillWorking() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "40"));   // 40 of 100 left
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 103);
        String t = textFor(lines, "B1");
        assertTrue("says part-filled: " + t, t.contains("part-filled"));
        assertEquals(MakerStatus.OK, toneFor(lines, "B1"));
    }

    @Test public void aRelockInFlightReadsAsRepricing() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 104));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 105);
        assertTrue(textFor(lines, "B1").contains("repricing"));
    }

    @Test public void cancellingOrdersAreSummarisedSoAWithdrawIsNeverSilent() {
        Map<String, Long> tomb = new LinkedHashMap<>();
        tomb.put("0xORD1", 100L);
        tomb.put("0xORD2", 100L);
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD1", order("0xC1", "0xORD1", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(new ArrayList<>(), new LinkedHashMap<>(),
                tomb, book, 101);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).text.contains("cancelling 2 orders"));
    }

    @Test public void aRecordedRungNoLongerWantedSaysItIsGoing() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B4", new MakerConfig.SlotRec("0xOLD", new BigDecimal("100"), 100, 0));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), new HashMap<>(), 101);
        assertTrue(textFor(lines, "B4").contains("no longer wanted"));
    }

    @Test public void everySlotInTheLadderGetsALine() {
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100", "0", "300"),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new HashMap<>(), 100);
        // the zero-size rung is a gap — not quoted, so not reported
        assertEquals(2, lines.size());
        assertTrue(Arrays.asList("B1", "B3").contains(lines.get(0).slotId));
    }
}

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
            c.put("tokenamount", locked.multiply(new BigDecimal(price)).toPlainString());
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
                new LinkedHashMap<>(), new HashMap<>(), 100, true);
        assertEquals(1, lines.size());
        assertTrue(textFor(lines, "B1").contains("waiting to post"));
        assertEquals(MakerStatus.WAIT, toneFor(lines, "B1"));
    }

    @Test public void anAbsentRecordedRungDoesNotInventMining() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0,new BigDecimal("4.99"),DexContract.USDT_ID));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), new HashMap<>(), 102, true);
        String t = textFor(lines, "B1");
        assertTrue("describes observed absence: " + t, t.contains("not seen"));
        assertTrue("and how long it has been waiting: " + t, t.contains("2 blk"));
        assertEquals(MakerStatus.WAIT, toneFor(lines, "B1"));
    }

    @Test public void aRungOnTheBookReadsAsLiveWithItsPriceAndSize() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0,new BigDecimal("4.99"),DexContract.USDT_ID));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 103, true);
        String t = textFor(lines, "B1");
        assertTrue("says live: " + t, t.startsWith("seen in latest book snapshot"));
        assertTrue("with the price: " + t, t.contains("0.049900"));
        assertEquals(MakerStatus.OK, toneFor(lines, "B1"));
    }

    @Test public void aPartlyTakenRungSaysWhatIsStillWorking() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 0,new BigDecimal("4.99"),DexContract.USDT_ID));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "40"));   // 40 of 100 left
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 103, true);
        String t = textFor(lines, "B1");
        assertTrue("says part-filled: " + t, t.contains("less funding remains"));
        assertEquals(MakerStatus.OK, toneFor(lines, "B1"));
    }

    @Test public void aRelockInFlightReadsAsRepricing() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B1", new MakerConfig.SlotRec("0xORD", new BigDecimal("100"), 100, 104,new BigDecimal("4.99"),DexContract.USDT_ID));
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD", order("0xC1", "0xORD", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), book, 105, true);
        assertTrue(textFor(lines, "B1").contains("reprice requested"));
    }

    @Test public void cancellingOrdersAreSummarisedSoAWithdrawIsNeverSilent() {
        Map<String, Long> tomb = new LinkedHashMap<>();
        tomb.put("0xORD1", 100L);
        tomb.put("0xORD2", 100L);
        Map<String, Order5> book = new HashMap<>();
        book.put("0xORD1", order("0xC1", "0xORD1", "0.049900", "100"));
        List<MakerStatus.Line> lines = MakerStatus.lines(new ArrayList<>(), new LinkedHashMap<>(),
                tomb, book, 101, true);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).text.contains("withdrawal instructions retained for 2 orders"));
    }

    @Test public void aRecordedRungNoLongerWantedSaysItIsGoing() {
        Map<String, MakerConfig.SlotRec> slots = new LinkedHashMap<>();
        slots.put("B4", new MakerConfig.SlotRec("0xOLD", new BigDecimal("100"), 100, 0));
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100"), slots,
                new LinkedHashMap<>(), new HashMap<>(), 101, true);
        assertTrue(textFor(lines, "B4").contains("no longer wanted"));
    }

    @Test public void everySlotInTheLadderGetsALine() {
        List<MakerStatus.Line> lines = MakerStatus.lines(want("100", "0", "300"),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new HashMap<>(), 100, true);
        // the zero-size rung is a gap — not quoted, so not reported
        assertEquals(2, lines.size());
        assertTrue(Arrays.asList("B1", "B3").contains(lines.get(0).slotId));
    }
    @Test public void repricedBuyWithUnchangedFundingStillReadsAsLive() {
        Map<String,MakerConfig.SlotRec> slots=new LinkedHashMap<>();
        slots.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,0,new BigDecimal("5"),DexContract.USDT_ID));
        Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("0xC1","0xORD","0.1","50"));
        List<MakerStatus.Line> lines=MakerStatus.lines(want("100"),slots,new LinkedHashMap<>(),book,105,true);
        assertTrue(textFor(lines,"B1").startsWith("seen in latest book snapshot"));assertEquals(MakerStatus.OK,toneFor(lines,"B1"));
    }
    @Test public void legacyBuyWithoutOriginalFundingRequestsReview() {
        Map<String,MakerConfig.SlotRec> slots=new LinkedHashMap<>();
        slots.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,0));
        Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("0xC1","0xORD","0.1","50"));
        List<MakerStatus.Line> lines=MakerStatus.lines(want("100"),slots,new LinkedHashMap<>(),book,105,true);
        assertTrue(textFor(lines,"B1").contains("original funding unknown"));assertEquals(MakerStatus.WAIT,toneFor(lines,"B1"));
    }

    @Test public void staleSnapshotRetainsPriceAndSizeWithoutClaimingLiveOwnership() {
        Map<String,MakerConfig.SlotRec> slots=new LinkedHashMap<>();
        slots.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,0,new BigDecimal("5"),DexContract.USDT_ID));
        Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("0xC1","0xORD","0.05","100"));
        List<MakerStatus.Line> lines=MakerStatus.lines(want("100"),slots,new LinkedHashMap<>(),book,105,false);
        String text=textFor(lines,"B1");assertTrue(text.startsWith("last seen"));assertTrue(text.contains("0.050000"));
        assertTrue(text.contains("100"));assertTrue(text.contains("check pending"));assertEquals(MakerStatus.WAIT,toneFor(lines,"B1"));
    }
    @Test public void uncheckedEmptySnapshotCannotDescribeAbsenceOrPromisePosting() {
        Map<String,MakerConfig.SlotRec> slots=new LinkedHashMap<>();
        slots.put("B1",new MakerConfig.SlotRec("0xORD",new BigDecimal("100"),100,0));
        List<MakerStatus.Line> lines=MakerStatus.lines(want("100","100"),slots,new LinkedHashMap<>(),new HashMap<>(),999,false);
        assertEquals("order recorded — wallet/book check pending",textFor(lines,"B1"));
        assertEquals("no order recorded — wallet/book check pending",textFor(lines,"B2"));
        for(MakerStatus.Line line:lines)assertEquals(MakerStatus.WAIT,line.tone);
    }
    @Test public void staleWithdrawalSnapshotKeepsInstructionsAndQualifiesItsObservation() {
        Map<String,Long> tomb=new LinkedHashMap<>();tomb.put("0xORD",100L);
        Map<String,Order5> book=new HashMap<>();book.put("0xORD",order("0xC1","0xORD","0.05","100"));
        List<MakerStatus.Line> lines=MakerStatus.lines(new ArrayList<>(),new LinkedHashMap<>(),tomb,book,999,false);
        assertTrue(lines.get(0).text.contains("instructions retained"));assertTrue(lines.get(0).text.contains("last seen"));
        assertTrue(lines.get(0).text.contains("check pending"));assertEquals(MakerStatus.WAIT,lines.get(0).tone);
    }
    @Test public void sameBookRefreshesOwnedOrdersWhenWalletKeysArriveOrChange() {
        Order5 o=order("0xC1","0xORD","0.05","100");Map<String,Order5> book=new HashMap<>();book.put(o.coinid,o);
        java.util.Set<String> keys=new java.util.HashSet<>(),addresses=new java.util.HashSet<>();addresses.add(o.wantAddr);
        MakerStatus.OwnedBook cache=new MakerStatus.OwnedBook();assertTrue(cache.get(book,keys,addresses).isEmpty());
        keys.add(o.ownerPk);assertEquals(o,cache.get(book,keys,addresses).get(o.orderId));
        keys.clear();assertTrue(cache.get(book,keys,addresses).isEmpty());
    }
    @Test public void copiedOwnerKeyWithoutPayoutOwnershipCannotSurviveCacheReuse() {
        Order5 o=order("0xC1","0xORD","0.05","100");Map<String,Order5> book=new HashMap<>();book.put(o.coinid,o);
        java.util.Set<String> keys=new java.util.HashSet<>(),addresses=new java.util.HashSet<>();keys.add(o.ownerPk);addresses.add(o.wantAddr);
        MakerStatus.OwnedBook cache=new MakerStatus.OwnedBook();assertEquals(1,cache.get(book,keys,addresses).size());
        addresses.clear();assertTrue(cache.get(book,keys,addresses).isEmpty());addresses.add(o.wantAddr);
        assertEquals(1,cache.get(book,keys,addresses).size());
    }
    @Test public void unchangedWalletAndBookReuseCacheButNewBookRebuilds() {
        Order5 o=order("0xC1","0xORD","0.05","100");Map<String,Order5> book=new HashMap<>();book.put(o.coinid,o);
        java.util.Set<String> keys=java.util.Collections.singleton(o.ownerPk),addresses=java.util.Collections.singleton(o.wantAddr);
        MakerStatus.OwnedBook cache=new MakerStatus.OwnedBook();Map<String,Order5> first=cache.get(book,keys,addresses);
        org.junit.Assert.assertSame(first,cache.get(book,keys,addresses));
        org.junit.Assert.assertNotSame(first,cache.get(new HashMap<>(book),keys,addresses));
        assertTrue(cache.get(new HashMap<>(),keys,addresses).isEmpty());
    }

}

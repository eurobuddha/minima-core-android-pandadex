package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tape must never invent a trade, and must never LOSE one either.
 *
 * v0.1.3 fixed the first half by discarding any diff where the book emptied — which threw away
 * the second half, because in a thin market the last resting order filling empties the book.
 * That is a real trade. These tests pin both directions.
 */
public class TapeEvidenceTest {

    private FillTape tape;
    private final List<Object[]> fills = new ArrayList<>();
    private final java.util.Set<String> cancelled = new HashSet<>();
    private final FillTape.Sink sink = (spent, order, size, price, takerBuy, partial, sinceBlock) ->
            fills.add(new Object[]{spent, size, price, partial});

    @Before public void setUp() {
        fills.clear();
        cancelled.clear();
        tape = new FillTape(new FillTape.CancelLog() {
            @Override public void note(String c) { cancelled.add(c); }
            @Override public boolean consume(String c) { return cancelled.contains(c); }
        });
    }

    private static Order5 sell(String coinid, String orderId, String locked, String want) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked);
            c.put("tokenid", "0x00");
            c.put("created", 10);
            JSONObject st = new JSONObject();
            st.put("0", "0xAAA");
            st.put("1", "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE");
            st.put("2", want);
            st.put("3", DexContract.USDT_ID);
            st.put("4", orderId);
            st.put("5", "1");
            st.put("6", "0");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, Order5> book(Order5... os) {
        Map<String, Order5> m = new LinkedHashMap<>();
        for (Order5 o : os) m.put(o.coinid, o);
        return m;
    }

    @Test public void theLastOrderFillingIsStillRecorded() {
        // a two-person market: one resting order, someone takes it, the book is now empty.
        // that is a TRADE, and v0.1.3 silently dropped it.
        Order5 only = sell("0xC1", "0xA1", "300", "15.45");
        tape.ingest(book(only), false, 100, sink);
        for (int b = 101; b <= 106; b++) tape.ingest(book(), false, b, sink);
        assertEquals("the last order filling must be recorded", 1, fills.size());
        assertEquals("0xC1", fills.get(0)[0]);
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) fills.get(0)[1]));
    }

    @Test public void aBriefEmptyBlipStillRecordsNothing() {
        // one bad read, then the book comes back — no trade happened
        Order5 only = sell("0xC1", "0xA1", "300", "15.45");
        tape.ingest(book(only), false, 100, sink);
        tape.ingest(book(), false, 101, sink);
        tape.ingest(book(), false, 102, sink);
        tape.ingest(book(only), false, 103, sink);
        assertTrue("a transient empty scan must mint nothing", fills.isEmpty());
    }

    @Test public void aCancelledOrderIsNeverATradeEvenWhenItEmptiesTheBook() {
        Order5 only = sell("0xC1", "0xA1", "300", "15.45");
        tape.ingest(book(only), false, 100, sink);
        tape.noteMyCancel("0xC1");
        for (int b = 101; b <= 106; b++) tape.ingest(book(), false, b, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void aResyncingNodeNeverBooksTheWholeBookAsTrades() {
        // A node with no tip answers `coins` with status:true and an EMPTY array. That parses
        // perfectly — truncated is false, the age and expiry guards don't fire — so before
        // this guard a couple of minutes of resync minted every resting order as a full fill,
        // permanently, into the app's only source of price truth.
        Order5 a = sell("0xC1", "0xA1", "100", "5");
        Order5 b = sell("0xC2", "0xA2", "100", "5");
        Order5 c = sell("0xC3", "0xA3", "100", "5");
        Order5 d = sell("0xC4", "0xA4", "100", "5");
        tape.ingest(book(a, b, c, d), false, 100, sink);
        for (int blk = 101; blk <= 130; blk++) tape.ingest(book(), false, blk, sink);
        assertTrue("an empty book is not evidence of anything", fills.isEmpty());

        // ...and when the node comes back, the orders are simply still there
        tape.ingest(book(a, b, c, d), false, 131, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void aFillDuringAnOutageIsStillFoundWhenTheNodeReturns() {
        // holding the last believable book (rather than folding the empty one in) is what
        // lets the recovered scan adjudicate: c really did trade while the node was blind
        Order5 a = sell("0xC1", "0xA1", "100", "5");
        Order5 b = sell("0xC2", "0xA2", "100", "5");
        Order5 c = sell("0xC3", "0xA3", "100", "5");
        Order5 d = sell("0xC4", "0xA4", "100", "5");
        tape.ingest(book(a, b, c, d), false, 100, sink);
        for (int blk = 101; blk <= 110; blk++) tape.ingest(book(), false, blk, sink);
        assertTrue("nothing minted while blind", fills.isEmpty());
        for (int blk = 111; blk <= 118; blk++) tape.ingest(book(a, b, d), false, blk, sink);
        assertEquals("the one that really went is recorded", 1, fills.size());
        assertEquals("0xC3", fills.get(0)[0]);
    }

    @Test public void noSingleScanCanAssertAWaveOfFills() {
        // however ripe the counters, one ingest may only ever assert MAX_VANISH_PER_SCAN
        Order5 keep = sell("0xK1", "0xB1", "10", "0.5");
        Order5 a = sell("0xC1", "0xA1", "100", "5");
        Order5 b = sell("0xC2", "0xA2", "100", "5");
        Order5 c = sell("0xC3", "0xA3", "100", "5");
        tape.ingest(book(keep, a, b, c), false, 100, sink);
        for (int blk = 101; blk <= 104; blk++) tape.ingest(book(keep), false, blk, sink);
        assertTrue("at most two per ingest", fills.size() <= 2);
        for (int blk = 105; blk <= 110; blk++) tape.ingest(book(keep), false, blk, sink);
        assertEquals("but nothing real is lost — the rest follow", 3, fills.size());
    }

    @Test public void aStrangerCopyingMyOrderIdentityCannotRewriteMyFill() {
        // every component of the identity is public on the book, so a stranger can mint a coin
        // carrying all four. Ambiguity must disqualify the match, not pick a winner.
        Order5 mine = sell("0xC1", "0xA1", "100", "5");
        Order5 impostor = sell("0xFAKE", "0xA1", "40", "2");   // same orderId, smaller locked
        tape.ingest(book(mine, impostor), false, 100, sink);
        for (int blk = 101; blk <= 108; blk++) tape.ingest(book(impostor), false, blk, sink);
        for (Object[] f : fills) {
            assertTrue("no fabricated PARTIAL at the stranger's size", !((Boolean) f[3]));
        }
    }

    @Test public void aPersistentMassVanishIsEventuallyRecorded() {
        Order5 a = sell("0xC1", "0xA1", "100", "5");
        Order5 b = sell("0xC2", "0xA2", "100", "5");
        Order5 c = sell("0xC3", "0xA3", "100", "5");
        Order5 keep = sell("0xK1", "0xB1", "10", "0.5");
        tape.ingest(book(a, b, c, keep), false, 100, sink);
        for (int blk = 101; blk <= 108; blk++) tape.ingest(book(keep), false, blk, sink);
        assertEquals("three real fills, confirmed over several scans", 3, fills.size());
    }
}

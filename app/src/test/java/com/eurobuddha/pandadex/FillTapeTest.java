package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FillTapeTest {

    private FillTape tape;
    private final List<Object[]> fills = new ArrayList<>();
    private final FillTape.Sink sink = (spentCoin, order, size, price, takerBuy, partial, sinceBlock) ->
            fills.add(new Object[]{spentCoin, order.orderId, size, price, takerBuy, partial});

    private final java.util.Set<String> cancelled = new java.util.HashSet<>();

    @Before public void setUp() {
        cancelled.clear();
        tape = new FillTape(new FillTape.CancelLog() {
            @Override public void note(String coinid) { cancelled.add(coinid); }
            @Override public boolean consume(String coinid) { return cancelled.contains(coinid); }
        });
        fills.clear();
    }

    private static Order5 sell(String coinid, String orderId, String locked, String want, long created) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", locked);
            c.put("tokenid", "0x00");
            c.put("created", created);
            JSONObject st = new JSONObject();
            st.put("0", "0xAAA1");
            st.put("1", "0xBBB1");
            st.put("2", want);
            st.put("3", DexContract.USDT_ID);
            st.put("4", orderId);
            st.put("5", "1");
            st.put("6", "0");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Order5> book(Order5... orders) {
        Map<String, Order5> m = new LinkedHashMap<>();
        for (Order5 o : orders) m.put(o.coinid, o);
        return m;
    }

    @Test public void firstScanSeedsSilently() {
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 100, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void partialFillExactDelta() {
        Order5 o = sell("0xC1", "0xA1", "100", "0.575", 10);
        tape.ingest(book(o), false, 100, sink);
        // same orderId reappears smaller under a new coinid
        tape.ingest(book(sell("0xC2", "0xA1", "40", "0.23", 101)), false, 101, sink);
        assertEquals(1, fills.size());
        Object[] f = fills.get(0);
        assertEquals("0xC1", f[0]);
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) f[2]));   // size
        assertEquals(0, new BigDecimal("0.005750000000").compareTo((BigDecimal) f[3]));
        assertEquals(Boolean.TRUE, f[4]);    // taker bought MINIMA from a sell order
        assertEquals(Boolean.TRUE, f[5]);    // partial
    }

    @Test public void renewalIsNotATrade() {
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 100, sink);
        // same orderId, same size, new coinid (atomic renew / edit)
        tape.ingest(book(sell("0xC2", "0xA1", "100", "0.500", 101)), false, 101, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void fullFillNeedsMissGrace() {
        // one order of several disappears — the rest of the book corroborates the read
        Order5 keep1 = sell("0xK1", "0xB1", "10", "0.05", 10);
        Order5 keep2 = sell("0xK2", "0xB2", "10", "0.05", 10);
        Order5 gone = sell("0xC1", "0xA1", "100", "0.575", 10);
        tape.ingest(book(gone, keep1, keep2), false, 100, sink);
        tape.ingest(book(keep1, keep2), false, 101, sink);   // first absence — grace
        assertTrue(fills.isEmpty());
        tape.ingest(book(keep1, keep2), false, 102, sink);   // second absence — fill
        assertEquals(1, fills.size());
        assertEquals(Boolean.FALSE, fills.get(0)[5]);   // full
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) fills.get(0)[2]));
    }

    // ---- the phantom-trade bug: a bad scan must never mint trades ----

    @Test public void emptyScanNeverProducesFills() {
        // THE 0.1.2 BUG: an empty (but not 'truncated') scan made every resting order look
        // filled at its own price and size, which is how a book of orders became a fake
        // 24h high/low/volume on the ticker.
        tape.ingest(book(sell("0xC1", "0xA1", "300", "15.6", 10),
                         sell("0xC2", "0xA2", "300", "14.55", 10),
                         sell("0xC3", "0xA3", "300", "15.0", 10)), false, 100, sink);
        tape.ingest(book(), false, 101, sink);
        tape.ingest(book(), false, 102, sink);
        tape.ingest(book(), false, 103, sink);
        assertTrue("an empty scan must never mint trades", fills.isEmpty());
    }

    @Test public void massDisappearanceNeverProducesFills() {
        Order5 a = sell("0xC1", "0xA1", "300", "15.6", 10);
        Order5 b = sell("0xC2", "0xA2", "300", "14.55", 10);
        Order5 c = sell("0xC3", "0xA3", "300", "15.0", 10);
        Order5 d = sell("0xC4", "0xA4", "300", "15.2", 10);
        tape.ingest(book(a, b, c, d), false, 100, sink);
        tape.ingest(book(a), false, 101, sink);      // three of four vanish at once
        tape.ingest(book(a), false, 102, sink);
        assertTrue("a mass vanish is a bad read, not a wave of trades", fills.isEmpty());
    }

    @Test public void noChainHeightNeverProducesFills() {
        // with chainBlock 0 the age guards are blind, so no fill may be booked
        Order5 keep = sell("0xK1", "0xB1", "10", "0.05", 10);
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10), keep), false, 100, sink);
        tape.ingest(book(keep), false, 0, sink);
        tape.ingest(book(keep), false, 0, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void reappearanceCancelsMissCounter() {
        Order5 o = sell("0xC1", "0xA1", "100", "0.575", 10);
        tape.ingest(book(o), false, 100, sink);
        tape.ingest(book(), false, 101, sink);       // absent once
        tape.ingest(book(o), false, 102, sink);      // back (reorg blip)
        tape.ingest(book(), false, 103, sink);       // absent once again — counter restarted
        assertTrue(fills.isEmpty());
    }

    @Test public void myCancelSuppressed() {
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 100, sink);
        tape.noteMyCancel("0xC1");
        tape.ingest(book(), false, 101, sink);
        tape.ingest(book(), false, 102, sink);
        assertTrue(fills.isEmpty());
    }

    @Test public void expirySweepSuppressed() {
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 700, sink);
        tape.ingest(book(), false, 701, sink);
        tape.ingest(book(), false, 702, sink);       // age 692 > EXPIRY 600 — sweep, not fill
        assertTrue(fills.isEmpty());
    }

    @Test public void truncatedScanNeverDiffs() {
        Order5 keep = sell("0xK1", "0xB1", "10", "0.05", 10);
        Order5 gone = sell("0xC1", "0xA1", "100", "0.575", 10);
        tape.ingest(book(gone, keep), false, 100, sink);
        tape.ingest(book(), true, 101, sink);        // truncated — ignored entirely
        tape.ingest(book(gone, keep), false, 102, sink);
        tape.ingest(book(keep), false, 103, sink);   // real disappearance, book corroborates
        tape.ingest(book(keep), false, 104, sink);
        assertEquals(1, fills.size());               // only the real disappearance counts
    }
}

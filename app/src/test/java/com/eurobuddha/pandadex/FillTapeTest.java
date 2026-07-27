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
    private final FillTape.Sink sink = (spentCoin, order, size, price, takerBuy, partial) ->
            fills.add(new Object[]{spentCoin, order.orderId, size, price, takerBuy, partial});

    @Before public void setUp() {
        tape = new FillTape();
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
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 100, sink);
        tape.ingest(book(), false, 101, sink);       // first absence — grace
        assertTrue(fills.isEmpty());
        tape.ingest(book(), false, 102, sink);       // second absence — fill
        assertEquals(1, fills.size());
        assertEquals(Boolean.FALSE, fills.get(0)[5]);   // full
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) fills.get(0)[2]));
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
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 3000, sink);
        tape.ingest(book(), false, 3001, sink);
        tape.ingest(book(), false, 3002, sink);      // age 2990 > EXPIRY — sweep, not fill
        assertTrue(fills.isEmpty());
    }

    @Test public void truncatedScanNeverDiffs() {
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 100, sink);
        tape.ingest(book(), true, 101, sink);        // truncated — ignored entirely
        tape.ingest(book(sell("0xC1", "0xA1", "100", "0.575", 10)), false, 102, sink);
        tape.ingest(book(), false, 103, sink);
        tape.ingest(book(), false, 104, sink);
        assertEquals(1, fills.size());               // only the real disappearance counts
    }
}

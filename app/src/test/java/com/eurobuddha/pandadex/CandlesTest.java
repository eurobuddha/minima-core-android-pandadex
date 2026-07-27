package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class CandlesTest {

    private static Candles.Fill f(long t, String p, String s) {
        return new Candles.Fill(t, new BigDecimal(p), new BigDecimal(s), true);
    }

    @Test public void bucketBoundaries() {
        assertEquals(0L, Candles.bucketStart(Candles.M15 - 1, Candles.M15));
        assertEquals(Candles.M15, Candles.bucketStart(Candles.M15, Candles.M15));
        assertEquals(Candles.M15, Candles.bucketStart(Candles.M15 + 1, Candles.M15));
    }

    @Test public void ohlcFolding() {
        List<Candles.Fill> fills = new ArrayList<>();
        fills.add(f(1_000, "0.0050", "10"));
        fills.add(f(2_000, "0.0070", "5"));
        fills.add(f(3_000, "0.0040", "2"));
        fills.add(f(4_000, "0.0060", "1"));
        // out-of-order arrival must not corrupt open/close
        List<Candles.Fill> shuffled = new ArrayList<>();
        shuffled.add(fills.get(2)); shuffled.add(fills.get(0)); shuffled.add(fills.get(3)); shuffled.add(fills.get(1));
        List<Candles.Candle> cs = Candles.aggregate(shuffled, Candles.M15);
        assertEquals(1, cs.size());
        Candles.Candle c = cs.get(0);
        assertEquals(0, new BigDecimal("0.0050").compareTo(c.open));
        assertEquals(0, new BigDecimal("0.0070").compareTo(c.high));
        assertEquals(0, new BigDecimal("0.0040").compareTo(c.low));
        assertEquals(0, new BigDecimal("0.0060").compareTo(c.close));
        assertEquals(0, new BigDecimal("18").compareTo(c.volume));
        assertEquals(4, c.fills);
    }

    @Test public void gapsAreNotFilled() {
        List<Candles.Fill> fills = new ArrayList<>();
        fills.add(f(1_000, "0.005", "1"));
        fills.add(f(3 * Candles.M15 + 1_000, "0.006", "1"));
        List<Candles.Candle> cs = Candles.aggregate(fills, Candles.M15);
        assertEquals(2, cs.size());
        assertEquals(0L, cs.get(0).openMs);
        assertEquals(3 * Candles.M15, cs.get(1).openMs);
    }

    @Test public void stats24hWindow() {
        long now = 100 * Candles.D1;
        List<Candles.Fill> fills = new ArrayList<>();
        fills.add(f(now - Candles.D1 - 1, "0.001", "50"));   // outside window — ignored
        fills.add(f(now - Candles.H1 * 5, "0.0050", "10"));
        fills.add(f(now - Candles.H1 * 2, "0.0075", "10"));
        BigDecimal[] s = Candles.stats24h(fills, now);
        assertEquals(0, new BigDecimal("0.0075").compareTo(s[0]));   // last
        assertEquals(0, new BigDecimal("50.00").compareTo(s[1]));    // +50%
        assertEquals(0, new BigDecimal("0.0075").compareTo(s[2]));   // high
        assertEquals(0, new BigDecimal("0.0050").compareTo(s[3]));   // low
        assertEquals(0, new BigDecimal("20").compareTo(s[4]));       // vol
    }

    @Test public void emptyTape() {
        BigDecimal[] s = Candles.stats24h(new ArrayList<>(), 12345L);
        assertNull(s[0]);
        assertEquals(0, BigDecimal.ZERO.compareTo(s[4]));
    }
}

package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * OHLC bucketer: folds the on-chain fill tape into candles for the chart. Pure math — no
 * Android, no node — so it's fully JVM-unit-testable. Buckets are aligned to epoch-millis
 * boundaries of the interval; fills are token-priced (mxUSDT per MINIMA).
 */
public final class Candles {

    /** One observed fill (one spend of a book coin). */
    public static final class Fill {
        public final long timeMs;
        public final BigDecimal price;    // mxUSDT per MINIMA
        public final BigDecimal size;     // MINIMA
        public final boolean buy;         // taker side: true = taker bought MINIMA

        public Fill(long timeMs, BigDecimal price, BigDecimal size, boolean buy) {
            this.timeMs = timeMs;
            this.price = price;
            this.size = size;
            this.buy = buy;
        }
    }

    public static final class Candle {
        public final long openMs;
        public BigDecimal open, high, low, close;
        public BigDecimal volume = BigDecimal.ZERO;   // MINIMA
        public int fills = 0;

        Candle(long openMs, BigDecimal first) {
            this.openMs = openMs;
            open = high = low = close = first;
        }

        void add(Fill f) {
            if (f.price.compareTo(high) > 0) high = f.price;
            if (f.price.compareTo(low) < 0) low = f.price;
            close = f.price;
            volume = volume.add(f.size);
            fills++;
        }
    }

    public static final long M15 = 15 * 60_000L;
    public static final long H1 = 60 * 60_000L;
    public static final long H4 = 4 * H1;
    public static final long D1 = 24 * H1;

    private Candles() {}

    public static long bucketStart(long timeMs, long intervalMs) {
        return timeMs - Math.floorMod(timeMs, intervalMs);
    }

    /** Fold fills (any order) into interval candles, ascending by bucket time. Gaps are NOT
     *  filled here — the chart renders gaps explicitly (an honest tape has quiet periods). */
    public static List<Candle> aggregate(List<Fill> fills, long intervalMs) {
        TreeMap<Long, Candle> buckets = new TreeMap<>();
        List<Fill> sorted = new ArrayList<>(fills);
        sorted.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        for (Fill f : sorted) {
            long b = bucketStart(f.timeMs, intervalMs);
            Candle c = buckets.get(b);
            if (c == null) {
                buckets.put(b, c = new Candle(b, f.price));
            }
            c.add(f);
        }
        return new ArrayList<>(buckets.values());
    }

    /** 24h rolling stats for the ticker header. Returns {last, changePct, high, low, volume}
     *  over fills newer than nowMs-24h; nulls when the tape is empty. */
    public static BigDecimal[] stats24h(List<Fill> fills, long nowMs) {
        long cutoff = nowMs - D1;
        BigDecimal last = null, first = null, high = null, low = null, vol = BigDecimal.ZERO;
        List<Fill> sorted = new ArrayList<>(fills);
        sorted.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        for (Fill f : sorted) {
            if (f.timeMs < cutoff) continue;
            if (first == null) first = f.price;
            last = f.price;
            high = high == null ? f.price : high.max(f.price);
            low = low == null ? f.price : low.min(f.price);
            vol = vol.add(f.size);
        }
        if (last == null) return new BigDecimal[]{null, null, null, null, BigDecimal.ZERO};
        BigDecimal change = first.signum() == 0 ? BigDecimal.ZERO
                : last.subtract(first).multiply(new BigDecimal(100)).divide(first, 2, java.math.RoundingMode.HALF_UP);
        return new BigDecimal[]{last, change, high, low, vol};
    }
}

package com.eurobuddha.pandadex;

import java.math.BigDecimal;

/**
 * Cheap, cached read model over the tape for the ticker header. Recomputing 24h stats on
 * every repaint would re-read the whole tape from SQLite on the main thread (Limit's
 * re-parse-200-trades-per-refresh mistake); this caches for a second.
 */
public final class DexStats {

    private static final long TTL_MS = 1000;

    private final DexDb db;
    private BigDecimal[] cached = new BigDecimal[]{null, null, null, null, BigDecimal.ZERO};
    private long cachedAt = 0;

    public DexStats(DexDb db) { this.db = db; }

    /** {last, changePct, high, low, volume} over the trailing 24h. */
    public BigDecimal[] stats24h() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < TTL_MS) return cached;
        cached = Candles.stats24h(db.fills(now - Candles.D1), now);
        cachedAt = now;
        return cached;
    }

    /** The newest observed fill as [timeMs, price], or null. Cheap, cached like the stats. */
    public Object[] lastFill() {
        long now = System.currentTimeMillis();
        if (now - lastFillAt >= TTL_MS) {
            lastFill = db.lastFill();
            lastFillAt = now;
        }
        return lastFill;
    }

    private Object[] lastFill;
    private long lastFillAt = 0;

    /** Invalidate after a new fill lands. */
    public void invalidate() { cachedAt = 0; lastFillAt = 0; }

    public DexDb raw() { return db; }
}

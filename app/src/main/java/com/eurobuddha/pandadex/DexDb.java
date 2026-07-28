package com.eurobuddha.pandadex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-first storage — the reason the app paints instantly (minimaSwap lesson: the chain is
 * a sync target, not the render source). SQLite, not prefs-JSON (Limit re-parsed 200 trades
 * per refresh on the main thread).
 *
 * Tables:
 *   tape    — every observed market fill (the decentralized ticker source), capped
 *   mytrade — my own fills (maker or taker)
 *   book    — last-good order book snapshot (instant first paint)
 *   meta    — key/value (last block, tracked flag, etc.)
 */
public final class DexDb extends SQLiteOpenHelper {

    private static final String DB = "pandadex.db";
    private static final int V = 3;
    private static final int TAPE_CAP = 8000;

    public DexDb(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, V);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tape (spentcoin TEXT PRIMARY KEY, timems INTEGER, block INTEGER,"
                + " price TEXT, size TEXT, buy INTEGER, partial INTEGER, mine INTEGER)");
        db.execSQL("CREATE INDEX tape_time ON tape(timems)");
        db.execSQL("CREATE TABLE mytrade (spentcoin TEXT PRIMARY KEY, timems INTEGER, block INTEGER,"
                + " price TEXT, size TEXT, buy INTEGER, maker INTEGER, orderid TEXT)");
        db.execSQL("CREATE TABLE book (coinid TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE cancelled (coinid TEXT PRIMARY KEY, timems INTEGER)");
        db.execSQL("CREATE TABLE myorder (coinid TEXT PRIMARY KEY, orderid TEXT, json TEXT,"
                + " timems INTEGER, block INTEGER)");
    }

    /**
     * ADDITIVE MIGRATIONS ONLY — never destroy user data again.
     *
     * The v3 purge below was a judgement call that also destroyed legitimately observed fills:
     * a user upgrading lost real trade history they could not get back, and two devices ended
     * up reporting different markets. It is kept only so devices that already ran it stay
     * consistent. Corrupt rows are now prevented at the source (FillTape's evidence rules),
     * which costs the user nothing they earned. Do not add another DELETE here — stored
     * history is the user's, and there is no way to get it back once deleted.
     */
    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS cancelled (coinid TEXT PRIMARY KEY, timems INTEGER)");
            db.execSQL("CREATE TABLE IF NOT EXISTS myorder (coinid TEXT PRIMARY KEY, orderid TEXT,"
                    + " json TEXT, timems INTEGER, block INTEGER)");
        }
        if (oldV < 3) {
            // ONE-TIME PURGE of the market tape and personal trade log. Builds before v0.1.3
            // could record a merely-placed ORDER as a trade when a book scan came back empty
            // or partial, so the stored history contains fabricated prices and volumes that
            // feed the ticker, the 24h stats and the candles. There is no way to tell the
            // invented rows from the real ones after the fact, and a wrong price history is
            // worse than a short one — so the tape restarts from genuinely observed fills.
            db.execSQL("DELETE FROM tape");
            db.execSQL("DELETE FROM mytrade");
        }
    }

    // ---- cancels (shared by the Activity's and the service's FillTape) ----

    /** Record that THIS device spent the coin itself (cancel/relock), so neither tape
     *  instance mistakes its disappearance for a trade. */
    public void noteCancelled(String coinid) {
        ContentValues cv = new ContentValues();
        cv.put("coinid", coinid);
        cv.put("timems", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("cancelled", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** True if this coin was cancelled by us. Kept (not deleted) so BOTH the foreground and
     *  background tapes can consult it — they each diff the book independently. Rows older
     *  than a day are pruned since the coin is long gone by then. */
    public boolean wasCancelled(String coinid) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM cancelled WHERE coinid=?", new String[]{coinid})) {
            boolean hit = c.moveToFirst();
            if (hit) getWritableDatabase().execSQL(
                    "DELETE FROM cancelled WHERE timems < " + (System.currentTimeMillis() - 86_400_000L));
            return hit;
        }
    }

    // ---- my orders (survive the node's visibility horizon) ----

    /** Remember an order this device created, so it can still be found and recovered after it
     *  ages out of the node's searchable window. */
    public void rememberMyOrder(String coinid, String orderId, String json, long block) {
        ContentValues cv = new ContentValues();
        cv.put("coinid", coinid);
        cv.put("orderid", orderId);
        cv.put("json", json);
        cv.put("timems", System.currentTimeMillis());
        cv.put("block", block);
        getWritableDatabase().insertWithOnConflict("myorder", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void forgetMyOrder(String coinid) {
        getWritableDatabase().delete("myorder", "coinid=?", new String[]{coinid});
    }

    /** [coinid, orderid, json, block] for every order this device still believes is live. */
    public List<Object[]> myOrders() {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT coinid, orderid, json, block FROM myorder ORDER BY block ASC", null)) {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getString(0), c.getString(1), c.getString(2), c.getLong(3)});
            }
        }
        return out;
    }

    // ---- meta ----

    public String meta(String k, String def) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k})) {
            return c.moveToFirst() ? c.getString(0) : def;
        }
    }

    public void putMeta(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- tape ----

    /** Insert a fill keyed by the SPENT order coinid (each partial spends a distinct coin —
     *  natural exactly-once). Returns true only for a NEW row (drives notify-once). */
    public boolean addFill(String spentCoin, long timeMs, long block, BigDecimal price,
                           BigDecimal size, boolean buy, boolean partial, boolean mine) {
        ContentValues cv = new ContentValues();
        cv.put("spentcoin", spentCoin);
        cv.put("timems", timeMs);
        cv.put("block", block);
        cv.put("price", price.toPlainString());
        cv.put("size", size.toPlainString());
        cv.put("buy", buy ? 1 : 0);
        cv.put("partial", partial ? 1 : 0);
        cv.put("mine", mine ? 1 : 0);
        long r = getWritableDatabase().insertWithOnConflict("tape", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (r != -1) trimTape();
        return r != -1;
    }

    private void trimTape() {
        getWritableDatabase().execSQL(
                "DELETE FROM tape WHERE spentcoin IN (SELECT spentcoin FROM tape ORDER BY timems DESC"
                        + " LIMIT -1 OFFSET " + TAPE_CAP + ")");
    }

    public List<Candles.Fill> fills(long sinceMs) {
        List<Candles.Fill> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT timems, price, size, buy FROM tape WHERE timems>=? ORDER BY timems ASC",
                new String[]{String.valueOf(sinceMs)})) {
            while (c.moveToNext()) {
                out.add(new Candles.Fill(c.getLong(0), new BigDecimal(c.getString(1)),
                        new BigDecimal(c.getString(2)), c.getInt(3) == 1));
            }
        }
        return out;
    }

    /** The most recent observed fill as [timeMs, price], or null if the tape is empty.
     *  Drives the "show the last trade while it's still recent" rule. */
    public Object[] lastFill() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT timems, price FROM tape ORDER BY timems DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            return new Object[]{c.getLong(0), new BigDecimal(c.getString(1))};
        }
    }

    /** Newest-first rows for the TRADES tape view: [timems, price, size, buy, mine]. */
    public List<Object[]> tapeRows(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT timems, price, size, buy, mine FROM tape ORDER BY timems DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), new BigDecimal(c.getString(1)),
                        new BigDecimal(c.getString(2)), c.getInt(3) == 1, c.getInt(4) == 1});
            }
        }
        return out;
    }

    // ---- my trades ----

    public boolean addMyTrade(String spentCoin, long timeMs, long block, BigDecimal price,
                              BigDecimal size, boolean buy, boolean maker, String orderId) {
        ContentValues cv = new ContentValues();
        cv.put("spentcoin", spentCoin);
        cv.put("timems", timeMs);
        cv.put("block", block);
        cv.put("price", price.toPlainString());
        cv.put("size", size.toPlainString());
        cv.put("buy", buy ? 1 : 0);
        cv.put("maker", maker ? 1 : 0);
        cv.put("orderid", orderId);
        return getWritableDatabase().insertWithOnConflict("mytrade", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Newest-first: [timems, price, size, buy, maker, orderid]. */
    public List<Object[]> myTrades(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT timems, price, size, buy, maker, orderid FROM mytrade ORDER BY timems DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), new BigDecimal(c.getString(1)),
                        new BigDecimal(c.getString(2)), c.getInt(3) == 1, c.getInt(4) == 1, c.getString(5)});
            }
        }
        return out;
    }

    // ---- book cache (instant first paint) ----

    public void saveBook(List<String> coinJsons, List<String> coinids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM book");
            for (int i = 0; i < coinJsons.size(); i++) {
                ContentValues cv = new ContentValues();
                cv.put("coinid", coinids.get(i));
                cv.put("json", coinJsons.get(i));
                db.insertWithOnConflict("book", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<String> loadBook() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM book", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }
}

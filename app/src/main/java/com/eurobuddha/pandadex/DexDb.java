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
public final class DexDb extends SQLiteOpenHelper implements FillSettler.Store, DexHistory.ProgressStore, ChainReview.Store, TakerRecovery.Store, OwnerRecovery.Store {

    private static final String DB = "pandadex.db";
    private static final int V = 13;
    private static final int TAPE_CAP = 8000;
    private static final String MAKER_ROW = "spentcoin=? AND maker=1";

    private final Pending pendingReceipts;
    Pending pendingReceipts() { return pendingReceipts; }

    public DexDb(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, V);
        // OpenlyDb's WAL pattern, with explicit FULL synchronization for funds-related receipts.
        setOpenParams(new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).setSynchronousMode(SQLiteDatabase.SYNC_MODE_FULL).build());
        pendingReceipts = new Pending(ctx,this::recordOwnerReceipt);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tape (spentcoin TEXT PRIMARY KEY, timems INTEGER, block INTEGER,"
                + " price TEXT, size TEXT, buy INTEGER, partial INTEGER, mine INTEGER, settlement_kind TEXT NOT NULL DEFAULT 'TRADE')");
        db.execSQL("CREATE INDEX tape_time ON tape(timems)");
        db.execSQL("CREATE TABLE mytrade (spentcoin TEXT PRIMARY KEY, timems INTEGER, block INTEGER,"
                + " price TEXT, size TEXT, buy INTEGER, maker INTEGER, orderid TEXT,"
                + " txpowid TEXT, source_kind TEXT, source_coinids TEXT, proceeds_coinid TEXT,"
                + " verification_status TEXT, verification_note TEXT, verified_block INTEGER)");
        createPendingFills(db);
        createHistoryProgress(db);
        createVerifiedSpends(db);
        createChainChecks(db);
        createReceiptAudit(db);
        createTakerReceipts(db);
        createOwnerReceipts(db);
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
     * up reporting different markets. It is no longer executed on devices upgrading from older schemas. Corrupt rows are now prevented at the source (FillTape's evidence rules),
     * which costs the user nothing they earned. Do not add another DELETE here — stored
     * history is the user's, and there is no way to get it back once deleted.
     */
    /** Sideloading an older APK must not brick the app: the default implementation throws,
     *  and the schema is additive, so an older build simply ignores the newer tables. */
    @Override public void onDowngrade(SQLiteDatabase db, int oldV, int newV) { }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS cancelled (coinid TEXT PRIMARY KEY, timems INTEGER)");
            db.execSQL("CREATE TABLE IF NOT EXISTS myorder (coinid TEXT PRIMARY KEY, orderid TEXT,"
                    + " json TEXT, timems INTEGER, block INTEGER)");
        }
        // Preserve history when upgrading directly from schema 1/2. Historical uncertainty
        // must be recorded as evidence metadata, never resolved by deleting the user's rows.
        if (oldV < 4) addMyTradeEvidenceColumns(db);
        if (oldV < 5) createPendingFills(db);
        if (oldV < 6) createHistoryProgress(db);
        if (oldV < 7) {
            addColumn(db, "pendingfill", "historical INTEGER NOT NULL DEFAULT 0");
            createVerifiedSpends(db);
        }
        if (oldV < 8) {
            addColumn(db, "verifiedspend", "sourcejson TEXT NOT NULL DEFAULT ''");
            createChainChecks(db);
        }
        if (oldV < 9) {
            createVerifiedSpends(db); // includes the recheck lookup index in this unreleased schema
            addColumn(db, "chaincheck", "proofepoch TEXT NOT NULL DEFAULT ''");
            addColumn(db, "chaincheck", "prooforder INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "tape", "settlement_kind TEXT NOT NULL DEFAULT 'TRADE'");
            createReceiptAudit(db);
        }
        if (oldV < 10) createTakerReceipts(db);
        if (oldV < 11) {
            addColumn(db,"takerreceipt","retry INTEGER NOT NULL DEFAULT 0");
            addColumn(db,"takerreceipt","needs_review INTEGER NOT NULL DEFAULT 0");
            createTakerReceipts(db);
            db.execSQL("UPDATE takerreceipt SET needs_review=1 WHERE EXISTS (SELECT 1 FROM chaincheck c WHERE c.txpowid=takerreceipt.txpowid AND (c.state='MISSING' OR (c.state='CURRENT' AND (c.block<>takerreceipt.block OR c.blockid<>takerreceipt.blockid COLLATE NOCASE))))");
        }
        if(oldV<12)createOwnerReceipts(db);
        if(oldV<13) {
            addColumn(db,"ownerreceipt","retry INTEGER NOT NULL DEFAULT 0");
            addColumn(db,"ownerreceipt","needs_review INTEGER NOT NULL DEFAULT 0");
            createOwnerReceipts(db);
            db.execSQL("UPDATE ownerreceipt SET needs_review=1 WHERE EXISTS (SELECT 1 FROM chaincheck c WHERE c.txpowid=ownerreceipt.txpowid AND (c.state='MISSING' OR (c.state='CURRENT' AND (c.block<>ownerreceipt.block OR c.blockid<>ownerreceipt.blockid COLLATE NOCASE))))");
        }
    }

    private static void createOwnerReceipts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ownerreceipt (receiptid TEXT PRIMARY KEY, outcome TEXT NOT NULL, txpowid TEXT NOT NULL COLLATE NOCASE, block INTEGER NOT NULL, blockid TEXT NOT NULL, blocktime INTEGER NOT NULL, recordedat INTEGER NOT NULL, json TEXT NOT NULL, retry INTEGER NOT NULL DEFAULT 0, needs_review INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS ownerreceipt_time ON ownerreceipt(recordedat,receiptid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS ownerreceipt_tx ON ownerreceipt(txpowid,receiptid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS ownerreceipt_review ON ownerreceipt(needs_review,retry,receiptid)");
    }
    /** Same archive/check-before-pending-clear protocol as recordTakerFill. */
    void recordOwnerReceipt(OwnerReceipt receipt) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            protectLatestProof(db,receipt.spend);
            org.json.JSONObject previous=storedRow(db,"ownerreceipt","receiptid",receipt.id);
            if(previous==null) {
                ContentValues v=new ContentValues();v.put("receiptid",receipt.id);v.put("outcome",receipt.outcome);v.put("txpowid",receipt.txpowid);
                v.put("block",receipt.block);v.put("blockid",receipt.blockid);v.put("blocktime",receipt.blockTimeMs);v.put("recordedat",receipt.recordedAt);v.put("json",receipt.json);
                db.insertOrThrow("ownerreceipt",null,v);
            }else if(!receipt.sameCompletion(previous.getString("json"))) {
                String original=previous.getString("json"),revised=receipt.revisionOf(original);
                org.json.JSONObject old=MakerConfig.storedObject(original),proof=old.getJSONObject("proof");
                String oldTx=previous.getString("txpowid");
                if(!oldTx.equalsIgnoreCase(proof.getString("txpowid"))||previous.getLong("block")!=MakerConfig.storedBlock(proof.get("block"))
                        ||!previous.getString("blockid").equalsIgnoreCase(proof.getString("blockid"))
                        ||!previous.getString("outcome").equals(old.getString("outcome"))
                        ||previous.getLong("recordedat")!=MakerConfig.storedBlock(old.get("recorded_at")))throw new ChainReview.Conflict();
                if(!oldTx.equalsIgnoreCase(receipt.txpowid)) {
                    try(Cursor c=db.rawQuery("SELECT state,proofepoch,prooforder FROM chaincheck WHERE txpowid=?",new String[]{oldTx})) {
                        FillSettler.Entry source=new FillSettler.Entry(receipt.spend.input.getString("coinid"),"",true);
                        if(!c.moveToFirst()||!ReceiptRepair.allowed(source,receipt.spend,oldTx,c.getString(0),c.getString(1),c.getLong(2)))throw new ChainReview.Conflict();
                    }
                }
                org.json.JSONObject snapshot=new org.json.JSONObject().put("previous_owner_receipt",previous)
                        .put("previous_check",storedRow(db,"chaincheck","txpowid",oldTx)).put("new_owner_receipt_json",revised);
                ContentValues audit=new ContentValues();audit.put("coinid","owner:"+receipt.id);audit.put("oldtxpowid",oldTx);audit.put("newtxpowid",receipt.txpowid);
                audit.put("reason","Owner operation rechecked against original intent and linked inclusion proof");audit.put("correctedat",System.currentTimeMillis());
                audit.put("snapshot",snapshot.toString());audit.put("summary",new org.json.JSONObject().put("owner_receipt_id",receipt.id).put("outcome",receipt.outcome).toString());
                db.insertOrThrow("receiptaudit",null,audit);
                ContentValues v=new ContentValues();v.put("outcome",receipt.outcome);v.put("txpowid",receipt.txpowid);v.put("block",receipt.block);v.put("blockid",receipt.blockid);
                v.put("blocktime",receipt.blockTimeMs);v.put("json",revised);
                if(db.update("ownerreceipt",v,"receiptid=? AND json=?",new String[]{receipt.id,original})!=1)throw new ChainReview.Conflict();
            }
            registerCheck(db,receipt.txpowid,receipt.block);adoptIncludedProof(db,receipt.spend);
            db.setTransactionSuccessful();
        }catch(org.json.JSONException invalid){throw new ChainReview.Conflict();}
        finally{db.endTransaction();}
    }
    List<OwnerReceipt.Entry> ownerReceipts(int limit) {return ownerReceipts(limit,null);}
    /** Resume by immutable time/identity, so new arrivals do not shift an older page. */
    List<OwnerReceipt.Entry> ownerReceipts(int limit,OwnerReceipt.Cursor after) {
        if(limit<1||limit>200)throw new IllegalArgumentException("Invalid owner history limit");
        List<OwnerReceipt.Entry> result=new ArrayList<>();
        String where=after==null?"":" WHERE (o.recordedat<? OR (o.recordedat=? AND o.receiptid>?))";
        String[] args=after==null?new String[]{String.valueOf(limit)}:new String[]{String.valueOf(after.recordedAt),String.valueOf(after.recordedAt),after.id,String.valueOf(limit)};
        try(Cursor c=getReadableDatabase().rawQuery("SELECT o.outcome,o.txpowid,o.block,o.blockid,o.blocktime,o.recordedat,COALESCE(c.state,'RECORDED'),COALESCE(c.depth,-1),COALESCE(c.checkedat,0),COALESCE(c.block,0),COALESCE(c.blockid,''),o.receiptid FROM ownerreceipt o LEFT JOIN chaincheck c ON c.txpowid=o.txpowid"+where+" ORDER BY o.recordedat DESC,o.receiptid LIMIT ?",args)) {
            while(c.moveToNext()) {
                OwnerReceipt.Entry row=new OwnerReceipt.Entry(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getLong(4),c.getLong(5),c.getString(6),c.getInt(7),c.getLong(8),c.getLong(9),c.getString(10));
                row.id=c.getString(11);result.add(row);
            }
        }
        return result;
    }
    private static final String OWNER_EXPORT_QUERY="SELECT o.json,COALESCE(c.state,'RECORDED'),COALESCE(c.depth,-1),COALESCE(c.block,0),COALESCE(c.blockid,''),COALESCE(c.checkedat,0),COALESCE(c.attemptedat,0),COALESCE(c.error,'') FROM ownerreceipt o LEFT JOIN chaincheck c ON c.txpowid=o.txpowid ORDER BY o.recordedat,o.receiptid";
    private static String ownerExportJson(Cursor c) throws org.json.JSONException {
        String original=c.getString(0);MakerConfig.storedObject(original); // Preserve the exact original bytes inside the export.
        org.json.JSONObject check=new org.json.JSONObject().put("state",c.getString(1)).put("depth",c.getInt(2)).put("block",c.getLong(3))
                .put("blockid",c.getString(4)).put("checked_at",c.getLong(5)).put("attempted_at",c.getLong(6)).put("error",c.getString(7));
        return new org.json.JSONObject().put("original_receipt_json",original).put("last_saved_node_check",check).toString();
    }
    private String ownerReceiptsJson() {
        org.json.JSONArray out=new org.json.JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery(OWNER_EXPORT_QUERY,null)) {
            while(c.moveToNext())out.put(new org.json.JSONObject(ownerExportJson(c)));
        }catch(org.json.JSONException malformed){throw new IllegalStateException("Owner evidence could not be exported",malformed);}
        return out.toString();
    }

    private static void createTakerReceipts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS takerreceipt (spentcoin TEXT PRIMARY KEY, txpowid TEXT NOT NULL COLLATE NOCASE, block INTEGER NOT NULL, blockid TEXT NOT NULL, json TEXT NOT NULL, retry INTEGER NOT NULL DEFAULT 0, needs_review INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS takerreceipt_tx ON takerreceipt(txpowid,spentcoin)");
        db.execSQL("CREATE INDEX IF NOT EXISTS takerreceipt_review ON takerreceipt(needs_review,retry,spentcoin)");
    }

    /** Persist host alternation so repeated Activity/service recreation cannot starve market recovery. */
    @Override public boolean claimOwnerTurn() {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            boolean take=true;
            try(Cursor c=db.rawQuery("SELECT v FROM meta WHERE k='owner_recovery_turn'",null)) {
                if(c.moveToFirst())take=!"OWNER".equals(c.getString(0));
            }
            ContentValues values=new ContentValues();values.put("k","owner_recovery_turn");values.put("v",take?"OWNER":"OTHER");
            if(db.insertWithOnConflict("meta",null,values,SQLiteDatabase.CONFLICT_REPLACE)==-1)
                throw new IllegalStateException("Recovery turn could not be saved");
            db.setTransactionSuccessful();return take;
        } finally {db.endTransaction();}
    }

    @Override public List<OwnerRecovery.Entry> ownerBatch(int limit) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            List<OwnerRecovery.Entry> out=new ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT receiptid,txpowid,block,blockid,json FROM ownerreceipt WHERE needs_review=1 ORDER BY retry,receiptid LIMIT ?",new String[]{String.valueOf(Math.max(1,Math.min(OwnerRecovery.LIMIT,limit)))})) {
                while(c.moveToNext())out.add(ownerEntry(c));
            }
            long retry;
            try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(retry),0)+1 FROM ownerreceipt WHERE needs_review=1",null)) {
                if(!c.moveToFirst())throw new IllegalStateException("Cannot order owner recovery");retry=c.getLong(0);
            }
            for(OwnerRecovery.Entry entry:out) {
                ContentValues values=new ContentValues();values.put("retry",retry++);
                db.update("ownerreceipt",values,"receiptid=?",new String[]{entry.id});
            }
            db.setTransactionSuccessful();return out;
        } finally {db.endTransaction();}
    }
    private static OwnerRecovery.Entry ownerEntry(Cursor c){return new OwnerRecovery.Entry(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4));}
    @Override public boolean repairOwner(OwnerRecovery.Entry entry,OwnerReceipt receipt) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT receiptid,txpowid,block,blockid,json FROM ownerreceipt WHERE receiptid=?",new String[]{entry.id})) {
                if(!c.moveToFirst()){db.setTransactionSuccessful();return false;}
                OwnerRecovery.Entry current=ownerEntry(c);
                if(!current.json.equals(entry.json)||!current.txpowid.equalsIgnoreCase(entry.txpowid)||current.block!=entry.block||!current.blockid.equalsIgnoreCase(entry.blockid)) {
                    db.setTransactionSuccessful();return false;
                }
            }
            if(!receipt.id.equals(entry.id)||!receipt.matchesIntent(entry.intent()))throw new ChainReview.Conflict();
            boolean changed=!receipt.sameCompletion(entry.json);
            recordOwnerReceipt(receipt);
            ContentValues done=new ContentValues();done.put("needs_review",0);
            db.update("ownerreceipt",done,"receiptid=?",new String[]{entry.id});
            db.setTransactionSuccessful();return changed;
        }finally{db.endTransaction();}
    }

    /** Persist host alternation so repeated Activity/service recreation cannot starve market recovery. */
    @Override public boolean claimTakerTurn() {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            boolean take=true;
            try(Cursor c=db.rawQuery("SELECT v FROM meta WHERE k='aggregate_recovery_turn'",null)) {
                if(c.moveToFirst())take=!"TAKER".equals(c.getString(0));
            }
            ContentValues values=new ContentValues();values.put("k","aggregate_recovery_turn");values.put("v",take?"TAKER":"MARKET");
            if(db.insertWithOnConflict("meta",null,values,SQLiteDatabase.CONFLICT_REPLACE)==-1)
                throw new IllegalStateException("Recovery turn could not be saved");
            db.setTransactionSuccessful();return take;
        } finally {db.endTransaction();}
    }

    @Override public List<TakerRecovery.Entry> takerBatch(int limit) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            List<TakerRecovery.Entry> out=new ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT spentcoin,txpowid,block,blockid,json FROM takerreceipt WHERE needs_review=1 ORDER BY retry,spentcoin LIMIT ?",new String[]{String.valueOf(Math.max(1,Math.min(TakerRecovery.LIMIT,limit)))})) {
                while(c.moveToNext())out.add(takerEntry(c));
            }
            long retry;
            try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(retry),0)+1 FROM takerreceipt WHERE needs_review=1",null)) {
                if(!c.moveToFirst())throw new IllegalStateException("Cannot order taker recovery");retry=c.getLong(0);
            }
            for(TakerRecovery.Entry entry:out) {
                ContentValues values=new ContentValues();values.put("retry",retry++);
                db.update("takerreceipt",values,"spentcoin=?",new String[]{entry.coinid});
            }
            db.setTransactionSuccessful();return out;
        } finally {db.endTransaction();}
    }
    private static TakerRecovery.Entry takerEntry(Cursor c) {
        return new TakerRecovery.Entry(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4));
    }
    private static TakerRecovery.Entry savedTaker(SQLiteDatabase db,String source) {
        try(Cursor c=db.rawQuery("SELECT spentcoin,txpowid,block,blockid,json FROM takerreceipt WHERE spentcoin=?",new String[]{source})) {
            return c.moveToFirst()?takerEntry(c):null;
        }
    }
    private static ContentValues takerValues(TakerReceipt receipt) {
        ContentValues values=new ContentValues();values.put("spentcoin",receipt.intent.sources.get(0));
        values.put("txpowid",receipt.spend.txpowid);values.put("block",receipt.spend.inclusionBlock);
        values.put("blockid",receipt.spend.inclusionBlockId);values.put("json",receipt.json);return values;
    }

    /** Compare retained bytes and ordered fresh proof before archiving/updating an aggregate row. */
    @Override public boolean repairTaker(TakerRecovery.Entry entry,TakerReceipt receipt) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            TakerRecovery.Entry current=savedTaker(db,entry.coinid);
            if(current==null || !current.json.equals(entry.json) || !current.txpowid.equalsIgnoreCase(entry.txpowid)
                    || current.block!=entry.block || !current.blockid.equalsIgnoreCase(entry.blockid)) {
                db.setTransactionSuccessful();return false; // another accepted repair already changed this snapshot
            }
            TakerReceipt.Intent original=entry.intent();DexHistory.Spend spend=receipt.spend;
            if(!original.same(receipt.intent) || !receipt.sameIntent(entry.json))throw new ChainReview.Conflict();
            protectLatestProof(db,spend);
            if(!entry.txpowid.equalsIgnoreCase(spend.txpowid)) {
                try(Cursor c=db.rawQuery("SELECT state,proofepoch,prooforder FROM chaincheck WHERE txpowid=?",new String[]{entry.txpowid})) {
                    FillSettler.Entry source=new FillSettler.Entry(entry.coinid,"",true);
                    if(!c.moveToFirst() || !ReceiptRepair.allowed(source,spend,entry.txpowid,c.getString(0),c.getString(1),c.getLong(2)))
                        throw new ChainReview.Conflict();
                }
            }
            org.json.JSONObject row=storedRow(db,"mytrade","spentcoin",entry.coinid);
            if(row==null || row.optInt("maker",-1)!=0 || !entry.txpowid.equalsIgnoreCase(row.optString("txpowid"))
                    || original.price.compareTo(new BigDecimal(row.getString("price")))!=0
                    || original.minima.compareTo(new BigDecimal(row.getString("size")))!=0
                    || original.buy!=(row.getInt("buy")==1))throw new ChainReview.Conflict();
            boolean changed=!entry.txpowid.equalsIgnoreCase(spend.txpowid)
                    || ReceiptRepair.moved(entry.block,entry.blockid,spend.inclusionBlock,spend.inclusionBlockId)
                    || row.getLong("timems")!=spend.inclusionTimeMs || row.getLong("verified_block")!=spend.inclusionBlock
                    || row.getLong("block")!=spend.inclusionBlock
                    || !original.sourceKind.equals(row.optString("source_kind"))
                    || !android.text.TextUtils.join(" ",original.sources).equalsIgnoreCase(row.optString("source_coinids"));
            if(changed) {
                archiveTakerRepair(db,entry,receipt,row);
                ContentValues values=new ContentValues();values.put("txpowid",spend.txpowid);
                values.put("timems",spend.inclusionTimeMs);values.put("block",spend.inclusionBlock);values.put("verified_block",spend.inclusionBlock);
                values.put("source_kind",original.sourceKind);values.put("source_coinids",android.text.TextUtils.join(" ",original.sources));
                String note=row.optString("verification_note","");
                for(String suffix:new String[]{ChainEvidence.BLOCK_TIME_NOTE,ChainEvidence.OBSERVED_TIME_NOTE})
                    if(note.endsWith(suffix)) {note=note.substring(0,note.length()-suffix.length());break;}
                values.put("verification_note",note+ChainEvidence.BLOCK_TIME_NOTE);
                if(db.update("mytrade",values,"spentcoin=? AND maker=0",new String[]{entry.coinid})!=1)throw new ChainReview.Conflict();
                if(db.update("takerreceipt",takerValues(receipt),"spentcoin=? AND json=?",new String[]{entry.coinid,entry.json})!=1)
                    throw new ChainReview.Conflict();
            }
            registerCheck(db,spend.txpowid,spend.inclusionBlock);adoptIncludedProof(db,spend);
            ContentValues done=new ContentValues();done.put("needs_review",0);
            db.update("takerreceipt",done,"spentcoin=?",new String[]{entry.coinid});
            db.setTransactionSuccessful();return changed;
        } catch(org.json.JSONException invalid) {throw new ChainReview.Conflict();}
        finally {db.endTransaction();}
    }
    private static void archiveTakerRepair(SQLiteDatabase db,TakerRecovery.Entry entry,TakerReceipt receipt,org.json.JSONObject row) throws org.json.JSONException {
        org.json.JSONObject snapshot=new org.json.JSONObject().put("personal_trade",row)
                .put("previous_taker_receipt",new org.json.JSONObject(entry.json))
                .put("previous_check",storedRow(db,"chaincheck","txpowid",entry.txpowid))
                .put("new_taker_receipt",new org.json.JSONObject(receipt.json));
        org.json.JSONObject brief=new org.json.JSONObject().put("public_trade",new org.json.JSONObject()
                .put("timems",row.opt("timems")).put("size",row.opt("size")).put("price",row.opt("price")));
        ContentValues values=new ContentValues();values.put("coinid",entry.coinid);values.put("oldtxpowid",entry.txpowid);
        values.put("newtxpowid",receipt.spend.txpowid);values.put("reason","Aggregate taker receipt rechecked against all selected sources and expected payout");
        values.put("correctedat",System.currentTimeMillis());values.put("snapshot",snapshot.toString());values.put("summary",brief.toString());
        db.insertOrThrow("receiptaudit",null,values);
    }

    private static void createVerifiedSpends(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS verifiedspend (coinid TEXT PRIMARY KEY, txpowid TEXT NOT NULL, blockid TEXT NOT NULL, block INTEGER NOT NULL, sourcejson TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS verifiedspend_tx ON verifiedspend(txpowid COLLATE NOCASE,coinid)");
    }

    @Override public boolean known(String coinid, String txpowid) {
        SQLiteDatabase db = getReadableDatabase();
        if (exists(db, "pendingfill", "coinid", coinid)) return true;
        try (Cursor c = db.rawQuery("SELECT v.txpowid,v.block,v.blockid,c.state,c.block,c.blockid FROM verifiedspend v LEFT JOIN chaincheck c ON c.txpowid=v.txpowid WHERE v.coinid=?", new String[]{coinid})) {
            if (!c.moveToFirst() || !txpowid.equalsIgnoreCase(c.getString(0))) return false;
            // A legacy ledger without source JSON can still be recovered when history rediscovers
            // the actual input. Do not deduplicate away a known change of inclusion block.
            return !ChainReview.CURRENT.equals(c.getString(3))
                    || !ReceiptRepair.moved(c.getLong(1),c.getString(2),c.getLong(4),c.getString(5));
        }
    }

    private static void ensureSameSpender(SQLiteDatabase db, String coinid, String txpowid) {
        try (Cursor c = db.rawQuery("SELECT txpowid FROM verifiedspend WHERE coinid=?", new String[]{coinid})) {
            if (c.moveToFirst() && !txpowid.equalsIgnoreCase(c.getString(0)))
                throw new ChainReview.Conflict();
        }
    }

    @Override public void retire(FillSettler.Entry entry, DexHistory.Spend spend) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureSameSpender(db, entry.coinid, spend.txpowid);
            if (!exists(db,"pendingfill","coinid",entry.coinid) && exists(db,"verifiedspend","coinid",entry.coinid)) {
                db.setTransactionSuccessful(); return;
            }
            saveWinner(db, entry, spend);
            db.delete("pendingfill", "coinid=?", new String[]{entry.coinid});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void saveWinner(SQLiteDatabase db, FillSettler.Entry entry, DexHistory.Spend spend) {
            protectLatestProof(db,spend);
            ContentValues values = new ContentValues();
            values.put("coinid", entry.coinid); values.put("txpowid", spend.txpowid);
            values.put("blockid", spend.inclusionBlockId); values.put("block", spend.inclusionBlock);
            values.put("sourcejson", entry.json);
            if (db.insertWithOnConflict("verifiedspend", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                throw new IllegalStateException("Verified-spend record was not saved");
            registerCheck(db, spend.txpowid, spend.inclusionBlock);
            adoptIncludedProof(db,spend);
    }

    private static void protectLatestProof(SQLiteDatabase db,DexHistory.Spend spend) {
        try (Cursor c=db.rawQuery("SELECT state,proofepoch,prooforder,blockid FROM chaincheck WHERE txpowid=?",new String[]{spend.txpowid})) {
            if(c.moveToFirst() && ReceiptRepair.superseded(spend,c.getString(0),c.getString(1),c.getLong(2),c.getString(3)))
                throw new ChainReview.Conflict();
        }
    }
    private static long nextCheckRevision(SQLiteDatabase db) {
        try (Cursor c=db.rawQuery("SELECT COALESCE(MAX(revision),0)+1 FROM chaincheck",null)) {
            if(!c.moveToFirst()) throw new IllegalStateException("Cannot order confirmation checks");
            return c.getLong(0);
        }
    }
    private static void adoptIncludedProof(SQLiteDatabase db,DexHistory.Spend spend) {
        if(spend.confirmations<0 || spend.inclusionBlock<=0 || !FundingCoins.hex(spend.inclusionBlockId)) return;
        try(Cursor c=db.rawQuery("SELECT proofepoch,prooforder FROM chaincheck WHERE txpowid=?",new String[]{spend.txpowid})) {
            if(!c.moveToFirst() || !ReceiptRepair.shouldAdopt(spend,c.getString(0),c.getLong(1))) return;
        }
        ContentValues values=new ContentValues();
        values.put("state",ChainReview.CURRENT); values.put("depth",spend.confirmations);
        values.put("block",spend.inclusionBlock); values.put("blockid",spend.inclusionBlockId);
        values.put("proofepoch",ChainEvidence.PROOF_EPOCH); values.put("prooforder",spend.proofOrder);
        values.put("checkedat",spend.proofTimeMs); values.put("attemptedat",spend.proofTimeMs); values.put("error","");
        values.put("revision",nextCheckRevision(db));
        if(db.update("chaincheck",values,"txpowid=?",new String[]{spend.txpowid})>0) {
            queueTakerReview(db,spend.txpowid,ChainReview.CURRENT,spend.inclusionBlock,spend.inclusionBlockId);
            queueOwnerReview(db,spend.txpowid,ChainReview.CURRENT,spend.inclusionBlock,spend.inclusionBlockId);
        }
    }

    private static void createReceiptAudit(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS receiptaudit (revision INTEGER PRIMARY KEY AUTOINCREMENT, coinid TEXT NOT NULL, oldtxpowid TEXT NOT NULL, newtxpowid TEXT NOT NULL, reason TEXT NOT NULL, correctedat INTEGER NOT NULL, snapshot TEXT NOT NULL, summary TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS receiptaudit_source ON receiptaudit(coinid,revision)");
    }

    private enum Correction { NONE, SPENDER, INCLUSION }

    /** Preserve the original rows before any correction, and authorize against ordered proof. */
    private Correction prepareCorrection(SQLiteDatabase db, FillSettler.Entry entry, DexHistory.Spend spend) {
        protectLatestProof(db,spend);
        String previous = ""; boolean ledger = false, moved = false;
        try (Cursor c = db.rawQuery("SELECT txpowid,block,blockid FROM verifiedspend WHERE coinid=?", new String[]{entry.coinid})) {
            if (c.moveToFirst()) {
                previous = c.getString(0); ledger = true;
                moved = previous.equalsIgnoreCase(spend.txpowid)
                        && (c.getLong(1) != spend.inclusionBlock || !c.getString(2).equalsIgnoreCase(spend.inclusionBlockId));
            }
        }
        if (ledger && previous.equalsIgnoreCase(spend.txpowid) && !moved) return Correction.NONE;
        boolean oldRows = exists(db,"tape","spentcoin",entry.coinid) || hasMakerReceipt(db,entry.coinid);
        if (!ledger && !oldRows) return Correction.NONE;
        if (!ledger) {
            try (Cursor c = db.rawQuery("SELECT txpowid FROM mytrade WHERE " + MAKER_ROW, new String[]{entry.coinid})) {
                if (c.moveToFirst() && c.getString(0) != null) previous = c.getString(0);
            }
        }
        if (!ReceiptRepair.usable(entry, spend)) throw new ChainReview.Conflict();
        if (FundingCoins.hex(previous) && !previous.equalsIgnoreCase(spend.txpowid)) {
            try (Cursor c = db.rawQuery("SELECT state,proofepoch,prooforder FROM chaincheck WHERE txpowid=?", new String[]{previous})) {
                if (!c.moveToFirst() || !ReceiptRepair.allowed(entry,spend,previous,c.getString(0),c.getString(1),c.getLong(2)))
                    throw new ChainReview.Conflict();
            }
        }
        archiveCorrection(db,entry,spend,previous,moved ? "Same TxPoW included in a different block"
                : ledger ? "Different included spender" : "First linked proof corrects a legacy record");
        // The old proof is already archived inside this transaction. Nested writers now see
        // the new winner; a failure rolls back the archive, rows, winner and queue together.
        saveWinner(db,entry,spend);
        return moved ? Correction.INCLUSION : Correction.SPENDER;
    }

    private static org.json.JSONObject storedRow(SQLiteDatabase db,String table,String key,String id) throws org.json.JSONException {
        try (Cursor c = db.rawQuery("SELECT * FROM "+table+" WHERE "+key+"=?",new String[]{id})) {
            if (!c.moveToFirst()) return null;
            org.json.JSONObject row = new org.json.JSONObject();
            for (int i=0;i<c.getColumnCount();i++) {
                Object value;
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL: value=org.json.JSONObject.NULL; break;
                    case Cursor.FIELD_TYPE_INTEGER: value=c.getLong(i); break;
                    case Cursor.FIELD_TYPE_FLOAT: value=c.getDouble(i); break;
                    default: value=c.getString(i);
                }
                row.put(c.getColumnName(i),value);
            }
            return row;
        }
    }
    private static void archiveCorrection(SQLiteDatabase db,FillSettler.Entry entry,DexHistory.Spend spend,String previous,String reason) {
        try {
            org.json.JSONObject snapshot = new org.json.JSONObject()
                    .put("ledger",storedRow(db,"verifiedspend","coinid",entry.coinid))
                    .put("public_trade",storedRow(db,"tape","spentcoin",entry.coinid))
                    .put("personal_trade",storedRow(db,"mytrade","spentcoin",entry.coinid))
                    .put("personal_maker_receipt",hasMakerReceipt(db,entry.coinid))
                    .put("previous_check",storedRow(db,"chaincheck","txpowid",previous))
                    .put("new_input",spend.input).put("new_outputs",spend.outputs).put("new_state",spend.transactionState)
                    .put("new_block",spend.inclusionBlock).put("new_blockid",spend.inclusionBlockId)
                    .put("new_block_time",spend.inclusionTimeMs).put("new_confirmations",spend.confirmations)
                    .put("new_proof_epoch",ChainEvidence.PROOF_EPOCH).put("new_proof_order",spend.proofOrder);
            ContentValues values = new ContentValues();
            values.put("coinid",entry.coinid); values.put("oldtxpowid",previous); values.put("newtxpowid",spend.txpowid);
            values.put("reason",reason); values.put("correctedat",System.currentTimeMillis()); values.put("snapshot",snapshot.toString());
            org.json.JSONObject original = snapshot.optJSONObject("personal_trade");
            if (original == null || original.optInt("maker",0) != 1) original = snapshot.optJSONObject("public_trade");
            org.json.JSONObject brief = new org.json.JSONObject();
            if (original != null) brief.put("public_trade", new org.json.JSONObject()
                    .put("timems", original.opt("timems")).put("size", original.opt("size")).put("price", original.opt("price")));
            values.put("summary", brief.toString());
            db.insertOrThrow("receiptaudit",null,values);
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("Correction archive was not saved",invalid); }
    }

    /** Complete settlement precedes notifications; correction and source retirement cannot split. */
    public boolean completeFill(FillSettler.Entry entry, DexHistory.Spend spend, Order5 order,
                                long timeMs,long block,BigDecimal price,BigDecimal size,boolean buy,
                                boolean partial,boolean mine,String evidence,String note) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Correction correction = prepareCorrection(db,entry,spend);
            if (correction == Correction.INCLUSION) updateInclusionTime(db,entry,spend);
            else if (correction == Correction.SPENDER) {
                db.update("tape",fillValues(entry.coinid,timeMs,block,price,size,buy,partial,mine),"spentcoin=?",new String[]{entry.coinid});
                if (mine) db.update("mytrade",myTradeValues(entry.coinid,timeMs,block,price,size,!order.sell,true,order.orderId,
                        spend.txpowid,"BOOK",entry.coinid,"",evidence,note,block),MAKER_ROW,new String[]{entry.coinid});
                else supersedePersonal(db,entry.coinid,"SUPERSEDED_UNATTRIBUTED","The source has a newly verified spend, but this wallet's current ownership was not established. Earlier record retained in Corrections.");
            }
            boolean isNew = recordVerifiedFill(entry.coinid,timeMs,block,price,size,buy,partial,mine,order,spend.txpowid,evidence,note);
            retire(entry,spend);
            db.setTransactionSuccessful();
            return isNew && correction == Correction.NONE; // A correction is not a new-fill notification.
        } finally { db.endTransaction(); }
    }

    public void completeNonTrade(FillSettler.Entry entry,DexHistory.Spend spend) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Correction correction=prepareCorrection(db,entry,spend);
            if (correction == Correction.SPENDER) {
                ContentValues values=new ContentValues(); values.put("settlement_kind","NONTRADE");
                db.update("tape",values,"spentcoin=?",new String[]{entry.coinid});
                supersedePersonal(db,entry.coinid,"SUPERSEDED_NONTRADE","The source was refunded or relocked in "+spend.txpowid+". Earlier trade evidence is retained in Corrections; this row is excluded from totals.");
            }
            noteCancelled(entry.coinid); retire(entry,spend); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Same transaction identity changes coordinates only, not economics or wallet attribution. */
    private static void updateInclusionTime(SQLiteDatabase db,FillSettler.Entry entry,DexHistory.Spend spend) {
        ContentValues coordinates=new ContentValues();
        coordinates.put("timems",spend.inclusionTimeMs); coordinates.put("block",spend.inclusionBlock);
        db.update("tape",coordinates,"spentcoin=?",new String[]{entry.coinid});
        String match=MAKER_ROW+" AND txpowid=? COLLATE NOCASE";
        String[] args={entry.coinid,spend.txpowid};
        try(Cursor c=db.rawQuery("SELECT verification_note FROM mytrade WHERE "+match,args)) {
            if(!c.moveToFirst()) return;
            String note=c.isNull(0)?"":c.getString(0);
            for(String suffix:new String[]{ChainEvidence.BLOCK_TIME_NOTE,ChainEvidence.OBSERVED_TIME_NOTE}) {
                if(note.endsWith(suffix)) {note=note.substring(0,note.length()-suffix.length());break;}
            }
            coordinates.put("verified_block",spend.inclusionBlock);
            coordinates.put("verification_note",note+ChainEvidence.BLOCK_TIME_NOTE);
            db.update("mytrade",coordinates,match,args);
        }
    }

    private static boolean hasMakerReceipt(SQLiteDatabase db,String coinid) {
        try(Cursor c=db.rawQuery("SELECT 1 FROM mytrade WHERE "+MAKER_ROW,new String[]{coinid})) { return c.moveToFirst(); }
    }

    private static void supersedePersonal(SQLiteDatabase db,String coinid,String status,String note) {
        ContentValues values=new ContentValues(); values.put("verification_status",status); values.put("verification_note",note);
        db.update("mytrade",values,MAKER_ROW,new String[]{coinid});
    }

    private static String correctionQuery(int limit,boolean personalOnly) {return "SELECT revision,coinid,oldtxpowid,newtxpowid,reason,correctedat,"+(personalOnly?"snapshot":"summary")+" FROM receiptaudit"+(personalOnly?" WHERE (EXISTS (SELECT 1 FROM mytrade WHERE spentcoin=receiptaudit.coinid) OR EXISTS (SELECT 1 FROM ownerreceipt WHERE 'owner:'||receiptid=receiptaudit.coinid))":"")+" ORDER BY revision DESC"+(limit>0?" LIMIT "+Math.min(limit,200):"");}
    private static org.json.JSONObject correctionJson(Cursor c) throws org.json.JSONException {
        return new org.json.JSONObject().put("revision",c.getLong(0)).put("coinid",c.getString(1))
                    .put("old_txpowid",c.getString(2)).put("new_txpowid",c.getString(3)).put("reason",c.getString(4))
                    .put("corrected_at",c.getLong(5)).put("evidence",new org.json.JSONObject(c.getString(6)));
    }
    private static final String TAKER_EXPORT_QUERY="SELECT t.json FROM takerreceipt t JOIN mytrade m ON m.spentcoin=t.spentcoin AND m.maker=0 ORDER BY m.timems,t.spentcoin";

    public String correctionArchive(int limit) { return correctionArchive(limit, false); }
    private String correctionArchive(int limit, boolean personalOnly) {
        org.json.JSONArray out=new org.json.JSONArray();
        try (Cursor c=getReadableDatabase().rawQuery(correctionQuery(limit,personalOnly),null)) {
            while(c.moveToNext()) out.put(correctionJson(c));
        } catch(org.json.JSONException invalid) { throw new IllegalStateException("Correction archive could not be read",invalid); }
        return out.toString();
    }

    /** Copy a coherent local snapshot to a streaming sink; no network or document-provider I/O. */
    void captureExport(TradeExport.Snapshot snapshot, TradeExportFiles.Sink sink) throws Exception {
        SQLiteDatabase db=getReadableDatabase();
        if(android.os.Build.VERSION.SDK_INT>=35) db.beginTransactionReadOnly();
        else db.beginTransactionNonExclusive(); // Older Android lacks the public read-only transaction API.
        try {
            try(Cursor c=db.rawQuery(tradeChecks()+" WHERE m.timems>=? AND m.timems<=? ORDER BY m.timems ASC",
                    new String[]{String.valueOf(snapshot.fromMs),String.valueOf(snapshot.toMs)})) {
                while(c.moveToNext())sink.trade(tradeRow(c));
            }
            try(Cursor c=db.rawQuery(correctionQuery(0,true),null)) {
                while(c.moveToNext())sink.correction(correctionJson(c).toString());
            }
            try(Cursor c=db.rawQuery(TAKER_EXPORT_QUERY,null)) {
                while(c.moveToNext())sink.taker(c.getString(0));
            }
            try(Cursor c=db.rawQuery(OWNER_EXPORT_QUERY,null)) {
                while(c.moveToNext())sink.owner(ownerExportJson(c));
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void loadExport(TradeExport.Snapshot snapshot) {
        SQLiteDatabase db=getReadableDatabase(); db.beginTransactionNonExclusive();
        try {
            snapshot.rows.addAll(myTradesAll(snapshot.fromMs,snapshot.toMs));
            snapshot.correctionsJson=correctionArchive(0, true);
            snapshot.takerReceiptsJson=takerReceiptsJson();
            snapshot.ownerReceiptsJson=ownerReceiptsJson();
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private String takerReceiptsJson() {
        org.json.JSONArray out=new org.json.JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery(TAKER_EXPORT_QUERY,null)) {
            while(c.moveToNext())out.put(new org.json.JSONObject(c.getString(0)));
        } catch(org.json.JSONException invalid) {throw new IllegalStateException("Saved taker evidence could not be read",invalid);}
        return out.toString();
    }

    private static void createChainChecks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chaincheck (txpowid TEXT PRIMARY KEY COLLATE NOCASE, state TEXT NOT NULL DEFAULT 'RECORDED', depth INTEGER NOT NULL DEFAULT -1, block INTEGER NOT NULL DEFAULT 0, blockid TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0, checkedat INTEGER NOT NULL DEFAULT 0, attemptedat INTEGER NOT NULL DEFAULT 0, proofepoch TEXT NOT NULL DEFAULT '', prooforder INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS chaincheck_rotation ON chaincheck(revision,txpowid)");
        // Existing maker and taker evidence enters the same repeat-check queue. A local handle
        // or malformed ID cannot become a node command. The original rows are untouched.
        db.execSQL("INSERT OR IGNORE INTO chaincheck(txpowid,block) SELECT LOWER(txpowid),block FROM verifiedspend WHERE substr(txpowid,1,2)='0x' AND length(txpowid) BETWEEN 4 AND 1026 AND length(txpowid)%2=0 AND substr(txpowid,3) NOT GLOB '*[^0-9a-fA-F]*'");
        db.execSQL("INSERT OR IGNORE INTO chaincheck(txpowid,block) SELECT LOWER(txpowid),COALESCE(verified_block,0) FROM mytrade WHERE substr(txpowid,1,2)='0x' AND length(txpowid) BETWEEN 4 AND 1026 AND length(txpowid)%2=0 AND substr(txpowid,3) NOT GLOB '*[^0-9a-fA-F]*'");
    }

    private static void registerCheck(SQLiteDatabase db, String txpowid, long block) {
        if (!FundingCoins.hex(txpowid)) return;
        ContentValues values = new ContentValues();
        values.put("txpowid", txpowid.toLowerCase(java.util.Locale.ROOT)); values.put("block", block);
        if (!exists(db, "chaincheck", "txpowid", txpowid)) db.insertOrThrow("chaincheck", null, values);
    }

    @Override public List<ChainReview.Entry> reviewBatch(long tip) {
        List<ChainReview.Entry> result = new ArrayList<>();
        // Four recent and four older proofs per pass: recent reorgs are checked promptly while
        // old receipts still rotate. Persisted revisions survive Activity/service restarts.
        long cutoff = Math.max(1, tip - 128);
        for (String comparison : new String[]{">=", "<"}) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT txpowid,revision FROM chaincheck WHERE block" + comparison + "? ORDER BY revision,txpowid LIMIT 4",
                    new String[]{String.valueOf(cutoff)})) {
                while (c.moveToNext()) result.add(new ChainReview.Entry(c.getString(0), c.getLong(1)));
            }
        }
        return result;
    }

    @Override public void reviewed(ChainReview.Entry entry, ChainReview.Evidence proof, long checkedAt) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            long revision = nextCheckRevision(db);
            values.put("revision", revision); values.put("attemptedat", checkedAt); values.put("error", proof.error);
            if (!proof.state.isEmpty()) {
                values.put("state", proof.state); values.put("depth", proof.depth); values.put("checkedat", checkedAt);
                values.put("proofepoch", ChainEvidence.PROOF_EPOCH); values.put("prooforder", proof.proofOrder);
                // A miss must preserve prior inclusion coordinates for recovery/audit.
                if (ChainReview.CURRENT.equals(proof.state)) { values.put("block", proof.block); values.put("blockid", proof.blockid); }
            }
            int accepted = db.update("chaincheck", values, "txpowid=? AND revision=?", new String[]{entry.txpowid, String.valueOf(entry.revision)});
            if (accepted > 0 && ChainReview.CURRENT.equals(proof.state)) queueMovedSources(db,entry.txpowid,proof);
            if (accepted > 0 && !proof.state.isEmpty()) {
                queueTakerReview(db,entry.txpowid,proof.state,proof.block,proof.blockid);
                queueOwnerReview(db,entry.txpowid,proof.state,proof.block,proof.blockid);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Indexed by transaction: normal passes do not scan/sort all completed taker history. */
    private static void queueTakerReview(SQLiteDatabase db,String txpowid,String state,long block,String blockid) {
        if(ChainReview.MISSING.equals(state)) {
            ContentValues values=new ContentValues();values.put("needs_review",1);
            db.update("takerreceipt",values,"txpowid=?",new String[]{txpowid});
        } else if(ChainReview.CURRENT.equals(state) && block>0 && FundingCoins.hex(blockid)) {
            db.execSQL("UPDATE takerreceipt SET needs_review=CASE WHEN block<>? OR blockid<>? COLLATE NOCASE THEN 1 ELSE 0 END WHERE txpowid=?",
                    new Object[]{block,blockid,txpowid});
        }
    }

    private static void queueOwnerReview(SQLiteDatabase db,String txpowid,String state,long block,String blockid) {
        if(ChainReview.MISSING.equals(state)) {
            ContentValues values=new ContentValues();values.put("needs_review",1);
            db.update("ownerreceipt",values,"txpowid=?",new String[]{txpowid});
        } else if(ChainReview.CURRENT.equals(state) && block>0 && FundingCoins.hex(blockid)) {
            db.execSQL("UPDATE ownerreceipt SET needs_review=CASE WHEN block<>? OR blockid<>? COLLATE NOCASE THEN 1 ELSE 0 END WHERE txpowid=?",
                    new Object[]{block,blockid,txpowid});
        }
    }

    /** Queue work in the same transaction as the accepted recheck, bounded per pass. */
    private static void queueMovedSources(SQLiteDatabase db,String txpowid,ChainReview.Evidence proof) {
        if (proof.block <= 0 || !FundingCoins.hex(proof.blockid)) return;
        try (Cursor c = db.rawQuery("SELECT v.coinid,v.sourcejson FROM verifiedspend v WHERE v.txpowid=? COLLATE NOCASE"
                + " AND (v.block<>? OR v.blockid<>? COLLATE NOCASE) AND v.sourcejson<>''"
                + " AND NOT EXISTS (SELECT 1 FROM pendingfill p WHERE p.coinid=v.coinid AND p.historical=1)"
                + " ORDER BY v.coinid LIMIT 32",new String[]{txpowid,String.valueOf(proof.block),proof.blockid})) {
            while(c.moveToNext()) enqueueRecord(db,c.getString(0),c.getString(1),true);
        }
    }

    private static void createHistoryProgress(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS historyprogress (scope INTEGER NOT NULL, coinid TEXT NOT NULL, offset INTEGER NOT NULL, PRIMARY KEY(scope,coinid))");
    }

    @Override public int offset(boolean relevant, java.util.Collection<String> coinids) {
        int offset = Integer.MAX_VALUE;
        SQLiteDatabase db = getReadableDatabase();
        for (String coinid : coinids) {
            try (Cursor c = db.rawQuery("SELECT offset FROM historyprogress WHERE scope=? AND coinid=?",
                    new String[]{relevant ? "1" : "0", coinid})) {
                if (!c.moveToFirst()) return 0;
                offset = Math.min(offset, Math.max(0, c.getInt(0)));
            }
        }
        return offset == Integer.MAX_VALUE ? 0 : offset;
    }

    @Override public void checkpoint(boolean relevant, java.util.Collection<String> unresolved,
                                     java.util.Collection<String> found, int nextOffset) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String coinid : unresolved) {
                ContentValues values = new ContentValues();
                values.put("scope", relevant ? 1 : 0); values.put("coinid", coinid);
                values.put("offset", Math.max(0, nextOffset));
                if (db.insertWithOnConflict("historyprogress", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new IllegalStateException("History progress was not saved");
            }
            for (String coinid : found) db.delete("historyprogress", "scope=? AND coinid=?",
                    new String[]{relevant ? "1" : "0", coinid});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void createPendingFills(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pendingfill (coinid TEXT PRIMARY KEY, json TEXT NOT NULL, retry INTEGER NOT NULL, historical INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS pendingfill_retry ON pendingfill(retry)");
    }

    // Durable, bounded-work recovery. No age or row-count deletion of unresolved candidates.
    @Override public void enqueue(Order5 order) { enqueue(order, false); }
    @Override public void enqueueHistorical(Order5 order) { enqueue(order, true); }

    private void enqueue(Order5 order, boolean historical) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            enqueueRecord(db,order.coinid,order.sourceJson(),historical);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void enqueueRecord(SQLiteDatabase db,String coinid,String json,boolean historical) {
        if (!exists(db,"pendingfill","coinid",coinid)) {
            ContentValues values=new ContentValues();
            values.put("coinid",coinid); values.put("json",json); values.put("retry",nextRetry(db));
            values.put("historical",historical ? 1 : 0); db.insertOrThrow("pendingfill",null,values);
        } else if (historical) {
            // A new inclusion time must be verified even when a live-book candidate was queued first.
            ContentValues values=new ContentValues(); values.put("historical",1);
            db.update("pendingfill",values,"coinid=?",new String[]{coinid});
        }
    }

    private static long nextRetry(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(retry),0)+1 FROM pendingfill", null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("Cannot order recovery queue");
            return c.getLong(0);
        }
    }

    @Override public List<FillSettler.Entry> batch(int limit) {
        List<FillSettler.Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT coinid,json,historical FROM pendingfill ORDER BY retry,coinid LIMIT ?",
                new String[]{String.valueOf(Math.max(1, Math.min(32, limit)))})) {
            while (c.moveToNext()) out.add(new FillSettler.Entry(c.getString(0), c.getString(1), c.getInt(2) == 1));
        }
        return out;
    }

    @Override public void remove(String coinid) {
        getWritableDatabase().delete("pendingfill", "coinid=?", new String[]{coinid});
    }

    @Override public void defer(List<String> coinids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long sequence = nextRetry(db);
            for (String coinid : coinids) {
                ContentValues values = new ContentValues(); values.put("retry", sequence++);
                db.update("pendingfill", values, "coinid=?", new String[]{coinid});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static boolean exists(SQLiteDatabase db, String table, String key, String id) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM " + table + " WHERE " + key + "=?", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    /** Public and personal records commit together. Replaying also repairs a missing personal
     * row when an older build committed only the public row before dying. Notifications follow commit. */
    public boolean recordVerifiedFill(String spentCoin, long timeMs, long block, BigDecimal price,
                                      BigDecimal size, boolean buy, boolean partial, boolean mine,
                                      Order5 order, String txpowid, String evidence, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureSameSpender(db, spentCoin, txpowid);
            boolean isNew = addFill(spentCoin, timeMs, block, price, size, buy, partial, mine);
            if (!isNew && !exists(db, "tape", "spentcoin", spentCoin))
                throw new IllegalStateException("Public fill was not saved");
            if (mine) {
                // Replay must preserve the original observation time instead of making an old
                // public fill look like a new personal trade after restart.
                long observedAt = timeMs, observedBlock = block;
                try (Cursor c = db.rawQuery("SELECT timems,block FROM tape WHERE spentcoin=?", new String[]{spentCoin})) {
                    if (c.moveToFirst()) { observedAt = c.getLong(0); observedBlock = c.getLong(1); }
                }
                String timeNote = note;
                if ((observedAt != timeMs || observedBlock != block) && timeNote != null)
                    timeNote = timeNote.replace(ChainEvidence.BLOCK_TIME_NOTE, ChainEvidence.OBSERVED_TIME_NOTE);
                boolean personalNew = addMyTrade(spentCoin, observedAt, observedBlock, price, size,
                        !order.sell, true, order.orderId, txpowid, "BOOK", spentCoin, "", evidence, timeNote, block);
                if (!personalNew && !exists(db, "mytrade", "spentcoin", spentCoin))
                    throw new IllegalStateException("Personal fill was not saved");
            }
            db.setTransactionSuccessful();
            return isNew;
        } finally { db.endTransaction(); }
    }

    private static void addMyTradeEvidenceColumns(SQLiteDatabase db) {
        addColumn(db, "mytrade", "txpowid TEXT");
        addColumn(db, "mytrade", "source_kind TEXT");
        addColumn(db, "mytrade", "source_coinids TEXT");
        addColumn(db, "mytrade", "proceeds_coinid TEXT");
        addColumn(db, "mytrade", "verification_status TEXT");
        addColumn(db, "mytrade", "verification_note TEXT");
        addColumn(db, "mytrade", "verified_block INTEGER");
    }

    private static void addColumn(SQLiteDatabase db, String table, String spec) {
        String column = spec.substring(0, spec.indexOf(' '));
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = c.getColumnIndexOrThrow("name");
            while (c.moveToNext()) if (column.equalsIgnoreCase(c.getString(name))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + spec);
    }

    // ---- cancels (shared by the Activity's and the service's FillTape) ----

    /** Record that THIS device spent the coin itself (cancel/relock), so neither tape
     *  instance mistakes its disappearance for a trade. */
    public void noteCancelled(String coinid) {
        ContentValues cv = new ContentValues();
        cv.put("coinid", coinid);
        cv.put("timems", System.currentTimeMillis());
        if (getWritableDatabase().insertWithOnConflict("cancelled", null, cv, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IllegalStateException("Cancellation history was not saved");
    }

    /** True if this coin was cancelled by us. Kept (not deleted) so BOTH the foreground and
     *  background tapes can consult it — they each diff the book independently. Rows older
     *  than a day are pruned since the coin is long gone by then. */
    public boolean wasCancelled(String coinid) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM cancelled WHERE coinid=?", new String[]{coinid})) {
            boolean hit = c.moveToFirst();
            prunedCancelled();   // unconditionally: pruning only on a HIT let the table grow
            return hit;          // without bound for a user who never cancels the same coin twice
        }
    }

    private long lastPruneMs = 0;

    private void prunedCancelled() {
        long now = System.currentTimeMillis();
        if (now - lastPruneMs < 60 * 60_000L) return;   // hourly is plenty for a daily cutoff
        lastPruneMs = now;
        getWritableDatabase().execSQL(
                "DELETE FROM cancelled WHERE timems < " + (now - 86_400_000L));
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
        ContentValues cv = fillValues(spentCoin, timeMs, block, price, size, buy, partial, mine);
        long r = getWritableDatabase().insertWithOnConflict("tape", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (r != -1) trimTape();
        return r != -1;
    }

    private static ContentValues fillValues(String spentCoin, long timeMs, long block, BigDecimal price,
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
        cv.put("settlement_kind", "TRADE");
        return cv;
    }

    private void trimTape() {
        getWritableDatabase().execSQL(
                "DELETE FROM tape WHERE spentcoin IN (SELECT spentcoin FROM tape ORDER BY timems DESC"
                        + " LIMIT -1 OFFSET " + TAPE_CAP + ")");
    }

    public List<Candles.Fill> fills(long sinceMs) {
        List<Candles.Fill> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.timems,t.price,t.size,t.buy" + tapeChecks() + " WHERE t.timems>=? AND t.settlement_kind='TRADE' AND (c.state IS NULL OR c.state<>'MISSING') ORDER BY t.timems ASC",
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
                "SELECT t.timems,t.price" + tapeChecks() + " WHERE t.settlement_kind='TRADE' AND (c.state IS NULL OR c.state<>'MISSING') ORDER BY t.timems DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            return new Object[]{c.getLong(0), new BigDecimal(c.getString(1))};
        }
    }

    /** Newest-first market records: [timems, price, size, buy, mine, repeat-check label]. */
    public List<Object[]> tapeRows(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.timems,t.price,t.size,t.buy,t.mine,c.state,c.depth,c.checkedat,t.settlement_kind" + tapeChecks() + " ORDER BY t.timems DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), new BigDecimal(c.getString(1)),
                        new BigDecimal(c.getString(2)), c.getInt(3) == 1, c.getInt(4) == 1,
                        "NONTRADE".equals(c.getString(8)) ? "Corrected: source refunded/relocked; earlier trade excluded from totals" : ChainReview.label(c.getString(5), c.getInt(6), c.getLong(7))});
            }
        }
        return out;
    }

    // ---- my trades ----

    public boolean addMyTrade(String spentCoin, long timeMs, long block, BigDecimal price,
                              BigDecimal size, boolean buy, boolean maker, String orderId) {
        return addMyTrade(spentCoin, timeMs, block, price, size, buy, maker, orderId,
                "", maker ? "BOOK" : "", spentCoin, "", maker ? "LOCAL_VERIFIED" : "LOCAL_VERIFIED",
                maker ? "Observed from owned order fill evidence" : "Observed from taker fill evidence",
                block);
    }

    public boolean addMyTrade(String spentCoin, long timeMs, long block, BigDecimal price,
                              BigDecimal size, boolean buy, boolean maker, String orderId,
                              String txpowid, String sourceKind, String sourceCoinids,
                              String proceedsCoinid, String verificationStatus,
                              String verificationNote, long verifiedBlock) {
        ContentValues cv = myTradeValues(spentCoin,timeMs,block,price,size,buy,maker,orderId,
                txpowid,sourceKind,sourceCoinids,proceedsCoinid,verificationStatus,verificationNote,verifiedBlock);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            boolean added = db.insertWithOnConflict("mytrade", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
            if (added) registerCheck(db, txpowid, verifiedBlock);
            db.setTransactionSuccessful(); return added;
        } finally { db.endTransaction(); }
    }

    /** Save the complete expectations/evidence in the same commit as the personal row and check. */
    boolean recordTakerFill(TakerReceipt receipt) {
        TakerReceipt.Intent intent=receipt.intent;DexHistory.Spend spend=receipt.spend;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            TakerRecovery.Entry previous=savedTaker(db,intent.sources.get(0));
            if(previous!=null) {
                repairTaker(previous,receipt);db.setTransactionSuccessful();return false;
            }
            boolean added=recordTakerFill(intent.sources,spend,intent.price,intent.minima,intent.buy,intent.sourceKind);
            db.insertOrThrow("takerreceipt",null,takerValues(receipt));
            db.setTransactionSuccessful();return added;
        } finally {db.endTransaction();}
    }

    /** Commit the verified taker row/check before its durable pending receipt may be cleared. */
    public boolean recordTakerFill(java.util.List<String> sources,DexHistory.Spend spend,BigDecimal price,
                                   BigDecimal minima,boolean buy,String sourceKind) {
        if (sources==null || sources.isEmpty() || !TakerEvidence.readyToRecord(spend,sources.get(0))
                || price==null || minima==null || price.signum()<=0 || minima.signum()<=0)
            throw new IllegalArgumentException("Incomplete taker receipt evidence");
        String source=sources.get(0); SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            protectLatestProof(db,spend);
            boolean added=addMyTrade(source,spend.inclusionTimeMs,spend.inclusionBlock,price,minima,buy,false,"",
                    spend.txpowid,sourceKind,android.text.TextUtils.join(" ",sources),"",FillSettler.CHAIN_VERIFIED,
                    "Transaction identity, all source inputs and expected payout matched"+ChainEvidence.BLOCK_TIME_NOTE,spend.inclusionBlock);
            if (!added) {
                try(Cursor c=db.rawQuery("SELECT maker,txpowid,price,size,buy,verified_block,timems,source_kind,source_coinids FROM mytrade WHERE spentcoin=?",new String[]{source})) {
                    if(!c.moveToFirst() || c.getInt(0)!=0 || !spend.txpowid.equalsIgnoreCase(c.getString(1))
                            || price.compareTo(new BigDecimal(c.getString(2)))!=0
                            || minima.compareTo(new BigDecimal(c.getString(3)))!=0 || buy!=(c.getInt(4)==1)
                            || spend.inclusionBlock!=c.getLong(5) || spend.inclusionTimeMs!=c.getLong(6)
                            || !sourceKind.equals(c.getString(7)) || !android.text.TextUtils.join(" ",sources).equalsIgnoreCase(c.getString(8)))
                        throw new ChainReview.Conflict();
                }
            }
            registerCheck(db,spend.txpowid,spend.inclusionBlock); adoptIncludedProof(db,spend);
            db.setTransactionSuccessful(); return added;
        } finally {db.endTransaction();}
    }

    private static ContentValues myTradeValues(String spentCoin, long timeMs, long block, BigDecimal price,
                                               BigDecimal size, boolean buy, boolean maker, String orderId,
                                               String txpowid, String sourceKind, String sourceCoinids,
                                               String proceedsCoinid, String verificationStatus,
                                               String verificationNote, long verifiedBlock) {
        ContentValues cv = new ContentValues();
        cv.put("spentcoin", spentCoin);
        cv.put("timems", timeMs);
        cv.put("block", block);
        cv.put("price", price.toPlainString());
        cv.put("size", size.toPlainString());
        cv.put("buy", buy ? 1 : 0);
        cv.put("maker", maker ? 1 : 0);
        cv.put("orderid", orderId);
        cv.put("txpowid", txpowid == null ? "" : txpowid);
        cv.put("source_kind", sourceKind == null ? "" : sourceKind);
        cv.put("source_coinids", sourceCoinids == null ? "" : sourceCoinids);
        cv.put("proceeds_coinid", proceedsCoinid == null ? "" : proceedsCoinid);
        cv.put("verification_status", verificationStatus == null ? "" : verificationStatus);
        cv.put("verification_note", verificationNote == null ? "" : verificationNote);
        cv.put("verified_block", verifiedBlock);
        return cv;
    }

    /** Original rows stay visible, with derived recheck status as element six. */
    public List<Object[]> myTrades(int limit) {
        List<Object[]> out = new ArrayList<>();
        for (TradeExport.TradeRow row : myTradeRows(limit)) out.add(new Object[]{row.timeMs, row.price,
                row.sizeMinima, row.buy, row.maker, row.orderId, row.verificationStatus});
        return out;
    }

    private static String tapeChecks() {
        return " FROM tape t LEFT JOIN verifiedspend v ON v.coinid=t.spentcoin"
                + " LEFT JOIN mytrade m ON m.spentcoin=t.spentcoin AND m.maker=1"
                + " LEFT JOIN chaincheck c ON c.txpowid=LOWER(COALESCE(v.txpowid,m.txpowid))";
    }
    private static String tradeChecks() {
        return "SELECT m.spentcoin,m.timems,m.block,m.price,m.size,m.buy,m.maker,m.orderid,"
                + "m.txpowid,m.source_kind,m.source_coinids,m.proceeds_coinid,"
                + "m.verification_status,m.verification_note,m.verified_block,c.state,c.depth,c.checkedat,c.error,COALESCE(t.needs_review,0)"
                + " FROM mytrade m LEFT JOIN chaincheck c ON c.txpowid=LOWER(m.txpowid)"
                + " LEFT JOIN takerreceipt t ON t.spentcoin=m.spentcoin AND m.maker=0";
    }

    /** Newest-first personal trade rows with evidence fields for the TRADES tab. */
    public List<TradeExport.TradeRow> myTradeRows(int limit) {
        List<TradeExport.TradeRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                tradeChecks() + " ORDER BY m.timems DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(tradeRow(c));
        }
        return out;
    }

    /** Chronological original personal records, including visibly unresolved repeat checks. */
    public List<TradeExport.TradeRow> myTradesAll(long fromMs, long toMs) {
        List<TradeExport.TradeRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                tradeChecks() + " WHERE m.timems>=? AND m.timems<=? ORDER BY m.timems ASC",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)})) {
            while (c.moveToNext()) out.add(tradeRow(c));
        }
        return out;
    }

    private static TradeExport.TradeRow tradeRow(Cursor c) {
        TradeExport.TradeRow original = new TradeExport.TradeRow(c.getString(0), c.getLong(1), c.getLong(2),
                new BigDecimal(c.getString(3)), new BigDecimal(c.getString(4)),
                c.getInt(5) == 1, c.getInt(6) == 1, c.getString(7),
                c.getString(8), c.getString(9), c.getString(10), c.getString(11),
                c.getString(12), c.getString(13), c.getLong(14));
        TradeExport.TradeRow checked=ChainReview.decorate(original,c.getString(15),c.getInt(16),c.getLong(17),c.getString(18));
        return c.getInt(19)==1?ChainReview.aggregatePending(checked):checked;
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

    /** Every coin THIS device cancelled, in one query. Used to keep the cold-start snapshot
     *  from painting orders we already know are spent — a per-coin lookup would be hundreds
     *  of queries on the main thread at launch. */
    public java.util.Set<String> cancelledIds() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT coinid FROM cancelled", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception ignore) {}
        return out;
    }
}

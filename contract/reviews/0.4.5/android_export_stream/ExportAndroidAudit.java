package com.eurobuddha.pandadex;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Synthetic local database/document fixtures. No node, network or production Activity. */
public final class ExportAndroidAudit extends Instrumentation {
    private int checks;private DexDb store;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        snapshot();saveDestination();
        result.putString("stream","PASS "+checks+" assertions\nWAL/FULL, coherent concurrent snapshot, streamed archive, failure release, safe document writes\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(store!=null)store.close();}}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private String scalar(String sql){try(Cursor c=store.getReadableDatabase().rawQuery(sql,null)){return c.moveToFirst()?c.getString(0):null;}}
    private void add(SQLiteDatabase db,String coin,String price){db.execSQL("INSERT INTO mytrade(spentcoin,timems,block,price,size,buy,maker,orderid,txpowid,source_kind,source_coinids,proceeds_coinid,verification_status,verification_note,verified_block) VALUES(?,100,10,?,'3',1,0,'','0xee','BOOK',?,'','CHAIN_VERIFIED','original',10)",new Object[]{coin,price,coin});}
    private void archive(SQLiteDatabase db,String json){db.execSQL("INSERT INTO receiptaudit(coinid,oldtxpowid,newtxpowid,reason,correctedat,snapshot,summary) VALUES('0xaa','0xee','0xff','audit',100,?,?)",new Object[]{json,json});}
    private void snapshot() throws Exception {
        getTargetContext().deleteDatabase("pandadex.db");store=new DexDb(getTargetContext());SQLiteDatabase db=store.getWritableDatabase();
        check("wal".equalsIgnoreCase(scalar("PRAGMA journal_mode")),"WAL enabled");check("2".equals(scalar("PRAGMA synchronous")),"FULL synchronization explicit");
        add(db,"0xaa","2");archive(db,"{\"before\":1}");
        db.execSQL("INSERT INTO takerreceipt(spentcoin,txpowid,block,blockid,json) VALUES('0xaa','0xee',10,'0xab','{\"before\":1}')");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        List<TradeExport.TradeRow> rows=new ArrayList<>();List<String> corrections=new ArrayList<>(),takers=new ArrayList<>();
        ExecutorService reader=Executors.newSingleThreadExecutor(),writer=Executors.newSingleThreadExecutor();
        Future<?> capture=reader.submit(()->{try{store.captureExport(new TradeExport.Snapshot(),new TradeExportFiles.Sink(){
            public void trade(TradeExport.TradeRow row) throws Exception{rows.add(row);entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("snapshot release timeout");}
            public void correction(String json){corrections.add(json);}
            public void taker(String json){takers.add(json);}
        });}catch(Exception e){throw new RuntimeException(e);}});
        try {
            check(entered.await(5,TimeUnit.SECONDS),"snapshot pinned before concurrent writer");
            Future<?> write=writer.submit(()->{db.beginTransactionNonExclusive();try{
                db.execSQL("UPDATE mytrade SET price='9' WHERE spentcoin='0xaa'");add(db,"0xbb","4");archive(db,"{\"after\":2}");
                db.execSQL("UPDATE takerreceipt SET json='{\"after\":2}' WHERE spentcoin='0xaa'");db.setTransactionSuccessful();
            }finally{db.endTransaction();}});
            write.get(3,TimeUnit.SECONDS);check(!capture.isDone(),"receipt writer commits while export snapshot remains open");
            release.countDown();capture.get(5,TimeUnit.SECONDS);
            check(rows.size()==1&&rows.get(0).price.toPlainString().equals("2"),"snapshot excludes concurrent inserted/updated trades");
            check(corrections.size()==1&&new JSONObject(corrections.get(0)).getJSONObject("evidence").has("before"),"corrections share the same old snapshot");
            check(takers.size()==1&&new JSONObject(takers.get(0)).has("before"),"taker archive shares the same old snapshot");
            check("2".equals(scalar("SELECT COUNT(*) FROM mytrade"))&&"9".equals(scalar("SELECT price FROM mytrade WHERE spentcoin='0xaa'")),"new committed receipts remain in live database");
        }finally{release.countDown();reader.shutdown();writer.shutdown();check(reader.awaitTermination(5,TimeUnit.SECONDS)&&writer.awaitTermination(5,TimeUnit.SECONDS),"snapshot test workers stopped");}
        TradeExport.Snapshot expected=new TradeExport.Snapshot();store.loadExport(expected);TradeExport.Report report=TradeExport.build(expected);
        TradeExport.Snapshot metadata=new TradeExport.Snapshot();TradeExportFiles.Prepared prepared=TradeExportFiles.prepare(getTargetContext().getCacheDir(),metadata,store::captureExport,null);
        try {
            Map<String,String> contents=new HashMap<>();
            try(java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new FileInputStream(prepared.zip))){java.util.zip.ZipEntry e;while((e=in.getNextEntry())!=null){ByteArrayOutputStream b=new ByteArrayOutputStream();TradeExportFiles.copy(in,b);contents.put(e.getName(),b.toString("UTF-8"));}}
            check(contents.size()==6&&prepared.report.tradeCount==2&&metadata.rows.isEmpty(),"real database streams six complete files without filling metadata rows");
            check(report.tradesCsv.equals(contents.get(TradeExport.FILE_TRADES)),"actual SQLite streamed CSV matches prior report");
            check(report.verificationCsv.equals(contents.get(TradeExport.FILE_VERIFICATION)),"actual SQLite streamed verification matches prior report");
            check(new JSONArray(contents.get(TradeExport.FILE_CORRECTIONS)).length()==2&&new JSONArray(contents.get(TradeExport.FILE_TAKER_RECEIPTS)).length()==1,"all current correction and taker evidence retained");
        }finally{File folder=prepared.directory;prepared.close();check(!folder.exists(),"prepared artifact cleaned up");}
        boolean failed=false;try{store.captureExport(new TradeExport.Snapshot(),new TradeExportFiles.Sink(){public void trade(TradeExport.TradeRow r)throws Exception{throw new IOException("injected file write failure");}public void correction(String s){}public void taker(String s){}});}catch(IOException expectedFailure){failed=true;}
        check(failed&&!db.inTransaction(),"failed spool write releases snapshot transaction");
        add(db,"0xcc","5");check("3".equals(scalar("SELECT COUNT(*) FROM mytrade")),"receipt writes continue after export failure");
    }
    public static final class SaveProvider extends ContentProvider {
        static volatile String mode;
        public boolean onCreate(){return true;}
        public ParcelFileDescriptor openFile(Uri uri,String requested)throws FileNotFoundException {
            mode=requested;
            if(uri.getPath().equals("/fail"))throw new FileNotFoundException("injected provider failure");
            return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),"document-target"),ParcelFileDescriptor.parseMode(requested));
        }
        public String getType(Uri uri){return "application/zip";}
        public Cursor query(Uri uri,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
        public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
        public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
        public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }
    private void saveDestination()throws Exception {
        File source=new File(getTargetContext().getCacheDir(),"source-export"),target=new File(getTargetContext().getCacheDir(),"document-target"),privateRecord=new File(getTargetContext().getFilesDir(),"private-record");
        byte[] payload="complete export bytes".getBytes(StandardCharsets.UTF_8),original="private funds receipt".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(),payload);Files.write(target.toPath(),new byte[1024]);Files.write(privateRecord.toPath(),original);
        try {
            check(!TradeExportWriter.writeTo(getTargetContext(),Uri.fromFile(privateRecord),source),"forged file URI rejected");
            check(Arrays.equals(original,Files.readAllBytes(privateRecord.toPath())),"private receipt untouched");
            check(!TradeExportWriter.writeTo(getTargetContext(),null,source),"missing destination rejected");
            check(TradeExportWriter.writeTo(getTargetContext(),Uri.parse("content://com.eurobuddha.pandadex.audit.exports/save"),source),"actual content-provider write succeeds");
            check("wt".equals(SaveProvider.mode)&&Arrays.equals(payload,Files.readAllBytes(target.toPath())),"provider receives truncation mode with no stale trailing bytes");
            check(!TradeExportWriter.writeTo(getTargetContext(),Uri.parse("content://com.eurobuddha.pandadex.audit.exports/fail"),source),"provider failure cannot report success");
        }finally{source.delete();target.delete();privateRecord.delete();}
    }
}

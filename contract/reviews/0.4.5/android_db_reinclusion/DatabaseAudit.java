package com.eurobuddha.pandadex;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.util.Collections;
import org.json.*;

/** Isolated audit package only. No node, SDK connection, signing or network permission. */
public final class DatabaseAudit extends Instrumentation {
    private Bundle arguments;
    private DexDb db;
    private int checks;
    private final StringBuilder passed = new StringBuilder();
    private static final long TIME = 1780000000000L;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            String phase=arguments==null?"normal":arguments.getString("phase","normal");
            if ("crash".equals(phase)) crashBeforeCommit();
            else if ("recover".equals(phase)) recoverAfterCrash();
            else { correctionAndRollback(); takerScope(); migration(); reinclusion(); legacyReinclusion(); boundedRequeue(); }
            result.putString("stream","PASS "+checks+" assertions\n"+passed);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,result);
        } finally { if(db!=null) db.close(); }
    }
    private void check(boolean ok,String what) { if(!ok)throw new AssertionError(what);checks++; }
    private void fresh() { if(db!=null)db.close();getTargetContext().deleteDatabase("pandadex.db");db=new DexDb(getTargetContext());check(db.getWritableDatabase().getVersion()==9,"fresh schema9"); }
    private String scalar(String sql) { try(Cursor c=db.getReadableDatabase().rawQuery(sql,null)){return c.moveToFirst()?c.getString(0):null;} }
    private long count(String table){return Long.parseLong(scalar("SELECT COUNT(*) FROM "+table));}
    private Order5 order() throws Exception {
        JSONObject raw=new JSONObject().put("coinid","0xaa").put("tokenid","0x00").put("amount","100").put("created",100)
            .put("state",new JSONObject().put("0","0xbb").put("1","0x"+"12".repeat(32)).put("2","1")
            .put("3",DexContract.USDT_ID).put("4","0xcc").put("5","1").put("7","1").put("8","1"));
        Order5 o=Order5.from(raw);check(o!=null&&o.fillable(),"existing order fixture valid");return o;
    }
    private FillSettler.Entry entry(Order5 o){return new FillSettler.Entry(o.coinid,o.sourceJson(),true);}
    private DexHistory.Spend spend(Order5 o,String tx,long block) throws Exception {
        DexHistory.Spend s=new DexHistory.Spend(tx,0,new JSONArray(),"0xeeff",4);
        s.input=new JSONObject(o.sourceJson());s.inputCount=1;s.inclusionBlock=block;s.inclusionTimeMs=TIME+block;
        s.inclusionBlockId="0xbeef";s.proofOrder=ChainEvidence.nextProofOrder();s.proofTimeMs=System.currentTimeMillis();return s;
    }
    private boolean fill(Order5 o,DexHistory.Spend s,String size,boolean mine){
        return db.completeFill(entry(o),s,o,s.inclusionTimeMs,s.inclusionBlock,new BigDecimal("0.01"),new BigDecimal(size),true,false,mine,FillSettler.CHAIN_VERIFIED,ChainEvidence.BLOCK_TIME_NOTE);
    }
    private ChainReview.Entry checkEntry(String tx){
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT revision FROM chaincheck WHERE txpowid=?",new String[]{tx})){
            if(!c.moveToFirst())throw new AssertionError("missing check "+tx);return new ChainReview.Entry(tx,c.getLong(0));
        }
    }
    private void missing(String tx){ db.reviewed(checkEntry(tx),new ChainReview.Evidence(ChainReview.MISSING,-1,0,"",""),System.currentTimeMillis()); }
    private Order5 seed() throws Exception {
        fresh();Order5 o=order();db.enqueueHistorical(o);check(fill(o,spend(o,"0xaaaa",100),"100",true),"initial new fill");
        check(count("tape")==1&&count("mytrade")==1&&count("verifiedspend")==1&&count("pendingfill")==0,"initial atomic rows");return o;
    }
    private void correctionAndRollback() throws Exception {
        Order5 o=seed();db.enqueueHistorical(o);
        boolean rejected=false;try{fill(o,spend(o,"0xbbbb",110),"80",true);}catch(ChainReview.Conflict expected){rejected=true;}
        check(rejected&&count("receiptaudit")==0&&count("pendingfill")==1,"no correction while old proof current");
        missing("0xaaaa");check(db.fills(0).isEmpty(),"missing proof excluded chart");
        check(db.myTradesAll(0,Long.MAX_VALUE).get(0).verificationStatus.startsWith("RECHECK_REQUIRED"),"missing receipt visible flagged");
        DexHistory.Spend replacement=spend(o,"0xbbbb",110);
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_correction BEFORE UPDATE ON mytrade BEGIN SELECT RAISE(ABORT,'audit injected write failure'); END");
        rejected=false;try{fill(o,replacement,"80",true);}catch(RuntimeException expected){rejected=true;}
        check(rejected,"injected actual Android update failed");
        check(count("receiptaudit")==0&&count("pendingfill")==1&&"0xaaaa".equals(scalar("SELECT txpowid FROM verifiedspend")),"archive winner queue rolled back");
        check("100".equals(scalar("SELECT size FROM tape"))&&"100".equals(scalar("SELECT size FROM mytrade")),"both original rows rolled back");
        db.getWritableDatabase().execSQL("DROP TRIGGER reject_correction");
        check(!fill(o,replacement,"80",true),"correction suppresses new notification");
        check(count("receiptaudit")==1&&count("pendingfill")==0&&"0xbbbb".equals(scalar("SELECT txpowid FROM verifiedspend")),"atomic correction persisted");
        check("80".equals(scalar("SELECT size FROM tape"))&&"80".equals(scalar("SELECT size FROM mytrade")),"both current rows corrected");
        check(db.fills(0).size()==1,"replacement chart restored");
        JSONObject archive=new JSONObject(scalar("SELECT snapshot FROM receiptaudit"));
        check("100".equals(archive.getJSONObject("personal_trade").getString("size")),"archive retains original amount");
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.toMs=Long.MAX_VALUE;db.loadExport(snapshot);
        check(snapshot.rows.size()==1&&new JSONArray(snapshot.correctionsJson).length()==1,"atomic Android export rows plus full archive");
        ChainReview.Entry stale=checkEntry("0xbbbb");missing("0xbbbb");
        db.reviewed(stale,new ChainReview.Evidence(ChainReview.CURRENT,999,110,"0xbeef",""),System.currentTimeMillis());
        check(ChainReview.MISSING.equals(scalar("SELECT state FROM chaincheck WHERE txpowid='0xbbbb'")),"stale revision ignored");
        db.enqueueHistorical(o);db.completeNonTrade(entry(o),spend(o,"0xcccc",120));
        check(count("receiptaudit")==2&&count("pendingfill")==0,"nontrade correction atomic");
        check(db.fills(0).isEmpty()&&db.tapeRows(20).size()==1,"nontrade retained excluded chart");
        check(db.myTradesAll(0,Long.MAX_VALUE).get(0).verificationStatus.equals("SUPERSEDED_NONTRADE"),"nontrade retained excluded personal");
        db.close();db=new DexDb(getTargetContext());
        check(count("receiptaudit")==2&&"0xcccc".equals(scalar("SELECT txpowid FROM verifiedspend")),"reopen keeps correction ledger");
        passed.append("correction, rollback, rechecks, export, nontrade and reopen\n");
    }
    private void takerScope() throws Exception {
        fresh();Order5 o=order();
        db.addMyTrade(o.coinid,TIME,100,new BigDecimal("0.02"),new BigDecimal("250"),true,false,"aggregate","0xdddd","COMPOSITE","0xaa,0xff","0xcc",FillSettler.CHAIN_VERIFIED,"aggregate receipt",100);
        db.enqueueHistorical(o);fill(o,spend(o,"0xaaaa",100),"100",false);missing("0xaaaa");db.enqueueHistorical(o);
        fill(o,spend(o,"0xbbbb",110),"80",true);
        check("250".equals(scalar("SELECT size FROM mytrade"))&&"0xdddd".equals(scalar("SELECT txpowid FROM mytrade")),"source correction preserves aggregate taker");
        missing("0xbbbb");db.enqueueHistorical(o);db.completeNonTrade(entry(o),spend(o,"0xcccc",120));
        check(FillSettler.CHAIN_VERIFIED.equals(scalar("SELECT verification_status FROM mytrade")),"source refund cannot supersede aggregate taker");
        passed.append("overlapping aggregate taker receipt preserved\n");
    }
    private void migration() throws Exception {
        for(int version:new int[]{4,6,7,8}) {
            Order5 o=seed();db.enqueueHistorical(o);db.checkpoint(false,Collections.singleton(o.coinid),Collections.emptyList(),72);
            SQLiteDatabase sql=db.getWritableDatabase();
            sql.execSQL("DROP INDEX verifiedspend_tx");
            sql.execSQL("DROP TABLE receiptaudit");sql.execSQL("ALTER TABLE tape DROP COLUMN settlement_kind");
            if(version<8) sql.execSQL("DROP TABLE chaincheck");
            else { sql.execSQL("ALTER TABLE chaincheck DROP COLUMN proofepoch");sql.execSQL("ALTER TABLE chaincheck DROP COLUMN prooforder"); }
            if(version<7) sql.execSQL("DROP TABLE verifiedspend");
            else if(version==7) sql.execSQL("ALTER TABLE verifiedspend DROP COLUMN sourcejson");
            if(version<5) sql.execSQL("DROP TABLE pendingfill");
            else if(version<7) sql.execSQL("ALTER TABLE pendingfill DROP COLUMN historical");
            if(version<6) sql.execSQL("DROP TABLE historyprogress");
            sql.setVersion(version);db.close();db=new DexDb(getTargetContext());
            check(db.getWritableDatabase().getVersion()==9,"SQLiteOpenHelper migration"+version+"to9");
            check("verifiedspend_tx".equals(scalar("SELECT name FROM sqlite_master WHERE type='index' AND name='verifiedspend_tx'")),"migration creates transaction recheck index");
            check(count("tape")==1&&count("mytrade")==1,"migration preserves original receipt rows");
            check("100".equals(scalar("SELECT size FROM mytrade"))&&"0xaaaa".equals(scalar("SELECT txpowid FROM mytrade")),"migration preserves original proof and amount");
            check(count("pendingfill")== (version>=5?1:0)&&count("verifiedspend")== (version>=7?1:0),"migration preserves existing queue and ledger without inventing old proof");
            check(db.offset(false,Collections.singleton(o.coinid))==(version>=6?72:0),"migration preserves existing history cursor");
            if(version>=5) check(db.batch(1).get(0).historical==(version>=7),"migration conservative historical flag");
            check("TRADE".equals(scalar("SELECT settlement_kind FROM tape"))&&"0".equals(scalar("SELECT prooforder FROM chaincheck")),"migration conservative check defaults");
        }
        passed.append("actual SQLiteOpenHelper schema4/6/7/8to9 upgrades\n");
    }
    private DexHistory.Spend movedSpend(Order5 o,long block,String blockid) throws Exception {
        DexHistory.Spend s=spend(o,"0xaaaa",block);s.inclusionBlockId=blockid;return s;
    }
    private void reinclusion() throws Exception {
        Order5 o=seed();ChainReview.Entry stale=checkEntry("0xaaaa");
        DexHistory.Spend delayedOld=spend(o,"0xaaaa",100);
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_queue BEFORE INSERT ON pendingfill BEGIN SELECT RAISE(ABORT,'audit queue failure'); END");
        boolean rejected=false;
        try{db.reviewed(stale,new ChainReview.Evidence(ChainReview.CURRENT,4,110,"0xdead",""),TIME+1);}catch(RuntimeException expected){rejected=true;}
        check(rejected&&count("pendingfill")==0&&"100".equals(scalar("SELECT block FROM chaincheck")),"queue failure rolls back accepted recheck");
        db.getWritableDatabase().execSQL("DROP TRIGGER fail_queue");
        db.enqueue(o);check(!db.batch(1).get(0).historical,"live candidate fixture");
        db.reviewed(stale,new ChainReview.Evidence(ChainReview.CURRENT,4,110,"0xdead",""),TIME+2);
        check(count("pendingfill")==1&&db.batch(1).get(0).historical,"moved proof queues historical recovery and upgrades live candidate");
        check(String.valueOf(TIME+100).equals(scalar("SELECT timems FROM mytrade")),"recheck alone cannot guess new block time");
        db.reviewed(stale,new ChainReview.Evidence(ChainReview.CURRENT,4,200,"0xcafe",""),TIME+3);
        check("110".equals(scalar("SELECT block FROM chaincheck")),"stale recheck cannot redirect recovery");
        rejected=false;try{fill(o,delayedOld,"100",true);}catch(ChainReview.Conflict expected){rejected=true;}
        check(rejected&&count("receiptaudit")==0,"delayed old inclusion cannot undo moved proof");
        DexHistory.Spend incomplete=movedSpend(o,110,"0xdead");incomplete.inclusionTimeMs=0;
        rejected=false;try{fill(o,incomplete,"100",true);}catch(ChainReview.Conflict expected){rejected=true;}
        check(rejected&&count("pendingfill")==1,"missing verified block time retains candidate and original record");
        incomplete=movedSpend(o,110,"");rejected=false;
        try{fill(o,incomplete,"100",true);}catch(ChainReview.Conflict expected){rejected=true;}
        check(rejected&&"0xbeef".equals(scalar("SELECT blockid FROM verifiedspend")),"incomplete new coordinates cannot erase old ledger proof");
        DexHistory.Spend current=movedSpend(o,110,"0xdead");
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_time BEFORE UPDATE ON mytrade BEGIN SELECT RAISE(ABORT,'audit correction failure'); END");
        rejected=false;try{fill(o,current,"100",true);}catch(RuntimeException expected){rejected=true;}
        check(rejected&&count("receiptaudit")==0&&"100".equals(scalar("SELECT block FROM verifiedspend")),"reinclusion correction rolls back archive and ledger on failure");
        db.getWritableDatabase().execSQL("DROP TRIGGER fail_time");
        check(!fill(o,current,"777",false),"reinclusion is a correction not a new-fill notification");
        check("100".equals(scalar("SELECT size FROM tape"))&&"100".equals(scalar("SELECT size FROM mytrade")),"same transaction preserves economics despite changed caller attribution");
        check("1".equals(scalar("SELECT mine FROM tape"))&&FillSettler.CHAIN_VERIFIED.equals(scalar("SELECT verification_status FROM mytrade")),"same transaction preserves original ownership and effect evidence");
        check(count("pendingfill")==0&&count("receiptaudit")==1,"reinclusion archive and retirement commit together");
        check(String.valueOf(TIME+110).equals(scalar("SELECT timems FROM mytrade"))&&"110".equals(scalar("SELECT block FROM tape")),"both receipt coordinates corrected from block proof");
        JSONObject old=new JSONObject(scalar("SELECT snapshot FROM receiptaudit"));
        check(old.getJSONObject("personal_trade").getLong("timems")==TIME+100&&old.getJSONObject("ledger").getLong("block")==100,"earlier time and ledger retained in archive");
        check("0xaaaa".equals(scalar("SELECT oldtxpowid FROM receiptaudit"))&&"0xaaaa".equals(scalar("SELECT newtxpowid FROM receiptaudit")),"archive explicitly keeps same transaction identity");
        check(!fill(o,current,"100",true)&&count("receiptaudit")==1,"same coordinates idempotent");
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,4,110,"0xcafe",""),TIME+4);
        check(count("pendingfill")==1,"different block at same height requeues");
        current=movedSpend(o,110,"0xcafe");current.inclusionTimeMs=TIME+111;fill(o,current,"100",true);
        check(count("receiptaudit")==2&&"0xcafe".equals(scalar("SELECT blockid FROM verifiedspend")),"same-height fork corrected and archived");
        passed.append("same-TxPoW reinclusion, exact time, queue rollback, stale proof, archive and same-height fork\n");
    }
    private void legacyReinclusion() throws Exception {
        Order5 o=seed();db.getWritableDatabase().execSQL("UPDATE verifiedspend SET sourcejson=''");
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,4,110,"0xdead",""),TIME+1);
        check(count("pendingfill")==0&&!db.known(o.coinid,"0xAAAA"),"legacy source absence permits actual history rediscovery without invented input");
        db.enqueueHistorical(o);fill(o,movedSpend(o,110,"0xdead"),"100",true);
        check(!scalar("SELECT sourcejson FROM verifiedspend").isEmpty()&&db.known(o.coinid,"0xAAAA"),"rediscovered input repairs legacy ledger then deduplicates");
        passed.append("legacy source rediscovery after moved inclusion\n");
    }
    private void boundedRequeue() throws Exception {
        fresh();Order5 template=order();
        for(int i=0;i<40;i++) {
            JSONObject raw=new JSONObject(template.sourceJson()).put("coinid",String.format("0x%04x",i));Order5 o=Order5.from(raw);
            db.enqueueHistorical(o);fill(o,spend(o,"0xaaaa",100),"100",true);
        }
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,4,110,"0xdead",""),TIME+1);
        check(count("pendingfill")==32,"one accepted recheck queues at most32 sources");
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,5,110,"0xdead",""),TIME+2);
        check(count("pendingfill")==40,"later recheck resumes remaining sources without starvation");
        passed.append("bounded source requeue across repeat checks\n");
    }
    private void crashBeforeCommit() throws Exception {
        Order5 o=seed();missing("0xaaaa");db.enqueueHistorical(o);
        db.getWritableDatabase().beginTransaction();fill(o,spend(o,"0xbbbb",110),"80",true);
        check(count("receiptaudit")==1,"uncommitted actual wrapper correction visible inside transaction");
        getTargetContext().getSharedPreferences("audit",Context.MODE_PRIVATE).edit().putBoolean("crash_armed",true).commit();
        android.os.Process.killProcess(android.os.Process.myPid());
        throw new AssertionError("process termination failed");
    }
    private void recoverAfterCrash() throws Exception {
        check(getTargetContext().getSharedPreferences("audit",Context.MODE_PRIVATE).getBoolean("crash_armed",false),"crash phase ran");
        db=new DexDb(getTargetContext());
        check(count("receiptaudit")==0&&count("pendingfill")==1,"process-death rolls back archive and queue deletion");
        check("0xaaaa".equals(scalar("SELECT txpowid FROM verifiedspend"))&&"100".equals(scalar("SELECT size FROM tape"))&&"100".equals(scalar("SELECT size FROM mytrade")),"process-death retains original winner and both receipts");
        boolean rejected=false;try{fill(order(),spend(order(),"0xbbbb",110),"80",true);}catch(ChainReview.Conflict expected){rejected=true;}
        check(rejected,"old-process missing proof requires fresh check");
        missing("0xaaaa");check(!fill(order(),spend(order(),"0xbbbb",110),"80",true),"fresh proof permits recovery after restart");
        check(count("receiptaudit")==1&&count("pendingfill")==0,"recovered correction committed");
        passed.append("real process death during uncommitted wrapper work; rollback, epoch guard and recovery\n");
    }
}

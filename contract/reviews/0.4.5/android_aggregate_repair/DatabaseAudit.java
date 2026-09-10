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
            if ("crash-taker-before".equals(phase)) crashTaker(false);
            else if ("crash-taker-after".equals(phase)) crashTaker(true);
            else if ("recover-taker-before".equals(phase)) recoverTaker(false);
            else if ("recover-taker-after".equals(phase)) recoverTaker(true);
            else if ("crash".equals(phase)) crashBeforeCommit();
            else if ("recover".equals(phase)) recoverAfterCrash();
            else { correctionAndRollback(); takerScope(); migration(); reinclusion(); legacyReinclusion(); boundedRequeue(); takerCompletion(); takerArchive(); aggregateRepair(); aggregateRotation(); }
            result.putString("stream","PASS "+checks+" assertions\n"+passed);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,result);
        } finally { if(db!=null) db.close(); }
    }
    private void check(boolean ok,String what) { if(!ok)throw new AssertionError(what);checks++; }
    private void fresh() { if(db!=null)db.close();getTargetContext().deleteDatabase("pandadex.db");db=new DexDb(getTargetContext());check(db.getWritableDatabase().getVersion()==11,"fresh schema11"); }
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
        for(int version:new int[]{4,6,7,8,9,10}) {
            Order5 o=seed();db.enqueueHistorical(o);db.checkpoint(false,Collections.singleton(o.coinid),Collections.emptyList(),72);
            SQLiteDatabase sql=db.getWritableDatabase();
            if(version<10)sql.execSQL("DROP TABLE takerreceipt");
            else {sql.execSQL("DROP INDEX takerreceipt_review");sql.execSQL("ALTER TABLE takerreceipt DROP COLUMN retry");sql.execSQL("ALTER TABLE takerreceipt DROP COLUMN needs_review");}
            if(version<9) {
                sql.execSQL("DROP INDEX verifiedspend_tx");
                sql.execSQL("DROP TABLE receiptaudit");sql.execSQL("ALTER TABLE tape DROP COLUMN settlement_kind");
            }
            if(version<8) sql.execSQL("DROP TABLE chaincheck");
            else if(version<9) { sql.execSQL("ALTER TABLE chaincheck DROP COLUMN proofepoch");sql.execSQL("ALTER TABLE chaincheck DROP COLUMN prooforder"); }
            if(version<7) sql.execSQL("DROP TABLE verifiedspend");
            else if(version==7) sql.execSQL("ALTER TABLE verifiedspend DROP COLUMN sourcejson");
            if(version<5) sql.execSQL("DROP TABLE pendingfill");
            else if(version<7) sql.execSQL("ALTER TABLE pendingfill DROP COLUMN historical");
            if(version<6) sql.execSQL("DROP TABLE historyprogress");
            sql.setVersion(version);db.close();db=new DexDb(getTargetContext());
            check(db.getWritableDatabase().getVersion()==11,"SQLiteOpenHelper migration"+version+"to11");
            check("verifiedspend_tx".equals(scalar("SELECT name FROM sqlite_master WHERE type='index' AND name='verifiedspend_tx'")),"migration creates transaction recheck index");
            check(count("tape")==1&&count("mytrade")==1,"migration preserves original receipt rows");
            check("100".equals(scalar("SELECT size FROM mytrade"))&&"0xaaaa".equals(scalar("SELECT txpowid FROM mytrade")),"migration preserves original proof and amount");
            check(count("pendingfill")== (version>=5?1:0)&&count("verifiedspend")== (version>=7?1:0),"migration preserves existing queue and ledger without inventing old proof");
            check(db.offset(false,Collections.singleton(o.coinid))==(version>=6?72:0),"migration preserves existing history cursor");
            if(version>=5) check(db.batch(1).get(0).historical==(version>=7),"migration conservative historical flag");
            check("TRADE".equals(scalar("SELECT settlement_kind FROM tape"))&&(version>=9 || "0".equals(scalar("SELECT prooforder FROM chaincheck"))),"migration conservative check defaults");
            check(count("takerreceipt")==0,"legacy migration does not invent taker expectations");
        }
        passed.append("actual SQLiteOpenHelper schema4/6/7/8/9/10to11 upgrades\n");
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
    private void takerCompletion() throws Exception {
        fresh();Order5 o=order();DexHistory.Spend proof=spend(o,"0xaaaa",100);
        java.util.List<String> sources=Collections.singletonList(o.coinid);
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_taker BEFORE INSERT ON mytrade BEGIN SELECT RAISE(ABORT,'audit taker write failure'); END");
        boolean failed=false;try{db.recordTakerFill(sources,proof,new BigDecimal("0.01"),new BigDecimal("100"),true,"BOOK");}catch(RuntimeException expected){failed=true;}
        check(failed&&count("mytrade")==0&&count("chaincheck")==0,"failed taker write cannot be treated as existing success");
        db.getWritableDatabase().execSQL("DROP TRIGGER fail_taker");
        check(db.recordTakerFill(sources,proof,new BigDecimal("0.01"),new BigDecimal("100"),true,"BOOK"),"new taker row committed");
        check(String.valueOf(TIME+100).equals(scalar("SELECT timems FROM mytrade"))&&"100".equals(scalar("SELECT verified_block FROM mytrade")),"taker receipt uses verified block coordinates");
        check(ChainReview.CURRENT.equals(scalar("SELECT state FROM chaincheck")),"taker record adopts actual ordered inclusion proof");
        check(!db.recordTakerFill(sources,proof,new BigDecimal("0.010"),new BigDecimal("100.0"),true,"BOOK")&&count("mytrade")==1,"replayed durable completion is idempotent");
        failed=false;try{db.recordTakerFill(sources,proof,new BigDecimal("0.01"),new BigDecimal("999"),true,"BOOK");}catch(ChainReview.Conflict expected){failed=true;}
        check(failed&&"100".equals(scalar("SELECT size FROM mytrade")),"conflicting prior receipt cannot masquerade as successful write");
        failed=false;try{db.recordTakerFill(sources,spend(o,"0xbbbb",110),new BigDecimal("0.01"),new BigDecimal("100"),true,"BOOK");}catch(ChainReview.Conflict expected){failed=true;}
        check(failed&&count("chaincheck")==1,"different transaction cannot retire pending receipt or add orphan check");
        proof.inclusionTimeMs=0;failed=false;
        try{db.recordTakerFill(sources,proof,new BigDecimal("0.01"),new BigDecimal("100"),true,"BOOK");}catch(IllegalArgumentException expected){failed=true;}
        check(failed,"unknown inclusion time cannot become today's receipt");
        passed.append("durable taker record, write failure, exact time, proof adoption, replay and conflict rejection\n");
    }
    private TakerReceipt archivedTaker(Order5 o) throws Exception {
        DexHistory.Spend proof=spend(o,"0xaaaa",100);
        proof.outputs.put(new JSONObject().put("address","0xcc").put("tokenid","0x00").put("amount","100"));
        JSONObject expected=new JSONObject().put("intent","audit-taker").put("sources",new JSONArray().put(o.coinid))
                .put("buy",true).put("minima","100").put("price","0.01").put("proceeds","100").put("token","0x00")
                .put("transactionid",proof.transactionId).put("payout","0xcc").put("source","BOOK");
        return TakerReceipt.capture(expected,Collections.singletonMap(o.coinid,proof));
    }
    private void takerArchive() throws Exception {
        fresh();Order5 o=order();TakerReceipt receipt=archivedTaker(o);
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_archive BEFORE INSERT ON takerreceipt BEGIN SELECT RAISE(ABORT,'audit archive failure'); END");
        boolean failed=false;try{db.recordTakerFill(receipt);}catch(RuntimeException expected){failed=true;}
        check(failed&&count("mytrade")==0&&count("chaincheck")==0&&count("takerreceipt")==0,"archive failure rolls back row and check");
        db.getWritableDatabase().execSQL("DROP TRIGGER fail_archive");
        check(db.recordTakerFill(receipt),"complete archive commits with new taker row");
        String original=scalar("SELECT json FROM takerreceipt");
        check(receipt.json.equals(original),"original expected sources payout and proof bytes retained");
        check(!db.recordTakerFill(archivedTaker(o))&&count("takerreceipt")==1,"replay neither duplicates nor replaces original full evidence");
        check(original.equals(scalar("SELECT json FROM takerreceipt")),"replay preserves original checked time and proof");
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();db.loadExport(snapshot);
        check(new JSONArray(snapshot.takerReceiptsJson).length()==1&&snapshot.rows.size()==1,"export snapshot includes linked receipt evidence");
        db.close();db=new DexDb(getTargetContext());check(original.equals(scalar("SELECT json FROM takerreceipt")),"full evidence survives reopen");
        // Same row economics but changed expected payout must not silently replace the archive.
        JSONObject changed=new JSONObject(receipt.intent.json).put("payout","0xdd");
        DexHistory.Spend proof=spend(o,"0xaaaa",100);proof.outputs.put(new JSONObject().put("address","0xdd").put("tokenid","0x00").put("amount","100"));
        failed=false;try{db.recordTakerFill(TakerReceipt.capture(changed,Collections.singletonMap(o.coinid,proof)));}catch(ChainReview.Conflict expected){failed=true;}
        check(failed&&original.equals(scalar("SELECT json FROM takerreceipt")),"conflicting intent retains original archive");
        // An earlier row can gain full proof only through a fresh complete linked receipt.
        db.getWritableDatabase().delete("takerreceipt",null,null);
        check(!db.recordTakerFill(archivedTaker(o))&&count("takerreceipt")==1,"complete pending proof repairs a missing archive without duplicate trade");
        passed.append("atomic full taker evidence, archive failure rollback, replay, export, conflict and reopen\n");
    }
    private TakerReceipt movedTaker(Order5 o,String tx,long block,String blockid) throws Exception {
        TakerReceipt old=archivedTaker(o);DexHistory.Spend proof=spend(o,tx,block);proof.inclusionBlockId=blockid;
        proof.outputs.put(new JSONObject().put("address","0xcc").put("tokenid","0x00").put("amount","100"));
        return TakerReceipt.capture(new JSONObject(old.intent.json),Collections.singletonMap(o.coinid,proof));
    }
    private void aggregateRepair() throws Exception {
        fresh();Order5 o=order();TakerReceipt first=archivedTaker(o);db.recordTakerFill(first);
        check(db.takerBatch(4).isEmpty(),"matching completed proof does not enter recovery");
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,2,110,"0xcafe",""),System.currentTimeMillis());
        java.util.List<TakerRecovery.Entry> queued=db.takerBatch(4);check(queued.size()==1,"changed inclusion queues aggregate");
        check(!ChainReview.accounted(db.myTradesAll(0,Long.MAX_VALUE).get(0).verificationStatus),"changed aggregate receipt remains visible but excluded pending full match");
        TakerRecovery.Entry saved=queued.get(0);String original=saved.json;TakerReceipt moved=movedTaker(o,"0xaaaa",110,"0xcafe");
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_aggregate BEFORE UPDATE ON mytrade BEGIN SELECT RAISE(ABORT,'audit aggregate failure'); END");
        boolean failed=false;try{db.repairTaker(saved,moved);}catch(RuntimeException expected){failed=true;}
        check(failed&&count("receiptaudit")==0&&original.equals(scalar("SELECT json FROM takerreceipt")),"failed aggregate write rolls archive/evidence back");
        check(db.takerBatch(4).size()==1&&String.valueOf(TIME+100).equals(scalar("SELECT timems FROM mytrade")),"failure keeps original time and pending recovery");
        db.getWritableDatabase().execSQL("DROP TRIGGER reject_aggregate");
        check(db.repairTaker(saved,moved),"same TxPoW aggregate corrected from full payout proof");
        check(String.valueOf(TIME+110).equals(scalar("SELECT timems FROM mytrade"))&&"100".equals(scalar("SELECT size FROM mytrade"))&&"0".equals(scalar("SELECT maker FROM mytrade")),"aggregate coordinates changed with economics/ownership preserved");
        JSONObject archive=new JSONObject(scalar("SELECT snapshot FROM receiptaudit"));
        check(archive.getJSONObject("previous_taker_receipt").getJSONObject("proof").getLong("timems")==TIME+100&&archive.getJSONObject("new_taker_receipt").getJSONObject("proof").getLong("timems")==TIME+110,"archive retains both inclusion observations");
        check(db.takerBatch(4).isEmpty()&&!db.repairTaker(saved,moved)&&count("receiptaudit")==1,"replayed stale snapshot cannot duplicate correction");
        check(ChainReview.accounted(db.myTradesAll(0,Long.MAX_VALUE).get(0).verificationStatus),"accepted aggregate repair restores original accounting eligibility");
        db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,1,110,"0xfeed",""),System.currentTimeMillis());
        TakerRecovery.Entry fork=db.takerBatch(4).get(0);
        check(db.repairTaker(fork,movedTaker(o,"0xaaaa",110,"0xfeed")),"same-height replacement block repairs aggregate");
        TakerRecovery.Entry before=savedEntry(o.coinid);
        failed=false;try{db.repairTaker(before,movedTaker(o,"0xbbbb",120,"0xdead"));}catch(ChainReview.Conflict expected){failed=true;}
        check(failed,"different TxPoW requires prior missing proof");
        TakerReceipt stale=movedTaker(o,"0xbbbb",120,"0xdead");missing("0xaaaa");
        failed=false;try{db.repairTaker(before,stale);}catch(ChainReview.Conflict expected){failed=true;}
        check(failed,"replacement captured before miss rejected");
        check(db.repairTaker(before,movedTaker(o,"0xbbbb",120,"0xdead")),"later full proof can follow same immutable transaction to another mined TxPoW");
        check("0xbbbb".equals(scalar("SELECT txpowid FROM mytrade"))&&db.takerBatch(4).isEmpty(),"replacement adopted and recovery cleared atomically");
        // A newer conflicting check must reject an obsolete full evidence callback.
        TakerRecovery.Entry current=savedEntry(o.coinid);TakerReceipt delayed=movedTaker(o,"0xbbbb",121,"0xbeef");
        db.reviewed(checkEntry("0xbbbb"),new ChainReview.Evidence(ChainReview.CURRENT,1,122,"0xcafe",""),System.currentTimeMillis());
        failed=false;try{db.repairTaker(current,delayed);}catch(ChainReview.Conflict expected){failed=true;}
        check(failed&&"120".equals(scalar("SELECT verified_block FROM mytrade")),"newer contradictory proof prevents stale timestamp overwrite");
        db.close();db=new DexDb(getTargetContext());check(db.takerBatch(4).size()==1&&count("receiptaudit")==3,"queue and original correction archive survive reopen");
        // Metadata repair is scoped to the saved intent and never substitutes maker economics.
        current=savedEntry(o.coinid);db.getWritableDatabase().execSQL("UPDATE mytrade SET source_kind='wrong',source_coinids='wrong'");
        check(db.repairTaker(current,movedTaker(o,"0xbbbb",122,"0xcafe"))&&"BOOK".equals(scalar("SELECT source_kind FROM mytrade")),"fresh full proof repairs summary source metadata from retained expectations");
        passed.append("aggregate reinclusion, same-height fork, full-proof replacement ordering, stale callbacks, atomic archive/queue and metadata repair\n");
    }
    private TakerRecovery.Entry savedEntry(String source) {
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT spentcoin,txpowid,block,blockid,json FROM takerreceipt WHERE spentcoin=?",new String[]{source})) {
            if(!c.moveToFirst())throw new AssertionError("no taker receipt");return new TakerRecovery.Entry(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4));
        }
    }
    private void aggregateRotation() throws Exception {
        fresh();Order5 o=order();TakerReceipt receipt=archivedTaker(o);db.recordTakerFill(receipt);
        for(int i=0;i<12;i++) {
            android.content.ContentValues v=new android.content.ContentValues();v.put("spentcoin",String.format(java.util.Locale.ROOT,"0x%04x",i));
            v.put("txpowid","0xaaaa");v.put("block",100);v.put("blockid","0xbeef");v.put("json","retained test evidence");v.put("needs_review",1);
            db.getWritableDatabase().insertOrThrow("takerreceipt",null,v);
        }
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int pass=0;pass<3;pass++) {java.util.List<TakerRecovery.Entry> batch=db.takerBatch(999);check(batch.size()==4,"aggregate selection capped at four");for(TakerRecovery.Entry e:batch)seen.add(e.coinid);}
        check(seen.size()==12,"durable rotation cannot starve older queued aggregates");
        check("takerreceipt_review".equals(scalar("SELECT name FROM sqlite_master WHERE type='index' AND name='takerreceipt_review'")),"pending aggregate index exists");
        // A failed queue update must also roll the accepted chain recheck back.
        String before=scalar("SELECT block FROM chaincheck");
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_queue BEFORE UPDATE ON takerreceipt BEGIN SELECT RAISE(ABORT,'audit queue failure'); END");
        boolean failed=false;try{db.reviewed(checkEntry("0xaaaa"),new ChainReview.Evidence(ChainReview.CURRENT,1,999,"0xffff",""),System.currentTimeMillis());}catch(RuntimeException expected){failed=true;}
        check(failed&&before.equals(scalar("SELECT block FROM chaincheck")),"aggregate queue failure rolls back recheck");
        db.getWritableDatabase().execSQL("DROP TRIGGER reject_queue");
        check(db.claimTakerTurn(),"first aggregate turn claimed");db.close();db=new DexDb(getTargetContext());
        check(!db.claimTakerTurn()&&db.claimTakerTurn(),"restart preserves alternation between aggregate and market recovery");
        String original=scalar("SELECT json FROM takerreceipt WHERE spentcoin='0xaa'");
        missing("0xaaaa");SQLiteDatabase sql=db.getWritableDatabase();
        sql.execSQL("DROP INDEX takerreceipt_review");sql.execSQL("ALTER TABLE takerreceipt DROP COLUMN retry");sql.execSQL("ALTER TABLE takerreceipt DROP COLUMN needs_review");
        sql.setVersion(10);db.close();db=new DexDb(getTargetContext());
        check(db.getWritableDatabase().getVersion()==11&&original.equals(scalar("SELECT json FROM takerreceipt WHERE spentcoin='0xaa'")),"schema10 full evidence preserved through real upgrade");
        check(count("takerreceipt")==13&&db.takerBatch(4).size()==4,"migration derives bounded pending recovery without dropping prior evidence");
        passed.append("bounded indexed aggregate rotation, durable turns, schema10 evidence and atomic recheck queueing\n");
    }
    private void crashTaker(boolean committed) throws Exception {
        fresh();Order5 o=order();
        JSONObject receipt=new JSONObject().put("intent","audit-taker").put("sources",new JSONArray().put(o.coinid))
                .put("buy",true).put("minima","100").put("price","0.01").put("proceeds","100").put("token","0x00")
                .put("block",90).put("txpowid","0xaaaa").put("payout","0xcc").put("source","BOOK");
        check(getTargetContext().getSharedPreferences("pandadex_taker",Context.MODE_PRIVATE).edit().putString("pending",receipt.toString()).commit(),"pending receipt durable before database work");
        if(!committed)db.getWritableDatabase().beginTransaction();
        check(db.recordTakerFill(archivedTaker(o)),"actual taker writer ran before crash");
        check(getTargetContext().getSharedPreferences("audit",Context.MODE_PRIVATE).edit().putString("taker_crash",committed?"after":"before").commit(),"crash phase sentinel saved");
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("termination failed");
    }
    private void recoverTaker(boolean committed) throws Exception {
        check((committed?"after":"before").equals(getTargetContext().getSharedPreferences("audit",Context.MODE_PRIVATE).getString("taker_crash","")),"matching taker crash phase ran");
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences("pandadex_taker",Context.MODE_PRIVATE);
        check(!prefs.getString("pending","").isEmpty(),"pending receipt survives process death on either side of database commit");
        db=new DexDb(getTargetContext());check(count("mytrade")== (committed?1:0)&&count("takerreceipt")== (committed?1:0),"only committed taker row and full evidence survive");
        Order5 o=order();boolean inserted=db.recordTakerFill(archivedTaker(o));
        check(inserted!=committed&&count("mytrade")==1&&count("takerreceipt")==1,"retry restores or reuses one taker row and archive");
        check(prefs.edit().remove("pending").commit(),"pending cleanup acknowledged after durable row");
        db.close();db=new DexDb(getTargetContext());
        check(count("mytrade")==1&&count("takerreceipt")==1&&prefs.getString("pending","").isEmpty(),"row and evidence retained after acknowledged cleanup and reopen");
        passed.append("taker crash "+(committed?"after":"before")+" database commit; pending retention and idempotent recovery\n");
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

package com.eurobuddha.pandadex;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;

/** Production preferences/journal on Android; no node, network or production Activity/service. */
public final class OwnerReceiptAndroidAudit extends Instrumentation {
    private int checks;private String phase;
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase","normal");start();}
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}
    void reset(){check(prefs("pandadex_maker").edit().clear().commit(),"maker fixture reset");check(prefs("pandadex_pending").edit().clear().commit(),"receipt fixture reset");MakerConfig.resetStorageForTests();}
    DexTxn.Result callback(){return new DexTxn.Result(){public void onPosted(String id){}public void onFailed(String message){}};}
    Order5 order(String coin)throws Exception {
        JSONObject state=new JSONObject().put("0","0xbb").put("1","0x1212121212121212121212121212121212121212121212121212121212121212")
                .put("2","1").put("3",DexContract.USDT_ID).put("4","0xcc").put("5","1").put("7","1").put("8","1");
        return Order5.from(new JSONObject().put("coinid",coin).put("tokenid","0x00").put("amount","100").put("created",100).put("state",state));
    }
    MakerConfig maker()throws Exception {
        MakerConfig c=new MakerConfig(getTargetContext());c.pegged=false;c.armed=true;
        c.asks.add(new MakerLadder.Level(new BigDecimal("0.01"),new BigDecimal("100")));
        c.rememberSlot("A1","0xcc",new BigDecimal("100"),100,new BigDecimal("100"),"0x00");
        check(c.saveUserAction(),"maker settings and funded slot acknowledged");return c;
    }
    Pending.Row find(Pending p,String kind,String coin){for(Pending.Row r:p.rows())if(kind.equals(r.kind)&&coin.equals(r.coinid))return r;throw new AssertionError("missing receipt "+kind+coin);}
    DexHistory.Spend relock(Order5 source,String wanted,int depth)throws Exception {
        JSONObject out=new JSONObject(source.sourceJson()).put("coinid","0xee").put("address",DexContract.ADDR_V5).put("storestate",true).put("state",new JSONArray());
        JSONObject state=new JSONObject(source.sourceJson()).getJSONObject("state");state.put("2",wanted);
        JSONArray states=new JSONArray();for(Iterator<String> it=state.keys();it.hasNext();){String k=it.next();states.put(new JSONObject().put("port",Integer.parseInt(k)).put("data",state.getString(k)));}
        DexHistory.Spend proof=new DexHistory.Spend("0xabcd",0,new JSONArray().put(out),"0xbbaa",depth);proof.input=new JSONObject(source.sourceJson());proof.transactionState=states;return proof;
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        if("normal".equals(phase)){normal();keyLoading();upkeep();integrity();schema();ownerHistory();ownerAccess();archiveIntegrity();ownerRevisions();ownerQueue();makerIdle();makerSnapshots();}else if("crash".equals(phase))crash();else if("recover".equals(phase)){recover();ownerRecovery();ownerQueueAfterDeath();}else throw new AssertionError("unknown phase");
        result.putInt("pid",android.os.Process.myPid());result.putInt("assertions",checks);
        result.putString("stream","PASS "+checks+" assertions\nOwner receipt Android "+phase+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}}
    void normal()throws Exception {
        reset();MakerConfig original=maker();MakerConfig reopened=new MakerConfig(getTargetContext());
        check(reopened.readable()&&reopened.armed,"real preference snapshot loaded");
        check(reopened.slots.get("A1").locked.compareTo(new BigDecimal("100"))==0,"funded amount retained");
        check("0x00".equals(reopened.slots.get("A1").lockedToken),"funded token retained");
        String good=prefs("pandadex_maker").getString("slots2","");
        check(prefs("pandadex_maker").edit().putString("slots2","{broken").commit(),"corrupt fixture stored");
        reopened.reload();check(!reopened.readable()&&!reopened.armed,"corruption pauses maker");
        check("0xcc".equals(reopened.orderIdFor("A1")),"previous complete identities retained");
        MakerConfig cold=new MakerConfig(getTargetContext());check(!cold.readable()&&cold.hasRecordedOrders(),"cold corruption is recovery work not empty ladder");
        check(!cold.saveUserAction()&&!cold.prepareCreate("A2","0xdd",BigDecimal.TEN,101),"corrupt store cannot be overwritten");
        check("{broken".equals(prefs("pandadex_maker").getString("slots2","")),"original corrupt bytes retained");
        check(prefs("pandadex_maker").edit().putString("slots2",good).commit(),"restore known fixture");
        cold.reload();check(cold.readable()&&!MakerConfig.storageHealthy(),"readable recovery does not silently resume");
        check(cold.saveUserAction()&&MakerConfig.storageHealthy(),"explicit acknowledged recovery resumes");
        check(prefs("pandadex_maker").edit().putString("armed","true").commit(),"wrong native type fixture stored");
        MakerConfig typed=new MakerConfig(getTargetContext());check(!typed.readable()&&!typed.save(),"Android native wrong type does not crash or overwrite");
        check(prefs("pandadex_maker").edit().putBoolean("armed",false).commit(),"restore boolean fixture");
        typed.reload();check(typed.saveUserAction(),"recover typed fixture");

        Pending pending=new Pending(getTargetContext());DexTxn.Result cancel=pending.cancellationResult(Arrays.asList(order("0xaa"),order("0xbb")),100,callback());
        check(cancel.onPrepared("cancel_android"),"batch intent committed before signing");
        check(new Pending(getTargetContext()).rows().size()==2,"both batch sources persisted");
        check(cancel.beforePost(),"batch POSTING committed");cancel.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
        for(Pending.Row r:new Pending(getTargetContext()).rows()) {
            check("UNKNOWN".equals(r.phase),"unknown cancellation retained");check(Pending.cancelSourceMatches(r,order(r.coinid)),"saved source economics match");
        }
        Order5 source=order("0xdd");DexTxn.Result edit=pending.relockResult(source,new BigDecimal("1.00000001"),100,callback());
        check(edit.onPrepared("relock_android")&&edit.beforePost(),"exact relock journal acknowledged");edit.onPosted("0xabcd");
        Pending.Row row=find(new Pending(getTargetContext()),Pending.EDIT,"0xdd");
        check("1.00000001".equals(row.editWant)&&"SUBMITTED".equals(row.phase),"exact new amount and accepted phase survive reload");
        check(Pending.editMatches(row,relock(source,"1.00000001",3),source),"actual Android JSON exact successor matches");
        check(!Pending.editMatches(row,relock(source,"1.00000002",3),source),"adjacent token grain rejected");
        check(!Pending.editMatches(row,relock(source,"1.00000001",-1),source),"unconfirmed successor rejected");
        check(prefs("pandadex_pending").edit().putString("rows","broken original receipts").commit(),"corrupt receipt fixture stored");
        String bad=prefs("pandadex_pending").getString("rows","");
        DexTxn.Result refused=pending.relockResult(source,new BigDecimal("2"),100,callback());
        check(!pending.healthy()&&!refused.onPrepared("blocked"),"unreadable receipts block next intent");
        check(bad.equals(prefs("pandadex_pending").getString("rows","")),"receipt bytes preserved");
    }
    void crash()throws Exception {
        reset();MakerConfig c=maker();check(c.prepareCreate("A2","0xdd",BigDecimal.TEN,101),"prepared maker intent saved");
        check(c.stopAndTrackWithdrawal(102),"pause and both withdrawal identities committed");
        Pending p=new Pending(getTargetContext());DexTxn.Result cancel=p.cancellationResult(Arrays.asList(order("0xaa"),order("0xbb")),101,callback());
        check(cancel.onPrepared("cancel_before_death")&&cancel.beforePost(),"cancel batch left POSTING before death");
        DexTxn.Result edit=p.relockResult(order("0xdd"),new BigDecimal("2"),101,callback());
        check(edit.onPrepared("relock_before_death")&&edit.beforePost(),"relock left POSTING before death");
        DexTxn.Result renewal=p.relockResult(order("0xff"),BigDecimal.ONE,101,callback());
        check(renewal.onPrepared("renew_before_death"),"renewal left PREPARED before death");
        DexTxn.Result refund=p.cancellationResult(Collections.singletonList(order("0xab")),101,callback());
        check(refund.onPrepared("refund_before_death")&&refund.beforePost(),"expired-refund journal left POSTING before death");
        check(prefs("pandadex_processor").edit().clear().putString("0xff","101").commit(),"legacy pacing marker committed before death");
        Pending.Row archivedRow=find(p,Pending.EDIT,"0xdd");
        try(DexDb db=new DexDb(getTargetContext())) {
            db.recordOwnerReceipt(OwnerReceipt.capture(archivedRow,ownerProof(order("0xdd"),"2","0xabcd")));
            check(ownerJson(db,archivedRow.receiptId).contains("EDITED"),"terminal receipt committed before pending cleanup/process death");
            queueOwnerForDeath(db);
        }
        check(prefs("owner_audit_checkpoint").edit().putString("owner_receipt_id",archivedRow.receiptId).commit(),"terminal receipt identity checkpoint committed");
        check(prefs("owner_audit_checkpoint").edit().putInt("pid",android.os.Process.myPid()).putInt("checks",checks)
                .putInt("build",29).putBoolean("ready",true).commit(),"crash checkpoint committed");
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("process did not exit");
    }
    void recover()throws Exception {
        SharedPreferences checkpoint=prefs("owner_audit_checkpoint");
        check(checkpoint.getBoolean("ready",false)&&checkpoint.getInt("build",0)==29,"prior crash checkpoint belongs to audit29");
        check(checkpoint.getInt("pid",0)>0&&checkpoint.getInt("pid",0)!=android.os.Process.myPid(),"recovery uses a different actual Android process");
        check(checkpoint.getInt("checks",0)>=8,"pre-death assertions completed");
        MakerConfig c=new MakerConfig(getTargetContext());check(c.readable()&&!c.armed,"acknowledged maker pause survives process death");
        check(c.cancelTombstones.containsKey("0xcc")&&c.cancelTombstones.containsKey("0xdd"),"both withdrawal identities survive");
        check("0xcc".equals(c.orderIdFor("A1"))&&"0xdd".equals(c.preparedOrderId()),"original slot and prepared intent survive");
        Pending p=new Pending(getTargetContext());check(p.healthy()&&p.rows().size()==5,"every owner receipt survives actual death");
        for(String coin:new String[]{"0xaa","0xbb"}) {Pending.Row r=find(p,Pending.CANCEL,coin);check("POSTING".equals(r.phase),"lost cancellation reply remains unknown POSTING");check(Pending.cancelSourceMatches(r,order(coin)),"cancellation source survives process death");}
        Pending.Row edit=find(p,Pending.EDIT,"0xdd");check("POSTING".equals(edit.phase)&&"2".equals(edit.editWant),"exact relock expectation survives death");
        check(Pending.editMatches(edit,relock(order("0xdd"),"2",3),order("0xdd")),"restarted intent verifies exact successor fixture");
        Pending.Row renewal=find(p,Pending.EDIT,"0xff");check("PREPARED".equals(renewal.phase)&&renewal.isRenewal(),"prepared renewal remains unsubmitted evidence");
        check(renewal.status(110).contains("submission is not confirmed"),"restarted prepared status does not invent submission");
        UpkeepTxn tx=new UpkeepTxn(p);DexProcessor processor=new DexProcessor(getTargetContext(),tx);
        Notices notices=new Notices();Order5 source=order("0xff");
        process(processor,source,source.created+DexContract.RENEW_AT+20,notices);
        Order5 expired=order("0xab");process(processor,expired,expired.created+DexContract.EXPIRY_BLOCKS+20,notices);
        check(tx.renews==0&&tx.refunds==0,"actual process restart cannot retry unresolved renewal or refund");
        check(!prefs("pandadex_processor").contains("0xff"),"pacing marker retires independently of receipt");
        check(new Pending(getTargetContext()).rows().size()==5,"all original receipts retained after automatic retry checks");
        check(notices.pauses==1,"repeated unresolved-source pause notice deduplicated");
    }
    static final class Commands implements FundingCoins.Command {
        final List<NodeApi.Cb> callbacks=new ArrayList<>();
        public void run(String command,NodeApi.Cb cb){callbacks.add(cb);}
        NodeApi.Cb last(){return callbacks.get(callbacks.size()-1);}
    }
    void mainChecked(Runnable task){
        final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable t){failure[0]=t;}});
        if(failure[0]!=null)throw new AssertionError("main-thread audit failed",failure[0]);
    }
    JSONObject keysReply() throws Exception {return new JSONObject().put("status",true).put("response",new JSONArray().put("0xbb").put("0xcc"));}
    JSONObject addressReply(String address)throws Exception{return new JSONObject().put("status",true).put("response",new JSONObject().put("parseok",true).put("script",new JSONObject().put("address",address)));}
    void keyLoading()throws Exception {
        check(prefs("pandadex_keys").edit().clear().putString("node_keys","[\"0xaa\"]").putString("node_addrs","[\"0x11\"]").commit(),"real key cache seeded");
        final KeySet[] key={null};Commands commands=new Commands();WatcherPassGate gate=new WatcherPassGate(300000);int[] continuations={0};
        JSONObject keys=keysReply(),first=addressReply("0x22"),second=addressReply("0x33");
        mainChecked(()->{
            check(Looper.myLooper()==Looper.getMainLooper(),"key loader runs on real Android main looper");
            key[0]=new KeySet(getTargetContext(),()->{if(gate.resumeAfterKeys(key[0].ready()))continuations[0]++;});
            check(!key[0].ready()&&key[0].keys().contains("0xaa"),"cold Android cache renders without authorizing");
            check(gate.next(0,true,false,true)==WatcherPassGate.Action.SCAN,"watcher waits for complete ownership");
            key[0].refresh(commands);commands.last().onResult(keys);commands.last().onResult(first);
            check(!key[0].ready()&&key[0].keys().contains("0xaa")&&key[0].addrs().contains("0x11"),"partial derivation retains both prior factors");
            check(continuations[0]==0,"watcher cannot resume from partial ownership");
            NodeApi.Cb stale=commands.last();new Handler(Looper.getMainLooper()).post(()->stale.onResult(second));
            key[0].invalidate();gate.invalidate();
        });
        mainChecked(()->{
            check(!key[0].ready()&&continuations[0]==0,"queued old address reply cannot restore invalidated readiness");
            check(key[0].keys().contains("0xaa")&&key[0].addrs().contains("0x11"),"queued obsolete reply cannot change snapshot");
            check(gate.next(300000,true,false,true)==WatcherPassGate.Action.SCAN,"next eligible watcher pass starts");
            key[0].refresh(commands);commands.last().onResult(keys);commands.last().onResult(first);commands.last().onResult(second);
            check(key[0].ready()&&key[0].keys().size()==2&&key[0].addrs().size()==2,"complete Android load publishes both factors");
            check(continuations[0]==1,"real completion callback resumes watcher once");
            key[0].invalidate();key[0].refresh(commands);commands.last().onError("fixture offline");
            key[0].invalidate(); // Cancel the real Handler's ten-second retry.
        });
        int before=commands.callbacks.size();Thread.sleep(10250);
        mainChecked(()->{
            check(commands.callbacks.size()==before,"real delayed retry cancelled by invalidation");
            KeySet reopened=new KeySet(getTargetContext(),null);
            check(!reopened.ready()&&reopened.keys().size()==2&&reopened.addrs().size()==2,"real cache reopened coherently but never freshly authorized");
            reopened.close();key[0].close();check(!key[0].ready(),"closed loader cannot authorize ownership");
        });
    }
    static final class Notices implements DexProcessor.Listener {
        int pauses,failures;public void onRenewed(Order5 o){}public void onRenewFailed(Order5 o,String message){failures++;}
        public void onPaused(String message){pauses++;}
    }
    final class UpkeepTxn extends DexTxn {
        final Pending pending;int renews,refunds;
        UpkeepTxn(Pending pending){super(null,null);this.pending=pending;}
        public void relock(Order5 o,BigDecimal want,Result cb){renews++;attempt(pending.relockResult(o,o.wantAmt,o.created+DexContract.RENEW_AT,cb));}
        public void collectExpired(Order5 o,Result cb){refunds++;attempt(pending.cancellationResult(Collections.singletonList(o),o.created+DexContract.EXPIRY_BLOCKS+1,cb));}
        void attempt(Result result){check(result.onPrepared("android_upkeep_"+(renews+refunds)),"upkeep intent stored on Android before signing");
            check(result.beforePost(),"upkeep POSTING stored on Android");result.onFailed("fixture lost reply");}
    }
    void process(DexProcessor processor,Order5 source,long block,Notices notices){
        processor.process(Collections.singletonMap(source.coinid,source),Collections.singleton(source.ownerPk),Collections.singleton(source.wantAddr),Collections.emptySet(),block,notices);
    }
    void upkeep()throws Exception {
        check(prefs("pandadex_pending").edit().clear().commit(),"reset owner receipt fixture for upkeep");
        check(prefs("pandadex_processor").edit().clear().commit(),"reset processor fixture");
        Pending pending=new Pending(getTargetContext());UpkeepTxn tx=new UpkeepTxn(pending);Notices notices=new Notices();
        DexProcessor processor=new DexProcessor(getTargetContext(),tx);Order5 source=order("0xaa");long due=source.created+DexContract.RENEW_AT;
        process(processor,source,due,notices);check(tx.renews==1,"real processor dispatched one fixture renewal");
        check("UNKNOWN".equals(new Pending(getTargetContext()).rows().get(0).phase),"lost Android renewal reply retains UNKNOWN");
        processor=new DexProcessor(getTargetContext(),tx);process(processor,source,due+20,notices);
        check(tx.renews==1&&notices.pauses==1,"reopened Android stores prevent aged renewal retry");
        Order5 refund=order("0xab");process(processor,refund,refund.created+DexContract.EXPIRY_BLOCKS+1,notices);
        check(tx.refunds==1,"unrelated expired source can refund");check(new Pending(getTargetContext()).rows().size()==2,"refund and renewal receipts coexist");
        process(processor,refund,refund.created+DexContract.EXPIRY_BLOCKS+20,notices);check(tx.refunds==1,"unknown expired refund cannot automatically repeat");
        check(prefs("pandadex_processor").edit().putBoolean("0xfe",true).commit(),"wrong native marker type persisted");
        Order5 fresh=order("0xdd");process(new DexProcessor(getTargetContext(),tx),fresh,due,notices);
        check(tx.renews==1&&prefs("pandadex_processor").getAll().get("0xfe") instanceof Boolean,"wrong marker type pauses without overwriting");
    }

    void receiptRefused(String raw)throws Exception {
        check(prefs("pandadex_pending").edit().putString("rows",raw).commit(),"damaged receipt fixture committed");
        Pending pending=new Pending(getTargetContext());
        check(!pending.healthy()&&"RECOVERY_ERROR".equals(pending.rows().get(0).kind),"damaged receipt is recovery work");
        DexTxn.Result result=pending.cancellationResult(Collections.singletonList(order("0xab")),100,callback());
        check(!result.onPrepared("integrity_blocked")&&!result.beforePost(),"damaged receipt cannot advance submission");
        check(raw.equals(prefs("pandadex_pending").getString("rows","")),"damaged original bytes retained exactly");
    }
    void integrity()throws Exception {
        reset();maker();String slots=prefs("pandadex_maker").getString("slots2","");
        Pending pending=new Pending(getTargetContext());
        DexTxn.Result result=pending.cancellationResult(Collections.singletonList(order("0xaa")),100,callback());
        check(result.onPrepared("integrity_source"),"valid source receipt fixture committed");
        String good=prefs("pandadex_pending").getString("rows","");
        for(String suffix:new String[]{" // hidden"," /* hidden */"," # hidden"}) {
            JSONTokener old=new JSONTokener("{}"+suffix);old.nextValue();
            check(old.nextClean()==0,"actual Android old suffix check skips comment");
        }
        for(String suffix:new String[]{" // hidden"," /* hidden */"," # hidden"," trailing"," []","\f","\u0001","\0"}) {
            receiptRefused(good+suffix);
            check(prefs("pandadex_maker").edit().putString("slots2",slots+suffix).commit(),"maker suffix fixture committed");
            MakerConfig maker=new MakerConfig(getTargetContext());
            check(!maker.readable()&&!maker.saveUserAction(),"maker suffix cannot authorize or overwrite");
            check((slots+suffix).equals(prefs("pandadex_maker").getString("slots2","")),"maker suffix preserved");
            JSONObject reply=org.minimarex.minimaapi.MinimaAPIResponse.parse("{\"status\":true}"+suffix);
            check(!reply.has("status")&&reply.has("transporterror"),"invalid reply suffix retains uncertain outcome");
        }
        check(prefs("pandadex_maker").edit().putString("slots2",slots+" \t\r\n").commit(),"maker whitespace fixture restored");
        MakerConfig restored=new MakerConfig(getTargetContext());
        check(restored.readable()&&restored.saveUserAction(),"legal whitespace and explicit maker recovery work");
        JSONObject first=new JSONArray(good).getJSONObject(0),second=new JSONObject(first.toString()).put("coinid","0xbb");
        receiptRefused(new JSONArray().put(first).put(second).toString());
        for(Object id:new Object[]{""," ",JSONObject.NULL,true,12,"x\0y"})
            receiptRefused(new JSONArray().put(new JSONObject(first.toString()).put("receiptId",id)).toString());
        JSONObject legacy=new JSONObject(first.toString());legacy.remove("receiptId");
        receiptRefused(new JSONArray().put(legacy).put(legacy).toString());
        String legacyRaw=" \t"+new JSONArray().put(legacy)+"\r\n";
        check(prefs("pandadex_pending").edit().putString("rows",legacyRaw).commit(),"single legacy fixture stored");
        Pending a=new Pending(getTargetContext()),b=new Pending(getTargetContext());
        check(a.healthy()&&a.rows().size()==1&&a.rows().get(0).receiptId.equals(b.rows().get(0).receiptId),"legacy identity stable on Android");
        check(Boolean.TRUE.equals(org.minimarex.minimaapi.MinimaAPIResponse.parse("{\"status\":true} \t\r\n").opt("status")),"legal reply whitespace accepted");
    }

    void badField(JSONObject good,String field,Object bad)throws Exception {
        receiptRefused(new JSONArray().put(new JSONObject(good.toString()).put(field,bad)).toString());
    }
    void schema()throws Exception {
        reset();check(prefs("pandadex_processor").edit().clear().commit(),"schema processor fixture reset");
        Pending pending=new Pending(getTargetContext());UpkeepTxn tx=new UpkeepTxn(pending);Notices notices=new Notices();
        Order5 source=order("0xaa");long due=source.created+DexContract.RENEW_AT;
        process(new DexProcessor(getTargetContext(),tx),source,due,notices);
        check(tx.renews==1,"one fixture renewal before receipt damage");
        JSONObject good=new JSONArray(prefs("pandadex_pending").getString("rows","")).getJSONObject(0);
        String badKind=new JSONArray().put(new JSONObject(good.toString()).put("kind","EDlT")).toString();
        check(prefs("pandadex_pending").edit().putString("rows",badKind).commit(),"unknown kind fixture committed");
        process(new DexProcessor(getTargetContext(),tx),source,due+20,notices);
        check(tx.renews==1&&notices.pauses==1,"malformed kind cannot permit renewal retry after instance restart");
        check(badKind.equals(prefs("pandadex_pending").getString("rows","")),"automatic hold preserves malformed kind bytes");
        for(String field:new String[]{"kind","orderId","coinid","buy","minima","price","submitMs","submitBlock"}) {
            JSONObject missing=new JSONObject(good.toString());missing.remove(field);receiptRefused(new JSONArray().put(missing).toString());
        }
        for(String field:new String[]{"kind","orderId","coinid","phase","transactionHandle","postedId","cancelSource","editSource","editWant"})
            for(Object value:new Object[]{true,12,JSONObject.NULL,new JSONObject(),"x\0y"})badField(good,field,value);
        for(String field:new String[]{"buy","delayedNotified"})for(Object value:new Object[]{"true",1,JSONObject.NULL})badField(good,field,value);
        for(String field:new String[]{"minima","price"})for(Object value:new Object[]{"garbage","1,2","1e100000","-1",true})badField(good,field,value);
        for(String field:new String[]{"submitMs","submitBlock"})for(Object value:new Object[]{1.5,"9223372036854775808",-1,true})badField(good,field,value);
        for(String value:new String[]{"CONFIRMED","unknown","NOT_SUBMITTED "})badField(good,"phase",value);
        for(Object value:new Object[]{JSONObject.NULL,"{}",new JSONArray()})badField(good,"creation",value);
        JSONObject legacy=new JSONObject(good.toString());
        for(String field:new String[]{"receiptId","delayedNotified","phase","transactionHandle","postedId","cancelSource","editSource","editWant"})legacy.remove(field);
        check(prefs("pandadex_pending").edit().putString("rows",new JSONArray().put(legacy).toString()).commit(),"original legacy receipt shape restored");
        Pending restored=new Pending(getTargetContext());check(restored.healthy(),"original legacy fields readable on Android");
        Pending.Row row=restored.rows().get(0);row.price=BigDecimal.ZERO;restored.add(row);
        check(new Pending(getTargetContext()).healthy()&&new Pending(getTargetContext()).rows().get(0).price.signum()==0,"zero display price survives Android update");
        String original=prefs("pandadex_pending").getString("rows","");row.kind="UNKNOWN_KIND";boolean refused=false;
        try{restored.add(row);}catch(IllegalStateException expected){refused=true;}
        check(refused&&original.equals(prefs("pandadex_pending").getString("rows","")),"invalid new row cannot poison Android store");
    }

    Map<String,DexHistory.Spend> ownerProof(Order5 source,String want,String txid)throws Exception {
        DexHistory.Spend old=relock(source,want,4);
        DexHistory.Spend proof=new DexHistory.Spend(txid,0,old.outputs,"0xccee",4);
        proof.input=old.input;proof.inputCount=1;proof.transactionState=old.transactionState;proof.inclusionBlock=100;proof.inclusionBlockId="0xbbaa";
        proof.proofOrder=ChainEvidence.nextProofOrder();proof.proofTimeMs=System.currentTimeMillis();
        return Collections.singletonMap(source.coinid,proof);
    }
    String ownerJson(DexDb db,String id) {
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT json FROM ownerreceipt WHERE receiptid=?",new String[]{id})) {
            check(c.moveToFirst(),"owner archive row exists");return c.getString(0);
        }
    }
    long count(DexDb db,String table) {
        if(!Arrays.asList("ownerreceipt","mytrade","tape","chaincheck").contains(table))throw new AssertionError("invalid fixture table");
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}
    }
    ChainReview.Entry checkEntry(DexDb db,String txid) {
        for(ChainReview.Entry e:db.reviewBatch(120))if(e.txpowid.equalsIgnoreCase(txid))return e;
        throw new AssertionError("missing repeated-check entry");
    }
    void ownerHistory()throws Exception {
        reset();
        try(DexDb db=new DexDb(getTargetContext())) {
            android.database.sqlite.SQLiteDatabase raw=db.getWritableDatabase();
            check(raw.getVersion()==13,"fresh Android schema is13");
            raw.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('owner_history_fixture','preserve')");
            raw.execSQL("DROP TABLE ownerreceipt");raw.setVersion(11); // Only disposable audit database.
        }
        try(DexDb db=new DexDb(getTargetContext())) {
            check(db.getReadableDatabase().getVersion()==13,"actual11-to13 additive upgrade completed");
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k='owner_history_fixture'",null)) {
                check(c.moveToFirst()&&"preserve".equals(c.getString(0)),"upgrade preserves existing data");
            }
            Order5 source=order("0xaa");Pending p=db.pendingReceipts();DexTxn.Result cb=p.relockResult(source,new BigDecimal("2"),100,callback());
            check(cb.onPrepared("owner_history_fixture")&&cb.beforePost(),"owner history intent stored before fixture post");
            Pending.Row row=find(p,Pending.EDIT,source.coinid);OwnerReceipt receipt=OwnerReceipt.capture(row,ownerProof(source,"2","0xaabb"));db.recordOwnerReceipt(receipt);
            String original=ownerJson(db,row.receiptId);check(count(db,"ownerreceipt")==1&&count(db,"chaincheck")==1,"archive and repeated check committed together");
            check(count(db,"mytrade")==0&&count(db,"tape")==0,"owner operation does not invent trade volume");
            check(db.ownerReceipts(100).get(0).current()&&db.ownerReceipts(100).get(0).depth==4,"actual Android history shows returned depth");
            cb.onPosted("0xaabb");row=find(p,Pending.EDIT,source.coinid);
            db.recordOwnerReceipt(OwnerReceipt.capture(row,ownerProof(source,"2","0xaabb")));
            check(count(db,"ownerreceipt")==1&&original.equals(ownerJson(db,row.receiptId)),"idempotent replay preserves first archive and timestamp");
            Pending.Row other=Pending.Row.from(row.json());other.receiptId=java.util.UUID.randomUUID().toString();
            OwnerReceipt next=OwnerReceipt.capture(other,ownerProof(source,"2","0xccdd"));
            db.getWritableDatabase().execSQL("CREATE TEMP TRIGGER fail_owner_check BEFORE INSERT ON chaincheck BEGIN SELECT RAISE(ABORT,'fixture check commit failure'); END");
            boolean failed=false;try{db.recordOwnerReceipt(next);}catch(RuntimeException expected){failed=true;}
            db.getWritableDatabase().execSQL("DROP TRIGGER fail_owner_check");
            check(failed&&count(db,"ownerreceipt")==1&&count(db,"chaincheck")==1,"failed check insertion rolls back owner archive too");
            db.reviewed(checkEntry(db,"0xaabb"),new ChainReview.Evidence(ChainReview.MISSING,-1,0,"","fixture missing"),System.currentTimeMillis());
            check(!db.ownerReceipts(100).get(0).current()&&db.ownerReceipts(100).get(0).status().contains("not found"),"missing recheck stays visible without green confirmation");
            db.reviewed(checkEntry(db,"0xaabb"),new ChainReview.Evidence(ChainReview.CURRENT,11,100,"0xbbaa",""),System.currentTimeMillis());
            check(db.ownerReceipts(100).get(0).current()&&db.ownerReceipts(100).get(0).depth==11,"matching recheck restores actual depth");
            db.reviewed(checkEntry(db,"0xaabb"),new ChainReview.Evidence(ChainReview.CURRENT,10,101,"0xbeef",""),System.currentTimeMillis());
            check(!db.ownerReceipts(100).get(0).current()&&db.ownerReceipts(100).get(0).status().contains("reconciliation"),"moved inclusion cannot silently rewrite original proof");
        }
    }
    void ownerRecovery()throws Exception {
        String id=prefs("owner_audit_checkpoint").getString("owner_receipt_id","");check(!id.isEmpty(),"terminal identity survives real process death");
        try(DexDb db=new DexDb(getTargetContext())) {
            String archived=ownerJson(db,id);long before=count(db,"ownerreceipt");Pending p=db.pendingReceipts();
            Pending.Row row=find(p,Pending.EDIT,"0xdd");check(row.receiptId.equals(id),"pending cleanup gap survives with same identity");
            DexHistory.Spend proof=ownerProof(order("0xdd"),"2","0xabcd").get("0xdd");
            JSONObject tx=new JSONObject().put("txpowid",proof.txpowid).put("body",new JSONObject().put("txn",new JSONObject()
                    .put("inputs",new JSONArray().put(proof.input)).put("outputs",proof.outputs).put("state",proof.transactionState)));
            JSONObject history=new JSONObject().put("status",true).put("response",new JSONObject().put("txpows",new JSONArray().put(tx)));
            JSONObject included=new JSONObject().put("status",true).put("response",new JSONObject().put("found",true).put("confirmations",4).put("block",100).put("blockid","0xbbaa"));
            JSONObject unavailable=new JSONObject().put("status",false);int[] settled={0};
            DexHistory.Cmd fixture=(command,callback)->callback.onResult(command.startsWith("history ")?history:command.startsWith("txpow onchain:")?included:unavailable);
            p.reconcile(new DexHistory(fixture),Collections.emptyMap(),120,o->true,new Pending.Listener(){public void onLive(Pending.Row r){throw new AssertionError("not creation");}public void onSettled(Pending.Row r){settled[0]++;}});
            check(settled[0]==1&&p.rows().size()==4,"restarted archive replay clears only its matching pending receipt");
            check(before==count(db,"ownerreceipt")&&archived.equals(ownerJson(db,id)),"real restart replay preserves exactly one original archive");
        }
    }

    void seedOwner(android.database.sqlite.SQLiteDatabase db,String original,int n,long time)throws Exception {
        String id=String.format(java.util.Locale.ROOT,"audit_row_%03d",n),txid="0x"+String.format(java.util.Locale.ROOT,"%064x",n+100);
        JSONObject data=new JSONObject(original);data.getJSONObject("expected").put("receiptId",id);data.getJSONObject("proof").put("txpowid",txid);data.put("recorded_at",time);
        android.content.ContentValues v=new android.content.ContentValues();v.put("receiptid",id);v.put("outcome",n%2==0?"RENEWED":"EDITED");v.put("txpowid",txid);
        v.put("block",100);v.put("blockid","0xbbaa");v.put("blocktime",0);v.put("recordedat",time);v.put("json",data.toString());db.insertOrThrow("ownerreceipt",null,v);
        android.content.ContentValues c=new android.content.ContentValues();c.put("txpowid",txid);c.put("state",n%3==0?ChainReview.MISSING:ChainReview.CURRENT);
        c.put("depth",n%3==0?-1:n+4);c.put("block",100);c.put("blockid","0xbbaa");c.put("checkedat",1700000100000L);db.insertOrThrow("chaincheck",null,c);
    }
    android.widget.TextView textView(android.view.View root,String text) {
        if(root instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)root).getText()))return (android.widget.TextView)root;
        if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++){android.widget.TextView found=textView(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    void screenshot(OwnerHistoryAuditActivity activity,String name)throws Exception {
        waitForIdleSync();mainChecked(()->{
            android.view.View view=activity.getWindow().getDecorView();check(view.getWidth()>0&&view.getHeight()>0,"actual Activity viewport laid out");
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getFilesDir(),name))){check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"actual view screenshot encoded");}
            catch(java.io.IOException failure){throw new IllegalStateException(failure);}finally{bitmap.recycle();}
        });
    }
    void ownerAccess()throws Exception {
        try(DexDb db=new DexDb(getTargetContext())) {
            String original;try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT json FROM ownerreceipt LIMIT 1",null)){check(c.moveToFirst(),"prior owner archive fixture available");original=c.getString(0);}
            android.database.sqlite.SQLiteDatabase raw=db.getWritableDatabase();raw.beginTransaction();
            try{for(int i=0;i<131;i++)seedOwner(raw,original,i,1700000000000L+i/3);raw.setTransactionSuccessful();}finally{raw.endTransaction();}
            List<OwnerReceipt.Entry> first=db.ownerReceipts(50,null);Set<String> seen=new HashSet<>();for(OwnerReceipt.Entry e:first)check(seen.add(e.id),"first page identity unique");
            seedOwner(raw,original,999,System.currentTimeMillis()+100000);
            OwnerReceipt.Cursor cursor=first.get(first.size()-1).cursor();
            while(true){List<OwnerReceipt.Entry> page=db.ownerReceipts(50,cursor);if(page.isEmpty())break;for(OwnerReceipt.Entry e:page)check(seen.add(e.id),"older pages do not duplicate tied/new-arrival records");cursor=page.get(page.size()-1).cursor();}
            check(seen.size()==132&&!seen.contains("audit_row_999"),"all original rows reached despite a newer insertion");
            check("audit_row_999".equals(db.ownerReceipts(50,null).get(0).id),"Newest includes arriving record");
            TradeExport.Snapshot metadata=new TradeExport.Snapshot();metadata.exportedAtMs=System.currentTimeMillis();
            try(TradeExportFiles.Prepared prepared=TradeExportFiles.prepare(getTargetContext().getCacheDir(),metadata,db::captureExport,null)) {
                String owners="";int entries=0;
                try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.FileInputStream(prepared.zip))){java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null){entries++;java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();TradeExportFiles.copy(zip,bytes);if(e.getName().equals(TradeExport.FILE_OWNER_RECEIPTS))owners=bytes.toString("UTF-8");}}
                JSONArray archive=new JSONArray(owners);check(entries==7&&archive.length()==133,"real DB export includes all owner rows beyond view pages");
                boolean originalPresent=false,missing=false;for(int i=0;i<archive.length();i++){JSONObject e=archive.getJSONObject(i);if(original.equals(e.getString("original_receipt_json")))originalPresent=true;if(ChainReview.MISSING.equals(e.getJSONObject("last_saved_node_check").getString("state")))missing=true;}
                check(originalPresent&&missing,"export retains original bytes and missing node status");check(prepared.report.tradeCount==0&&prepared.report.totals.netMinima.signum()==0,"owner export does not invent trades");
                try(java.io.InputStream in=new java.io.FileInputStream(prepared.zip);java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getFilesDir(),"owner-reconciliation.zip"))){TradeExportFiles.copy(in,out);}
            }
        }
        OwnerHistoryAuditActivity activity=(OwnerHistoryAuditActivity)startActivitySync(new android.content.Intent(getTargetContext(),OwnerHistoryAuditActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitForIdleSync();mainChecked(()->{
                check(textView(activity.history,"Page 1 · 50 operations")!=null,"production view displays bounded first page");
                android.widget.TextView older=textView(activity.history,"Older");check(older!=null&&older.isEnabled(),"older navigation available");older.performClick();
                check(textView(activity.history,"Page 2 · 50 operations")!=null,"production older button advances page");
                textView(activity.history,"Newer").performClick();check(textView(activity.history,"Page 1 · 50 operations")!=null,"production newer button returns");
                textView(activity.history,"Export reconciliation ZIP").performClick();check(activity.exports==1,"production view invokes existing export action once");
                activity.show("normal",Design.Mode.ONYX);
            });screenshot(activity,"owner-history-dark.png");
            mainChecked(()->{
                activity.show("normal",Design.Mode.DAYLIGHT);
                check(textView(activity.history,"Page 1 · 50 operations").getCurrentTextColor()==Design.TEXT(),"light secondary text uses existing readable primary ink");
                check(textView(activity.history,"Export reconciliation ZIP").getCurrentTextColor()==Design.TEXT(),"light action text stays readable");
            });screenshot(activity,"owner-history-light.png");
            mainChecked(()->check(textView(activity.history,"Older").getHeight()>=Design.dp(activity,48),"rendered navigation has at least 48dp tap height"));
            mainChecked(()->{activity.show("empty",Design.Mode.ONYX);check(textView(activity.history,"Page 1 · 0 operations")!=null,"empty history renders explicitly");check(textView(activity.history,"Export reconciliation ZIP").isEnabled(),"empty trade/history view retains export access");});screenshot(activity,"owner-history-empty.png");
            mainChecked(()->{activity.show("error",Design.Mode.ONYX);check(textView(activity.history,"Saved operations could not be read. Preserve app data for recovery.")!=null,"read failure is visible");});screenshot(activity,"owner-history-error.png");
        }finally{mainChecked(activity::finish);waitForIdleSync();}
    }

    void archiveIntegrity()throws Exception {
        JSONObject expected=new JSONObject().put("intent","audit29_sweep").put("sources",new JSONArray().put("0xfaaa").put("0xfbbb"))
            .put("transactionid","0xfccd").put("payout","0xfee1").put("token","0x00").put("proceeds","100").put("minima","100")
            .put("price","0.01").put("buy",true).put("source","BOOK+POOL");
        Map<String,DexHistory.Spend> found=new LinkedHashMap<>();
        JSONArray outputs=new JSONArray().put(new JSONObject().put("address","0xfee1").put("tokenid","0x00").put("amount","100"));
        for(int i=0;i<2;i++) {
            String id=i==0?"0xfaaa":"0xfbbb";DexHistory.Spend proof=new DexHistory.Spend("0xfabc",i,outputs,"0xfccd",4);
            proof.input=new JSONObject().put("coinid",id).put("amount","50");proof.inputCount=3;
            proof.inclusionBlock=100;proof.inclusionBlockId="0xfbee";proof.inclusionTimeMs=1700000000000L;
            proof.proofOrder=ChainEvidence.nextProofOrder();proof.proofTimeMs=System.currentTimeMillis();found.put(id,proof);
        }
        TakerReceipt receipt=TakerReceipt.capture(expected,found);String original=receipt.json;
        DexHistory.Spend input=found.get("0xfaaa");input.inclusionBlock=999;input.inclusionBlockId="0xeeee";input.inclusionTimeMs=1;
        input.input.put("coinid","0xffff");input.outputs.getJSONObject(0).put("amount","999");input.transactionState.put(new JSONObject().put("port",1).put("data","bad"));
        check(receipt.spend.inclusionBlock==100&&"0xfbee".equals(receipt.spend.inclusionBlockId),"captured taker coordinates detached from caller");
        check("100".equals(receipt.spend.outputs.getJSONObject(0).getString("amount"))&&receipt.spend.transactionState.length()==0,"captured Android output/state detached");
        try(DexDb db=new DexDb(getTargetContext())) {
            check(db.recordTakerFill(receipt),"detached taker receipt commits to real SQLite");
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT m.block,m.timems,t.block,t.blockid,t.json FROM mytrade m JOIN takerreceipt t ON t.spentcoin=m.spentcoin WHERE m.spentcoin='0xfaaa'",null)) {
                check(c.moveToFirst()&&c.getLong(0)==100&&c.getLong(1)==1700000000000L&&c.getLong(2)==100&&"0xfbee".equals(c.getString(3))&&original.equals(c.getString(4)),"SQLite trade and receipt retain matching original proof after caller mutation");
            }
        }
        List<String> damaged=new ArrayList<>();
        for(String suffix:new String[]{"{}","//ignored","/*ignored*/",String.valueOf((char)0)})damaged.add(original+suffix);
        for(Object version:new Object[]{1.5,"1.5","9223372036854775809"})damaged.add(new JSONObject(original).put("version",version).toString());
        damaged.add(new JSONObject(original).put("padding",new String(new char[TakerReceipt.MAX_BYTES]).replace('\0','x')).toString());
        for(String raw:damaged) {
            check(!receipt.sameIntent(raw),"damaged Android archive cannot authorize matching");boolean refused=false;
            try{new TakerRecovery.Entry("0xfaaa","0xfabc",100,"0xfbee",raw).intent();}catch(RuntimeException invalid){refused=true;}
            check(refused,"damaged Android archive cannot dispatch recovery");
        }
        JSONObject fractional=new JSONObject(original);fractional.getJSONObject("proof").put("block",100.5);boolean refused=false;
        try{new TakerRecovery.Entry("0xfaaa","0xfabc",100,"0xfbee",fractional.toString()).intent();}catch(RuntimeException invalid){refused=true;}
        check(refused,"Android numeric getter cannot truncate archived inclusion");
        check(receipt.sameIntent(original+" \r\n\t"),"valid Android whitespace remains compatible");
    }

    void ownerRevisions()throws Exception {
        try(DexDb db=new DexDb(getTargetContext())) {
            Order5 source=order("0xf123");Pending pending=db.pendingReceipts();DexTxn.Result journal=pending.relockResult(source,new BigDecimal("2"),100,callback());
            check(journal.onPrepared("owner_revision_fixture")&&journal.beforePost(),"revision fixture intent acknowledged");
            Pending.Row row=find(pending,Pending.EDIT,source.coinid);
            OwnerReceipt original=OwnerReceipt.capture(row,ownerProof(source,"2","0xfb12"));db.recordOwnerReceipt(original);
            String before=ownerJson(db,row.receiptId);long trades=count(db,"mytrade"),receipts=count(db,"ownerreceipt");
            Map<String,DexHistory.Spend> moved=ownerProof(source,"2","0xfb12");DexHistory.Spend proof=moved.get(source.coinid);
            proof.inclusionBlock=111;proof.inclusionBlockId="0xf222";proof.inclusionTimeMs=1700000000000L;
            OwnerReceipt replacement=OwnerReceipt.capture(row,moved);
            for(String table:new String[]{"receiptaudit","ownerreceipt"}) {
                db.getWritableDatabase().execSQL("CREATE TEMP TRIGGER fail_revision BEFORE "+(table.equals("receiptaudit")?"INSERT":"UPDATE")+" ON "+table+" BEGIN SELECT RAISE(ABORT,'fixture revision failure'); END");
                boolean refused=false;try{db.recordOwnerReceipt(replacement);}catch(RuntimeException expected){refused=true;}
                db.getWritableDatabase().execSQL("DROP TRIGGER fail_revision");
                check(refused&&before.equals(ownerJson(db,row.receiptId)),"archive/update failure preserves original owner receipt");
                try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM receiptaudit WHERE coinid=?",new String[]{"owner:"+row.receiptId})) {check(c.moveToFirst()&&c.getInt(0)==0,"failed revision leaves no partial archive");}
            }
            JSONObject tx=new JSONObject().put("txpowid",proof.txpowid).put("body",new JSONObject().put("txn",new JSONObject().put("inputs",new JSONArray().put(proof.input)).put("outputs",proof.outputs).put("state",proof.transactionState)));
            JSONObject history=new JSONObject().put("status",true).put("response",new JSONObject().put("txpows",new JSONArray().put(tx)));
            JSONObject included=new JSONObject().put("status",true).put("response",new JSONObject().put("found",true).put("confirmations",8).put("block",111).put("blockid","0xf222"));
            JSONObject block=new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid","0xf222").put("isblock",true).put("istransaction",false)
                .put("header",new JSONObject().put("block",111).put("timemilli",1700000000000L)).put("body",new JSONObject().put("txnlist",new JSONArray().put("0xfb12"))));
            int[] settled={0};pending.reconcile(new DexHistory((command,cb)->cb.onResult(command.startsWith("history ")?history:command.startsWith("txpow onchain:")?included:block)),Collections.emptyMap(),120,o->true,
                new Pending.Listener(){public void onLive(Pending.Row r){throw new AssertionError("not creation");}public void onSettled(Pending.Row r){settled[0]++;}});
            check(settled[0]==1&&pending.rows().stream().noneMatch(r->r.receiptId.equals(row.receiptId)),"fresh moved inclusion clears actual pending cleanup gap once");
            String revised=ownerJson(db,row.receiptId);JSONObject data=new JSONObject(revised);
            check(data.getLong("recorded_at")==original.recordedAt&&data.getJSONObject("proof").getLong("block")==111,"revision preserves first observation and records actual new inclusion");
            check(count(db,"mytrade")==trades&&count(db,"ownerreceipt")==receipts,"owner revision neither duplicates operation nor invents trade");
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT snapshot FROM receiptaudit WHERE coinid=?",new String[]{"owner:"+row.receiptId})) {
                check(c.moveToFirst()&&before.equals(new JSONObject(c.getString(0)).getJSONObject("previous_owner_receipt").getString("json")),"earlier original bytes retained in correction archive");
                check(!c.moveToNext(),"exactly one revision archived");
            }
            boolean stale=false;try{db.recordOwnerReceipt(original);}catch(ChainReview.Conflict expected){stale=true;}
            check(stale&&revised.equals(ownerJson(db,row.receiptId)),"older inclusion cannot undo accepted revision");
            Map<String,DexHistory.Spend> completeCompetitor=ownerProof(source,"2","0xf333");completeCompetitor.get(source.coinid).inclusionTimeMs=1700000000100L;
            OwnerReceipt competing=OwnerReceipt.capture(row,completeCompetitor);
            boolean refused=false;try{db.recordOwnerReceipt(competing);}catch(ChainReview.Conflict expected){refused=true;}
            check(refused&&revised.equals(ownerJson(db,row.receiptId)),"different spender cannot replace a currently included transaction");
            // A complete alternative remains blocked until the old winner is explicitly missing.
            long revision;try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT revision FROM chaincheck WHERE txpowid='0xfb12'",null)){check(c.moveToFirst(),"revised transaction is queued for repeated checks");revision=c.getLong(0);}
            db.reviewed(new ChainReview.Entry("0xfb12",revision),new ChainReview.Evidence(ChainReview.MISSING,-1,0,"",""),System.currentTimeMillis());
            Map<String,DexHistory.Spend> alternative=ownerProof(source,"2","0xf333");alternative.get(source.coinid).inclusionTimeMs=1700000000100L;
            OwnerReceipt freshAlternative=OwnerReceipt.capture(row,alternative);db.recordOwnerReceipt(freshAlternative);
            check(new JSONObject(ownerJson(db,row.receiptId)).getJSONObject("proof").getString("txpowid").equals("0xf333"),"ordered missing then fresh complete alternative permits revision");
            TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.fromMs=0;snapshot.toMs=Long.MAX_VALUE;
            try(TradeExportFiles.Prepared prepared=TradeExportFiles.prepare(getTargetContext().getCacheDir(),snapshot,db::captureExport,null)) {
                String corrections="";try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.FileInputStream(prepared.zip))){java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();TradeExportFiles.copy(zip,bytes);if(e.getName().equals(TradeExport.FILE_CORRECTIONS))corrections=bytes.toString("UTF-8");}}
                JSONArray archive=new JSONArray(corrections);int matched=0;boolean originalFound=false;
                for(int i=0;i<archive.length();i++){JSONObject item=archive.getJSONObject(i);if(item.getString("coinid").equals("owner:"+row.receiptId)){matched++;if(before.equals(item.getJSONObject("evidence").getJSONObject("previous_owner_receipt").getString("json")))originalFound=true;}}
                check(matched==2&&originalFound,"full export includes both owner revisions even without a personal trade for that receipt");
            }
        }
    }

    void markOwnerMissing(DexDb db,String txid)throws Exception {
        long revision;try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT revision FROM chaincheck WHERE txpowid=?",new String[]{txid})){if(!c.moveToFirst())throw new AssertionError("missing check");revision=c.getLong(0);}
        db.reviewed(new ChainReview.Entry(txid,revision),new ChainReview.Evidence(ChainReview.MISSING,-1,0,"",""),System.currentTimeMillis());
    }
    int ownerNeeds(DexDb db,String id) {
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT needs_review FROM ownerreceipt WHERE receiptid=?",new String[]{id})){if(!c.moveToFirst())throw new AssertionError("missing owner");return c.getInt(0);}
    }
    OwnerRecovery.Entry queuedOwner(DexDb db,String id) {
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT receiptid,txpowid,block,blockid,json FROM ownerreceipt WHERE receiptid=?",new String[]{id})) {
            if(!c.moveToFirst())throw new AssertionError("missing owner");return new OwnerRecovery.Entry(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4));
        }
    }
    Map<String,DexHistory.Spend> queueProof(String txid,long block,String blockid)throws Exception {
        Map<String,DexHistory.Spend> found=ownerProof(order("0xf123"),"2",txid);DexHistory.Spend proof=found.get("0xf123");
        proof.inclusionBlock=block;proof.inclusionBlockId=blockid;proof.inclusionTimeMs=1700000000000L+block;return found;
    }
    void ownerQueue()throws Exception {
        String id,original;long records;
        try(DexDb db=new DexDb(getTargetContext())) {
            try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT receiptid,json FROM ownerreceipt WHERE txpowid='0xf333'",null)){check(c.moveToFirst(),"completed revised owner exists");id=c.getString(0);original=c.getString(1);}
            check(db.pendingReceipts().rows().stream().noneMatch(r->r.receiptId.equals(id)),"completed queue fixture has no pending receipt");
            markOwnerMissing(db,"0xf333");check(ownerNeeds(db,id)==1,"accepted missing check queues completed owner atomically");records=count(db,"ownerreceipt");
            android.database.sqlite.SQLiteDatabase raw=db.getWritableDatabase();raw.execSQL("ALTER TABLE ownerreceipt RENAME TO ownerreceipt_audit13");
            raw.execSQL("CREATE TABLE ownerreceipt (receiptid TEXT PRIMARY KEY, outcome TEXT NOT NULL, txpowid TEXT NOT NULL COLLATE NOCASE, block INTEGER NOT NULL, blockid TEXT NOT NULL, blocktime INTEGER NOT NULL, recordedat INTEGER NOT NULL, json TEXT NOT NULL)");
            raw.execSQL("INSERT INTO ownerreceipt SELECT receiptid,outcome,txpowid,block,blockid,blocktime,recordedat,json FROM ownerreceipt_audit13");raw.execSQL("DROP TABLE ownerreceipt_audit13");raw.setVersion(12);
        }
        try(DexDb db=new DexDb(getTargetContext())) {
            check(db.getReadableDatabase().getVersion()==13&&count(db,"ownerreceipt")==records&&original.equals(ownerJson(db,id)),"actual12-to13 additive upgrade preserves every owner and original bytes");
            check(ownerNeeds(db,id)==1,"migration seeds completed owner needing recheck");
            // Isolate this fixture from audit22's large synthetic history rows.
            db.getWritableDatabase().execSQL("UPDATE ownerreceipt SET needs_review=0 WHERE receiptid<>?",new Object[]{id});
            db.getWritableDatabase().execSQL("DELETE FROM meta WHERE k='owner_recovery_turn'");
            OwnerRecovery.Entry entry=queuedOwner(db,id);Map<String,DexHistory.Spend> moved=queueProof("0xf333",121,"0xf444");OwnerReceipt replacement=OwnerReceipt.capture(entry.intent(),moved);
            db.getWritableDatabase().execSQL("CREATE TEMP TRIGGER fail_owner_queue BEFORE UPDATE ON ownerreceipt WHEN NEW.needs_review=0 BEGIN SELECT RAISE(ABORT,'fixture queue failure'); END");
            boolean refused=false;try{db.repairOwner(entry,replacement);}catch(RuntimeException expected){refused=true;}
            db.getWritableDatabase().execSQL("DROP TRIGGER fail_owner_queue");
            check(refused&&original.equals(ownerJson(db,id))&&ownerNeeds(db,id)==1,"failed queue retirement rolls back correction and retains retry");
            int[] changed={0};new OwnerRecovery(db,(sources,cb)->{check(sources.size()==1&&sources.contains("0xf123"),"completed owner recovers original source without pending state");cb.onSpends(moved);cb.onSpends(moved);})
                .run((attempted,updated,error)->{check(attempted&&updated&&error.isEmpty(),"real owner queue accepts fresh linked proof");changed[0]++;});
            check(changed[0]==1&&ownerNeeds(db,id)==0,"accepted completed recovery retires queue exactly once");
            String revised=ownerJson(db,id);check(new JSONObject(revised).getLong("recorded_at")==new JSONObject(original).getLong("recorded_at"),"completed recovery preserves original observation");
            check(!db.repairOwner(entry,replacement)&&revised.equals(ownerJson(db,id)),"obsolete queued snapshot cannot overwrite accepted revision");
            check(!db.claimOwnerTurn()&&db.claimOwnerTurn(),"persisted owner turns alternate with other settlement work");
            prefs("owner_audit_checkpoint").edit().putString("owner_queue_id",id).putString("owner_queue_json",revised).commit();
            db.getWritableDatabase().execSQL("UPDATE ownerreceipt SET needs_review=0");
            List<String> ids=new ArrayList<>();try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT receiptid FROM ownerreceipt ORDER BY receiptid LIMIT 9",null)){while(c.moveToNext())ids.add(c.getString(0));}
            for(String selected:ids)db.getWritableDatabase().execSQL("UPDATE ownerreceipt SET needs_review=1,retry=0 WHERE receiptid=?",new Object[]{selected});
            Set<String> seen=new HashSet<>();for(int page=0;page<3;page++){List<OwnerRecovery.Entry> batch=db.ownerBatch(100);check(batch.size()<=4,"actual owner batch caps at four");for(OwnerRecovery.Entry e:batch)seen.add(e.id);}
            check(seen.containsAll(ids)&&seen.size()==9,"durable retry rotation reaches all nine queued receipts");
            db.getWritableDatabase().execSQL("UPDATE ownerreceipt SET needs_review=0");
        }
    }
    void queueOwnerForDeath(DexDb db)throws Exception {
        String id=prefs("owner_audit_checkpoint").getString("owner_queue_id","");check(!id.isEmpty(),"completed owner identity checkpoint available before death");
        markOwnerMissing(db,"0xf333");check(ownerNeeds(db,id)==1,"completed owner queue committed before process death");
        db.getWritableDatabase().execSQL("DELETE FROM meta WHERE k='owner_recovery_turn'");
    }
    void ownerQueueAfterDeath()throws Exception {
        String id=prefs("owner_audit_checkpoint").getString("owner_queue_id","");String original=prefs("owner_audit_checkpoint").getString("owner_queue_json","");
        try(DexDb db=new DexDb(getTargetContext())) {
            check(ownerNeeds(db,id)==1&&original.equals(ownerJson(db,id)),"completed owner queue and original bytes survive different-process restart");
            Map<String,DexHistory.Spend> moved=queueProof("0xf333",131,"0xf555");
            new OwnerRecovery(db,(sources,cb)->cb.onSpends(moved)).run((attempted,changed,error)->check(attempted&&changed&&error.isEmpty(),"restarted automatic owner queue repairs new inclusion"));
            check(ownerNeeds(db,id)==0&&new JSONObject(ownerJson(db,id)).getJSONObject("proof").getLong("block")==131,"restarted owner queue commits and retires atomically");
        }
    }

    final class IdleTxn extends DexTxn {
        Result parked;int calls;IdleTxn(){super(null,null);}
        @Override public void cancelBatch(List<Order5> orders,Result cb){calls++;parked=cb;}
    }
    void makerIdle()throws Exception {
        Order5 source=order("0xd123");
        mainChecked(()->{
            check(Looper.myLooper()==Looper.getMainLooper(),"maker queue fixture runs on actual Android main looper");
            MakerConfig config=new MakerConfig();IdleTxn tx=new IdleTxn();MakerEngine engine=new MakerEngine(config,tx),other=new MakerEngine(config,tx);List<Integer> events=new ArrayList<>();
            engine.cancelAllLadder(Collections.singletonList(source),0,200,null);DexTxn.Result first=tx.parked;
            engine.runWhenIdle(()->events.add(1));
            other.runWhenIdle(()->{events.add(2);other.cancelAllLadder(Collections.singletonList(source),0,201,null);});
            engine.runWhenIdle(()->events.add(3));
            check(events.isEmpty()&&engine.isWorking(),"deferred requests remain queued during first chain");first.onPosted("0xbe11");
            check(events.equals(Arrays.asList(1,2))&&engine.isWorking()&&tx.calls==2,"all hosts retain order and wait for newly started chain");
            first.onFailed("duplicate old callback");check(events.size()==2,"duplicate callback cannot release second chain");tx.parked.onPosted("0xbe12");
            check(events.equals(Arrays.asList(1,2,3))&&!engine.isWorking(),"third request runs only after second async completion");
            List<String> nested=new ArrayList<>();engine.runWhenIdle(()->{nested.add("enter");engine.runWhenIdle(()->nested.add("nested"));nested.add("return");});
            check(nested.equals(Arrays.asList("enter","return","nested")),"reentrant request waits for enclosing action to return");
            engine.cancelAllLadder(Collections.singletonList(source),0,202,null);List<Integer> ready=new ArrayList<>();
            engine.stopForCancelAll(203,null,()->ready.add(1));other.stopForCancelAll(204,null,()->ready.add(2));tx.parked.onPosted("0xbe13");
            check(ready.equals(Arrays.asList(1,2))&&!engine.isWorking(),"two stop requests retain both ready callbacks");
            engine.cancelAllLadder(Collections.singletonList(source),0,205,null);List<String> failure=new ArrayList<>();
            engine.runWhenIdle(()->{failure.add("throws");throw new IllegalStateException("fixture view gone");});engine.runWhenIdle(()->failure.add("next"));
            boolean thrown=false;try{tx.parked.onPosted("0xbe14");}catch(IllegalStateException expected){thrown=true;}
            check(thrown&&failure.equals(Arrays.asList("throws","next"))&&!engine.isWorking(),"failed callback preserves later queued request and existing exception behavior");
            engine.runWhenIdle(()->failure.add("later"));check(failure.size()==3,"deferred queue remains usable after failure");
        });
    }

    void makerSnapshots()throws Exception {
        reset();MakerConfig first=maker(),stale=new MakerConfig(getTargetContext());
        first.armed=false;check(first.saveUserAction(),"new maker pause acknowledged in real preferences");Map<String,?> saved=new HashMap<>(prefs("pandadex_maker").getAll());
        stale.stepPct=new BigDecimal("0.4");check(!stale.save()&&saved.equals(prefs("pandadex_maker").getAll()),"stale Android writer cannot restore old armed settings");
        check(MakerConfig.settingsChangedElsewhere()&&!MakerConfig.storageHealthy()&&MakerConfig.storageFailureMessage().contains("Reload"),"settings conflict pauses quoting with distinct recovery guidance");
        stale.reload();check(!stale.armed&&!MakerConfig.storageHealthy(),"reload adopts acknowledged pause without automatically clearing failure");
        stale.stepPct=new BigDecimal("0.3");check(stale.saveUserAction()&&MakerConfig.storageHealthy()&&!MakerConfig.settingsChangedElsewhere(),"explicit reviewed save clears conflict");
        first.reload();MakerConfig oldPrepared=new MakerConfig(getTargetContext());check(first.prepareCreate("A2","0xbe21",BigDecimal.TEN,120),"prepared create acknowledged");
        saved=new HashMap<>(prefs("pandadex_maker").getAll());check(!oldPrepared.prepareCreate("A3","0xbe22",BigDecimal.TEN,121)&&saved.equals(prefs("pandadex_maker").getAll()),"stale prepare cannot erase another acknowledged prepared identity");
        first.reload();MakerConfig oldTracking=new MakerConfig(getTargetContext());first.rememberSlot("A3","0xbe23",BigDecimal.TEN,122);check(first.stopAndTrackWithdrawal(123),"latest Android withdrawal identities retained");
        saved=new HashMap<>(prefs("pandadex_maker").getAll());check(!oldTracking.saveUserAction()&&saved.equals(prefs("pandadex_maker").getAll()),"stale explicit settings save cannot erase newer slot/tombstone records");
        MakerConfig reopened=new MakerConfig(getTargetContext());check(reopened.slots.containsKey("A3")&&reopened.cancelTombstones.containsKey("0xbe23")&&!reopened.armed,"reopened Android state retains latest pause and tracking");
        check(prefs("pandadex_maker").edit().putString("audit_extra","keep").commit(),"unrelated preference fixture saved");
        check(reopened.saveUserAction()&&"keep".equals(prefs("pandadex_maker").getString("audit_extra","")),"unrelated preference survives maker save without false conflict");
        MakerConfig a=new MakerConfig(getTargetContext()),b=new MakerConfig(getTargetContext());a.manualMid=new BigDecimal("20");b.manualMid=new BigDecimal("30");
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2),go=new java.util.concurrent.CountDownLatch(1);
        boolean[] accepted={false,false};java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread one=new Thread(()->{ready.countDown();try{go.await();accepted[0]=a.saveUserAction();}catch(Throwable t){failure.set(t);}},"maker-audit-writer-one");
        Thread two=new Thread(()->{ready.countDown();try{go.await();accepted[1]=b.saveUserAction();}catch(Throwable t){failure.set(t);}},"maker-audit-writer-two");
        one.start();two.start();boolean started=ready.await(5,java.util.concurrent.TimeUnit.SECONDS);go.countDown();one.join(5000);two.join(5000);
        check(started&&!one.isAlive()&&!two.isAlive()&&failure.get()==null,"concurrent Android writers complete without lock failure");
        check(accepted[0]!=accepted[1],"exactly one writer can save from the shared starting snapshot");
        MakerConfig winner=new MakerConfig(getTargetContext());check(winner.manualMid.compareTo(new BigDecimal(accepted[0]?"20":"30"))==0&&!winner.armed,"stored state belongs to accepted writer and preserves pause");
        check(!MakerConfig.storageHealthy()&&MakerConfig.settingsChangedElsewhere(),"losing concurrent writer leaves explicit-review guard active");
        check(winner.saveUserAction(),"test acknowledges latest winner before later existing crash fixtures");
    }

}

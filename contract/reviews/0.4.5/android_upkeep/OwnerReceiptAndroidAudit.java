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
        if("normal".equals(phase)){normal();keyLoading();upkeep();}else if("crash".equals(phase))crash();else if("recover".equals(phase))recover();else throw new AssertionError("unknown phase");
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
        check(prefs("owner_audit_checkpoint").edit().putInt("pid",android.os.Process.myPid()).putInt("checks",checks)
                .putInt("build",18).putBoolean("ready",true).commit(),"crash checkpoint committed");
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("process did not exit");
    }
    void recover()throws Exception {
        SharedPreferences checkpoint=prefs("owner_audit_checkpoint");
        check(checkpoint.getBoolean("ready",false)&&checkpoint.getInt("build",0)==18,"prior crash checkpoint belongs to audit18");
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

}

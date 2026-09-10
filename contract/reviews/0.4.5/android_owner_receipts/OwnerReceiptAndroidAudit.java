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
        if("normal".equals(phase))normal();else if("crash".equals(phase))crash();else if("recover".equals(phase))recover();else throw new AssertionError("unknown phase");
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
        check(prefs("owner_audit_checkpoint").edit().putInt("pid",android.os.Process.myPid()).putInt("checks",checks)
                .putInt("build",17).putBoolean("ready",true).commit(),"crash checkpoint committed");
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("process did not exit");
    }
    void recover()throws Exception {
        SharedPreferences checkpoint=prefs("owner_audit_checkpoint");
        check(checkpoint.getBoolean("ready",false)&&checkpoint.getInt("build",0)==17,"prior crash checkpoint belongs to audit17");
        check(checkpoint.getInt("pid",0)>0&&checkpoint.getInt("pid",0)!=android.os.Process.myPid(),"recovery uses a different actual Android process");
        check(checkpoint.getInt("checks",0)>=8,"pre-death assertions completed");
        MakerConfig c=new MakerConfig(getTargetContext());check(c.readable()&&!c.armed,"acknowledged maker pause survives process death");
        check(c.cancelTombstones.containsKey("0xcc")&&c.cancelTombstones.containsKey("0xdd"),"both withdrawal identities survive");
        check("0xcc".equals(c.orderIdFor("A1"))&&"0xdd".equals(c.preparedOrderId()),"original slot and prepared intent survive");
        Pending p=new Pending(getTargetContext());check(p.healthy()&&p.rows().size()==4,"every owner receipt survives actual death");
        for(String coin:new String[]{"0xaa","0xbb"}) {Pending.Row r=find(p,Pending.CANCEL,coin);check("POSTING".equals(r.phase),"lost cancellation reply remains unknown POSTING");check(Pending.cancelSourceMatches(r,order(coin)),"cancellation source survives process death");}
        Pending.Row edit=find(p,Pending.EDIT,"0xdd");check("POSTING".equals(edit.phase)&&"2".equals(edit.editWant),"exact relock expectation survives death");
        check(Pending.editMatches(edit,relock(order("0xdd"),"2",3),order("0xdd")),"restarted intent verifies exact successor fixture");
        Pending.Row renewal=find(p,Pending.EDIT,"0xff");check("PREPARED".equals(renewal.phase)&&renewal.isRenewal(),"prepared renewal remains unsubmitted evidence");
        check(renewal.status(110).contains("submission is not confirmed"),"restarted prepared status does not invent submission");
    }
}

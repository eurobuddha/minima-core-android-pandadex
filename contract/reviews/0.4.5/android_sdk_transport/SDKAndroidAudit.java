package org.minimarex.minimaapi;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Adapted from base ApiInputTest. Every outgoing SDK broadcast is intercepted here. */
public final class SDKAndroidAudit extends Instrumentation {
    private int checks;
    private final StringBuilder passed=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            fresh(); malformed(); fileReplies(); backlogAndExpiry(); pairing();
            result.putString("stream","PASS "+checks+" assertions\n"+passed);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure){
            result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private SharedPreferences prefs(){return getTargetContext().getSharedPreferences("minima_api_prefs",Context.MODE_PRIVATE);}
    private void fresh(){check(prefs().edit().clear().commit(),"clean disposable pairing preferences");}
    private void uncertain(JSONObject reply){check(reply!=null&&!reply.has("status")&&!reply.has("enabled")&&reply.has("transporterror"),"transport problem remains unknown");}
    final class Capture extends ContextWrapper {
        volatile Intent last; final AtomicInteger sent=new AtomicInteger();
        boolean failCommit,throwSend;
        Capture(){super(getTargetContext());}
        @Override public void sendBroadcast(Intent intent){
            last=intent;sent.incrementAndGet();
            if(throwSend)throw new SecurityException("injected dispatch failure");
            // Never call super: no Minima node can be contacted by this harness.
        }
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences real=super.getSharedPreferences(name,mode);
            if(!failCommit)return real;
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
                Object value=m.invoke(real,a);
                if(!m.getName().equals("edit"))return value;
                SharedPreferences.Editor editor=(SharedPreferences.Editor)value;
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(ep,em,ea)->{
                    Object actual=em.invoke(editor,ea);
                    if(em.getName().equals("commit"))return false; // simulated failed durability acknowledgment
                    return actual instanceof SharedPreferences.Editor?ep:actual;
                });
            });
        }
        Intent reply(){return new Intent(MinimaAPIMessages.MINIMA_API_RESPONSE)
            .putExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID,last.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID))
            .putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID,last.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID));}
        String id(){return last.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID);}
    }
    private void malformed() throws Exception {
        Capture c=new Capture();AtomicInteger calls=new AtomicInteger();AtomicReference<JSONObject> result=new AtomicReference<>();
        MinimaAPI api=new MinimaAPI(c,j->{calls.incrementAndGet();result.set(j);});
        try {
            check(c.sent.get()==1&&MinimaAPIMessages.MINIMA_BASE_CLASS.equals(c.last.getPackage()),"registration keeps explicit stock node package");
            check(MinimaAPI.validPairingId(c.id())&&MinimaAPI.validPairingId(prefs().getString("myapp_uid","")),"secure-format persisted IDs");
            MinimaAPIReceive receiver=new MinimaAPIReceive(api);
            receiver.onReceive(c,null);receiver.onReceive(c,new Intent(MinimaAPIMessages.MINIMA_API_RESPONSE));
            api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID,""));
            api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID,"unknown"));
            api.ResponseReceived(c.reply());
            api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI,"file:///private"));
            api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,7));
            check(calls.get()==0&&api.mResponseHandlers.size()==1,"malformed unauthenticated and payloadless replies ignored");
            check(!MinimaAPI.checkMinimaID(c,null)&&!MinimaAPI.checkMinimaID(c,new Intent()),"notification null and absent auth rejected");
            check(MinimaAPI.checkMinimaID(c,c.reply()),"paired notification ID recognized");
            Intent valid=c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{\"status\":true}");
            receiver.onReceive(c,valid);receiver.onReceive(c,valid);
            check(calls.get()==1&&result.get().getBoolean("status")&&api.mResponseHandlers.isEmpty(),"one authenticated delivery consumes handler");
            check(api.expiries.isEmpty()&&api.claimed.isEmpty(),"successful reply removes deadline and claim");
            for(String value:new String[]{"{invalid","[]","{\"status\":true}junk"}){
                result.set(null);api.Command("status",result::set);
                api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,value));uncertain(result.get());
            }
            api.Command("status",result::set);api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{\"status\":false,\"error\":\"real rejection\"}"));
            check(Boolean.FALSE.equals(result.get().opt("status")),"actual complete node rejection preserved");
            api.Command("status",j->calls.incrementAndGet());Intent late=c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{}");
            api.onDestroy();api.ResponseReceived(late);
            check(calls.get()==1&&api.mResponseHandlers.isEmpty()&&api.expiries.isEmpty(),"destroy drops late callbacks and all pending deadlines");
        } finally {api.onDestroy();}
        passed.append("authentication, malformed payloads, exactly-once delivery and destruction\n");
    }
    private File responseFile(String name,byte[] bytes) throws Exception {
        File dir=new File(getTargetContext().getCacheDir(),"sdk-audit");dir.mkdirs();File f=new File(dir,name);Files.write(f.toPath(),bytes);return f;
    }
    private String uri(File f){return FileProvider.getUriForFile(getTargetContext(),"com.eurobuddha.pandadex.audit.replies",f).toString();}
    private JSONObject fileResponse(MinimaAPI api,Capture c,String uri) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> result=new AtomicReference<>();
        api.Command("status",j->{result.set(j);done.countDown();});
        api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI,uri));
        check(done.await(5,TimeUnit.SECONDS),"file response completed");return result.get();
    }
    private void fileReplies() throws Exception {
        Capture c=new Capture();MinimaAPI api=new MinimaAPI(c,j->{});
        api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{}"));
        File good=responseFile("good.json","{\"status\":true,\"response\":\"large\"}".getBytes(StandardCharsets.UTF_8));
        File big=responseFile("big.json",new byte[MinimaAPIResponse.MAX_BYTES+1]);
        CountDownLatch hold=new CountDownLatch(1),started=new CountDownLatch(1);
        try {
            JSONObject got=fileResponse(api,c,uri(good));check(got.getBoolean("status")&&"large".equals(got.getString("response")),"real granted FileProvider stream works");
            uncertain(fileResponse(api,c,uri(big)));
            File missing=new File(good.getParentFile(),"absent.json");uncertain(fileResponse(api,c,uri(missing)));
            api.mFileExecutor.execute(()->{started.countDown();try{hold.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
            check(started.await(5,TimeUnit.SECONDS),"controlled reader stall started");
            AtomicInteger delivered=new AtomicInteger();CountDownLatch done=new CountDownLatch(2);
            api.Command("status",j->{delivered.incrementAndGet();done.countDown();});Intent duplicate=c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI,uri(good));
            for(int n=0;n<100;n++)api.ResponseReceived(duplicate);
            check(api.mFileExecutor.getQueue().size()==1&&api.claimed.size()==1,"100 duplicate broadcasts queue only one file read");
            api.Command("status",j->{delivered.incrementAndGet();done.countDown();});api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI,uri(good)));
            AtomicReference<JSONObject> rejected=new AtomicReference<>();api.Command("status",rejected::set);
            api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI,uri(good)));
            uncertain(rejected.get());check(api.mFileExecutor.getQueue().size()==2&&api.mFileExecutor.getPoolSize()==1,"backlog stays two tasks with one physical worker");
            hold.countDown();check(done.await(5,TimeUnit.SECONDS)&&delivered.get()==2,"accepted unique file reads complete once after stall clears");
            check(api.mResponseHandlers.isEmpty()&&api.claimed.isEmpty(),"file reads release all pending state");
        } finally {hold.countDown();api.onDestroy();good.delete();big.delete();}
        passed.append("actual content provider, oversize and missing files, duplicate floods and bounded queue\n");
    }
    private void backlogAndExpiry() throws Exception {
        Capture c=new Capture();MinimaAPI api=new MinimaAPI(c,j->{});api.ResponseReceived(c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{}"));
        AtomicInteger calls=new AtomicInteger();AtomicReference<JSONObject> expired=new AtomicReference<>();
        try {
            api.Command("txnpost id:audit",j->{expired.set(j);calls.incrementAndGet();});
            String id=c.id();Intent late=c.reply().putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT,"{\"status\":true}");
            Runnable expiry=api.expiries.get(id);check(expiry!=null&&MinimaAPI.REPLY_RETENTION_MS>180000,"write replies retained beyond outer timeout");
            expiry.run();uncertain(expired.get());api.ResponseReceived(late);
            check(calls.get()==1&&api.expiries.isEmpty()&&api.mResponseHandlers.isEmpty(),"expiry consumes only callback and rejects late replay");
            for(int i=0;i<MinimaAPI.MAX_PENDING;i++)api.Command("status",j->{});
            int sent=c.sent.get();AtomicReference<JSONObject> full=new AtomicReference<>();api.Command("status",full::set);
            uncertain(full.get());check(c.sent.get()==sent&&api.mResponseHandlers.size()==128&&api.expiries.size()==128,"unanswered cap rejects before broadcasting");
            api.onDestroy();check(api.mResponseHandlers.isEmpty()&&api.expiries.isEmpty(),"destroy clears capped backlog");
        }finally{api.onDestroy();}
        Capture broken=new Capture();broken.throwSend=true;AtomicReference<JSONObject> error=new AtomicReference<>();MinimaAPI failed=new MinimaAPI(broken,error::set);
        try{uncertain(error.get());check(failed.mResponseHandlers.isEmpty()&&failed.expiries.isEmpty(),"dispatch exception frees request without node rejection");}finally{failed.onDestroy();}
        passed.append("late reply retention, bounded unanswered requests, expiry and send failure\n");
    }
    private void pairing() throws Exception {
        String app=prefs().getString("myapp_uid",""),node=prefs().getString("minima_uid","");
        Capture same=new Capture();MinimaAPI restored=new MinimaAPI(same,j->{});
        try{check(app.equals(same.last.getStringExtra(MinimaAPIMessages.MINIMA_API_APP_UID))&&node.equals(same.last.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID)),"existing node approval IDs survive recreation");}finally{restored.onDestroy();}
        Capture failed=new Capture();failed.failCommit=true;AtomicReference<JSONObject> error=new AtomicReference<>();MinimaAPI noCommit=new MinimaAPI(failed,error::set);
        try{uncertain(error.get());check(failed.sent.get()==0,"failed pairing durability acknowledgment sends no registration");noCommit.Command("status",error::set);uncertain(error.get());check(failed.sent.get()==0,"failed pairing cannot dispatch commands");}finally{noCommit.onDestroy();}
        Capture retry=new Capture();MinimaAPI retried=new MinimaAPI(retry,j->{});try{check(retry.sent.get()==1&&app.equals(prefs().getString("myapp_uid","")),"retry commits the same existing ID pair");}finally{retried.onDestroy();}
        check(prefs().edit().remove("minima_uid").commit(),"inject incomplete pairing");
        Capture partial=new Capture();MinimaAPI noPair=new MinimaAPI(partial,error::set);
        try{uncertain(error.get());check(partial.sent.get()==0&&app.equals(prefs().getString("myapp_uid",""))&&!prefs().contains("minima_uid"),"partial pairing preserved, never silently rotated or sent");}finally{noPair.onDestroy();}
        fresh();passed.append("existing approvals, simulated commit-ack failure and incomplete pairing\n");
    }
}

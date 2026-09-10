package com.eurobuddha.pandadex;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual NodeTransport with an in-process fake service. Never binds a real node/service. */
public final class BridgeLifecycleAudit {
    private final Instrumentation instrumentation;
    private int checks;
    private final AtomicReference<Throwable> serviceFailure=new AtomicReference<>();
    private BridgeLifecycleAudit(Instrumentation i){instrumentation=i;}
    public static int run(Instrumentation i) throws Exception {return new BridgeLifecycleAudit(i).exercise();}
    private void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    private void main(Runnable r){instrumentation.runOnMainSync(r);}
    private final class Capture extends ContextWrapper {
        final List<String> ids=new ArrayList<>();
        boolean connect=true;
        final Messenger service=new Messenger(new Handler(Looper.getMainLooper(),msg->{
            try {
                String id=msg.getData().getString("id");ids.add(id);
                String command=NodeTransport.read(NodeTransport.file(this,id,".cmd"));
                if(!command.equals("status")&&!command.equals("checkmode"))throw new AssertionError("unexpected fake command");
                NodeTransport.file(this,id,".cmd").delete();
                Files.write(NodeTransport.file(this,id,".json").toPath(),"{\"status\":true,\"enabled\":true}".getBytes(StandardCharsets.UTF_8));
                Message reply=Message.obtain(null,1);Bundle data=new Bundle();data.putString("id",id);reply.setData(data);msg.replyTo.send(reply);
            }catch(Throwable failure){serviceFailure.set(failure);}
            return true;
        }));
        Capture(){super(instrumentation.getTargetContext());}
        @Override public Context getApplicationContext(){return this;}
        @Override public boolean bindService(Intent intent,ServiceConnection conn,int flags){
            // This is the only service path: never call Context.bindService or a node.
            if(connect)new Handler(Looper.getMainLooper()).post(()->conn.onServiceConnected(new ComponentName(getPackageName(),"FakeNodeTransport"),service.getBinder()));return true;
        }
        @Override public void unbindService(ServiceConnection conn){}
        @Override public void sendBroadcast(Intent intent){throw new AssertionError("No broadcasts in private bridge audit");}
    }
    private NodeTransport create(Capture context,CountDownLatch registered){
        AtomicReference<NodeTransport> value=new AtomicReference<>();
        main(()->value.set(new NodeTransport(context,j->{if(j.optBoolean("enabled",false))registered.countDown();})));return value.get();
    }
    private void queueSize(ThreadPoolExecutor reader,int n) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(reader.getQueue().size()!=n&&System.nanoTime()<until){if(serviceFailure.get()!=null)throw new AssertionError(serviceFailure.get());Thread.sleep(10);}
        check(reader.getQueue().size()==n,"expected shared private-reader queue size "+n);
    }
    private Map<?,?> map(NodeTransport owner,String name) {
        try{java.lang.reflect.Field f=NodeTransport.class.getDeclaredField(name);f.setAccessible(true);return (Map<?,?>)f.get(owner);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private void cleared(NodeTransport owner) {
        for(String name:new String[]{"callbacks","reads","deadlines","connectDeadlines"})
            check(map(owner,name).isEmpty(),"destroy clears owned "+name);
    }
    private int exercise() throws Exception {
        java.lang.reflect.Field field=NodeTransport.class.getDeclaredField("reader");field.setAccessible(true);
        ThreadPoolExecutor reader=(ThreadPoolExecutor)field.get(null);
        Capture ca=new Capture(),cb=new Capture();CountDownLatch registeredA=new CountDownLatch(1),registeredB=new CountDownLatch(1);
        NodeTransport a=create(ca,registeredA),b=create(cb,registeredB);
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1),peerDone=new CountDownLatch(1);
        AtomicInteger peerSuccess=new AtomicInteger(),peerFailure=new AtomicInteger();
        try {
            check(registeredA.await(5,TimeUnit.SECONDS)&&registeredB.await(5,TimeUnit.SECONDS),"actual Messenger/file replies register both private-bridge owners");
            reader.execute(()->{entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}});
            check(entered.await(5,TimeUnit.SECONDS),"controlled private-reader stall entered");
            main(()->b.Command("status",j->{if(j.optBoolean("status",false))peerSuccess.incrementAndGet();else peerFailure.incrementAndGet();peerDone.countDown();}));
            queueSize(reader,1);main(a::onDestroy);
            check(!reader.isShutdown(),"destroy does not shut down process-wide private reader");
            for(int n=0;n<20;n++) {
                Capture context=new Capture();NodeTransport next=create(context,new CountDownLatch(1));
                try{queueSize(reader,2);}finally{main(next::onDestroy);}
                queueSize(reader,1);
                main(()->{cleared(next);for(String id:context.ids)check(!NodeTransport.file(context,id,".cmd").exists()&&!NodeTransport.file(context,id,".json").exists(),"destroy removes its private request/reply files");});
            }
            check(reader.getLargestPoolSize()==1&&peerSuccess.get()==0&&peerFailure.get()==0,"reconnects preserve one physical private reader and the live peer");
            release.countDown();check(peerDone.await(5,TimeUnit.SECONDS),"private peer reply resumes after stall");
            check(peerSuccess.get()==1&&peerFailure.get()==0,"private peer succeeds exactly once");
            check(serviceFailure.get()==null,"fake Binder service encountered no errors");
        }finally{release.countDown();main(a::onDestroy);main(b::onDestroy);}
        Capture after=new Capture();CountDownLatch registeredAfter=new CountDownLatch(1);NodeTransport restored=create(after,registeredAfter);
        try{check(registeredAfter.await(5,TimeUnit.SECONDS),"private bridge reconnects after every previous owner is destroyed");}finally{main(restored::onDestroy);}
        Capture waiting=new Capture();waiting.connect=false;
        NodeTransport unbound=create(waiting,new CountDownLatch(1));AtomicReference<org.json.JSONObject> unsent=new AtomicReference<>();
        main(()->{
            unbound.Command("status",unsent::set);
            check(map(unbound,"connectDeadlines").size()==2,"unsent requests have owned connection deadlines");
            List<String> ids=new ArrayList<>();for(Object id:map(unbound,"callbacks").keySet())ids.add((String)id);
            unbound.onDestroy();cleared(unbound);
            check(unsent.get()!=null&&!unsent.get().has("status"),"destroyed unsent request reports transport failure");
            for(String id:ids)check(!NodeTransport.file(waiting,id,".cmd").exists(),"destroy removes unsent command files");
        });
        return checks;
    }
}

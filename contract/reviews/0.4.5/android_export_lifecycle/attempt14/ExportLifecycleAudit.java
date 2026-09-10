package com.eurobuddha.pandadex;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.*;
import androidx.core.app.ActivityOptionsCompat;
import androidx.lifecycle.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Node-free lifecycle tests with controlled work and picker delivery. */
public final class ExportLifecycleAudit extends Instrumentation {
    static volatile Host host;
    static Pending.Store store;
    static FakeWork work;
    private int checks;
    private String phase;
    static final class Memory implements Pending.Store {
        String value="";boolean fail;
        public String read(){return value;}
        public boolean write(String v){if(fail)return false;value=v;return true;}
    }
    static final class FakeWork implements TradeExportSession.Work {
        int builds,saves;TradeExportWriter.Cb build;Consumer<Boolean> save;
        public boolean build(TradeExport.Snapshot s,TradeExportWriter.Cb cb){builds++;build=cb;return true;}
        public void save(Uri u,TradeExportFiles.Prepared p,Consumer<Boolean> cb){saves++;save=cb;}
    }
    public static final class Host extends ComponentActivity {
        TradeExportSession session;int launches,code;ActivityResultLauncher<String> launcher;
        final ActivityResultRegistry registry=new ActivityResultRegistry(){
            public <I,O> void onLaunch(int requestCode,ActivityResultContract<I,O> contract,I input,ActivityOptionsCompat options){code=requestCode;launches++;}
        };
        @Override public void onCreate(Bundle state){
            super.onCreate(state);
            if(state!=null){registry.onRestoreInstanceState(state.getBundle("picker"));code=state.getInt("code");}
            session=new ViewModelProvider(this,new ViewModelProvider.Factory(){
                @Override public <T extends ViewModel>T create(Class<T> type){return type.cast(new TradeExportSession(getApplication(),store,work));}
            }).get(TradeExportSession.class);
            launcher=registry.register("export",this,new ActivityResultContracts.CreateDocument("application/zip"),session::selected);
            session.state.observe(this,p->{if(p==TradeExportSession.Phase.READY){String f=session.claimPicker();if(f!=null)launcher.launch(f);}});
            host=this;
        }
        @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);Bundle b=new Bundle();registry.onSaveInstanceState(b);out.putBundle("picker",b);out.putInt("code",code);}
        void result(Uri uri){registry.dispatchResult(code,uri==null?Activity.RESULT_CANCELED:Activity.RESULT_OK,new Intent().setData(uri));}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase","normal");start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(phase.equals("normal"))normal();else if(phase.equals("crash"))crash();else if(phase.equals("recover"))recover();else throw new AssertionError("unknown phase");
        result.putString("stream","PASS "+checks+" assertions\nExport lifecycle "+phase+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
    void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    void ui(Runnable r){runOnMainSync(r);}
    void launch(){host=null;startActivitySync(new Intent(getTargetContext(),Host.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();}
    void rotate()throws Exception{Host old=host;ui(old::recreate);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(host==old&&System.nanoTime()<end)Thread.sleep(20);waitForIdleSync();check(host!=old,"Activity really recreated");}
    void close(){Host h=host;ui(h::finish);waitForIdleSync();}
    TradeExportFiles.Prepared prepared()throws Exception {
        return TradeExportFiles.prepare(getTargetContext().getCacheDir(),new TradeExport.Snapshot(),(s,k)->{},null);
    }
    void normal()throws Exception {
        Memory memory=new Memory();store=memory;work=new FakeWork();launch();TradeExportSession model=host.session;
        ui(()->check(model.start(new TradeExport.Snapshot()),"start accepted"));check(work.builds==1&&memory.value.equals("BUILDING"),"durable build intent");
        rotate();check(host.session==model&&model.phase()==TradeExportSession.Phase.BUILDING,"build retained across recreation");
        TradeExportFiles.Prepared p=prepared();ui(()->work.build.onDone(p));check(host.launches==1&&memory.value.equals("PICKER"),"one picker and durable intent");
        rotate();check(host.session==model&&host.launches==0&&p.zip.exists(),"picker and file retained without relaunch");
        ui(()->host.result(Uri.parse("content://audit/output")));check(work.saves==1&&memory.value.equals("SAVING"),"result reaches retained owner once");
        ui(()->host.result(Uri.parse("content://audit/duplicate")));check(work.saves==1,"duplicate picker result does not save twice");
        rotate();check(host.session==model&&model.phase()==TradeExportSession.Phase.SAVING,"save retained across recreation");
        ui(()->work.save.accept(true));check(model.notice().contains("written and closed")&&!p.zip.exists(),"completion visible and private file deleted");
        rotate();check(host.session==model&&model.notice().contains("cloud"),"completed notice survives recreation");
        ui(()->check(model.acknowledge(),"completion acknowledged"));check(memory.value.isEmpty(),"journal cleared only after acknowledgement");
        // A second Activity owner cannot start competing work or erase the first journal.
        TradeExportSession[] second=new TradeExportSession[1];ui(()->{
            check(model.start(new TradeExport.Snapshot()),"next build accepted");
            second[0]=new TradeExportSession(host.getApplication(),memory,new FakeWork());
            check(!second[0].start(new TradeExport.Snapshot()),"second owner blocked");second[0].onCleared();
        });
        TradeExportFiles.Prepared cancellation=prepared();ui(()->work.build.onDone(cancellation));ui(()->host.result(null));
        check(work.saves==1&&!cancellation.zip.exists()&&model.notice().contains("cancelled"),"cancellation never writes and releases file");ui(model::acknowledge);
        memory.fail=true;int before=work.builds;ui(()->model.start(new TradeExport.Snapshot()));check(work.builds==before&&model.phase()==TradeExportSession.Phase.NOTICE,"failed build journal blocks worker");
        ui(()->check(!model.acknowledge(),"failed acknowledgement retained"));memory.fail=false;ui(model::acknowledge);
        ui(()->model.start(new TradeExport.Snapshot()));TradeExportFiles.Prepared noPicker=prepared();memory.fail=true;ui(()->work.build.onDone(noPicker));
        check(!noPicker.zip.exists()&&model.phase()==TradeExportSession.Phase.NOTICE,"failed picker journal closes file without launch");memory.fail=false;ui(model::acknowledge);
        ui(()->model.start(new TradeExport.Snapshot()));TradeExportFiles.Prepared noWrite=prepared();ui(()->work.build.onDone(noWrite));memory.fail=true;
        ui(()->host.result(Uri.parse("content://audit/failure")));check(work.saves==1&&!noWrite.zip.exists(),"failed save journal blocks provider");memory.fail=false;ui(model::acknowledge);
        ui(()->model.start(new TradeExport.Snapshot()));TradeExportFiles.Prepared forged=prepared();ui(()->work.build.onDone(forged));ui(()->host.result(Uri.parse("file:///private/receipt")));
        check(work.saves==1&&!forged.zip.exists(),"forged URI rejected before worker");ui(model::acknowledge);
        ui(()->model.start(new TradeExport.Snapshot()));TradeExportFiles.Prepared partial=prepared();ui(()->work.build.onDone(partial));ui(()->host.result(Uri.parse("content://audit/partial")));ui(()->work.save.accept(false));
        check(model.notice().contains("partial")&&!partial.zip.exists(),"failure reports partial destination honestly");ui(model::acknowledge);
        ui(()->model.start(new TradeExport.Snapshot()));close();TradeExportFiles.Prepared abandoned=prepared();ui(()->work.build.onDone(abandoned));check(!abandoned.zip.exists(),"late build after finish cleaned up");
        // Fresh owner sees durable notice even without Android saved state.
        launch();check(host.session.phase()==TradeExportSession.Phase.NOTICE,"abandoned result recovered by new owner");ui(host.session::acknowledge);close();
    }
    Pending.Store disk(){android.content.SharedPreferences p=getTargetContext().getSharedPreferences("audit_export_journal",0);return new Pending.Store(){public String read(){return p.getString("state","");}public boolean write(String s){return p.edit().putString("state",s).commit();}};}
    void crash()throws Exception {
        store=disk();check(store.write(""),"fixture journal reset");work=new FakeWork();launch();
        ui(()->host.session.start(new TradeExport.Snapshot()));TradeExportFiles.Prepared p=prepared();ui(()->work.build.onDone(p));ui(()->host.result(Uri.parse("content://audit/unfinished")));
        check(store.read().equals("SAVING")&&work.saves==1,"durable intent before simulated stuck save");
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("process did not exit");
    }
    void recover(){
        store=disk();work=new FakeWork();launch();TradeExportSession model=host.session;
        check(model.phase()==TradeExportSession.Phase.NOTICE&&model.notice().contains("interrupted"),"actual process death recovered as unknown");
        check(work.saves==0&&work.builds==0,"no automatic repeat");ui(()->model.selected(Uri.parse("content://audit/stale")));
        check(work.saves==0,"late restored picker result cannot write lost export");
        ui(()->check(model.acknowledge(),"recovery acknowledged"));check(store.read().isEmpty(),"recovery journal cleared");close();
    }
}

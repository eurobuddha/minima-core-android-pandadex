package com.eurobuddha.pandadex;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.util.function.Consumer;

/** Main-thread export ownership. Workers hold application context, never an Activity.
 * The journal records intent before external writes; it never silently resumes an uncertain save. */
public final class TradeExportSession extends AndroidViewModel {
    enum Phase { IDLE, BUILDING, READY, PICKER, SAVING, NOTICE }
    interface Work {
        boolean build(TradeExport.Snapshot snapshot, TradeExportWriter.Cb callback);
        void save(Uri uri, TradeExportFiles.Prepared prepared, Consumer<Boolean> callback);
    }
    // The provider may block forever. Keep ownership until its worker actually returns.
    private static TradeExportSession owner;
    final MutableLiveData<Phase> state = new MutableLiveData<>(Phase.IDLE);
    private final Pending.Store journal;
    private final Work work;
    private Phase phase=Phase.IDLE;
    private TradeExportFiles.Prepared prepared;
    private boolean cleared;
    private String notice="";

    public TradeExportSession(Application app) {
        this(app, store(app), new Work() {
            public boolean build(TradeExport.Snapshot s,TradeExportWriter.Cb cb){return TradeExportWriter.run(app,s,cb);}
            public void save(Uri u,TradeExportFiles.Prepared p,Consumer<Boolean> cb){TradeExportWriter.save(app,u,p,cb);}
        });
    }
    TradeExportSession(Application app,Pending.Store journal,Work work) {
        super(app);this.journal=journal;this.work=work;
        recoverJournal();
    }
    private boolean recoverJournal() {
        if(owner!=null)return false;
        String saved;
        try {saved=journal.read();}catch(RuntimeException invalid){saved="UNREADABLE";}
        if(saved==null||saved.isEmpty())return false;
        owner=this;
        notice=saved.startsWith("NOTICE\n")?saved.substring(7):
            "The previous export was interrupted. Its destination may be empty, partial or complete. Check the file before exporting again; no save will be repeated automatically.";
        publish(Phase.NOTICE);return true;
    }
    private static Pending.Store store(Context context) {
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("pandadex_export_session",Context.MODE_PRIVATE);
        return new Pending.Store(){
            public String read(){return prefs.getString("state","");}
            public boolean write(String value){return prefs.edit().putString("state",value).commit();}
        };
    }
    Phase phase(){return phase;}
    String notice(){return notice;}
    private void publish(Phase next){phase=next;if(!cleared)state.setValue(next);}
    private boolean persist(String value){try{return journal.write(value);}catch(RuntimeException failure){return false;}}
    private void release(){if(owner==this)owner=null;}
    private void dispose(){if(prepared!=null){prepared.close();prepared=null;}}
    private void finish(String message) {
        dispose();
        notice=message;
        if(!persist("NOTICE\n"+message))notice+=" Export status could not be saved locally; check the destination after restarting.";
        if(cleared){release();return;}
        publish(Phase.NOTICE);
    }
    boolean start(TradeExport.Snapshot snapshot) {
        if(cleared||phase!=Phase.IDLE||(owner!=null&&owner!=this))return false;
        // Another screen may have finished since this idle model was constructed.
        // Re-read its durable outcome before replacing the journal with a new export.
        if(recoverJournal())return true;
        owner=this;
        if(!persist("BUILDING")){finish("Could not record the export session. No export was started; free storage and try again.");return true;}
        publish(Phase.BUILDING);
        try {
            boolean accepted=work.build(snapshot,new TradeExportWriter.Cb(){
                public void onDone(TradeExportFiles.Prepared result){
                    if(cleared){result.close();finish("Export preparation was cancelled when its screen closed.");return;}
                    prepared=result;publish(Phase.READY);
                }
                public void onError(String message){finish("Could not prepare the export. Source records are unchanged. "+message);}
            });
            if(!accepted)finish("Another export worker is still running. Try again when it finishes.");
        }catch(RuntimeException failure){finish("Could not start the export. Source records are unchanged.");}
        return true;
    }
    /** Claim before launching. A recreated observer must never launch a second picker. */
    String claimPicker() {
        if(cleared||phase!=Phase.READY||prepared==null)return null;
        if(!persist("PICKER")){finish("Could not record the save request. No destination was written; try again after freeing storage.");return null;}
        String filename=prepared.filename;publish(Phase.PICKER);return filename;
    }
    void pickerFailed(){if(phase==Phase.PICKER)finish("Could not open the save picker. Please export again.");}
    void selected(Uri uri) {
        // Includes duplicate callbacks and stale results restored after process death.
        if(cleared||phase!=Phase.PICKER||prepared==null)return;
        if(uri==null){finish("Export cancelled. No file was written by PandaDEX.");return;}
        if(!"content".equals(uri.getScheme())){finish("The destination is not a document provider. No file was written.");return;}
        if(!persist("SAVING")){finish("Could not record the save request. The selected document was not written; please export again.");return;}
        publish(Phase.SAVING);
        String description=TradeExportWriter.describe(prepared.report);
        try {work.save(uri,prepared,ok -> finish(ok?
                "Export written and closed · "+description+". For cloud destinations, check that the provider has finished uploading.":
                "The export could not be completed. The destination may contain a partial file; check it and export to another destination."));}
        catch(RuntimeException failure){finish("Could not start saving. Check the destination and export again.");}
    }
    boolean acknowledge() {
        if(cleared||phase!=Phase.NOTICE)return false;
        if(!persist("")){notice="Export status could not be cleared. Free storage and try again; source trade records are unchanged.";return false;}
        notice="";release();publish(Phase.IDLE);return true;
    }
    @Override protected void onCleared() {
        cleared=true;dispose();
        if(phase!=Phase.BUILDING&&phase!=Phase.SAVING)release();
    }
}

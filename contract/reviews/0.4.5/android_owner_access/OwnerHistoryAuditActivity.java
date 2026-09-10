package com.eurobuddha.pandadex;
import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import java.util.Collections;

/** Only the production local history view; no MainActivity, service, node or signing. */
public final class OwnerHistoryAuditActivity extends Activity {
    DexDb db;OwnerHistoryView history;ScrollView scroll;int exports;String mode="normal";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);Design.load(this);db=new DexDb(this);
        history=new OwnerHistoryView(this,(limit,after)->{
            if("error".equals(mode))throw new IllegalStateException("fixture storage error");
            return "empty".equals(mode)?Collections.emptyList():db.ownerReceipts(limit,after);
        },()->exports++);
        scroll=new ScrollView(this);scroll.setBackgroundColor(Design.BG());int p=Design.dp(this,12);history.setPadding(p,p,p,p);
        scroll.addView(history);setContentView(scroll);history.render();
    }
    void show(String mode,Design.Mode theme){this.mode=mode;Design.set(this,theme);scroll.setBackgroundColor(Design.BG());history.render();scroll.scrollTo(0,0);}
    @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}

package com.eurobuddha.pandadex;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.*;

/** Existing Operations cards, with bounded local pages and the established export action. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic view requires its local data source and export action.
final class OwnerHistoryView extends LinearLayout {
    interface Source {List<OwnerReceipt.Entry> page(int limit,OwnerReceipt.Cursor after);}
    private static final int PAGE_SIZE=50;
    private final Source source;private final Runnable export;
    private final List<OwnerReceipt.Cursor> previous=new ArrayList<>();
    private OwnerReceipt.Cursor after;
    private final SimpleDateFormat fmt=new SimpleDateFormat("dd MMM HH:mm",Locale.US);
    OwnerHistoryView(Context context,Source source,Runnable export){super(context);this.source=source;this.export=export;setOrientation(VERTICAL);}
    private TextView line(String text,int color,float size){
        // The existing primary ink stays readable on light surfaces; tertiary grey and
        // orange/green are too faint at these text sizes. Status wording carries the meaning.
        if(!Design.isDark())color=Design.TEXT();
        else if(color==Design.DIM2())color=Design.DIM();
        TextView v=new TextView(getContext());v.setText(text);v.setTextColor(color);v.setTypeface(Design.mono());v.setTextSize(size);return v;
    }
    private TextView button(String text,boolean enabled,Runnable click){
        TextView v=line(text,enabled?Design.ACCENT():Design.DIM2(),11f);v.setGravity(Gravity.CENTER);
        int p=Design.dp(getContext(),10);v.setPadding(p,p,p,p);v.setBackground(Design.stroked(getContext(),Design.SURFACE2(),10));
        v.setMinHeight(Design.dp(getContext(),48));
        v.setEnabled(enabled);v.setAlpha(enabled?1f:.45f);v.setOnClickListener(view->click.run());return v;
    }
    private void move(Runnable update){update.run();render();requestRectangleOnScreen(new Rect(0,0,getWidth(),1),true);}
    void render(){
        removeAllViews();
        addView(line("Saved order operations. Confirmations are from the last node check; these records do not add trade volume or P&L.",Design.DIM2(),10f));
        addView(button("Export reconciliation ZIP",true,export));
        final List<OwnerReceipt.Entry> page;
        try{page=source.page(PAGE_SIZE+1,after);}catch(RuntimeException failure){
            addView(line("Saved operations could not be read. Preserve app data for recovery.",Design.ACCENT(),11f));return;
        }
        int shown=Math.min(PAGE_SIZE,page.size());boolean hasOlder=page.size()>PAGE_SIZE;
        OwnerReceipt.Cursor next=shown==0?null:page.get(shown-1).cursor();
        LinearLayout nav=new LinearLayout(getContext());
        nav.addView(button("Newest",after!=null,()->move(()->{after=null;previous.clear();})),new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        nav.addView(button("Newer",!previous.isEmpty(),()->move(()->after=previous.remove(previous.size()-1))),new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        nav.addView(button("Older",hasOlder,()->move(()->{previous.add(after);after=next;})),new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));addView(nav);
        addView(line("Page "+(previous.size()+1)+" · "+shown+" operations",Design.DIM2(),10f));
        if(shown==0){addView(line(after==null?"No completed operations saved yet. Older operations removed by previous builds are not available here.":"No older operations on this page. Use Newest to refresh.",Design.DIM2(),11f));return;}
        for(int i=0;i<shown;i++) {
            OwnerReceipt.Entry r=page.get(i);LinearLayout card=new LinearLayout(getContext());card.setTag(r.id);card.setOrientation(VERTICAL);card.setBackground(Design.card(getContext(),10));
            int p=Design.dp(getContext(),10);card.setPadding(p,p,p,p);
            card.addView(line(OwnerReceipt.title(r.outcome),Design.TEXT(),12f));
            card.addView(line(r.status(),r.current()?Design.IN():Design.ACCENT(),10f));
            card.addView(line((r.blockTimeMs>0?"Block time ":"First observed ")+fmt.format(new Date(r.blockTimeMs>0?r.blockTimeMs:r.recordedAt)),Design.DIM2(),9.5f));
            card.addView(line("Verified inclusion block "+r.block+(r.checkedAt>0?" · checked "+fmt.format(new Date(r.checkedAt)):""),Design.DIM2(),9.5f));
            TextView id=line("TxPoW "+r.txpowid,Design.DIM(),9f);id.setTextIsSelectable(true);card.addView(id);
            LayoutParams lp=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);lp.bottomMargin=Design.dp(getContext(),8);addView(card,lp);
        }
    }
}

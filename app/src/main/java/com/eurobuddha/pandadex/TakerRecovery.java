package com.eurobuddha.pandadex;

import java.util.*;
import org.json.*;

/** Recheck retained aggregate expectations through the existing bounded wallet-history pager. */
final class TakerRecovery {
    static final int LIMIT=4;
    static final class Entry {
        final String coinid,txpowid,blockid,json;
        final long block;
        Entry(String coinid,String txpowid,long block,String blockid,String json) {
            this.coinid=coinid;this.txpowid=txpowid;this.block=block;this.blockid=blockid;this.json=json;
        }
        TakerReceipt.Intent intent() {
            try {
                JSONObject row=TakerReceipt.stored(json),proof=row.getJSONObject("proof");
                TakerReceipt.Intent intent=new TakerReceipt.Intent(row.getJSONObject("expected"));
                if(!coinid.equalsIgnoreCase(intent.sources.get(0))
                        || !txpowid.equalsIgnoreCase(proof.getString("txpowid"))
                        || block!=MakerConfig.storedBlock(proof.get("block")) || !blockid.equalsIgnoreCase(proof.getString("blockid")))
                    throw new IllegalArgumentException("Stored taker evidence does not match its index");
                return intent;
            } catch(JSONException invalid) {throw new IllegalArgumentException("Stored taker evidence could not be read",invalid);}
        }
    }
    interface Store {
        /** Select and rotate a bounded batch atomically, without changing its original evidence. */
        List<Entry> takerBatch(int limit);
        default boolean claimTakerTurn() {return true;}
        boolean repairTaker(Entry entry,TakerReceipt receipt);
    }
    interface Finder {void find(Collection<String> sources,DexHistory.Cb cb);}
    interface Done {void done(boolean attempted,boolean changed,String error);}
    private static boolean running;
    private boolean finished,delivered,attempted,changed;
    private String error="";
    private final Store store;
    private final Finder finder;
    TakerRecovery(Store store,Finder finder) {this.store=store;this.finder=finder;}
    void run(Done done) {
        synchronized(TakerRecovery.class) {
            if(running) {done.done(false,false,"");return;}
            running=true;
        }
        try {
            if(!store.claimTakerTurn()) {finish(done);return;}
            List<Entry> offered=store.takerBatch(LIMIT);
            List<Entry> batch=new ArrayList<>(offered.subList(0,Math.min(LIMIT,offered.size())));
            attempted=!batch.isEmpty();
            Map<Entry,TakerReceipt.Intent> intents=new LinkedHashMap<>();Set<String> sources=new LinkedHashSet<>();
            for(Entry entry:batch) {
                try {TakerReceipt.Intent intent=entry.intent();intents.put(entry,intent);sources.addAll(intent.sources);}
                catch(RuntimeException invalid) {error="Some saved taker evidence could not be read. Original receipts are retained.";}
            }
            if(sources.isEmpty()) {finish(done);return;}
            finder.find(sources,found->{
                if(delivered || finished)return;delivered=true;
                try { for(Map.Entry<Entry,TakerReceipt.Intent> item:intents.entrySet()) {
                    final TakerReceipt receipt;
                    try {receipt=TakerReceipt.capture(new JSONObject(item.getValue().json),found);}
                    catch(JSONException | IllegalArgumentException incomplete) {continue;}
                    try {changed=store.repairTaker(item.getKey(),receipt) || changed;}
                    catch(RuntimeException failure) {error="Taker receipt reconciliation could not complete. Original evidence is retained.";}
                } } catch(RuntimeException failure) {
                    error="Taker receipt recovery could not complete. Original evidence is retained.";
                } finally {finish(done);}
            });
        } catch(RuntimeException failure) {error="Taker receipt recovery could not complete. Original evidence is retained.";finish(done);}
    }
    private void finish(Done done) {
        if(finished)return;finished=true;
        synchronized(TakerRecovery.class) {running=false;}
        done.done(attempted,changed,error);
    }
}

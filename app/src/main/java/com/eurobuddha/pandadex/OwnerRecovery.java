package com.eurobuddha.pandadex;

import java.util.*;
import org.json.*;

/** Recheck retained owner expectations through the existing bounded wallet-history pager. */
final class OwnerRecovery {
    static final int LIMIT=4;
    static final class Entry {
        final String id,txpowid,blockid,json;final long block;
        Entry(String id,String txpowid,long block,String blockid,String json){this.id=id;this.txpowid=txpowid;this.block=block;this.blockid=blockid;this.json=json;}
        Pending.Row intent() {
            try {
                JSONObject saved=TakerReceipt.stored(json),proof=saved.getJSONObject("proof");
                Pending.Row row=Pending.Row.from(saved.getJSONObject("expected"));
                if(block<=0||!FundingCoins.hex(txpowid)||!FundingCoins.hex(blockid)||!id.equals(row.receiptId)||!txpowid.equalsIgnoreCase(proof.getString("txpowid"))
                        ||block!=MakerConfig.storedBlock(proof.get("block"))||!blockid.equalsIgnoreCase(proof.getString("blockid")))
                    throw new IllegalArgumentException("Stored owner evidence does not match its index");
                if(sources(row).isEmpty())throw new IllegalArgumentException("Owner sources unavailable");return row;
            }catch(JSONException invalid){throw new IllegalArgumentException("Stored owner evidence could not be read",invalid);}
        }
    }
    static List<String> sources(Pending.Row row){return Pending.PLACE.equals(row.kind)?CreationEvidence.inputs(row):FundingCoins.hex(row.coinid)?Collections.singletonList(row.coinid):Collections.emptyList();}
    interface Store {
        /** Select and rotate a bounded batch atomically, without changing its original evidence. */
        List<Entry> ownerBatch(int limit);
        default boolean claimOwnerTurn() {return true;}
        boolean repairOwner(Entry entry,OwnerReceipt receipt);
    }
    interface Finder {void find(Collection<String> sources,DexHistory.Cb cb);}
    interface Done {void done(boolean attempted,boolean changed,String error);}
    private static boolean running;
    private boolean finished,delivered,attempted,changed;
    private String error="";
    private final Store store;
    private final Finder finder;
    OwnerRecovery(Store store,Finder finder) {this.store=store;this.finder=finder;}
    void run(Done done) {
        synchronized(OwnerRecovery.class) {
            if(running) {done.done(false,false,"");return;}
            running=true;
        }
        try {
            if(!store.claimOwnerTurn()) {finish(done);return;}
            List<Entry> offered=store.ownerBatch(LIMIT);
            List<Entry> batch=new ArrayList<>(offered.subList(0,Math.min(LIMIT,offered.size())));
            attempted=!batch.isEmpty();
            Map<Entry,Pending.Row> intents=new LinkedHashMap<>();Set<String> sources=new LinkedHashSet<>();
            for(Entry entry:batch) {
                try {Pending.Row intent=entry.intent();intents.put(entry,intent);sources.addAll(sources(intent));}
                catch(RuntimeException invalid) {error="Some saved owner evidence could not be read. Original receipts are retained.";}
            }
            if(sources.isEmpty()) {finish(done);return;}
            finder.find(sources,found->{
                if(delivered || finished)return;delivered=true;
                try { for(Map.Entry<Entry,Pending.Row> item:intents.entrySet()) {
                    final OwnerReceipt receipt;
                    try {receipt=OwnerReceipt.capture(item.getValue(),found);}
                    catch(IllegalArgumentException incomplete) {continue;}
                    try {changed=store.repairOwner(item.getKey(),receipt) || changed;}
                    catch(RuntimeException failure) {error="Owner receipt reconciliation could not complete. Original evidence is retained.";}
                } } catch(RuntimeException failure) {
                    error="Owner receipt recovery could not complete. Original evidence is retained.";
                } finally {finish(done);}
            });
        } catch(RuntimeException failure) {error="Owner receipt recovery could not complete. Original evidence is retained.";finish(done);}
    }
    private void finish(Done done) {
        if(finished)return;finished=true;
        synchronized(OwnerRecovery.class) {running=false;}
        done.done(attempted,changed,error);
    }
}

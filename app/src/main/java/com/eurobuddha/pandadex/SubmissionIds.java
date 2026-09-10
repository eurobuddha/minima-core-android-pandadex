package com.eurobuddha.pandadex;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Submission lookup cache plus receipt-level immutable identity, from PandaPools ActivityLog. */
final class SubmissionIds {
    private static final String PREFS = "pandadex_submissions";
    private static Pending.Store store(Context ctx,String name,String key,String empty) {
        android.content.SharedPreferences prefs=ctx.getSharedPreferences(name,Context.MODE_PRIVATE);
        return new Pending.Store() {
            public String read() { return prefs.getString(key,empty); }
            public boolean write(String next) { return prefs.edit().putString(key,next).commit(); }
        };
    }
    private static Pending.Store pending(Context ctx) { return store(ctx,"pandadex_taker","pending",""); }
    private static Pending.Store cache(Context ctx) { return store(ctx,PREFS,"ids","[]"); }

    static synchronized boolean remember(Context ctx,JSONObject reply) { return remember(ctx,reply,""); }
    static synchronized boolean remember(Context ctx,JSONObject reply,String command) {
        if(ctx==null) return false;
        try { return remember(pending(ctx),cache(ctx),reply,command); }
        catch(RuntimeException unavailable) { return false; }
    }
    static synchronized boolean remember(Pending.Store pending,Pending.Store cache,JSONObject reply,String command) {
        if(reply==null || !Boolean.TRUE.equals(reply.opt("status")) || reply.optBoolean("pending",false)) return false;
        JSONObject tx=reply.optJSONObject("response");
        if(tx!=null && tx.optJSONObject("txpow")!=null) tx=tx.optJSONObject("txpow");
        String id=ChainEvidence.transactionId(tx),posted=Util.extractTxpowid(reply,"");
        if(!FundingCoins.hex(id) || !FundingCoins.hex(posted)) return false;
        String handle="";
        if(command!=null && command.startsWith("txnpost ")) for(String part:command.split("\\s+"))
            if(part.startsWith("id:")) handle=part.substring(3);
        boolean pinned=true;
        try {
            String saved=pending.read();
            if(!saved.isEmpty()) {
                JSONObject row=new JSONObject(saved);
                if(matches(row,posted) || (!handle.isEmpty() && matches(row,handle))) {
                    String previous=row.optString("transactionid","");
                    if(!previous.isEmpty() && !id.equalsIgnoreCase(previous)) return false;
                    // Save before the rolling cache, including late replies with no Activity callback.
                    row.put("transactionid",id); pinned=pending.write(row.toString());
                }
            }
        } catch(Exception invalid) { pinned=false; }
        try {
            JSONArray old=new JSONArray(cache.read());
            JSONArray next=new JSONArray().put(new JSONObject().put("p",posted).put("t",id));
            if(!handle.isEmpty()) next.put(new JSONObject().put("p",handle).put("t",id));
            for(int i=0;i<old.length() && next.length()<512;i++) {
                JSONObject row=old.optJSONObject(i);
                if(row!=null && !posted.equalsIgnoreCase(row.optString("p"))
                        && (handle.isEmpty() || !handle.equalsIgnoreCase(row.optString("p")))) next.put(row);
            }
            return cache.write(next.toString()) && pinned;
        } catch(Exception invalid) { return false; }
    }

    private static boolean matches(JSONObject row,String reference) {
        return reference!=null && !reference.isEmpty()
                && (reference.equalsIgnoreCase(row.optString("txpowid",""))
                    || reference.equalsIgnoreCase(row.optString("intent","")));
    }
    private static String cached(Pending.Store cache,String reference) throws Exception {
        if(reference==null || reference.isEmpty()) return "";
        JSONArray rows=new JSONArray(cache.read());
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(row!=null && reference.equalsIgnoreCase(row.optString("p"))) {
                String id=row.optString("t",""); return FundingCoins.hex(id)?id:"";
            }
        }
        return "";
    }
    static synchronized String transactionFor(Context ctx,String posted) {
        if(ctx==null) return "";
        try { return transactionFor(pending(ctx),cache(ctx),posted); }
        catch(RuntimeException unavailable) { return ""; }
    }
    static synchronized String transactionFor(Pending.Store pending,Pending.Store cache,String posted) {
        try {
            String saved=pending.read();
            if(!saved.isEmpty()) {
                JSONObject row=new JSONObject(saved);
                if(matches(row,posted)) {
                    String id=row.optString("transactionid","");
                    if(!id.isEmpty()) return FundingCoins.hex(id)?id:"";
                    id=cached(cache,posted);
                    if(id.isEmpty()) id=cached(cache,row.optString("intent",""));
                    if(id.isEmpty()) return "";
                    row.put("transactionid",id);
                    return pending.write(row.toString())?id:"";
                }
            }
            return cached(cache,posted);
        } catch(Exception invalid) { return ""; }
    }

    /** Preserve the pinned ID when an Activity updates the receipt's submitted reference. */
    static synchronized boolean savePending(Context ctx,JSONObject row) {
        if(ctx==null) return false;
        try { return savePending(pending(ctx),cache(ctx),row); }
        catch(RuntimeException unavailable) { return false; }
    }
    static synchronized boolean savePending(Pending.Store pending,Pending.Store cache,JSONObject row) {
        try {
            String intent=row.optString("intent","");
            if(intent.isEmpty()) return false;
            String saved=pending.read(),id="";
            if(!saved.isEmpty()) {
                JSONObject previous=new JSONObject(saved);
                if(!intent.equals(previous.optString("intent",""))) return false;
                id=previous.optString("transactionid","");
                if(!id.isEmpty() && !FundingCoins.hex(id)) return false;
            }
            if(id.isEmpty()) {
                id=cached(cache,row.optString("txpowid",""));
                if(id.isEmpty()) id=cached(cache,intent);
            }
            // Work on a copy: failed persistence does not mutate the caller's intended receipt.
            JSONObject next=new JSONObject(row.toString()).put("transactionid",id);
            return pending.write(next.toString());
        } catch(Exception invalid) { return false; }
    }
}

package com.eurobuddha.pandadex;

import org.json.*;
import java.util.*;

/** Immutable owner-operation intent and linked proof, following TakerReceipt's capture boundary. */
final class OwnerReceipt {
    interface Store { void completed(OwnerReceipt receipt); }
    final String id,kind,outcome,txpowid,blockid,json,intent;
    final long block,blockTimeMs,recordedAt;
    final int depth;
    final DexHistory.Spend spend;
    private OwnerReceipt(Pending.Row row,String outcome,DexHistory.Spend spend,String json,long recordedAt) throws JSONException {
        this.id=row.receiptId;this.kind=row.kind;this.outcome=outcome;this.txpowid=spend.txpowid;
        this.blockid=spend.inclusionBlockId;this.block=spend.inclusionBlock;this.blockTimeMs=spend.inclusionTimeMs;
        this.depth=spend.confirmations;this.spend=DexHistory.copyProof(spend);this.json=json;this.recordedAt=recordedAt;this.intent=intentKey(row);
    }
    static OwnerReceipt capture(Pending.Row row,Map<String,DexHistory.Spend> found) {
        try {
            Pending.Row expected=Pending.Row.from(row.json());
            List<String> sources;String outcome;
            if(Pending.PLACE.equals(expected.kind)) {
                if(CreationEvidence.match(expected,found)==null)throw new IllegalArgumentException("Creation proof does not match");
                sources=CreationEvidence.inputs(expected);outcome="CREATED";
            }else {
                sources=Collections.singletonList(expected.coinid);
                DexHistory.Spend proof=found.get(expected.coinid);Order5 original=proof==null?null:Order5.from(proof.input);
                if(original==null||!expected.orderId.equalsIgnoreCase(original.orderId)
                        ||(Pending.CANCEL.equals(expected.kind)?!Pending.cancelSourceMatches(expected,original):!Pending.editSourceMatches(expected,original)))
                    throw new IllegalArgumentException("Owner source proof does not match");
                if(Pending.EDIT.equals(expected.kind)&&Pending.editMatches(expected,proof,original))outcome=expected.isRenewal()?"RENEWED":"EDITED";
                else {
                    FillVerifier.Verdict verdict=DexHistory.verdictFor(proof,original);
                    if(verdict==FillVerifier.Verdict.CANCELLED)outcome=Pending.CANCEL.equals(expected.kind)?"CANCELLED":"CANCELLED_INSTEAD";
                    else if(verdict==FillVerifier.Verdict.FILLED)outcome="FILLED_INSTEAD";
                    else throw new IllegalArgumentException("Owner outcome is not verified");
                }
            }
            DexHistory.Spend first=found.get(sources.get(0));
            if(first==null||first.confirmations<0||first.inputIndex<0||first.inputCount<=first.inputIndex||!FundingCoins.hex(first.txpowid)||first.inclusionBlock<=0||!FundingCoins.hex(first.inclusionBlockId))
                throw new IllegalArgumentException("Owner inclusion coordinates are incomplete");
            JSONArray inputs=new JSONArray();
            for(String source:sources) {
                DexHistory.Spend leg=found.get(source);
                if(leg==null||leg.input==null||!source.equalsIgnoreCase(leg.input.optString("coinid",""))
                        ||!first.txpowid.equalsIgnoreCase(leg.txpowid)||first.inclusionBlock!=leg.inclusionBlock
                        ||!first.inclusionBlockId.equalsIgnoreCase(leg.inclusionBlockId))throw new IllegalArgumentException("Inconsistent owner input proof");
                inputs.put(new JSONObject().put("index",leg.inputIndex).put("coin",leg.input));
            }
            long recordedAt=System.currentTimeMillis();
            JSONObject proof=new JSONObject().put("txpowid",first.txpowid).put("transactionid",first.transactionId)
                    .put("inputs",inputs).put("input_count",first.inputCount).put("outputs",first.outputs).put("state",first.transactionState)
                    .put("block",first.inclusionBlock).put("blockid",first.inclusionBlockId).put("timems",first.inclusionTimeMs)
                    .put("depth",first.confirmations).put("proof_epoch",ChainEvidence.PROOF_EPOCH).put("proof_order",first.proofOrder).put("proof_time",first.proofTimeMs);
            String json=new JSONObject().put("version",1).put("expected",expected.json()).put("outcome",outcome)
                    .put("recorded_at",recordedAt).put("proof",proof).toString();
            if(json.length()>TakerReceipt.MAX_BYTES||json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>TakerReceipt.MAX_BYTES)
                throw new IllegalArgumentException("Owner evidence exceeds the local receipt storage limit");
            return new OwnerReceipt(expected,outcome,first,json,recordedAt);
        }catch(Exception invalid){throw new IllegalArgumentException("Owner receipt could not be captured",invalid);}
    }
    private static String intentKey(Pending.Row row) throws JSONException {
        try {
            JSONObject data=Pending.Row.from(row.json()).json();
            // These can advance between archive commit and pending cleanup; intent cannot.
            data.remove("phase");data.remove("postedId");data.remove("delayedNotified");return data.toString();
        }catch(JSONException invalid){throw invalid;}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
    }
    boolean matchesIntent(Pending.Row row) {
        try{return intent.equals(intentKey(row));}catch(Exception invalid){return false;}
    }
    boolean sameCompletion(String previous) {
        try {
            JSONObject old=MakerConfig.storedObject(previous),proof=old.getJSONObject("proof");
            return MakerConfig.storedBlock(old.get("version"))==1&&outcome.equals(old.getString("outcome"))
                    &&matchesIntent(Pending.Row.from(old.getJSONObject("expected")))
                    &&txpowid.equalsIgnoreCase(proof.getString("txpowid"))&&block==MakerConfig.storedBlock(proof.get("block"))&&blockid.equalsIgnoreCase(proof.getString("blockid"));
        }catch(Exception malformed){return false;}
    }
    /** Validate a revision against the original economic intent; the DB supplies ordered-chain guards. */
    String revisionOf(String previous) {
        try {
            JSONObject old=TakerReceipt.stored(previous),proof=old.getJSONObject("proof");
            if(!matchesIntent(Pending.Row.from(old.getJSONObject("expected"))))throw new ChainReview.Conflict();
            String oldTx=MakerConfig.jsonString(proof,"txpowid","");
            if(!FundingCoins.hex(oldTx)||MakerConfig.storedBlock(proof.get("block"))<=0
                    ||!FundingCoins.hex(MakerConfig.jsonString(proof,"blockid","")))throw new ChainReview.Conflict();
            // The same immutable transaction cannot acquire different application effects.
            if(txpowid.equalsIgnoreCase(oldTx)&&!outcome.equals(MakerConfig.jsonString(old,"outcome","")))throw new ChainReview.Conflict();
            if(!ReceiptRepair.usable(new FillSettler.Entry(spend.input.optString("coinid",""),"",true),spend)
                    ||spend.proofOrder<=0||spend.proofTimeMs<=0)throw new ChainReview.Conflict();
            JSONObject revised=MakerConfig.storedObject(json);
            revised.put("recorded_at",MakerConfig.storedBlock(old.get("recorded_at")));
            return revised.toString();
        }catch(Exception invalid){throw new ChainReview.Conflict();}
    }
    static String title(String outcome) {
        switch(outcome){
            case "CREATED":return "Order created";case "CANCELLED":return "Order cancelled";
            case "EDITED":return "Price updated";case "RENEWED":return "Order renewed";
            case "CANCELLED_INSTEAD":return "Order refunded before update";
            case "FILLED_INSTEAD":return "Order spent with payment before request";
            default:return "Saved order operation";
        }
    }
    static final class Cursor {
        final long recordedAt;final String id;
        Cursor(long recordedAt,String id){
            if(recordedAt<0||id==null||id.isEmpty()||id.indexOf(0)>=0)throw new IllegalArgumentException("Invalid history position");
            this.recordedAt=recordedAt;this.id=id;
        }
    }
    static final class Entry {
        String id="";
        Cursor cursor(){return new Cursor(recordedAt,id);}
        final String outcome,txpowid,blockid,state;final long block,blockTimeMs,recordedAt,checkedAt,checkBlock;final String checkBlockid;final int depth;
        Entry(String outcome,String txpowid,long block,String blockid,long blockTimeMs,long recordedAt,String state,int depth,long checkedAt,long checkBlock,String checkBlockid){
            this.outcome=outcome;this.txpowid=txpowid;this.block=block;this.blockid=blockid;this.blockTimeMs=blockTimeMs;this.recordedAt=recordedAt;
            this.state=state;this.depth=depth;this.checkedAt=checkedAt;this.checkBlock=checkBlock;this.checkBlockid=checkBlockid;
        }
        boolean current(){return ChainReview.CURRENT.equals(state)&&depth>=0&&block==checkBlock&&blockid.equalsIgnoreCase(checkBlockid);}
        String status(){
            if(current())return "On-chain · "+depth+" confirmations";
            if(ChainReview.MISSING.equals(state))return "Previously verified · not found in latest node check";
            if(ChainReview.CURRENT.equals(state))return "Inclusion changed · reconciliation required";
            return "Verification recorded · recheck pending";
        }
    }
}

package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.*;
import org.json.*;

/** Retained taker expectations and linked evidence; stored data alone is never a fresh chain check. */
final class TakerReceipt {
    // Keep both old/new evidence plus receipt metadata inside a correction CursorWindow. Oversized evidence leaves
    // the pending receipt intact; this is a local storage limit, not a chain TxPoW-size estimate.
    static final int MAX_BYTES=512*1024;
    final Intent intent;
    final DexHistory.Spend spend;
    final String json;
    private TakerReceipt(Intent intent,DexHistory.Spend spend,String json) throws JSONException {
        this.intent=intent;this.spend=DexHistory.copyProof(spend);this.json=json;
    }
    static final class Intent {
        final List<String> sources;
        final String transactionId,payout,token,sourceKind,handle;
        final BigDecimal proceeds,price,minima;
        final boolean buy;
        final String json;
        Intent(JSONObject row) throws JSONException {
            JSONArray ids=row.getJSONArray("sources");
            if(ids.length()==0 || ids.length()>20)throw new IllegalArgumentException("Invalid taker sources");
            List<String> selected=new ArrayList<>();Set<String> unique=new HashSet<>();
            for(int i=0;i<ids.length();i++) {
                String id=ids.getString(i);
                if(!FundingCoins.hex(id) || !unique.add(id.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Invalid taker source");
                selected.add(id);
            }
            sources=Collections.unmodifiableList(selected);
            transactionId=row.getString("transactionid");payout=row.getString("payout");token=row.getString("token");
            if(!FundingCoins.hex(transactionId) || !FundingCoins.hex(payout) || !FundingCoins.hex(token))
                throw new IllegalArgumentException("Incomplete taker identity or payout");
            proceeds=positive(row,"proceeds");price=positive(row,"price");minima=positive(row,"minima");
            Object direction=row.get("buy");if(!(direction instanceof Boolean))throw new IllegalArgumentException("Invalid taker direction");
            buy=(Boolean)direction;
            if(buy ? (!Util.MINIMA_TOKENID.equalsIgnoreCase(token) || proceeds.compareTo(minima)!=0)
                    : !DexContract.USDT_ID.equalsIgnoreCase(token))throw new IllegalArgumentException("Unexpected taker proceeds");
            sourceKind=row.getString("source");
            if(!Arrays.asList("BOOK","POOL","BOOK+POOL").contains(sourceKind))throw new IllegalArgumentException("Unknown taker source kind");
            handle=row.optString("intent","");json=row.toString();
        }
        private static BigDecimal positive(JSONObject row,String field) throws JSONException {
            BigDecimal value=Util.dec(row.getString(field));
            if(value.signum()<=0)throw new IllegalArgumentException("Invalid taker "+field);return value;
        }
        boolean same(Intent other) {
            if(other==null || sources.size()!=other.sources.size())return false;
            for(int i=0;i<sources.size();i++)if(!sources.get(i).equalsIgnoreCase(other.sources.get(i)))return false;
            return transactionId.equalsIgnoreCase(other.transactionId) && payout.equalsIgnoreCase(other.payout)
                    && token.equalsIgnoreCase(other.token) && proceeds.compareTo(other.proceeds)==0
                    && price.compareTo(other.price)==0 && minima.compareTo(other.minima)==0
                    && buy==other.buy && sourceKind.equals(other.sourceKind) && handle.equals(other.handle);
        }
    }
    static TakerReceipt capture(JSONObject expected,Map<String,DexHistory.Spend> found) {
        try {
            Intent intent=new Intent(expected);
            DexHistory.Spend first=TakerEvidence.match(found,intent.sources,intent.transactionId,intent.payout,intent.token,intent.proceeds);
            if(first==null || !TakerEvidence.readyToRecord(first,intent.sources.get(0)))
                throw new IllegalArgumentException("Incomplete linked taker evidence");
            JSONArray inputs=new JSONArray();
            for(String source:intent.sources) {
                DexHistory.Spend leg=found.get(source);
                if(!TakerEvidence.readyToRecord(leg,source) || leg.inputCount!=first.inputCount)
                    throw new IllegalArgumentException("Inconsistent taker input evidence");
                inputs.put(new JSONObject().put("index",leg.inputIndex).put("coin",leg.input));
            }
            JSONObject proof=new JSONObject().put("txpowid",first.txpowid).put("transactionid",first.transactionId)
                    .put("inputs",inputs).put("input_count",first.inputCount).put("outputs",first.outputs)
                    .put("state",first.transactionState).put("block",first.inclusionBlock).put("blockid",first.inclusionBlockId)
                    .put("timems",first.inclusionTimeMs).put("depth",first.confirmations)
                    .put("proof_epoch",ChainEvidence.PROOF_EPOCH).put("proof_order",first.proofOrder).put("proof_time",first.proofTimeMs);
            String json=new JSONObject().put("version",1).put("expected",new JSONObject(intent.json)).put("proof",proof).toString();
            if(json.length()>MAX_BYTES || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)
                throw new IllegalArgumentException("Taker evidence exceeds the local receipt storage limit");
            return new TakerReceipt(intent,first,json);
        } catch(JSONException invalid) {throw new IllegalArgumentException("Invalid taker receipt",invalid);}
    }
    /** Complete, bounded saved input and an exact supported version are required for repair. */
    static JSONObject stored(String raw) throws JSONException {
        if(raw==null || raw.length()>MAX_BYTES || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid taker archive size");
        JSONObject row=MakerConfig.storedObject(raw);
        if(MakerConfig.storedBlock(row.get("version"))!=1)throw new IllegalArgumentException("Unsupported taker archive version");
        return row;
    }
    boolean sameIntent(String existing) {
        try {
            JSONObject row=stored(existing);
            return intent.same(new Intent(row.getJSONObject("expected")));
        } catch(JSONException | RuntimeException invalid) {return false;}
    }
}

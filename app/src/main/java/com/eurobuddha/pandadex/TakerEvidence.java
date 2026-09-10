package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

final class TakerEvidence {
    static DexHistory.Spend match(Map<String, DexHistory.Spend> found, List<String> sources,
                                  String transactionId, String payout, String token, BigDecimal amount) {
        if (found == null || sources == null || sources.isEmpty() || !FundingCoins.hex(transactionId)
                || !FundingCoins.hex(payout) || !FundingCoins.hex(token) || amount == null || amount.signum() <= 0) return null;
        DexHistory.Spend first = found.get(sources.get(0));
        if (first == null || first.confirmations < 0 || !transactionId.equalsIgnoreCase(first.transactionId)) return null;
        java.util.Set<String> unique=new java.util.HashSet<>();
        java.util.Set<Integer> positions=new java.util.HashSet<>();
        if (!FundingCoins.hex(first.txpowid) || sources.size()>20) return null;
        for (String id : sources) {
            if (!FundingCoins.hex(id) || !unique.add(id.toLowerCase(java.util.Locale.ROOT))) return null;
            DexHistory.Spend spend = found.get(id);
            if (spend == null || spend.confirmations < 0 || !first.txpowid.equalsIgnoreCase(spend.txpowid)
                    || !transactionId.equalsIgnoreCase(spend.transactionId)
                    || spend.input == null || !id.equalsIgnoreCase(spend.input.optString("coinid",""))
                    || spend.inputIndex<0 || spend.inputIndex>=spend.inputCount || !positions.add(spend.inputIndex)
                    || spend.inclusionBlock!=first.inclusionBlock
                    || !first.inclusionBlockId.equalsIgnoreCase(spend.inclusionBlockId)
                    || spend.inclusionTimeMs!=first.inclusionTimeMs) return null;
        }
        for (int i = 0; i < first.outputs.length(); i++) {
            JSONObject out = first.outputs.optJSONObject(i);
            if (DexHistory.paysTo(out, payout) && DexHistory.sameToken(token, out.optString("tokenid", ""))
                    && DexHistory.value(out).compareTo(amount) == 0) return first;
        }
        return null;
    }
    /** Storage needs exact coordinates as well as the separately checked all-source/payout proof. */
    static boolean readyToRecord(DexHistory.Spend spend,String source) {
        return spend!=null && FundingCoins.hex(source) && FundingCoins.hex(spend.txpowid)
                && FundingCoins.hex(spend.transactionId) && spend.confirmations>=0
                && spend.input!=null && source.equalsIgnoreCase(spend.input.optString("coinid",""))
                && spend.inclusionBlock>0 && FundingCoins.hex(spend.inclusionBlockId)
                && spend.inclusionTimeMs>0 && spend.proofOrder>0 && spend.proofTimeMs>0;
    }

}

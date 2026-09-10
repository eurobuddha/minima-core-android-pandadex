package com.eurobuddha.pandadex;

/** Guards correcting the winner of a source coin. Inclusion of a different spender must
 * have been checked after the previous spender was explicitly missing, in this process. */
final class ReceiptRepair {
    static boolean moved(long oldBlock, String oldBlockId, long newBlock, String newBlockId) {
        return newBlock > 0 && FundingCoins.hex(newBlockId)
                && (oldBlock != newBlock || !newBlockId.equalsIgnoreCase(oldBlockId));
    }
    static boolean usable(FillSettler.Entry entry, DexHistory.Spend spend) {
        return entry != null && spend != null && spend.confirmations >= 0 && FundingCoins.hex(spend.txpowid)
                && spend.input != null && entry.coinid.equalsIgnoreCase(spend.input.optString("coinid", ""))
                && spend.inclusionBlock > 0 && spend.inclusionTimeMs > 0 && FundingCoins.hex(spend.inclusionBlockId);
    }
    static boolean superseded(DexHistory.Spend spend, String state, String epoch, long order, String blockid) {
        return ChainEvidence.PROOF_EPOCH.equals(epoch) && order > spend.proofOrder
                && (ChainReview.MISSING.equals(state) || (ChainReview.CURRENT.equals(state)
                && !spend.inclusionBlockId.equalsIgnoreCase(blockid)));
    }
    static boolean shouldAdopt(DexHistory.Spend spend, String epoch, long order) {
        return spend.proofOrder > 0 && (!ChainEvidence.PROOF_EPOCH.equals(epoch) || spend.proofOrder > order);
    }
    static boolean allowed(FillSettler.Entry entry, DexHistory.Spend spend, String previous,
                           String state, String epoch, long missingOrder) {
        return usable(entry, spend) && FundingCoins.hex(previous) && !previous.equalsIgnoreCase(spend.txpowid)
                && ChainReview.MISSING.equals(state) && ChainEvidence.PROOF_EPOCH.equals(epoch)
                && missingOrder > 0 && spend.proofOrder > missingOrder;
    }
}

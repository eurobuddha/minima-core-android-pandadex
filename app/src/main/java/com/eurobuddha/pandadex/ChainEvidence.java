package com.eurobuddha.pandadex;

import org.json.JSONObject;

/** Stock-node inclusion evidence, reused from PandaPools ActivityLog. */
final class ChainEvidence {
    // Order accepted proof replies independently of wall-clock changes. Only this process's
    // epoch can authorize a repair; after restart the previous spender must be checked again.
    static final String PROOF_EPOCH = java.util.UUID.randomUUID().toString();
    private static final java.util.concurrent.atomic.AtomicLong PROOF_ORDER = new java.util.concurrent.atomic.AtomicLong();
    static long nextProofOrder() { return PROOF_ORDER.incrementAndGet(); }
    static final String BLOCK_TIME_NOTE = " Time basis: verified inclusion block.";
    static final String OBSERVED_TIME_NOTE = " Time basis: observation on this device.";
    static int confirmationDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (!TxValidation.truthy(reply, "status") || !TxValidation.truthy(r, "found")) return -1;
        try {
            int depth = new java.math.BigDecimal(r.get("confirmations").toString()).intValueExact();
            return depth < 0 ? -1 : depth;
        } catch (Exception invalid) { return -1; }
    }
    static long positiveLong(JSONObject object, String field) {
        if (object == null) return 0;
        try {
            String value = object.get(field).toString();
            if (!value.matches("[0-9]{1,19}")) return 0;
            long number = Long.parseLong(value);
            return number > 0 ? number : 0;
        } catch (Exception invalid) { return 0; }
    }

    static long inclusionBlock(JSONObject inclusion) {
        return confirmationDepth(inclusion) < 0 ? 0 : positiveLong(inclusion.optJSONObject("response"), "block");
    }

    static String inclusionBlockId(JSONObject inclusion) {
        if (confirmationDepth(inclusion) < 0) return "";
        JSONObject response = inclusion.optJSONObject("response");
        String id = response == null ? "" : response.optString("blockid", "");
        return FundingCoins.hex(id) ? id : "";
    }

    /** Header time belongs to the inclusion block, not the transaction creator's proposed time. */
    static long inclusionTime(JSONObject inclusion, JSONObject blockReply, String txpowid) {
        long height = inclusionBlock(inclusion);
        String blockid = inclusionBlockId(inclusion);
        if (height <= 0 || blockid.isEmpty() || !FundingCoins.hex(txpowid)
                || !TxValidation.truthy(blockReply, "status")) return 0;
        JSONObject block = blockReply.optJSONObject("response");
        if (block == null || !TxValidation.truthy(block, "isblock")
                || !blockid.equalsIgnoreCase(block.optString("txpowid", ""))) return 0;
        JSONObject header = block.optJSONObject("header");
        if (positiveLong(header, "block") != height) return 0;
        boolean contains = blockid.equalsIgnoreCase(txpowid) && TxValidation.truthy(block, "istransaction");
        JSONObject body = block.optJSONObject("body");
        org.json.JSONArray ids = body == null ? null : body.optJSONArray("txnlist");
        if (ids != null) for (int i = 0; i < ids.length() && !contains; i++) {
            Object value = ids.opt(i);
            if (value instanceof String && txpowid.equalsIgnoreCase((String) value)) contains = true;
        }
        return contains ? positiveLong(header, "timemilli") : 0;
    }

    static String transactionId(JSONObject txpow) {
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        String id = txn == null ? "" : txn.optString("transactionid", "");
        return FundingCoins.hex(id) ? id : "";
    }
}

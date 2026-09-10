package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Creation counterpart to TakerEvidence: bind wallet funding to exact intended outputs.
 * An order ID, owner key or payout address alone is public and cannot prove this submission. */
final class CreationEvidence {
    static Pending.Row prepare(boolean buy, BigDecimal minima, BigDecimal price, String token,
                               BigDecimal lock, String payout, String[] state,
                               List<JSONObject> coins, long block) {
        // Validate through the exact builder used by the transaction, without signing/posting.
        DexTxn.createOrderSteps("receipt", token, lock, payout, state, coins);
        try {
            JSONArray inputs = new JSONArray(), expectedState = new JSONArray();
            BigDecimal total = BigDecimal.ZERO;
            for (JSONObject coin : coins) {
                inputs.put(coin.getString("coinid"));
                total = total.add(FundingCoins.coinValue(coin));
            }
            for (String value : state) expectedState.put(value);
            Pending.Row row = new Pending.Row();
            row.kind = Pending.PLACE; row.buy = buy; row.minima = minima; row.price = price;
            row.orderId = state[4]; row.submitBlock = block; row.submitMs = System.currentTimeMillis();
            row.creation = new JSONObject().put("inputs", inputs).put("state", expectedState)
                    .put("token", token).put("lock", lock.toPlainString()).put("payout", payout)
                    .put("change", total.subtract(lock).toPlainString());
            return row;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid creation evidence", invalid); }
    }

    static List<String> inputs(Pending.Row row) {
        JSONArray saved = row == null || row.creation == null ? null : row.creation.optJSONArray("inputs");
        if (saved == null || saved.length() == 0 || saved.length() > FundingCoins.SAFE_COINS) return Collections.emptyList();
        List<String> result = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (int i = 0; i < saved.length(); i++) {
            String id = saved.optString(i, "");
            if (!FundingCoins.hex(id) || !unique.add(id.toLowerCase(Locale.ROOT))) return Collections.emptyList();
            result.add(id);
        }
        return result;
    }

    static Order5 match(Pending.Row row, Map<String, DexHistory.Spend> found) {
        List<String> inputs = inputs(row);
        if (inputs.isEmpty() || found == null || !Pending.PLACE.equals(row.kind)) return null;
        DexHistory.Spend first = found.get(inputs.get(0));
        if (first == null || first.confirmations < 0 || !FundingCoins.hex(first.txpowid)
                || first.inputCount != inputs.size()) return null;
        Set<Integer> positions = new HashSet<>();
        for (String id : inputs) {
            DexHistory.Spend spend = found.get(id);
            if (spend == null || spend.confirmations < 0 || spend.inputCount != inputs.size()
                    || !first.txpowid.equalsIgnoreCase(spend.txpowid) || spend.input == null
                    || !id.equalsIgnoreCase(spend.input.optString("coinid", ""))
                    || spend.inputIndex < 0 || spend.inputIndex >= inputs.size()
                    || !positions.add(spend.inputIndex)) return null;
        }
        try {
            JSONObject saved = row.creation;
            BigDecimal lock = Util.decOr(saved.getString("lock"), null);
            BigDecimal change = Util.decOr(saved.getString("change"), null);
            String token = saved.getString("token"), payout = saved.getString("payout");
            if (!DexTxn.amountOk(lock) || change == null || change.signum() < 0
                    || !FundingCoins.hex(token) || !FundingCoins.hex(payout)) return null;
            if (first.outputs.length() != (change.signum() > 0 ? 2 : 1)) return null;
            JSONObject output = first.outputs.optJSONObject(0);
            if (!TxValidation.truthy(output, "storestate") || !DexHistory.paysTo(output, DexContract.ADDR_V5)
                    || !DexHistory.sameToken(token, output.optString("tokenid", ""))
                    || FundingCoins.coinValue(output).compareTo(lock) != 0) return null;
            if (change.signum() > 0) {
                JSONObject rest = first.outputs.optJSONObject(1);
                if (rest == null || !(Boolean.FALSE.equals(rest.opt("storestate")) || "false".equals(rest.opt("storestate")))
                        || !DexHistory.paysTo(rest, payout) || !DexHistory.sameToken(token, rest.optString("tokenid", ""))
                        || FundingCoins.coinValue(rest).compareTo(change) != 0) return null;
            }
            JSONArray expected = saved.getJSONArray("state"), actual = first.transactionState;
            if (expected.length() != 9 || actual.length() != 9) return null;
            Set<Integer> ports = new HashSet<>();
            for (int i = 0; i < actual.length(); i++) {
                JSONObject field = actual.getJSONObject(i);
                String portText = field.get("port").toString();
                if (!portText.matches("[0-8]")) return null;
                int port = Integer.parseInt(portText);
                if (!ports.add(port)) return null;
                String a = field.getString("data"), e = expected.getString(port);
                if (port == 0 || port == 1 || port == 3 || port == 4) {
                    if (!FundingCoins.hex(e) || !e.equalsIgnoreCase(a)) return null;
                } else {
                    BigDecimal actualNumber = Util.decOr(a, null), expectedNumber = Util.decOr(e, null);
                    if (actualNumber == null || expectedNumber == null || actualNumber.compareTo(expectedNumber) != 0) return null;
                }
            }
            Order5 created = DexHistory.orderOutput(first, 0);
            return created != null && row.orderId.equalsIgnoreCase(created.orderId) ? created : null;
        } catch (Exception invalid) { return null; }
    }
}

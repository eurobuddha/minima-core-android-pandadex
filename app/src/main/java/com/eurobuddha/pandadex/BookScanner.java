package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded V5 book scan — TWO commands total (Limit fired ten):
 *
 *   1. coins simplestate:true order:desc depth:<SCAN_DEPTH> address:<V5>   — the book
 *   2. coins relevant:true address:<V5>                                    — ownership belt
 *
 * SCAN_DEPTH sits under the node's ~1024-block visibility trim and above EXPIRY_BLOCKS, so
 * the scan sees every live order: GTC renewal keeps
 * every live order coin younger than expiry, and anything older is dead (expiry-sweepable)
 * anyway. Bounded because the upstream node's 256KB Binder overflow is an uncatchable
 * app-kill (HARD upstream-node constraint).
 *
 * The `truncated` flag distinguishes transport failure from a genuinely empty book so the
 * caller keeps its last-good view (Limit's stale-empty lesson).
 */
public final class BookScanner {

    public interface Cb {
        void onBook(Map<String, Order5> orders, boolean truncated, List<String> rawJsons);
    }

    private BookScanner() {}

    public static void scan(NodeApi node, Cb cb) {
        final Map<String, Order5> found = new LinkedHashMap<>();
        final java.util.List<String> raw = new java.util.ArrayList<>();
        final boolean[] truncated = {false};

        node.cmd("coins simplestate:true order:desc depth:" + DexContract.SCAN_DEPTH
                + " address:" + DexContract.ADDR_V5, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                if (!(resp instanceof JSONArray)) {
                    truncated[0] = true;
                    relevant(node, found, truncated, raw, cb);
                    return;
                }
                JSONArray arr = (JSONArray) resp;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Order5 o = Order5.from(c);
                    if (o == null) {
                        // a coin at the book address we can't read = an incomplete view;
                        // callers must not treat the missing entry as a departed order
                        truncated[0] = true;
                        continue;
                    }
                    if (!found.containsKey(o.coinid)) {
                        found.put(o.coinid, o);
                        raw.add(c.toString());
                    }
                }
                relevant(node, found, truncated, raw, cb);
            }
            @Override public void onError(String message) {
                truncated[0] = true;
                relevant(node, found, truncated, raw, cb);
            }
        });
    }

    private static void relevant(NodeApi node, Map<String, Order5> found, boolean[] truncated,
                                 List<String> raw, Cb cb) {
        node.cmd("coins relevant:true address:" + DexContract.ADDR_V5, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                if (resp instanceof JSONArray) {
                    JSONArray arr = (JSONArray) resp;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        Order5 o = found.get(c.optString("coinid", ""));
                        if (o != null) o.markRelevant();
                    }
                }
                cb.onBook(found, truncated[0], raw);
            }
            @Override public void onError(String message) {
                // ownership belt is best-effort — a failure here does NOT taint the book
                cb.onBook(found, truncated[0], raw);
            }
        });
    }
}

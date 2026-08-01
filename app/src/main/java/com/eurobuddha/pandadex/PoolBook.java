package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded PandaPools sentinel discovery for PandaDEX. No script enumeration, no APK dependency. */
public final class PoolBook {

    public interface Listener {
        void onPools(List<Pool> pools);
        void onError(String msg);
    }

    interface Commander { void cmd(String command, NodeApi.Cb cb); }

    private final Commander node;

    public PoolBook(NodeApi node) { this.node = node::cmd; }

    PoolBook(Commander node) { this.node = node; }

    public void scan(Listener cb) {
        node.cmd("coins simplestate:true order:desc depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH
                + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                if (!(resp instanceof JSONArray)) { cb.onError("pool scan returned no coin list"); return; }
                JSONArray coins = (JSONArray) resp;
                Map<String, String[]> params = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String tok = state(c, 2), oadr = state(c, 3), opk = state(c, 4), kmin = state(c, 5);
                    if (tok == null || oadr == null || opk == null || kmin == null) continue;
                    if (!DexContract.USDT_ID.equalsIgnoreCase(tok)) continue;
                    params.putIfAbsent((opk + "|" + oadr + "|" + tok + "|" + kmin).toLowerCase(),
                            new String[]{opk, oadr, tok, kmin});
                }
                if (params.isEmpty()) { cb.onPools(new ArrayList<>()); return; }
                derive(new ArrayList<>(params.values()), cb);
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    private void derive(List<String[]> params, Listener cb) {
        List<Pool> pools = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(params.size());
        for (String[] p : params) {
            final String opk = p[0], oadr = p[1], tok = p[2], kmin = p[3];
            final String script;
            try { script = PoolCovenant.script(opk, oadr, tok, kmin); }
            catch (Exception e) { if (pending.decrementAndGet() == 0) fund(pools, cb); continue; }
            node.cmd("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    try {
                        JSONObject resp = j.getJSONObject("response");
                        if (truthy(resp, "parseok")) {
                            JSONObject sc = resp.getJSONObject("script");
                            Pool pool = new Pool();
                            pool.opk = opk; pool.oadr = oadr; pool.tok = tok; pool.kmin = kmin;
                            pool.address = sc.getString("address");
                            pool.mxaddress = sc.optString("mxaddress", "");
                            pool.covenantScript = script;
                            synchronized (pools) { pools.add(pool); }
                        }
                    } catch (Exception ignore) {}
                    if (pending.decrementAndGet() == 0) fund(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) fund(pools, cb); }
            });
        }
    }

    private void fund(List<Pool> pools, Listener cb) {
        if (pools.isEmpty()) { cb.onPools(pools); return; }
        AtomicInteger pending = new AtomicInteger(pools.size());
        for (Pool pool : pools) {
            node.cmd("coins simplestate:true depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH + " address:" + pool.address, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Object resp = j.opt("response");
                    JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                    int mb = 0, tb = 0;
                    for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.optJSONObject(i);
                        if (c == null || c.optBoolean("spent", false)) continue;
                        String tid = c.optString("tokenid", "");
                        if (Util.MINIMA_TOKENID.equals(tid)) {
                            BigDecimal amt = Util.dec(c.optString("amount", "0"));
                            if (pool.reserveM == null || amt.compareTo(pool.reserveM) > 0) {
                                pool.reserveM = amt; pool.coinidM = c.optString("coinid", ""); mb = c.optInt("created", 0);
                            }
                        } else if (pool.tok.equalsIgnoreCase(tid)) {
                            BigDecimal amt = Util.dec(c.optString("tokenamount", c.optString("amount", "0")));
                            if (pool.reserveT == null || amt.compareTo(pool.reserveT) > 0) {
                                pool.reserveT = amt; pool.coinidT = c.optString("coinid", ""); tb = c.optInt("created", 0);
                                pool.tokDecimals = tokenDecimals(c.opt("token"));
                            }
                        }
                    }
                    pool.reserveBlock = Math.max(mb, tb);
                    if (pending.decrementAndGet() == 0) done(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) done(pools, cb); }
            });
        }
    }

    private void done(List<Pool> pools, Listener cb) {
        List<Pool> funded = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            if (p.address != null && !seen.add(p.address.toLowerCase())) continue;
            funded.add(p);
        }
        cb.onPools(funded);
    }

    static String state(JSONObject coin, int port) {
        if (coin == null) return null;
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), ""); return v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) {
                String d = e.optString("data", ""); return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    private static boolean truthy(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.equals("1") || s.equalsIgnoreCase("true");
        }
        return false;
    }

    private static int tokenDecimals(Object token) {
        if (token instanceof JSONObject) return ((JSONObject) token).optInt("decimals", 8);
        return 8;
    }
}

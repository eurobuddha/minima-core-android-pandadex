package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Minimal public-explorer tRPC verifier for exported txpow IDs. */
public final class ExplorerVerifier {

    static final String[] HOSTS = {
            "https://explorer.minima.global",
            "https://block.minima.global"
    };

    public static final class Result {
        public String status;
        public long block;
        public String note;
        public String host;
        /** Identity actually matched in the explorer response, never just the requested URL. */
        public String txpowid = "";
        boolean confirms(String requested) {
            return FundingCoins.hex(requested) && requested.equalsIgnoreCase(txpowid)
                    && block > 0 && "EXPLORER_OK".equals(status);
        }
    }

    private ExplorerVerifier() {}

    public static Result lookup(String txpowid) {
        if (!FundingCoins.hex(txpowid)) {
            Result r = new Result();
            r.status = "LOCAL_ONLY";
            r.note = "No txpow ID captured for this row";
            return r;
        }
        Result last = null;
        for (String host : HOSTS) {
            Result one = lookup(txpowid, host);
            if (one.confirms(txpowid)) return one;
            last = one;
        }
        Result r = last == null ? new Result() : last;
        r.status = "EXPLORER_UNAVAILABLE";
        r.note = "Public explorers unavailable or did not return this TxPoW";
        return r;
    }

    private static Result lookup(String txpowid, String host) {
        Result r = new Result();
        r.host = host;
        HttpURLConnection c = null;
        try {
            String input = "{\"0\":{\"json\":{\"id\":\"" + txpowid + "\"}}}";
            String qs = "batch=1&input=" + URLEncoder.encode(input, "UTF-8");
            URL url = new URL(host + "/api/trpc/txpow.findById?" + qs);
            c = (HttpURLConnection) url.openConnection();
            // Only the configured HTTPS origins may receive the receipt lookup.
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "PandaDEX");
            c.setRequestProperty("Referer", host + "/");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                r.status = "EXPLORER_HTTP_" + code;
                r.note = host + " returned HTTP " + code;
                return r;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try (java.io.InputStream is = c.getInputStream()) {
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > 4 * 1024 * 1024) throw new java.io.IOException("Explorer response too large");
                    bos.write(buf, 0, n);
                }
            }
            return classify(txpowid, host, bos.toString("UTF-8"));
        } catch (Exception t) {
            r.status = "EXPLORER_ERROR";
            r.note = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return r;
        } finally { if (c != null) c.disconnect(); }
    }
    /** Pure boundary parser, retaining the existing tRPC envelope and stock integer guard. */
    static Result classify(String requested, String host, String body) {
        Result r = new Result(); r.host = host;
        r.status = "EXPLORER_ERROR"; r.note = "Explorer reply could not be verified";
        if (!FundingCoins.hex(requested)) return r;
        try {
            JSONArray arr = new JSONArray(body);
            if (arr.length() != 1) return r;
            JSONObject first = arr.optJSONObject(0);
            if (first == null || first.has("error")) return r;
            JSONObject result = first.optJSONObject("result");
            JSONObject data = result == null ? null : result.optJSONObject("data");
            if (data == null || !data.has("json")) return r;
            JSONObject d = data.optJSONObject("json");
            if (d == null && !data.isNull("json")) return r;
            if (d == null || !d.has("block_number") || d.isNull("block_number")) {
                r.status = "EXPLORER_NOT_FOUND";
                r.note = "Explorer returned no indexed inclusion block; original evidence retained";
                return r;
            }
            long block = ChainEvidence.positiveLong(d, "block_number");
            if (block <= 0) return r;
            // Both fields are present in the live primary/backup API. An echoed request ID
            // alone cannot corroborate a different indexed TxPoW or a stale cached response.
            Object indexed = d.opt("txpow_id"), identity = d.opt("id");
            if (!(indexed instanceof String) || !(identity instanceof String)
                    || !requested.equalsIgnoreCase((String) indexed)
                    || !requested.equalsIgnoreCase((String) identity)) {
                r.status = "EXPLORER_MISMATCH";
                r.note = "Explorer transaction identity did not match the requested TxPoW";
                return r;
            }
            r.status = "EXPLORER_OK"; r.block = block; r.txpowid = (String) indexed;
            r.note = "Explorer reports this TxPoW at block " + block + " on " + host;
            return r;
        } catch (Exception invalid) { return r; }
    }

}

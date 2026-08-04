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
    }

    private ExplorerVerifier() {}

    public static Result lookup(String txpowid) {
        if (txpowid == null || txpowid.isEmpty()) {
            Result r = new Result();
            r.status = "LOCAL_ONLY";
            r.note = "No txpow ID captured for this row";
            return r;
        }
        Result last = null;
        for (String host : HOSTS) {
            Result one = lookup(txpowid, host);
            if (one.block > 0) return one;
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
        try {
            String input = "{\"0\":{\"json\":{\"id\":\"" + txpowid + "\"}}}";
            String qs = "batch=1&input=" + URLEncoder.encode(input, "UTF-8");
            URL url = new URL(host + "/api/trpc/txpow.findById?" + qs);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
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
                while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            JSONObject first = arr.optJSONObject(0);
            JSONObject result = first == null ? null : first.optJSONObject("result");
            JSONObject data = result == null ? null : result.optJSONObject("data");
            JSONObject d = data == null ? null : data.optJSONObject("json");
            long block = d == null ? 0 : d.optLong("block_number", 0);
            if (block > 0) {
                r.status = "EXPLORER_OK";
                r.block = block;
                r.note = "TxPoW found on " + host;
            } else {
                r.status = "EXPLORER_NOT_FOUND";
                r.note = host + " returned no block_number";
            }
            return r;
        } catch (Throwable t) {
            r.status = "EXPLORER_ERROR";
            r.note = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return r;
        }
    }
}

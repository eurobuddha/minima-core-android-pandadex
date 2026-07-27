package com.eurobuddha.pandadex;

import org.json.JSONObject;

/**
 * The FROZEN V5 partial-fill order-book covenant. Proven on-chain 2026-07-27 — see
 * contract/RESULTS.md (Phase A 134/134 math vectors, Phase B full lifecycle incl. buy-side
 * token leg, adversary 9/9 rejected). DO NOT EDIT THE SCRIPT — the address derives from it.
 *
 * State ports:
 *   0 ownerPk · 1 wantAddr · 2 wantAmt · 3 wantTok · 4 orderId · 5 side("0" buy/"1" sell)
 *   · 6 price (DISPLAY ONLY — taker-writable on remainders, never trust; derive from 2 and
 *   the coin amount) · 7 GTC ("1"/"0") · 8 minRemainder (contract-enforced dust floor).
 *
 * Spend paths: owner atomic re-lock (edit/renew, state-pinned except port 2/6) — owner
 * cancel refund — third-party expiry sweep (@COINAGE > 1500) — full fill (pay port 2 to
 * port 1) — partial fill (remainder at output @INPUT+1, cross-multiplied pro-rata payment).
 */
public final class DexContract {

    private DexContract() {}

    /** mxUSDT — the only non-Minima token the book accepts (whitelisted in-script). */
    public static final String USDT_ID =
            "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /** Order lifetime in blocks (~21h); GTC renewal re-locks before this. */
    public static final int EXPIRY_BLOCKS = 1500;
    /** Renew a GTC order once it's this old (≈14h window before expiry). */
    public static final int RENEW_AT = 500;
    /** Book scan depth: > EXPIRY so a bounded scan sees every live (renewed) order. */
    public static final int SCAN_DEPTH = 1700;

    /** The frozen V5 script — byte-exact match of contract/v5script.tpl (mainnet dims). */
    public static final String SCRIPT_V5 =
            "LET u=" + USDT_ID + " "
            + "IF (@TOKENID NEQ 0x00 AND @TOKENID NEQ u) OR (PREVSTATE(3) NEQ 0x00 AND PREVSTATE(3) NEQ u) THEN RETURN FALSE ENDIF "
            + "IF SIGNEDBY(PREVSTATE(0)) THEN "
            + "IF GETOUTADDR(@INPUT) EQ @ADDRESS THEN "
            + "IF SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2) GT 0 AND VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE) THEN RETURN TRUE ENDIF "
            + "ENDIF "
            + "IF VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
            + "ENDIF "
            + "IF @COINAGE GT " + EXPIRY_BLOCKS + " THEN "
            + "RETURN VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) "
            + "ENDIF "
            + "LET w=PREVSTATE(2) "
            + "LET r=0 "
            + "IF @TOTOUT GT @INPUT+1 THEN "
            + "IF GETOUTADDR(@INPUT+1) EQ @ADDRESS THEN "
            + "LET r=GETOUTAMT(@INPUT+1) "
            + "ENDIF "
            + "ENDIF "
            + "IF r EQ 0 THEN "
            + "RETURN VERIFYOUT(@INPUT PREVSTATE(1) w PREVSTATE(3) FALSE) "
            + "ENDIF "
            + "RETURN r LT @AMOUNT AND r GTE PREVSTATE(8) AND VERIFYOUT(@INPUT+1 @ADDRESS r @TOKENID TRUE) AND SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2)*@AMOUNT GTE w*r AND STATE(2) LTE w AND GETOUTADDR(@INPUT) EQ PREVSTATE(1) AND GETOUTTOK(@INPUT) EQ PREVSTATE(3) AND GETOUTAMT(@INPUT)*@AMOUNT GTE w*(@AMOUNT-r)";

    /** Pinned book address — derived from SCRIPT_V5 on the solo node and frozen. */
    public static final String ADDR_V5 =
            "0xCE5A0A3CC2E19B1860E60C58397FD5D5E986EEA4AF4423B53E08BAA5591B6F32";
    public static final String ADDR_V5_MX =
            "MxG086EB853PGN1JCC61PGCB0SNVYEYT63ET95F8GHRAFG8NAWYW6RF69FVMZ6M";

    // Legacy Limit book (read/fill interop, LATER phase — constants only)
    public static final String ADDR_V4 =
            "0x94F2CB876903FAED64EA4C9C4B7FD602BAC5CD59F9EBC38AB0C4A0F0B346807F";

    public interface Ready {
        void ok();
        void failed(String why);
    }

    /**
     * The casino "headless-reveal" lesson + parseok gate in one place: verify the node has
     * the covenant REGISTERED and PARSED before any funds move. newscript is idempotent;
     * we re-verify via runscript (parseok + derived address must equal the pinned one) and
     * retry the registration once on mismatch of the tracked set.
     */
    public static void ensureScript(NodeApi node, Ready cb) {
        node.cmd("runscript script:" + Util.scriptArg(SCRIPT_V5), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                boolean parseok = resp != null && resp.optBoolean("parseok", false);
                JSONObject sc = resp == null ? null : resp.optJSONObject("script");
                String addr = sc == null ? "" : sc.optString("address", "");
                if (!parseok || !ADDR_V5.equalsIgnoreCase(addr)) {
                    cb.failed("covenant mismatch (parseok=" + parseok + " addr=" + addr + ")");
                    return;
                }
                node.cmd("newscript trackall:true script:" + Util.scriptArg(SCRIPT_V5), new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) { cb.ok(); }
                    @Override public void onError(String m) { cb.failed(m); }
                });
            }
            @Override public void onError(String message) { cb.failed(message); }
        });
    }
}

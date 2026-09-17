#!/usr/bin/env python3
"""Read-only live preflight for PandaDEX + PandaPools composite liquidity.

This talks to the user's LIVE mainnet node by default (127.0.0.1:16005) but deliberately
uses only read-only commands: block/status/balance/coins/runscript. It does not call send,
txncreate, txninput, txnoutput, txnsign, txnbasics, txncheck, txnpost, newscript, coinnotify,
or mds mutations.

The output is evidence for `contract/COMPOSITE_LIVE_INTEROP.md`; it does not complete the
real-funds dust gate by itself.
"""
import json
import os
import sys
import urllib.parse
import urllib.request
import urllib.error
from decimal import Decimal

RPC = os.environ.get("PANDADEX_LIVE_RPC", "http://127.0.0.1:16005/")
MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"
SENTINEL = "0x50414E4441504F4F4C53"  # "PANDAPOOLS"
DEPTH = 1500

# Provenance: byte-compatible with PandaPools/PandaDEX PoolCovenant.TEMPLATE and the
# live anchor reannouncer. Do not change independently of those sources.
POOL_TEMPLATE = (
    "IF SIGNEDBY($OPK) THEN "
    "IF VERIFYOUT(@INPUT $OADR @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
    "RETURN GETOUTADDR(@INPUT) EQ @ADDRESS AND GETOUTTOK(@INPUT) EQ @TOKENID AND GETOUTAMT(@INPUT) GTE @AMOUNT "
    "ENDIF "
    "IF @TOKENID EQ 0x00 THEN "
    "ASSERT @INPUT % 2 EQ 0 LET s=@INPUT+1 "
    "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ $TOK "
    "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ $TOK "
    "LET x=@AMOUNT LET y=GETINAMT(s) LET nx=GETOUTAMT(@INPUT) LET ny=GETOUTAMT(s) "
    "ASSERT VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) "
    "ELSE "
    "ASSERT @TOKENID EQ $TOK AND @INPUT % 2 EQ 1 LET s=@INPUT-1 "
    "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ 0x00 "
    "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ 0x00 "
    "LET y=@AMOUNT LET x=GETINAMT(s) LET ny=GETOUTAMT(@INPUT) LET nx=GETOUTAMT(s) "
    "ASSERT VERIFYOUT(@INPUT @ADDRESS ny $TOK FALSE) "
    "ENDIF "
    "LET dx=nx-x LET dy=ny-y LET fx=MAX(dx 0)*5/1000 LET fy=MAX(dy 0)*5/1000 "
    "RETURN (nx-fx)*(ny-fy) GTE MAX(x*y $KMIN)"
)


def rpc(cmd, timeout=200):
    try:
        with urllib.request.urlopen(RPC + urllib.parse.quote(cmd, safe=""), timeout=timeout) as r:
            return json.load(r)
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", e)
        raise RuntimeError("live RPC unavailable at %s (%s)" % (RPC, reason))


def ok(cmd, timeout=200):
    r = rpc(cmd, timeout=timeout)
    if not r.get("status"):
        raise RuntimeError("cmd failed: " + cmd[:100] + " -> " + str(r.get("error") or r.get("response")))
    return r.get("response")


def resp_list(r):
    x = r.get("response")
    return x if isinstance(x, list) else []


def oneline(s):
    return " ".join(s.split())


def pool_script(opk, oadr, tok, kmin):
    return oneline(POOL_TEMPLATE.replace("$OPK", opk)
                   .replace("$OADR", oadr)
                   .replace("$TOK", tok)
                   .replace("$KMIN", kmin))


def state_value(coin, port):
    st = coin.get("state")
    if isinstance(st, dict):
        v = st.get(str(port))
        return str(v) if v not in (None, "") else None
    if isinstance(st, list):
        for e in st:
            if isinstance(e, dict) and int(e.get("port", -1)) == port:
                v = e.get("data")
                return str(v) if v not in (None, "") else None
    return None


def derive_pool(opk, oadr, tok, kmin):
    script = pool_script(opk, oadr, tok, kmin)
    r = ok("runscript script:" + json.dumps(script))
    parseok = r.get("parseok")
    if parseok is None and isinstance(r.get("script"), dict):
        parseok = r["script"].get("parseok", True)
    parseok = parseok is True or str(parseok).lower() in ("1", "true")
    sc = r.get("script") if isinstance(r.get("script"), dict) else {}
    return {
        "parseok": parseok,
        "script": script,
        "address": sc.get("address", ""),
        "mxaddress": sc.get("mxaddress", ""),
    }


def reserves(address, tok):
    rows = resp_list(rpc("coins order:desc depth:%d address:%s" % (DEPTH, address)))
    best_m = best_t = None
    for c in rows:
        if not isinstance(c, dict) or c.get("spent"):
            continue
        tid = c.get("tokenid", "")
        if tid == "0x00":
            amt = Decimal(str(c.get("amount", "0")))
            if best_m is None or amt > best_m[0]:
                best_m = (amt, c)
        elif tid.lower() == tok.lower():
            amt = Decimal(str(c.get("tokenamount", c.get("amount", "0"))))
            if best_t is None or amt > best_t[0]:
                best_t = (amt, c)
    return {
        "minima": best_m[1] if best_m else None,
        "token": best_t[1] if best_t else None,
        "scanned": len(rows),
    }


def balance(tokenid):
    r = ok("balance tokenid:" + tokenid)
    return r[0] if isinstance(r, list) and r else r


def discover():
    tip = ok("block").get("block")
    print("# PandaDEX + PandaPools live preflight")
    print("rpc:", RPC)
    print("block:", tip)
    print("MxUSD:", MXUSD)
    print("MINIMA balance:", json.dumps(balance("0x00"), sort_keys=True))
    print("MxUSD balance:", json.dumps(balance(MXUSD), sort_keys=True))
    print()

    cmd = "coins simplestate:true order:desc depth:%d address:%s" % (DEPTH, SENTINEL)
    beacons = resp_list(rpc(cmd))
    print("sentinel command:", cmd)
    print("sentinel coins:", len(beacons))

    grouped = {}
    for c in beacons:
        tok = state_value(c, 2)
        oadr = state_value(c, 3)
        opk = state_value(c, 4)
        kmin = state_value(c, 5)
        if not (tok and oadr and opk and kmin):
            continue
        if tok.lower() != MXUSD.lower():
            continue
        key = (opk + "|" + tok + "|" + kmin).lower()
        created = int(c.get("created", "0") or 0)
        cur = grouped.get(key)
        if cur is None or created > cur["created"]:
            grouped[key] = {"opk": opk, "oadr": oadr, "tok": tok, "kmin": kmin,
                            "created": created, "coinid": c.get("coinid", "")}

    print("candidate MxUSD pool beacons:", len(grouped))
    print()
    found = 0
    for i, p in enumerate(grouped.values(), 1):
        d = derive_pool(p["opk"], p["oadr"], p["tok"], p["kmin"])
        rs = reserves(d["address"], p["tok"]) if d["parseok"] and d["address"] else {"minima": None, "token": None, "scanned": 0}
        funded = rs["minima"] is not None and rs["token"] is not None
        if funded:
            found += 1
        print("pool #%d:" % i)
        print("  beacon:", p["coinid"], "created:", p["created"])
        print("  parseok:", d["parseok"])
        print("  address:", d["address"])
        print("  mxaddress:", d["mxaddress"])
        print("  owner payout:", p["oadr"])
        print("  owner pk:", p["opk"])
        print("  kmin:", p["kmin"])
        print("  reserve scan coins:", rs["scanned"], "funded:", funded)
        if rs["minima"]:
            print("  MINIMA reserve:", rs["minima"].get("coinid"), rs["minima"].get("amount"))
        if rs["token"]:
            print("  MxUSD reserve:", rs["token"].get("coinid"), rs["token"].get("tokenamount", rs["token"].get("amount")))
        print()
    print("funded pools:", found)
    if found == 0:
        print("status: PREFLIGHT_INCOMPLETE - no funded PandaPools MINIMA/MxUSD pool visible in the bounded scan")
        return 2
    print("status: PREFLIGHT_OK - funded pool(s) visible; live dust posting still requires explicit approval")
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] not in ("discover", "status"):
        print("usage: composite_live_preflight.py [discover|status]", file=sys.stderr)
        return 64
    try:
        if len(sys.argv) > 1 and sys.argv[1] == "status":
            print(json.dumps({"rpc": RPC, "block": ok("block"), "minima": balance("0x00"), "mxusd": balance(MXUSD)}, indent=2))
            return 0
        return discover()
    except RuntimeError as e:
        print("status: PREFLIGHT_UNAVAILABLE - " + str(e), file=sys.stderr)
        return 75


if __name__ == "__main__":
    raise SystemExit(main())

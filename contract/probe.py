"""Probe which V5 clause fails in the real tx context: register one tiny RETURN-<expr> script
per suspect construct, lock a probe coin with the order state at it, build the SAME
partial-fill-shaped txn, and read txncheck.valid.scripts."""
import json, sys, time
from decimal import Decimal

import dexlib as d
from phaseB import load, ceilg, fund_coin, d_int

st = load()

# the same numbers as the failing partial fill
LOCK = Decimal(100)
W = Decimal("0.575")
TAKE = Decimal(60)
REM = LOCK - TAKE                      # 40
PAY = ceilg(W * TAKE / LOCK)           # 0.345
NEWW = ceilg(W * REM / LOCK)           # 0.23

EXPRS = [
    ("totout", "@TOTOUT GT @INPUT+1"),
    ("outaddr1", "GETOUTADDR(@INPUT+1) EQ @ADDRESS"),
    ("outamt1", "GETOUTAMT(@INPUT+1) EQ 40"),
    ("payamt-token", "GETOUTAMT(0) EQ 0.345"),
    ("payaddr", "GETOUTADDR(0) EQ PREVSTATE(1)"),
    ("paytok", "GETOUTTOK(0) EQ " + st["tok"]),
    ("samestate", "SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8)"),
    ("state2", "STATE(2) EQ 0.23"),
    ("verifyout-rem", "VERIFYOUT(@INPUT+1 @ADDRESS GETOUTAMT(@INPUT+1) @TOKENID TRUE)"),
    ("crossmul-pay", "GETOUTAMT(0)*@AMOUNT GTE PREVSTATE(2)*(@AMOUNT-GETOUTAMT(@INPUT+1))"),
    ("crossmul-neww", "STATE(2)*@AMOUNT GTE PREVSTATE(2)*GETOUTAMT(@INPUT+1)"),
]


def probe(name, expr):
    script = "RETURN " + expr
    clean, addr, mx, pok = d.clean_script(script)
    if not pok:
        print(f"{name}: PARSE FAIL")
        return
    d.ok("newscript trackall:true script:" + json.dumps(clean))
    ostate = {"0": st["mypk"], "1": st["myaddr"], "2": str(W), "3": st["tok"],
              "4": "0xAB" + format(int(time.time_ns()) % 0xFFFF, "04X"), "5": "1",
              "6": "0.00575", "7": "1", "8": "1"}
    d.send_to(addr, LOCK, state=ostate)
    d.advance(4)
    coin = None
    for c in d.coins(addr):
        if d.state_of(c).get(4) == ostate["4"]:
            coin = c
    if coin is None:
        print(f"{name}: probe coin missing")
        return
    fund = fund_coin(st["tok"], PAY)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    newstate = dict(ostate)
    newstate["2"] = str(NEWW)
    txid = "pr" + str(time.time_ns() % 100000)
    d.ok("txncreate id:" + txid)
    try:
        for s in [
            "txninput id:%s coinid:%s" % (txid, coin["coinid"]),
            "txninput id:%s coinid:%s" % (txid, fund["coinid"]),
            "txnoutput id:%s amount:%s address:%s tokenid:%s storestate:false" % (txid, PAY, st["myaddr"], st["tok"]),
            "txnoutput id:%s amount:%s address:%s storestate:true" % (txid, REM, addr),
            "txnoutput id:%s amount:%s address:%s storestate:false" % (txid, TAKE, st["myaddr"]),
            "txnoutput id:%s amount:%s address:%s tokenid:%s storestate:false" % (txid, famt - PAY, st["myaddr"], st["tok"]),
        ] + ["txnstate id:%s port:%s value:%s" % (txid, p, v) for p, v in sorted(d_int(newstate).items())] \
          + ["txnsign id:%s publickey:auto" % txid, "txnbasics id:%s" % txid]:
            d.ok(s)
        chk = d.rpc("txncheck id:" + txid).get("response", {})
        v = chk.get("valid", {})
        print(f"{name}: scripts={v.get('scripts')}  ({expr[:60]})")
    finally:
        d.rpc("txndelete id:" + txid)


if __name__ == "__main__":
    only = sys.argv[1:] or None
    for name, expr in EXPRS:
        if only and name not in only:
            continue
        try:
            probe(name, expr)
        except Exception as e:
            print(f"{name}: ERROR {e}")

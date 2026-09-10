"""App-shape verification: replay the EXACT command sequences DexTxn.java builds (create /
sweep-with-partial / relock / cancel) against the private solo node, and gate each on the same
txncheck verdict the app gates on. This catches divergence between the proven harness shapes
and the app's own construction — the shapes in Java are what actually ship.

Usage: python3 appshapes.py            (needs phaseB.py setup to have run)
"""
import json, time
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR

import dexlib as d
from phaseB import load, find_order, fund_coin, d_int, EXP

GRAIN = Decimal("0.00000001")


def up(x, dp=8):
    return Decimal(x).quantize(Decimal(1).scaleb(-dp), rounding=ROUND_CEILING)


def down(x, dp=8):
    return Decimal(x).quantize(Decimal(1).scaleb(-dp), rounding=ROUND_FLOOR)


def check(txid):
    """Require every stock txncheck verdict; missing values fail closed."""
    reply = d.rpc("txncheck id:" + txid)
    chk = reply.get("response", {})
    v = chk.get("valid", {}) or {}
    valid = (reply.get("status") is True
             and all(v.get(k) is True for k in ("scripts", "basic", "mmrproofs"))
             and all(chk.get(k) is True for k in ("validamounts", "allsignaturesvalid", "validtransaction")))
    return valid, chk


def run(name, steps, expect_ok=True):
    txid = name[:6] + str(time.time_ns() % 100000)
    d.ok("txncreate id:" + txid)
    try:
        for s in steps:
            d.ok(s.replace("$ID", txid))
        ok, v = check(txid)
        status = "OK " if ok == expect_ok else "BAD"
        print(f"{status} {name}: gate={ok} {json.dumps(v)}")
        if ok and expect_ok:
            d.ok("txnpost id:" + txid)
        return ok == expect_ok
    finally:
        try:
            d.rpc("txndelete id:" + txid)
        except Exception:
            pass


def main():
    st = load()
    passed = 0

    # ---- 1. createOrder (DexTxn.createOrder) — a plain `send` with 9 state ports
    oid = "0x" + format(time.time_ns() % 0xFFFFFF, "X") + "A1"
    price = Decimal("0.00575")
    minima = Decimal("100")
    want = up(minima * price)
    state = {"0": st["mypk"], "1": st["myaddr"], "2": str(want), "3": st["tok"],
             "4": oid, "5": "1", "6": str(price), "7": "1", "8": "1"}
    for attempt in range(6):
        try:
            d.send_to(st["addr"], str(down(minima)), state=state)
            break
        except RuntimeError:
            d.advance(3)
    d.advance(4)
    o = None
    for _ in range(5):
        o = find_order(st, oid)
        if o:
            break
        d.advance(2)
    assert o, "createOrder shape produced no book coin"
    print("OK  createOrder: order on book, want", want)
    passed += 1

    # ---- 2. fillSweep with ONE partial (DexTxn.fillSweep, k=1)
    locked = Decimal(o.get("tokenamount") or o["amount"])
    w = Decimal(d.state_of(o)[2])
    take = Decimal("60")
    rem = locked - take
    pay = up(w * take / locked)
    neww = min(w, up(w * rem / locked))
    fund = fund_coin(st["tok"], pay)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    steps = [
        "txninput id:$ID coinid:" + o["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        f"txnoutput id:$ID amount:{pay} address:{st['myaddr']} tokenid:{st['tok']} storestate:false",
        f"txnoutput id:$ID amount:{rem} address:{st['addr']} storestate:true",
        f"txnoutput id:$ID amount:{take} address:{st['myaddr']} storestate:false",
    ]
    if famt - pay > 0:
        steps.append(f"txnoutput id:$ID amount:{famt - pay} address:{st['myaddr']} tokenid:{st['tok']} storestate:false")
    newstate = dict(d.state_of(o))
    newstate[2] = str(neww)
    newstate[6] = str((neww / rem).quantize(Decimal("0.000000000001")))
    for p, v in sorted(d_int(newstate).items()):
        steps.append(f"txnstate id:$ID port:{p} value:{v}")
    steps += ["txnsign id:$ID publickey:auto", "txnbasics id:$ID"]
    passed += 1 if run("fillSweep-partial", steps) else 0
    d.advance(4)

    # ---- 3. relock / atomic edit (DexTxn.relock)
    rc = None
    for _ in range(5):
        rc = find_order(st, oid)
        if rc and rc["coinid"] != o["coinid"]:
            break
        d.advance(2)
    assert rc, "remainder missing"
    rlocked = Decimal(rc.get("tokenamount") or rc["amount"])
    newwant = up(rlocked * Decimal("0.006"))
    rs = dict(d.state_of(rc))
    rs[2] = str(newwant)
    rs[6] = "0.006"
    steps = ["txninput id:$ID coinid:" + rc["coinid"],
             f"txnoutput id:$ID amount:{rlocked} address:{st['addr']} storestate:true"]
    for p, v in sorted(d_int(rs).items()):
        steps.append(f"txnstate id:$ID port:{p} value:{v}")
    steps += ["txnsign id:$ID publickey:" + rs[0], "txnbasics id:$ID"]
    passed += 1 if run("relock-edit", steps) else 0
    d.advance(4)

    # ---- 4. cancel (DexTxn.cancel)
    cc = None
    for _ in range(5):
        cc = find_order(st, oid)
        if cc and cc["coinid"] != rc["coinid"]:
            break
        d.advance(2)
    assert cc, "edited order missing"
    clocked = cc.get("tokenamount") or cc["amount"]
    cs = d.state_of(cc)
    tokarg = "" if cc.get("tokenid", "0x00") == "0x00" else " tokenid:" + cc["tokenid"]
    steps = ["txninput id:$ID coinid:" + cc["coinid"],
             f"txnoutput id:$ID amount:{clocked} address:{cs[1]}{tokarg} storestate:false",
             "txnsign id:$ID publickey:" + cs[0], "txnbasics id:$ID"]
    passed += 1 if run("cancel", steps) else 0
    d.advance(4)
    assert find_order(st, oid) is None, "order still on book after cancel"

    print(f"\nApp shapes: {passed}/4 PASSED (create, sweep-partial, relock-edit, cancel)")


if __name__ == "__main__":
    main()

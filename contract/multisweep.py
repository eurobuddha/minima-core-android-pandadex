"""Multi-order sweep proof: TWO order coins consumed in ONE transaction, index-aligned maker
payments, with the LAST order partially filled and its remainder re-locked at output k.

This is the shape DexTxn.fillSweep builds for k>1 — the covenant runs once per INPUT, and each
run reads @INPUT to find "its" payment output, so index alignment is the whole ballgame.
Layout for k=2 (order A full, order B partial):
    inputs : 0=orderA  1=orderB  2=funding
    outputs: 0=payA    1=payB    2=remainderB(relock,storestate)  3=takerProceeds  4=change
"""
import json, time
from decimal import Decimal, ROUND_CEILING

import dexlib as d
from phaseB import load, find_order, fund_coin, d_int, create_sell


def up(x, dp=8):
    return Decimal(x).quantize(Decimal(1).scaleb(-dp), rounding=ROUND_CEILING)


def main():
    st = load()
    tag = format(time.time_ns() % 0xFFFF, "04X")
    oidA = "0x" + tag + "A1"
    oidB = "0x" + tag + "B2"

    a = create_sell(st, 40, "0.20", oidA)     # price 0.005
    b = create_sell(st, 60, "0.36", oidB)     # price 0.006
    print("PROOF setup: two sell orders on the book (40 @0.005, 60 @0.006)")

    sa, sb = d.state_of(a), d.state_of(b)
    la = Decimal(a.get("tokenamount") or a["amount"])
    lb = Decimal(b.get("tokenamount") or b["amount"])
    wa, wb = Decimal(sa[2]), Decimal(sb[2])

    takeB = Decimal(25)                       # partial: take 25 of B's 60
    remB = lb - takeB
    payA = wa                                 # A is a FULL fill → exact want
    payB = up(wb * takeB / lb)
    newWantB = min(wb, up(wb * remB / lb))
    proceeds = la + takeB                     # MINIMA the taker receives

    need = payA + payB
    fund = fund_coin(st["tok"], need)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    change = famt - need

    txid = "ms" + str(time.time_ns() % 100000)
    d.ok("txncreate id:" + txid)
    steps = [
        # inputs: order coins FIRST, index-aligned with their payments
        f"txninput id:{txid} coinid:{a['coinid']}",
        f"txninput id:{txid} coinid:{b['coinid']}",
        f"txninput id:{txid} coinid:{fund['coinid']}",
        # outputs 0..k-1 = maker payments (VERIFYOUT(@INPUT ...) for each order input)
        f"txnoutput id:{txid} amount:{payA} address:{sa[1]} tokenid:{st['tok']} storestate:false",
        f"txnoutput id:{txid} amount:{payB} address:{sb[1]} tokenid:{st['tok']} storestate:false",
        # output k = the single partial's remainder, re-locked at the covenant
        f"txnoutput id:{txid} amount:{remB} address:{st['addr']} storestate:true",
        # taker proceeds + change
        f"txnoutput id:{txid} amount:{proceeds} address:{st['myaddr']} storestate:false",
    ]
    if change > 0:
        steps.append(f"txnoutput id:{txid} amount:{change} address:{st['myaddr']} tokenid:{st['tok']} storestate:false")
    newstate = dict(sb)
    newstate[2] = str(newWantB)
    newstate[6] = str((newWantB / remB).quantize(Decimal("0.000000000001")))
    for p, v in sorted(d_int(newstate).items()):
        steps.append(f"txnstate id:{txid} port:{p} value:{v}")
    steps += [f"txnsign id:{txid} publickey:auto", f"txnbasics id:{txid}"]

    try:
        for s in steps:
            d.ok(s)
        chk = d.rpc("txncheck id:" + txid).get("response", {})
        v = chk.get("valid", {}) or {}
        amounts = v.get("validamounts", chk.get("validamounts", True))
        gate = bool(v.get("scripts")) and bool(v.get("basic")) and bool(v.get("mmrproofs")) and bool(amounts)
        print("PROOF gate:", gate, json.dumps(v))
        assert gate, "multi-order sweep FAILED the app's txncheck gate"
        d.ok("txnpost id:" + txid)
    finally:
        try:
            d.rpc("txndelete id:" + txid)
        except Exception:
            pass

    d.advance(4)
    for _ in range(5):
        gone = find_order(st, oidA) is None
        rem = find_order(st, oidB)
        if gone and rem is not None and rem["coinid"] != b["coinid"]:
            break
        d.advance(2)
    assert find_order(st, oidA) is None, "order A survived the sweep"
    rem = find_order(st, oidB)
    assert rem is not None, "order B's remainder is missing"
    ramt = Decimal(rem.get("tokenamount") or rem["amount"])
    assert ramt == remB, f"remainder {ramt} != {remB}"
    assert Decimal(d.state_of(rem)[2]) == newWantB
    print(f"PROOF multi-sweep: A fully consumed (paid {payA}), B partially filled "
          f"(took {takeB}, paid {payB}), remainder {remB} @ want {newWantB} re-locked")
    print("\nMulti-order sweep: PASSED")


if __name__ == "__main__":
    main()

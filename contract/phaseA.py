"""Phase A — prove the V5 partial-fill INEQUALITY MATH in the real KISS VM vs an independent
Decimal reference model.

runscript cannot exercise VERIFYOUT/SAMESTATE/GETOUT* (no transaction context), so this phase
runs a SHIM script that is byte-for-byte the partial-fill math of v5script.tpl with the
tx-context reads replaced by injected state ports:

    port 90 = r        (remainder amount, on-chain GETOUTAMT(@INPUT+1))
    port 91 = pay      (maker payment, on-chain GETOUTAMT(@INPUT))
    port 92 = newWant  (remainder's STATE(2))
    prevstate 2 = w (want), 8 = minRemainder;  global @AMOUNT = locked

Accept iff:  r < locked  AND  r >= minrem  AND  newWant*locked >= w*r  AND  newWant <= w
             AND  pay*locked >= w*(locked-r)

Phase B then proves the same predicates through real VERIFYOUT/STATE on posted transactions.
"""
import json
from decimal import Decimal, ROUND_CEILING, getcontext

import dexlib as d

getcontext().prec = 64

SHIM = d.oneline("""
LET w=PREVSTATE(2)
LET r=STATE(90)
RETURN r LT @AMOUNT AND r GTE PREVSTATE(8) AND STATE(92)*@AMOUNT GTE w*r AND STATE(92) LTE w AND STATE(91)*@AMOUNT GTE w*(@AMOUNT-r)
""")

GRAIN = Decimal("0.00000001")   # 8dp MxUSD token grain


def ceil_grain(x):
    return x.quantize(GRAIN, rounding=ROUND_CEILING)


def model(locked, w, r, minrem, pay, neww):
    """Independent Decimal reference: True iff the VM should accept."""
    return (r < locked and r >= minrem
            and neww * locked >= w * r and neww <= w
            and pay * locked >= w * (locked - r))


def vm(locked, w, r, minrem, pay, neww):
    cmd = ("runscript script:" + json.dumps(SHIM)
           + " state:" + json.dumps({"90": str(r), "91": str(pay), "92": str(neww)})
           + " prevstate:" + json.dumps({"2": str(w), "8": str(minrem)})
           + " globals:" + json.dumps({"@AMOUNT": str(locked)}))
    resp = d.ok(cmd)
    assert resp.get("parseok"), "shim did not parse"
    return bool(resp.get("success"))


def honest(locked, w, r):
    """What the app will construct: maker-favored grain-ceiled pro-rata amounts."""
    pay = ceil_grain(w * (locked - r) / locked)
    neww = min(w, ceil_grain(w * r / locked))
    return pay, neww


PASS = 0
FAIL = []


def case(name, locked, w, r, minrem, pay, neww, expect=None):
    global PASS
    locked, w, r, minrem, pay, neww = map(Decimal, map(str, (locked, w, r, minrem, pay, neww)))
    want = model(locked, w, r, minrem, pay, neww) if expect is None else expect
    got = vm(locked, w, r, minrem, pay, neww)
    tag = "OK " if got == want else "BAD"
    if got == want:
        PASS += 1
    else:
        FAIL.append(name)
    print(f"{tag} {name}: vm={got} model={want}  (L={locked} w={w} r={r} min={minrem} pay={pay} nw={neww})")


def main():
    # 1-3: honest partial fills at various ratios (accept)
    for name, L, w, r in [("honest-60pct", 100, "0.575", 60),
                          ("honest-tiny-fill", 100, "0.575", "99.9"),
                          ("honest-big-fill", 100, "0.575", "0.01")]:
        L, w, r = Decimal(str(L)), Decimal(str(w)), Decimal(str(r))
        pay, neww = honest(L, w, r)
        case(name, L, w, r, 0, pay, neww, expect=True)

    # 4: payment shaved by one grain (reject)
    L, w, r = Decimal(100), Decimal("0.575"), Decimal(60)
    pay, neww = honest(L, w, r)
    case("pay-shaved", L, w, r, 0, pay - GRAIN, neww, expect=False)

    # 5: remainder want shaved by one grain (reject)
    case("newwant-shaved", L, w, r, 0, pay, neww - GRAIN, expect=False)

    # 6: no progress r == locked (reject)
    case("no-progress", L, w, L, 0, pay, neww, expect=False)

    # 7-8: dust floor
    case("below-minrem", L, w, 5, 10, pay, w, expect=False)
    p2, n2 = honest(L, w, Decimal(10))
    case("at-minrem", L, w, 10, 10, p2, n2, expect=True)

    # 9: griefed inflated remainder want (reject: newWant > w)
    case("newwant-inflated", L, w, r, 0, pay, w + GRAIN, expect=False)

    # 10: exact rational boundary (no rounding slack)
    L2, w2, r2 = Decimal(3), Decimal("0.33333333"), Decimal(1)
    p3, n3 = honest(L2, w2, r2)
    case("exact-boundary", L2, w2, r2, 0, p3, n3, expect=True)
    case("exact-boundary-shave", L2, w2, r2, 0, p3 - GRAIN, n3, expect=False)

    # 11: large-scale values (overflow behavior: must accept honest, never crash-accept junk)
    L3, w3 = Decimal(10 ** 9), Decimal(10 ** 9)
    r3 = Decimal(1)
    p4, n4 = honest(L3, w3, r3)
    case("big-values", L3, w3, r3, 0, p4, n4, expect=True)
    case("big-values-shave", L3, w3, r3, 0, p4 - 1, n4, expect=False)

    # 12: sub-grain want on a large order
    L4, w4, r4 = Decimal(1000000), Decimal("0.00000001"), Decimal(999999)
    p5, n5 = honest(L4, w4, r4)
    case("tiny-want", L4, w4, r4, 0, p5, n5, expect=True)

    # 13: randomized sweep vs model (both accepts and near-miss rejects)
    import random
    random.seed(42)
    for i in range(120):
        L = Decimal(random.randint(1, 10 ** 6)) / (10 ** random.randint(0, 4))
        w = Decimal(random.randint(1, 10 ** 9)) / (10 ** random.randint(0, 8))
        if L == 0 or w == 0:
            continue
        r = (L * Decimal(random.randint(1, 99)) / 100).quantize(GRAIN)
        if r == 0 or r >= L:
            continue
        pay, neww = honest(L, w, r)
        jitter = random.choice([Decimal(0), GRAIN, -GRAIN])
        which = random.choice(["pay", "neww"])
        p, n = (pay + jitter, neww) if which == "pay" else (pay, neww + jitter)
        case(f"rand{i}", L, w, r, 0, p, n)   # expectation from the model

    print(f"\nPhase A: {PASS} passed, {len(FAIL)} failed")
    if FAIL:
        print("FAILED:", FAIL)
        raise SystemExit(1)


if __name__ == "__main__":
    main()

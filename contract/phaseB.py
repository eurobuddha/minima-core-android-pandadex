"""Phase B — full V5 lifecycle + adversarial vectors as POSTED, MINED transactions on the
private -solo node (the only way to exercise VERIFYOUT / SAMESTATE / GETOUT* / @COINAGE).

Run in CHUNKS (the -test node slows as tracked coins accumulate):
    python3 phaseB.py setup      # confirm funds, create tUSDT token, register V5 (exp=20)
    python3 phaseB.py lifecycle  # create/full-fill/partial/chain/cancel/edit/renew/expiry
    python3 phaseB.py adversary  # every vector must be REJECTED
Each step prints PROOF lines; append them to RESULTS.md.
"""
import json, sys, time
from decimal import Decimal, ROUND_CEILING

import dexlib as d

EXP = 20                      # short expiry for testing (mainnet 1500 — same branch logic)
GRAIN = Decimal("0.00000001")
STORE = "phaseB_state.json"   # persisted setup facts between chunks


def save(**kw):
    try:
        cur = json.load(open(STORE))
    except Exception:
        cur = {}
    cur.update(kw)
    json.dump(cur, open(STORE, "w"), indent=1)


def load():
    return json.load(open(STORE))


def ceilg(x):
    return Decimal(x).quantize(GRAIN, rounding=ROUND_CEILING)


# --------------------------------------------------------------------------- setup

def setup():
    # 1. wait for the genesis coin to confirm, then split some change
    for _ in range(30):
        b = d.bal()
        if Decimal(b["sendable"]) > 0:
            break
        d.advance(1)
    print("PROOF sendable:", d.bal()["sendable"])

    # 2. create the 8dp test token
    r = d.ok('tokencreate name:"tUSDT" amount:1000000 decimals:8')
    d.advance(4)
    tok = None
    for row in d.rpc("balance").get("response", []):
        if row.get("token") == "tUSDT" or (isinstance(row.get("token"), dict) and row["token"].get("name") == "tUSDT"):
            tok = row["tokenid"]
    assert tok, "tUSDT not found in balance"
    print("PROOF tUSDT tokenid:", tok)

    # 3. register the V5 covenant (test dims) — parseok-gated
    script = d.v5_script(tok=tok, exp=EXP)
    clean, addr, mx, pok = d.clean_script(script)
    assert pok, "V5 did not parse"
    d.ok("newscript trackall:true script:" + json.dumps(clean))
    print("PROOF V5 test address:", addr, "chars:", len(clean))

    # 4. identity
    a, amx, pk = d.getaddress()
    save(tok=tok, addr=addr, script=clean, mypk=pk, myaddr=a)
    print("PROOF owner pk:", pk[:20] + "…", "wallet:", a[:20] + "…")


# --------------------------------------------------------------------------- order helpers

def order_state(st, mypk, myaddr, tok, want, orderid, side, price, gtc=1, minrem="1"):
    return {"0": mypk, "1": myaddr, "2": str(want), "3": tok if side == 1 else "0x00",
            "4": orderid, "5": str(side), "6": str(price), "7": str(gtc), "8": str(minrem)}


def create_sell(st, lock_minima, want_usdt, orderid, minrem="1"):
    """SELL: lock MINIMA at the covenant, want tUSDT."""
    stt = order_state(st, st["mypk"], st["myaddr"], st["tok"], want_usdt, orderid, 1,
                      Decimal(str(want_usdt)) / Decimal(str(lock_minima)), minrem=minrem)
    for attempt in range(6):
        try:
            d.send_to(st["addr"], lock_minima, state=stt)
            break
        except RuntimeError:
            if attempt == 5:
                raise
            d.advance(3)   # change still confirming after the previous test burst
    d.advance(4)
    for _ in range(5):     # the -test node's scans lag as tracked coins accumulate
        for c in d.coins(st["addr"]):
            if d.state_of(c).get(4) == orderid:
                return c
        d.advance(2)
    raise AssertionError("order coin not found")


def find_order(st, orderid):
    for c in d.coins(st["addr"]):
        if d.state_of(c).get(4) == orderid:
            return c
    return None


def fund_coin(tokenid, need, exclude=None):
    """Pick one confirmed stateless coin with amount >= need (tokenamount for tokens)."""
    r = d.rpc("coins relevant:true sendable:true tokenid:" + tokenid)
    best = None
    for c in r.get("response", []):
        if exclude and c.get("coinid") == exclude:
            continue
        if c.get("state"):
            continue
        amt = Decimal(c.get("tokenamount") or c.get("amount"))
        if amt >= Decimal(str(need)) and (best is None or amt < best[1]):
            best = (c, amt)
    assert best, f"no funding coin for {need} of {tokenid}"
    return best[0]


# --------------------------------------------------------------------------- lifecycle

def full_fill(st, order):
    """Taker full-fills a SELL order: out0 = want tUSDT to maker wallet, out1 = MINIMA to taker."""
    stt = d.state_of(order)
    want = stt[2]
    locked = order.get("tokenamount") or order["amount"]
    fund = fund_coin(st["tok"], want)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    change = famt - Decimal(want)
    steps = [
        "txninput id:$ID coinid:" + order["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        f"txnoutput id:$ID amount:{want} address:{stt[1]} tokenid:{st['tok']} storestate:false",
        f"txnoutput id:$ID amount:{locked} address:{st['myaddr']} storestate:false",
    ]
    if change > 0:
        steps.append(f"txnoutput id:$ID amount:{change} address:{st['myaddr']} tokenid:{st['tok']} storestate:false")
    steps += ["txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("ff" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    assert find_order(st, stt[4]) is None, "order coin still on book after full fill"
    print("PROOF full-fill: order", stt[4], "consumed; maker paid", want, "tUSDT")


def partial_fill(st, order, take):
    """Taker partial-fills a SELL order: out0 = pro-rata tUSDT to maker, out1 = remainder relock,
    out2 = taken MINIMA to taker, out3 = tUSDT change."""
    stt = d.state_of(order)
    w = Decimal(stt[2])
    locked = Decimal(order.get("tokenamount") or order["amount"])
    take = Decimal(str(take))
    rem = locked - take
    pay = ceilg(w * take / locked)
    neww = min(w, ceilg(w * rem / locked))
    fund = fund_coin(st["tok"], pay)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    change = famt - pay
    newstate = dict(stt)
    newstate[2] = str(neww)
    newstate[6] = str((neww / rem).quantize(Decimal("0.000000000001")))
    steps = [
        "txninput id:$ID coinid:" + order["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        f"txnoutput id:$ID amount:{pay} address:{stt[1]} tokenid:{st['tok']} storestate:false",
        f"txnoutput id:$ID amount:{rem} address:{st['addr']} storestate:true",
        f"txnoutput id:$ID amount:{take} address:{st['myaddr']} storestate:false",
    ]
    if change > 0:
        steps.append(f"txnoutput id:$ID amount:{change} address:{st['myaddr']} tokenid:{st['tok']} storestate:false")
    for port, val in sorted(d_int(newstate).items()):
        steps.append(f"txnstate id:$ID port:{port} value:{val}")
    steps += ["txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("pf" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    nc = find_order(st, stt[4])
    assert nc is not None, "remainder coin missing after partial fill"
    namt = Decimal(nc.get("tokenamount") or nc["amount"])
    nst = d.state_of(nc)
    assert namt == rem, f"remainder amount {namt} != {rem}"
    assert Decimal(nst[2]) == neww, f"remainder want {nst[2]} != {neww}"
    print(f"PROOF partial-fill: took {take}, maker paid {pay}, remainder {rem} @ want {neww} relocked (coin {nc['coinid'][:16]}…)")
    return nc


def d_int(state):
    return {int(k): v for k, v in state.items()}


def owner_relock(st, order, new_want=None, label="renew"):
    """Owner atomic in-place re-lock (edit price if new_want, else same-state renewal)."""
    stt = d.state_of(order)
    locked = order.get("tokenamount") or order["amount"]
    newstate = dict(stt)
    if new_want is not None:
        newstate[2] = str(new_want)
        newstate[6] = str((Decimal(str(new_want)) / Decimal(str(locked))).quantize(Decimal("0.000000000001")))
    steps = ["txninput id:$ID coinid:" + order["coinid"],
             f"txnoutput id:$ID amount:{locked} address:{st['addr']} storestate:true"]
    for port, val in sorted(d_int(newstate).items()):
        steps.append(f"txnstate id:$ID port:{port} value:{val}")
    steps += ["txnsign id:$ID publickey:" + stt[0], "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("rl" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    nc = None
    for _ in range(5):     # scan lag tolerance on the slowing -test node
        nc = find_order(st, stt[4])
        if nc is not None and nc["coinid"] != order["coinid"]:
            break
        d.advance(2)
    assert nc is not None, label + ": order vanished"
    assert nc["coinid"] != order["coinid"], label + ": relock txn not mined (old coin still live)"
    print(f"PROOF {label}: coin {order['coinid'][:12]}… -> {nc['coinid'][:12]}… want {d.state_of(nc)[2]} created {nc.get('created')} (was {order.get('created')})")
    return nc


def cancel(st, order):
    stt = d.state_of(order)
    locked = order.get("tokenamount") or order["amount"]
    tokarg = "" if order.get("tokenid", "0x00") == "0x00" else " tokenid:" + order["tokenid"]
    steps = ["txninput id:$ID coinid:" + order["coinid"],
             f"txnoutput id:$ID amount:{locked} address:{stt[1]}{tokarg} storestate:false",
             "txnsign id:$ID publickey:" + stt[0], "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("cx" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    assert find_order(st, stt[4]) is None, "order still on book after cancel"
    print("PROOF cancel: order", stt[4], "refunded to maker wallet")


def lifecycle_d():
    """Chunk D alone: atomic RENEW (run against a freshly restarted node)."""
    st = load()
    oid4 = "0x" + format(time.time_ns() % 0xFFFFFF, "X") + "04"
    o4 = create_sell(st, 25, "0.15", oid4)
    o4b = owner_relock(st, o4, label="atomic-renew")
    cancel(st, o4b)
    print("\nPhase B chunk D: PASSED")


def lifecycle_e():
    """Chunk E alone: third-party expiry sweep."""
    st = load()
    oid5 = "0x" + format(time.time_ns() % 0xFFFFFF, "X") + "05"
    o5 = create_sell(st, 10, "0.06", oid5)
    st5 = d.state_of(o5)
    # wait on the coin's ACTUAL age (advance() can return early on a slow node — a premature
    # sweep at age<EXP was correctly REJECTED by the covenant, which is its own proof)
    created = int(o5.get("created"))
    while d.block() - created <= EXP + 1:
        d.advance(3)
    locked = o5.get("tokenamount") or o5["amount"]
    steps = ["txninput id:$ID coinid:" + o5["coinid"],
             f"txnoutput id:$ID amount:{locked} address:{st5[1]} storestate:false",
             "txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("ex" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    for _ in range(4):
        if find_order(st, st5[4]) is None:
            break
        d.advance(2)
    assert find_order(st, st5[4]) is None, "expired order not swept"
    print("PROOF expiry: aged order swept to maker wallet WITHOUT owner sig (COINAGE branch)")
    print("\nPhase B chunk E: PASSED")


def lifecycle_f():
    """Chunk F: BUY-side order — the TOKEN coin is the locked leg (scale-36 exercise).
    Lock 0.575 tUSDT at the covenant wanting 100 MINIMA; taker sells 60 MINIMA."""
    st = load()
    oid6 = "0x" + format(time.time_ns() % 0xFFFFFF, "X") + "06"
    ostate = {"0": st["mypk"], "1": st["myaddr"], "2": "100", "3": "0x00",
              "4": oid6, "5": "0", "6": "0.00575", "7": "1", "8": "0.01"}
    for attempt in range(6):
        try:
            d.send_to(st["addr"], "0.575", tokenid=st["tok"], state=ostate)
            break
        except RuntimeError:
            if attempt == 5:
                raise
            d.advance(3)
    d.advance(4)
    o = None
    for _ in range(5):
        o = find_order(st, oid6)
        if o is not None:
            break
        d.advance(2)
    assert o is not None, "buy order coin not found"
    locked = Decimal(o.get("tokenamount") or o["amount"])
    assert locked == Decimal("0.575"), f"locked {locked} != 0.575 (token-scale check)"
    print("PROOF create-buy: locked 0.575 tUSDT wanting 100 MINIMA (tokenamount correct)")

    w = Decimal("100")
    take = Decimal("0.345")            # tUSDT taken by the taker
    rem = locked - take                # 0.23 tUSDT remainder
    pay = ceilg(w * take / locked)     # 60 MINIMA to maker
    neww = ceilg(w * rem / locked)     # 40 MINIMA still wanted
    fund = fund_coin("0x00", pay)
    famt = Decimal(fund.get("tokenamount") or fund["amount"])
    newstate = dict(d.state_of(o))
    newstate[2] = str(neww)
    steps = [
        "txninput id:$ID coinid:" + o["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        f"txnoutput id:$ID amount:{pay} address:{d.state_of(o)[1]} storestate:false",
        f"txnoutput id:$ID amount:{rem} address:{st['addr']} tokenid:{st['tok']} storestate:true",
        f"txnoutput id:$ID amount:{take} address:{st['myaddr']} tokenid:{st['tok']} storestate:false",
    ]
    if famt - pay > 0:
        steps.append(f"txnoutput id:$ID amount:{famt - pay} address:{st['myaddr']} storestate:false")
    for port, val in sorted(d_int(newstate).items()):
        steps.append(f"txnstate id:$ID port:{port} value:{val}")
    steps += ["txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("bf" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    nc = None
    for _ in range(5):
        nc = find_order(st, oid6)
        if nc is not None and nc["coinid"] != o["coinid"]:
            break
        d.advance(2)
    assert nc is not None and nc["coinid"] != o["coinid"], "buy-side partial not mined"
    namt = Decimal(nc.get("tokenamount") or nc["amount"])
    assert namt == rem, f"token remainder {namt} != {rem}"
    assert Decimal(d.state_of(nc)[2]) == neww
    print(f"PROOF buy-side partial: taker sold {pay} MINIMA for {take} tUSDT; remainder {rem} tUSDT wants {neww} MINIMA")
    cancel(st, nc)
    print("\nPhase B chunk F: PASSED")


def lifecycle():
    st = load()
    oid = lambda n: "0x" + format(time.time_ns() % 0xFFFFFF, "X") + format(n, "02X")

    # A. create + FULL fill
    o1 = create_sell(st, 100, "0.575", oid(1))
    print("PROOF create: sell 100 MINIMA want 0.575 tUSDT on book")
    full_fill(st, o1)

    # B. create + PARTIAL fill + chained partial + full-fill the remainder
    o2 = create_sell(st, 100, "0.575", oid(2))
    r1 = partial_fill(st, o2, 60)          # take 60, remainder 40
    r2 = partial_fill(st, r1, 30)          # take 30 of 40, remainder 10
    full_fill(st, r2)                       # sweep the last 10

    # C. atomic EDIT (price change in place) then CANCEL
    o3 = create_sell(st, 50, "0.30", oid(3))
    o3b = owner_relock(st, o3, new_want="0.25", label="atomic-edit")
    cancel(st, o3b)

    # D. atomic RENEW (same state, new young coin)
    o4 = create_sell(st, 25, "0.15", oid(4))
    o4b = owner_relock(st, o4, label="atomic-renew")
    cancel(st, o4b)

    # E. expiry sweep by a THIRD PARTY (no owner sig) after EXP blocks
    o5 = create_sell(st, 10, "0.06", oid(5))
    st5 = d.state_of(o5)
    d.advance(EXP + 2)
    locked = o5.get("tokenamount") or o5["amount"]
    steps = ["txninput id:$ID coinid:" + o5["coinid"],
             f"txnoutput id:$ID amount:{locked} address:{st5[1]} storestate:false",
             "txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
    d.txn("ex" + str(time.time_ns() % 100000), steps)
    d.advance(4)
    assert find_order(st, st5[4]) is None, "expired order not swept"
    print("PROOF expiry: aged order swept to maker wallet WITHOUT owner sig (COINAGE branch)")

    print("\nPhase B lifecycle: ALL PROOFS PASSED")


# --------------------------------------------------------------------------- adversarial

def adversary():
    st = load()
    oid = "0x" + format(time.time_ns() % 0xFFFFFF, "X") + "AD"
    o = create_sell(st, 100, "0.575", oid, minrem="5")
    stt = d.state_of(o)
    w = Decimal(stt[2])
    locked = Decimal(o.get("tokenamount") or o["amount"])

    def base_partial(take, pay=None, neww=None, rem_addr=None, keepstate=True, state_tweak=None):
        take = Decimal(str(take))
        rem = locked - take
        pay = ceilg(w * take / locked) if pay is None else Decimal(str(pay))
        neww = min(w, ceilg(w * rem / locked)) if neww is None else Decimal(str(neww))
        fund = fund_coin(st["tok"], pay)
        famt = Decimal(fund.get("tokenamount") or fund["amount"])
        newstate = dict(stt)
        newstate[2] = str(neww)
        if state_tweak:
            newstate.update(state_tweak)
        steps = [
            "txninput id:$ID coinid:" + o["coinid"],
            "txninput id:$ID coinid:" + fund["coinid"],
            f"txnoutput id:$ID amount:{pay} address:{stt[1]} tokenid:{st['tok']} storestate:false",
            f"txnoutput id:$ID amount:{rem} address:{rem_addr or st['addr']} storestate:{'true' if keepstate else 'false'}",
            f"txnoutput id:$ID amount:{take} address:{st['myaddr']} storestate:false",
        ]
        if famt - pay > 0:
            steps.append(f"txnoutput id:$ID amount:{famt - pay} address:{st['myaddr']} tokenid:{st['tok']} storestate:false")
        for port, val in sorted({int(k): v for k, v in newstate.items()}.items()):
            steps.append(f"txnstate id:$ID port:{port} value:{val}")
        steps += ["txnsign id:$ID publickey:auto", "txnbasics id:$ID", "txnpost id:$ID"]
        return steps

    vectors = [
        ("shaved-payment", base_partial(60, pay=ceilg(w * 60 / locked) - GRAIN)),
        ("shaved-newwant", base_partial(60, neww=ceilg(w * 40 / locked) - GRAIN)),
        ("dust-remainder", base_partial(Decimal("99.9"))),                # rem 0.1 < minrem 5
        ("remainder-hijack", base_partial(60, rem_addr=st["myaddr"])),   # relock to taker addr ⇒ treated as full-fill underpay
        ("keepstate-omitted", base_partial(60, keepstate=False)),
        ("owner-port-flip", base_partial(60, state_tweak={"0": "0xFF"})),
        ("minrem-flip", base_partial(60, state_tweak={"8": "0"})),
    ]
    okc = 0

    def attempt(name, steps):
        """A consensus-rejected txn POSTS fine and never mines — the only trustworthy proof
        of rejection is that the order coin is still unspent afterwards."""
        nonlocal okc
        how = "posted-not-mined"
        try:
            d.txn("av" + str(time.time_ns() % 100000), steps)
        except RuntimeError as e:
            how = "step-failed: " + str(e)[:60].replace("\n", " ")
        d.advance(3)
        live = None
        for _ in range(4):
            live = find_order(st, stt[4])
            if live is not None:
                break
            d.advance(2)
        assert live is not None, name + ": ORDER COIN WAS CONSUMED — VULNERABILITY"
        print("PROOF rejected:", name, "(" + how + ")")
        okc += 1

    for name, steps in vectors:
        attempt(name, steps)

    # third-party steal (no owner sig, not expired): single-input redirect to a fresh address
    locked_s = o.get("tokenamount") or o["amount"]
    thief_addr = d.newaddress()[0]
    attempt("third-party-steal",
            ["txninput id:$ID coinid:" + o["coinid"],
             f"txnoutput id:$ID amount:{locked_s} address:{thief_addr} storestate:false",
             "txnbasics id:$ID", "txnpost id:$ID"])

    # relock-with-worse-want and NO payment output (a "free reprice" grief). Note: on this
    # single-wallet node any wallet signature IS the owner key, so this vector is owner-signed
    # here; the covenant must still reject it because the relock is not at output @INPUT+1
    # with a payment at @INPUT — as a bare relock it hits the owner branch, where the
    # SAMESTATE(3 5)/STATE(2) pins make a want-drop legal ONLY with the owner's signature.
    # The UNSIGNED variant is the security-critical one:
    steps = ["txninput id:$ID coinid:" + o["coinid"],
             f"txnoutput id:$ID amount:{locked_s} address:{st['addr']} storestate:true"]
    bad = dict(stt)
    bad[2] = "0.01"
    for port, val in sorted({int(k): v for k, v in bad.items()}.items()):
        steps.append(f"txnstate id:$ID port:{port} value:{val}")
    steps += ["txnbasics id:$ID", "txnpost id:$ID"]
    attempt("unsigned-relock-reprice", steps)

    cancel(st, find_order(st, stt[4]))
    print(f"\nPhase B adversary: {okc}/9 vectors REJECTED, order coin never moved")


if __name__ == "__main__":
    {"setup": setup, "lifecycle": lifecycle, "lifecycle_d": lifecycle_d, "lifecycle_f": lifecycle_f,
     "lifecycle_e": lifecycle_e, "adversary": adversary}[sys.argv[1]]()

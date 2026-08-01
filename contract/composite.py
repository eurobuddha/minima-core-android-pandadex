#!/usr/bin/env python3
"""Composite PandaDEX + PandaPools private-chain proofs.

This extends the existing V5 order harness with PandaPools reserve coins. It reuses:
  - dexlib.py for the private solo-node RPC plumbing and transaction lifecycle;
  - phaseB.py for V5 order creation, order lookup, state parsing and Decimal rounding;
  - the current PandaPools 0.5% covenant text vendored in PoolCovenant.java.

Run after `python3 contract/phaseB.py setup` against the private solo node on 19105:
    python3 contract/composite.py
"""
import json
import os
import sys
import time
from decimal import Decimal, Context, ROUND_CEILING, ROUND_DOWN, ROUND_UP, getcontext

import dexlib as d
from phaseB import create_sell, d_int, find_order

getcontext().prec = 64

D = Decimal
TGRAIN = D("0.00000001")
MGRAIN = D("0.00000000001")
MININUMBER_MAX = D("18446744073709551615")

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

HERE = os.path.dirname(os.path.abspath(__file__))


def up8(x):
    return D(x).quantize(TGRAIN, rounding=ROUND_CEILING)


def up11(x):
    return D(x).quantize(MGRAIN, rounding=ROUND_UP)


def plain(x):
    return format(D(x).normalize(), "f")


def kmin_for(x0, y0):
    p = D(str(x0)) * D(str(y0))
    if p == 0:
        return "0"
    if p >= MININUMBER_MAX:
        raise ValueError("pool product exceeds MiniNumber range")
    return format(Context(prec=20, rounding=ROUND_DOWN).create_decimal(p), "f")


def pool_script(opk, oadr, tok, kmin):
    return (POOL_TEMPLATE.replace("$OPK", opk)
            .replace("$OADR", oadr)
            .replace("$TOK", tok)
            .replace("$KMIN", str(kmin)))


def retry_send(address, amount, tokenid="0x00", state=None):
    cmd = "send amount:%s address:%s" % (plain(amount), address)
    if tokenid != "0x00":
        cmd += " tokenid:" + tokenid
    if state:
        cmd += " state:" + json.dumps(state)
    cmd += " mine:true"
    for attempt in range(6):
        try:
            return d.ok(cmd)
        except RuntimeError:
            if attempt == 5:
                raise
            mine_blocks(2)


def mine_blocks(n=1):
    _addr, mx, _pk = d.getaddress()
    start = d.block()
    last_err = None
    for _ in range(n):
        try:
            d.rpc("send amount:0.0001 address:" + mx + " mine:true", timeout=180)
        except Exception as e:
            last_err = e
            time.sleep(1)
    for _ in range(60):
        now = d.block()
        if now >= start + n:
            return now
        time.sleep(1)
    if last_err:
        print("WARN mine_blocks: target not reached; last send error: " + str(last_err), flush=True)
    return d.block()


d.advance = mine_blocks


def load_state():
    return json.load(open(os.path.join(HERE, "phaseB_state.json")))


def fresh_coin(amount, tokenid="0x00", extra=D("0")):
    addr, _mx, _pk = d.newaddress()
    total = D(amount) + D(extra)
    retry_send(addr, total, tokenid=tokenid)
    d.advance(4)
    for _ in range(6):
        rows = [c for c in d.coins(addr) if not c.get("spent") and c.get("tokenid", "0x00") == tokenid]
        rows = [c for c in rows if D(c.get("tokenamount") or c.get("amount")) == total]
        if rows:
            return rows[0], total
        d.advance(2)
    raise AssertionError("fresh funding coin not found")


def spent(coinid):
    c = d.coin_by_id(coinid)
    return c is None or bool(c.get("spent"))


def wait_spent(label, *coinids):
    for _ in range(12):
        if all(spent(cid) for cid in coinids):
            return
        d.advance(1)
    raise AssertionError(label + ": expected coin(s) not spent")


def gate(txid):
    chk = d.rpc("txncheck id:" + txid).get("response", {})
    v = chk.get("valid", {}) if isinstance(chk, dict) else {}
    amounts = v.get("validamounts", chk.get("validamounts", True) if isinstance(chk, dict) else False)
    sigs = chk.get("allsignaturesvalid", True) if isinstance(chk, dict) else True
    return bool(v.get("scripts")) and bool(v.get("basic")) and bool(v.get("mmrproofs")) and bool(amounts) and bool(sigs), v


def run_tx(name, steps, should_land):
    txid = name[:8] + str(time.time_ns() % 100000)
    d.rpc("txndelete id:" + txid)
    d.ok("txncreate id:" + txid)
    try:
        for step in steps:
            d.ok(step.replace("$ID", txid))
        ok, valid = gate(txid)
        if should_land:
            assert ok, name + " failed txncheck gate: " + json.dumps(valid)
            d.ok("txnpost id:" + txid + " mine:true")
            time.sleep(2)
        elif ok:
            d.rpc("txnpost id:" + txid + " mine:true")
            time.sleep(2)
        print(("OK  " if ok == should_land else "BAD ") + name + ": gate=" + str(ok), flush=True)
        return ok
    finally:
        d.rpc("txndelete id:" + txid)


def assert_rejected(name, steps, live_coinids):
    watched = set(live_coinids)
    for step in steps:
        if step.startswith("txninput id:$ID coinid:"):
            cid = step.split("coinid:", 1)[1].strip()
            if not spent(cid):
                watched.add(cid)
    try:
        run_tx(name, steps, should_land=False)
    except RuntimeError as e:
        print("OK  " + name + ": step failed " + str(e)[:80], flush=True)
    d.advance(3)
    for cid in watched:
        assert not spent(cid), name + ": watched coin moved"


class Pool:
    def __init__(self, st, x0, y0):
        self.tok = st["tok"]
        self.opk = d.ok("keys action:new")["publickey"]
        self.oadr = d.newaddress()[0]
        self.kmin = kmin_for(x0, y0)
        self.script = pool_script(self.opk, self.oadr, self.tok, self.kmin)
        self.clean, self.address, _mx, parseok = d.clean_script(self.script)
        assert parseok, "pool covenant did not parse"
        d.ok("newscript trackall:false script:" + json.dumps(self.clean))
        retry_send(self.address, x0, "0x00")
        retry_send(self.address, y0, self.tok)
        d.advance(4)
        self.wait_ready()

    def coinpair(self):
        rows = [c for c in d.coins(self.address) if not c.get("spent")]
        minima = [c for c in rows if c.get("tokenid", "0x00") == "0x00"]
        token = [c for c in rows if c.get("tokenid", "").lower() == self.tok.lower()]
        if not minima or not token:
            raise AssertionError("pool reserve pair not visible at " + self.address[:16])
        cm = max(minima, key=lambda c: D(c.get("amount", "0")))
        ct = max(token, key=lambda c: D(c.get("tokenamount") or c.get("amount")))
        return cm, ct

    def wait_ready(self):
        for _ in range(10):
            try:
                self.coinpair()
                return
            except AssertionError:
                d.advance(1)
        self.coinpair()

    def reserves(self):
        cm, ct = self.coinpair()
        return D(cm["amount"]), D(ct.get("tokenamount") or ct["amount"])


class Quote:
    def __init__(self, in_amount, out_amount, new_x, new_y):
        self.in_amount = D(in_amount)
        self.out_amount = D(out_amount)
        self.new_x = D(new_x)
        self.new_y = D(new_y)


def quote_m_to_t(pool, dx):
    x, y = pool.reserves()
    dx = D(dx)
    nx = x + dx
    fx = dx * D(5) / D(1000)
    ny = (max(x * y, D(pool.kmin)) / (nx - fx)).quantize(TGRAIN, rounding=ROUND_UP)
    dy = y - ny
    assert dy > 0 and (nx - fx) * ny >= max(x * y, D(pool.kmin))
    return Quote(dx, dy, nx, ny)


def quote_t_for_m_out(pool, minima_out):
    x, y = pool.reserves()
    minima_out = D(minima_out)
    nx_target = x - minima_out
    need_after_fee = (max(x * y, D(pool.kmin)) / nx_target) - y
    dyin = (need_after_fee / D("0.995")).quantize(TGRAIN, rounding=ROUND_UP)
    ny = y + dyin
    fy = dyin * D(5) / D(1000)
    nx = (max(x * y, D(pool.kmin)) / (ny - fy)).quantize(MGRAIN, rounding=ROUND_UP)
    dm = x - nx
    assert dm >= minima_out and (ny - fy) * nx >= max(x * y, D(pool.kmin))
    return Quote(dyin, dm, nx, ny)


def output(amount, address, tokenid="0x00", storestate=False):
    cmd = "txnoutput id:$ID amount:" + plain(amount) + " address:" + address
    if tokenid != "0x00":
        cmd += " tokenid:" + tokenid
    return cmd + " storestate:" + ("true" if storestate else "false")


def sign_and_basics():
    return ["txnsign id:$ID publickey:auto", "txnbasics id:$ID"]


def state_steps(state):
    return ["txnstate id:$ID port:%d value:%s" % (p, v) for p, v in sorted(d_int(state).items())]


def create_buy(st, lock_token, want_minima, orderid, minrem="0.01"):
    price = D(lock_token) / D(want_minima)
    state = {"0": st["mypk"], "1": st["myaddr"], "2": plain(want_minima), "3": "0x00",
             "4": orderid, "5": "0", "6": plain(price), "7": "1", "8": str(minrem)}
    retry_send(st["addr"], lock_token, tokenid=st["tok"], state=state)
    d.advance(4)
    for _ in range(6):
        coin = find_order(st, orderid)
        if coin:
            return coin
        d.advance(2)
    raise AssertionError("buy order coin not found")


def mixed_buy_steps(st, tag, mutation=None):
    pool = Pool(st, D("10"), D("0.10"))
    orderid = "0xCB" + tag
    order = create_sell(st, D("0.60"), "0.0030", orderid, minrem="0.01")
    ost = d.state_of(order)
    locked = D(order.get("tokenamount") or order["amount"])
    take_order = D("0.30")
    rem = locked - take_order
    pay = up8(D(ost[2]) * take_order / locked)
    newwant = min(D(ost[2]), up8(D(ost[2]) * rem / locked))
    q = quote_t_for_m_out(pool, D("0.20"))
    need = pay + q.in_amount
    fund, fund_total = fresh_coin(need, tokenid=st["tok"], extra=D("0.01"))
    cm, ct = pool.coinpair()
    newstate = dict(ost)
    newstate[2] = plain(newwant)
    newstate[6] = plain(newwant / rem)
    proceeds = q.out_amount + take_order
    change = fund_total - need

    inputs = [cm["coinid"], ct["coinid"], order["coinid"], fund["coinid"]]
    outs = [
        output(q.new_x, pool.address),
        output(q.new_y, pool.address, st["tok"]),
        output(pay, ost[1], st["tok"]),
        output(rem, st["addr"], order.get("tokenid", "0x00"), True),
        output(proceeds, st["myaddr"]),
        output(change, st["myaddr"], st["tok"]),
    ]
    states = state_steps(newstate)

    if mutation == "wrong-parity":
        inputs[0], inputs[1] = inputs[1], inputs[0]
    elif mutation == "shift-order-output":
        outs[2], outs[3] = outs[3], outs[2]
    elif mutation == "underpay-order":
        outs[2] = output(pay - TGRAIN, ost[1], st["tok"])
        outs[5] = output(change + TGRAIN, st["myaddr"], st["tok"])
    elif mutation == "invalid-remainder":
        bad = dict(newstate)
        bad[2] = plain(newwant - TGRAIN)
        bad[6] = plain((newwant - TGRAIN) / rem)
        states = state_steps(bad)

    steps = ["txninput id:$ID coinid:" + cid for cid in inputs] + outs + states + sign_and_basics()
    return pool, order, steps


def mixed_sell_steps(st, tag):
    pool = Pool(st, D("10"), D("0.10"))
    orderid = "0xC5" + tag
    order = create_buy(st, "0.0050", D("1"), orderid, minrem="0.0001")
    ost = d.state_of(order)
    locked = D(order.get("tokenamount") or order["amount"])
    take_token = D("0.0020")
    rem = locked - take_token
    pay = up8(D(ost[2]) * take_token / locked)
    newwant = min(D(ost[2]), up8(D(ost[2]) * rem / locked))
    q = quote_m_to_t(pool, D("0.20"))
    need = pay + q.in_amount
    fund, fund_total = fresh_coin(need, extra=D("1"))
    cm, ct = pool.coinpair()
    newstate = dict(ost)
    newstate[2] = plain(newwant)
    newstate[6] = plain(rem / newwant)
    proceeds = q.out_amount + take_token
    change = fund_total - need
    steps = [
        "txninput id:$ID coinid:" + cm["coinid"],
        "txninput id:$ID coinid:" + ct["coinid"],
        "txninput id:$ID coinid:" + order["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        output(q.new_x, pool.address),
        output(q.new_y, pool.address, st["tok"]),
        output(pay, ost[1]),
        output(rem, st["addr"], st["tok"], True),
        output(proceeds, st["myaddr"], st["tok"]),
        output(change, st["myaddr"]),
    ] + state_steps(newstate) + sign_and_basics()
    return pool, order, steps


def pool_only_buy(st, tag):
    pool = Pool(st, D("10"), D("0.10"))
    q = quote_t_for_m_out(pool, D("0.10"))
    fund, fund_total = fresh_coin(q.in_amount, tokenid=st["tok"], extra=D("0.01"))
    cm, ct = pool.coinpair()
    steps = [
        "txninput id:$ID coinid:" + cm["coinid"],
        "txninput id:$ID coinid:" + ct["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        output(q.new_x, pool.address),
        output(q.new_y, pool.address, st["tok"]),
        output(q.out_amount, st["myaddr"]),
        output(fund_total - q.in_amount, st["myaddr"], st["tok"]),
    ] + sign_and_basics()
    run_tx("pool-buy-" + tag, steps, should_land=True)
    wait_spent("pool-only buy", cm["coinid"], ct["coinid"])
    x2, y2 = pool.reserves()
    assert x2 == q.new_x and y2 == q.new_y, "pool-only recreated wrong reserves"
    print("PROOF pool-only buy: paid", q.in_amount, "tUSDT; received", q.out_amount, "MINIMA", flush=True)


def prove_mixed_buy(st, tag):
    pool, order, steps = mixed_buy_steps(st, tag)
    old_cm, old_ct = pool.coinpair()
    run_tx("mixed-buy-" + tag, steps, should_land=True)
    wait_spent("mixed buy", old_cm["coinid"], old_ct["coinid"])
    rem = find_order(st, d.state_of(order)[4])
    assert rem is not None and rem["coinid"] != order["coinid"], "sell-order remainder missing"
    print("PROOF mixed buy: pool pair first, sell order partial last, reserves and order remainder recreated", flush=True)


def prove_mixed_sell(st, tag):
    pool, order, steps = mixed_sell_steps(st, tag)
    old_cm, old_ct = pool.coinpair()
    run_tx("mixed-sell-" + tag, steps, should_land=True)
    wait_spent("mixed sell", old_cm["coinid"], old_ct["coinid"])
    rem = find_order(st, d.state_of(order)[4])
    assert rem is not None and rem["coinid"] != order["coinid"], "buy-order remainder missing"
    print("PROOF mixed sell: pool pair first, buy order partial last, reserves and order remainder recreated", flush=True)


def prove_rejections(st):
    base = format(time.time_ns() % 0xFFFFFF, "06X")
    for i, mutation in enumerate(["wrong-parity", "shift-order-output", "underpay-order", "invalid-remainder"], 1):
        pool, order, steps = mixed_buy_steps(st, base + "AD%02d" % i, mutation=mutation)
        cm, ct = pool.coinpair()
        assert_rejected(mutation, steps, [cm["coinid"], ct["coinid"], order["coinid"]])

    pool, order, steps = mixed_buy_steps(st, base + "AD05")
    cm, ct = pool.coinpair()
    q = quote_m_to_t(pool, D("0.01"))
    fund, _total = fresh_coin(q.in_amount)
    pool_spend = [
        "txninput id:$ID coinid:" + cm["coinid"],
        "txninput id:$ID coinid:" + ct["coinid"],
        "txninput id:$ID coinid:" + fund["coinid"],
        output(q.new_x, pool.address),
        output(q.new_y, pool.address, st["tok"]),
        output(q.out_amount, st["myaddr"], st["tok"]),
    ] + sign_and_basics()
    run_tx("move-pool", pool_spend, should_land=True)
    wait_spent("move-pool", cm["coinid"], ct["coinid"])
    assert_rejected("stale-pool-race", steps, [order["coinid"]])

    pool, order, steps = mixed_buy_steps(st, base + "AD06")
    cm, ct = pool.coinpair()
    ost = d.state_of(order)
    cancel_steps = [
        "txninput id:$ID coinid:" + order["coinid"],
        output(order.get("tokenamount") or order["amount"], ost[1], order.get("tokenid", "0x00")),
        "txnsign id:$ID publickey:" + ost[0],
        "txnbasics id:$ID",
    ]
    run_tx("move-order", cancel_steps, should_land=True)
    wait_spent("move-order", order["coinid"])
    assert_rejected("stale-order-race", steps, [cm["coinid"], ct["coinid"]])
    print("PROOF adversarial composites: bad parity/output/payment/state and stale races moved no fresh watched coins", flush=True)


def main():
    st = load_state()
    if len(sys.argv) > 1 and sys.argv[1] == "status":
        print("block", d.block(), flush=True)
        print("MINIMA", json.dumps(d.bal("0x00")), flush=True)
        print("TOKEN", json.dumps(d.bal(st["tok"])), flush=True)
        return
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    base = format(time.time_ns() % 0xFFFFFF, "06X")
    print("Composite proof using token", st["tok"], flush=True)
    if mode in ("all", "valid"):
        pool_only_buy(st, "01")
        prove_mixed_buy(st, base + "01")
        prove_mixed_sell(st, base + "02")
    if mode in ("all", "adversary"):
        prove_rejections(st)
    print("\nComposite liquidity private-chain proofs: PASSED", flush=True)


if __name__ == "__main__":
    main()

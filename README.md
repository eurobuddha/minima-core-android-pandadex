# PandaDEX

A fully-decentralized, MEXC-style limit-order exchange for **MINIMA ⇄ mxUSDT**, running as a
native Android app against your own Minima node.

There is no server. No matching engine, no order-book API, no price feed, no relay. The order
book *is* a set of coins locked at one on-chain covenant address; your node reads it directly
and your device builds the transactions that fill it. Two people running this app on two
phones can trade with each other with nothing in between but the chain.

## What it does

- **Real limit orders with partial fills.** A resting order can be eaten a piece at a time —
  the taker's transaction pays the maker pro-rata and re-locks the remainder at the same
  price, atomically. Everything is enforced by the covenant, not by the app.
- **Marketable limits / market orders.** An order that crosses the book sweeps the resting
  liquidity first (best price first, up to 5 orders in a single transaction) and rests only
  the unfilled balance.
- **Good-till-cancelled orders that actually survive.** Orders are renewed by an atomic
  in-place re-lock — one transaction, funds never leave the book — driven by a Doze-proof
  background watcher so a GTC order doesn't quietly expire overnight.
- **Atomic reprice.** Editing an order's price is one transaction; the order never leaves the
  book and there is no window where your funds are sitting loose in your wallet.
- **An honest chart.** Candles, the trades tape, 24h stats and your P&L are all built from
  fills *your node observed on-chain*. Nothing is fetched from an exchange.
- **Fills are proven, not assumed.** When an order coin vanishes, the app reads the transaction
  that spent it and takes the verdict from that transaction's outputs — order-linked, and still
  readable long after the proceeds have been spent onward. Where history cannot answer, payout
  evidence is adjudicated across the whole scan at once so a single refund coin cannot prove a
  payment for every other order of the same size. Anything still unproven is dropped: a lost
  trade is invisible, a phantom one is not.

## Screens

`TRADE` — ticker, depth ladder with cumulative-depth bars and price grouping, buy/sell panel
with percent chips, your open orders with edit/cancel.
`CHART` — OHLC candles + volume, 15m/1H/4H/1D, touch crosshair.
`TRADES` — the market tape, your own fills badged.
`ORDERS` — open orders and your fill history with P&L vs the book mid.
`ASSETS` — balances split into available vs locked in orders, portfolio value, receive address.

## The contract

The book lives at one address, derived from a frozen KISS-VM covenant:

```
0x2D43279DD85DABCA3EA90C9997DAB9169D8B7A0E8CB594236AF44542489774A5
MxG081D8CJPRM2TYF53TA8CJ6BTYE8MJM5NK3KCMMA26QNK8Y14H5RKKK2B0665
```

Spend paths: owner cancel (refund), owner atomic re-lock (renew/reprice), third-party expiry
sweep after 600 blocks, full fill, and partial fill with a pro-rata remainder. The 600-block
lifetime is deliberate: a light node cannot see a coin older than ~1024 blocks, so an order
must be able to expire *and still be visible* long enough for anyone to sweep it home. Prices are
enforced by cross-multiplication (no division, no rounding slack), always rounded in the
maker's favour, with a maker-set minimum remainder to stop dust griefing.

`contract/` holds the covenant template and the proof harness. See `contract/RESULTS.md`:
Phase A proved the fill arithmetic against an independent decimal model (134/134 vectors);
Phase B posted and mined the whole lifecycle on a private chain — full fills, chained
partials, buy-side token-leg partials, atomic edit and renew, expiry sweeps, and a
multi-order sweep — plus 9 adversarial attacks that were all rejected with the order coin
left untouched.

The PandaPools composite-liquidity proof harness lives in `contract/composite.py`; the remaining
real-funds dust interop checklist is `contract/COMPOSITE_LIVE_INTEROP.md`.

## Requirements

- Minima Core (the standard, upstream node app) installed and running on the same device.
- Enable PandaDEX in **Minima Core → Apps**.

Every node query the app makes is bounded, because an oversized IPC reply kills an Android app
outright rather than returning an error.

## Building

```
./gradlew assembleDebug      # JDK 21 (pinned in gradle.properties)
./gradlew test               # JVM unit tests
```

## Status

The V5 order book has settled real mainnet trades between two devices. The composite
PandaDEX + PandaPools path is implemented and privately proven, including pool-only fills,
mixed fills in both directions, layout invariants, and stale/race rejection cases. It remains
behind the final real-funds composite dust gate until PandaDEX executes pool-only and mixed
trades against a PandaPools-created mainnet pool and PandaPools confirms the recreated
reserves.

The app has been through adversarial fund-safety reviews that found and fixed critical issues,
including an expiry beyond the node's visibility horizon, `trackall:true` ownership pollution,
and phantom-fill recording on partial scans. `contract/RESULTS.md` documents the proofs,
mainnet sessions, and remaining composite-live gate.

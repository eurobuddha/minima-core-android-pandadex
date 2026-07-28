# PandaDEX V5 partial-fill covenant — proof results (2026-07-27)

## FROZEN MAINNET CONTRACT

- Template: `v5script.tpl` with `$TOK` = mxUSDT
  `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`, `$EXP` = 1500.
- **Mainnet script: 1128 chars, parseok true.**
- **Mainnet address: `0xCE5A0A3CC2E19B1860E60C58397FD5D5E986EEA4AF4423B53E08BAA5591B6F32`**
  (`MxG086EB853PGN1JCC61PGCB0SNVYEYT63ET95F8GHRAFG8NAWYW6RF69FVMZ6M`)
- State ports: 0 ownerPk · 1 wantAddr · 2 wantAmt · 3 wantTok · 4 orderId · 5 side(0 buy/1 sell)
  · 6 price(DISPLAY ONLY — never trust; derive from 2 and coin amount) · 7 GTC · 8 minRemainder.
- Test dims (private solo node, ports 19101/19105): tUSDT 8dp scale-36 token, EXP=20; same
  branch logic, only the token literal + expiry constant differ from mainnet.

## Phase A — VM math vs independent Decimal model: **134/134**

`phaseA.py` runscript shim of the partial-fill inequalities. Honest pro-rata (grain-ceiled,
maker-favored) accepted at every ratio incl. 1e9-scale and sub-grain wants; one-grain payment
shave, one-grain newWant shave, inflated newWant (> w), no-progress (rem==locked), below
min-remainder ALL rejected; 120-vector randomized sweep exactly matches the model.

## Phase B — posted + mined transactions on the solo chain

Lifecycle (all PROOF lines reproduced by `phaseB.py setup|lifecycle|lifecycle_d|lifecycle_e|lifecycle_f`):

- create SELL (100 MINIMA want 0.575 tUSDT) → **full fill** (maker paid 0.575, coin consumed)
- **partial fill**: take 60 → maker paid 0.345, remainder 40 relocked at want 0.23, SAME
  address, state preserved except pro-rata port 2
- **chained partial**: take 30 of the 40-remainder → remainder 10 @ want 0.0575
- **full fill of the final remainder** (0.0575 paid)
- **atomic in-place EDIT** (owner-signed re-lock, want 0.30 → 0.25, funds never left the book)
- **atomic RENEW** (owner-signed same-state re-lock; `created` 211 → 215 — **coinage resets**,
  the property GTC renewal rides on)
- cancel (owner refund) — incl. a TOKEN-coin cancel (buy-side remainder 0.23 tUSDT)
- **third-party expiry sweep** (no owner sig) after age > EXP
- **BUY-side order** (chunk F): 0.575 tUSDT locked as a scale-36 TOKEN coin, wanting 100
  MINIMA; partial fill (taker sold 60 MINIMA for 0.345 tUSDT; remainder 0.23 tUSDT wanting 40
  MINIMA relocked). Token `tokenamount` semantics correct end-to-end.

Negative proofs observed along the way (bugs in the HARNESS, correctly rejected by the covenant):
- a premature expiry sweep at age 18 < EXP 20 was rejected (COINAGE guard binds)
- a cancel that omitted `tokenid:` (refund would have switched tUSDT→MINIMA) was rejected
  (token whitelist + VERIFYOUT @TOKENID binds)

## Phase B adversary — **9/9 REJECTED, order coin never moved**

shaved-payment · shaved-newwant · dust-remainder (below port-8 floor) · remainder-hijack
(relock to taker's address ⇒ read as full-fill underpay) · keepstate-omitted ·
owner-port-flip (port 0 swap) · minrem-flip (port 8 zeroed) · third-party-steal (unsigned
redirect) · unsigned-relock-reprice. Rejection mode: txns post fine and are never mined —
the proof standard is "coin still unspent afterwards" (consensus rejection is silent;
harness asserts on coin liveness, not step errors).

## Script-design lessons (found by on-chain probes, fixed in the frozen script)

1. **Owner-sig hijack**: a taker whose wallet holds the maker key (self-fill — supported)
   entered the owner branch and got rejected. Fix: owner shapes are NON-ABORTING `IF …
   THEN RETURN TRUE ENDIF` checks that FALL THROUGH to the fill branch.
2. **Stateless-txn abort**: `SAMESTATE`/`STATE()` reads evaluated in an AND-chain abort the
   whole script when the txn carries no state (full fills don't). Fix: nest them under the
   `GETOUTADDR(@INPUT) EQ @ADDRESS` relock gate — only relock txns (which always carry
   state) reach them.
3. **GETOUTAMT returns TOKEN-scaled amounts** (probe: `GETOUTAMT(0) EQ 0.345` true on a
   scale-36 tUSDT output) — the covenant's cross-multiplication mixes MINIMA and token
   units safely.
4. KISS `AND` chains mixing comparisons parse fine unparenthesized (probe-verified).

## Known properties / residuals (documented, not blockers)

- **One partial per txn** (one txn = one state list): a sweep full-fills every crossed order
  and partials at most the LAST order input; remainder sits at output index `@INPUT+1`.
- A taker paying a maker MORE than pro-rata is legal (inequalities are maker-favored).
- Port 6 (price) is display-only and taker-writable on remainders — ALWAYS derive price from
  port 2 / amount (app rule; Limit lesson).
- Fill-vs-renew race = plain UTXO double-spend: the node mines exactly one spend of the coin;
  both outcomes are safe (fill pays the maker; renew re-locks). No covenant involvement.
- Overflow: products ≤ 1e9×1e9 = 1e18 < 2^64; the creation path must enforce amount caps
  (app-side guard, PandaPools precedent).

## Multi-order sweep (the k>1 shape DexTxn.fillSweep builds) — PROVEN 2026-07-28

`multisweep.py`: TWO order coins consumed in ONE transaction —
inputs [orderA, orderB, funding], outputs [payA, payB, remainderB(relock), proceeds, change].
Order A fully consumed (paid 0.2 exactly), order B partially filled (took 25 of 60, paid
0.15), remainder 35 re-locked at want 0.21. App gate (valid.scripts+basic+mmrproofs+
validamounts) TRUE, mined. Confirms the covenant's per-input `VERIFYOUT(@INPUT …)` alignment
holds for every order input simultaneously, and that the single partial must be the LAST
order input with its remainder at output k.

## App-shape verification — PROVEN 2026-07-28

`appshapes.py` replays DexTxn.java's exact command sequences and gates on the same verdict
the app uses: createOrder, fillSweep(partial), relock(edit), cancel — 4/4 accepted and mined.

## IPC reply sizes — measured, all bounded (2026-07-28)

The upstream node's 256,000-byte reply cap is an UNCATCHABLE Binder app-kill, so every query
the app issues was measured against a live node:

| command | bytes | % of cap |
|---|---|---|
| `block` | 218 | 0.09% |
| book scan `coins simplestate:true order:desc depth:1700 address:<V5>` | 2,811 | 1.10% |
| ownership belt `coins relevant:true address:<V5>` | 3,606 | 1.41% |
| `balance tokenid:0x00` / `tokenid:<mxUSDT>` | 289 / 351 | 0.14% |
| `keys` | 1,424 | 0.56% |
| `getaddress` | 444 | 0.17% |
| funding `coins relevant:true sendable:true tokenid:` | 6,610 | 2.58% |
| `runscript` (covenant registration, one-time) | 18,078 | 7.06% |

Worst case 7.06%. The app issues NO unbounded `coins address:`, no all-token `balance`, no
`history`, and no `scripts` enumeration. The book scan is bounded by `depth:1700` and is
complete by construction: GTC renewal keeps every live order coin younger than the
1500-block expiry.

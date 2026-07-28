# PandaDEX V5 partial-fill covenant — proof results

(2026-07-27 original; **2026-07-28 re-frozen after the adversarial review** — see the
CORRECTION at the end. The EXPIRY-1500 contract at `0xCE5A0A3C…` is ABANDONED.)

## FROZEN MAINNET CONTRACT

- Template: `v5script.tpl` with `$TOK` = mxUSDT
  `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`, **`$EXP` = 600**.
- **Mainnet script: 1127 chars, parseok true.**
- **Mainnet address: `0x2D43279DD85DABCA3EA90C9997DAB9169D8B7A0E8CB594236AF44542489774A5`**
  (`MxG081D8CJPRM2TYF53TA8CJ6BTYE8MJM5NK3KCMMA26QNK8Y14H5RKKK2B0665`)
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


---

# CORRECTION (2026-07-28) — the 1500-block expiry was unsafe

An adversarial fund-safety review found that **this document's central claim was false on
mainnet**, and that the proof harness had hidden it.

## What was wrong

The book scan used `depth:1700` and this file asserted the scan was "COMPLETE by
construction" because 1700 > EXPIRY 1500. But `coins ... depth:` walks **tree nodes**, and
the chain tree is trimmed at `MINIMA_CASCADE_START_DEPTH = 1024` (`GlobalParams.java:48`).
The real visibility horizon is therefore **~1024 blocks (~14 h)**, not 1700.

With EXPIRY at 1500, an order could age past 1024 while still "live". Once there it was:
invisible to every `coins` query, so **un-cancellable and un-renewable**; and because the
expiry-sweep branch also needs the coin to be visible, **un-sweepable by anyone**. Recovering
it would need an archive/MegaMMR node and a hand-built transaction. Separately, the fill tape
saw the coin vanish while `expired()` was still false and recorded a **phantom fill** —
inventing a trade, poisoning the candles, and firing a false "order filled" alert.

## Why the proofs did not catch it

The solo node runs `TestParams`, which trims at **32** nodes, and the harness used
**EXP = 20**. The inequality `EXPIRY < HORIZON` held by accident in the test environment and
failed only on mainnet parameters. **Lesson: a proof harness that changes the constants it is
proving must re-check the invariants those constants participate in.** `OrderValidationTest`
now asserts `EXPIRY_BLOCKS < HORIZON_BLOCKS` so this cannot silently regress.

## What changed

| | before | after |
|---|---|---|
| EXPIRY_BLOCKS | 1500 | **600** (~8.3 h) |
| RENEW_AT | 500 | **200** (~2.8 h) |
| SCAN_DEPTH | 1700 | **1000** (under the 1024 trim) |
| HORIZON_BLOCKS | — | **1024** (documented ceiling) |
| address | `0xCE5A0A3C…` | **`0x2D43279D…`** |

A lapsed order now has a ~424-block (~6 h) window in which it is expired *and* still visible,
so anyone can sweep it home. The old book was never funded on mainnet, so nothing is stranded.

## Re-proof after the fix (all re-run 2026-07-28)

- Phase A: **134/134**.
- Phase B adversary: **9/9 rejected**, order coin never moved.
- Multi-order sweep (k=2, trailing partial): **PASSED**.
- App shapes (create / sweep-partial / relock-edit / cancel): **4/4**, and confirmed the book
  is still fully discoverable after switching registration to `trackall:false` (the C2 fix).
- 38 JVM tests green, including new regression cover for hostile resting orders.

## Second critical: `trackall:true` broke ownership

Registering the covenant with `trackall:true` puts the address in
`Wallet.mAllTrackedAddress`, and `TxPoWTreeNode.checkRelevant` returns true for **any** coin
at a tracked address — so `coins relevant:true` returned the whole book and every stranger's
order read as the user's own. That starved GTC renewal (the two renewal slots per pass were
spent on foreign orders that fail signing) and fed straight into the C1 loss path, besides
poisoning P&L, notifications and portfolio value. Registration is now `trackall:false`, and
`Order5.isMine()` requires a **key match** — the node's relevance flag can no longer stand
alone.


---

# FIRST REAL MAINNET TRADE (2026-07-28)

The covenant has now been exercised with **real funds between two independent devices**:

- Maker (Galaxy Z Fold, v0.1.0): posted a bid and a **300 MINIMA** sell offer.
- Taker (Galaxy S10, v0.1.0): **partially filled it — bought 150 MINIMA for 0.151 mxUSDT**.
- The trade settled and the funds transferred correctly on both sides.

This is live confirmation on mainnet of the partial-fill path end to end: the sweep
construction, the index-aligned maker payment, the pro-rata cross-multiplied pricing and the
remainder re-lock at the same address. Everything the solo-node proofs asserted, with money.

## Defects found in that session (all UI/correctness, no funds affected)

1. **Ladder merged price levels.** Verified: at the shipped default tick (0.0001) prices of
   0.0515 and 0.0520 do NOT merge — they only merge at 0.001 or coarser, so the levels were
   either grouped by an accidentally-tapped tick chip (they were 6×2dp — barely hittable) or
   the book was visually garbled by defect 3 below. What IS confirmed is the display: the old
   formatter stripped trailing zeros, so **0.0520 rendered as "0.052"** — exactly the "not
   enough decimal places" complaint. Fixed: default is now **exact levels (no grouping)**,
   grouping is opt-in and persisted with proper tap targets, and every price renders at a
   **fixed 5 decimals**.
2. **Taker saw the maker's remainder as its own order**, on the opposite side to its trade.
   Root cause confirmed as the `trackall:true` ownership bug (C2), already fixed in v0.1.1:
   the remainder coin carries the MAKER's pubkey at port 0 but was created *after* the taker
   registered the script, so the node flagged it relevant and `isMine()` accepted that flag
   alone. Ownership now requires a key match. Still to confirm by re-running the same
   two-device flow on v0.1.2.
3. **All five tabs painted on top of each other at launch** — the tab views are added to one
   container but visibility was only ever set on a tab tap, which never happens at startup.
   This is what put the chart caption and assets text through the order-entry panel.
4. **A placed order's optimistic row could never resolve** (the row carried an empty order id
   the matcher could never match), so a perfectly good order sat on "PLACING…" and then
   falsely warned "NOT CONFIRMED — check funds".


# SECOND MAINNET SESSION (2026-07-28, v0.1.2)

Another **successful partial fill on mainnet** between the two phones — the maker's orders were
visible on the taker's device and the fill settled with funds moving correctly. The covenant
and the sweep construction continue to hold up in the real world.

Defects exposed, all in the layer ABOVE the trade (no funds affected):

1. **Orders were being recorded as trades (CRITICAL, data).** With only resting orders and no
   trade yet, the ticker reported 24h high 0.052 / low 0.0485 / volume 900 MINIMA — the price
   range and total size of the user's own order book. Root cause: `FillTape` treats "in the
   previous book, absent from this one" as a full fill, and a scan returning an EMPTY or
   PARTIAL array is not `truncated` — it parses fine and looks exactly like "everything
   filled". Two such scans (~4s apart) minted a fill for every resting order at its own limit
   price and full size. **The app's own unit test had encoded this as expected behaviour.**
   Fixed with a sanity gate (never diff an emptied book or a mass-vanish; re-seed instead),
   plus: no diffing without a chain height (the age guards are blind at block 0), re-seed
   after a stale gap (the background service's previous book can be half an hour old), the
   book cache no longer accepts an empty scan as last-good, unparseable coins now mark the
   scan incomplete, and GTC renew/reprice now record their own spend so the replacement
   landing a scan later can't read as a trade. The polluted tape is wiped once on upgrade.
2. **The maker had no signal an order was live.** The optimistic row sat on "Confirming 1/3"
   while the same order was listed below it as live and cancellable — shown twice, one copy
   lying. Replaced with an honest two-state lifecycle (Sending… → LIVE on the book, then the
   row retires) plus a system notification when the order hits the book. An order is live and
   fillable the moment its id appears; counting further blocks reported doubt that did not
   exist.
3. **The taker had no signal at all.** Tapping to fill produced silence through coin
   selection, signing, proof-of-work and up to a block of waiting. Added a running stage line
   (Building transaction… → Posted, waiting for a block → ✓ Bought 150 MINIMA @ 0.05150),
   FILLING markers on the ladder rows being taken, and a completion notification — the taker's
   own fill produces no book-diff signature of its own, so it had to be detected explicitly.


# THIRD MAINNET SESSION (2026-07-28, v0.1.3) — two more successful trades

Two more trades settled correctly, including a taker hitting a bid. Defects found:

1. **The maker's own orders appeared on the REMOTE phone before the local one** — and could be
   traded there before the maker's app knew they existed. Root cause: `MainActivity.poll()`
   began with `if (inputFocused) return;`, so while any text field had focus the app made NO
   node calls at all — no block height, no balances, no book scan. Placing an order leaves the
   amount field focused, so the maker's phone stopped reading the chain at exactly the moment
   it most needed to, while the taker's phone (not typing) polled normally. That also froze
   the pending row on "Sending…" forever, since the row resolves inside the scan callback.
   The guard was inherited from an app whose refresh rebuilt the whole form; this screen builds
   its inputs once, so there was nothing to protect. Removed.
2. **"Sending…" was open-ended** with no sense of how long to wait. Now shows an elapsed clock
   and what it is waiting for ("waiting for the next block (~50s) · 12s"), and says plainly
   when it is overdue rather than spinning. A 1s UI tick keeps it moving.
3. **Five decimals was too coarse for this pair.** MINIMA trades near 0.05 mxUSDT, so 5dp could
   not separate genuinely different orders or show what a tap would actually trade at. Prices
   now display at **6 decimals**, and tapping a ladder row prefills the EXACT best price behind
   that level rather than the rounded label.
4. **Trade proceeds looked missing.** Funds are on-chain the moment a trade mines but are not
   spendable until confirmed, and nothing showed that gap — so "sold" was followed by an
   apparently unchanged balance. ASSETS now has a **Confirming** column alongside Available and
   In orders, and the completion message points at it.


# FOURTH SESSION (2026-07-28, v0.1.4) — market data disagreed between devices

The user reported the Z Fold showing no 24h high/low/volume while the S10 showed correct
figures, and the two phones showing DIFFERENT centre prices (0.05000 vs 0.05075) for an
IDENTICAL order book.

**I first blamed the v0.1.3 database purge and the user corrected me — the Fold had not
skipped 0.1.3, so no purge ran.** That correction was right and led to the real causes:

1. **The guard I added in 0.1.3 was destroying real trades.** It discarded any book-diff where
   the book went empty, to stop a bad scan minting phantom fills. But in a two-person market
   the LAST resting order filling empties the book — a genuine trade, silently dropped. The
   principle was wrong: a suspicious scan means *demand more evidence*, never *destroy the
   evidence*. Now an emptied book or mass-vanish simply requires more consecutive
   confirmations (4 instead of 2) before it is recorded. Both directions are pinned by tests.
2. **The maker never observed its fills anyway** — until 0.1.4 the app skipped its whole chain
   poll while a text field had focus, and the maker is the phone doing the typing.
3. **The centre price was two quantities in one unlabelled slot**: last-traded when the local
   tape had a fill, book mid when it didn't — and `shownLast` was assigned once and NEVER
   cleared, so a stale trade displayed as current indefinitely. Replaced with the user's rule:
   **MID by default, the LAST trade for 10 minutes after one (each trade restarting the
   window), then back to MID — and both states labelled.** The ladder centre and the headline
   now come from one source, so they can no longer disagree with each other, with Assets or
   with P&L. The ladder also stopped computing its own mid from GROUPED level keys, which
   differed from `bookMid()` by up to half a tick whenever grouping was on.
4. **Devices could never converge** because the tape is built only from locally witnessed book
   diffs. Added `TradeBackfill`: on pairing it reconstructs every trade THIS wallet took part
   in from the node's own transaction history — identified by what the money actually did (a
   transaction touching the book address that moves two assets in opposite directions), keyed
   on the spent order coinid so a live-recorded trade is never double-counted. Trades between
   other people that this node never saw remain unrecoverable; there is no server to ask.
5. **No further destructive migrations.** The v3 purge cost the user real history; corrupt rows
   are now prevented at the source and missing ones recovered from the chain.


# v0.1.6 (2026-07-28) — simplified price display, backfill removed

- **Each price slot now has ONE fixed meaning.** The big headline price is ALWAYS the last
  trade, with how long ago it happened. The number between the bid and the ask is ALWAYS the
  mid, and carries no label because it cannot be anything else. v0.1.5 switched the headline
  between last-trade and mid depending on recency, so the same slot meant different things at
  different moments — over-engineered, and it needed a label to explain itself.
- **`TradeBackfill` removed.** It was added in v0.1.5 to reconstruct history from the chain and
  never worked for the maker: it identified a trade by two opposing wallet legs, which is the
  taker's signature. Verified against the node source and live data — with the covenant
  registered `trackall:false`, a maker's `difference` for a fill contains only the incoming
  payment (one leg), because `difference` keys purely off coin ADDRESS and never inspects
  state variables. Recovering both sides would mean parsing each consumed coin's state ports;
  history recovery isn't needed, so the feature was deleted rather than expanded.
- **Build version moved into the header.** A phone silently stayed on 0.1.4 through a round of
  testing and looked like an app bug (two builds compared side by side). The version is now
  always visible next to the pairing pill.
- Retained from v0.1.5 and re-verified: a genuine fill that empties the book IS recorded, and
  no migration destroys stored history.

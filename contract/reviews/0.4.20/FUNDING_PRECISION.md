# PandaDEX 0.4.20 — stock precision in the funding path

Every trade attempt failed with "Could not read the available balance. Nothing was posted by this
request." on a wallet holding more than `SAFE_COINS` (8) coins of the traded token.

Cause: the same defect 0.4.16 fixed for the Assets display, in the path 0.4.16 did not touch.
`FundingCoins.nextKey` parsed the per-address `balance … address:` row's `sendable` with
`Util.decOr`, the order/transaction parser capped at 44 significant digits. Stock MinimaCore
returns balances with up to 64 significant digits and 44 decimal places — the S10 Plus
(SM-G975F) on MinimaCore 1.1.2.3 returns 48. The parse returned null and the selector aborted
before any coin was listed, so no order could ever be funded from that wallet.

Its neighbour had the same defect: `FundingCoins.coinValue` parsed each coin's `amount` /
`tokenamount` with `decOr` too. A coin amount above 44 significant digits was rejected as
"Invalid wallet coin amount." — and via `fundingCoin` it also made `PoolBook` silently skip
such a coin when reading pool reserves.

Fix: both node-reported amount reads now use `Util.balanceDecimal`, the stock-precision parser
already used by the display path. The limit that must not move did not move — amounts the app
*builds* (order locks, splits, relocks) keep the 44-digit `decOr` / `DexTxn.amountOk` bound,
asserted in the new test.

Validation:
- New `FundingCoinsTest.stockPrecisionBalancesAndCoinsFundATrade` reproduces the exact popup
  before the fix (48-significant-digit `sendable`, 47-significant-digit coin) and passes after.
- 705 JVM tests pass (704 before, +1).

Not verified on device yet: this is a JVM-proven fix; the two-phone live check is outstanding.

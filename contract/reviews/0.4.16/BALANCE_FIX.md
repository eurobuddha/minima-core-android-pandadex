# PandaDEX 0.4.16 — stock MINIMA balance precision

The S10 Plus (SM-G975F) running PandaDEX 0.4.14 and stock MinimaCore 1.1.2.3 showed NODE connected and fresh MxUSD, but no MINIMA balance. The read-only node terminal returned valid balances with 48 significant digits and 44 decimal places. MainActivity reused the stricter maker/order parser, which rejects more than 44 significant digits. The whole MINIMA observation was consequently discarded, keeping the combined available-to-trade value unknown.

Reuse: existing Util.decOr bounded BigDecimal parsing, PandaPools TokenBalance exact decimal handling, and core MiniNumber limits (64 significant digits / 44 decimal places). A balance-only entry point accepts the stock precision without rounding. Existing transaction/order parsing retains its 44-digit restriction. Failed, malformed, wrong-token, negative and ambiguous replies remain unknown and preserve the last good observation. Connected balance-read failures now offer retry instead of directing the user to reconnect; initial connection discovery has its own header state.

Validation:
- Captured S10 balance regression failed before the fix (1 of 10 tests).
- All 703 JVM tests passed after the fix, including exact amounts and locked difference, bounded hostile exponents, and unchanged transaction parsing limits.
- Release lint: zero errors, 76 existing warnings.
- Signed 0.4.16 / 416 built once; installed with adb install -r on S10, retaining data. Installed Assets displayed the exact MINIMA and MxUSD balances. No funded transactions executed.
- Self-review: balance parsing change is confined to the three balance amount fields; signing, funding, covenant and order limits are unchanged.

APK SHA-256: 312b12b37664d10702ac38bb587b3f7abec4d3d92cbaa230a2012673bc976dc5

Follow-up discovered during cold-start validation: receive-address and empty-order messages still suggest connecting while identity loading is in progress. Those display strings will be corrected in a separately versioned build; this artifact will not be overwritten.

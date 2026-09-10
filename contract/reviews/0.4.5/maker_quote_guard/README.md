# Queued maker quote safety — 2026-09-10

**438 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/76 warnings. No APK was built, installed, overwritten or published for this change; source remains0.4.5/405. No user node, phone, emulator or external price endpoint was contacted. The most recent Android execution evidence remains audit15 at its archived source checkpoint; these later maker changes are JVM-tested and source-reviewed, not stock-device/live-chain validated.

## Findings and fixes

1. MakerEngine originally checked feed suitability while planning a batch, then executed later asynchronous actions without another price/settings check. An action could wait behind transaction work while the feed expired, its midpoint changed, the spread needed widening, or the user disarmed/edited the maker. `MakerQuoteGuard` now retains the planned configuration and checks current configuration/reference before starting each action, during the existing preparation callback and at the existing final `beforePost()` boundary. The calculation reuses `MakerLadder.desired()` and `worthRepricing()`: small moves inside the user's existing threshold are permitted, changes at/beyond it stop old quotes, and widened spread requirements are checked even if the midpoint stays constant. Rejected remaining actions are not silently repriced or substituted; the next book cycle replans or withdraws. Already accepted actions remain recorded.

2. MarketPrice's two-read jump guard retained the previous mid, but still reported that old price as fresh/usable while a large move was unconfirmed. A separate quote-quarantine state now makes pegged quoting unavailable until successful evidence resolves the suspect move. Invalid/failed readings reset consecutive corroboration without clearing the quarantine. Two agreeing large-move readings or a successful return to the previous range can resolve it. This is repeated evidence from the same source, not independent corroboration or proof of fair value.

Cancellation remains independent of the feed. Manual fixed-price work is not gated on an external price, but changing its settings or disarming it still invalidates a queued action. The receipt wrapper forwards a caller's pre-post refusal and preserves the receipt as NOT_SUBMITTED; it does not manufacture an unknown broadcast for an operation stopped before posting.

## Reuse and inspected paths

- `app/src/main/java/com/eurobuddha/pandadex/MakerEngine.java`: existing sequential execution, exactly-once callbacks, slot/prepared intent bookkeeping and withdrawal paths.
- `MakerLadder.java`: existing desired ladder arithmetic and configured repricing threshold.
- `MarketPrice.java`: retained depth validation, last-good mid, two-reading jump rule and widening policy; added coherent quote reads and quarantine.
- `DexTxn.java`, `CmdChain.java`, `Pending.java`: inspected native signing/posting and receipt callback propagation. Existing `Result.onPrepared()` / `beforePost()` are reused; transaction construction and signing code are unchanged.
- Sibling `../pandapools/app/src/main/java/com/eurobuddha/pandapools/MarketPrice.java`: display-only donor does not guard asynchronous funds operations.
- Sibling `../minimaswap/app/src/main/java/com/eurobuddha/minimaswap/swap/PriceOracle.java`: applyPeg's publication-time suitability gate is the existing concept reused at PandaDEX's native asynchronous boundary. Its different persistence, broader stale window and weaker top-of-book fallback were not imported.

## Executed regression scope

Eleven added JVM cases cover a held create becoming stale, a changed midpoint, a permitted small move, stale spread widening without midpoint movement, disarming, size edits, and keeping the accepted first action while stopping the remaining batch. Additional cases cover manual orders with no feed, a suspect large move blocking the old queued quote, quarantine surviving invalid reads and clearing on successful evidence, and refusal propagating through the real Pending creation wrapper. Existing cancellation/stale-withdraw and maker bookkeeping tests also pass. Fake transaction callbacks provide deterministic delay; there are no real funds or node commands in these tests. Relevant raw test XML accompanies this file; the complete source/test/resource manifest is in `../validation.json`.

## Remaining risks

MEXC remains a single external trust source, and the configured depth minimum does not prove resistance to market manipulation. The two-read guard is not two independent oracles. Freshness and cycle pacing still use wall-clock time; monotonic-clock and slow-response/deadline review remain necessary. An exchange response can be delayed without its receipt time proving exchange freshness. A guard immediately before requesting `txnpost` cannot guarantee the price remains unchanged during node processing, propagation or inclusion, nor remove an already live order instantly. Transactions may already have been signed before the final guard refuses posting; no one-time signing key or signature is reused. Stock S23/ZFold/MinimaCore and the human-only composite interoperability gate remain open. No production approval or complete-security claim is made.

# Maker ownership cache and observed-book status — 2026-09-10

## Findings and changes

MakerTab cached its owned-order lookup solely by the BookRepository map object's identity. KeySet updates its keys/addresses independently; a successful wallet load, reseed or changed payout ownership could therefore leave the Maker panel using an empty or wrong-wallet lookup until the next book replacement. The same view passed cached data to MakerStatus without its current wallet/book readiness, allowing a stale row to be labelled live with an OK checkmark.

MakerStatus.OwnedBook extracts and retains the existing MakerTab cache/filter. It compares both ownership sets as well as book identity and saves copies of those sets, because KeySet accessors expose live unmodifiable views. Both owner public key and payout address still pass through the existing Order5.isMine predicate. Stable book/ownership snapshots reuse the lookup; changed keys, addresses or book identity rebuild it. The result is read-only to callers.

MainActivity now exposes an observation-readiness predicate requiring pairing, live KeySet readiness, a believable current repository snapshot and a positive block. MakerTab passes that explicitly to MakerStatus.lines; every caller/test now supplies the premise. If checks are pending, recorded rungs retain last-seen price/size with WAIT tone. An unchecked empty snapshot does not claim current absence, mining or imminent posting. Withdrawal instructions remain visible and their cached presence is qualified as last-seen. For a checked snapshot, the former live price/size text now says seen in latest book snapshot. This is an observation, not transaction inclusion/confirmation proof.

The armed header also indicates pending wallet/book checks with the existing accent colour instead of its ready green. Unreadable maker records use the existing error colour, consistently with their paused/error label. No transaction selection, signing, maker reconciliation or receipt-verification algorithm changes.

## Reuse and inspection

- Current MakerTab.java: status tick, updateStatus, complete myOrdersById cache, desired-rung formatting and render/header callers.
- Current MakerStatus.java and complete MakerStatusTest.java: existing per-rung wording, funded-position comparisons, pending reprices, absence and withdrawal summaries. The cache is extracted into this existing pure formatter for testing, not replaced by a new UI framework.
- MainActivity.java: KeySet accessors, pairing/readiness and BookRepository listeners/visible-tab render wiring. Order5.isMine remains the two-factor predicate.
- BookRepository.java: cache identity replacement, believable/current state and notification/error handling.
- `/Users/eurobuddha/Projects/minima/apks/pandapools/app/src/main/java/com/eurobuddha/pandapools/PoolRepository.java`: read the complete donor cache/listener implementation. Its cache is useful for immediate rendering but provides no wallet-ownership invalidation or stronger verification state; PandaDEX's existing cache/readiness implementation is the compatible building block.
- Graph query `MakerTab myOrdersById MakerStatus ready` located these relationships. The graph is pre-#1504; current source was authoritative.

## Verification

Six new regression methods cover stale snapshot price/size retention without OK tone, unchecked empty snapshots, qualified retained withdrawal instructions, same-book key arrival/removal, payout-address ownership removal/restoration despite a copied owner key, and unchanged-cache reuse versus new-book replacement. The existing10 formatter tests now state explicitly that their snapshots are checked and use the observational wording. **539 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. git diff --check passes; source/test hashes are refreshed.

These tests run the production cache and formatter, not Android view rendering or real node refreshes. Header wiring is compilation/lint-checked; Samsung text wrapping/visual behaviour has not been executed. No APK, emulator, phone or node was used. Source remains0.4.5/405, frozen404 is unchanged, and nothing was committed/pushed/published. Audit17 remains an earlier Android checkpoint; the latest changes are not covered by that APK.

## Limits and remaining work

BookRepository.current reflects its last believable scan; this change does not add an elapsed-time expiry to that flag or prove that an observed coin remains unspent now. Wording therefore describes the latest snapshot. Complete Android UI/lifecycle/MinimaCore testing, long pass overlap, durable terminal owner-operation history and the broader production gates remain open. General maker restart/valid-snapshot concurrency policies remain unchanged. No production-readiness or100%-security claim is made.

# Maker funded positions and reconciliation — 2026-09-10

**473 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. Eleven new regression tests are included. No production or audit APK was built, no emulator or phone accessed, and no node commands issued for this pass. Source remains 0.4.5/405; the frozen0.4.4 APK hash is unchanged. Earlier Android audits cover their recorded source checkpoints, not these latest maker changes.

## Findings and fixes

**Buy repricing falsely detected as a partial fill.** MakerEngine compared current requested MINIMA with the original requested quantity. An owner reprice can change that quantity while leaving every funded USDT unit locked. The engine then treated the unchanged funded position as partially filled and suppressed later adjustments. MakerStatus made the same unsupported inference.

New maker SlotRec and pre-sign prepared intent now retain original locked amount and token. The funding amount comes from the exact calculation extracted from DexTxn.createOrder; the builder itself uses the same helper. Original requested MINIMA remains separately stored for deliberate size changes. MakerPosition compares locked assets, following the existing FillTape balance comparison. An unchanged funded buy remains eligible for subsequent repricing even after its wanted MINIMA quantity changes. Lower, higher, token-mismatched or unknown funding is preserved from automatic reconciliation. This balance comparison is not proof of a particular spending transaction or trade. Status says less funding remains instead of claiming a verified partial fill.

**Flat-midpoint gate suppressed necessary work.** The complete-ladder midpoint shortcut returned before MakerLadder.reconcile could request a due GTC renewal or wider quotes for an aging feed. It has been removed. The existing per-order price threshold, action budget, per-side create limit, elapsed cycle limit and queued quote checks remain in force. The per-order reconciler now decides whether anything is due, including when the reference midpoint is unchanged.

**Preview protection differed from execution.** The edit preview now uses the same funded-position protection for observed orders. A zero-action preview no longer claims the live ladder already matches the settings; it directs the user to rung statuses. This does not make preview a chain guarantee: current feed, in-flight work and asynchronously changing book/settings can still affect execution.

## Proven-code sources inspected and reused

- `app/src/main/java/com/eurobuddha/pandadex/FillTape.java`: existing locked-asset comparison, plus its warning that lookalike successor IDs are not lineage proof.
- `DexTxn.java`: exact create funding quantization and relock preserving locked funds; extracted the existing calculation without changing transaction layout or covenant.
- `MakerLadder.java`: existing protected-position handling, per-order threshold, renewal and budgets. The planner remains the implementation of reconciliation.
- `MakerConfig.java`, `MakerEngine.java`, `MakerStatus.java`, `MainActivity.java`: existing serializer, create callbacks, preview caller and status rendering.
- `MakerWithdrawalDurabilityTest.Memory`: actual MakerConfig save/load exercised with controlled visible and durable preferences, reused for the new funding persistence test.

The graph query led to existing maker configuration/reconciliation paths but predates these edits. Sibling minimaSwap/PandaPools searches did not identify an original-funding slot model to reuse. The smallest adaptation is two optional SlotRec fields and a shared balance-baseline helper; existing preferences remain readable without a database migration.

## Regression coverage

Five engine cases exercise repeated buy repricing without fresh funding, preserving genuinely reduced buy funding, protecting legacy buys with unknown funding, GTC renewal at a flat midpoint and stale-feed widening at a flat midpoint. Three baseline/persistence cases exercise exact token-grain rounding through prepared and accepted records and real serializer reload, legacy sell funding, and mismatched tokens/increased funding. A preview case checks known, legacy-unknown and reduced positions. Two status cases distinguish unchanged repriced buys from legacy unknown funding. Existing partial-position status assertions now describe the balance rather than infer a trade.

The first run had one test-only failure: BigDecimal.equals compared decimal scale (`100` versus `100.00000000`). It was corrected to compare numeric value; production comparison already used compareTo. Subsequent full suites passed. Saved XML and source hashes identify the final473-test checkpoint. Transaction dispatch is stubbed; these tests are not real-node relock/renewal evidence.

## Remaining limitations

Legacy buy records do not contain original USDT funding. Their original funding is not guessed from the current price, and their current position requires review before automatic adjustment. Legacy sells can reuse the original MINIMA funding rounded to the builder's grain. This pass does not reconstruct missing historical maker intent.

Existing policy preserves reduced positions from all automatic reconciliation, including renewal. That policy is not changed here. Missing orders still need verified terminal matching before replenishment. Corrupt MakerConfig loading, cross-instance persistence, durable failed-settings pauses across process death, cached-book/ownership freshness and background pairing remain open review items. Stock Samsung/MinimaCore integration and the human-only composite gate remain unexecuted. No production-ready or100%-security claim is made.

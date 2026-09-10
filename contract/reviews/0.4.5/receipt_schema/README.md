# Receipt field types and operation schema

Nine of ten adversarial tests failed against the prior parser. The retained before.xml includes an actual production DexProcessor fixture dispatching a second renewal after the saved unknown-outcome receipt's kind was changed from EDIT to EDlT. The parser considered that row healthy; unresolvedOwnerCoins ignored its unknown kind. The fixture's assertion reports expected1/actual2 renewal calls. This is a reproduced automatic-dispatch defect, not evidence of two live transactions or duplicate on-chain spend.

## Fix and reuse

Pending.Row.from now requires the original writer's base fields, validates PLACE/CANCEL/EDIT kinds and known submission phases, requires native booleans, checks text types without coercion, validates order/source hex identities, rejects negative or malformed amounts and requires exact nonnegative long timestamps/heights. Explicit malformed creation containers no longer become an absent legacy creation record. New/update rows pass the same reader before any store write, so callers cannot poison an otherwise readable store.

The implementation directly reuses MakerConfig.jsonString/storedDecimal/storedBlock, made package-visible, and Util.decOr's existing bounded decimal reader. Receipt flag validation uses the same native Boolean test already used by TakerReceipt.Intent and MakerConfig's preference readers. The existing complete-input boundary, opaque receipt IDs, shared lock, stable migration, original-byte preservation and commit acknowledgement remain intact.

Missing newer fields retain the old defaults, but explicit wrong types do not. The original HEAD Pending writer was inspected and always wrote kind/orderId/coinid/buy/minima/price/submitMs/submitBlock, so requiring those fields preserves its valid format. Empty coinid remains valid for PLACE; owner changes require their source. Zero display amounts/prices remain supported, including the existing valid buy-relock test whose price rounds to zero; exact transaction economics still belong to the proof verifier. No status is inferred from time.

Inspected current Pending writer/reader/intent/reconciliation and its MainActivity listeners, MakerConfig's complete stored-field helpers, TakerReceipt, CreationEvidence and existing cancellation/relock/processor recovery fixtures. Sibling PandaPools ActivityLog's list/add/save still uses permissive JSON coercion, a display retention cap and catch-ignore, so it cannot supply stricter financial receipt parsing. Its synchronized read-before-write donor pattern is retained. The graph query `Pending Row from phase receipt identity cancellation edit recovery` was followed by source inspection; its pre-#1504 IDs remain stale and the budgeted traversal was truncated, so it is not complete current-source evidence.

## Verification

Ten new JVM tests cover missing original fields, unknown kind with actual processor retry, wrong native text/boolean types, unknown phase, malformed/negative/bounded amounts, fractional/overflow/negative timestamps, invalid creation containers, invalid new-row save, and original legacy/zero-display compatibility. **579 full JVM tests pass**, no failures/errors/skips; release lint zero errors/75 warnings. Before/after XML is archived.

[Audit20](../android_receipt_schema/README.md) passes545 Android assertions, including345 new schema assertions with real SharedPreferences and production DexProcessor. All current production Java hashes match at execution. No live node, phone, IPC or signing was used.

## Remaining limits

This validates individual field types/known discriminators, not complete consistency between kind, phase and modern intent fields. A modern cancellation whose source text becomes empty can still encounter cancelSourceMatches's legacy empty-source branch; modern/legacy distinction and exact field combinations need their own adversarial tests and fix. Valid but semantically altered data, duplicate JSON object keys, complete nested creation schema, growth limits and raw recovery/export UX remain open. Confirmed owner receipts are still removed after proof and lack durable terminal operation history; changing only the parser does not fix that. The production release and stock Samsung/MinimaCore/human-only composite gates remain open.

Source0.4.5/405 remains uncommitted and unpublished. No production405 APK was built, audit20 has a fresh numbered artifact, and frozen404 is unchanged.

# Archived proof mutation and permissive recovery input

Seven new regressions failed against the prior production source; all seven now pass within612 JVM tests, zero failures/errors/skips. Lint:zero errors/75 warnings. Baseline and passing XML/logs retained.

TakerReceipt kept a caller-owned mutable Spend after capturing immutable JSON. Later callback mutation could make DexDb adopt inclusion coordinates inconsistent with that JSON. OwnerReceipt already copied proof data: its complete copy helper is now shared unchanged through DexHistory.copyProof and used by both receipt types. It snapshots inputs, outputs, state, inclusion coordinates/time, proof ordering/time and input count.

TakerRecovery.Entry and TakerReceipt.sameIntent accepted a valid JSON prefix with ignored suffixes and truncated fractional version/block values. They now reuse MakerConfig.storedObject and storedBlock, plus the existing512KiB archive bound. OwnerReceipt.sameCompletion also uses exact version/block reads. Invalid stored input is retained and cannot dispatch the tested recovery lookup or authorize repair. Numeric formatting of exact integer values and valid trailing whitespace remain compatible.

Reuse inspected: PandaDEX OwnerReceipt/TakerReceipt/TakerRecovery/DexHistory/DexDb/ReceiptRepair/MakerConfig, their tests and FillSettler caller; PandaPools ActivityLog and ReceiptRecovery. The latter implement confirmation lookup/header-ID reconstruction, not archived trade correction. No new dependency or database schema.

[Audit25](../android_archive_integrity/README.md) passes749 actual Android assertions including SQLite adoption after caller mutation. Limits: Spend remains an internally mutable type; this closes aliasing from original callback objects, not arbitrary later mutation of receipt.spend by trusted package code. Nested archive proof schema/duplicate JSON keys and owner reorg correction remain open. No data was rewritten to conceal an invalid receipt.

Owner re-inclusion investigation also confirmed that a changed inclusion after archive commit but before pending cleanup can retain an unresolved owner receipt: sameCompletion correctly rejects changed coordinates, and no owner revision path exists yet. The remedy needs preserved original evidence plus verified replacement proof, following the existing taker/source correction protocol. This remains active work, not a completed reorg fix.

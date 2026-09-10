# Durable terminal owner-operation history

## Problem and implementation

Pending previously removed verified creation/cancellation/edit receipts before a transient listener notification. Closing the Activity or losing the notification left no durable owner-operation record. The existing trade ledger does not represent every creation/refund/renewal, and treating those as trades would falsify volume and P&L.

OwnerReceipt now captures the original Pending row and its exact linked transaction evidence: relevant input coins/indices, total input count, outputs/state, TxPoW and transaction IDs, included block coordinates, node-returned depth, proof order/time, optional verified block time and separate first-recorded time. Capture reuses CreationEvidence.match, Pending's guarded source/edit checks, DexHistory.verdictFor and relockSuccessor. Outcomes distinguish creation, cancellation, edit, renewal, competing refund and payment before the request. No caller-supplied success label can bypass those checks. The capture has TakerReceipt's512KiB local record bound and copies proof objects so later history-callback mutation cannot change what the database adopts.

Pending.complete reloads the current row under its existing shared lock and compares immutable intent fields before acting. Phase/posted-ID/delay-notification changes may legitimately advance during the archive/cleanup gap; economic intent may not. The archive and repeat-check entry commit first, then the existing pending JSON update must acknowledge cleanup, then success is announced. Missing/failed archive storage or failed pending cleanup retains evidence. A retry accepts the same completion without replacing its original archive or recorded time; a conflicting receipt/proof is rejected. Listener failure after completion leaves the archive intact. The host receives an accurate recovery error without claiming an erased pending row still exists.

DexDb uses additive schema12 with ownerreceipt and a chronological index. Its transaction reuses recordTakerFill's protectLatestProof/registerCheck/adoptIncludedProof ordering and original-proof conflict handling. Archive insert and recheck registration roll back together. There is no new signing path or deletion migration. MainActivity uses DexDb.pendingReceipts so the real reconciliation path has the configured archive; an unconfigured Pending store fails closed on completion.

Orders gains an Operations segment with saved outcome, node-returned confirmation count, last valid check time, original inclusion height and selectable TxPoW ID. Missing or moved inclusion stays visible with explicit reconciliation wording. A missing block timestamp is labelled First observed, never substituted as block time. Owner operations do not enter mytrade/tape, volume or P&L. Reads select small indexed columns rather than parsing proof JSON on every render.

## Inspected and reused

Read current Pending row/save/intent/reconciliation/listener paths; MainActivity completion callers; DexDb schema/migrations, recordTakerFill, proof guards, chaincheck registration/review and owner storage; complete TakerReceipt capture; CreationEvidence, Order5, DexTxn.safeOrder and DexHistory inclusion/owner-verdict helpers; OrdersTab existing segmented/card layout; and the export snapshot paths. PandaPools ActivityLog's retained-status/recheck pattern and MDS pending.js were inspected in the preceding passes. Their caps/permissive parsing/book-presence behavior cannot replace the current APK's durable exact-proof path. The smallest compatible donor is the existing taker archive-before-pending-clear protocol, adapted to owner outcomes and storage.

The graph query `DexDb trades Pending remove TradeView OrdersTab` remains pre-#1504 and budget-truncated; current source supplied the implementation evidence. The graph has not been regenerated for OwnerReceipt.

Local stock MinimaCore source `/Users/eurobuddha/Projects/minima/core/minima-core/src/org/minima/system/commands/search/txpow.java` supplies block and blockid with a successful on-chain response. Owner archival requires these coordinates. HistoryProgressTest.Node was enriched with that actual shape; its head-lookup assertion now expects history, inclusion and inclusion-block lookup. A block lookup without a verified timestamp leaves blockTimeMs0 rather than inventing a time. No user node was queried.

## Verification

**600 full JVM tests pass**, zero failures/errors/skips; release lint zero errors/75 warnings. Eleven new OwnerReceipt tests cover all creation funding inputs/original intent, incomplete coordinates/wrong outputs, oversized proof, archive-before-notification, archive failure, cleanup failure/replay preserving first evidence, stale intent change, missing archive configuration, listener failure, truthful depth/missing/moved status, and captured-proof immutability. Existing owner proof/recovery tests use an in-memory archive that implements the same acknowledgement/conflict semantics. No pre-fix execution is claimed for this new API; the original loss was directly verified in Pending.remove and its transient callers.

[Audit21](../android_owner_history/README.md) passes570 actual Android assertions, including fresh/11-to12 SQLite migration, rollback, actual recheck-state rendering data and an archive-committed/pending-uncleared process-death gap. All80 production Java hashes match at execution. Full raw results and artifact identity are retained.

## Remaining work / code review

- MAJOR: the new Operations view is compiled but not visually exercised on Android yet. It currently shows the latest100 records; older records remain stored but pagination and owner-receipt export are not implemented. Existing reconciliation ZIPs do not yet include ownerreceipt. These are immediate follow-up requirements, not a completed history UX.
- MAJOR: missing/moved inclusion is accurately flagged by the existing repeat-check queue, but automatic owner-proof correction/archive for reorgs and recovery of historical receipts deleted by older builds remain unimplemented. Saved original evidence must not be overwritten as a shortcut.
- MAJOR: owner pending reconciliation still runs in the foreground path; full Activity/service lifecycle, stock S23/ZFold MinimaCore IPC/Doze, disk-full crash behavior and human-only composite tests remain open.
- MAJOR: full removal of modern metadata remains indistinguishable from legacy without a stronger persisted format policy. Complete nested schema, duplicate JSON keys, storage growth and raw recovery UX remain open.

Verdict: request changes before production approval. Durable owner storage and scoped crash/replay checks are now implemented and verified; export/paging/visual UX and the other production gates remain active work.

Source0.4.5/405 remains uncommitted/unpublished. No production405 APK was built. Audit21 is separately numbered in a fresh output path; audits1–20 and frozen404 remain unchanged. The disposable emulator/ADB5049 were stopped and userdata/keys deleted.

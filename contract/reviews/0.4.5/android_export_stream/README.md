# Streaming export and WAL audit — build 13

Executed on 2026-09-10 with a fresh disposable Android16/API36 arm64 emulator, isolated ADB server5049/keys/userdata and separate audit package/version13. No user phone, existing AVD, wallet or Minima node was accessed. The audit manifest has no INTERNET permission or production Activity/service. All earlier audit APKs and the frozen production0.4.4 APK remain unchanged. Source is0.4.5/405; no production0.4.5 APK was built.

## Results

**427 JVM tests pass**, no failures/errors/skips. **234 Android assertions pass:** 22 new export/database/document assertions plus the full 212-assertion database migration, correction, rollback and process-crash recovery suite rerun with WAL/FULL enabled. Exact production Java, runner and APK hashes and raw results are retained here.

A separate JVM run with a verified **64MiB maximum heap** processed **30,000 trades and60MiB of correction payloads**, streamed all six ZIP entries and completed without accumulating receipt rows/archive strings in the report. The memory result XML, init script and exact source hashes are in `memory/`. This is a representative constrained-heap test, not a stock-Samsung peak-memory or latency benchmark.

Small fixture exports match the existing CSV, summary, reconciliation, verification and receipt contents, including formula escaping, Unicode, rounding, unresolved accounting exclusion and explorer height conflicts. Tests cover private-row codec preservation, truncated/invalid record rejection, capture failure cleanup, active prepared-file ownership, deferred cleanup during saves and rejection of extreme decimal exponents without substituting invented amounts. No receipt count limit or truncation was introduced.

## Android evidence

The actual SQLite helper reports `journal_mode=wal` and `synchronous=2` (FULL). A read-only export transaction is paused after its first row. A concurrent writer commits changed prices, a new trade, a new correction and changed taker evidence while that snapshot remains open. Subsequent export queries still return the coherent earlier trade/correction/taker snapshot; live database queries retain the newer committed records. A thrown spool-write error releases the snapshot and later receipt writes succeed.

The resulting ZIP from the actual database matches the prior report for small fixtures and includes all correction/taker evidence. A real local ContentProvider receives mode `wt`; replacing a longer destination leaves exactly the new bytes. Missing destinations and provider-open failures return failure. A forged `file://` destination is rejected and an app-private receipt fixture remains byte-for-byte unchanged. This tests the write boundary directly, not delivery of a malicious result through the system picker.

The rerun database suite includes schemas4/6/7/8/9/10 to11, existing atomic aggregate/source correction and rollback, and deliberate process death before/after the retained completion boundaries. It is not hardware power-loss or disk-exhaustion testing.

## Reuse and implementation

`TradeExport`'s tested row formatting, totals, accounting rules and external-corroboration logic are shared by the legacy fixture builder and new streaming path. `DexDb` reuses its existing personal-row, correction and taker queries and row decoders. `TradeExportFiles` captures one record at a time into a private temporary workspace, closes the coherent database snapshot, then performs bounded optional explorer checks and writes CSV/ZIP streams. Only the finished ZIP and small summary survive while waiting for the picker; intermediates, cancelled artifacts and stale abandoned workspaces are cleaned up. Prepared files stay pinned during an active save.

PandaPools `ExportWriter.stageForShare` supplied the private-cache staging pattern, but its complete in-memory CSV was unsuitable for the all-history ZIP. Openly `OpenlyDb` supplied WAL use; PandaDEX adds explicit FULL synchronization through the connection open parameters so manufacturer defaults cannot silently choose weaker durability. The public read-only transaction API is available fromAPI35 and supports concurrent writers with WAL. Older runtimes use the existing non-exclusive transaction during local capture. No explorer or document-provider operation runs under either snapshot transaction. [Android transaction API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#beginTransactionReadOnly()).

FULL synchronization adds a WAL sync at each transaction commit; this is configured explicitly for funds-related local receipts. The tests establish the configuration and application-process recovery, not every hardware/filesystem power-loss guarantee. [SQLite synchronous modes](https://www.sqlite.org/pragma.html#pragma_synchronous).

A spool record is capped at4MiB and numeric scales/precision reuse the existing44-digit bound. Oversized/malformed records abort preparation visibly and leave source data intact. The final ZIP is not limited to4MiB. The export worker has a bounded queue and process-wide build/save admission; a provider cannot create an unlimited number of replacement export workers.

## Lint and compatibility

Release lint: **zero errors,76 warnings**. One new warning is `InlinedApi` for the API33 name `SQLiteDatabase.SYNC_MODE_FULL`. Its value is compiled as the literal `FULL`, with no runtime field access; the constructor bytecode is retained in `memory/constructor-bytecode.txt`. `OpenParams.setSynchronousMode` itself is available at the app's API28 minimum. The initial literal triggered lint's StringDef error and was replaced by the named constant. API28–34 runtime performance/locking still needs execution coverage; the API35 read-only branch was executed onAPI36.

## Remaining limits

The system save-picker flow is not yet retained across Activity recreation/process death. Current cleanup prevents abandoned in-memory payloads/files from being kept by a destroyed Activity, but rotation while choosing a destination can require exporting again. This remains a workflow task, not a completed lifecycle claim.

A permanently blocked document provider can occupy the export worker; completion after process death/cloud upload and malicious close-time/provider behaviors still need broader validation. A completed local stream close is not independent evidence of remote cloud durability. Large snapshots consume temporary disk and retain a WAL snapshot while capturing; actual low-storage and stock-device latency tests remain open. Older API28–34 capture uses a transaction that can delay other writers. Corrupt/unsupported legacy numeric records cause a visible export failure rather than guessed accounting; a separate raw-data recovery workflow remains open.

Reproduce with `build_harness.py`, a fresh output directory and **audit version14 or greater**. Run instrumentation `.../com.eurobuddha.pandadex.ExportAndroidAudit`, then `.../com.eurobuddha.pandadex.DatabaseAudit` phases `normal`, `crash`, `recover`, `crash-taker-before`, `recover-taker-before`, `crash-taker-after`, `recover-taker-after` on an explicitly isolated disposable emulator. Require the saved PASS counts and expected crash markers. Use the archived memory init script for the single large-archive JVM test, then rerun the full suite separately. No production approval or complete-security claim is made.

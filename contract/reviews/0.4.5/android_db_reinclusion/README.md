# Android receipt re-inclusion audit — build 5

Executed 2026-09-09 on a fresh disposable Android 16 / API 36 emulator using the installed API 36.1 Google Play arm64-v8a image, emulator 36.4.9.0. No user phone, existing AVD userdata, wallet or Minima node was accessed. The separate `com.eurobuddha.pandadex.audit` test package has no INTERNET permission and uses explicit synthetic Spend fixtures.

## Final results

- **105 assertions passed** in `normal.txt`: actual Android `DexDb` correction/archive/queue transactions, injected write failures, stale rechecks, missing-proof accounting, export archive consistency, aggregate taker key protection, SQLiteOpenHelper migrations from schemas 4/6/7/8 to 9, and the new re-inclusion cases.
- **Expected process termination** in `crash.txt`: the existing competing-spender correction runs inside an enclosing uncommitted SQLite transaction, then the audit process kills itself.
- **10 recovery assertions passed** in `recover.txt`: original receipts/ledger and queued work survive, incomplete correction/archive changes roll back, proof epochs prevent stale authorization, and fresh proof permits recovery.

The final build therefore completed **115 Android assertions**, separate from **388 passing JVM tests**. These counts are assertions, not additional JVM test methods. Build 3 passed 99 Android assertions before coordinate-only economic preservation and index migration checks were added. Intermediate audit builds 3 and 4 were preserved; the final revised APK uses version 5. No production APK was built or overwritten.

## Re-inclusion cases

An accepted current-chain recheck with a changed inclusion block queues historical source recovery in the same SQLite transaction. Failure to save the queue rolls the recheck back. Each pass queues at most 32 source records and later checks resume the rest; existing live candidates become historical without replacing their original source bytes. Legacy ledger rows lacking original source JSON allow real history rediscovery when their recorded block differs from the checked inclusion.

Only a complete later Spend, including the exact linked source and verified inclusion-block time, can correct the receipt. Missing time, invalid new block coordinates and delayed old inclusion proofs retain the original rows and queued work. The archive preserves the old timestamp/ledger and explicitly records identical old/new TxPoW IDs for a re-inclusion. A replacement block at the same height also triggers repair. Unchanged block coordinates are idempotent.

A same-TxPoW correction updates source/public and matching maker receipt coordinates only. It preserves their original economics, ownership and effect-verification status even when the current caller's amount or wallet attribution differs. Aggregate taker receipts sharing a source key are not rewritten from maker-source evidence. No new-fill notification is produced for a correction.

## Performance and migration

`verifiedspend_tx(txpowid COLLATE NOCASE, coinid)` indexes the requeue lookup. Host SQLite EXPLAIN changed from a whole-ledger primary-key scan to an index search by TxPoW. Android migration fixtures explicitly remove the new index before marking the database as schema 4/6/7/8, then verify that the real SQLiteOpenHelper upgrade recreates it while preserving receipts and queues. Schema 9 remains unreleased production source; its index is created both for new databases and upgrades from every earlier schema.

## Reproduce and provenance

The builder is reused from [audit build 2](../android_db_audit/README.md). Use a new output directory and incremented audit build number, for example:

```sh
python3 contract/reviews/0.4.5/android_db_reinclusion/build_harness.py --output /private/tmp/pandadex-db-audit-6 --build-number 6
```

Use only a newly created disposable emulator with an explicit serial and isolated ADB server/key. Invoke `com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.DatabaseAudit` with phases `normal`, `crash`, then `recover`; require the PASS output and expected crash marker, not merely an `am instrument` exit code. See the earlier audit README for isolation requirements. The runner/builder and raw results here reproduce this test scope. `build-evidence.json` records every production Java hash, runner hash and APK hash; `android-system.json` records the tested image.

## Limits

The fixtures do not prove a live chain reorganization or cryptographic inclusion. The deliberate kill covers competing-spender rollback, while the new same-TxPoW branch has real SQLite write-failure/rollback coverage; do not relabel this as a separate same-TxPoW process-kill test. No production manifest, SDK/IPC, phone lifecycle, SAF/cloud save flow, real disk exhaustion or hardware power loss was tested. The current export classes were compiled into this audit, but optional network checks and save-picker flows were not exercised. Aggregate taker/composite timestamp reconstruction, missing/pruned history, and the stock Samsung/MinimaCore and human-only live composite gates remain open.

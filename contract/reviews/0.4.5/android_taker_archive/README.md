# Android completed taker archive audit — build 8

Executed 2026-09-09 on a fresh disposable Android 16 / API 36 arm64 emulator with a separate audit application ID and isolated ADB keys/server. No user phone, existing AVD userdata, wallet or Minima node was accessed. The audit package has no INTERNET permission. The exact Java/runner/APK hashes and Android system properties are archived alongside raw results.

## Results

**167 assertions passed:** 143 normal database assertions, 10 competing-spender crash/recovery assertions, and 7 assertions each for taker recovery after deliberate process death before and after the database commit. The APK is independently versioned as audit build 8; all earlier APKs and evidence checkpoints were preserved. Production source remains 0.4.5 / 405 and no production APK was built or overwritten.

The normal run executes the actual production SQLiteOpenHelper upgrades from schemas 4/6/7/8/9 to 10. Original trades, amounts, checks, queues and history cursors are preserved; the new evidence table is empty for old receipts, without fabricated expectations. Existing maker/source correction, re-inclusion, bounded queue, rollback, overlapping taker key and export tests also pass.

The new archive case uses `TakerReceipt.capture` and the actual `DexDb.recordTakerFill(TakerReceipt)` boundary. An injected archive-insert failure rolls back the new personal trade and confirmation check. Successful completion preserves the expected transaction identity, selected source IDs, payout/token/amount, direction, price, source kind, selected source input JSON/positions, transaction input count, outputs/state and verified inclusion coordinates/time/order. Replay preserves original archive bytes. A changed expected payout cannot replace the archive, and a matching already-recorded row can gain complete evidence through a fresh fully linked pending receipt without creating a duplicate trade. A database snapshot exports the matching trade and evidence together; reopening preserves both.

The taker process-kill phases now execute the full archive boundary. Before the enclosing database commit, neither the row nor evidence survives; after commit, both survive. In both cases the separately committed pending intent remains until a new process restores/reuses one completed record and acknowledges pending cleanup. These phases execute the real database/preference operations in sequence, not MainActivity's private method or a complete Activity lifecycle.

## Reproduce

Reuse `build_harness.py` with a new output directory and an incremented audit number (9 or later after this checkpoint). Use only an explicitly selected fresh disposable emulator. Run instrumentation `com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.DatabaseAudit` with phases `normal`, `crash`, `recover`, `crash-taker-before`, `recover-taker-before`, `crash-taker-after`, `recover-taker-after`, in that order. Require each PASS count and the expected process-crash marker, rather than relying on shell exit status. Isolation details are in [the original audit](../android_db_audit/README.md).

## Limits

The Spend fixtures are synthetic and do not establish live chain inclusion, stock MinimaCore compatibility or a real reorganization. The archive contains selected order/pool source inputs, not every wallet funding input; `input_count` records the full transaction count. No production manifest, SDK/IPC, phone lifecycle, real disk exhaustion, hardware power loss, save-picker or cloud provider was exercised. Export snapshot contents are tested; the ZIP/save UI is compiled, not exercised in these phases. SharedPreferences failed commits are simulated in separate JVM tests, not injected into Android here.

The archive is a retained historical observation, never a new chain-verification result. Automatic reconstruction and re-inclusion repair of completed aggregate taker receipts remain open. Receipts lacking original expectations are not backfilled by this migration. One archive row is limited to 1 MiB for local SQLite/CursorWindow handling; exceeding it prevents completion and retains the pending receipt, without truncating evidence. This is not a chain TxPoW limit. Export memory remains proportional to the full history/archive size. Stock Samsung/MinimaCore and the human-only live composite release gates remain open.

The source checkpoint passes **406 JVM tests**, no failures/errors/skips, and release lint has zero errors and 75 warnings. The host DDL smoke result is retained separately and does not stand in for the Android execution above.

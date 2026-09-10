# Android taker completion audit — build 7

Executed on 2026-09-09 with a fresh disposable Android 16 / API 36 arm64 emulator, separate audit package `com.eurobuddha.pandadex.audit` and isolated ADB server. No user phone, AVD userdata, wallet or Minima node was accessed. The audit manifest has no INTERNET permission.

The final runner completed **139 assertions**: 115 normal database assertions, 10 competing-spender process-recovery assertions, and 7 assertions each for taker recovery after process death before and after the database commit. Raw outputs, exact production Java hashes, runner/APK hashes and Android image properties are retained here. The prior build-5 evidence remains unchanged. Intermediate audit build 6 completed 115 normal assertions before the taker crash phases were added; both APKs are preserved outside the production release directory.

## Scope

Actual production `DexDb.recordTakerFill` executes on Android SQLite. Tests inject an insert failure and require rollback without an orphan check, require exact verified block timestamps, adopt the ordered inclusion proof, reject mismatched economics/TxPoWs or missing time, and replay an identical recorded trade without a duplicate row. Existing correction, migration, bounded source requeue and re-inclusion tests also run.

For each taker crash fixture, the runner saves a synthetic pending intent through Android SharedPreferences, executes the actual database method, and kills its own process either before an enclosing transaction commits or after the database commit but before preference removal. A new process requires the pending intent to remain, restores or reuses the single trade row, acknowledges preference removal and verifies state after reopening.

These tests execute the persistence operations in the intended order. They do **not** invoke MainActivity's private completion method or exercise a full Activity lifecycle. No SharedPreferences `commit(false)` failure was injected. MainActivity's cleanup retry was compiled/reviewed; Android's memory-before-disk commit semantics were checked against the [AOSP implementation](https://android.googlesource.com/platform/frameworks/base/%2B/d88b5baa7b5a/core/java/android/app/SharedPreferencesImpl.java). Process termination is not a hardware power-loss or disk-exhaustion test. Synthetic Spend fixtures do not prove live transaction inclusion, stock MinimaCore compatibility or on-chain reorganizations.

## Reproduce

Reuse `build_harness.py` with a new output directory and incremented audit build number (at least 8 after this checkpoint). Never overwrite a prior APK. On an isolated disposable emulator, run instrumentation `com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.DatabaseAudit` with `-e phase` in this order: `normal`, `crash`, `recover`, `crash-taker-before`, `recover-taker-before`, `crash-taker-after`, `recover-taker-after`. Require each PASS count and expected crash marker, not only the shell exit code. Isolation details are in [the original audit](../android_db_audit/README.md).

The source checkpoint also passes **391 JVM tests**, zero failures/errors/skips; release lint has zero errors and 76 warnings. No production 0.4.5 APK was built. Full completed taker intent retention/reconstruction, aggregate re-inclusion timestamp repair, SDK/IPC, stock Samsung testing and the human-only live composite gate remain open.

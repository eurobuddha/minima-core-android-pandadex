# Android ownership loading and automatic upkeep — audit18

**92 actual Android assertions pass**:62 normal,11 before deliberate audit-process death,19 in a verified new process. The existing550-JVM-test and zero-error/75-warning lint checkpoint remains current; no production Java changed during this run.

## Artifact and isolation

- Separate application ID `com.eurobuddha.pandadex.audit`, versionCode18 / versionName `export-audit-18`, checked from the APK.
- APK: `/private/tmp/pandadex-android-audit-build-18/pandadex-export-audit-18.apk`.
- SHA-256: `34632cfc1250acc95dcbfaf1c403ccb1abe5d1c43bb548031cc000ca83e3896e`.
- Fresh Android16/API36 arm64 AVD PandaDexAudit18, isolated ADB5049 and separate temporary userdata/keys. The runner verifies the exact owned QEMU/AVD/path and boot state before targeted installation; it never queries default ADB5037 or connected user devices.
- No INTERNET permission, production MainActivity or NodeTransportService in the audit manifest. All node commands/transaction replies are controlled fixtures; no NodeApi instance or node IPC is used. Full APK badging is archived.
- All79 production Java source hashes match before/after compilation and before/after execution. Exact runner hash and APK hash are archived.

The owned emulator and isolated ADB server were stopped, and their userdata/keys removed. APK/logs remain preserved. Audits1–17 and frozen production404 were not overwritten. No production405 APK was built, and no commit/push/publish occurred.

## Executed checks

**Normal62:** repeats audit17's34 maker/owner-receipt checks against current sources, including real SharedPreferences wrong native types/corrupt JSON, pause/recovery, batch cancellation phases and exact relock expectations. Adds ownership-loader and automatic-upkeep checks using real Android preferences.

KeySet runs on the actual main looper with its public Context constructor and real Handler. A two-address derivation keeps the previous key/address snapshot until complete. An address reply is posted to the main queue, then the loader/pass are invalidated before delivery; the queued reply cannot restore readiness or overwrite the snapshot. A complete subsequent load publishes both factors and resumes the production WatcherPassGate once. After an error schedules the actual ten-second retry, invalidation cancels it; the instrumentation waits10.25seconds and observes no extra command. Reopening the cache recovers both factors while remaining unready until a live load. Closing the loader clears readiness. This is not a Doze or timer-delivery guarantee.

The production DexProcessor(Context,txn) uses actual Android preferences and Pending(Context). A fixture renewal progresses through committed PREPARED/POSTING then lost-reply UNKNOWN. A new processor instance refuses an aged retry. An unrelated expired source can dispatch its refund through the same cancellation journal; its unknown outcome prevents another automatic refund. A real boolean stored in the string marker field pauses new upkeep without overwriting it.

**Before death11:** commits the existing maker pause, funded slot, prepared intent and withdrawal identities, two cancellation receipts left POSTING, a relock left POSTING and a renewal left PREPARED. Adds a refund receipt left POSTING and an old pacing marker. A checkpoint records build18/PID/assertion count; only the audit process kills itself. The host verifies the expected crash result and that process's absence. The saved count10 excludes the checkpoint assertion itself, yielding11 before death.

**Recover19:** verifies a different Android PID, acknowledged disarmed maker settings, original/prepared identities and all five owner-operation receipts. Original cancellation economics, exact relock amount/successor fixture and prepared renewal semantics survive death. The real processor refuses both the unresolved renewal and expired refund; the aged pacing hint can be removed while all five receipts remain. Repeated unresolved-source pauses are deduplicated.

## Reuse and reproducibility

The builder, runner, launcher and cleanup are the inspected audit17 harness, adapted to a fresh build/output/AVD18. The original owner-receipt instrumentation is retained and extended with the current KeySetRecoveryTest/DexProcessorRecoveryTest fixture shapes. The real KeySet, MakerConfig, Pending, WatcherPassGate and DexProcessor are compiled from the repository; no duplicate preference or journal implementation replaces them. UpkeepTxn deliberately supplies controlled receipt callbacks, so this does not execute actual signing or submission.

Reproduction scripts and raw results are archived here. Use a new build number/output/AVD for any future APK; never overwrite audit18 or redirect these scripts to a user device.

## Limits

This establishes the described Android preference, queued-callback, process-death and processor-retry cases. It does not execute the real foreground Service/Activity lifecycle, render the Maker screen, run MinimaCore IPC, construct/sign/post transactions, inject disk-full/commit-false, prove Doze/wakelock delivery, or test stock S23/ZFold behavior. Audit18 repeats selected earlier owner tests, not every SDK/export/database test. It does not resolve the failed-pause restart-policy question or establish general valid-snapshot host concurrency.

Long asynchronous pass/transaction overlap, durable terminal owner-operation history and recovery UX, legacy reconstruction, receipt/store growth, broader callback-side errors and the human-only composite gate remain open. No production-readiness or100%-security claim is made.

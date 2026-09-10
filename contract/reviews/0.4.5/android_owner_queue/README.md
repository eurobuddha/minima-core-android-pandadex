# Completed owner recovery — audit27 / schema13

**798 actual Android assertions pass**:752 normal,16 before deliberate process death and30 after restart. Reuses audit26's complete isolated harness, adapts existing migration expectations to13, and adds completed-owner queue checks. All current production Java hashes match compilation and execution.

## New evidence

- A completed owner receipt has no pending row. Accepted MISSING recheck sets its durable queue flag. The disposable database is reconstructed with schema12's actual eight-column owner table; reopening via the real SQLite helper adds schema13 columns/indexes, seeds mismatched/missing receipts, and preserves every owner row and original JSON.
- Real queue repair fails under a SQLite trigger that rejects retirement: old evidence and the retry flag survive together. After dropping the trigger, actual OwnerRecovery requests the original source, verifies fresh effects and repairs the stored receipt. A duplicated callback completes once. Original observation time survives, the flag clears, and an obsolete snapshot cannot overwrite the revision.
- Durable turn selection alternates owner/other work. Nine queued records are all reached across bounded four-record batches.
- The earlier competing-transaction fixture now has complete linked block time. The real database rejects it while the old winner is CURRENT, then accepts a newly verified complete alternative after the old winner is MISSING. This closes audit26's specific incomplete-fixture qualification.
- The completed owner queue is committed before actual process death. In a verified different PID, queued state and original JSON remain, and automatic recovery accepts a fresh re-inclusion and clears the queue. This covers restart of an already-completed receipt independently of Pending.

## Artifact and limits

APK `/private/tmp/pandadex-android-audit-build-27/pandadex-export-audit-27.apk`; SHA-256 `5e319043cdff0da089065c19c6ce84ec609dc6c6500bf9c2e25920389ae677a4`. Package com.eurobuddha.pandadex.audit, versionCode27; fresh output path. Previous APKs retained. Production405 remains unbuilt and frozen404 unchanged.

Fresh disposable Android16/API36 arm64 emulator, exact owned QEMU/AVD/path checked before commands, isolated ADB5049. No INTERNET permission, production MainActivity/NodeTransportService, user node/phone, signing or submission. Actual SQLite/process execution uses transaction fixtures. The shared FillSettler scheduling is covered by JVM tests/source callers; this audit does not run the production Activity or watcher service lifecycle, Samsung IPC or Doze. New screenshots/fixture ZIP remain at the temporary output path; no new personal visual inspection is claimed. Raw logs/hashes/checkpoints archived here. Emulator/isolated ADB stopped; temporary data/keys removed.

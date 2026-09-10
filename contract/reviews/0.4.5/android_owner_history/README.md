# Actual Android owner history — audit21

**570 assertions pass**:531 normal,14 before deliberate audit-process death and25 after restart in a verified different PID. This repeats audit20's545 checks and adds25 owner-history assertions. All80 current production Java hashes match during compilation and execution. JVM checkpoint:600 tests, zero failures/errors/skips; release lint zero errors/75 warnings.

## Artifact and isolation

Package `com.eurobuddha.pandadex.audit`, versionCode21 / `export-audit-21`. APK `/private/tmp/pandadex-android-audit-build-21/pandadex-export-audit-21.apk`; SHA-256 `41357a5e0bba90e2b73d7b4e78c1d7ef2e8c19205c30c7c4e9afd59090d86f59`. Fresh output directory, prior APKs preserved. Production405 unbuilt; frozen404 unchanged. No commit/push/publish.

Reused audit20's complete isolated builder/launcher/runner/cleanup and instrumentation, incrementing audit number/output/AVD and adding owner database/recovery checks. Fresh Android16/API36 arm64 PandaDexAudit21, isolated ADB5049/console5680 and separate temporary data/keys. Runner checks exact owned QEMU/AVD/path and boot state before targeted install. Default ADB5037, user devices and user nodes were not queried. Audit manifest has no INTERNET permission, production MainActivity or NodeTransportService; node responses and transaction callbacks are fixtures, with no IPC/signing.

The owned emulator and isolated ADB were stopped, and userdata/keys removed. APK/logs retained. Source/runner hashes, badging, persistent crash checkpoint, results and reproduction scripts are archived here. Future builds require a new number/output/AVD.

## New execution evidence

Normal checks open a fresh schema12 SQLite database, emulate prior schema11 in that disposable database by dropping only the new owner table and setting its version11, then reopen through the actual helper upgrade. Existing meta data survives. A real Pending/DexDb-backed relock intent is captured and archived; ownerreceipt and chaincheck commit together, mytrade/tape stay empty, and the returned entry carries the actual fixture depth4.

A second capture after the pending phase advances to SUBMITTED is idempotent and preserves the first JSON/timestamp. A temporary SQLite trigger aborts chaincheck insertion for a second receipt: both its archive and check roll back. Actual ChainReview reviewed updates drive MISSING, restored CURRENT depth11, and changed inclusion; the list keeps the original proof coordinates and does not report the moved proof as current.

Before deliberate process death, the existing five owner intents remain pending while the 0xdd edit's terminal archive is committed. Its ID is stored in the durable checkpoint. After restart in a different PID, the real DexDb/Pending path re-verifies a controlled included transaction and replays completion. Exactly one matching pending row clears (five become four), archive count stays unchanged and the original JSON remains byte-identical. This directly exercises the cross-store commit/cleanup gap, not just instance recreation.

## Limits

The emulator database and process execution are real; transaction/network data are fixtures. The Operations view is compiled but not inflated/rendered here. Full UI/lifecycle, stock Samsung/MinimaCore IPC, signing/submission, Doze and disk-full injection are not covered. The migration uses an otherwise current database marked11 with only the new table absent; it does not replace all older-schema migration fixtures. Owner export/paging, reorg proof correction and deleted legacy-history reconstruction remain unfinished. See [owner-history review](../owner_history/README.md). No production-readiness or100%-security claim.

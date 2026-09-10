# Android receipt integrity — audit19

**200 assertions pass**:170 normal,11 before deliberate audit-process death and19 after restart in a verified different PID. Of these,108 added checks exercise receipt/parser integrity; the prior92 ownership, maker, owner-receipt and upkeep assertions repeat. All79 production Java hashes match at compilation and execution. Current full JVM suite:569 tests, zero failures/errors/skips; lint zero errors/75 warnings.

## Artifact and isolation

Separate package `com.eurobuddha.pandadex.audit`, versionCode19 / `export-audit-19`. APK preserved at `/private/tmp/pandadex-android-audit-build-19/pandadex-export-audit-19.apk`; SHA-256 `ceeb356b992e301d62391fb12c6c7d8259f2778a4921f70f7173a11977589857`. The builder used a fresh output directory and preserved prior APKs. No production405 APK was built; frozen404 is unchanged. No commit/push/publish occurred.

The audit reused the complete audit18 builder/runner/launcher/cleanup and instrumentation, adapting only the audit identity/output/AVD and adding integrity fixtures. It compiled current production Java/resources with a separate manifest containing no INTERNET permission, production MainActivity or NodeTransportService. All transaction callbacks and ownership replies are fixtures; no node IPC or signing runs.

A fresh Android16/API36 arm64 PandaDexAudit19 emulator used isolated ADB5049, console5680 and separate userdata/keys. The runner verifies the exact owned QEMU/path and boot state before installation. The first invocation stopped before installation because boot was incomplete; the subsequent invocation ran all phases after boot. Default ADB5037, user phones and user nodes were not queried. The owned emulator/ADB were stopped and userdata/keys removed; APK/logs remain.

## Integrity checks

Three checks reproduce Android JSONTokener.nextClean skipping trailing line/block/hash comments. Eight suffix cases then exercise Pending storage, MakerConfig slots and MinimaAPIResponse: those comments, ordinary garbage, a second array, form feed, a non-JSON control character and raw NUL. Real SharedPreferences retain the exact original bytes; unreadable maker/receipt state cannot authorize an update or advance owner preparation/post boundaries; malformed node responses cannot claim status success or rejection.

Further checks cover duplicate identities on distinct receipt rows, identical legacy rows, six malformed explicit identity types, stable single-legacy migration and legal trailing JSON whitespace. These execute the actual production parser and journal on Android, not a copied implementation. The old ownership/receipt/upkeep crash-recovery checks also repeat; only the audit process deliberately kills itself.

## Limits

This audit does not execute all compiled classes or every earlier SDK/export/database test. It does not test production Activity/Service rendering/lifecycle, actual MinimaCore IPC, signing/submission, stock S23/ZFold behavior, disk-full/commit-false or Doze. It does not make Android's root JSON parser fully strict or repair missing chain evidence. Broader receipt schema/recovery/terminal history and host concurrency remain open. See [receipt findings](../receipt_integrity/README.md) and the main review. No production-readiness or100%-security claim is made.

# Actual Android receipt schema — audit20

**545 assertions pass**:515 normal,11 before deliberate audit-process death and19 in a verified different PID. This repeats audit19's200 assertions and adds345 schema checks. All79 production Java hashes match at compilation and execution. The current full JVM checkpoint is579 tests with zero failures/errors/skips; release lint zero errors/75 warnings.

## Artifact and isolation

Separate package `com.eurobuddha.pandadex.audit`, versionCode20 / `export-audit-20`. APK `/private/tmp/pandadex-android-audit-build-20/pandadex-export-audit-20.apk`, SHA-256 `2b92ab16788937f9fb5fa4ba8fc02f48d05b71129355c8ece6979713a39200ae`. Fresh output directory; prior audit APKs and frozen404 preserved. Production405 unbuilt; no commit/push/publish.

Reused the complete audit19 builder/launcher/runner/cleanup and instrumentation, changed audit number/paths/AVD and added schema fixtures. Fresh Android16/API36 arm64 PandaDexAudit20 with isolated ADB5049/console5680 and separate temporary userdata/keys. Before targeted installation, the runner verifies the owned QEMU/AVD/path and boot state. It does not query default ADB5037, connected user devices or user nodes. Audit manifest has no INTERNET permission, production MainActivity or NodeTransportService. Transaction callbacks and node replies are fixtures; no node IPC/signing runs.

The owned emulator and isolated ADB server were stopped and their userdata/keys removed. APK and logs remain. Raw results, persistent crash checkpoint, APK badging, exact source/runner hashes and reproduction scripts are archived here. Future builds must use a new version/output/AVD.

## Executed schema checks

Production DexProcessor first receives a fixture renewal with an acknowledged PREPARED/POSTING/UNKNOWN receipt. After its kind is changed to EDlT in real SharedPreferences, a new processor instance pauses instead of dispatching another renewal after the pacing interval; exact corrupted bytes remain.

The production Pending(Context) path then rejects absent original fields, wrong native text/boolean types, malformed/negative/oversized decimals, fractional/overflow/negative heights/times, unknown phases and wrong creation-container types. Each malformed store must expose recovery state, reject preparation/post boundaries and preserve original bytes. The valid old shape remains readable, zero display price survives an acknowledged update, and an invalid new row is refused without poisoning the store.

Audit19's suffix/comment/identity cases and earlier ownership/maker/upkeep process-death recovery repeat. This run uses actual Android JSON and SharedPreferences, not the JVM org.json substitute.

## Limits

Compiling all production Java does not mean every class/path was executed. No production Activity/Service rendering/lifecycle, stock Samsung/MinimaCore IPC, real signing/submission, disk-full/commit-false or Doze validation. The new malformed-kind case tests a new processor instance, while the repeated crash phase tests durable owner state in a new actual process. Full modern-intent cross-field consistency, terminal history, recovery UX and the remaining production gates stay open; see [schema findings](../receipt_schema/README.md). No production-ready or100%-security claim.

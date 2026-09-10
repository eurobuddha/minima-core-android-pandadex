# Android maker and owner-receipt audit17 — 2026-09-10

**58 actual Android assertions pass**:34 normal checks,9 pre-death assertions and15 assertions after deliberate audit-process termination. This runs the current maker preference loader and shared creation/cancellation/relock receipt journal on a fresh disposable Android16/API36 arm64 emulator. The existing514 JVM tests and zero-error/75-warning lint checkpoint remain unchanged; no production Java changed during this audit.

## Artifact and isolation

- Separate application ID: `com.eurobuddha.pandadex.audit`.
- Audit versionCode17 / versionName `export-audit-17`, verified from the built APK. No production0.4.5 APK was built.
- APK: `/private/tmp/pandadex-android-audit-build-17/pandadex-export-audit-17.apk`.
- SHA-256: `1832fd3747878ee2c9d1f2e63aa087203b630061ed3ea85156d0b8cc2a63dbbd`.
- The merged audit manifest has no INTERNET permission, no production MainActivity and no NodeTransportService. No NodeApi or live node was used. Dependencies contribute normal Android support permissions; the full badging output is archived.
- Fresh AVD PandaDexAudit17 used separate ADB5049 and its own keys/userdata. Runner verified QEMU, the exact AVD/process/path, completed boot and all production source hashes before installation/execution. No default ADB5037 or connected user phone was accessed.

The emulator and isolated ADB server were stopped after the run; its userdata and keys were removed. The APK/logs remain retained. Frozen production0.4.4 is unchanged. This APK is an instrumentation harness, not a wallet release.

## What executed

**Normal34:** real SharedPreferences save/reload of maker settings, original funded amount/token and slot identity; actual persisted corrupt JSON and wrong native preference types; graceful pause, preservation of previously decoded IDs and raw malformed bytes, blocking further maker writes, and explicit recovery after valid data is restored. The production Pending(Context) store records a two-source cancellation batch, advances POSTING, retains UNKNOWN and its original source economics, and saves/loads exact relock amount and accepted phase. Android's actual JSON implementation checks exact successor, adjacent-grain rejection and unconfirmed rejection. Persisted malformed receipt JSON prevents a new intent without overwriting the original bytes.

**Before death9:** committed fixture resets/settings, a prepared maker intent, the maker pause with both accepted/prepared withdrawal identities, a two-source cancellation batch left POSTING, a relock left POSTING and a renewal left PREPARED. A committed checkpoint records build17 and the process PID, then only the audit process kills itself. The host observes the expected Process crashed result and confirms that process is absent. The archived checkpoint contains8 assertions completed before its own final commit assertion, making9 before deliberate termination.

**New process15:** verifies that the checkpoint belongs to audit17 and the actual process PID changed, loads the acknowledged disarmed maker state, both cancellation identities and original slot/prepared intent, and recovers all four owner-operation receipts. Cancellation sources and phases, exact relock expectation, exact successor fixture and unsubmitted renewal status survive real process death.

## Reuse and reproducibility

`build_harness.py` is adapted from android_price_timing's node-free builder; `OwnerReceiptAndroidAudit.java` follows its Instrumentation flow and android_export_lifecycle's deliberate crash/recovery pattern. Source-order/evidence fixtures use the already-tested CancellationReceiptTest/RelockReceiptTest shapes. The same production MakerConfig/Pending implementations are compiled directly from the repo, with pre/post source hashes checked. No duplicate persistence implementation or Android mock preference store supplies these assertions.

The archived build evidence identifies every production Java source and the exact runner SHA. The host runner, isolated-emulator launcher and cleanup script are saved for review. They are intentionally pinned to this isolated audit; use a new build number/output/AVD for a subsequent APK. Never overwrite audit17 or redirect these scripts to a user device.

## Limits

These tests use real Android storage and actual process death, but controlled transaction/refund/successor fixtures. They do not construct/sign/post transactions through successful real MinimaCore IPC, test stock S23/ZFold behavior, render MainActivity, force a filesystem-full condition, or validate Android Doze/foreground-service scheduling. A committed pause surviving death is not proof that an unsuccessful pause write survives death; the process-local failure latch/restart-policy question remains open. Tests preserve corruption; they do not reconstruct damaged identities.

The audit does not repeat every earlier SDK/export/database test or the full JVM suite inside Android. Terminal owner-operation history/retirement, retry policy, broader callback errors, valid cross-instance snapshot races, remaining pending-row validation, legacy reconstruction and the human-only composite gate remain open. No production-ready or100%-security claim is made.

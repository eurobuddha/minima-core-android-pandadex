# Isolated Android database audit — build 2

Executed 2026-09-09 on a newly created disposable Android 16 / API 36 emulator, using the installed API 36.1 Google Play arm64-v8a image (revision 4), emulator 36.4.9.0. This emulator had no Minima node, wallet or funds. No user phone, user AVD userdata, or user Minima node was accessed.

The separate `com.eurobuddha.pandadex.audit` application compiles the actual production Java and resources directly. Its manifest declares the audit instrumentation, no production Activity or node transport service, and no INTERNET permission. Its application ID, version and manifest differ from the production app. It does not exercise the production manifest, lifecycle, IPC, SDK connection, signing, or on-chain verification against a live node. Spend evidence is an explicit fixture; classification is covered separately by the JVM suite.

## Observed results

- `normal.txt`: **68 passing assertions**. Actual `DexDb.completeFill`, `completeNonTrade`, `reviewed`, `loadExport`, and `SQLiteOpenHelper` execution: atomic receipt/archive/winner/queue commit, injected SQLite update failure rolling all of them back, missing-proof accounting exclusion, stale callback rejection, nontrade preservation, overlapping aggregate taker receipt preservation, close/reopen, and additive schema 4/6/7/8 → 9 upgrades.
- `crash.txt`: **expected process termination**. The runner holds an outer SQLite transaction around actual correction work, verifies its uncommitted archive is visible, durably records an audit sentinel in separate preferences, then kills its own process before the outer commit. This simulates process death before database commit; it does not simulate hardware power loss or every filesystem failure.
- `recover.txt`: **10 passing assertions** in a new process. The sentinel proves the crash phase ran; the archive and queue deletion rolled back, both original receipts and their spender survived, prior-process missing proof could not authorize correction, and a fresh check allowed atomic recovery.

These are 78 assertions, not 78 additional JVM test methods. The intentional crash phase also checks its fixture and uncommitted state before termination; its assertion count is not added to the completed runs. Build 1 passed 33 + 10 assertions before older-schema migration coverage was added. Build 2 was built separately with an incremented audit version; build 1 and production APKs were not overwritten.

## Reproduce

`build_harness.py` uses the repository's existing Gradle configuration, dependencies, Java sources and resources. Run it with a **new** output directory outside the repo and a **new, incremented** audit build number:

```sh
python3 contract/reviews/0.4.5/android_db_audit/build_harness.py --output /private/tmp/pandadex-db-audit-3 --build-number 3
```

It requires the existing Android SDK and cached dependencies; it builds offline, verifies production Java hashes did not change during compilation, checks the merged manifest, and writes a distinctly named audit APK plus its SHA-256 evidence. It never invokes a production build task in the production repository.

Create a fresh disposable AVD from a pristine installed SDK system image. Use a new temporary AVD/data/user directory, a dedicated ADB server port, and the same temporary ADB key for server and emulator **before the first boot**. Do not copy an existing AVD's userdata, wipe a user AVD, or restart the user's ADB server. See [Android's documented emulator data-directory options](https://developer.android.com/studio/run/emulator-commandline).

For the recorded run, the isolated ADB server was port 5049 and the disposable emulator was `emulator-5680`. Only after verifying an explicitly chosen emulator is disposable, install the audit APK and invoke this instrumentation with `-e phase normal`, then `-e phase crash`, then `-e phase recover`:

```text
com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.DatabaseAudit
```

Pass both the explicit ADB server port and serial to every command. Require the `PASS` assertion count in normal/recover output; an `am instrument` shell exit code alone does not establish success. The crash phase must report `Process crashed.` and recovery must pass. The crash phase seeds its own database; it is safe only for this isolated package and emulator.

## Evidence and limits

`build-evidence.json` contains every compiled production Java source hash, runner hash and audit APK hash. `android-system.json` identifies the tested OS image. The audit APK remains in temporary build output; it is not a release artifact. The signed PandaDEX 0.4.4 APK SHA-256 remains `e39aa40dc42f051d9668f47e9309b05349f8149c07cdb6c29187f8c1a26b769f`. Production source remains 0.4.5 / 405; no production 0.4.5 APK was built.

This closes the specific lack of Android database-wrapper evidence for these cases. It does not approve stock Samsung/MinimaCore behavior, API 28 behavior, real reorganizations, real power loss, disk exhaustion, aggregate receipt reconstruction, or the human-only live composite gate. The full security objective remains open.

# Full offline Activity — audit31

895 actual Android assertions pass:808 normal component checks,16 before deliberate process death,31 after restart and40 full-Activity offline checks. Production Java hashes match source0.4.6/406 at build and execution. The652-test JVM suite and zero-error/75-warning lint run remain the current source validation; no production code changed for this audit.

Reuses audit30's complete harness, Activity launch/main-thread helper, screenshot writer, exact-owned-emulator checks, isolated ADB and cleanup. The audit manifest adds the real MainActivity with its production theme and private NodeTransportService in its normal separate process. It omits INTERNET permission, DexKeepAliveService, heartbeat/boot receivers and production background scheduling. The shell verifies MinimaCore is absent before the full-Activity phase; package visibility is not the sole absence check. After existing evidence is pulled, only the disposable audit package data is cleared for a cold launch.

The real Activity starts, shows pairing guidance, displays all six tabs through their actual navigation buttons, rejects the existing ready() spending precondition on every tab, preserves the selected Maker tab across actual Activity recreation, and releases its foreground flag on finish. Calling ready() is a guard check, not a transaction-button-to-node test. No signing or submission is performed.

All seven full-app screenshots were personally inspected: Trade, Chart, Trades, Orders, Assets, Maker and recreated Maker. Their hashes are in validation.json; originals remain in `/private/tmp/pandadex-android-audit-build-31`. The six tabs are visible and navigable and no crash occurred in this scenario. The long audit-only version label wraps the app logo, so these screenshots do not prove normal release-header fit. Dark theme at one portrait size only; keyboard, large fonts, landscape, Samsung Fold geometry, restored populated wallets, SAF provider handling, real node responses and FGS/Doze remain outside this pass.

## Findings from visual inspection

**MAJOR — Open:** cold offline Assets shows0 sendable/confirmed/unconfirmed and0 coins even though its timestamp is "updated never". Orders and Trade show "No open orders" before the node/book/ownership data loads. The pairing banner does not make those financial absence claims accurate. MainActivity already exposes per-token balance timestamps and makerBookReady(); follow-up should reuse those freshness fields while retaining cached records and pending receipts. No correction is claimed by this audit commit.

Chart's "no historical backfill" explanatory text also predates the retained-history discovery work; reconcile the wording with current evidence coverage in a follow-up. No complete-history claim is justified.

## Artifact and cleanup

APK `/private/tmp/pandadex-android-audit-build-31/pandadex-export-audit-31.apk`, SHA-256 `31fc0ae643b79f6835a463f0d01fe57168b8c0963c70fe67b3b62ed98d12c267`. Package com.eurobuddha.pandadex.audit, versionCode31, new output path; all previous APKs preserved. Disposable Android16/API36 arm64 emulator and isolated ADB5049 stopped; only its temporary data/keys removed. No user phone/node, production release, push or publication.

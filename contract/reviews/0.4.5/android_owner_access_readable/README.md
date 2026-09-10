# Owner history access — audit23

Failed after 678 assertions: the harness incorrectly used View.getMinimumHeight to inspect TextView.setMinHeight. The run stopped before later phases. Audit24 corrects the harness to check actual rendered height after layout. Production source is identical between audit23 and audit24. This failure is retained, not relabelled as a pass.

APK: `/private/tmp/pandadex-android-audit-build-23/pandadex-export-audit-23.apk`
SHA-256: `f9894f9cb643567c59b41f6ebf62cbdc8679c6d16c112e5af9add9cb6e3eff34`
Package: `com.eurobuddha.pandadex.audit`; versionCode 23. Each APK uses a fresh output directory and number. Earlier APKs remain preserved; production405 is unbuilt and frozen404 unchanged.

Reuses the prior owner-history Android harness, existing SQLite/Pending/ChainReview implementations and production export path. Fixtures seed tied timestamps and a new arrival while paging: older traversal reaches all original132 records without duplication; Newest includes the arriving row. The real database export contains133 owner receipts in a seven-entry ZIP, retaining original JSON and last saved node state without adding trades or totals. The actual view exercises Older/Newer and export callback availability, plus empty/error states. Node replies and transactions are fixtures; confirmation counts in screenshots are test data.

Fresh Android16/API36 arm64 disposable emulator, exact owned QEMU/AVD/path checked before targeted commands, isolated ADB5049. No INTERNET permission, production MainActivity, NodeTransportService, user node, phone, signing or submission. Export callback wiring is source-inspected; this UI audit does not invoke the SAF picker. Raw results, hashes, fixture ZIP and available screenshots are archived here. Emulator and isolated ADB stopped; temporary userdata/keys removed. No production approval or complete-security claim.

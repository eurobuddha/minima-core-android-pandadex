# Owner history access — audit24

727 Android assertions passed: 688 normal, 14 before deliberate process death and 25 after restart. All current production Java hashes match the APK. The actual production OwnerHistoryView is rendered inside an isolated audit Activity; four screenshots were personally inspected. Light text, wrapped TxPoW IDs, empty/error messages and measured 48dp navigation height pass this scoped visual check. Full MainActivity lifecycle, font scaling, screen-reader operation and stock-device testing remain open.

APK: `/private/tmp/pandadex-android-audit-build-24/pandadex-export-audit-24.apk`
SHA-256: `b7482ae096afd02d643c03678e36fbf59035c10c90c91e4874feeda616501586`
Package: `com.eurobuddha.pandadex.audit`; versionCode 24. Each APK uses a fresh output directory and number. Earlier APKs remain preserved; production405 is unbuilt and frozen404 unchanged.

Reuses the prior owner-history Android harness, existing SQLite/Pending/ChainReview implementations and production export path. Fixtures seed tied timestamps and a new arrival while paging: older traversal reaches all original132 records without duplication; Newest includes the arriving row. The real database export contains133 owner receipts in a seven-entry ZIP, retaining original JSON and last saved node state without adding trades or totals. The actual view exercises Older/Newer and export callback availability, plus empty/error states. Node replies and transactions are fixtures; confirmation counts in screenshots are test data.

Fresh Android16/API36 arm64 disposable emulator, exact owned QEMU/AVD/path checked before targeted commands, isolated ADB5049. No INTERNET permission, production MainActivity, NodeTransportService, user node, phone, signing or submission. Export callback wiring is source-inspected; this UI audit does not invoke the SAF picker. Raw results, hashes, fixture ZIP and available screenshots are archived here. Emulator and isolated ADB stopped; temporary userdata/keys removed. No production approval or complete-security claim.

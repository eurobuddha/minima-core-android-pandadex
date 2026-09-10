# Owner history access — audit22

724 Android assertions passed. Visual inspection found low-contrast light-theme text and dim dark secondary text. This is preserved before-fix evidence; audit24 verifies the corrected production view.

APK: `/private/tmp/pandadex-android-audit-build-22/pandadex-export-audit-22.apk`
SHA-256: `f77cd197c8166219e00cd3a4be0993d22d7efde77ae7c4905a17cd63ea5b50ff`
Package: `com.eurobuddha.pandadex.audit`; versionCode 22. Each APK uses a fresh output directory and number. Earlier APKs remain preserved; production405 is unbuilt and frozen404 unchanged.

Reuses the prior owner-history Android harness, existing SQLite/Pending/ChainReview implementations and production export path. Fixtures seed tied timestamps and a new arrival while paging: older traversal reaches all original132 records without duplication; Newest includes the arriving row. The real database export contains133 owner receipts in a seven-entry ZIP, retaining original JSON and last saved node state without adding trades or totals. The actual view exercises Older/Newer and export callback availability, plus empty/error states. Node replies and transactions are fixtures; confirmation counts in screenshots are test data.

Fresh Android16/API36 arm64 disposable emulator, exact owned QEMU/AVD/path checked before targeted commands, isolated ADB5049. No INTERNET permission, production MainActivity, NodeTransportService, user node, phone, signing or submission. Export callback wiring is source-inspected; this UI audit does not invoke the SAF picker. Raw results, hashes, fixture ZIP and available screenshots are archived here. Emulator and isolated ADB stopped; temporary userdata/keys removed. No production approval or complete-security claim.

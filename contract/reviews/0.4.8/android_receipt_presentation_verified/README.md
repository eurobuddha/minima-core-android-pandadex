# Receipt presentation — audit34

954 Android assertions pass: 808 normal, 16 before deliberate process death, 31 after restart and 99 full offline Activity checks. All production Java hashes match source 0.4.8/408 at build and execution.

Reuses audit32 with additional actual-Activity fixtures for failed cancellation controls, missing-source CANCEL/EDIT visibility on both screens, uncertain same-source action guards, unknown block-age labels and unreadable receipt storage. Receipt callbacks persist fixture intents but do not construct, sign or submit transactions. No MinimaCore is installed and the audit package has no INTERNET permission. The actual Activity and private IPC service use the audit package; production keepalive/boot/heartbeat components are absent.

APK: `/private/tmp/pandadex-android-audit-build-34/pandadex-export-audit-34.apk`; SHA256 `d20eab9382c3240f9b89b0a6652ba5573b0fe5b42b7e241462cde063adad0860`. Unique audit versionCode34 and output; earlier APKs preserved. Isolated ADB5049 commands verify the exact owned QEMU/AVD/path first. Emulator, isolated ADB, temporary user data and keys were cleaned up. No user phone or node accessed.

Screenshot hashes are in validation.json. Portrait dark-theme fixture views were inspected for receipt readability, available failed-attempt controls and explicit storage/age warnings. Audit version text is intentionally different from a production build; this is not stock-device or release-layout approval.

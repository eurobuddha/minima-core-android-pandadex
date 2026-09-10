# Audit37 — identity and connection guards

979 assertions pass: 821 normal +16 before deliberate process death +31 recovery +111 full Activity. Production Java hashes match 0.4.9/409 at build and execution.

Reuses audit35's failing reconnect fixture and the earlier offline Activity/component harness. Adds actual NodeApi IPC-queue rejection before write-marker/transport dispatch, shared connection generation and fresh identity binding, stale watcher/Activity callbacks, late covenant registration, current setup success and cancellation-batch stop checks. Service callbacks are component fixtures, not a running foreground-service/Doze test. Identity replies and readiness flags are simulated; the audit has no INTERNET permission and MinimaCore is absent. No transaction is signed or posted.

APK `/private/tmp/pandadex-android-audit-build-37/pandadex-export-audit-37.apk`; SHA256 `6d1039a484cd49b9e155c5462072f6809f7bb89e09f6509c33e3448a66be766e`. Unique audit versionCode37; prior APKs are preserved. Exact owned emulator/AVD/path checks precede isolated ADB5049 actions. Cleanup completed. Existing receipt screenshots were captured as regression artifacts; the new identity checks are programmatic, not stock-device visual approval.

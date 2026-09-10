# Archived proof integrity — audit25

749 Android assertions pass:710 normal,14 before deliberate process death and25 after restart. Reuses audit24's complete isolated harness; adds22 actual Android JSON/proof/SQLite assertions. Production Java hashes match compilation and execution.

A complete taker receipt is captured, then caller-owned inclusion coordinates, input coin, output amount and transaction state are mutated. Actual DexDb.recordTakerFill commits the original matching trade/archive coordinates and original receipt bytes. Android saved readers reject trailing objects/comments/NUL, fractional/overflow versions, oversized archive data and fractional inclusion heights. Valid trailing whitespace remains accepted.

APK `/private/tmp/pandadex-android-audit-build-25/pandadex-export-audit-25.apk`; SHA-256 `24ded0a4b8a4e0b3f3b90ef6a564e69bc8ccd261f252a64351d50da9804bede4`; package com.eurobuddha.pandadex.audit, versionCode25. Fresh output path/number. All previous APKs and frozen production404 preserved; production405 unbuilt.

Fresh disposable Android16/API36 arm64 emulator; exact owned QEMU/AVD/path checked before commands, isolated ADB5049. No INTERNET permission, production MainActivity/NodeTransportService, user phone/node, signing or submission. Fixture transaction data only. Audit24's existing production-view navigation/rendering checks rerun; this continuation does not claim a new personal screenshot inspection or full-app UI verification. Screenshots/ZIP remain in the retained temporary output path. Owned emulator and isolated ADB stopped; temporary data/keys removed.

This verifies archived-input/proof adoption boundaries, not complete reorg reconciliation, stock-device MinimaCore compatibility or production readiness.

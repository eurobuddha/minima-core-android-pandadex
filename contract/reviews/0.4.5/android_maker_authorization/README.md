# Maker authorization — audit30

**855 actual Android assertions pass**: 808 normal, 16 before deliberate process death and 31 after restart. Audit29's complete harness is retained and extended with persisted authorization revisions and actual queued MakerEngine callbacks.

The new checks verify pause/rearm and edit/restore invalidate old create/relock guards; cancellation remains available; stale preferences cannot overwrite identical restored settings; slot/receipt bookkeeping retains current authorization; malformed revisions retain original bytes and fail closed. On the real Android main looper a parked create initially passes beforePost, another config pauses and rearms identical settings, and both beforePost and onPrepared then reject the old work. No prepared intent is written, and the chain exits without further creates. The saved revision is compared across two different actual Android process IDs.

APK `/private/tmp/pandadex-android-audit-build-30/pandadex-export-audit-30.apk`, SHA-256 `3851042d5f53b83d4bcdd653096b550b1a66a5143e8a61a5a55f96293481bbff`. Package com.eurobuddha.pandadex.audit, versionCode30, fresh output directory. Earlier APKs preserved; production405 unbuilt; frozen404 unchanged. Production Java hashes match compilation and execution.

Disposable Android16/API36 arm64 emulator, exact owned QEMU/AVD/path verified before commands; isolated ADB5049 and temporary keys. No user phone/node, production MainActivity/NodeTransportService, INTERNET permission, signing or submission. Emulator/isolated ADB stopped and its data/keys deleted; APK/results retained. Existing screenshot/export artifacts were captured by the harness but were not visually re-reviewed this pass. This is not stock Samsung IPC/Doze or full Activity/service lifecycle validation.

# Android SDK reply validation — build 11

Follow-up to [SDK audit build 10](../android_sdk_transport/README.md), using a new APK/version/output directory and a new disposable Android16/API36 arm64 emulator. Build10 and all older APKs remain unchanged. Every SDK broadcast is intercepted; no user device, wallet or Minima node was accessed.

**44 Android assertions passed** against the exact production Java hashes recorded here. The added case rejects a raw NUL followed by trailing content after an otherwise valid object. Android JSONTokener can treat that raw character as end-of-input, so the parser must reject it before accepting the object. The rest of the full build10 SDK coverage also passes: authentication, real node rejection preservation, malformed replies, actual local FileProvider reads, oversize/missing files, duplicate floods, executor/backlog caps, expiry, destruction and pairing durability/partial-pair preservation.

**421 JVM tests pass**, with no failures/errors/skips; release lint has zero errors/75 warnings. The NUL case extends the existing malformed-response test. No production APK was built or overwritten; source remains0.4.5/405.

Reproduce with `build_harness.py`, a fresh output directory and **audit version12 or greater**, then run `com.eurobuddha.pandadex.audit/org.minimarex.minimaapi.SDKAndroidAudit` only on a separately chosen disposable emulator. Require `PASS 44 assertions` in the raw stream. Do not use a user node.

The build10 scope limits still apply. Executors are bounded per SDK/transport instance. A provider that ignores interruption may retain an old instance's worker during repeated owner recreation; a process-wide worker bound across that lifecycle remains open. The scheduled ten-minute callback expiry uses Handler timing and does not establish a wall-clock deadline under deep sleep. This harness does not test stock Samsung, MinimaCore broadcasts, production Messenger/service lifecycle, cross-package grants, hardware disk failures or live transactions. No production approval or complete-security claim is made.

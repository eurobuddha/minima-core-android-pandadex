# PandaDEX 0.4.8 / 408 — receipt presentation

Orders and Trade now show explicit cancellation/update receipts even when their source coins are missing. A definitely unsubmitted cancellation no longer hides its order controls; both screens share the existing guard for unresolved owner attempts. Stored receipt errors are visible in Orders. Receipt verification, retention and transaction construction remain unchanged.

Legacy owner receipts no longer promise next-block confirmation. Unknown/future local timestamps remain unavailable. Missing chain height cannot display age zero, and stale age/expiry labels identify the last observation. Trade receipt descriptions and status text use separate lines.

[Detailed review](contract/reviews/0.4.8/receipt_presentation/README.md): 665 JVM tests pass, zero failures/errors/skips; lint zero errors/74 warnings. Three pre-fix regression failures and final test evidence are retained. [Audit34](contract/reviews/0.4.8/android_receipt_presentation_verified/README.md): 954 actual Android assertions, including 99 full-Activity checks using local receipt fixtures. [Validation manifest](contract/reviews/0.4.8/validation.json) pins source and evidence hashes.

Source version increased for a separate verified local commit. Audit APK34 is separately numbered; previous APKs are preserved. No production release build, user-device/node access, push or publication. Disposable emulator/ADB/data/keys cleaned up.

Overall production readiness is not approved. The [broader open gates](SECURITY_REVIEW_0.4.5.md) and [previous checkpoint](SECURITY_REVIEW_0.4.7.md) still apply, including stock Samsung/MinimaCore IPC and lifecycle/Doze, historical evidence gaps, oracle trust and the human-only composite test.

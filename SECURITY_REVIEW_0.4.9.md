# PandaDEX 0.4.9 / 409 — verified wallet connection

Fixes a reproduced authorization gap: after reconnecting, the Activity could use its previous transaction identity before the new address arrived. Both hosts now fence setup callbacks to the current attempt/connection; the Activity reuses the watcher's keys -> address -> covenant ordering and clears stale identity/receive data.

Transaction command chains capture their original identity before asynchronous funding and are checked again at IPC dispatch. Observed loss in either host invalidates old work; new work remains blocked until a fresh identity read binds the transaction object again. Delayed edits and cancellation batches retain the connection used for the original confirmation. Amount calculations, covenant scripts and receipt retention are unchanged.

[Detailed review](contract/reviews/0.4.9/identity_session/README.md): **674 JVM tests pass**, zero failures/errors/skips; lint **zero errors/74 warnings**. [Audit37](contract/reviews/0.4.9/android_identity_final/README.md): **979 Android assertions**, including 111 full-Activity checks and the actual IPC queue guard. Audit35's failing baseline and audit36's unexecuted intermediate build are preserved. [Manifest](contract/reviews/0.4.9/validation.json) pins source, tests and artifacts.

No production release, user-device/node access, push or publication. Audit APK versions 35-37 are unique; prior APKs remain intact. Isolated emulators/ADB/data/keys cleaned up.

Production approval remains incomplete. Already-dispatched node commands require receipt recovery; an unobserved seed/wallet replacement is outside this connection-generation mechanism. Wallet affinity of stored maker settings/history, balance/book attribution, strict block parsing, stock Samsung/MinimaCore lifecycle/IPC and the [broader gates](SECURITY_REVIEW_0.4.5.md) remain open. Maker restart/auto-resume policy and the human-only composite test are unchanged.

# Reader lifecycle audit — build 12

Executed on 2026-09-10 using a new disposable Android16/API36 arm64 emulator, separate audit package/version12, isolated ADB server5049, keys and userdata. Every outgoing SDK broadcast is intercepted. The private bridge uses an in-process fake Binder service, and never binds the production NodeTransportService or a Minima node. No user phone, existing AVD, wallet or node was accessed. Earlier audit APKs and production0.4.4 remain unchanged; no production0.4.5 APK was built.

## Results

**255 Android assertions passed:** 44 retained SDK assertions, 54 new SDK-owner lifecycle assertions and 157 private-bridge assertions. **421 JVM tests pass**, no failures/errors/skips; release lint has zero errors/75 warnings. Both audit runner hashes and all production Java hashes are recorded with the APK hash. Sources matched before and after execution.

The SDK lifecycle case uses an actual same-UID ContentProvider whose `openFile` deliberately ignores interruption until a test latch is released. A second SDK owner queues a legitimate file reply behind that stalled read. Destroying the first owner and creating/destroying twenty replacements keeps one physical SDK reader and preserves the second owner's task. Each destroyed replacement removes only its queued task. Expiry removes its own queued work and reports an unknown outcome. A pairing-initialization failure cannot shut down another owner's worker. Releasing the provider delivers the live peer's reply exactly once, suppresses destroyed-owner callbacks, and permits another SDK owner after all previous owners have closed.

The private-bridge case executes actual NodeTransport Messenger request/reply handling, same-UID checking, cache-file staging/reading and registration callbacks against a local fake service. A controlled executor task holds its worker while a live peer queues a reply. Twenty replacement transports queue and destroy their own work without replacing the physical worker or losing the peer's reply. Assertions cover queue bounds, callback/read/deadline cleanup, request/reply file deletion, exactly-once peer success after release, and successful reconnection. A separate never-connected fixture verifies removal of both pending command files and connection deadlines on destruction, with a transport failure rather than node status.

## Implementation and reuse

`ExportChecks.java` and `ExportChecksTest.timedOutNoninterruptibleWorkCannotCreateReplacementNetworkThreads` supply the proven pattern: a static bounded ThreadPoolExecutor, cancellation of owned FutureTasks, and removal of cancelled queued work without creating a replacement physical worker. Relevant base SDK and PandaPools/UTXO transport candidates were inspected; their per-instance executors did not supply this lifecycle bound.

`MinimaAPI.mFileExecutor` and `NodeTransport.reader` now each have process-wide lifetime, one physical reader and a two-task queue. The SDK records each file FutureTask under its request ID before submitting it while holding the existing request lock. Expiry/destruction cancels and removes only that owner's tasks; successful delivery removes bookkeeping without interrupting its own callback. The private bridge similarly tracks/cancels its owned tasks and deletes their cache files. Connection-wait timers are tracked and removed on dispatch/completion/destruction instead of retaining a dead connection until their twenty-second delay expires. No instance shuts down the shared reader.

The SDK and private bridge have distinct executors; in production they run in their respective private/UI processes. The statement is one physical worker per reader type per process, not one thread for all application I/O. Request expiry remains an unknown outcome, and this change neither replays commands nor releases the financial-write quarantine.

## Reproduce and limits

Use `build_harness.py` with a fresh output directory and **audit version13 or greater**, then invoke `com.eurobuddha.pandadex.audit/org.minimarex.minimaapi.SDKAndroidAudit` only on an explicitly isolated disposable emulator. Require `PASS 255 assertions` in the raw stream, not only command exit status. The harness compiles both SDKAndroidAudit and BridgeLifecycleAudit.

The SDK's non-interruptible provider is real Android code under controlled same-UID conditions. The bridge worker stall is an injected executor task; it is not a blocked physical disk or Binder-driver fault. Its fake service and client are in one process, so the test does not establish cross-process death behavior, cross-package URI grants or stock MinimaCore compatibility. No timing claim is made about Samsung deep sleep, and no real disk-exhaustion/power-loss or live funds test occurred.

A provider that never returns can still deny file replies. This fix bounds retained physical work across reconnects; it does not force native/provider I/O to terminate or promise automatic availability recovery. Existing late-reply retention, private-process isolation, bounded payloads and unknown-write recovery remain necessary. Stock Samsung/MinimaCore and human live-composite release gates are still open. No complete-security or production-approval claim is made.

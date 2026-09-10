# Background watcher pairing — 2026-09-10

## Finding and fix

PandaDEX's background service supplied no pairing listener to NodeApi. Its first unpaired pass advanced lastPassMs before re-registering, then returned. Even a prompt successful registration did not resume startup; a later heartbeat/worker start was needed. This could delay unattended renewal and fill checking after a cold start.

DexKeepAliveService now resumes its existing pass through NodeApi's pairing listener. WatcherPassGate extracts the existing elapsed-time throttle and tracks registration attempts separately from actual scan starts. Pairing can immediately start the first scan, while repeated pairing/heartbeat callbacks still obey the five-minute scan interval. Foreground starts consume neither allowance. A disabled pairing invalidates the service's live key readiness. The service marks itself stopped before transport destruction; late startup/book/maker callbacks recheck that lifecycle state and foreground ownership.

This reuses the pairing-triggered startup pattern inspected in `/Users/eurobuddha/Projects/minima/apks/minimaswap/app/src/main/java/com/eurobuddha/minimaswap/SwapService.java` (onCreate, onPaired, ensureDerivations, tickPass and shutdown). It retains PandaDEX's own pass pipeline, monotonic interval, KeySet refresh, transaction gates and foreground stand-down. No swap wallet/ETH derivation or new periodic polling loop is imported. PandaPools' PoolKeepAliveService was the original sibling search candidate; the compatible pairing callback was found in minimaSwap.

The graph query `pairing service pass enabled` identified NodeApi.PairingListener, DexKeepAliveService.pass/onCreate/isEnabled, KeySet and the settlement pipeline. The graph remains pre-#1504; relationships were checked against current source. Read NodeApi's constructor/reRegister/noteEnabled and NodeTransport's callback dispatch, the complete KeySet implementation, DexWatchWorker and service callers before adapting the existing pattern.

## Validation and limits

Seven new JVM regression methods exercise the production throttle used by the service: cold registration then immediate pairing, duplicate replies/heartbeats, bounded unpaired retries, foreground handoff, startup/shutdown rejection, recent-scan reconnect and elapsed-clock rollback. **521 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors,75 warnings. git diff --check passes.

These are deterministic gate tests, not an Android Service lifecycle or successful MinimaCore IPC test. Audit17 retains its exact earlier source checkpoint and owner-receipt runtime evidence; it did not run this later watcher change. No new APK, emulator, phone or node was used. Source remains0.4.5/405, frozen404 is unchanged, and nothing was committed/pushed/published.

This fix does not guarantee Android alarm delivery, immediate recovery when the node never replies, or cancellation of already-dispatched transactions on service destruction. An unavailable node still relies on existing heartbeat/worker starts to retry registration. A recent attempted scan still respects the five-minute limit after a connection change. Key readiness retry timing, long asynchronous pass overlap, full service lifecycle/stock-device behavior and durable terminal owner-operation history remain review work. It is not production approval or a100%-security claim.

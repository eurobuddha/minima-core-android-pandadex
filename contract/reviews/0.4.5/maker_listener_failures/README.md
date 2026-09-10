# Maker display failures cannot strand financial completion

## Reproduced defect

MakerEngine invokes its advisory status listeners between durable action bookkeeping and continuation/idle handling. An onCreateSent/onCancelSent/onRelockSent/onMakerState RuntimeException could escape an asynchronous callback after its advanced latch was set. The next action or drainIdle was then skipped; the shared working flag could remain true and block both hosts. Terminal notifications could also consume a queued withdrawal by throwing before drainIdle. NodeApi's callback boundary may release its own queue, but cannot repair this Maker state.

Seven targeted regressions were run against the unchanged engine first: **all seven failed** from the deliberately throwing listeners. The baseline XML is retained. Their cases include accepted asynchronous creation, startup/terminal status, uncertain and rejected replies, cancellation across two batches, late-order cancellation and queued idle work.

## Fix and reuse

MakerEngine now delivers its four advisory event types through a small observer boundary, following the existing Notifier.alert principle that a display/notification failure must not control financial completion. Only RuntimeExceptions thrown by the listener are isolated. Bookkeeping, persistence, quote/ownership checks, transaction calls and deferred action execution remain outside that boundary. Existing once-only latches and run/drainIdle are retained; no second transaction engine, signing retry or replacement receipt is introduced.

A broken listener may fail to display that message; subsequent rendering reads retained state. Its error is not reclassified as a rejected or unknown transaction. This does not make arbitrary deferred business Runnables exception-safe or hide persistence failures.

Inspected MakerEngine's complete run, withdrawal batching, late-order sweep, stopForCancelAll, shared working/idle fields and listener callers; MainActivity.setStage/repaint and maker listener; DexTxn/NodeApi completion boundaries; and the complete Notifier alert path. Reused MakerEngineTest.StubTxn by exposing it to package-local tests, plus the existing MakerHandoffTest/MakerCancelAllTest fixture approach. Sibling searches found no PandaPools MakerEngine; minimaSwap's swap engine uses a different transaction/notifier flow, so the current PandaDEX notification boundary is the compatible building block. The graph query `MakerEngine working drainIdle Listener run` remains pre-#1504 and was checked against source.

## Verification

The seven reproduced failures now pass. Two additional tests cover reprice bookkeeping and the real stopForCancelAll handoff while a create is pending. The full suite passes **559 tests**, zero failures/errors/skips; release lint zero errors/75 warnings. Checks assert preserved slot/tombstone state, exactly-once callbacks, continued second cancellation batch, disarming on uncertain outcome, and release of queued work. Tests deliberately reset only the test process's static engine fields after each case so a failing baseline cannot poison unrelated cases.

These are production-engine tests driven by deferred transaction callbacks, not live node transactions or Android view rendering. No APK, emulator, phone or node was used. Source remains0.4.5/405, frozen404 is unchanged, and no commit/push/publish occurred. Audit18 is the earlier runtime checkpoint; it did not execute this later MakerEngine change.

Remaining work includes exceptions in non-observational deferred actions, broader transaction/host lifecycle overlap, durable terminal owner-operation history, recovery UX and storage growth, stock Samsung/MinimaCore validation and the human-only composite gate. No production-readiness or100%-security claim is made.

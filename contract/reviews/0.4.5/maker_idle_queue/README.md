# Deferred maker request loss

## Reproduced and fixed

MakerEngine.pendingOnIdle was one Runnable: a second request overwrote the first. Six regressions failed before the fix, including two stopForCancelAll callbacks losing one continuation. This could leave a user-requested action unperformed even though it had been queued in the UI.

The fix directly reuses SerialQueue, already used by SignGate and NodeApi. Each deferred action starts at an idle boundary and releases its queue ownership in finally only when no maker chain is active; if it starts another asynchronous chain, the existing drainIdle boundary supplies completion. MainActivity and service maker instances share this queue. No elapsed-time release is introduced. Existing callback exception propagation remains, while later requests are retained. Tests' static-state cleanup resets the queue after failures.

Reuse inspected: MakerEngine callers in MainActivity and stopForCancelAll/withdrawAll, SerialQueue and its tests, SignGate, NodeApi completion handling, PandaPools TxPost's FIFO/release protocol and sibling queue candidates. SerialQueue remains unchanged; only the maker idle adaptation and tests were added.

Six new tests cover FIFO arrival order, asynchronous successor ownership, nested submissions, a throwing deferred callback, repeated stop requests and two maker hosts. Full suite631 tests pass, zero failures/errors/skips; lint zero errors/75 warnings. Audit28 adds actual main-looper evidence; raw baseline/pass XML and logs retained.

## Remaining production review

Verdict: request changes for the overall release. The queue is intentionally in-process and main-thread-only, consistent with NodeApi/UI callers. It does not persist arbitrary Runnables or establish cross-process execution. Durable maker withdrawal records still carry financial recovery; a broader cancellation handoff interrupted before its own durable journal needs separate lifecycle review. Unbounded repeated user enqueueing, large synchronous nested workloads, valid cross-host settings changes during active passes and real stock-device lifecycle remain broader review items. No new automatic restart/resume policy was introduced.

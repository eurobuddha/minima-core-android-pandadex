# Cancel-all maker intent and callback handoff — 2026-09-10

**491 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. Eight new cancellation and cross-host handoff regressions are included. No new APK, emulator, user device or node was used. Source remains0.4.5/405; frozen0.4.4 remains unchanged. Prior Android audits cover their archived source checkpoints, not this new Activity/engine path.

## Findings and fixes

**HIGH — Cancel all removed maker identities before cancellation intent was durable.** MainActivity.cancelAll cleared the complete maker slot map before invoking the visible-order cancellation loop. A maker order still being submitted could therefore be absent from both that loop and the saved maker map. It also ignored the pause save result and could begin cancelling concurrently with an active maker action. An empty confirmed-book snapshot incorrectly stopped the flow even when a maker intent existed.

The cancel-all caller now uses MakerEngine.stopForCancelAll and MakerConfig.stopAndTrackWithdrawal. The latter reloads current maker state, collects accepted slot IDs and any prepared-create ID, disarms the maker, and uses the existing prepareWithdrawal serializer to commit those cancellation identities while retaining original slots/prepared intent. Failed persistence or unreadable records prevent the visible cancellation loop from starting. If a maker action is active, the existing runWhenIdle handoff waits for it and then repeats the tracking step to include any newly accepted order. A maker that starts after the confirmation dialog was opened is rechecked at acceptance.

Empty-book requests with maker settings/records now reach this tracking path. The dialog describes orders which are not yet visible, batch cancellation and the distinction between requests and confirmed returned funds. A failed/unknown batch no longer implies it must have been filled. No chain outcome is inferred from elapsed time or absence.

**HIGH — An uncertain background create reply could overwrite a newer foreground withdrawal.** The onPosted branch reloaded MakerConfig, but the ERR_WRITE_UNCERTAIN branch used the older in-memory configuration before remembering its order and disarming. It could erase cancellation identities that the foreground host had just saved. onFailed now reloads before either outcome branch, using the same read-before-update pattern as the existing successful callback. The current intent and original funded-position metadata remain tracked after an unknown result.

## Reuse and proof

Inspected and reused `MakerConfig.prepareWithdrawal`, `MakerEngine.withdrawAll`, `runWhenIdle` and the existing success callback's reload. MainActivity retains its existing cancelSequentially/cancelBatch path for visible orders. Pending's synchronized read-before-update design and tests, sibling PandaPools ActivityLog, and relevant minimaSwap persistence searches supplied comparison points; no independent cancellation or transaction-construction implementation was added. The graph maps MakerConfig, MakerEngine, foreground and service callers, but its pre-#1504 IDs and source checkpoint remain stale.

The existing MakerWithdrawalDurabilityTest.Memory seam now retains every acknowledged preference snapshot. Eight new methods cover invisible accepted slots, prepared-only records, failed initial pause/intent persistence, unreadable records, accepted background create handoff, uncertain background reply handoff, failed post-handoff tracking, and reloading another host's newer slot. The accepted and uncertain handoff cases check every successful intermediate save from the first stop onwards: maker remains disarmed and the cancellation identity is never temporarily erased. Both use actual MakerConfig serialization/reload and two distinct foreground/background configuration instances; transaction callbacks are controlled stubs.

The full491-test suite and release lint passed after the Activity caller/text changes. Actual Activity clicks/rendering, Android process termination, Samsung scheduling and live node cancellations were not executed. The JVM evidence proves the tested callback/persistence boundaries, not all runtime or on-chain behavior.

## Remaining work

This closes the identified uncertain-create stale-host overwrite and cancel-all deletion paths; it is not a general atomic compare-and-update mechanism for every valid MakerConfig snapshot. Other cross-instance updates still need review. A failed pause write can leave older enabled settings on disk after process death; the previously asked restart-policy choice remains unanswered and unchanged.

Cancellation identity tracking is not terminal chain matching or confirmed receipt history. Tombstone retirement and proven-full-fill replenishment remain open. MainActivity's manual cancel/cancelBatch pending receipt rows are still added after the accepted callback; their pre-submit receipt durability, callback persistence-failure behavior and matching should receive a separate review. The new maker tracking protects maker IDs in cancel-all, but does not retroactively supply complete original intent for every manual cancellation receipt.

Legacy maker funding reconstruction, raw corrupted-data recovery, book/ownership freshness, background pairing startup, stock Samsung/MinimaCore validation and the human-only composite gate remain open. No production-ready or100%-security claim is made.

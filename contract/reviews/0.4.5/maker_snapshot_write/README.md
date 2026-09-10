# Stale maker settings writes

## Reproduced problem

MakerConfig.canWrite validated current preferences but did not compare them to what the caller loaded. A stale instance could overwrite valid newer data. Five of seven new regressions failed: acknowledged pause, slots/withdrawal identities, another prepared create and newer price settings could be replaced. The other two compatibility checks already passed.

## Change and reuse

The writer now keeps a snapshot of its owned preference keys. A process-wide lock covers coherent load, compare, validation and commit, following Pending's STORE_LOCK and the existing receipt snapshot checks. Both full save and prepared-create writes compare the current owned values with the loaded snapshot before editing. Invalid data retains the prior unreadable-data behavior; valid-but-newer data remains untouched and produces a settings-conflict guard. Unknown/unrelated preference keys are excluded from comparison and preserved.

An instance advances its own baseline after a commit attempt, accounting for Android commit failures changing visible memory despite no durable acknowledgement. Quoting remains paused by the existing failure guard; a successful explicit save is still required to clear it. Prepared writes update the baseline without falsely claiming a failed prepare succeeded. Normal reload does not clear the failure guard. UI status and publish/withdraw failure feedback distinguish settings changes from storage problems.

Inspected/reused sources: MakerConfig serialization/validation and MakerConfigRecoveryTest/MakerWithdrawalDurabilityTest; Pending's locked intent comparison; DexDb snapshot checks; PandaPools ActivityLog's synchronized read-before-write pattern. No separate preferences writer exists in current production sources. No new dependency/schema or restart policy.

Seven new JVM tests cover stale data preservation, explicit recovery, own-write/failed-commit retry and unrelated fields. Full638 tests pass; zero failures/errors/skips, lint zero errors/75 warnings. Audit29 adds actual preferences and simultaneous-writer evidence.

## Review / limits

Overall production verdict remains request changes. The guard protects same-process writers using MakerConfig; it is not disk authentication, multi-process synchronization, or a durable restart-policy change. A settings conflict pauses quoting in the current process through the existing failure latch. UI recovery/unsaved-edit behavior under full Activity/service lifecycle still needs stock-device exercise. Readable malformed semantic relationships, older missing receipt reconstruction, long-running transaction host transitions, and the human-only composite gate remain broader work. A byte-identical change-and-restore is intentionally indistinguishable from unchanged settings; detecting cancelled-and-rearmed operation generations requires separate persisted operation-generation policy.

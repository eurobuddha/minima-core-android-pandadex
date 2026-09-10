# Maker authorization across pause/rearm

## Code Review

### Summary

The snapshot comparison prevented overwriting different current settings, but could not distinguish an unchanged authorization from a pause/rearm or edit/restore cycle. Queued maker actions compared only the final armed flag and quote values. A saved revision now distinguishes those histories, using the existing quote comparison and locked preference writer.

### Findings

#### MAJOR — Fixed: restored settings revived obsolete queued authorization

**Files:** `app/src/main/java/com/eurobuddha/pandadex/MakerQuoteGuard.java`, `MakerConfig.java`.

A create/relock queued before an acknowledged pause became eligible again after identical settings were re-enabled. Changing and restoring a quote had the same effect. A stale settings writer also passed the value-only snapshot check once the values returned to its original snapshot. This could submit work belonging to an earlier authorization.

MakerConfig now stores a UUID revision in the same acknowledged preference write as changes to armed status or economic quote configuration. MakerQuoteGuard captures and compares it at each existing action, preparation and posting boundary. The revision participates in the existing owned-key snapshot comparison. It survives reload/process death, retains the current Android failed-commit memory/failure-latch behavior, and rejects malformed stored values without overwriting original data. Legacy settings without the key remain readable and receive a revision on their next authorization change. New guards after a deliberate rearm use the new revision.

Normal slot/tombstone/receipt bookkeeping does not rotate the revision. Numerically equivalent decimal formatting does not rotate it. Cancellation keeps its existing independence from price/quote settings. No automatic restart/resume policy was changed, and this cannot retract a transaction already submitted to the node.

### Reuse and validation

Inspected PandaDEX MakerConfig, MakerQuoteGuard, MakerEngine callbacks, MakerTab/MainActivity save callers, KeySet's obsolete-callback identity checks, ChainEvidence's UUID epoch, MakerEngineTest's deferred transaction fixture and MakerWithdrawalDurabilityTest/ MakerSnapshotWriteTest. Sibling PandaPools callback/generation search did not find a persistent maker authorization implementation. KeySet tokens are per-instance in-memory request identities; ChainEvidence's epoch is process-wide proof ordering. Neither can directly identify saved maker edits shared across hosts. The minimum adaptation adds one stored string and reuses MakerQuoteGuard.same unchanged, existing typed preference readers/validation, snapshot locking, and the existing UUID generator pattern. No dependency or database schema change.

Seven new JVM tests: six failed before the change (five authorization/legacy behavior cases plus the new malformed-revision requirement); all now pass. They cover pause/rearm, edit/restore, stale writers, equivalent numbers/bookkeeping, failed pause followed by explicit rearm, legacy data and invalid native/string types. Full645 JVM tests pass, zero failures/errors/skips; release lint zero errors/75 warnings. Audit30 additionally exercises real preferences, actual MakerEngine callbacks on the Android main looper and persistence across deliberate process death.

### Verdict

Overall production verdict: request changes. This defect is fixed within the tested same-process MakerConfig writer and callback boundaries. It is not authenticated storage, rollback protection against external file replacement, multi-process SharedPreferences coordination, or approval for production trading. Full Activity/service/stock Samsung lifecycle and the existing human-only composite gate remain open. Unsaved transient field changes do not create an acknowledged revision; current value checks still reject a presently different configuration. The test-only in-memory MakerConfig constructor retains its documented no-op save behavior.

# Maker prepared-intent ownership

## Code Review

### Summary

This follow-up to the local WIP checkpoint hardens the maker journal and callback boundary. It reuses Pending's identity-scoped cleanup and completed-callback rejection. Existing transaction-layer guards already reject completed callbacks; the maker now enforces those rules itself and cannot replace an unresolved prepared intent through a fresh MakerConfig writer.

### Findings

#### MAJOR — Fixed: a valid existing prepared create could be replaced or cleared by another intent

**Files:** `app/src/main/java/com/eurobuddha/pandadex/MakerConfig.java`, `MakerEngine.java`.

MakerConfig.prepareCreate compared the loaded preference snapshot but allowed a fresh writer to replace an existing acknowledged create. MakerEngine's create callbacks also cleared preparedCreate regardless of the recorded order ID. At these boundaries, a callback for one order could remove another order's recovery record.

Preparation now refuses any occupied journal. A failure clears only its matching order ID. Acceptance clears only its matching ID and records the accepted slot in the same existing save, preserving a different prepared record. No extra persistence step is introduced between clearing the matched intent and storing its accepted slot. Failures before preparation avoid an unnecessary full settings write.

#### MINOR — Fixed: completed maker callbacks could prepare or authorize again

MakerEngine's completion latch protected onPosted/onFailed, but not onPrepared/beforePost. Both now reject calls after the action has completed, matching Pending.intentResult's existing finished guard.

### Reuse and validation

Inspected current MakerConfig storage, MakerEngine action/withdrawal paths, all preparedCreate callers, DexTxn's create/submit wrappers, Pending.intentResult/phases/complete, MakerHandoffTest, MakerListenerFailureTest, MakerWithdrawalDurabilityTest and MakerSnapshotWriteTest. Inspected sibling PandaPools ActivityLog's synchronized identity-matched updates. Reused the existing preparedOrderId accessor, completion latch, snapshot-checked persistence and deferred DexTxn/preference fault-injection fixtures. No new library, preferences key or database migration.

The corrected seven-test baseline has four failures: occupied-journal replacement, a completed callback accepting more work, unrelated-intent cleanup on failure, and unrelated-intent cleanup on acceptance. Three compatibility cases already pass: matching failure cleanup, atomic intent-to-slot replacement, and identity survival after a failed commit. All seven now pass; the entire652-test JVM suite passes with zero failures/errors/skips. Release lint has zero errors/75 warnings. git diff --check passes.

The initial test-development run had six failures because two assertions mistakenly assumed a single save and no safe later bookkeeping retry. They were corrected before production changes to inspect durable identity preservation; the initial log is retained for transparency. The four-defect baseline and final XML/logs are separate.

### Verdict and limits

Approve this bounded defensive fix. Overall production readiness remains incomplete. These tests deliberately inject competing journal state and late callbacks through the component APIs. They do not demonstrate that two production MakerEngine hosts currently overlap transaction submission: the existing process-wide working gate and Pending wrapper already prevent that normal path. Source0.4.6/406, no APK packaged or installed for this follow-up. Audit30's855 Android assertions apply to the prior0.4.5 snapshot, not this new source. Stock Samsung lifecycle/IPC, full Activity/service recovery and the human-only composite interoperability gate remain unverified here. A future change involving competing accepted slots needs a separate identity-preserving policy; this patch addresses prepared-journal ownership only.

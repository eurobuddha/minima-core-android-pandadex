# Receipt-aware automatic renewal and expired refunds — 2026-09-10

## Findings and fixes

**Automatic retries ignored durable unresolved receipts.** DexProcessor used a six-block preferences marker and cleared it on any failure callback or book absence. After a lost reply, the marker could be gone while the new shared Pending journal still recorded UNKNOWN/POSTING/SUBMITTED. Further scans could spend more signing/PoW effort on the same input and accumulate conflicting attempts. Global signing/coin locks constrain simultaneous activity, but do not make a later retry an informed decision about the earlier outcome.

Pending.unresolvedOwnerCoins now reads the durable journal under its existing store lock and supplies unresolved cancellation/edit source identities to automatic upkeep. All owner phases except exact NOT_SUBMITTED hold that source, including legacy phase-less receipts and PREPARED intent. Source IDs compare case-insensitively. An unreadable journal or malformed unresolved owner source fails closed. Processor pacing-marker age and temporary book absence can still retire hints, but cannot erase the receipt or bypass its source hold. Other eligible source coins retain the existing two-renewal/two-refund per-pass limits, ownership checks and maker skip IDs.

**Expired automatic refunds lacked the shared pre-submission journal.** The only current collectExpired caller is DexProcessor's owned-order path. DexTxn.collectExpired now reuses postCancellation with its singleton source after building the existing COINAGE refund transaction. This uses the exact original source/refund proof and acknowledged PREPARED/POSTING journal already used by owner cancellation. A lost reply retains UNKNOWN and blocks another automatic attempt; missing receipt storage refuses the real entry point before node access. Covenant layout, output amount/address/token, signature and transaction gates are unchanged.

**Pacing-marker storage failures could be ignored or crash callbacks.** Marker writes/removals now require commit acknowledgement. Loading validates the existing string block/hex coin format into a temporary map rather than silently dropping malformed fields. Failed reads/writes pause processing and retain raw data; a failed mark prevents that transaction dispatch. Callback removal failures are caught and reported, while the durable transaction receipt remains authoritative. Startup no longer reads marker data outside the guarded process path.

A deduplicated onPaused callback feeds the foreground stage and background notification. Failure messages no longer promise an automatic retry when receipt checks may be holding it. Manual user cancellation/recovery paths are not disabled by this automatic-upkeep source hold.

## Proven implementations inspected/reused

- Complete current DexProcessor.java and its MainActivity/DexKeepAliveService callers, ownership and work-limit gates.
- Pending's load/store lock, rows, cancellationResult/relockResult, shared intentResult phase journal and linked-spend reconciliation. New unresolved-source lookup reuses that store directly; no competing transaction journal is invented.
- DexTxn.cancel/postCancellation/relock/collectExpired and postGated's existing receipt boundary. The expiry refund now calls the same cancellation helper; it retains its original transaction builder.
- Limit's LimitProcessor.java: inspected renewal start/advance/finish, persisted-state reload, retry/collection gates and LimitService caller. Its cancel-then-recreate protocol differs from PandaDEX's atomic V5 relock, so it is not imported. The current PandaDEX journal is the compatible implementation.
- MakerWithdrawalDurabilityTest.Memory and PendingRecoveryTest.Memory supply acknowledged preference/receipt fixtures, and existing cancellation/relock proof tests remain in the full suite.
- Graph query `DexProcessor inflight process renewal Pending relock`; the pre-#1504 graph remains stale, so current callers/source were authoritative.

## Validation

Eleven new tests exercise the production processor with the real Pending serializer/phase callbacks: lost renewal reply beyond six blocks and restart; accepted receipt surviving temporary book absence; known unsubmitted retry; pending cancellation blocking renewal and expiry refund; unknown expired refund after restart; failed marker commit; failed callback marker removal; corrupt receipt/marker preservation; legacy and malformed owner sources; two-action cap and both ownership factors/maker exclusion; real collectExpired entry-point refusal without receipt storage.

The fake transaction driver deliberately controls acceptance/loss and invokes the same receipt callbacks, but performs no signing or node IPC. Preferences use the existing failure-injection proxy, not real Android disk timing. **550 JVM tests pass**, zero failures/errors/skips; release lint zero errors/75 warnings. git diff --check passes. Current source/test hashes are refreshed. No APK, emulator, phone or node was used; source remains0.4.5/405 and frozen404 is unchanged. No commit/push/publish occurred.

## Limits and remaining scope

An unresolved receipt may hold automatic upkeep indefinitely until linked evidence or deliberate user recovery settles the source. This is preferable to interpreting elapsed blocks as rejection, but production recovery/terminal-history UX remains required. Legacy attempts without receipts cannot be reconstructed by this change. Manual maker/cancellation policy is separate; the new hold applies to DexProcessor automation.

The existing source snapshot/phase journal is not a general cross-process compare-and-swap scheduler. Marker cleanup is a pacing hint, not confirmation. Broader valid MakerConfig host races, long asynchronous pass overlap, callback-side UI/database failures, receipt growth and terminal owner-operation archival remain open. Actual Android storage/lifecycle, stock Samsung/MinimaCore behavior and human-only composite gates remain unexecuted. Audit17 predates these Pending/DexTxn changes and is not current-source runtime proof. No production-readiness or100%-security claim is made.

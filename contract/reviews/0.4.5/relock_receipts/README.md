# Exact durable owner-relock receipts — 2026-09-10

**514 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. Nine new regressions cover exact replacement amount, renewal and recovery. No APK, emulator, user phone or node was used. Source remains0.4.5/405; frozen0.4.4 is unchanged. Earlier Android audits do not provide runtime coverage of this new owner-relock journal.

## Findings and fixes

**HIGH — Price-edit and renewal receipts were not durable before submission.** MainActivity created edit receipts after node acceptance, including its maker listener. A lost reply/process death or a background GTC renewal could leave no receipt. DexTxn.relock now attaches Pending.relockResult before the existing postGated signing path. This covers manual edits, maker reprices and DexProcessor renewals through their existing common entry point. Creation/cancellation's acknowledged shared intentResult provides PREPARED before signing and POSTING before txnpost. Required storage failure refuses the next step, unknown outcomes retain evidence, and late callbacks cannot resurrect resolved rows. Duplicate UI-only inserts are removed.

**Exact replacement amount, not rounded display price, is the modern proof.** A new receipt saves the original effective source snapshot and the exact requested STATE(2) amount after the builder's validation. For renewal, that amount is the unchanged original want. DexHistory.relockSuccessor still verifies the included original input, index-matched successor, owner, payout, tokens, locked amount and preserved covenant state. The saved source adds original-economics comparison. Modern editMatches compares the successor's wanted amount directly with editWant; legacy price-only receipts retain their existing conversion path.

This avoids a round-trip error where source size multiplied by the rounded displayed price yields a different token-grain amount. A valid very small buy price can also round to zero at display precision, so modern verification does not require a positive displayed price when the exact wanted amount is valid. These tests do not change the general UI price-formatting policy.

Incomplete modern source/amount pairs cannot fall back to legacy matching, including when a competing refund is found. The original record stays unresolved. A verified unchanged-amount relock is described as an order renewal, rather than a price update; refund messages distinguish renewal and repricing. Source snapshots reuse cancellation's compact effective-state encoding, excluding unrelated metadata. The JSON preference format is extended with optional editSource/editWant fields; no database schema change is needed.

## Proven implementation reused

- Pending.intentResult from the previous creation/cancellation hardening supplies the acknowledged journal, phase transitions and callback ownership unchanged.
- Pending's compact cancellation source encoder and source comparison are now shared with owner edits; no independent financial snapshot format is invented.
- DexTxn.relock's existing exact newWant computation/validation, state layout, signature, txncheck, SignGate and CoinLock remain the transaction implementation.
- DexHistory.relockSuccessor and legacy Pending.editMatches supply the existing linked-output verification; only modern expected-amount selection and source pinning are added.
- Inspected MainActivity's edit callback/maker listener, MakerEngine relock call, DexProcessor automatic renewal call and service/foreground listeners. The graph led to those relationships but remains an older pre-#1504 graph. Relevant sibling owner-operation searches found no more compatible receipt implementation than this shared native journal.

## Tests

Nine new methods cover sell price-round-trip loss, a buy whose displayed price rounds to zero, process-restart simulation after a lost reply followed by exact included-successor recovery, unchanged-want renewal, incomplete modern fields, changed original funding/unconfirmed successor, failed prepare/posting writes, real edit/renewal entry points refusing missing receipt storage before node access, and preservation of partial modern intent against a competing refund. Existing cancellation journal, creation, maker and legacy edit proof tests continue to pass.

History/transaction callbacks are controlled JVM fixtures. These tests invoke the production receipt serializer/reconciler but do not execute live signing, actual Android process death, successful end-to-end MinimaCore IPC, Samsung scheduling or Activity rendering. Saved XML and current source/test hashes identify the scope.

## Remaining work

Central durable receipts now cover creation, owner cancellation and owner relock. Third-party/expired collection, funding splits and taker/composite flows have their own paths and must not be assumed covered by this change. DexProcessor's retry/in-flight policy, other callback-side database/UI failures, whole-store malformed-row handling, growth limits and raw recovery tooling remain review topics.

Completed owner receipts are still removed after their linked outcomes are verified; durable terminal owner-operation history and competing-attempt attribution/retirement need further work. The modern proof cannot reconstruct missing legacy expectations. General valid-snapshot maker races, failed-pause restart policy (question still unanswered), book/ownership freshness, background pairing startup and stock Samsung/MinimaCore validation remain open. The human-only composite gate is unexecuted. No production-ready or100%-security claim is made.

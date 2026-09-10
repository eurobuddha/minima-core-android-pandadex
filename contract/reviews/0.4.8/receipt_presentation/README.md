# Receipt visibility and evidence-qualified status

## Code Review

### Summary

Orders previously omitted CANCEL and EDIT receipts, including failed attempts and receipts whose source coins were absent. Every cancellation receipt also hid its source order's controls, even when NOT_SUBMITTED proved that no transaction was sent. Both order screens now render all durable receipts with explicit action descriptions and share the existing owner-intent guard. Receipt retention and on-chain verification are unchanged.

### Findings

#### MAJOR — Fixed: failed cancellation hid its receipt and disabled order controls

**Files:** OrdersTab.java, TradeView.java, Pending.java.

Orders now renders every receipt independently of source-book presence, including cancellation, update and storage-error rows. Both screens use Pending.unresolvedOwnerCoins(List), extracted without changing the instance method's validation, normalization or phase policy. NOT_SUBMITTED alone cannot hold controls; any other unresolved CANCEL/EDIT for the same case-normalized coin still does. The store-based spending guard continues to fail closed on corrupt data. UI placeholders show the storage error, and existing ready() checks continue to block spending. Trade now lays receipt details beneath their action description so long evidence text does not consume the description's width. These are presentation and affordance changes, not new signing authorization.

#### MINOR — Fixed: legacy receipts promised the next block without submission evidence

**File:** Pending.java.

Legacy CANCEL/EDIT receipts now retain an unknown-submission label immediately and indefinitely. Only persisted SUBMITTED says the node accepted submission; POSTING says submission was requested. No phase or elapsed time is promoted to confirmation. Missing/future local timestamps display time unavailable instead of an invented elapsed age. General text says verification is pending, which does not imply an active network request while offline. Existing PREPARED, NOT_SUBMITTED and missing-creation-evidence explanations remain intact.

#### MINOR — Fixed: unknown or cached tip produced an unqualified order age

**Files:** Order5.java, OrdersTab.java, TradeView.java.

Unknown creation/tip or a tip preceding creation displays age unavailable. A known equal tip still displays a legitimate zero-block age. Known ages and expired labels are qualified at last check unless makerBookReady reports a current observation. Numeric age, expiry and renewal calculations are unchanged.

### Reuse and evidence

Read and reused PandaDEX Pending.Row/status/unresolvedOwnerCoins, durable cancellation/relock callbacks, OrdersTab.renderOpen, TradeView.renderOrders, Order5.age/expired and MainActivity owner entrypoints. Inspected CancellationReceiptTest, PendingRecoveryTest, PendingIntegrityTest and the audit32 full-Activity fixtures. Sibling `/Users/eurobuddha/Projects/minima/apks/limit/app/src/main/java/com/eurobuddha/limit/NewOrderView.java` clears temporary cancellation flags on failure, but its transient sets and cancel/re-place edit path cannot replace PandaDEX's durable receipt policy. No new dependency or schema.

Three pre-fix status regression failures are preserved (including legacy wording consistency); seven new JVM methods now pass. Entire JVM suite: 665 tests, zero failures/errors/skips. Lint: zero errors/74 warnings. Android audit34: 954 assertions, including 99 full offline Activity checks. It exercises real persisted failed/uncertain intent callbacks, missing-source cancellation/update receipts, both screens' action controls, unknown-age display and corrupt-store warnings. These are disposable local fixtures, not real node submissions or chain confirmations.

### Verdict

Approve this bounded fix. Production readiness remains incomplete; stock Samsung/MinimaCore IPC, lifecycle/Doze, missing legacy evidence, oracle trust and the human-only composite gate remain open. The failed audit33 lifecycle-timing assertion is retained separately; audit34 waits for actual Activity destruction before checking the foreground flag. No production lifecycle code was changed. No production release build, push or publication. Source 0.4.8/408 accompanies this separate verified commit.

Archived text logs have trailing whitespace normalized for git checks; original raw logs remain in the audit output directories under /private/tmp.

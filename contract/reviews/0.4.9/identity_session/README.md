# Verified wallet connection and queued transactions

## Code Review

### Summary

PandaDEX retained its previous transaction identity across pairing loss. The real Activity could become ready again when fresh-key and covenant flags arrived before the new address. Audit35 reproduces that authorization gap on unchanged 0.4.8 source using controlled ordering of local observations. No funds were sent. Inspection of DexTxn showed that transaction outputs use its saved payout identity while funding is read asynchronously from the node, so mixing connections could use the wrong wallet destination. This funding impact is an inference from construction code, not a demonstrated on-chain loss.

### Findings

#### MAJOR — Fixed: previous wallet identity could authorize a new pairing

**Files:** MainActivity.java, DexKeepAliveService.java, DexTxn.java.

The Activity now follows the existing watcher's complete-keys -> address -> covenant setup sequence. A pairing change clears its transaction identity and receive address, invalidates live key ownership and creates a new attempt token. Readiness requires that attempt and its observed connection to remain current. Address/covenant replies and retry callbacks from older attempts cannot replace current state. The watcher likewise clears identity on pairing notifications and fences address, block, book and covenant callbacks to its current pass and connection. Valid current setup still advances normally; cached order records remain available for display.

#### MAJOR — Fixed: queued transaction steps were not bound to their original connection

**Files:** CommandSession.java, DexTxn.java, CmdChain.java, NodeApi.java.

All eight transaction entry paths (create, split, sweep, composite, cancel, batch cancel, relock and expiry collection) capture an identity-generation snapshot before asynchronous funding/construction. The existing command chain uses that snapshot through preparation, signing, txncheck, txnpost and cleanup. The actual process-wide IPC queue checks the guard again immediately before creating a write marker or handing the command to transport. A rejected step releases queue/input ownership through the existing completion paths. Cleanup cannot delete an old transaction handle on a changed connection. Already-dispatched replies still reach their receipt handlers; the guard does not discard accepted submission evidence or claim to undo a sent request.

Observed connection loss in either NodeApi host changes a shared in-process generation. A transaction object remains bound to its previously verified generation, so even newly captured work is refused until a fresh valid identity read binds it again. Same-identity routine refresh does not invalidate current work; changing away and back cannot revive an older snapshot. On a later successful reply, another host notices the changed generation and repeats its pairing setup. Explicit registration also invalidates the prior ready state.

#### MAJOR — Fixed: delayed owner confirmations and later cancellation batches lacked the original pairing check

**File:** MainActivity.java.

Edit confirmation rechecks the original pairing, full ready() guard and ownership. Cancel-all confirmation and its queued start recheck the original pairing. Every subsequent batch retains that same token; a changed connection stops remaining batches, clears busy state and directs the user to existing receipts. It does not silently authorize the next batch using a new identity snapshot.

### Proven-code reuse

Inspected/reused current `KeySet.java` and `KeySetRecoveryTest.java` obsolete-load/retained-cache rules, `DexKeepAliveService.java` setup sequencing, `WatcherPassGate.java`, `MainActivity.java` readiness and owner actions, `DexTxn.java`, `CmdChain.java`, `NodeApi.java`, `SerialQueue.java`, `Pending.java` receipt transitions, and `CoinLock.java` completion. Sibling Limit `MainActivity.java` and `LimitService.java` retain an older unscoped identity loader and cannot provide the needed connection fence. Sibling PandaPools/UTXO searches did not provide a directly compatible generation-bound native transaction queue. `CommandSession` is the small adaptation of KeySet's token guard to the existing FundingCoins.Command seam, plus a live predicate passed into NodeApi's existing dispatch queue; no new dependency, persistence schema or transaction-construction algorithm.

### Validation

674 JVM tests pass with zero failures/errors/skips, including nine new CommandSession regressions for current/obsolete snapshots, queued dispatch, cross-host loss, binding after a fresh read, ABA identity changes, command-chain continuation/cleanup and fail-closed guard errors. Lint: zero errors/74 warnings.

Audit35 preserves the failing real-Activity readiness baseline. Audit36 is a preserved intermediate APK that was compiled but never installed/executed: review found the additional binding and batch requirements before it ran. Audit37 passes 979 Android assertions: 821 normal, 16 before deliberate process death, 31 recovery, 111 full Activity. Its real NodeApi/SerialQueue test queues a guarded sign request, invalidates permission before dispatch, and proves rejection without a write marker. It exercises cross-host reinitialization, stale Activity/watcher address callbacks, late covenant registration, successful current setup, obsolete cancellation continuation and the original reconnect baseline.

The watcher fixture constructs the production service class and invokes its callbacks with controlled fields; it does not start the foreground service or validate Doze. Scripted identity replies and readiness flags are local fixtures. No real node command was used to sign or submit a transaction. Audit APKs 35-37 are unique and preserved; disposable emulators, isolated ADB5049, data and keys are cleaned up. No user phone/node, production release build, push or publication.

### Verdict and remaining limits

Approve this bounded fix; overall production readiness is not approved. A command already handed to transport/node cannot be revoked by this guard. Interrupted-write recovery and receipts remain authoritative for its outcome. The generation detects observed enable/connection transitions, not an undetectable wallet/seed replacement that produces no signal; it is not a cryptographic node identity. Stored maker configuration/receipt affinity across different wallets, old balance/book observation attribution, strict block parsing and stock Samsung lifecycle/IPC still need review. Existing watcher throttling may delay reinitialization until its next pass. No change was made to the unresolved user preference about maker restart/auto-resume, or to the human-only composite gate. The graph is still stale; current source and tests are the evidence for this review.

# Remove unused cancellation callback writes — 2026-09-10

## Finding and minimal fix

MainActivity's cancelSequentially acceptance callback deleted each source from DexDb.myorder before continuing to the next chunk. Single cancellation and maker-cancellation notifications also called forgetMyOrder before their UI refresh. SQLite delete can throw; after a node acceptance, that exception could skip the batch continuation or the intended UI update. The batch had already set busy=true. This was an unnecessary post-submission failure point.

Repository search found no callers of DexDb.myOrders or rememberMyOrder. The prior per-book writer is already intentionally disabled, with a source comment documenting that the myorder recovery path was never connected. Current recovery uses the shared Pending journal and linked chain proofs, not this legacy table. Removed the three forgetMyOrder call sites and the now-empty per-chunk loop. The schema, stored legacy rows and database API remain intact. No replacement storage, catch-and-ignore write or financial workflow is introduced.

The existing Pending journal already acknowledges intent before signing and POSTING before submission; acceptance handling retains the receipt before calling the UI. That proven workflow remains unchanged. Single cancellation, batch continuation and maker notification retain their original status/refresh behavior without the unused delete.

## Inspection and reuse

Read current MainActivity.cancelAll/cancelSequentially/makerListener/cancelOrder, DexDb's complete legacy reader/writer/deleter, Pending.intentResult, DexTxn's gated callback/cleanup, and NodeApi's callback-exception boundary. Searches of app/src confirmed only these three delete callers and no reader/writer callers. Limit's MainActivity cancellation/edit searches and accessor/delegation path provide no compatible need for this obsolete table; retain PandaDEX's already-tested native journal. No new implementation is required.

Graph query `MainActivity cancelOrder forgetMyOrder cancelSequentially` located the relationships; the graph remains pre-#1504, so current source/call searches were authoritative.

## Validation and limits

**550 JVM tests pass**, zero failures/errors/skips; release lint zero errors/75 warnings. The existing cancellation/relock journal and maker tests remain green. No new test was added merely to mirror removal of an unused call. Source inspection confirms the removed database operation can no longer interrupt these callbacks. This is not a fault-injected Android Activity lifecycle test and does not establish that every other UI callback is exception-safe.

No APK, emulator, phone or node was used. Source remains0.4.5/405 and frozen404 is unchanged; no commit/push/publish occurred. Audit18 retains the prior exact Android source checkpoint. The current change affects MainActivity only, which audit18 did not instantiate; its tested journal/upkeep classes are unchanged. Raw prior runtime evidence is preserved rather than presented as an Android execution of this edit.

Remaining callback review includes exceptions from repaint/notification listeners and maker continuation bookkeeping, along with long pass/host concurrency, durable terminal owner-operation history, recovery UX, storage growth and stock-device/human-only production gates. No production-readiness or100%-security claim is made.

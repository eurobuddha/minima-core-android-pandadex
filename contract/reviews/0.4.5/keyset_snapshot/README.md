# Ownership snapshot invalidation and watcher startup — 2026-09-10

## Findings and fixes

**HIGH — An obsolete wallet-load callback could restore readiness after invalidation.** KeySet.invalidate previously changed only fresh=false. An address-derivation callback already in flight could finish afterward, set fresh=true and notify the host. The same issue could let obsolete errors interfere with a newer load. Each load now has a private identity; every key/address reply and error must still own the active load. Invalidation and close discard that identity and pending retry identity before cancelling queued work. Already-dispatched commands are not replayed or cancelled, but their obsolete results cannot authorize ownership or mutate the snapshot.

**Partial derivation replaced one ownership factor early.** The loader cleared/replaced keys and cached them before deriving all addresses. A later failure left new public keys alongside prior addresses. Keys and addresses now remain staged until every address is validated. A successful completion caches both factors in one SharedPreferences editor and publishes both in-memory sets together, then notifies the listener. A failed load preserves the last complete loader snapshot and leaves ready=false. Refresh also clears readiness while its current evidence is being checked. Existing getaddress extras remain explicit display/identity additions; they do not independently make the loader ready.

**Derivation retries never reached their intended longer delays.** A successful keys reply reset attempt=0 even if address derivation repeatedly failed. Attempt now resets only after the complete load succeeds; failures progress through10s,30s,90s,300s, capped without integer overflow. Manual refresh invalidates a scheduled retry; already-queued cancelled tasks cannot launch redundant work.

**The cold background scan could outrun wallet derivation.** The service previously started getaddress/block/book alongside key loading, then skipped maintenance if the book finished before all wallet addresses. Its null KeySet listener never resumed that pass. The service now uses KeySet's existing completion listener to begin its identity/block/book pipeline. WatcherPassGate consumes that continuation once per eligible pass; disconnect, foreground handoff and shutdown cannot launch it. Asynchronous watcher stages also require the node enabled and complete live key readiness. A new block/book read follows readiness; no old skipped book is replayed.

## Proven code inspected and reused

- Current PandaDEX KeySet.java: existing complete key enumeration, FundingCoins-style standard-address derivation, two-factor owns/ready distinction, preferences cache and backoff loader.
- FundingCoins.java: inspected keys parsing and nextKey/runscript derivation, validation and callers. Reused its existing Command interface for deterministic loader tests; no new node protocol or address derivation is introduced.
- PoolLiquidityRepository.java: inspected the complete implementation; reused its existing scheduler interface and production Handler adapter pattern.
- MainActivity.java: constructor/pairing callbacks, invalidate/refresh calls and ready-based maker/settlement ownership. DexKeepAliveService.java: pass, pairing, key listener, scan, maker and lifecycle callbacks. WatcherPassGate and prior watcher tests supply the existing elapsed-time/stand-down gate.
- `/Users/eurobuddha/Projects/minima/apks/limit/app/src/main/java/com/eurobuddha/limit/KeySet.java`: read the complete named donor. Its cache-only readiness and key-only ownership are less strict than PandaDEX's current two-factor live-readiness boundary; retain PandaDEX's existing hardened implementation rather than importing those older semantics.
- MakerWithdrawalDurabilityTest.Memory supplies the preference fixture. The new tests adapt cache apply to that fixture's commit; they do not claim real Android apply/disk timing.

Graph query: `KeySet refresh ready deriveAddresses service scanBook`. The pre-#1504 graph remains stale; current source verified the relationships. No claim that the new gate/test files are indexed.

## Validation

Twelve new JVM methods exercise the production KeySet serializer/loader with queued command replies and scheduler tasks: cache-only startup; atomic two-address publication; invalidation before keys or during address derivation; obsolete errors over a new load; second-address failure and late success; full capped backoff; unchanged verified-key optimization versus required rederivation after invalidation; manual refresh/invalidation cancelling stale retries; close; malformed/empty replies; actual key-completion callback resuming the production watcher gate once; foreground/disconnect rejection.

**533 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. git diff --check passes. Current source/test/resource hashes are recorded in validation.json. No production APK, audit APK, emulator, phone or node was used. Source remains0.4.5/405, frozen404 is unchanged, and no commit/push/publish occurred. Audit17 is the earlier owner-receipt Android checkpoint; these KeySet/service changes were not executed by that APK.

## Remaining scope

Deterministic callbacks and a preference proxy do not establish Android Service lifecycle, SharedPreferences apply timing, main-thread/Doze wake scheduling, real MinimaCore IPC or Samsung behavior. Very slow key derivation can outlive the existing three-minute timed wakelock. Disconnects still respect the prior scan pacing; an unavailable node relies on existing heartbeat/worker retries. Long asynchronous book/transaction overlap and lifecycle handoff after a pass has entered the transaction pipeline need further review. This change does not cancel submitted writes.

Malformed preference native types, general cache recovery, cardinality/runtime bounds for unusually large key sets, UI cached-order ownership invalidation, valid MakerConfig cross-instance races, durable terminal owner-operation history, failed-pause restart policy and human-only composite/stock-device gates remain outstanding. No production-readiness or100%-security claim is made.

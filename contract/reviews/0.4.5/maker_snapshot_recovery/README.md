# Maker configuration corruption recovery — 2026-09-10

**483 JVM tests pass**, zero failures/errors/skips. Release lint: zero errors/75 warnings. Ten new regression methods cover stored and outgoing maker configuration corruption. No APK or emulator was built/launched; no user node or phone was accessed. Source remains0.4.5/405, the frozen0.4.4 APK is unchanged, and earlier Android audit evidence applies to its own recorded source checkpoints.

## Findings and fixes

**HIGH — Silent decoding loss could orphan orders and permit replacement funding.** MakerConfig previously cleared live collections and ignored malformed JSON or malformed records while loading. It could therefore interpret a damaged slot map as an empty ladder, continue from a partially decoded map, and later save over the remaining evidence. Corrupt cancellation records could disappear the same way. Native preference type mismatches could throw during construction/reload.

The loader now reads one SharedPreferences.getAll snapshot into a temporary configuration and publishes it only after complete validation. On failure it disarms locally, latches the existing maker storage pause, marks the configuration unreadable, and leaves previously loaded identities intact. On a cold start it treats unreadability as retained recovery work, not an empty safe-to-fund ladder. Raw preferences are left untouched. A stale host checks stored readability before either a full save or prepared-create write, so it cannot overwrite corruption it has not yet reloaded. A readable replacement snapshot still requires the existing explicit acknowledged recovery action to clear the process-local pause.

**Serialization could also save partial or invalid data.** Rung serialization used to ignore errors; a missing rung could become a blank or a truncated array. Full output now undergoes the same snapshot validation before the preference editor is committed. Prepared intent validates its required identity, amount, block and optional funding pair before writing. Invalid outgoing state does not change durable preferences. Valid legacy preference migrations retain original order IDs, cancellation clocks and positional rung sizes.

Validation uses the existing bounded decimal parser and requires complete object/array input, rejecting raw NUL and trailing data as the existing SDK response parser does. Missing or malformed identity fields, numeric overflow/fractional block clocks, wrong native types and incomplete funded amount/token pairs are rejected rather than silently replaced with defaults. Missing optional legacy funding remains readable; malformed modern funding cannot fall back to a legacy sell baseline. Existing formats and database schema are unchanged.

The Maker tab now distinguishes RECORDS UNREADABLE from a normal disabled maker or generic storage failure. The publish/withdraw toggle explains that saved records need recovery and that app data should be retained. The pause is shown in red even when the local armed flag has been cleared. Independently verified manual order operations are not replaced by a guessed maker recovery.

## Proven-code sources and scope

- `Pending.java` load/save: reused the complete-decode-or-preserve rule already protecting local receipts; it is a JSON row list, so it cannot directly decode maker's multiple preference keys and legacy formats.
- `MakerConfig.java`: retained its serializer fields, positional rung model and legacy migrations; added temporary snapshot publication and complete input/output validation in the same class.
- `org/minimarex/minimaapi/MinimaAPIResponse.java` and its tests: reused the complete-input/NUL checks, adapting the token parsing for local arrays as well as objects. Its node-response error-object return is inappropriate for local recovery, so malformed maker JSON raises a local parse failure.
- `MakerWithdrawalDurabilityTest.Memory`: reused the real MakerConfig serializer/loader seam with separate visible and durable preference maps.
- `TradeExportSession.java`: inspected its write-ahead export journal and recovery status; its process/export state does not supply a maker restart authorization policy.
- Sibling PandaPools ActivityLog and searches in UTXO/minimaSwap did not contain a compatible durable maker configuration decoder. ActivityLog's permissive load and bounded activity list are not suitable for preserving maker funding identities.

The existing graph led to MakerTab commit, MakerConfig load/save, foreground and service callers. It warns about its pre-#1504 IDs and predates these changes; it has not been regenerated as part of this pass.

## Tests

Ten new methods cover malformed slot/withdrawal/rung/prepared data and trailing/NUL JSON; partial failed reload retaining the previous complete identities; stale-host overwrite prevention; valid legacy migration and restart; invalid modern funding that must not become legacy funding; incomplete outgoing rows; invalid outgoing numeric and block values; explicit recovery after valid data is restored; wrong native preference types; and invalid prepared output before persistence. Parameter loops cover several corruption forms. The full withdrawal durability, funded-position and maker suites also pass.

This is deterministic JVM evidence invoking the actual production serializer and loader. It does not emulate Android filesystem corruption, reproduce actual process termination or render the updated Maker tab. Stock-device behavior and a new Android runtime audit of this loader remain unexecuted.

## Remaining risks

The storage-failure latch is still process-local. If storage refuses a pause write and the process dies, older successfully saved enabled settings may survive. No journal can record a failed pause if every write fails. Requiring explicit review before quoting after process restart would avoid silently resuming those old settings, but changes unattended maker behavior. The user was asked about that policy; no answer had arrived during this pass, and existing restart behavior has not been silently changed.

This change preserves unreadable preferences and blocks their overwrite; it is not a repair UI or an automatic reconstruction of damaged identities. Cross-instance read/modify/write races between otherwise valid snapshots still require work. Long-term growth of unresolved cancellation records, legacy funding reconstruction, terminal cancellation matching/replenishment, stale book/ownership display, background pairing, stock Samsung/MinimaCore validation and the human-only composite gate remain open. No production approval or100%-security claim is made.

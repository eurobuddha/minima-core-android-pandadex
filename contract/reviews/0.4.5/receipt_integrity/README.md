# Receipt identities and complete-input integrity

Seven of nine targeted receipt regressions failed before the fix; the two legacy/update compatibility cases already passed. The baseline XML is retained. All nine now pass, as does an additional SDK suffix regression. Full validation: **569 JVM tests**, zero failures/errors/skips; release lint zero errors/75 warnings.

## Defects and fixes

Pending.load accepted a JSON array prefix followed by extra bytes. A subsequent acknowledged save could discard the ignored suffix. It also accepted duplicate receipt IDs: removal by identity could retire a second transaction when the first was confirmed. Explicit empty, null, non-string or NUL-containing identities were coerced or accepted, and invalid new identities could poison storage.

Pending now reuses MakerConfig.storedArray's complete-input boundary, validates opaque nonempty string identities before reading/writing and refuses duplicate identities before any reconciliation or store update. Missing legacy IDs keep the existing deterministic migration; ordinary opaque IDs and intentional same-ID updates remain supported. The existing shared store lock, read-before-write pattern, pre-sign/post intent journal and commit acknowledgement are retained. Corrupt original bytes remain untouched, the recovery state stays visible and new intents cannot proceed. No ambiguous row is guessed, silently merged or deleted.

The existing complete-input check used JSONTokener.nextClean. Android skips comments in that method, so trailing //, /* */ or # comments could still be discarded. MinimaAPIResponse now supplies a shared raw-character suffix check allowing only JSON space/tab/CR/LF; both its node reply parser and MakerConfig use it. Existing raw-NUL and response-size guards remain. Invalid node replies still return transporterror without a status or enabled claim, preserving unknown-submission handling. This is an end-of-input check, not a replacement strict JSON parser: Android's underlying parser remains lenient within/before the root value.

## Inspected and reused

Read current Pending Row/json/from/load/save, stable legacy migration, identity-based removal, intent callbacks, reconciliation and recovery tests; MakerConfig's stored JSON/object/array helpers; the complete SDK MinimaAPIResponse and tests. Inspected PandaPools app/src/main/java/com/eurobuddha/pandapools/ActivityLog.java's list/add/save/status flow: its synchronized storage pattern remains the donor, but its permissive JSONArray/catch-ignore parser cannot supply the required integrity check. Tests reuse PendingRecoveryTest.Memory and CreationEvidenceTest proof/callback fixtures. The graph query `Pending load Row from receiptId MakerConfig parse JSON` was checked against current source; the graph remains pre-#1504 and has not been regenerated.

Android's documented nextClean behavior: https://developer.android.com/reference/org/json/JSONTokener . Audit19 also demonstrates the previous suffix check skipping all three comment forms on actual Android, then tests the production fix.

## Evidence and limits

[Audit19](../android_receipt_integrity/README.md) passes200 actual Android assertions, including108 added integrity checks, using real SharedPreferences and the production parsers/journal. Tests preserve malformed suffixes and duplicate/invalid identities, block preparation/post boundaries, retain stable single-legacy migration, and accept legal whitespace. The original process-death/ownership/upkeep checks repeat.

Broader row-field/schema validation, duplicate object-key handling, raw recovery/export UX, storage growth, durable terminal owner-operation history and valid concurrent host updates remain open. No automatic repair invents missing chain proof. No live node, signing or stock Samsung test occurred. Source remains0.4.5/405; no production405 APK, commit, push or publication. Audit19 is separate and frozen production404 is unchanged.

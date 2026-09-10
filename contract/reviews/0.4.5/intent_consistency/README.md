# Modern intents cannot silently use legacy proof rules

Nine of ten adversarial cases failed against the prior implementation. A modern cancellation could lose cancelSource and then retire against a refund whose original funding amount differed from the saved expectation. A modern edit could lose both editSource/editWant and then match the old price-only path or retire for a competing refund, despite retaining its journal phase/handle. The baseline XML is preserved; these are controlled production-verifier fixtures, not live transactions.

## Fix and reuse

Pending's legacy proof branch is now limited to rows with no modern phase, handle, posted ID, creation object, cancellation source or edit source/amount. The presence of any surviving modern marker prevents missing fields being treated as a legacy record. Load/save validates kind/payload consistency: PLACE cannot carry owner-operation fields, CANCEL cannot carry edit/creation fields, and EDIT cannot carry cancel/creation fields. Journalled creation must retain its creation object. Modern owner intents need a parsable safe source matching the row's coin ID, order ID and direction; edits additionally require the exact positive bounded wanted amount within eight decimal places.

The verifier reuses this same guard before cancellation or edit matching, including the direct editMatches path and competing-refund classification. It retains the existing sourceMatches economic/ownership checks, exact edit amount comparison, original included-input requirement and relockSuccessor proof. Owner-source parsing now directly reuses MakerConfig.storedObject's complete-input check, made package-visible, so a valid source prefix with trailing garbage/comment is not accepted. isRenewal uses that source parser and rejects inconsistent intents too.

No chain-confirmation shortcut, signing path, retry or guessed receipt repair was introduced. Original corrupted store bytes survive and new/update rows must pass the same reader before saving. Valid pre-journal legacy owner receipts remain supported; their missing expectations are not fabricated. Existing normal cancellation/relock/pre-post/lost-reply/late-callback regressions remain passing.

Inspected current Pending Row/from/save/intent callbacks/reconcile and legacy branches, CreationEvidence's complete prepare/input/match path, Order5 parser and DexTxn.safeOrder, MakerConfig's object/field parser and existing TakerReceipt/ReceiptRepair candidates. Current cancellation/relock/creation/recovery tests supply the fixtures. Sibling `/Users/eurobuddha/Projects/minima/mds/pandadex-mds/pending.js` was read completely: it has the old clean/refs/book-presence resolver and no exact modern journal, so it cannot supply this fix. The APK's existing proof helpers are the compatible implementation. The graph query `Pending cancelSourceMatches editSourceMatches intentResult` remains pre-#1504 and budget-truncated; source verification was authoritative.

## Verification

Ten new JVM methods: deletion of modern cancel funding evidence, deletion of both exact edit fields, competing refund after deletion, each surviving journal marker independently blocking legacy fallback, cross-kind payloads, journalled creation losing its object, complete source JSON and matching row identity/direction, missing/invalid/fine-grained edit amounts/sources, invalid new-intent save, and original legacy cancellation/edit compatibility.

**589 full JVM tests pass**, zero failures/errors/skips; release lint zero errors/75 warnings. Nine before-fix failures are retained, all ten new cases now pass. These execute the actual production parser/processor-proof code with synthetic history/callbacks, not live node calls. No new APK or Android run occurred in this pass. Audit20's545 assertions predate these Pending/MakerConfig changes; its source-match flag is explicitly false. The next audit APK must be21 or higher in a fresh output directory. Source remains0.4.5/405; frozen404 is unchanged, production405 unbuilt, and no commit/push/publish occurred.

## Code review

The new guard closes the observed downgrade paths and keeps the shared persistence/verification invariants. Remaining production findings:

- MAJOR — Pending.remove still erases a verified owner receipt before transient host notification. Durable terminal owner-operation history, proof retention and later rechecks are required; current trade archives do not fully represent these operations.
- MAJOR — A historical row with every modern marker and payload absent is indistinguishable from a legitimate old-format row. This fix uses surviving metadata, not authentication of local storage. An explicit persisted intent-format marker and migration policy could strengthen future records. Complete nested creation-schema validation, arbitrary consistent data alteration, duplicate JSON keys and all phase/ID combinations remain outside this fix.
- MAJOR — Stock Samsung/MinimaCore, lifecycle/Doze and the human-only composite gate remain unverified. Audit20 and the JVM fixture suite cannot substitute for those checks.

Verdict: request changes before production approval. The scoped downgrade regressions pass; the full production goal remains active.

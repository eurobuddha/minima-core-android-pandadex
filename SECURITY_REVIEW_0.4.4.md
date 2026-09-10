# PandaDEX APK adversarial review — 0.4.4 (404)

Review date: 2026-09-09. Baseline: `b65ff33`. Verdict: **hardening candidate; production release is not approved by this review**. This is an engineering review with regression and disposable-chain tests, not a formal verification or a guarantee of complete security.

The source and APK are versioned 0.4.4 / 404. No user node was queried, no real funds were spent, and no APK was installed, committed, pushed, or published during this review. `decentralized-chat-icon.svg` was pre-existing and is untouched.

## Work completed

| Finding | Change and evidence |
|---|---|
| Concurrent signing could outlive an elapsed-time gate; separate foreground/background actors could overlap | Reused PandaPools' operation-owned serial queue. Signing releases only on completion, and maker execution is process-wide. `SerialQueueTest`, `MakerHandoffTest`. |
| Lost replies/process death could permit another financial write with an unknown prior outcome | Durable marker is committed before dispatch. Timeouts, incomplete replies and transport loss pause new signing; dispatched writes are never replayed. The recovery screen includes the interrupted command. `NodeApi`, `NodeTransport`, `TransactionHardeningTest`. |
| Large native SDK broadcasts could kill the UI process | SDK and raw node notifications moved into private `:nodeipc`; same-UID Messenger calls carry request IDs, while bounded private files carry payloads. Main-process events carry only a filtered event name. `NodeTransportTest`. Device process-death validation remains outstanding. |
| Funding selection could enumerate huge wallets, consume state-bearing coins or contend with other queued transactions | Reused UTXO/PandaPools count/address tiers, strict human token amounts, state-free selection, bounded inputs and atomic process-wide input claims. Creation, taker trades and wallet splitting now use explicit inputs, outputs, change, validation and posting. `FundingCoinsTest`, `CoinLockTest`, `CreateFundingTest`; Java-generated buy/sell creates and split also tested on the disposable node. |
| Transaction checks accepted missing/wrongly interpreted verdict fields | Every required `txncheck` verdict must explicitly pass, including signatures, basic, scripts, MMR proofs and amounts. `txnpost`'s actual TxPoW-size rejection is preserved. A coin count or `txnexport` length is not called a serialized TxPoW-size guarantee. |
| Hostile state, token metadata or numeric exponents could alter commands, produce wrong token values or exhaust arithmetic | Single-command validation; validated hex/state literals; strict bounded decimals and tokenamount; pool recipe validation; duplicate-input and ownership checks. `TransactionHardeningTest`, layout/stress tests. |
| Cached keys or tracked scripts could be treated as fresh wallet ownership | Ownership requires freshly derived standard wallet addresses and keys. Arbitrary tracked pool scripts no longer prove ownership; hosts must also register/verify the covenant before acting. |
| Coin disappearance, a copied successor or an unrelated payout could fabricate a fill | Settlement now requires an included spending TxPoW. Taker receipts require the submitted immutable transaction identity, all selected inputs and the exact expected payout in that transaction. Mempool-only matches and competing spends cannot confirm it. Cancellation matches the spent order and its refund. `DexHistoryTest`, `FillSettlerTest`, `TakerEvidenceTest`. |
| Accepted taker receipts could disappear on restart, and old Activity callbacks could overwrite another receipt | Save intent before signing; save immutable identity before callback, including late replies; bind local transaction handles; retain unknown outcomes and prevent another taker receipt from overwriting the unresolved one. Capture the payout address before asynchronous work. |
| Maker assumed an absent order had failed after four blocks and funded a replacement | Preserve the order ID and pause instead. Separate durable create intent from accepted slots; retain cancellation instructions for delayed orders; stop remaining actions after disarm. A paused maker with recorded intent offers withdrawal. This deliberately changes automatic replenishment behavior, described below. |
| Failed pool reads could leave apparently current quotes | Malformed/failed derivation makes the snapshot unavailable. Stale retained cache is not offered to the trade planner as current liquidity; discovery fanout has a cap. |
| An unavailable depth feed could silently become a manipulable top-of-book price | Require usable ordered depth on both sides; reject nonfinite values; reuse minimaSwap's corroborated large-jump guard. Failed readings reset corroboration. |
| Quote/depth work blocked the UI or accumulated obsolete calculations | Quote calculation runs off the UI thread; depth calculations coalesce; reuse the previous composite route instead of recalculating it. Destroyed Activities stop rendering/export callbacks. Samsung-safe decimal fields have bounded input. |
| Exported attacker-controlled text could become spreadsheet formulas; legacy data was over-labelled as confirmed | Escape textual CSV cells; retain historical data on migration; label legacy evidence honestly. Screen times are explicitly device observation times. |
| Private test harness had a hardcoded old node endpoint and permissive validation | Require explicit loopback disposable-node configuration before access; no default personal-node port. Align default V5 expiry with 600 blocks and require all validation flags in the app-shape check. |

The V5 covenant template and its production address have not been changed by this review. Pool-consumption validation changed; the economic covenant is not being presented as newly proven on mainnet.

## Validation actually performed

| Check | Result / limit |
|---|---|
| `./gradlew testDebugUnitTest lintRelease` | **285 tests, 0 failures, 0 errors, 0 skipped.** Release lint: **0 errors, 76 warnings**. |
| `./gradlew assembleRelease` | Passed. |
| APK manifest | `com.eurobuddha.pandadex`, `versionName=0.4.4`, `versionCode=404`, min SDK 28, target SDK 35. |
| APK signature | `apksigner verify`: valid APK v2 signature, Minima Family certificate. |
| Phase A VM arithmetic cases | **134 passed, 0 failed**. This harness uses a VM shim for some transaction operations; it is not a complete covenant proof. |
| Existing app transaction shapes on disposable solo chain | **4/4 passed**: create, partial fill, atomic reprice and cancellation, with all validation flags required. The legacy harness's create used `send`; the new Java create was tested separately below. |
| Hostile transactions on disposable solo chain | **9/9 attempts did not consume the protected order coin**: shaved payment, shaved new-want, dust remainder, payout/remainder hijack, omitted state, owner-state change, min-remainder change, unsigned theft and unsigned reprice. These are observed non-mining tests over a finite window, not a formal impossibility proof. |
| Buy-side lifecycle | Passed create, partial fill and refund using human token units. |
| Actual compiled Java create builder | **2/2 passed**, buy and sell: emitted commands, all `txncheck` flags true, mined exact order amount/state, then refund. Only the test token and derived test covenant address were substituted. |
| Actual compiled Java split builder | Passed: all flags true, source matched to an included spending transaction, expected state-free wallet outputs, exact conservation of value including rounding remainder/change. |
| Whitespace validation | `git diff --check` passed. |
| Real Samsung device, stock released MinimaCore, app restart/force-stop, background limits | **Not run.** No user device or node access was used. |
| Manual live composite interop gate | **Not run.** Project instructions reserve this for a human-led real-device test. |

The disposable node was newly created for this review with its own data directory, wallet and RPC password. It reported **1.1.2-TEST.4**, so these results must not be described as validation against a stock released MinimaCore build. Test jar SHA-256: `2827b24508d67424e60dc77ced69aadd2a4bccab7ccfeccba91c3ca2417bc2c2`. Public test logs are in `contract/reviews/0.4.4/`; credentials and private node data are not included.

## Remaining findings and release blockers

1. **High — recovery is conservative, not complete.** `MainActivity.reviewUnresolvedTrade`, `SubmissionIds` and `DexHistory` retain unresolved taker intent, but a pruned/missing history entry, a lost post reply or a transaction that never mined can leave the single taker receipt unresolved indefinitely. The bounded history search restarts near the latest entries; it is not a durable complete history index. Add durable lookup progress and explicit evidence-based rejected/competing-spend resolution, with a visible archived recovery record. Never infer rejection from elapsed time. Other manual PLACE/EDIT receipts still use order-ID presence (`Pending.resolve`) and are not equivalent to the stronger taker proof; they also retain the older timeout and storage model.
2. **High — complete, reorg-aware trade history is unfinished.** `FillSettler` keeps at most 512 candidates in memory, so process death or overflow can lose unverified market-fill candidates. Confirmed-at-check-time evidence is accepted at stock depth zero and is not subsequently retracted on reorg. Persist unresolved candidates, distinguish inclusion from finality, store the actual inclusion timestamp/height and recheck canonicality. Existing old rows cannot be upgraded to proven history merely by relabelling them.
3. **High — stock-device and manual composite release evidence is missing.** Run S23 and Z Fold pairing, fragmented-wallet operation, node/IPC process interruption during each write stage, activity recreation, screen-off/background renewal, cold recovery, upgrade preservation and cancellation races using the built version. Complete `contract/COMPOSITE_LIVE_INTEROP.md` manually before approving pool-only/mixed real-funds use. The repository explicitly says: “Do not run this from an automated agent session.”
4. **Medium — maker replenishment now pauses on unexplained absence.** This prevents duplicate funding but means a fully filled or manually removed rung may require review/withdrawal before the ladder can be published again. Restore automatic replenishment only after linking the former rung to a verified terminal spend; do not reinstate the old four-block retry. Cancellation tombstones are deliberately retained, and need evidence-based retirement rather than a timer.
5. **Medium — availability limits remain.** A token concentrated in more than eight coins at one wallet address may require consolidation before bounded selection can use it. Sentinel/reserve reads still depend on legacy node reply limits; more than 64 valid announcements disables pool quotes. The native SDK can allocate a large response before the private bridge's 4 MiB cap. Isolation protects the UI better but does not turn these into byte-size or memory guarantees. Add observable/paged recovery and exercise these limits on the actual devices.
6. **Medium — trusted-service boundaries remain.** This app trusts the installed MinimaCore package and the bundled SDK. SDK inspection found predictable-PRNG registration identifiers and unbounded file-response reading; the SDK itself was not rewritten in this pass. The external price source and explorer are also trust boundaries. Explorer export status currently accepts the endpoint's block response without independently proving returned transaction identity; it is not used to authorize spending. A second oracle and exact explorer identity validation would improve assurance.
7. **Medium — lint and long-running service testing are not release clearance.** Remaining lint findings include intentional synchronous safety persistence, target/dependency age, battery-optimization API use, locale and UI/localization/accessibility warnings. No dependency migration or store-policy certification was performed. Long-running export remains memory-based, and large histories deserve a streaming/cancellable export pass.

## Next implementation and verification plan

1. Persist receipt/candidate state in the existing database with durable operation IDs and explicit lifecycle states; migrate legacy evidence without erasing it. Add process-restart and reorg tests before restoring automatic maker replenishment.
2. Implement bounded historical lookup with a persisted cursor and definitive terminal outcomes; expose copyable transaction IDs and actual inclusion depth uniformly across receipt views.
3. Validate the exact candidate on stock Samsung devices. Complete the human-run composite gate and retain transaction IDs, chain evidence, versions and before/after balances.
4. Address SDK identity/response limits and oracle/explorer trust improvements with the proven family implementations where compatible; finish accessibility/background-service checks.
5. Only then approve a production release, with another version bump for any further code change. Do not overwrite the 0.4.4 artifact.

## Proven-code and review provenance

Read the target's `AGENTS.md`, `CLAUDE.md`, README, local PandaDEX memories and graph before the implementation review. The graph query returned 548 nodes and was treated as an index; source was read directly because the graph predates these edits. The referenced older plan `~/.claude/plans/this-is-a-tough-declarative-scroll.md` was absent; it is not claimed as reviewed.

Skills used: `reuse-proven-code`, `graphify`, `code-review`, `minima-reference`, `minima-tx`, `minima-mds`, `minima-smart-contracts`, plus the complete local Minima developer reference. Reviewed the transaction, transport, key ownership, discovery, maker, routing, persistence, settlement, export, background and UI layers; this does not assert exhaustive review of every dependency or every possible execution.

Reused relevant implementations from:

- `/Users/eurobuddha/Projects/minima/apks/pandapools/app/src/main/java/com/eurobuddha/pandapools/`: `SerialQueue`, `CoinLock`, native transport/service, funding/transaction validation, `ActivityLog` identity/inclusion handling, `PoolBook` reserve checks and statement CSV escaping.
- `/Users/eurobuddha/Projects/minima/apks/utxo/`: wallet/CoinLoader funding tiers and existing consolidation approach.
- Existing PandaDEX `DexTxn`, `CmdChain`, `PriceMath`, `SweepPlanner`, `CompositeRouter`, covenant and transaction harnesses rather than replacing their economic arithmetic.
- minimaSwap `PriceOracle` jump corroboration and AtomiX/MakerTab decimal-input behavior.
- Local minima-core Java command implementations for actual `send`, `txncheck`, `txnpost`, history and on-chain TxPoW behavior.

## Artifact

`releases/pandadex-0.4.4.apk` — 13,592,932 bytes.

SHA-256: `e39aa40dc42f051d9668f47e9309b05349f8149c07cdb6c29187f8c1a26b769f`

Signing certificate SHA-256: `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`

Source remains an uncommitted review change set, as the project forbids committing/pushing/publishing without an explicit request. The APK is a review artifact, not a published production release.

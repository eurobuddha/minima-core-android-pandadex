# PandaDEX final triage — 0.4.10 / 410

The user stopped expansion of the adversarial review on 10 September 2026. Final triage began at 19:57:21 UTC with a hard deadline of 20:57:21 UTC. Scope is limited to demonstrated funds-loss, incorrect-transaction, data-loss or crash risks. This document supersedes earlier statements that the broad review will continue indefinitely. Other improvements are deferred. No push, publication or production release build is authorized.

## Completed work and evidence

The local WIP checkpoint is `5d99d08a9b6bb959c961edccc29103ab98ad9d0c`, including review documentation and tests. Its commit message records the actual test state at that checkpoint rather than asserting the user's older report of failures. The later demonstrated prepared-intent ownership defect has its failing baseline and passing fix in `a53ef181470815da6e5faecfe5f5164a0a53034f` (0.4.6).

Subsequent separately verified commits: wallet observation state `bdea05b` (0.4.7), retained owner-receipt presentation `eb09a7f` (0.4.8), and verified identity/queued-command binding `60e4103` (0.4.9). The final triage closes only the already-reproduced numeric-height defect; see [0.4.10 review and evidence](SECURITY_REVIEW_0.4.10.md). It does not claim that every risk below is a demonstrated exploit or fixed.

## Remaining production blockers and limits

| Item | Established evidence / unresolved boundary | Required disposition |
|---|---|---|
| Stock S23 and Z Fold with stock MinimaCore | Isolated Android tests cover the archived callback, SQLite, receipt and process-death cases. They do not execute Samsung background restrictions or stock MinimaCore pairing/IPC/signing. | Execute the bounded device plan below. A crash, duplicate spend request, lost receipt, or incorrect transaction effect blocks release. |
| Composite liquidity | The human-only live pool-only and mixed trade gate remains unexecuted for this candidate. | Run [COMPOSITE_LIVE_INTEROP.md](contract/COMPOSITE_LIVE_INTEROP.md) manually with capped test funds. No automated real-funds testing. |
| Legacy and unavailable evidence | Legacy PLACE/taker/composite records can lack original funding/expected effects; pruning can remove historical proof. Recovery cannot honestly reconstruct missing facts. Conflicting immutable transactions remain unresolved in the documented cases. | Preserve original records, amounts and timestamps, show unresolved outcomes, and prohibit automatic retry based on elapsed time or disappearance. Reconcile externally where evidence exists; otherwise report the limitation. Never promise exhaustive repaired history. |
| Wallet changes and cached observations | 0.4.9 binds queued work to observed connection changes. It does not establish cryptographic wallet identity or detect an unannounced seed swap. Stored maker/history affinity and old balance/book/tip attribution remain unvalidated. Invalid block replies in 0.4.10 retain the last valid tip; they do not establish its freshness. | Do not approve wallet-switch/re-pair behavior until tested with disposable wallets. Maker restart/automatic-resume policy remains unchanged and requires an explicit product decision before changing it. No new exploit is claimed by this limitation. |
| Oracle assumptions | Price availability guards do not establish the economic correctness of a single external venue's price or eliminate manipulation risk. | Treat pegged maker operation as unapproved until its intended price-trust assumptions and outage behavior are accepted and tested. This is an open dependency assumption, not a newly reproduced funds-loss incident. |

No additional demonstrated unfixed defect was established during this bounded final triage. That is not evidence that no other defect exists. The items above prevent an unconditional production-ready claim.

## Bounded stock-device validation plan — 120 minutes maximum

This is a plan, not authorization to access phones/nodes, install an APK, or spend funds. Obtain device/node access approval and candidate-build/install authorization separately. No release is built by this review. Use an immutable, uniquely versioned candidate with its source commit, APK SHA-256, signing certificate and displayed version recorded; never overwrite an earlier artifact. Preserve existing app data and exports before any installation. Use disposable, explicitly capped test wallets for spend and wallet-switch cases; do not swap or wipe a funded production wallet.

| Time | Checks | Pass evidence |
|---|---|---|
| 0–10 min | Record S23/Z Fold OS, stock MinimaCore version, candidate identity and installed versions. Save receipt/history exports and baselines; confirm which device/node operations are authorized. | Inventory, original exports/hashes and matching version display. Stop if a safe candidate or backup is unavailable. |
| 10–35 min | S23: cold start, pairing, complete wallet loading, one human-approved dust order, edit and cancel. Inspect the actual included transaction effects. | Exact funding/source coins, owner/payout, tokens and amounts match the approved intent. Receipts persist through tab navigation; no false confirmation. |
| 35–55 min | Z Fold: repeat startup/pairing; compare the same chain evidence where visible. Disconnect/reconnect and test a disposable wallet change. | No queued action uses the previous identity. Inclusion ID/block/time agree across devices; depth matches each node's actual tip. Observation times remain identified as device observations. |
| 55–80 min | Interrupt an in-flight approved request, restart, switch foreground/background and use a bounded screen-off interval. Re-open receipt/export views. | One retained request, no automatic duplicate submission, no crash/data loss, and recovery driven by included transaction evidence. Record Samsung battery restrictions; this short interval is not an overnight Doze guarantee. |
| 80–105 min | Human executes the existing composite runbook's pool-only and mixed cases with capped funds. | Exact selected sources and proceeds verified on-chain; no phantom pool contribution on public tape. If confirmations/evidence do not arrive by the slot's end, mark inconclusive and stop. |
| 105–120 min | Export and compare pre/post history, retained unresolved requests and all device failures; record pass/fail/inconclusive for each gate. | Reproducible evidence bundle, exact remaining blockers and release verdict. |

Stop immediately for an unexpected destination/amount/token, duplicate automatic transaction, disappearing receipt, data mutation without evidence, or crash. Preserve evidence and app data; do not retry a funds request whose outcome is unknown. An expired time slot becomes an unresolved gate, not permission to extend the session. Slow confirmation, unavailable history or incomplete Samsung lifecycle coverage remains explicitly inconclusive. Any repair is a separately authorized, bounded follow-up with its own version bump and verified commit.

## Deferred work

UI polish, features/upgrades, generalized optimization, broad refactoring, graph regeneration, long-term performance/storage-growth work and further speculative adversarial exploration are deferred. The queried graph is stale; current source and archived tests are the evidence for the completed fix. No user device or node was accessed in final triage. The unrelated `decentralized-chat-icon.svg` is preserved outside review commits.

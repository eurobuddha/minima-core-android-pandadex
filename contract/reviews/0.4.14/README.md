# PandaDEX 0.4.14 — current trade history and automatic input reveal

Verified taker receipts now populate the public market history in the same database transaction as the private receipt, using the existing PoolMarket classifier and FillSettler covenant verifier. Input positions and actual per-leg execution evidence are preserved; quoted sweep averages are not substituted for individual executions. Fresh repaired proofs update both views. Existing deduplication and chain-review exclusion apply. Public discovery receives every other completed scan even with a persistent owner/taker recovery backlog.

Each device still has an independently observed, bounded node history. This fixes immediate own-trade indexing and discovery starvation; it does not establish a complete global trade feed or guarantee instantaneous agreement between devices. Existing old receipts are not assigned new timestamps. The Fold's 0.004452 and S10+'s 0.004379 have not been matched to transaction IDs in this review, so neither is asserted to be the globally newest trade.

The trading form now requests automatic visibility after IME layout and input focus, adapting Salon's existing focus-scroll implementation. It keeps price, amount and action together when they fit; otherwise the focused field takes priority. The progress log uses Casino's one-line ticker while typing and remains tappable. No trade guard, transaction construction, covenant or renewal change is included.

## Reuse and review

Inspected sources: current DexDb receipt/repair/read-model paths; TakerReceipt and TakerEvidence; FillSettler settlement and history scheduling; DexHistory verification/discovery; PoolMarket classification; MainActivity and TradeView input/inset handling; ../salon/app/src/main/java/com/eurobuddha/salon/MainActivity.java focus-scroll; prior inspected ../casino MainActivity ticker and ../base KeyboardInsets/KeyboardScreenTest. Existing proof verification, public persistence and chain correction are reused. Only receipt-to-market plumbing, scheduling fairness and automatic UI reveal are added. Existing graph was queried but is older than these source changes.

Scoped code review: approve for device testing. Checks cover preserved sparse indices, receipt replay deduplication, reorg exclusion and fresh-proof repair, and recovery backlog fairness. Market indexing remains atomic with receipt storage, so persistence failure retains pending recovery rather than clearing an unrecorded receipt. Remaining stock-device and release gates below prevent a production-readiness claim.

## Validation

700 JVM tests pass with zero failures/errors/skips. Final lint: zero errors, 76 warnings. Android audit 47: 871 normal + 16 pre-crash + 31 recovery + 174 Activity assertions = 1,092, all pass. The deliberate crash/recovery scenario is expected. The audit package has no network permission and no MinimaCore. Portrait testing focuses the field and opens the real IME without test-assisted scrolling, then checks the amount, Buy button and log above the keyboard; landscape checks the focused field and log. Input value and navigation restoration and log persistence are covered. Both keyboard screenshots were visually inspected.

Earlier attempts are not counted as passes: audit 44 had a fixture compilation error; audit 45 used a due-review queue helper to find correctly fresh proof, fixed to query the persisted revision; audit 46 passed market/recovery checks but exposed the full log consuming short-portrait space. Audit 47 includes the resulting one-line typing log fix. The original 0.4.13 emulator test manually scrolled and therefore did not establish automatic reveal; the actual Fold screenshot confirmed this missing behavior.

## Remaining bounded device validation (20 minutes)

1. Install unique signed 0.4.14 on authorized Fold and S10+, verify version/signature/hash and preserve data. On Fold focus the entry fields with the keyboard open without entering or executing a trade; inspect actual screen (5 minutes).
2. User checks folded/unfolded and rotation layouts on Fold plus S10+ typing and log visibility (5 minutes).
3. User identifies a recent known book/pool execution; compare its transaction ID, inclusion block/time and executed price on both devices after catch-up. User alone initiates any funded test (10 minutes). Independent incomplete history remains a market-data limitation if agreement is not established; report it, do not extend review indefinitely.

Broader blockers remain in FINAL_TRIAGE_0.4.10.md and contract/COMPOSITE_LIVE_INTEROP.md. No push or publication before user testing/authorization. This is not a claim of complete security.

## Actual stock-device follow-up

Installed signed 0.4.14 (414) on Z Fold SM-F966B and S10+ SM-G975F with app data preserved. Both installed base APK hashes equal `c6445ecf3b50efddcd2df01a79882990458368c7efca8ad85084367b2554220e`; source commit `5fc955fa436729e24ce3cdc7a087a5879da7a42e`. Existing versioned APKs remain unchanged. No push or publication.

Opened PandaDEX on the Fold outer screen, focused the observed empty Amount field, and inspected the actual Samsung-keyboard screenshot and UI bounds. Price bottom 939, Amount bottom 1053, Buy bottom 1402; keyboard top 1548. All are visible after automatic reveal, without manual scrolling or entering/executing a trade. The keyboard was dismissed afterward. Fold unfolded/rotation and S10+ keyboard behavior still require user testing.

Fold header still showed 0.004452, 1 day ago on opening. Existing history convergence and the user's recent transaction remain unverified. This is a remaining market-data blocker, not evidence that all stale historical displays are repaired. The unique signed APK is for the authorized user test; no claim of global price agreement is made.

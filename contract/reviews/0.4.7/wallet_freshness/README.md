# Wallet and order observation freshness

## Code Review

### Summary

Cold offline UI previously displayed default zero wallet balances and "No open orders" before any node observation. The screens now distinguish unknown data from a genuine zero or empty current scan, using the existing per-token timestamps and makerBookReady() predicate. Cached records and pending receipts remain visible.

### Findings

#### MAJOR — Fixed: never-loaded data appeared to prove absence of funds or orders

**Files:** AssetsTab.java, OrdersTab.java, TradeView.java, MainActivity.java.

Assets now shows a dash and loading guidance for each token whose timestamp is zero. Aggregate valuation requires both balances and a current book/ownership snapshot. A receive address that has not loaded is explicitly unavailable and has no copy handler. Trade and Orders share loading text when empty and not current, or a saved-list warning when records exist. Current empty scans still render "No open orders". Cached order values shown in Assets are identified as saved values until readiness returns. These are display changes; spending retains its existing checks.

#### MAJOR — Fixed: malformed balance numbers became fresh observations

MainActivity.balanceMeta used zero-fallback decimals and optInt, then always stamped the result as fresh. Invalid strings/types, negative amounts and fractional/overflow coin counts could display false balances. Both production balance commands now pass their expected token ID. The parser requires a matching single row, exact bounded nonnegative numbers/counts, and a valid success status. Invalid reads leave the last valid fields/timestamp untouched. Fields are assigned only after validation. The legacy coinamount fallback and absent legacy unconfirmed/count defaults remain compatible.

The inspected local stock node command always emits native MINIMA's row but omits non-native tokens with no coins. A successful empty array for the explicitly requested non-native token therefore records an observed zero. A missing native row, empty unscoped result, failed reply, mismatched token or ambiguous multirow result remains unknown. This rule is grounded in `/Users/eurobuddha/Projects/minima/core/minima-core/src/org/minima/system/commands/base/balance.java` (SHA256 `02f70532559ced2800ace362544e93efe2699ea35e816dc6621393741b3164ae`), not a fresh query to a user's node.

#### MINOR — Fixed: chart explanation contradicted retained-history discovery

ChartTab now describes local saved records and possible recovery from node-retained history, with explicit missing/pruned-history limits. It no longer claims there is no historical backfill.

### Reuse and evidence

Inspected/reused MainActivity's BalanceMeta/timestamps and makerBookReady, AssetsTab/OrdersTab/TradeView renderers, BookRepository's retained-cache/current-scan policy, MakerConfig.storedDecimal/storedBlock/jsonString and Util.decOr bounds, and BalanceDisplayTest. Sibling UTXO TokenBalance/BalancesView still use permissive/default-zero display and cannot supply this distinction unchanged. PandaPools PoolStatement's unavailable-reserves handling confirms the same preserve-unknown presentation rule. No new dependency, persistence key, database schema or transaction construction policy.

Six additional JVM test methods cover malformed/negative numbers, exact bounded coin counts, valid zero/legacy fields, scoped empty-token behavior, expected token identity and ambiguous rows. Two pre-fix numeric regressions failed; the remaining compatibility/scoped cases supplement them. Full suite: 658 tests pass, zero failures/errors/skips; release lint zero errors/74 warnings. git diff --check passes. The visual baseline is audit31.

### Verdict and limits

Approve this bounded fix; overall production readiness remains incomplete. Audit32 executes real offline Activity rendering/recreation on the new source, including zero-balance and saved-order UI fixtures. Parser compatibility is JVM plus inspected local node source, not live stock Samsung IPC. Cached balances retain their existing age display; this does not make them fresh or authorize spending. Broader populated-wallet, re-pairing, lifecycle/Doze, receipt reconstruction, oracle trust and the human-only composite gate remain open. Cached order block-age formatting when the current height is unavailable also deserves follow-up; the new saved-list label does not make a default block-derived age current.

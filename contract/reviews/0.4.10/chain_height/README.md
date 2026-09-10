# Chain-height validation

## Code Review

### Summary

A demonstrated coercion defect could supply fabricated age/expiry inputs to automatic upkeep and misleading block context to transaction journals. Stock Minima's `block` command returns a decimal integer string. The app instead truncated decimals and could wrap out-of-range values. The bounded fix reuses the existing exact positive-integer parser; no transaction construction, covenant, receipt schema or expiry threshold is changed.

### Findings

#### MAJOR — Fixed: malformed heights became usable expiry/transaction context

`MainActivity.poll` and `DexKeepAliveService.readBlockThenBook` used `Util.dec(...).longValue()`. The foreground callback also failed to require a successful command result. Both now use `ChainEvidence.tipBlock`, which checks the existing status verdict and delegates to `ChainEvidence.positiveLong`. Unknown, zero, negative, fractional, exponent, wrong-shaped or overflowing values cannot replace a valid observation. The watcher does not begin its scan/upkeep for such a reply. Valid lower heights remain accepted, preserving reorg behavior.

`Order5.from` used `optLong` for `created`; the regression demonstrates that `100.9` became block 100 and that malformed values were accepted. It now uses the same exact parser, retaining the existing zero/unknown sentinel and unavailable-age display. Valid order expiry/renewal boundaries are unchanged. Two of three initial tests failed against unchanged 0.4.9 source; all seven tests pass after the fix. This demonstrates incorrect application decisions from malformed observations, not an on-chain funds-loss event.

### Reuse evidence

Read/reused `app/src/main/java/com/eurobuddha/pandadex/ChainEvidence.java` (`positiveLong`), its `InclusionTimeTest.java` coverage and `TxValidation.truthy`. Inspected host callbacks, `BookRepository`, `DexProcessor`, `Order5`, transaction block-context consumers, and the `TransactionHardeningTest.orderCoin`/`TestJson` fixtures reused by the regression. The sibling candidate `/Users/eurobuddha/Projects/minima/apks/pandapools/app/src/main/java/com/eurobuddha/pandapools/PoolRefresher.java:191` is strict but uses a 32-bit parser; the current 64-bit parser is directly compatible and already used for verified inclusion heights/timestamps. Stock `/Users/eurobuddha/Projects/minima/core/minima-core/src/org/minima/system/commands/base/block.java` supplies the response shape. No node was queried. Android callbacks reuse audit37's scripted NodeApi/reflection fixtures and owned emulator workflow.

### Validation and verdict

681 JVM tests pass (zero failures/errors/skips); lint zero errors/74 warnings. Audit38 passes 1,012 Android assertions, including both actual host callbacks and the existing receipt/process-death regressions. Baseline failure, passing test XML, logs and hashes are retained. Source version is 0.4.10/410; schema13 and the signed production 0.4.4 artifact are unchanged.

Approve the bounded fix. Production approval remains blocked by the gates in `FINAL_TRIAGE_0.4.10.md`. The parser establishes syntax/range, not chain authenticity or cache freshness. Last valid observations remain cached; callback attribution across connection changes is not solved by this patch. A well-formed but dishonest node height is outside this parser's protection. No further review expansion is part of this triage.

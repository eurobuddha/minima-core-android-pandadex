# Size-weighted book reference — 0.4.11 / 411

The user requested sum(price * MINIMA size) / sum(size), combining pool and limit liquidity. The centre now uses the best displayed bid and offer and adds both liquidity sources at each level before weighting. This is same-side weighting, not an imbalance microprice that swaps weights. Equal sizes at 0.00439 / 0.00443 give 0.004410; 300 bid /100 offer give 0.004400. One-sided depth uses that side; missing/zero depth is unavailable. While a changed pool snapshot is being sampled, the centre waits for the matching depth result.

Reuses TradeView's existing sorted/merged level maps and SyntheticDepth output, plus PriceMath's exact decimal/display conventions. No order construction or execution limits change. Grouping affects the displayed levels and sizes used in this reference. Last-trade pool discovery is a separate follow-up; the old limit-only bookMid accessor used by Assets is unchanged by this centre-only request.

686 JVM tests pass; zero failures/errors/skips. Five new regressions cover equal/unequal sizes, combined pool/limit size, one-sided/empty depth and precision. Full lint completed successfully. Android UI verification will run with the subsequent pool-history and transaction-feedback changes. No new APK, install, push or publication in this commit.

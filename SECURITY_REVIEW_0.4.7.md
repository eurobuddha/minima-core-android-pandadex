# PandaDEX 0.4.7 /407 — wallet and order freshness

Fixes the misleading offline states found in audit31. Assets shows unknown balances as dashes with loading guidance, preserves genuine observed zeros, and does not offer to copy an unloaded address. Aggregate valuation waits for both balances and current book/ownership data. Trade and Orders distinguish a never-loaded list from an empty current scan; saved records remain visible with a freshness warning.

Malformed, negative, ambiguous or mismatched balance replies cannot become fresh observations. Exact bounded coin counts replace truncating coercion. Failed reads preserve the last valid values and their original timestamp. A successful empty scoped non-native token result remains a genuine zero, matching the inspected local stock Minima balance command. Chart text now describes retained-history recovery and its limits.

[Detailed review](contract/reviews/0.4.7/wallet_freshness/README.md): **658 JVM tests pass**, zero failures/errors/skips; release lint **zero errors/74 warnings**. [Audit32](contract/reviews/0.4.7/android_wallet_freshness/README.md): **913 actual Android assertions**, including 58 full-Activity checks. Six changed-state screenshots were visually reviewed; the observed-zero/cached-order cases are local fixtures, not live node data. [Validation manifest](contract/reviews/0.4.7/validation.json) pins sources and evidence.

Source version advances for the separate verified code commit. Audit APK32 is separately numbered; earlier APKs are preserved. No production release was built, no user device/node accessed, nothing pushed or published. The isolated emulator/ADB/data/keys were cleaned up.

Production approval remains incomplete. [Broader outstanding gates](SECURITY_REVIEW_0.4.5.md) still apply. Populated-wallet re-pairing/lifecycle, Doze and stock Samsung IPC, cached block-age presentation, missing legacy evidence, oracle trust and the human-only composite test remain outside this fix.

# PandaDEX 0.4.10 / 410 — final height fix

Malformed creation/tip heights could be truncated or wrapped into valid-looking values used for expiry/upkeep and transaction context. Both hosts and order parsing now reuse the existing exact positive-integer parser. Failed node replies cannot supply a tip; invalid watcher replies cannot trigger a new upkeep scan. Valid expiry thresholds and lower reorg heights still work.

Two baseline regressions failed on unchanged 0.4.9 source. After the fix: **681 JVM tests pass**, **1,012 isolated Android assertions pass**, lint **zero errors/74 warnings**. [Detailed review](contract/reviews/0.4.10/chain_height/README.md), [Android callback evidence](contract/reviews/0.4.10/android_chain_height/README.md), [hash manifest](contract/reviews/0.4.10/validation.json).

This concludes the user's bounded final triage; [remaining blockers and the stock-device plan](FINAL_TRIAGE_0.4.10.md) replace the previous open-ended review. No production release, publication, push or user device/node access. Audit38 is a separately versioned test package. Existing release APKs and unrelated icon work are preserved. Production readiness is not approved.

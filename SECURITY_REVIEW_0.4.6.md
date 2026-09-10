# PandaDEX 0.4.6 /406 — prepared-intent ownership

This is a source follow-up to WIP checkpoint `5d99d08a9b6bb959c961edccc29103ab98ad9d0c`. No release APK was built, installed, pushed or published. The source version advances for the repository's mandatory code-commit version hook. Previous APKs are preserved.

The maker journal now refuses to replace an unresolved prepared create. Create callbacks clear only the record with their own order identity, preserving another intent. A matched acceptance still replaces its prepared record with the accepted slot in the same save. Completed callbacks cannot prepare or authorize again. A rejection before preparation avoids an unnecessary settings write.

[Detailed review and regressions](contract/reviews/0.4.6/maker_prepared_identity/README.md): four failures reproduced in seven new tests and fixed. **652 JVM tests pass**, zero failures/errors/skips; release lint **zero errors/75 warnings**. [Validation manifest](contract/reviews/0.4.6/validation.json) pins the tested source and regression hashes. Test development corrections are documented separately from the defect baseline.

This is defensive component-boundary hardening, not evidence that normal production hosts overlap submissions. The process-wide maker gate and Pending's callback guard already protect that normal path. No new Android run was performed for this change: audit30's855 assertions cover the preceding0.4.5 source only.

Overall production verdict remains incomplete. [The broader review and remaining gates](SECURITY_REVIEW_0.4.5.md) still apply, including stock-device lifecycle/IPC, full Activity/service recovery, oracle trust and the human-only composite interoperability test. The saved authorization-revision defect was fixed before the WIP checkpoint; it was not a currently failing test at that checkpoint. This subsequent fix addresses prepared-journal ownership and late-callback defenses.

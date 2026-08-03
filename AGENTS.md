# PandaDEX / Minima working instructions

These instructions are project memory for future Codex/agent work in this repository.

## Highest-priority rules

1. Follow the user's explicit instructions first. If the user says "look at X", "use Y", "do Z first", or "do not do W", that is blocking.
2. Reuse existing proven code before inventing new code. If native Android/APK code, sibling repos, or prior working MiniDapp code exist, read and copy/adapt those paths first.
3. Do not silently substitute a different approach. If an instruction appears wrong or unsafe, state the concern and ask before acting.
4. Keep scope tight. Do not add unrelated integrations or experimental features.
5. Do not publish unless explicitly asked.
6. Do not commit or push unless explicitly asked.
7. Give concise progress feedback while working; do not go silent during long tasks.

## Minima-specific rules

- For any Minima blockchain, MiniDapp, MDS, KISS VM, token, transaction, or covenant work, use the local Minima reference skill/instructions first.
- The user also keeps a reference file at:
  `/Users/eurobuddha/Projects/archive/minima-skills.md`
- Treat transaction construction, signing, cancellation, refund, and coin-selection logic as high-risk. Read the proven native or existing implementation before changing it.

## PandaDEX scope notes

- PandaDEX APK `0.3.9` is the source-of-truth parity target for PandaDEX MDS work, **including the
  PandaPools composite/pool integration**. The user changed the target on 2026-08-02; it was
  previously `0.2.19` with pool integration explicitly excluded, and the earlier scope cut is what
  produced the orphaned `preserve-pool-composite-*.patch` in the MDS repo. Do not re-cut it.
- The composite path remains behind the real-funds gate in `contract/COMPOSITE_LIVE_INTEROP.md`.
  That test is run by hand on real devices — never from an automated agent session.
- PandaPools is still a separate product/repo and evolves independently. PandaDEX consuming pool
  liquidity as a taker is not the same thing as merging the two apps.
- For UI parity work, match the Android APK layout, flow, detail, wording, validation behavior, and feedback model before designing anything new.

## PandaPools scope notes

- PandaPools MDS is separate from PandaDEX.
- Previously completed PandaPools MDS release:
  - Repo: `/Users/eurobuddha/Projects/minima/mds/pandapools-mds`
  - Version: `0.6.17`
  - GitHub tag: `v0.6.17`
  - Release asset: `PandaPools_0.6.17.mds.zip`
  - SHA-256: `0e34fe1b548c761cbad586e46ca1382f3d993022982dc913bfae1e254c0f9bb4`


# PandaDEX 0.4.18 — open-orders placeholder follows the pairing state

Code review of 0.4.17 found that its reworded `OrdersTab.WAITING_ORDERS` ("Open orders are loading from MinimaCore.") was still shown unconditionally whenever `makerBookReady()` was false — which includes never-paired and pairing-revoked. An unpaired user was therefore told orders were "loading", replacing the one message that used to send them to MinimaCore → Apps. The receive-address card in the same commit had been wired correctly; the two order call sites had only been reworded.

Change: `MainActivity.ordersWaitingMessage(paired, known)` mirrors `receiveLoadingMessage` — paired → `WAITING_ORDERS`, otherwise the existing `balanceMessage(false, known, false)` ("Connecting to MinimaCore…" / "Open MinimaCore → Apps and check that PandaDEX is enabled."). `OrdersTab.renderOpen` and `TradeView` both use it. Both helpers are now static functions of `(paired, known)` so the full pairing matrix is unit-tested (`BalanceDisplayTest.emptyPlaceholdersOnlyClaimLoadingWhilePaired`), replacing the 0.4.17 assertion that only pinned the constant's wording. Removed the unreachable `addr.isEmpty()` guard inside the receive-card copy listener.

No signing, maker, funding, receipt, export or contract behavior changed.

Validation: 704 JVM tests pass (703 + the new matrix test). Not yet installed on a phone; no store release for 0.4.16–0.4.18.

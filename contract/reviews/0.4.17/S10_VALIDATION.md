# PandaDEX 0.4.17 — Assets and loading-message verification

Follow-up to 0.4.16's stock MINIMA precision fix. Reused existing OrdersTab.WAITING_ORDERS in both order views and MainActivity's actual connection state for the receive-address loading message. A pending wallet load no longer tells an already connected user to connect again. No signing, maker, funding, receipt, or contract behavior changed.

Validation: 12 focused BalanceDisplayTest regressions passed; release lint passed with zero errors and 76 existing warnings. The preceding balance implementation passed all 703 JVM tests. Build 0.4.17 / 417 was built once with the existing release certificate and installed on the S10 Plus with app data retained. ADB verified the installed version and base APK SHA-256.

Actual S10 UI check: NODE connected; native MINIMA available 1003.00000043009800000000000000000000099989999929; MxUSD available 3.04881584; combined available-to-trade value approximately 7.0608 MxUSD at displayed book price 0.004000. Both observations refreshed; the receive address loaded. On cold startup the order view showed “Open orders are loading from MinimaCore.” No funded trades were executed. The phone is left on Assets.

APK SHA-256: 687968d9d369572380e19b9c7cd471c93552feca1ba2dd420c0302fec1f48ad2

Self-review: approved for this bounded correction; the exact stock response is accepted without rounding, invalid balance replies remain unknown, and pre-existing transaction amount limits remain unchanged. No store release has been published for 0.4.16 or 0.4.17. MDS work remains paused; its corresponding balance parser will need this same correction when resumed.

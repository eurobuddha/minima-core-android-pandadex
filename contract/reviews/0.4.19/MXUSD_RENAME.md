# PandaDEX 0.4.19 — the dollar token is MxUSD

Copy-only correction. The Minima-side dollar stablecoin is **MxUSD**; the app, README and contract notes had called it "mxUSDT", which is the family's wrong name for the Minima token (USDT names only the Ethereum ERC-20 leg of a bridge or AtomiX swap). Every user-facing string, comment, doc line and the two Python constants (`MXUSDT` → `MXUSD`) now say MxUSD. The token id is unchanged: `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`.

Deliberately kept: the two trade-export CSV column keys `mxusdt_delta` and `mxusdt_notional` in `TradeExport.TRADESCSV_HEADER`. They are a machine schema parsed by `tools/dexHistory/pandadex_ledger.py` and `pandadex_extend.py` (the reconciled ledgers); renaming them is a ledger migration, not a copy fix, and belongs in a separate change coordinated with those scripts. Java identifiers such as `usdtAmount()`, `USDT_ID`, `USDT_DP` are also unchanged — they are code names, not display text.

No signing, maker, funding, receipt, export-format or contract behavior changed.

Validation: 704 JVM tests pass; `contract/*.py` compile. Not yet installed on a phone; no store release for 0.4.16–0.4.19.

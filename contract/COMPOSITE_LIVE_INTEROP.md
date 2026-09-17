# PandaDEX + PandaPools composite live interop runbook

This is the remaining release-gate proof for composite liquidity. The private-chain harness
(`contract/composite.py`) already proves pool-only, mixed buy, mixed sell, transaction layout,
and adversarial stale/race cases. This runbook is only for the final dust test on the real
mainnet node, where writes spend real MINIMA and MxUSD.

Do not run this from an automated agent session. Use the two Android apps on real devices, or
explicitly approved live-node commands, and stop before any `txnpost` if a preflight check fails.

## Proven sources reused

- PandaDEX V5 order covenant and mainnet proof history: `contract/RESULTS.md`.
- Composite private-chain transaction proof: `contract/composite.py`.
- PandaPools live-node safety model: `/Users/eurobuddha/Projects/minima/support/pandapools/ppmain.py`.
- PandaPools mainnet dust lifecycle evidence: `/Users/eurobuddha/Projects/minima/support/pandapools/RESULTS.md`.

The PandaPools helper is deliberately different from the private harness: it never self-mines
and every write spends real funds. Keep that property for this test.

## Definition of done

All of these must be true before marking composite liquidity complete:

1. PandaDEX discovers a PandaPools-created MINIMA/MxUSD pool through the `PANDAPOOLS`
   sentinel using a bounded `depth:1500` scan.
2. PandaDEX re-derives the pool covenant with `runscript`, requires `parseok:true`, derives the
   address itself, and uses the largest unspent MINIMA and MxUSD reserve coins at that address.
3. A pool-only dust trade is posted by PandaDEX and lands on mainnet.
4. A mixed dust trade is posted by PandaDEX and lands on mainnet with at least one V5 order coin
   and at least one PandaPools reserve pair consumed in the same transaction.
5. PandaPools sees the recreated reserve coins after both trades, at the same pool address, with
   old reserve coin IDs spent and new reserve coin IDs unspent.
6. PandaDEX records one aggregate personal trade for each execution and does not create a phantom
   public tape fill from the pool contribution.
7. Any unfilled balance rests only after the submitted immutable transaction identity is matched
   to an included TxPoW that spends every selected source and pays the exact expected proceeds.
   Source disappearance alone is insufficient; a competing spend must not confirm this trade.

## Preflight

Record these values before posting any live trade:

| item | value |
|---|---|
| chain block before test | |
| PandaDEX version | |
| PandaPools version | |
| MxUSD token id | `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90` |
| pool address | |
| pool MINIMA reserve coin id | |
| pool MxUSD reserve coin id | |
| pool reserves before pool-only trade | |
| pool reserves before mixed trade | |
| V5 order coin id used in mixed trade | |

Read-only commands that are safe for evidence collection:

```text
coins simplestate:true order:desc depth:1500 address:0x50414E4441504F4F4C53
runscript script:<derived-pool-script-json>
coins order:desc depth:1500 address:<derived-pool-address>
coins coinid:<reserve-or-order-coinid>
```

Or use the read-only helper:

```bash
python3 contract/composite_live_preflight.py
```

It performs the bounded sentinel scan, re-derives candidate MxUSD pool covenants with
`runscript`, selects the largest unspent reserve coin for each leg, and prints the pool
address/reserve evidence. It deliberately does not run any write command.

Abort before posting if the sentinel scan is empty after only one transient read, oversized, or
contains a beacon whose covenant does not parse or re-derive to the advertised pool address.
Abort if either reserve leg is missing, spent, or not MINIMA/MxUSD.
Abort if the live node cannot return a current `block` response, for example while it reports
`NO Blocks yet..` or is still syncing.

## Pool-only dust trade

1. Create or identify a dust PandaPools MINIMA/MxUSD pool in PandaPools.
2. Open PandaDEX and wait for the pool liquidity to appear as labelled `POOL` depth.
3. Use a dust amount that can be filled entirely by the pool, with the order book empty or priced
   outside the limit.
4. Confirm the PandaDEX preview shows `POOL` contribution and zero `BOOK` contribution.
5. Post the trade from PandaDEX.
6. Wait for the old reserve coin IDs to disappear on-chain.
7. Verify PandaPools shows recreated reserves at the same pool address.

Evidence to keep:

| item | value |
|---|---|
| PandaDEX preview pay / receive / effective price | |
| worst marginal price | |
| transaction id / TxPoW | |
| old pool MINIMA coin spent | |
| old pool MxUSD coin spent | |
| new pool MINIMA reserve coin id / amount | |
| new pool MxUSD reserve coin id / amount | |

## Mixed dust trade

1. Place a small V5 order in PandaDEX at a price that should be touched before, after, or between
   the pool curve slices.
2. Choose a dust taker amount larger than that order's executable size so the route needs both
   `BOOK` and `POOL` sources.
3. Confirm the preview shows both contributions, touched order IDs, touched pool addresses, worst
   marginal price, price impact, and any resting remainder.
4. Post the trade from PandaDEX.
5. Wait for every source coin ID in the preview to disappear on-chain.
6. Verify the V5 order is fully consumed or recreated as the final partial remainder, and verify
   PandaPools sees the recreated reserve pair.

Evidence to keep:

| item | value |
|---|---|
| side | buy / sell |
| PandaDEX preview pay / receive / effective price | |
| `BOOK` amount | |
| `POOL` amount | |
| touched order coin id(s) | |
| touched pool reserve coin ids | |
| transaction id / TxPoW | |
| order full-fill or remainder coin id | |
| new pool MINIMA reserve coin id / amount | |
| new pool MxUSD reserve coin id / amount | |
| personal-history row | |
| public tape / candle impact | order-fill based only |

## Mandatory abort conditions

Do not post the transaction if any of these occur:

- `txnbasics`, `txncheck.valid.scripts`, `txncheck.valid.basic`,
  `txncheck.valid.mmrproofs`, `txncheck.valid.validamounts`, or signature validation fails.
- `txnexport` is above 60 KiB.
- Wallet funding needs more than eight inputs.
- A funding coin is selected from a pool address or an owner payout address.
- Any planned order or reserve coin moves between preview and posting.
- The mixed transaction would exceed `2 * poolCount + orderCount <= 12`.
- A partial order is not the final order input, or its remainder would not be at `@INPUT+1`.

`txnpost` returning `status:true` is not evidence that the transaction landed. Treat success as
confirmed only when the consumed reserve/order coin IDs disappear on-chain and the recreated coins
are visible.

## Final result note

After the test, append the filled evidence tables and TxPoWs to `contract/RESULTS.md`. Until then,
the composite implementation remains privately proven but not live-interoperability complete.

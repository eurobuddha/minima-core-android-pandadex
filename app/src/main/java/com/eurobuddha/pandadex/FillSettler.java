package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what a scan's whole-coin disappearances actually were, in three layers, strongest first.
 *
 *   1. TRUE HISTORY ({@link DexHistory}) — ask the chain which transaction spent the order coin and
 *      read its outputs. Order-linked and definitive. It is the only layer that survives the
 *      proceeds being spent onward, which is the NORMAL case for your own orders because the maker
 *      re-spends them to fund the next rung. It is also the only thing that can see a cancel posted
 *      from another device on the same seed.
 *   2. EXCLUSIVE PAYOUT EVIDENCE ({@link FillVerifier#verifyBatch}) — for everything history cannot
 *      answer, principally a stranger's order filled by another stranger.
 *   3. UNKNOWN — dropped. A lost trade is invisible; a phantom one is not, and it poisons the one
 *      source of price truth this app has.
 *
 * WHY THIS IS A BATCH. Layer 2's exclusivity rule only works if every vanish from one scan is in
 * front of it at once — a coin must be evidence for at most one order, and that cannot be decided
 * one order at a time. {@link FillTape} emits its disappearances synchronously during
 * {@code ingest}, so they are buffered here and settled when the scan ends.
 *
 * PARTIALS DO NOT COME THROUGH HERE. A partial is proven by its remainder coin: the delta is exact,
 * so it is recorded immediately and needs no adjudication.
 */
public final class FillSettler implements FillTape.Sink {

    /** How the caller records what we decided. Evidence and note are stored on the row so a trade
     *  carries the strength of the proof behind it, not just the fact of it. */
    public interface Outcome {
        void record(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                    boolean takerBuy, boolean partial, String txpowid, String evidence, String note);
        /** Settled as a cancellation — never adjudicate this coin again. */
        void cancelled(String spentCoin);
    }

    public interface BlockSource { long chainBlock(); }

    static final String CHAIN_VERIFIED = "CHAIN_VERIFIED";
    static final String LOCAL_VERIFIED = "LOCAL_VERIFIED";
    static final String NOTE_HISTORY = "Full fill proven by the transaction that spent the order coin";
    static final String NOTE_PAYOUT = "Full fill verified by payout evidence";
    static final String NOTE_PARTIAL = "Partial fill proven by successor order";

    private static final class Vanish {
        final String coinid; final Order5 order; final BigDecimal size, price;
        final boolean takerBuy; final long since;
        Vanish(String coinid, Order5 order, BigDecimal size, BigDecimal price,
               boolean takerBuy, long since) {
            this.coinid = coinid; this.order = order; this.size = size;
            this.price = price; this.takerBuy = takerBuy; this.since = since;
        }
    }

    private final DexHistory history;
    private final FillVerifier verifier;
    private final BlockSource blocks;
    private final Outcome outcome;
    private final List<Vanish> buffer = new ArrayList<>();

    public FillSettler(DexHistory history, FillVerifier verifier, BlockSource blocks, Outcome outcome) {
        this.history = history; this.verifier = verifier; this.blocks = blocks; this.outcome = outcome;
    }

    @Override public void onFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                                 boolean takerBuy, boolean partial, long sinceBlock) {
        if (partial) {
            outcome.record(spentCoin, order, size, price, takerBuy, true, "", LOCAL_VERIFIED, NOTE_PARTIAL);
            return;
        }
        buffer.add(new Vanish(spentCoin, order, size, price, takerBuy, sinceBlock));
    }

    @Override public void onScanComplete() {
        if (buffer.isEmpty()) return;
        // Take the batch and clear, so a scan that starts while these node calls are in flight
        // buffers into a clean list rather than having its vanishes settled twice.
        final List<Vanish> batch = new ArrayList<>(buffer);
        buffer.clear();
        final long seenBlock = blocks.chainBlock();

        List<String> coinids = new ArrayList<>();
        for (Vanish v : batch) coinids.add(v.coinid);

        history.findSpends(coinids, found -> {
            List<FillVerifier.Item> unresolved = new ArrayList<>();
            Map<String, FillVerifier.Verdict> decided = new HashMap<>();
            Map<String, String> chainTxpow = new HashMap<>();
            for (Vanish v : batch) {
                DexHistory.Spend spend = found.get(v.coinid);
                FillVerifier.Verdict verdict = spend == null
                        ? null : DexHistory.verdictFor(spend, v.order);
                if (verdict != null) {
                    decided.put(v.coinid, verdict);
                    chainTxpow.put(v.coinid, spend.txpowid);
                }
                else unresolved.add(new FillVerifier.Item(v.coinid, v.order, v.since));
            }
            if (unresolved.isEmpty()) { apply(batch, decided, chainTxpow); return; }
            verifier.verifyBatch(unresolved, seenBlock, verdicts -> {
                decided.putAll(verdicts);
                apply(batch, decided, chainTxpow);
            });
        });
    }

    private void apply(List<Vanish> batch, Map<String, FillVerifier.Verdict> verdicts,
                       Map<String, String> chainTxpow) {
        for (Vanish v : batch) {
            FillVerifier.Verdict verdict = verdicts.get(v.coinid);
            if (verdict == FillVerifier.Verdict.CANCELLED) { outcome.cancelled(v.coinid); continue; }
            if (verdict != FillVerifier.Verdict.FILLED) continue;      // UNKNOWN is never recorded
            String txpowid = chainTxpow.get(v.coinid);
            boolean fromChain = txpowid != null;
            outcome.record(v.coinid, v.order, v.size, v.price, v.takerBuy, false,
                    txpowid == null ? "" : txpowid,
                    fromChain ? CHAIN_VERIFIED : LOCAL_VERIFIED,
                    fromChain ? NOTE_HISTORY : NOTE_PAYOUT);
        }
    }
}

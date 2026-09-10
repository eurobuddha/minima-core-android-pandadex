package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Settles book changes from included spending transactions. Current UTXO coincidences and
 * copied successor state are candidates, not verified fills. Unresolved candidates are retried
 * on subsequent scans from a durable queue, with bounded work per scan. */
public final class FillSettler implements FillTape.Sink {

    /** How the caller records what we decided. Evidence and note are stored on the row so a trade
     *  carries the strength of the proof behind it, not just the fact of it. */
    public interface Outcome {
        void record(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                    boolean takerBuy, boolean partial, String txpowid, String evidence, String note);
        default void recordAt(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                              boolean takerBuy, boolean partial, String txpowid, String evidence,
                              String note, long timeMs, long block) {
            record(spentCoin, order, size, price, takerBuy, partial, txpowid, evidence, note);
        }
        /** Production stores the trade, correction archive and source retirement atomically. */
        default void recordVerified(Entry entry, DexHistory.Spend spend, Order5 order,
                                    BigDecimal size, BigDecimal price, boolean takerBuy, boolean partial,
                                    String note, long timeMs, long block) {
            recordAt(entry.coinid, order, size, price, takerBuy, partial, spend.txpowid,
                    CHAIN_VERIFIED, note, timeMs, block);
        }
        default void cancelledVerified(Entry entry, DexHistory.Spend spend) { cancelled(entry.coinid); }
        /** Confirmed refund or owner relock: retire this non-trade candidate. */
        void cancelled(String spentCoin);
        default void recoveryError(String message) {}
        default void receiptsRepaired() {}
    }

    public interface BlockSource { long chainBlock(); }

    static final String CHAIN_VERIFIED = "CHAIN_VERIFIED";
    static final String LOCAL_VERIFIED = "LOCAL_VERIFIED";
    static final String NOTE_HISTORY = "Full fill proven by the transaction that spent the order coin";
    static final String NOTE_PAYOUT = "Full fill verified by payout evidence";
    static final String NOTE_PARTIAL = "Partial fill proven by confirmed spending transaction and remainder";

    /** Storage must acknowledge enqueue only after commit. Coin IDs are the idempotence key. */
    interface Store {
        void enqueue(Order5 order);
        default void enqueueHistorical(Order5 order) { enqueue(order); }
        default boolean known(String coinid, String txpowid) { return false; }
        default void retire(Entry entry, DexHistory.Spend spend) { remove(entry.coinid); }
        List<Entry> batch(int limit);
        void remove(String coinid);
        void defer(List<String> coinids);
    }

    static final class Entry {
        final String coinid, json;
        final boolean historical;
        Entry(String coinid, String json) { this(coinid, json, false); }
        Entry(String coinid, String json, boolean historical) {
            this.coinid = coinid; this.json = json; this.historical = historical;
        }
        Order5 order() {
            try {
                Order5 order = Order5.from(new org.json.JSONObject(json));
                return order != null && coinid.equals(order.coinid) ? order : null;
            } catch (Exception e) { return null; }
        }
    }

    private final DexHistory history;
    private final BlockSource blocks;
    private final Outcome outcome;
    private final Store store;
    private boolean checking, discoverNext;
    private boolean publicTurn = true;
    private String lastError = "";

    FillSettler(DexHistory history, BlockSource blocks, Outcome outcome, Store store) {
        this.history = history; this.blocks = blocks; this.outcome = outcome; this.store = store;
    }

    @Override public void onFill(String spentCoin, Order5 order, BigDecimal size, BigDecimal price,
                                 boolean takerBuy, boolean partial, long sinceBlock) {
        if (order == null || !order.coinid.equals(spentCoin))
            throw new IllegalArgumentException("Fill candidate does not match its source coin");
        try { store.enqueue(order); }
        catch (RuntimeException e) {
            reportError(e);
            // Do not let the book snapshot advance past a candidate that was not saved.
            throw e;
        }
    }

    private void reportError() { reportError(null); }
    private void reportError(RuntimeException failure) {
        String message = failure instanceof ChainReview.Conflict
                ? "A stored trade has conflicting inclusion evidence. Earlier evidence is retained while history reconciliation is pending."
                : "Trade-history recovery is paused by a storage error. Saved candidates are retained; free device storage and reopen PandaDEX.";
        if (!message.equals(lastError)) { lastError = message; outcome.recoveryError(message); }
    }

    @Override public void onScanComplete() {
        if (checking || blocks.chainBlock() <= 0) return;
        // Owner and taker recovery can both stay non-empty indefinitely. Reserve an
        // independent public-history turn so they cannot starve the current market.
        if (store instanceof PoolMarket.Store) {
            boolean discoverNow = publicTurn;
            publicTurn = !publicTurn;
            if (discoverNow) { discover(); return; }
        }
        if (store instanceof ChainReview.Store) {
            checking = true;
            history.review((ChainReview.Store) store, blocks.chainBlock(), error -> {
                checking = false;
                if (!error.isEmpty() && !error.equals(lastError)) {
                    lastError = error;
                    try { outcome.recoveryError(error); } catch (RuntimeException ignored) { }
                }
                recoverReceipts();
            });
        } else recoverReceipts();
    }

    private void recoverReceipts() {
        if(store instanceof OwnerRecovery.Store) {
            checking=true;
            new OwnerRecovery((OwnerRecovery.Store)store,history::findSpends).run((attempted,changed,error)->{
                checking=false;
                if(changed)try {outcome.receiptsRepaired();}catch(RuntimeException ignored) {}
                if(!error.isEmpty()&&!error.equals(lastError)) {
                    lastError=error;try {outcome.recoveryError(error);}catch(RuntimeException ignored) {}
                }
                if(!attempted)recoverTakerReceipts();
            });
        }else recoverTakerReceipts();
    }
    private void recoverTakerReceipts() {
        if(store instanceof TakerRecovery.Store) {
            checking=true;
            new TakerRecovery((TakerRecovery.Store)store,history::findSpends).run((attempted,changed,error)->{
                checking=false;
                if(changed)try {outcome.receiptsRepaired();}catch(RuntimeException ignored) {}
                if(!error.isEmpty() && !error.equals(lastError)) {
                    lastError=error;try {outcome.recoveryError(error);}catch(RuntimeException ignored) {}
                }
                if(!attempted)recoverCandidates();
            });
        } else recoverCandidates();
    }

    private void recoverCandidates() {
        if (checking || blocks.chainBlock() <= 0) return;
        final List<Entry> batch;
        try { batch = store.batch(32); }
        catch (RuntimeException e) { reportError(e); return; }
        if (batch.isEmpty() || discoverNext) {
            discoverNext = false;
            discover();
            return;
        }
        discoverNext = true;
        checking = true;
        List<String> coinids = new ArrayList<>();
        for (Entry entry : batch) coinids.add(entry.coinid);
        try {
            history.findMarketSpends(coinids, found -> {
                List<String> retry = new ArrayList<>();
                try {
                    if (!history.recoveryError().isEmpty()) {
                        try { outcome.recoveryError(history.recoveryError()); }
                        catch (RuntimeException ignored) { /* A closed UI cannot stop receipt recovery. */ }
                    }
                    for (Entry entry : batch) {
                        boolean retired = false;
                        try {
                            DexHistory.Spend spend = found.get(entry.coinid);
                            if (!settle(entry, spend)) continue;
                            store.retire(entry, spend);
                            retired = true;
                        } catch (RuntimeException e) { reportError(e); }
                        finally {
                            if (!retired) retry.add(entry.coinid);
                        }
                    }
                } finally {
                    try { if (!retry.isEmpty()) store.defer(retry); }
                    catch (RuntimeException e) { reportError(e); }
                    finally { checking = false; }
                }
            });
        } catch (RuntimeException e) { checking = false; reportError(e); }
    }

    private void discover() {
        checking = true;
        try {
            history.discover(new DexHistory.Discovery() {
                public boolean known(String coinid, String txpowid) { return store.known(coinid, txpowid); }
                public void found(Order5 order, DexHistory.Spend spend) {
                    // Commit the input before recording or advancing the history cursor.
                    store.enqueueHistorical(order);
                    Entry entry = new Entry(order.coinid, order.sourceJson(), true);
                    if (settle(entry, spend)) store.retire(entry, spend);
                }
            }, () -> {
                checking = false;
                if (!history.recoveryError().isEmpty()) {
                    try { outcome.recoveryError(history.recoveryError()); }
                    catch (RuntimeException ignored) { }
                }
            });
        } catch (RuntimeException e) { checking = false; reportError(e); }
    }

    boolean settle(Entry entry, DexHistory.Spend spend) {
        Order5 order = entry.order();
        if (order == null) { reportError(); return false; }
        if (spend == null || spend.confirmations < 0 || blocks.chainBlock() <= 0) return false;
        FillVerifier.Verdict verdict = DexHistory.verdictFor(spend, order);
        if (verdict == FillVerifier.Verdict.CANCELLED || DexHistory.relockSuccessor(spend, order) != null) {
            outcome.cancelledVerified(entry, spend);
            return true;
        }
        PartialFill partial = partialFill(spend, order);
        if (verdict != FillVerifier.Verdict.FILLED && partial == null) return false;
        // Importing an old trade at today's observation time would falsify current volume.
        if (entry.historical && spend.inclusionTimeMs <= 0) return false;
        boolean actualPartial = partial != null;
        outcome.recordVerified(entry, spend, order,
                actualPartial ? partial.minima : order.minimaAmount(),
                actualPartial ? PriceMath.price(partial.usdt, partial.minima) : order.price(),
                order.sell, actualPartial,
                (actualPartial ? NOTE_PARTIAL : NOTE_HISTORY)
                        + (spend.inclusionTimeMs > 0 ? ChainEvidence.BLOCK_TIME_NOTE : ChainEvidence.OBSERVED_TIME_NOTE),
                spend.inclusionTimeMs > 0 ? spend.inclusionTimeMs : System.currentTimeMillis(),
                spend.inclusionBlock > 0 ? spend.inclusionBlock : blocks.chainBlock());
        return true;
    }

    static boolean recentForNotification(long timeMs, long nowMs) {
        return timeMs > 0 && nowMs >= timeMs && nowMs - timeMs <= 2 * 60_000L;
    }

    static final class PartialFill {
        final BigDecimal minima, usdt;
        PartialFill(BigDecimal minima, BigDecimal usdt) { this.minima = minima; this.usdt = usdt; }
    }

    /** A book-diff amount is a candidate hint. Transaction payments determine actual volume. */
    static PartialFill partialFill(DexHistory.Spend spend, Order5 order) {
        if (spend == null || spend.confirmations < 0 || order == null || spend.inputIndex < 0) return null;
        org.json.JSONObject pay = spend.outputs.optJSONObject(spend.inputIndex);
        org.json.JSONObject rem = spend.outputs.optJSONObject(spend.inputIndex + 1);
        if (!DexHistory.paysTo(pay, order.wantAddr) || !DexHistory.paysTo(rem, DexContract.ADDR_V5)
                || !DexHistory.sameToken(pay.optString("tokenid"), order.wantTok)
                || !DexHistory.sameToken(rem.optString("tokenid"), order.lockedTok)) return null;
        BigDecimal left = DexHistory.value(rem);
        BigDecimal taken = order.locked.subtract(left);
        BigDecimal paid = DexHistory.value(pay);
        if (left.signum() <= 0 || taken.signum() <= 0 || paid.signum() <= 0) return null;
        if (paid.multiply(order.locked).compareTo(order.wantAmt.multiply(taken)) < 0) return null;
        return new PartialFill(order.sell ? taken : paid, order.sell ? paid : taken);
    }

    static boolean partialMatches(DexHistory.Spend spend, Order5 order, BigDecimal size) {
        PartialFill actual = partialFill(spend, order);
        return actual != null && size != null && actual.minima.compareTo(size) == 0;
    }
}

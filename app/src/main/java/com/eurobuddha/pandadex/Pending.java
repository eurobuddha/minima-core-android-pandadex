package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Durable unresolved order receipts. A missing reply or elapsed time is not rejection evidence.
 * Read-before-write serialization follows PandaPools ActivityLog, without its display retention cap. */
public final class Pending {

    public static final String PLACE = "PLACE";
    public static final String CANCEL = "CANCEL";
    public static final String EDIT = "EDIT";

    /** After this long without the chain showing the result, say so plainly. */
    private static final long SLOW_MS = 3 * 60_000;
    /** Stop claiming anything is in progress after this long — the chain is the truth and the
     *  book itself will show the real state. */
    private static final long GIVEUP_MS = 20 * 60_000;
    private static final String PREFS = "pandadex_pending";
    private static final String KEY = "rows";

    public static final class Row {
        public String kind;
        public String receiptId = java.util.UUID.randomUUID().toString();
        public boolean delayedNotified;
        public String orderId;
        public String coinid;        // CANCEL/EDIT target coin
        public boolean buy;
        public BigDecimal minima;
        public BigDecimal price;
        public long submitMs;
        public long submitBlock;
        public JSONObject creation;  // Exact funding and outputs saved before any signing.
        public String editSource="",editWant=""; // Exact owner-relock expectation; display price is not the proof.
        public String cancelSource=""; // Original order identity/economics; excludes unrelated token metadata.
        public String phase = "", transactionHandle = "", postedId = "";

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("receiptId", receiptId);
            o.put("delayedNotified", delayedNotified);
            o.put("orderId", orderId);
            o.put("coinid", coinid == null ? "" : coinid);
            o.put("buy", buy);
            o.put("minima", minima.toPlainString());
            o.put("price", price.toPlainString());
            o.put("submitMs", submitMs);
            o.put("submitBlock", submitBlock);
            o.put("creation", creation);
            o.put("cancelSource",cancelSource);
            o.put("editSource",editSource);o.put("editWant",editWant);
            o.put("phase", phase);
            o.put("transactionHandle", transactionHandle);
            o.put("postedId", postedId);
            return o;
        }

        static Row from(JSONObject o) throws org.json.JSONException {
            Row r = new Row();
            r.kind = receiptText(o,"kind",null);
            if(!PLACE.equals(r.kind)&&!CANCEL.equals(r.kind)&&!EDIT.equals(r.kind))
                throw new IllegalArgumentException("Unknown receipt kind");
            // Missing new fields are legacy; explicitly malformed fields are never defaults.
            r.receiptId = o.has("receiptId") ? receiptIdentity(o.opt("receiptId"))
                    : java.util.UUID.nameUUIDFromBytes(
                            o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            r.delayedNotified = receiptFlag(o,"delayedNotified",false);
            r.orderId = receiptText(o,"orderId",null);
            r.coinid = receiptText(o,"coinid",null);
            if(!FundingCoins.hex(r.orderId)||(!r.coinid.isEmpty()&&!FundingCoins.hex(r.coinid))
                    ||(!PLACE.equals(r.kind)&&r.coinid.isEmpty()))throw new IllegalArgumentException("Invalid receipt source identity");
            r.buy = receiptFlag(o,"buy",null);
            r.minima = MakerConfig.storedDecimal(o.get("minima"));
            r.price = MakerConfig.storedDecimal(o.get("price"));
            // Display prices can round to zero; exact economics are checked by the proof verifier.
            if(r.minima.signum()<0||r.price.signum()<0)throw new IllegalArgumentException("Negative receipt amount");
            r.submitMs = MakerConfig.storedBlock(o.get("submitMs"));
            r.submitBlock = MakerConfig.storedBlock(o.get("submitBlock"));
            if(o.has("creation")) {
                Object creation=o.get("creation");
                if(!(creation instanceof JSONObject))throw new IllegalArgumentException("Invalid creation receipt");
                r.creation=(JSONObject)creation;
            }
            r.cancelSource=receiptText(o,"cancelSource","");
            r.editSource=receiptText(o,"editSource","");r.editWant=receiptText(o,"editWant","");
            r.phase = receiptText(o,"phase","");
            if(!java.util.Arrays.asList("","PREPARED","POSTING","UNKNOWN","SUBMITTED","NOT_SUBMITTED").contains(r.phase))
                throw new IllegalArgumentException("Unknown receipt phase");
            r.transactionHandle = receiptText(o,"transactionHandle","");
            r.postedId = receiptText(o,"postedId","");
            if(!validIntent(r))throw new IllegalArgumentException("Incomplete or inconsistent receipt intent");
            return r;
        }

        public boolean isRenewal() {
            if(!EDIT.equals(kind)||!validIntent(this)||editSource.isEmpty()||editWant.isEmpty())return false;
            try{Order5 source=sourceOrder(editSource);BigDecimal want=Util.decOr(editWant,null);
                return source!=null&&want!=null&&source.wantAmt.compareTo(want)==0;
            }catch(Exception malformed){return false;}
        }

        /** Show persisted evidence, never infer submission or rejection from elapsed time. */
        public String status(long chainBlock) {
            if ("RECOVERY_ERROR".equals(kind)) return "Stored receipts could not be read. Trading is paused; preserve app data for recovery.";
            if ("NOT_SUBMITTED".equals(phase))
                return "Not submitted — this attempt stopped before posting. No transaction was sent.";
            if (PLACE.equals(kind) && creation == null)
                return "Older order receipt: funding details were not saved. Its creation cannot yet be verified; check live orders and trade history.";
            if ("PREPARED".equals(phase))
                return "Transaction intent saved; submission is not confirmed. Keep this receipt while recovery checks the chain.";
            long secs = Math.max(0, (System.currentTimeMillis() - submitMs) / 1000);
            String verb = PLACE.equals(kind) ? "Sending"
                        : CANCEL.equals(kind) ? "Cancelling" : "Updating price";
            // An open-ended "Sending…" tells the user nothing about whether to keep waiting.
            // Blocks land roughly every 50s, so show the clock AND what we're waiting for —
            // and once it is clearly overdue, say that plainly instead of spinning forever.
            String clock = secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
            if (PLACE.equals(kind)||!phase.isEmpty()) {
                String evidence = "SUBMITTED".equals(phase) ? "Node accepted submission"
                        : "POSTING".equals(phase) ? "Submission requested"
                        : "Submission outcome unknown";
                return evidence + " · " + clock + ". Checking the exact transaction on-chain; receipt retained.";
            }
            if (System.currentTimeMillis() - submitMs > SLOW_MS) {
                return verb + " — " + clock + ", longer than usual. Its outcome is unknown; "
                        + "this receipt is retained while the chain is checked.";
            }
            return verb + "… waiting for the next block (~50s) · " + clock;
        }
    }

    /** IDs are opaque for legacy compatibility, but must be unambiguous stored strings. */
    private static String receiptIdentity(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid receipt identity");
        String id = (String) value;
        if (id.trim().isEmpty() || id.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid receipt identity");
        return id;
    }

    private static String receiptText(JSONObject row,String key,String fallback) throws org.json.JSONException {
        String value=MakerConfig.jsonString(row,key,fallback);
        if(value==null||value.indexOf(0)>=0)throw new IllegalArgumentException("Invalid receipt text");
        return value;
    }
    private static boolean receiptFlag(JSONObject row,String key,Boolean fallback) throws org.json.JSONException {
        Object value=row.has(key)?row.get(key):fallback;
        if(!(value instanceof Boolean))throw new IllegalArgumentException("Invalid receipt flag");
        return (Boolean)value;
    }

    interface Store extends OwnerReceipt.Store {
        String read(); boolean write(String json);
        default void completed(OwnerReceipt receipt){throw new IllegalStateException("Completed receipt storage is unavailable");}
    }
    private static final Object STORE_LOCK = new Object();
    private final Store store;

    public Pending(Context ctx) { this(ctx,null); }
    Pending(Context ctx,OwnerReceipt.Store completed) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        store = new Store() {
            public String read() { return prefs.getString(KEY, "[]"); }
            public boolean write(String json) { return prefs.edit().putString(KEY, json).commit(); }
            public void completed(OwnerReceipt receipt) {
                if(completed==null)throw new IllegalStateException("Completed receipt storage is unavailable");
                completed.completed(receipt);
            }
        };
    }
    Pending(Store store) { this.store = store; }

    private List<Row> load() {
        try {
            JSONArray data = MakerConfig.storedArray(store.read());
            List<Row> result = new ArrayList<>();
            java.util.Set<String> identities = new java.util.HashSet<>();
            for (int i = 0; i < data.length(); i++) {
                Row row = Row.from(data.getJSONObject(i));
                if (!identities.add(row.receiptId)) throw new IllegalArgumentException("Duplicate receipt identity");
                result.add(row);
            }
            return result;
        } catch (Exception invalid) {
            // Never replace unreadable evidence with an empty list on the next save.
            throw new IllegalStateException("Stored receipts could not be read; original data preserved", invalid);
        }
    }
    private void save(List<Row> all) {
        try {
            JSONArray data = new JSONArray();
            java.util.Set<String> identities = new java.util.HashSet<>();
            for (Row row : all) {
                if (!identities.add(receiptIdentity(row.receiptId))) throw new IllegalArgumentException("Duplicate receipt identity");
                JSONObject encoded=row.json();
                Row.from(encoded); // A new/update row must satisfy the same reader before any write.
                data.put(encoded);
            }
            if (!store.write(data.toString())) throw new IllegalStateException("Could not persist receipt update");
        } catch (Exception failure) { throw new IllegalStateException("Receipt update was not saved", failure); }
    }
    public boolean healthy() {
        synchronized (STORE_LOCK) { try { load(); return true; } catch (IllegalStateException invalid) { return false; } }
    }
    public List<Row> rows() {
        synchronized (STORE_LOCK) {
            try { return load(); }
            catch (IllegalStateException invalid) {
                Row error = new Row(); error.kind = "RECOVERY_ERROR";
                error.minima = error.price = BigDecimal.ZERO;
                return java.util.Collections.singletonList(error);
            }
        }
    }
    /** Durable owner attempts, independent of the processor's temporary pacing markers. */
    java.util.Set<String> unresolvedOwnerCoins() {
        synchronized (STORE_LOCK) {
            java.util.Set<String> result = new java.util.HashSet<>();
            for (Row row : load()) {
                if (!CANCEL.equals(row.kind) && !EDIT.equals(row.kind)) continue;
                if ("NOT_SUBMITTED".equals(row.phase)) continue;
                if (!FundingCoins.hex(row.coinid)) throw new IllegalStateException("Owner receipt source is unreadable");
                result.add(row.coinid.toLowerCase(java.util.Locale.ROOT));
            }
            return result;
        }
    }

    public void add(Row row) {
        synchronized (STORE_LOCK) {
            List<Row> all = load();
            all.removeIf(r -> r.receiptId.equals(row.receiptId));
            all.add(row); save(all);
        }
    }
    /** Both manual and automated creation use the same durable pre-sign/post boundary. */
    DexTxn.Result creationResult(Row row,DexTxn.Result callback) {
        return intentResult(java.util.Collections.singletonList(row),true,callback);
    }

    /** One atomic receipt write covers every source in a cancellation batch. */
    DexTxn.Result cancellationResult(List<Order5> orders,long block,DexTxn.Result callback) {
        List<Row> receipts=new ArrayList<>();java.util.Set<String> sources=new java.util.HashSet<>();
        if(orders==null||orders.isEmpty()||orders.size()>SweepPlanner.MAX_ORDERS)throw new IllegalArgumentException("Invalid cancellation batch");
        for(Order5 order:orders) {
            if(order==null||!FundingCoins.hex(order.coinid)||!sources.add(order.coinid.toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Invalid cancellation source");
            Row row=new Row();row.kind=CANCEL;row.orderId=order.orderId;row.coinid=order.coinid;
            row.buy=!order.sell;row.minima=order.minimaAmount();row.price=order.price();
            row.submitMs=System.currentTimeMillis();row.submitBlock=block;row.cancelSource=ownerSource(order);
            if(row.cancelSource==null||row.cancelSource.isEmpty())throw new IllegalArgumentException("Missing cancellation source");
            receipts.add(row);
        }
        return intentResult(receipts,false,callback);
    }

    /** Save the exact new STATE(2), including unchanged-price GTC renewal. */
    DexTxn.Result relockResult(Order5 order,BigDecimal want,long block,DexTxn.Result callback) {
        if(!DexTxn.safeOrder(order)||!DexTxn.amountOk(want)||want.stripTrailingZeros().scale()>8)
            throw new IllegalArgumentException("Invalid relock intent");
        Row row=new Row();row.kind=EDIT;row.coinid=order.coinid;row.orderId=order.orderId;row.buy=!order.sell;
        row.minima=order.sell?order.locked:want;
        row.price=order.sell?PriceMath.price(want,order.locked):PriceMath.price(order.locked,want);
        row.submitMs=System.currentTimeMillis();row.submitBlock=block;
        row.editWant=want.toPlainString();row.editSource=ownerSource(order);
        return intentResult(java.util.Collections.singletonList(row),false,callback);
    }

    private static String ownerSource(Order5 order) {
        try {
            // The same effective state fields used by DexTxn's owner-relock builder. Port6 is
            // display-only; token metadata and extra coin fields do not participate in this proof.
            JSONObject state=new JSONObject().put("0",order.ownerPk).put("1",order.wantAddr)
                    .put("2",order.wantAmt.toPlainString()).put("3",order.wantTok).put("4",order.orderId)
                    .put("5",order.sell?"1":"0").put("7",order.gtc?"1":"0").put("8",order.minRem.toPlainString());
            JSONObject source=new JSONObject().put("coinid",order.coinid).put("tokenid",order.lockedTok)
                    .put("created",order.created).put("state",state);
            source.put(DexHistory.isMinima(order.lockedTok)?"amount":"tokenamount",order.locked.toPlainString());
            return source.toString();
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid cancellation source",invalid);}
    }

    /** Creation's acknowledged journal, shared by cancellations without a UI-only success write. */
    private DexTxn.Result intentResult(List<Row> receipts,boolean creation,DexTxn.Result callback) {
        return new DexTxn.Result() {
            private boolean prepared,attempted,finished;
            public boolean onPrepared(String handle) {
                if(prepared||finished)return false;
                try {
                    if(!callback.onPrepared(handle))return false;
                    synchronized(STORE_LOCK) {
                        List<Row> all=load();
                        for(Row row:receipts) {
                            for(Row existing:all)if(existing.receiptId.equals(row.receiptId)
                                    ||(creation&&PLACE.equals(existing.kind)&&row.orderId.equalsIgnoreCase(existing.orderId)))return false;
                            row.transactionHandle=handle;row.phase="PREPARED";all.add(row);
                        }
                        save(all);prepared=true;
                    }
                    return true;
                }catch(RuntimeException failure){return false;}
            }
            public boolean beforePost() {
                if(!prepared||attempted||finished)return false;
                try {
                    if(!callback.beforePost()||!phases(receipts,"POSTING","",true))return false;
                    attempted=true;return true;
                }catch(RuntimeException failure){return false;}
            }
            public void onPosted(String id) {
                if(finished)return;finished=true;
                // POSTING is already durable. A failed status write cannot erase that uncertainty.
                try{phases(receipts,"SUBMITTED",id,false);}catch(RuntimeException failure){}
                callback.onPosted(id);
            }
            public void onFailed(String message) {
                if(finished)return;finished=true;
                try{phases(receipts,attempted?"UNKNOWN":"NOT_SUBMITTED","",false);}
                catch(RuntimeException failure){message+=" Receipt status could not be saved; keep app data for recovery.";}
                callback.onFailed(message);
            }
        };
    }

    private boolean phases(List<Row> receipts,String phase,String postedId,boolean requireAll) {
        synchronized(STORE_LOCK) {
            List<Row> all=load();java.util.Set<String> wanted=new java.util.HashSet<>();
            for(Row row:receipts)wanted.add(row.receiptId);
            int found=0;for(Row current:all)if(wanted.contains(current.receiptId))found++;
            if(found==0||(requireAll&&found!=wanted.size()))return false;
            for(Row current:all)if(wanted.contains(current.receiptId)) {
                current.phase=phase;if(!postedId.isEmpty())current.postedId=postedId;
            }
            save(all);return true; // Missing/resolved rows are never resurrected by a late reply.
        }
    }

    private boolean complete(Row row,java.util.Map<String,DexHistory.Spend> found) {
        OwnerReceipt receipt=OwnerReceipt.capture(row,found);
        synchronized (STORE_LOCK) {
            List<Row> all=load();Row current=null;
            for(Row candidate:all)if(candidate.receiptId.equals(row.receiptId)){current=candidate;break;}
            if(current==null)return false;
            if(!receipt.matchesIntent(current))throw new IllegalStateException("Receipt changed during verification");
            // Archive/check commit first. A failed cleanup leaves a safe, idempotent replay.
            store.completed(receipt);
            all.remove(current);save(all);return true;
        }
    }
    private boolean markDelayed(Row row) {
        synchronized (STORE_LOCK) {
            List<Row> all = load();
            for (Row current : all) if (current.receiptId.equals(row.receiptId) && !current.delayedNotified) {
                current.delayedNotified = true; save(all); return true;
            }
            return false;
        }
    }

    /** Told when a row completes, so the host can announce it. */
    public interface Listener {
        /** Exact creation transaction is included. The order may already have filled. */
        void onLive(Row r);
        /** A cancel or reprice took effect. */
        void onSettled(Row r);
        /** Still unresolved after GIVEUP_MS. Notify once, retaining the receipt for recovery. */
        default void onGaveUp(Row r) {}
        /** Confirmed spending transaction paid for the order instead of refunding it. */
        default void onFilledInstead(Row r) {}
        /** The source was refunded instead of being edited. */
        default void onCancelledInstead(Row r) {}
        default void onRecoveryError(String message) {}
    }

    /** Only linked included transactions resolve a receipt; public order IDs are copyable. */
    private boolean checking;
    public void reconcile(DexHistory history, java.util.Map<String, Order5> book, long chainBlock,
                          java.util.function.Predicate<Order5> owns, Listener listener) {
        if (!healthy()) return;
        resolve(book, chainBlock, listener);
        if (checking) return;
        java.util.List<Row> waiting = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Row r : rows()) {
            if("NOT_SUBMITTED".equals(r.phase))continue;
            if (PLACE.equals(r.kind) && r.creation != null && !"NOT_SUBMITTED".equals(r.phase)) {
                List<String> funding = CreationEvidence.inputs(r);
                if (!funding.isEmpty()) { waiting.add(r); ids.addAll(funding); }
            } else if ((CANCEL.equals(r.kind) || EDIT.equals(r.kind)) && FundingCoins.hex(r.coinid) && !book.containsKey(r.coinid)) {
                waiting.add(r); ids.add(r.coinid);
            }
        }
        if (ids.isEmpty()) return;
        checking = true;
        history.findSpends(ids, found -> {
            try {
                for (Row r : waiting) {
                    if (PLACE.equals(r.kind)) {
                        Order5 created = CreationEvidence.match(r, found);
                        if (created != null && owns.test(created) && complete(r,found) && listener != null) listener.onLive(r);
                        continue;
                    }
                    DexHistory.Spend spend = found.get(r.coinid);
                    Order5 original = spend == null ? null : Order5.from(spend.input);
                    if (original == null || !owns.test(original) || !r.orderId.equals(original.orderId)) continue;
                    if(CANCEL.equals(r.kind)&&!cancelSourceMatches(r,original))continue;
                    if(EDIT.equals(r.kind)&&!editSourceMatches(r,original))continue;
                    if (EDIT.equals(r.kind) && editMatches(r, spend, original)) {
                        if (complete(r,found) && listener != null) listener.onSettled(r);
                        continue;
                    }
                    FillVerifier.Verdict verdict = DexHistory.verdictFor(spend, original);
                    if (verdict == null || !complete(r,found)) continue;
                    if (listener != null) {
                        if (verdict == FillVerifier.Verdict.CANCELLED) {
                            if (EDIT.equals(r.kind)) listener.onCancelledInstead(r); else listener.onSettled(r);
                        } else listener.onFilledInstead(r);
                    }
                }
            } catch(RuntimeException failure) {
                if(listener!=null)listener.onRecoveryError("Operation recovery could not complete. Saved evidence is retained; preserve app data for recovery.");
                throw failure;
            } finally { checking = false; }
        });
    }

    /** Only records with no modern metadata or payload can use the older proof path. */
    private static boolean legacyIntent(Row row) {
        return row.phase.isEmpty()&&row.transactionHandle.isEmpty()&&row.postedId.isEmpty()
                &&row.creation==null&&row.cancelSource.isEmpty()&&row.editSource.isEmpty()&&row.editWant.isEmpty();
    }
    private static Order5 sourceOrder(String raw) {
        try{return Order5.from(MakerConfig.storedObject(raw));}
        catch(Exception malformed){return null;}
    }
    private static boolean validIntent(Row row) {
        if(legacyIntent(row))return true;
        if(PLACE.equals(row.kind))return row.creation!=null&&row.cancelSource.isEmpty()
                &&row.editSource.isEmpty()&&row.editWant.isEmpty();
        if(row.creation!=null)return false;
        String saved;
        if(CANCEL.equals(row.kind)) {
            if(!row.editSource.isEmpty()||!row.editWant.isEmpty())return false;
            saved=row.cancelSource;
        }else if(EDIT.equals(row.kind)) {
            if(!row.cancelSource.isEmpty())return false;
            BigDecimal wanted=Util.decOr(row.editWant,null);
            if(!DexTxn.amountOk(wanted)||wanted.stripTrailingZeros().scale()>8)return false;
            saved=row.editSource;
        }else return false;
        Order5 source=sourceOrder(saved);
        return DexTxn.safeOrder(source)&&row.coinid.equalsIgnoreCase(source.coinid)
                &&row.orderId.equalsIgnoreCase(source.orderId)&&row.buy==!source.sell;
    }

    /** Pin the saved cancellation source to the included input before classifying its refund/fill.
     * Same owner/payout/token/amount invariants as the existing owner-relock evidence check. */
    static boolean cancelSourceMatches(Row row,Order5 actual) {
        return row!=null&&CANCEL.equals(row.kind)&&validIntent(row)
                &&(legacyIntent(row)||sourceMatches(row.coinid,row.cancelSource,actual));
    }
    private static boolean sourceMatches(String coinid,String source,Order5 actual) {
        try {
            Order5 expected=sourceOrder(source);
            return expected!=null&&actual!=null&&coinid.equalsIgnoreCase(expected.coinid)
                    &&expected.coinid.equalsIgnoreCase(actual.coinid)&&expected.orderId.equalsIgnoreCase(actual.orderId)
                    &&expected.ownerPk.equalsIgnoreCase(actual.ownerPk)&&expected.wantAddr.equalsIgnoreCase(actual.wantAddr)
                    &&expected.lockedTok.equalsIgnoreCase(actual.lockedTok)&&expected.wantTok.equalsIgnoreCase(actual.wantTok)
                    &&expected.locked.compareTo(actual.locked)==0&&expected.wantAmt.compareTo(actual.wantAmt)==0
                    &&expected.minRem.compareTo(actual.minRem)==0&&expected.sell==actual.sell&&expected.gtc==actual.gtc;
        }catch(Exception malformed){return false;}
    }

    static boolean editSourceMatches(Row row,Order5 actual) {
        if(!validIntent(row))return false;
        if(legacyIntent(row))return true; // Original price-only record, without modern markers.
        BigDecimal wanted=Util.decOr(row.editWant,null);
        return sourceMatches(row.coinid,row.editSource,actual)&&DexTxn.amountOk(wanted)
                &&wanted.stripTrailingZeros().scale()<=8;
    }

    /** Link the exact original input to the requested owner re-lock, never a copied book row. */
    static boolean editMatches(Row row, DexHistory.Spend spend, Order5 original) {
        if(row==null||original==null||!EDIT.equals(row.kind)||!row.coinid.equalsIgnoreCase(original.coinid)
                ||!row.orderId.equalsIgnoreCase(original.orderId))return false;
        if(!editSourceMatches(row,original))return false;
        BigDecimal expected;
        if(!legacyIntent(row)) {
            expected=Util.decOr(row.editWant,null);
            if(!DexTxn.amountOk(expected)||expected.stripTrailingZeros().scale()>8)return false;
        }else {
            // Legacy UI receipts stored only the requested price; retain their existing proof path.
            if(row.price==null||row.price.signum()<=0)return false;
            expected=original.sell
                    ?PriceMath.up(original.locked.multiply(row.price,PriceMath.MC),PriceMath.USDT_DP)
                    :PriceMath.down(original.locked.divide(row.price,PriceMath.MINIMA_DP,java.math.RoundingMode.DOWN),PriceMath.MINIMA_DP);
        }
        Order5 next=DexHistory.relockSuccessor(spend,original);
        return next!=null&&expected.compareTo(next.wantAmt)==0;
    }

    public boolean resolve(java.util.Map<String, Order5> book, long chainBlock, Listener listener) {
        if (!healthy()) return false;
        for (Row row : rows()) {
            if (!"NOT_SUBMITTED".equals(row.phase) && System.currentTimeMillis() - row.submitMs > GIVEUP_MS && markDelayed(row)) {
                // A deadline is only a notification. Never erase unresolved operations.
                if (listener != null) listener.onGaveUp(row);
            }
        }
        return false;
    }
}

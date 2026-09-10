package com.eurobuddha.pandadex;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The market maker's settings and its memory of which on-chain order belongs to which rung.
 *
 * The rung rows are stored POSITIONALLY (index i = rung i+1 out from the mid) and are never
 * sanitized/sorted here: while pegged, a rung's SIZE is the user's per-rung choice and its
 * position IS its identity — sorting would silently reassign sizes to different rungs. The
 * manual branch of {@link MakerLadder#desired} sanitizes its own copies.
 *
 * The slot→order records are the important part: without them a restart would not recognise
 * the ladder already on the book and would post a second one on top of it. Each record also
 * carries WHEN it was sent (block height), because an order takes ~1 block of PoW + mining to
 * surface in the confirmed book — a record whose order is not yet visible is IN FLIGHT, not
 * missing, and re-creating it would duplicate real on-chain funds (observed live in 0.2.6).
 * Cancel tombstones are the same idea for the reverse direction: an orderId we have asked to
 * cancel but whose coin may still surface — the engine keeps cancelling anything tombstoned
 * until it is gone, which is what finally catches late-confirming orphans.
 */
public final class MakerConfig {

    private static final String PREFS = "pandadex_maker";
    private static final String K_LEVELS = "levels";     // legacy 0.2.x offset ladder (migration only)
    private static final String K_ASKS = "asks";
    private static final String K_BIDS = "bids";
    private static final String K_PEGGED = "pegged";
    private static final String K_STEP = "step";
    private static final String K_NLEVELS = "nlevels";
    private static final String K_ASKSIZE = "asksize";
    private static final String K_BIDSIZE = "bidsize";
    private static final String K_MID = "manualmid";
    private static final String K_SKEW = "skew";
    private static final String K_REPRICE = "reprice";
    private static final String K_ARMED = "armed";
    private static final String K_QUOTE_REVISION = "quote_revision";
    private static final String K_SLOTS = "slots";       // legacy 0.2.x id map (migration only)
    private static final String K_SIZES = "slotsizes";   // legacy 0.2.x size map (migration only)
    private static final String K_SLOTS2 = "slots2";
    private static final String K_TOMB = "tombstones";
    private static final String K_LASTMID = "lastmid";

    private static final Object STORE_LOCK = new Object();
    private final SharedPreferences prefs;
    private Map<String,Object> loadedSnapshot;
    // A restored price/armed snapshot must not reauthorize callbacks queued before a change.
    private String quoteRevision = "";
    String quoteRevision(){return quoteRevision;}
    private static volatile boolean settingsConflict;
    private static Map<String,Object> ownedSnapshot(Map<String,?> values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:new String[]{K_LEVELS,K_ASKS,K_BIDS,K_PEGGED,K_STEP,K_NLEVELS,K_ASKSIZE,K_BIDSIZE,K_MID,K_SKEW,K_REPRICE,K_ARMED,K_QUOTE_REVISION,K_SLOTS,K_SIZES,K_SLOTS2,K_TOMB,K_LASTMID,"prepared_create"})
            if(values.containsKey(key))result.put(key,values.get(key));
        return result;
    }
    private static volatile boolean storageFailed;
    private boolean readable = true;
    /** False means stored maker evidence was not completely decoded; never overwrite it. */
    public boolean readable(){return readable;}

    public static boolean storageHealthy(){return !storageFailed;}
    private static boolean failedSave(){settingsConflict=false;storageFailed=true;return false;}
    public static boolean settingsChangedElsewhere(){return settingsConflict;}
    public static String storageFailureMessage(){return settingsConflict
            ?"Maker settings changed in another session. Reload and review them before saving again."
            :"Maker settings could not be saved. Check available storage.";}
    /** Only an explicit user action may resume maker quoting after a failed disk acknowledgement. */
    public boolean saveUserAction(){synchronized(STORE_LOCK){if(!save())return false;storageFailed=false;settingsConflict=false;return true;}}
    static void resetStorageForTests(){storageFailed=false;settingsConflict=false;}

    /** What we posted for one rung, and when. */
    public static final class SlotRec {
        public final String orderId;
        public final BigDecimal size;        // requested MINIMA size, used for deliberate resizing
        public final BigDecimal locked;      // original funded amount; unchanged by repricing
        public final String lockedToken;
        /** Block the CREATE send was accepted at. 0 = unknown (legacy/restart) — the engine
         *  stamps the current block on first sight, giving a fresh patience window. */
        public long sentBlock;
        /** Block a RELOCK was sent for this slot, or 0 for none in flight. Explicitly not
         *  "max(create, relock)": the old coin stays visible at its old price until the
         *  relock mines, so without an unambiguous marker every cycle would re-relock it. */
        public long lastActionBlock;

        public SlotRec(String orderId, BigDecimal size, long sentBlock, long lastActionBlock) {
            this(orderId,size,sentBlock,lastActionBlock,null,"");
        }
        public SlotRec(String orderId,BigDecimal size,long sentBlock,long lastActionBlock,BigDecimal locked,String token) {
            this.locked=locked;this.lockedToken=token==null?"":token;
            this.orderId = orderId;
            this.size = size;
            this.sentBlock = sentBlock;
            this.lastActionBlock = lastActionBlock;
        }
    }

    /** Positional rung rows (index = rung). See class doc — never sorted, blanks are gaps. */
    public final List<MakerLadder.Level> asks = new ArrayList<>();
    public final List<MakerLadder.Level> bids = new ArrayList<>();

    /** Auto-fill seed parameters — UI conveniences only; the engine never reads them. */
    public boolean pegged = true;
    public BigDecimal stepPct = new BigDecimal("0.20");
    public int levelCount = 3;
    public BigDecimal askSize = BigDecimal.ZERO;
    public BigDecimal bidSize = BigDecimal.ZERO;
    /** The mid typed into the auto-fill row while unpegged — a seed for generating rungs,
     *  remembered so reopening the tab shows what the ladder was built from. */
    public BigDecimal manualMid = BigDecimal.ZERO;

    public BigDecimal skewPct = BigDecimal.ZERO;
    public BigDecimal repricePct = new BigDecimal("0.25");
    public boolean armed = false;
    /** Write-ahead intent is separate from accepted slots: a prepared create may never post. */
    public String preparedCreate = "";
    public boolean prepareCreate(String slot, String orderId, BigDecimal size, long block) {
        return prepareCreate(slot,orderId,size,block,null,"");
    }
    public boolean prepareCreate(String slot,String orderId,BigDecimal size,long block,BigDecimal locked,String token) {
        synchronized(STORE_LOCK){return prepareCreateLocked(slot,orderId,size,block,locked,token);}
    }
    private boolean prepareCreateLocked(String slot,String orderId,BigDecimal size,long block,BigDecimal locked,String token) {
        try {
            if(!canWrite())return false;
            // One unresolved create owns this journal until its outcome or withdrawal.
            // A fresh/reloaded writer must not replace a different acknowledged intent.
            if(!preparedCreate.isEmpty())return false;
            String data = new JSONObject().put("slot", slot).put("id", orderId)
                    .put("size", size.toPlainString()).put("block", block)
                    .put("locked",locked==null?"":locked.toPlainString()).put("lock_token",token).toString();
            validatePrepared(data);
            if (prefs != null) {
                boolean saved=prefs.edit().putString("prepared_create", data).commit();
                // Android commit failure can still publish the attempted value in memory.
                loadedSnapshot=new LinkedHashMap<>(loadedSnapshot);loadedSnapshot.put("prepared_create",data);
                if(!saved)return failedSave();
            }
            preparedCreate = data;
            return true;
        } catch (Exception invalid) { return failedSave(); }
    }
    public String preparedOrderId() {
        try { return new JSONObject(preparedCreate).optString("id", ""); }
        catch (Exception invalid) { return ""; }
    }

    /**
     * An orderId condemned to die, with TWO independent clocks. They must not share one field:
     * expiry has to be measured from when we condemned it (so a slow-confirming order is still
     * chased), while re-send pacing is measured from the last attempt (so we don't burn
     * proof-of-work every scan). Backdating one clock to hurry the other shortened the
     * protection window to a few blocks.
     */
    public static final class Tomb {
        public final long createdBlock;      // when it was condemned — expiry is measured here
        public long lastAttemptBlock;        // last cancel sent, or 0 for "never tried"

        public Tomb(long createdBlock, long lastAttemptBlock) {
            this.createdBlock = createdBlock;
            this.lastAttemptBlock = lastAttemptBlock;
        }
    }

    /** slot id ("B1"/"A2"…) → what we posted for it and when. */
    public final Map<String, SlotRec> slots = new LinkedHashMap<>();
    /** orderId → its death warrant. Lives until the coin is verifiably gone for good. */
    public final Map<String, Tomb> cancelTombstones = new LinkedHashMap<>();
    public BigDecimal lastActedMid = null;

    public MakerConfig(Context ctx) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    /** In-memory only, for unit tests — {@link #save()} is a no-op without prefs. */
    MakerConfig() {this((SharedPreferences)null);}
    MakerConfig(SharedPreferences prefs) {this.prefs=prefs;if(prefs!=null)load();}

    /** Includes an interrupted pre-sign create even when no accepted slot exists yet. */
    public boolean hasRecordedOrders() {
        return !readable || !preparedCreate.isEmpty() || !slots.isEmpty() || !cancelTombstones.isEmpty();
    }

    /** Save all cancellation identities while their original slots/intents still exist.
     * A failed acknowledgement must not dispatch work or remove the original records. */
    boolean prepareWithdrawal(java.util.Collection<String> ids,long block) {
        for(String id:ids){
            if(id==null||id.isEmpty())return false;
            if(!cancelTombstones.containsKey(id))cancelTombstones.put(id,new Tomb(block,0));
        }
        return save();
    }

    /** Cancel-all must also retain maker orders not yet visible in the confirmed book. */
    boolean stopAndTrackWithdrawal(long block) {
        reload();
        if(!readable)return false;
        String interrupted=preparedOrderId();
        if(!preparedCreate.isEmpty()&&interrupted.isEmpty())return failedSave();
        java.util.Set<String> ids=new java.util.LinkedHashSet<>();
        for(SlotRec record:slots.values())ids.add(record.orderId);
        if(!interrupted.isEmpty())ids.add(interrupted);
        armed=false;
        // Same write-ahead cancellation records as withdrawAll; keep original slots/intents.
        return prepareWithdrawal(ids,block);
    }

    // ---------------------------------------------------------------- slot records

    /** Record a CREATE that the node ACCEPTED (call from onPosted, never before). */
    public void rememberSlot(String slotId, String orderId, BigDecimal sizeMinima, long block) {
        rememberSlot(slotId,orderId,sizeMinima,block,null,"");
    }
    public void rememberSlot(String slotId,String orderId,BigDecimal sizeMinima,long block,BigDecimal locked,String token) {
        slots.put(slotId,new SlotRec(orderId,sizeMinima,block,0,locked,token));save();
    }

    /** A relock/adjustment was sent for this slot — restart its settling window. */
    public void noteSlotAction(String slotId, long block) {
        SlotRec r = slots.get(slotId);
        if (r == null) return;
        r.lastActionBlock = block;
        save();
    }

    public void forgetSlot(String slotId) {
        if (slots.remove(slotId) != null) save();
    }

    public void forgetSlotByOrderId(String orderId) {
        if (orderId == null) return;
        String slot = null;
        for (Map.Entry<String, SlotRec> e : slots.entrySet()) {
            if (orderId.equals(e.getValue().orderId)) { slot = e.getKey(); break; }
        }
        if (slot != null) { slots.remove(slot); save(); }
    }

    public void clearSlots() {
        slots.clear();
        save();
    }

    public String orderIdFor(String slotId) {
        SlotRec r = slots.get(slotId);
        return r == null ? null : r.orderId;
    }

    /** The size we posted for this slot, or null if we never did. */
    public BigDecimal postedSizeFor(String slotId) {
        SlotRec r = slots.get(slotId);
        return (r == null || r.size == null || r.size.signum() <= 0) ? null : r.size;
    }

    // ---------------------------------------------------------------- tombstones

    /** Condemn an orderId. Keeps the original createdBlock if it is already condemned, so
     *  repeated attempts can never extend (or shorten) its protection window. */
    public void tombstone(String orderId, long createdBlock, long lastAttemptBlock) {
        if (orderId == null || orderId.isEmpty()) return;
        Tomb existing = cancelTombstones.get(orderId);
        if (existing == null) {
            cancelTombstones.put(orderId, new Tomb(createdBlock, lastAttemptBlock));
        } else if (lastAttemptBlock > existing.lastAttemptBlock) {
            // only ever ADVANCES: re-condemning with "not tried yet" (0) must not rewind the
            // pacing clock, or the sweep re-sends a cancel that is still mining
            existing.lastAttemptBlock = lastAttemptBlock;
        }
        save();
    }

    /** Condemn without having sent a cancel yet — the sweep picks it up on its next pass. */
    public void tombstone(String orderId, long block) {
        tombstone(orderId, block, 0);
    }

    public void clearTombstone(String orderId) {
        if (cancelTombstones.remove(orderId) != null) save();
    }

    // ---------------------------------------------------------------- load / save

    /** Re-read persisted state. The foreground Activity and the background service each hold
     *  their own instance; whichever is about to ACT must reload first, or it will save a
     *  stale slot map over the other's records and orphan whatever the other posted. */
    public void reload() {
        if (prefs != null) load();
    }

    private void load() {synchronized(STORE_LOCK){loadLocked();}}
    private void loadLocked() {
        // Decode one coherent preferences snapshot before replacing any live identities.
        // Pending.load uses the same all-or-error rule to preserve unreadable receipts.
        try {
            MakerConfig next = new MakerConfig();
            Map<String,?> snapshot=prefs.getAll();next.readSnapshot(snapshot);
            asks.clear();asks.addAll(next.asks);bids.clear();bids.addAll(next.bids);
            pegged=next.pegged;stepPct=next.stepPct;levelCount=next.levelCount;
            askSize=next.askSize;bidSize=next.bidSize;manualMid=next.manualMid;
            skewPct=next.skewPct;repricePct=next.repricePct;armed=next.armed;quoteRevision=next.quoteRevision;
            preparedCreate=next.preparedCreate;lastActedMid=next.lastActedMid;
            slots.clear();slots.putAll(next.slots);
            cancelTombstones.clear();cancelTombstones.putAll(next.cancelTombstones);
            loadedSnapshot=ownedSnapshot(snapshot);readable=true;
        }catch(Exception invalid){readable=false;armed=false;failedSave();}
    }

    /** Compare one coherent loaded snapshot before editing; valid newer settings also belong to the user. */
    private boolean canWrite() {
        if(!readable)return failedSave();
        if(prefs==null)return true;
        try {
            Map<String,?> current=prefs.getAll();new MakerConfig().readSnapshot(current);
            if(!ownedSnapshot(current).equals(loadedSnapshot)) {
                armed=false;settingsConflict=true;storageFailed=true;return false;
            }
            return true;
        }
        catch(Exception invalid){readable=false;armed=false;return failedSave();}
    }

    private void readSnapshot(Map<String,?> values) throws org.json.JSONException {
        readLevels(storedString(values,K_ASKS,"[]"),asks);
        readLevels(storedString(values,K_BIDS,"[]"),bids);
        pegged=storedBoolean(values,K_PEGGED,true);
        stepPct=storedDecimal(storedString(values,K_STEP,"0.20"));
        Object levels=values.get(K_NLEVELS);
        if(levels!=null&&!(levels instanceof Integer))throw new IllegalArgumentException("Invalid level count");
        levelCount=Math.max(1,Math.min(MakerLadder.MAX_LEVELS,levels==null?3:(Integer)levels));
        askSize=storedDecimal(storedString(values,K_ASKSIZE,"0"));
        bidSize=storedDecimal(storedString(values,K_BIDSIZE,"0"));
        manualMid=storedDecimal(storedString(values,K_MID,"0"));
        skewPct=storedDecimal(storedString(values,K_SKEW,"0"));
        repricePct=storedDecimal(storedString(values,K_REPRICE,"0.25"));
        armed=storedBoolean(values,K_ARMED,false);
        quoteRevision=storedString(values,K_QUOTE_REVISION,"");
        if(!quoteRevision.isEmpty()&&!quoteRevision.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid maker quote revision");
        preparedCreate=storedString(values,"prepared_create","");
        validatePrepared(preparedCreate);

        // Retain the existing legacy migrations, but never turn malformed data into defaults.
        JSONArray legacy=storedArray(storedString(values,K_LEVELS,"[]"));
        for(int i=0;i<legacy.length();i++) {
            JSONObject row=legacy.getJSONObject(i);storedDecimal(row.get("off"));storedDecimal(row.get("size"));
        }
        if(askSize.signum()<=0&&bidSize.signum()<=0&&asks.isEmpty()&&bids.isEmpty()&&legacy.length()>0) {
            JSONObject row=legacy.getJSONObject(0);
            BigDecimal off=storedDecimal(row.get("off")),size=storedDecimal(row.get("size"));
            if(off.signum()>0)stepPct=off;
            if(size.signum()>0){askSize=size;bidSize=size;}
            levelCount=Math.max(1,Math.min(MakerLadder.MAX_LEVELS,legacy.length()));pegged=true;
        }
        if(pegged&&!MakerLadder.hasSizedRung(asks)&&askSize.signum()>0) {
            asks.clear();for(int i=0;i<levelCount;i++)asks.add(new MakerLadder.Level(BigDecimal.ZERO,askSize));
        }
        if(pegged&&!MakerLadder.hasSizedRung(bids)&&bidSize.signum()>0) {
            bids.clear();for(int i=0;i<levelCount;i++)bids.add(new MakerLadder.Level(BigDecimal.ZERO,bidSize));
        }

        JSONObject records=storedObject(storedString(values,K_SLOTS2,"{}"));
        for(Iterator<String> it=records.keys();it.hasNext();) {
            String key=nonempty(it.next());JSONObject r=records.getJSONObject(key);
            slots.put(key,new SlotRec(nonempty(r.get("id")),storedDecimal(r.get("size")),
                    storedBlock(r.has("sent")?r.get("sent"):0),storedBlock(r.has("act")?r.get("act"):0),
                    fundedAmount(r),jsonString(r,"lock_token","")));
        }
        JSONObject ids=storedObject(storedString(values,K_SLOTS,"{}"));
        JSONObject sizes=storedObject(storedString(values,K_SIZES,"{}"));
        // Validate all old records even when modern records take precedence: save removes old keys.
        Map<String,SlotRec> oldSlots=new LinkedHashMap<>();
        for(Iterator<String> it=ids.keys();it.hasNext();) {
            String key=nonempty(it.next());
            oldSlots.put(key,new SlotRec(nonempty(ids.get(key)),storedDecimal(sizes.has(key)?sizes.get(key):"0"),0,0));
        }
        for(Iterator<String> it=sizes.keys();it.hasNext();)storedDecimal(sizes.get(it.next()));
        if(slots.isEmpty())slots.putAll(oldSlots);

        JSONObject tomb=storedObject(storedString(values,K_TOMB,"{}"));
        for(Iterator<String> it=tomb.keys();it.hasNext();) {
            String key=nonempty(it.next());Object value=tomb.get(key);
            if(value instanceof JSONObject) {
                JSONObject t=(JSONObject)value;
                cancelTombstones.put(key,new Tomb(storedBlock(t.get("made")),storedBlock(t.get("try"))));
            }else {
                long block=storedBlock(value);cancelTombstones.put(key,new Tomb(block,block));
            }
        }
        String mid=storedString(values,K_LASTMID,"");lastActedMid=mid.isEmpty()?null:storedDecimal(mid);
    }

    private static void validatePrepared(String data) throws org.json.JSONException {
        if(data.isEmpty())return;
        JSONObject p=storedObject(data);
        nonempty(p.get("slot"));nonempty(p.get("id"));storedDecimal(p.get("size"));
        storedBlock(p.get("block"));fundedAmount(p);
    }
    private static String storedString(Map<String,?> values,String key,String fallback) {
        if(!values.containsKey(key))return fallback;
        Object value=values.get(key);if(!(value instanceof String))throw new IllegalArgumentException("Invalid maker field");
        return (String)value;
    }
    private static boolean storedBoolean(Map<String,?> values,String key,boolean fallback) {
        if(!values.containsKey(key))return fallback;
        Object value=values.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("Invalid maker flag");
        return (Boolean)value;
    }
    private static String nonempty(Object value) {
        if(!(value instanceof String)||((String)value).trim().isEmpty())throw new IllegalArgumentException("Missing maker identity");
        return (String)value;
    }
    static String jsonString(JSONObject row,String key,String fallback) throws org.json.JSONException {
        if(!row.has(key))return fallback;
        Object value=row.get(key);if(!(value instanceof String))throw new IllegalArgumentException("Invalid maker text");return (String)value;
    }
    static BigDecimal storedDecimal(Object value) {
        if(!(value instanceof String)&&!(value instanceof Number))throw new IllegalArgumentException("Invalid maker amount");
        BigDecimal n=Util.decOr(value.toString(),null);
        if(n==null)throw new IllegalArgumentException("Invalid maker amount");return n;
    }
    static long storedBlock(Object value) {
        long n=storedDecimal(value).longValueExact();
        if(n<0)throw new IllegalArgumentException("Invalid maker block");return n;
    }
    private static BigDecimal fundedAmount(JSONObject row) throws org.json.JSONException {
        String token=jsonString(row,"lock_token","");
        Object raw=row.has("locked")?row.get("locked"):"";
        boolean absent="".equals(raw);
        if(absent){if(!token.isEmpty())throw new IllegalArgumentException("Incomplete maker funding");return null;}
        BigDecimal amount=storedDecimal(raw);
        if(token.isEmpty())throw new IllegalArgumentException("Incomplete maker funding");return amount;
    }
    private static Object storedJson(String raw) throws org.json.JSONException {
        // Same complete-input/NUL check as MinimaAPIResponse, adapted for local arrays too.
        if(raw.indexOf(0)>=0)throw new IllegalArgumentException("Invalid maker JSON");
        org.json.JSONTokener input=new org.json.JSONTokener(raw);Object value=input.nextValue();
        if(!org.minimarex.minimaapi.MinimaAPIResponse.hasOnlyTrailingWhitespace(input))throw new IllegalArgumentException("Trailing maker JSON");return value;
    }
    static JSONObject storedObject(String raw) throws org.json.JSONException {
        Object value=storedJson(raw);if(!(value instanceof JSONObject))throw new IllegalArgumentException("Invalid maker object");return (JSONObject)value;
    }
    // Shared with Pending: accept one complete array, never a valid prefix of damaged data.
    static JSONArray storedArray(String raw) throws org.json.JSONException {
        Object value=storedJson(raw);if(!(value instanceof JSONArray))throw new IllegalArgumentException("Invalid maker array");return (JSONArray)value;
    }
    private static void readLevels(String raw,List<MakerLadder.Level> out) throws org.json.JSONException {
        JSONArray a=storedArray(raw);
        if(a.length()>MakerLadder.MAX_LEVELS)throw new IllegalArgumentException("Too many maker rungs");
        for(int i=0;i<a.length();i++) {
            JSONObject row=a.getJSONObject(i);
            out.add(new MakerLadder.Level(storedDecimal(row.get("p")),storedDecimal(row.get("a"))));
        }
    }

    private static JSONArray levelsJson(List<MakerLadder.Level> levels) throws org.json.JSONException {
        if(levels.size()>MakerLadder.MAX_LEVELS)throw new IllegalArgumentException("Too many maker rungs");
        JSONArray a=new JSONArray();
        for(MakerLadder.Level l:levels) {
            if(l==null||l.price==null||l.sizeMinima==null)throw new IllegalArgumentException("Incomplete maker rung");
            a.put(new JSONObject().put("p",l.price.toPlainString()).put("a",l.sizeMinima.toPlainString()));
        }
        return a;
    }

    public MakerLadder.Config toLadderConfig() {
        return new MakerLadder.Config(pegged, stepPct,
                new ArrayList<>(asks), new ArrayList<>(bids), skewPct, repricePct);
    }

    public boolean save() {synchronized(STORE_LOCK){return saveLocked();}}
    private boolean saveLocked() {
        if (prefs == null) return true;   // test instance
        if(!canWrite())return false;
        JSONObject recs = new JSONObject();
        JSONObject tomb = new JSONObject();
        try {
            for (Map.Entry<String, SlotRec> e : slots.entrySet()) {
                SlotRec r = e.getValue();
                JSONObject o = new JSONObject();
                o.put("id", r.orderId);
                o.put("size", r.size == null ? "0" : r.size.toPlainString());
                o.put("sent", r.sentBlock);
                o.put("act", r.lastActionBlock);
                o.put("locked",r.locked==null?"":r.locked.toPlainString());
                o.put("lock_token",r.lockedToken);
                recs.put(e.getKey(), o);
            }
            for (Map.Entry<String, Tomb> e : cancelTombstones.entrySet()) {
                JSONObject t = new JSONObject();
                t.put("made", e.getValue().createdBlock);
                t.put("try", e.getValue().lastAttemptBlock);
                tomb.put(e.getKey(), t);
            }
        } catch (Exception invalid) {return failedSave();}
        try {
            Map<String,Object> updated=new LinkedHashMap<>();
            updated.put(K_ASKS, levelsJson(asks).toString());
            updated.put(K_BIDS, levelsJson(bids).toString());
            updated.put(K_PEGGED, pegged);
            updated.put(K_STEP, stepPct.toPlainString());
            updated.put(K_NLEVELS, levelCount);
            updated.put(K_ASKSIZE, askSize.toPlainString());
            updated.put(K_BIDSIZE, bidSize.toPlainString());
            updated.put(K_MID, manualMid.toPlainString());
            updated.put(K_SKEW, skewPct.toPlainString());
            updated.put(K_REPRICE, repricePct.toPlainString());
            updated.put(K_ARMED, armed);
            MakerConfig previous=new MakerConfig();previous.readSnapshot(loadedSnapshot);
            // Reuse the queued-quote comparison; accepted slots, tombstones and prepared
            // receipts do not change economic authorization. Legacy snapshots use "" until
            // the first change, preserving existing settings without resuming paused work.
            String nextRevision=previous.armed!=armed
                    ||!MakerQuoteGuard.same(previous.toLadderConfig(),toLadderConfig())
                    ?java.util.UUID.randomUUID().toString():previous.quoteRevision;
            updated.put(K_QUOTE_REVISION,nextRevision);
            updated.put("prepared_create", preparedCreate);
            updated.put(K_SLOTS2, recs.toString());
            updated.put(K_TOMB, tomb.toString());
            updated.put(K_LASTMID, lastActedMid == null ? "" : lastActedMid.toPlainString());
            Map<String,Object> snapshot=new LinkedHashMap<>(prefs.getAll());snapshot.putAll(updated);
            snapshot.remove(K_LEVELS);snapshot.remove(K_SLOTS);snapshot.remove(K_SIZES);
            new MakerConfig().readSnapshot(snapshot); // Validate output before editing preferences.
            SharedPreferences.Editor editor=prefs.edit();
            for(Map.Entry<String,Object> e:updated.entrySet()) {
                Object value=e.getValue();
                if(value instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)value);
                else if(value instanceof Integer)editor.putInt(e.getKey(),(Integer)value);
                else editor.putString(e.getKey(),(String)value);
            }
            boolean saved=editor.remove(K_LEVELS).remove(K_SLOTS).remove(K_SIZES).commit();
            loadedSnapshot=ownedSnapshot(snapshot);quoteRevision=nextRevision;
            return saved || failedSave();
        }catch(Exception failure){return failedSave();}
    }
}

package com.eurobuddha.pandadex;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE shared book scan (PoolRepository pattern): single-flight with a coalesced
 * pending-rescan, an in-memory + SQLite cache served instantly to any subscriber, a 4s
 * min-interval throttle protecting the node's single command thread, and the FillTape fed
 * exactly once per good scan. All main-thread (NodeApi marshals callbacks there).
 */
public final class BookRepository {

    public interface Listener {
        void onBook(Map<String, Order5> orders, boolean syncing);
    }

    private static final long MIN_INTERVAL_MS = 4_000;
    /** Consecutive empty scans before we believe the book really is empty. */
    static final int EMPTY_CONFIRM = 3;

    private final NodeApi node;
    private final DexDb db;
    private final FillTape tape;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private Map<String, Order5> cached = new LinkedHashMap<>();
    private boolean haveLive = false;     // a live scan has landed this process
    private boolean scanning = false;
    private boolean pendingRescan = false;
    private long lastScanMs = 0;
    private int emptyScans = 0;
    private long chainBlock = 0;
    private FillTape.Sink sink;

    public BookRepository(NodeApi node, DexDb db) {
        this.node = node;
        this.db = db;
        // the cancel log lives in SQLite so the background service's own FillTape agrees
        this.tape = new FillTape(new FillTape.CancelLog() {
            @Override public void note(String coinid) { db.noteCancelled(coinid); }
            @Override public boolean consume(String coinid) { return db.wasCancelled(coinid); }
        });
        // local-first: seed the cache from the persisted last-good book BEFORE any node work.
        // Skip anything WE cancelled: the snapshot is only as fresh as the last scan that wrote
        // it, so after cancelling orders it still lists them, and painting them on launch shows
        // the user rows they already killed — with cancel buttons on coins that no longer exist.
        java.util.Set<String> dead = db.cancelledIds();
        for (String json : db.loadBook()) {
            try {
                Order5 o = Order5.from(new JSONObject(json));
                if (o != null && !dead.contains(o.coinid)) cached.put(o.coinid, o);
            } catch (Exception ignore) {}
        }
    }

    public void setFillSink(FillTape.Sink s) { sink = s; }

    public FillTape tape() { return tape; }

    public Map<String, Order5> book() { return cached; }

    public long chainBlock() { return chainBlock; }

    public void setChainBlock(long b) { chainBlock = b; }

    public void subscribe(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onBook(cached, !haveLive);
    }

    public void unsubscribe(Listener l) { listeners.remove(l); }

    /** Serve the cache instantly, then refresh. */
    public void requestNow(Listener l) {
        l.onBook(cached, !haveLive);
        refresh();
    }

    /** Coalesced, throttled refresh — safe to call from anywhere, any number of times. */
    public void refresh() {
        if (scanning) { pendingRescan = true; return; }
        long now = System.currentTimeMillis();
        long wait = lastScanMs + MIN_INTERVAL_MS - now;
        if (wait > 0) {
            if (!pendingRescan) {
                pendingRescan = true;
                ui.postDelayed(() -> { if (pendingRescan && !scanning) { pendingRescan = false; refresh(); } }, wait);
            }
            return;
        }
        scanning = true;
        lastScanMs = now;
        BookScanner.scan(node, (orders, truncated, rawJsons) -> {
            scanning = false;
            // An EMPTY scan is not proof of an empty book — a partial or momentarily-empty
            // reply parses perfectly and is indistinguishable from "everything traded". But
            // DISTRUST MUST EXPIRE: the first cut refused an empty book unconditionally, so
            // once the book legitimately emptied (cancel every order — exactly what a user
            // does) the cache froze permanently. The stale orders stayed on screen forever and
            // cancel rows could never clear, because they clear when the coin leaves the book.
            // Demand repeated confirmation, then believe it.
            // Count ONLY believable scans. A transport failure (timeout while the node grinds
            // PoW, ERR_TOO_LONG, ERR_NOT_ENABLED) also arrives as an empty map — counting
            // those let three failures spend the entire confirmation budget, so the next
            // genuine-looking empty reply was accepted with no confirmation at all, wiping
            // the cache AND the persisted snapshot.
            if (!truncated) {
                if (orders.isEmpty() && !cached.isEmpty()) emptyScans++; else emptyScans = 0;
            }
            boolean believable = believable(truncated, orders.isEmpty(), cached.isEmpty(),
                    emptyScans, haveLive);
            if (believable) {
                cached = orders;
                haveLive = true;
                if (sink != null) tape.ingest(orders, false, chainBlock, sink);
                // Persist WHATEVER this scan says, empty included. Refusing to save an empty
                // book left the snapshot holding the last non-empty one forever, so every cold
                // start after cancelling everything repainted dead orders until the first live
                // scan corrected it — ghosts that invite the user to act on spent coins.
                // The protection against a BOGUS empty is the suspectEmpty gate above
                // (EMPTY_CONFIRM believable empty scans, truncated ones not counted); by the
                // time we are here the empty book has already earned belief. Worst case is a
                // blank cold-start paint that self-heals on the next scan.
                persist(orders, rawJsons);
            } else if (!truncated) {
                // still feed the tape so its own sanity gate observes and re-seeds
                if (sink != null) tape.ingest(orders, false, chainBlock, sink);
            }
            // truncated → keep last-good cache (Limit lesson); still notify so views show "syncing"
            for (Listener l : new ArrayList<>(listeners)) l.onBook(cached, truncated && !haveLive);
            if (pendingRescan) {
                pendingRescan = false;
                refresh();
            }
        });
    }

    /**
     * Is this scan worth believing — and therefore worth writing through to the cache AND the
     * persisted snapshot? Pure, because this one rule has now been wrong twice: once counting
     * transport failures toward the empty-book budget, and once refusing to persist a
     * confirmed-empty book (which left ghost orders on every cold start).
     *
     * A truncated scan is never believed. An empty scan is believed only after
     * {@link #EMPTY_CONFIRM} consecutive believable empties — unless the cache is already
     * empty, in which case there is nothing to contradict.
     */
    static boolean believable(boolean truncated, boolean ordersEmpty, boolean cachedEmpty,
                              int emptyScans, boolean haveLive) {
        if (truncated) return false;
        // Before the FIRST live scan the cache is a guess loaded from disk, not an observation.
        // The confirmation gate exists to stop one bad read destroying a book we have actually
        // seen — it must not defend a stale snapshot against fresh evidence, or a cold start
        // after cancelling everything paints dead orders until three scans talk it round.
        if (!haveLive) return true;
        boolean suspectEmpty = ordersEmpty && !cachedEmpty && emptyScans < EMPTY_CONFIRM;
        return !suspectEmpty;
    }

    private void persist(Map<String, Order5> orders, List<String> rawJsons) {
        List<String> ids = new ArrayList<>(orders.keySet());
        // The two lists are parallel only because BookScanner appends to both in lockstep over
        // a LinkedHashMap. On any mismatch we skip rather than persist a mis-paired snapshot —
        // a scrambled cache would attribute one order's JSON to another's coinid.
        if (rawJsons.size() == ids.size()) db.saveBook(rawJsons, ids);
    }
}

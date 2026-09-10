package com.eurobuddha.pandadex;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class MakerHandoffTest {
    private static final class Deferred extends DexTxn {
        int calls; Result pending;
        Deferred() { super(null, null); }
        public String createOrder(boolean buy, BigDecimal size, BigDecimal price, boolean gtc, BigDecimal rem, String id, Result cb) {
            calls++; pending=cb; return id;
        }
    }
    private static MakerConfig config() {
        MakerConfig cfg=new MakerConfig(); cfg.armed=true; cfg.pegged=false;
        cfg.asks.add(new MakerLadder.Level(new BigDecimal("0.01"), new BigDecimal("10"))); return cfg;
    }
    @Test public void foregroundCannotStartWhileBackgroundStillOwnsCycle() {
        Deferred bg=new Deferred(), fg=new Deferred();
        MakerEngine a=new MakerEngine(config(),bg), b=new MakerEngine(config(),fg);
        a.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        try {
            assertEquals(1,bg.calls);
            b.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
            assertEquals(0,fg.calls);
        } finally { if(bg.pending!=null) bg.pending.onFailed("rejected"); }
        b.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        try { assertEquals(1,fg.calls); } finally { if(fg.pending!=null) fg.pending.onFailed("rejected"); }
    }
    @Test public void uncertainCreateKeepsItsIdentityAndDisarmsWithoutRetrying() {
        Deferred tx=new Deferred(); MakerConfig cfg=config(); MakerEngine engine=new MakerEngine(cfg,tx);
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        tx.pending.onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
        assertFalse(cfg.armed); assertEquals(1,cfg.slots.size()); assertFalse(engine.isWorking());
        engine.nudge(); engine.onBook(Collections.emptyMap(),Collections.emptySet(),205,null);
        assertEquals(1,tx.calls);
    }
    @Test public void preparedCreateIsSeparateFromAcceptedSlotsAndSurvivesUnknownOutcome() {
        Deferred tx=new Deferred(); MakerConfig cfg=config(); MakerEngine engine=new MakerEngine(cfg,tx);
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        try {
            assertTrue(tx.pending.onPrepared("create_local"));
            assertTrue(cfg.slots.isEmpty());
            assertFalse(cfg.preparedOrderId().isEmpty());
        } finally { tx.pending.onFailed(NodeApi.ERR_WRITE_UNCERTAIN); }
        assertFalse(cfg.preparedCreate.isEmpty());
        engine.nudge(); engine.onBook(Collections.emptyMap(),Collections.emptySet(),210,null);
        assertEquals(1,tx.calls); assertFalse(cfg.armed);
    }
    @Test public void aDisarmDuringAnAsyncActionStopsTheRemainingRungs() {
        Deferred tx=new Deferred(); MakerConfig cfg=config();
        cfg.bids.add(new MakerLadder.Level(new BigDecimal("0.009"),new BigDecimal("10")));
        MakerEngine engine=new MakerEngine(cfg,tx);
        engine.onBook(Collections.emptyMap(),Collections.emptySet(),200,null);
        cfg.armed=false;
        tx.pending.onPosted("0xaa");
        assertEquals(1,tx.calls); assertFalse(engine.isWorking());
    }
}

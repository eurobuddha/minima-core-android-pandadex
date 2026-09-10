package com.eurobuddha.pandadex;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class MakerPositionTest {
    private static final class Create extends DexTxn {
        Result callback;Create(){super(null,null);}
        public String createOrder(boolean buy,BigDecimal size,BigDecimal price,boolean gtc,BigDecimal rem,String id,Result cb){callback=cb;return id;}
    }
    @Test public void exactBuyFundingSurvivesPreparedAcceptedAndReloadedStages()throws Exception {
        MakerWithdrawalDurabilityTest.Memory memory=new MakerWithdrawalDurabilityTest.Memory();
        MakerConfig cfg=new MakerConfig(memory.prefs());cfg.pegged=false;cfg.armed=true;
        cfg.bids.add(new MakerLadder.Level(new BigDecimal("0.0123456789"),new BigDecimal("1.234567891")));assertTrue(cfg.save());
        Create tx=new Create();MakerEngine engine=new MakerEngine(cfg,tx);engine.onBook(Collections.emptyMap(),Collections.emptySet(),100,null);
        assertNotNull(tx.callback);
        try {
            assertTrue(tx.callback.onPrepared("create_fixture"));
            org.json.JSONObject intent=new org.json.JSONObject(memory.restart().preparedCreate);
            // Literal expected lock includes the transaction builder's rounding, not current book price.
            assertEquals("0.01524158",intent.getString("locked"));assertEquals(DexContract.USDT_ID,intent.getString("lock_token"));
            tx.callback.onPosted("0xaabb");
            MakerConfig.SlotRec record=memory.restart().slots.get("B1");
            assertNotNull(record);assertEquals(new BigDecimal("0.01524158"),record.locked);assertEquals(DexContract.USDT_ID,record.lockedToken);
        }finally{if(engine.isWorking()){cfg.armed=false;tx.callback.onFailed("fixture cleanup");}}
    }
    private static Order5 order() {return Order5.from(TransactionHardeningTest.orderCoin());}
    @Test public void legacySellBaselineUsesItsFundedMinimaAmount() {
        Order5 o=order();assertTrue(o.sell);MakerConfig.SlotRec record=new MakerConfig.SlotRec(o.orderId,o.locked,100,0);
        assertEquals(0,o.locked.compareTo(MakerPosition.baseline(record,o)));assertFalse(MakerPosition.preserve(record,o));
    }
    @Test public void differentFundingTokenOrIncreasedBalanceRequiresReview() {
        Order5 o=order();MakerConfig.SlotRec wrong=new MakerConfig.SlotRec(o.orderId,o.locked,100,0,o.locked,DexContract.USDT_ID);
        assertNull(MakerPosition.baseline(wrong,o));assertTrue(MakerPosition.preserve(wrong,o));
        MakerConfig.SlotRec smaller=new MakerConfig.SlotRec(o.orderId,o.locked,100,0,o.locked.divide(BigDecimal.TEN),o.lockedTok);
        assertTrue(MakerPosition.preserve(smaller,o));
    }
}

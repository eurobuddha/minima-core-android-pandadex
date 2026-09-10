package com.eurobuddha.pandadex;
import java.math.BigDecimal;

/** Compare funded assets, as FillTape does; requested buy quantity changes on repricing.
 * This describes a balance comparison, not proof of a particular fill transaction. */
final class MakerPosition {
    private MakerPosition(){}
    static BigDecimal baseline(MakerConfig.SlotRec record,Order5 order) {
        if(record==null||order==null)return null;
        if(record.locked!=null) {
            return record.locked.signum()>0&&order.lockedTok.equalsIgnoreCase(record.lockedToken)?record.locked:null;
        }
        // Legacy sell size was the funded MINIMA amount. A legacy buy size alone cannot
        // recover its original USDT funding after the price has changed.
        return order.sell&&record.size!=null&&record.size.signum()>0
                ?PriceMath.down(record.size,PriceMath.MINIMA_DP):null;
    }
    static boolean preserve(MakerConfig.SlotRec record,Order5 order) {
        BigDecimal initial=baseline(record,order);
        return initial==null||order.locked.compareTo(initial)!=0;
    }
}

package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.List;

/** Revalidate queued maker intent; no network, node calls or replacement quote is made here. */
final class MakerQuoteGuard {
    private final MakerLadder.Config original;
    private final String revision;
    private final BigDecimal mid;
    private final List<MakerLadder.Slot> planned;
    MakerQuoteGuard(MakerConfig config,BigDecimal mid) {
        original=config.toLadderConfig();revision=config.quoteRevision();this.mid=mid;
        MarketPrice.Quote quote=MarketPrice.quote();
        planned=MakerLadder.desired(mid,original,BigDecimal.valueOf(quote.widen));
    }
    boolean allows(MakerConfig config,MakerLadder.Action action) {
        // Withdrawal does not depend on the price feed or current ladder settings.
        if(action.kind==MakerLadder.Kind.CANCEL)return true;
        if(!MakerConfig.storageHealthy()||!config.armed||!revision.equals(config.quoteRevision())||!same(original,config.toLadderConfig()))return false;
        if(!original.pegged)return true;
        MarketPrice.Quote quote=MarketPrice.quote();
        if(quote.unavailable||!Double.isFinite(quote.mid)||quote.mid<=0||mid==null||mid.signum()<=0)return false;
        BigDecimal current=BigDecimal.valueOf(quote.mid);
        if(moved(mid,current,original.repricePct))return false;
        // Also recheck widening: an ageing reference may require a wider spread even
        // when its midpoint has not moved. Reuse the exact ladder math and threshold.
        List<MakerLadder.Slot> now=MakerLadder.desired(current,original,BigDecimal.valueOf(quote.widen));
        if(planned.size()!=now.size())return false;
        for(int i=0;i<planned.size();i++) {
            MakerLadder.Slot a=planned.get(i),b=now.get(i);
            if(!a.id.equals(b.id)||moved(a.price,b.price,original.repricePct))return false;
        }
        return true;
    }
    private static boolean moved(BigDecimal old,BigDecimal now,BigDecimal threshold) {
        if(threshold==null||threshold.signum()<0)return true;
        return old.compareTo(now)!=0&&MakerLadder.worthRepricing(old,now,threshold);
    }
    private static boolean number(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    private static boolean levels(List<MakerLadder.Level> a,List<MakerLadder.Level> b){
        if(a==null||b==null)return a==b;
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++){
            MakerLadder.Level x=a.get(i),y=b.get(i);
            if(x==null||y==null){if(x!=y)return false;}
            else if(!number(x.price,y.price)||!number(x.sizeMinima,y.sizeMinima))return false;
        }
        return true;
    }
    static boolean same(MakerLadder.Config a,MakerLadder.Config b){
        return a.pegged==b.pegged&&number(a.stepPct,b.stepPct)&&number(a.skewPct,b.skewPct)
                &&number(a.repricePct,b.repricePct)&&levels(a.asks,b.asks)&&levels(a.bids,b.bids);
    }
}

package com.eurobuddha.pandadex;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

/** Public executed pool trades: PandaPools reserve-flow classification, using the exact
 * input/output pairs enforced by PoolCovenant so unrelated donations cannot distort prices. */
final class PoolMarket {
    static final class Trade {
        final String pool;
        final int inputIndex;
        final BigDecimal minima, token;
        final boolean buy;
        Trade(String pool, int index, BigDecimal dm, BigDecimal dt) {
            this.pool=pool; inputIndex=index; minima=dm.abs(); token=dt.abs(); buy=dm.signum()<0;
        }
        BigDecimal price() { return PriceMath.price(token,minima); }
    }
    interface Store {
        Set<String> marketPoolAddresses();
        boolean poolTradeKnown(String txpowid, Trade trade);
        void recordPoolTrades(List<Trade> trades, DexHistory.Spend proof);
    }
    private final Store store;
    private Set<String> addresses=Collections.emptySet();
    PoolMarket(Store store) { this.store=store; }
    void begin() { addresses=store.marketPoolAddresses(); }
    boolean interested(JSONObject tx) {
        String id=tx.optString("txpowid","");
        for(Trade trade:trades(tx,addresses)) if(!store.poolTradeKnown(id,trade))return true;
        return false;
    }
    void found(JSONObject tx,DexHistory.Spend proof) {
        if(proof.inclusionTimeMs<=0 || proof.inclusionBlock<=0 || proof.confirmations<0
                || !FundingCoins.hex(proof.inclusionBlockId))return;
        List<Trade> trades=trades(tx,addresses);
        if(!trades.isEmpty())store.recordPoolTrades(trades,proof);
    }
    static List<Trade> trades(JSONObject tx,Set<String> addresses) {
        List<Trade> trades=new ArrayList<>();
        if(addresses==null || addresses.isEmpty())return trades;
        JSONArray ins=DexHistory.coinsOf(tx,"inputs"),outs=DexHistory.coinsOf(tx,"outputs");
        for(int i=0;i+1<ins.length() && i+1<outs.length();i+=2) {
            JSONObject m=ins.optJSONObject(i),t=ins.optJSONObject(i+1),nm=outs.optJSONObject(i),nt=outs.optJSONObject(i+1);
            String pool=m==null?"":m.optString("address","").toLowerCase(Locale.ROOT);
            if(!addresses.contains(pool) || !leg(m,pool,"0x00") || !leg(t,pool,DexContract.USDT_ID)
                    || !leg(nm,pool,"0x00") || !leg(nt,pool,DexContract.USDT_ID))continue;
            BigDecimal x=value(m),y=value(t),nx=value(nm),ny=value(nt);
            if(x==null || y==null || nx==null || ny==null)continue;
            BigDecimal dm=nx.subtract(x),dt=ny.subtract(y);
            if(dm.signum()*dt.signum()<0)trades.add(new Trade(pool,i,dm,dt));
        }
        return trades;
    }
    private static boolean leg(JSONObject coin,String address,String token) {
        return coin!=null && address.equalsIgnoreCase(coin.optString("address",""))
                && token.equalsIgnoreCase(coin.optString("tokenid",""));
    }
    private static BigDecimal value(JSONObject coin) {
        String field="0x00".equalsIgnoreCase(coin.optString("tokenid",""))?"amount":"tokenamount";
        try { BigDecimal value=MakerConfig.storedDecimal(coin.get(field));return value.signum()>0?value:null; }
        catch(Exception invalid) { return null; }
    }
}

package com.eurobuddha.pandadex;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class PoolMarketTest {
    static final String POOL="0xaacc";
    static JSONObject coin(String address,String token,String amount) {
        return new TestJson().put("address",address).put("tokenid",token)
                .put(token.equals("0x00")?"amount":"tokenamount",amount);
    }
    static JSONObject tx(String m,String t) {
        return new TestJson().put("txpowid","0xaabb").put("header",new TestJson().put("timemilli","9999999999999"))
                .put("body",new TestJson().put("txn",new TestJson()
                    .put("inputs",new JSONArray().put(coin(POOL,"0x00","1000")).put(coin(POOL,DexContract.USDT_ID,"5")))
                    .put("outputs",new JSONArray().put(coin(POOL,"0x00",m)).put(coin(POOL,DexContract.USDT_ID,t)))));
    }
    static List<PoolMarket.Trade> trades(JSONObject tx) { return PoolMarket.trades(tx,Collections.singleton(POOL)); }
    @Test public void strangersSellUsesExecutedReserveDeltas() {
        PoolMarket.Trade t=trades(tx("1010","4.95")).get(0);
        assertFalse(t.buy);assertEquals(0,new BigDecimal("10").compareTo(t.minima));
        assertEquals(0,new BigDecimal("0.005").compareTo(t.price()));
    }
    @Test public void buyUsesExecutedPriceRatherThanPoolSpot() {
        PoolMarket.Trade t=trades(tx("990","5.06")).get(0);
        assertTrue(t.buy);assertEquals(0,new BigDecimal("0.006").compareTo(t.price()));
    }
    @Test public void additionsMaintenanceAndWithdrawalsAreNotTrades() throws Exception {
        assertTrue(trades(tx("2000","10")).isEmpty());assertTrue(trades(tx("1000","5")).isEmpty());
        assertTrue(trades(tx("500","2.5")).isEmpty());
        JSONObject refund=tx("990","5.06");DexHistory.coinsOf(refund,"outputs").getJSONObject(0).put("address","0xdead");
        assertTrue(trades(refund).isEmpty());
    }
    @Test public void unrelatedDonationCannotChangeTheTradePrice() throws Exception {
        JSONObject tx=tx("1010","4.95");DexHistory.coinsOf(tx,"outputs").put(coin(POOL,"0x00","999999"));
        assertEquals(0,new BigDecimal("0.005").compareTo(trades(tx).get(0).price()));
    }
    @Test public void unknownAddressWrongTokenAndMalformedAmountsAreRejected() throws Exception {
        assertTrue(PoolMarket.trades(tx("1010","4.95"),Collections.emptySet()).isEmpty());
        for(Object bad:new Object[]{JSONObject.NULL,true,"garbage","1e999999","-1"}) {
            JSONObject tx=tx("1010","4.95");DexHistory.coinsOf(tx,"outputs").getJSONObject(1).put("tokenamount",bad);
            assertTrue(trades(tx).isEmpty());
        }
        JSONObject tx=tx("1010","4.95");DexHistory.coinsOf(tx,"outputs").getJSONObject(1).put("tokenid","0xdead");
        assertTrue(trades(tx).isEmpty());
    }
    @Test public void missingScaledTokenAmountCannotUseRawColourAmount() throws Exception {
        JSONObject tx=tx("1010","4.95");JSONObject token=DexHistory.coinsOf(tx,"outputs").getJSONObject(1);
        token.remove("tokenamount");token.put("amount","4.95");assertTrue(trades(tx).isEmpty());
    }
    @Test public void separatePoolPairsStaySeparate() throws Exception {
        JSONObject tx=tx("1010","4.95");String second="0xbbcc";
        DexHistory.coinsOf(tx,"inputs").put(coin(second,"0x00","1000")).put(coin(second,DexContract.USDT_ID,"5"));
        DexHistory.coinsOf(tx,"outputs").put(coin(second,"0x00","990")).put(coin(second,DexContract.USDT_ID,"5.06"));
        List<PoolMarket.Trade> list=PoolMarket.trades(tx,new HashSet<>(Arrays.asList(POOL,second)));
        assertEquals(2,list.size());assertEquals(0,list.get(0).inputIndex);assertEquals(2,list.get(1).inputIndex);
        assertFalse(list.get(0).buy);assertTrue(list.get(1).buy);
    }
    static class Store implements DexHistory.ProgressStore,PoolMarket.Store {
        boolean known,fail;int saved;DexHistory.Spend proof;
        public Set<String> marketPoolAddresses(){return Collections.singleton(POOL);}
        public boolean poolTradeKnown(String id,PoolMarket.Trade t){return known;}
        public void recordPoolTrades(List<PoolMarket.Trade> trades,DexHistory.Spend p){if(fail)throw new IllegalStateException("disk full");saved+=trades.size();known=true;proof=p;}
        public int offset(boolean relevant,Collection<String> ids){return 0;}
        public void checkpoint(boolean relevant,Collection<String> missing,Collection<String> found,int offset){}
    }
    static void discover(Store store,JSONObject inclusion,JSONObject block,List<String> calls) {
        DexHistory history=new DexHistory((command,cb)->{
            calls.add(command);
            if(command.startsWith("history "))cb.onResult(new TestJson().put("status",true).put("response",new TestJson().put("txpows",new JSONArray().put(tx("1010","4.95")))));
            else if(command.startsWith("txpow onchain:"))cb.onResult(inclusion);
            else cb.onResult(block);
        },store);
        history.discover(new DexHistory.Discovery(){public boolean known(String id,String tx){return false;}public void found(Order5 o,DexHistory.Spend p){fail("not a limit order");}},()->calls.add("DONE"));
    }
    @Test public void pagedPublicDiscoveryUsesVerifiedBlockTimeAndAvoidsDuplicateTrade() {
        Store store=new Store();List<String> calls=new ArrayList<>();discover(store,InclusionTimeTest.inclusion(),InclusionTimeTest.block(),calls);
        assertEquals(1,store.saved);assertEquals(InclusionTimeTest.BLOCK_TIME,store.proof.inclusionTimeMs);
        assertEquals(100,store.proof.inclusionBlock);assertTrue(calls.get(0).contains("relevant:false max:8 offset:0"));
        assertEquals("DONE",calls.get(calls.size()-1));calls.clear();discover(store,InclusionTimeTest.inclusion(),InclusionTimeTest.block(),calls);
        assertEquals(1,store.saved);assertEquals(2,calls.size());
    }
    @Test public void mempoolAndUnverifiedBlockTimeCannotBecomeLastTrades() throws Exception {
        Store store=new Store();JSONObject missing=InclusionTimeTest.inclusion();missing.getJSONObject("response").put("found",false);
        discover(store,missing,InclusionTimeTest.block(),new ArrayList<>());assertEquals(0,store.saved);
        discover(store,InclusionTimeTest.inclusion(),new TestJson().put("status",false),new ArrayList<>());assertEquals(0,store.saved);
    }
    @Test public void storageFailureStillCompletesTheHistoryPass() {
        Store store=new Store();store.fail=true;List<String> calls=new ArrayList<>();discover(store,InclusionTimeTest.inclusion(),InclusionTimeTest.block(),calls);
        assertEquals(0,store.saved);assertEquals("DONE",calls.get(calls.size()-1));
    }
}

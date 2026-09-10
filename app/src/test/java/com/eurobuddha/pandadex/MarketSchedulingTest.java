package com.eurobuddha.pandadex;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class MarketSchedulingTest {
    static class Backlog extends OwnerRecoveryTest.Memory implements PoolMarket.Store {
        public Set<String> marketPoolAddresses(){return Collections.emptySet();}
        public boolean poolTradeKnown(String tx,PoolMarket.Trade trade){return false;}
        public void recordPoolTrades(List<PoolMarket.Trade> trades,DexHistory.Spend proof){fail("no pool fixture");}
    }
    @Test public void ownerBacklogCannotStarveFreshPublicHistory()throws Exception {
        Backlog store=new Backlog();store.entries.add(OwnerRecoveryTest.entry(OwnerRecoveryTest.row()));
        List<String> commands=new ArrayList<>();
        DexHistory history=new DexHistory((command,cb)->{commands.add(command);cb.onResult(OwnerRecoveryTest.emptyReply());});
        FillSettler settler=new FillSettler(history,()->100,new FillRecoveryTest.Outcome(),store);
        for(int i=0;i<6;i++){
            commands.clear();settler.onScanComplete();
            assertFalse(commands.isEmpty());
            assertTrue(commands.get(0).startsWith(i%2==0?"history relevant:false":"history relevant:true"));
        }
        assertEquals(1,store.entries.size());assertEquals(3,store.batches);
    }
}

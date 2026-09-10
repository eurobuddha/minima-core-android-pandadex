package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class CreateFundingTest {
    static String[] state() { return new String[]{"0xaa","0xbb","1",DexContract.USDT_ID,"0xcc","1","0.01","1","1"}; }
    static JSONObject coin(String token, String amount) {
        TestJson c = new TestJson().put("coinid","0xdd").put("tokenid",token).put("amount",amount);
        if (!Util.isMinima(token)) c.put("tokenamount",amount);
        return c;
    }
    @Test public void createKeepsStateOffChangeAndUsesOnlyChosenInputs() {
        List<String> commands = DexTxn.createOrderSteps("test", "0x00", new BigDecimal("100"), "0xbb", state(),
                Arrays.asList(coin("0x00","75"),new TestJson().put("coinid","0xee").put("tokenid","0x00").put("amount","50")));
        assertEquals(2, commands.stream().filter(c -> c.startsWith("txninput ")).count());
        assertTrue(commands.contains("txnoutput id:test amount:100 address:"+DexContract.ADDR_V5+" storestate:true"));
        assertTrue(commands.contains("txnoutput id:test amount:25 address:0xbb storestate:false"));
        assertEquals(9, commands.stream().filter(c -> c.startsWith("txnstate ")).count());
        assertEquals("txnsign id:test publickey:auto",commands.get(commands.size()-1));
        assertFalse(commands.stream().anyMatch(c -> c.startsWith("send ") || c.startsWith("txnpost ")));
    }
    @Test public void tokenFundingUsesTokenAmountAndRejectsOtherTokens() {
        JSONObject c=coin(DexContract.USDT_ID,"3");
        List<String> commands=DexTxn.createOrderSteps("test",DexContract.USDT_ID,new BigDecimal("2"),"0xbb",state(),Collections.singletonList(c));
        assertTrue(commands.contains("txnoutput id:test amount:1 address:0xbb tokenid:"+DexContract.USDT_ID+" storestate:false"));
        assertThrows(IllegalArgumentException.class,()->DexTxn.createOrderSteps("test","0x00",BigDecimal.ONE,"0xbb",state(),Collections.singletonList(c)));
    }
    @Test public void insufficientFundingAndStateInjectionNeverReachSigning() {
        assertThrows(IllegalArgumentException.class,()->DexTxn.createOrderSteps("test","0x00",new BigDecimal("2"),"0xbb",state(),Collections.singletonList(coin("0x00","1"))));
        String[] bad=state();bad[4]="0xaa;send amount:1";
        assertThrows(IllegalArgumentException.class,()->DexTxn.createOrderSteps("test","0x00",BigDecimal.ONE,"0xbb",bad,Collections.singletonList(coin("0x00","1"))));
    }
    @Test public void splitConservesEveryUnitAndNeverCarriesState() {
        List<String> commands=DexTxn.splitFundingSteps("test","0x00",new BigDecimal("1.00000001"),"0xbb",Collections.singletonList(coin("0x00","2")));
        BigDecimal total=BigDecimal.ZERO; int count=0;
        for(String command:commands) if(command.startsWith("txnoutput ")) {
            count++; assertTrue(command.endsWith("address:0xbb storestate:false"));
            for(String word:command.split(" ")) if(word.startsWith("amount:")) total=total.add(new BigDecimal(word.substring(7)));
        }
        assertEquals(11,count); assertEquals(0,new BigDecimal("2").compareTo(total));
        assertFalse(commands.stream().anyMatch(c->c.startsWith("txnstate ")));
    }
}

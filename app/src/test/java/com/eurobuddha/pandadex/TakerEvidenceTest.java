package com.eurobuddha.pandadex;
import java.math.BigDecimal;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class TakerEvidenceTest {
    private DexHistory.Spend spend(String mined, String immutable, int depth, String payout) {
        JSONObject coin = new TestJson().put("address", payout).put("tokenid", "0x00").put("amount", "12.5");
        DexHistory.Spend s=new DexHistory.Spend(mined, 0, new JSONArray().put(coin), immutable, depth);
        s.input=new TestJson().put("coinid","0x01");s.inputCount=2;return s;
    }
    @Test public void allSourceCoinsMustBeConsumedByOurConfirmedTransaction() {
        Map<String,DexHistory.Spend> found = new HashMap<>();
        DexHistory.Spend good = spend("0xAAAA", "0xBBBB", 0, "0xCCCC");
        found.put("0x01",good);
        DexHistory.Spend second=new DexHistory.Spend(good.txpowid,1,good.outputs,good.transactionId,good.confirmations);
        second.input=new TestJson().put("coinid","0x02");second.inputCount=2;found.put("0x02",second);
        assertSame(good,TakerEvidence.match(found,Arrays.asList("0x01","0x02"),"0xBBBB","0xCCCC","0x00",new BigDecimal("12.5")));
        found.put("0x02",spend("0xDDDD","0xEEEE",9,"0xCCCC"));
        assertNull(TakerEvidence.match(found,Arrays.asList("0x01","0x02"),"0xBBBB","0xCCCC","0x00",new BigDecimal("12.5")));
        found.remove("0x02");
        assertNull(TakerEvidence.match(found,Arrays.asList("0x01","0x02"),"0xBBBB","0xCCCC","0x00",new BigDecimal("12.5")));
    }
    @Test public void unconfirmedCompetingOrWrongPayoutTransactionIsNotOurTrade() {
        for (DexHistory.Spend wrong : Arrays.asList(spend("0xAAAA","0xBBBB",-1,"0xCCCC"),
                spend("0xAAAA","0xFFFF",3,"0xCCCC"),spend("0xAAAA","0xBBBB",3,"0xDDDD"))) {
            assertNull(TakerEvidence.match(Collections.singletonMap("0x01",wrong),Collections.singletonList("0x01"),
                    "0xBBBB","0xCCCC","0x00",new BigDecimal("12.5")));
        }
    }
    @Test public void inclusionFlagsAndDepthMustAllBeValid() {
        for (Object depth : Arrays.asList(-1,1.5,"NaN","999999999999999999999999"))
            assertEquals(-1,ChainEvidence.confirmationDepth(new TestJson().put("status",true)
                    .put("response",new TestJson().put("found",true).put("confirmations",depth))));
        assertEquals(-1,ChainEvidence.confirmationDepth(new TestJson().put("status",true)
                .put("response",new TestJson().put("found",false).put("confirmations",100))));
        assertEquals(0,ChainEvidence.confirmationDepth(new TestJson().put("status",true)
                .put("response",new TestJson().put("found",true).put("confirmations",0))));
    }
    @Test public void duplicateOrMisboundSourcesCannotCompleteAnAggregateTrade() {
        DexHistory.Spend good=spend("0xaaaa","0xbbbb",1,"0xcccc");
        Map<String,DexHistory.Spend> found=new HashMap<>();found.put("0x01",good);found.put("0x02",good);found.put("0x01".toUpperCase(java.util.Locale.ROOT),good);
        for(List<String> sources:Arrays.asList(Arrays.asList("0x01","0x01"),Arrays.asList("0x01","0x02")))
            assertNull(TakerEvidence.match(found,sources,"0xbbbb","0xcccc","0x00",new BigDecimal("12.5")));
    }
    @Test public void exactBlockCoordinatesAndFreshProofAreNeededBeforeDurableCompletion() {
        DexHistory.Spend s=spend("0xaaaa","0xbbbb",0,"0xcccc");
        assertFalse(TakerEvidence.readyToRecord(s,"0x01"));
        s.inclusionBlock=100;s.inclusionBlockId="0xbeef";s.inclusionTimeMs=1700000000000L;s.proofOrder=1;s.proofTimeMs=1700000000001L;
        assertTrue(TakerEvidence.readyToRecord(s,"0x01"));assertFalse(TakerEvidence.readyToRecord(s,"0x02"));
        s.inclusionTimeMs=0;assertFalse(TakerEvidence.readyToRecord(s,"0x01"));
    }

    @Test public void inputPositionsAndInclusionCoordinatesMustAgreeAcrossAllLegs() {
        DexHistory.Spend first=spend("0xaaaa","0xbbbb",1,"0xcccc");
        first.inclusionBlock=100;first.inclusionBlockId="0xbeef";first.inclusionTimeMs=1000;
        for(int fault=0;fault<4;fault++) {
            DexHistory.Spend second=new DexHistory.Spend(first.txpowid,fault==0?0:1,first.outputs,first.transactionId,1);
            second.input=new TestJson().put("coinid","0x02");second.inputCount=2;
            second.inclusionBlock=fault==1?101:100;second.inclusionBlockId=fault==2?"0xdead":"0xbeef";
            second.inclusionTimeMs=fault==3?2000:1000;
            Map<String,DexHistory.Spend> found=new HashMap<>();found.put("0x01",first);found.put("0x02",second);
            assertNull(TakerEvidence.match(found,Arrays.asList("0x01","0x02"),"0xbbbb","0xcccc","0x00",new BigDecimal("12.5")));
        }
    }

}

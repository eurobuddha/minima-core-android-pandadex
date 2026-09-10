package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class ArchiveIntegrityTest {
    @Test public void takerCaptureFreezesAdoptedProofAlongsideArchiveBytes() throws Exception {
        Map<String,DexHistory.Spend> found=TakerReceiptTest.proof();
        TakerReceipt saved=TakerReceipt.capture(TakerReceiptTest.expected(),found);
        DexHistory.Spend input=found.get("0xaa");
        input.inclusionBlock=999;input.inclusionBlockId="0xeeee";input.inclusionTimeMs=1;
        input.proofOrder=900;input.proofTimeMs=2;input.inputCount=900;
        input.input.put("coinid","0xffff");input.outputs.getJSONObject(0).put("amount","999");
        input.transactionState.put(new JSONObject().put("port",1).put("data","damaged"));
        assertEquals(100,saved.spend.inclusionBlock);assertEquals("0xbeef",saved.spend.inclusionBlockId);
        assertEquals(1700000000000L,saved.spend.inclusionTimeMs);assertEquals(1,saved.spend.proofOrder);
        assertEquals(1700000000001L,saved.spend.proofTimeMs);assertEquals(3,saved.spend.inputCount);
        assertEquals("0xaa",saved.spend.input.getString("coinid"));assertEquals("100",saved.spend.outputs.getJSONObject(0).getString("amount"));
        assertEquals(0,saved.spend.transactionState.length());
    }
    @Test public void damagedArchiveSuffixCannotAuthorizeTakerRepair() throws Exception {
        TakerReceipt saved=TakerReceipt.capture(TakerReceiptTest.expected(),TakerReceiptTest.proof());
        for(String suffix:Arrays.asList("{}","//ignored","/*ignored*/","\u0000")) {
            assertFalse(saved.sameIntent(saved.json+suffix));
            TakerRecovery.Entry e=new TakerRecovery.Entry("0xaa","0xaabb",100,"0xbeef",saved.json+suffix);
            assertThrows(RuntimeException.class,e::intent);
        }
        assertTrue(saved.sameIntent(saved.json+" \r\n\t"));
    }
    @Test public void fractionalOrOverflowVersionCannotMatchTakerIntent() throws Exception {
        TakerReceipt saved=TakerReceipt.capture(TakerReceiptTest.expected(),TakerReceiptTest.proof());
        for(Object version:Arrays.asList(1.5,"1.5","9223372036854775809")) {
            String data=new JSONObject(saved.json).put("version",version).toString();
            assertFalse(saved.sameIntent(data));
            assertThrows(RuntimeException.class,()->new TakerRecovery.Entry("0xaa","0xaabb",100,"0xbeef",data).intent());
        }
    }
    @Test public void fractionalInclusionCannotMatchArchiveIndex() throws Exception {
        TakerRecovery.Entry original=TakerRecoveryTest.entry();JSONObject data=new JSONObject(original.json);
        data.getJSONObject("proof").put("block",100.5);
        assertThrows(RuntimeException.class,()->new TakerRecovery.Entry(original.coinid,original.txpowid,original.block,original.blockid,data.toString()).intent());
    }
    @Test public void invalidArchiveDoesNotDispatchRecoveryOrMutateStore() throws Exception {
        TakerRecovery.Entry original=TakerRecoveryTest.entry();TakerRecoveryTest.Memory store=new TakerRecoveryTest.Memory();
        String damaged=original.json+" trailing";
        store.entries.add(new TakerRecovery.Entry(original.coinid,original.txpowid,original.block,original.blockid,damaged));
        new TakerRecovery(store,(ids,cb)->fail("Damaged archived intent reached node lookup"))
            .run((attempted,changed,error)->{assertTrue(attempted);assertFalse(changed);assertFalse(error.isEmpty());});
        assertEquals(0,store.repairs);assertEquals(damaged,store.entries.get(0).json);
    }
    @Test public void oversizedSavedArchiveCannotBeReusedForRepair() throws Exception {
        TakerReceipt saved=TakerReceipt.capture(TakerReceiptTest.expected(),TakerReceiptTest.proof());
        String damaged=new JSONObject(saved.json).put("padding","x".repeat(TakerReceipt.MAX_BYTES)).toString();
        assertFalse(saved.sameIntent(damaged));
        assertThrows(RuntimeException.class,()->new TakerRecovery.Entry("0xaa","0xaabb",100,"0xbeef",damaged).intent());
    }
    @Test public void ownerReplayRejectsFractionalVersionAndBlock() throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt saved=OwnerReceipt.capture(row,CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row)));
        assertFalse(saved.sameCompletion(new JSONObject(saved.json).put("version",1.5).toString()));
        JSONObject wrong=new JSONObject(saved.json);wrong.getJSONObject("proof").put("block",100.5);assertFalse(saved.sameCompletion(wrong.toString()));
        assertTrue(saved.sameCompletion(saved.json));
    }
}

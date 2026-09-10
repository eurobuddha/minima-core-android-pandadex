package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerRevisionTest {
    static Map<String,DexHistory.Spend> moved(Pending.Row row)throws Exception {
        Map<String,DexHistory.Spend> found=CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row));
        for(DexHistory.Spend p:found.values()) {
            p.inclusionBlock=101;p.inclusionBlockId="0xeeee";p.inclusionTimeMs=1700000000000L;
            p.proofOrder=ChainEvidence.nextProofOrder();p.proofTimeMs=System.currentTimeMillis();
        }
        return found;
    }
    @Test public void changedInclusionPreservesOriginalObservationAndIntent()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt original=OwnerReceipt.capture(row,CreationEvidenceTest.proof(row,CreationEvidenceTest.transaction(row)));
        OwnerReceipt replacement=OwnerReceipt.capture(row,moved(row));String saved=original.json;
        assertFalse(replacement.sameCompletion(saved));JSONObject revision=new JSONObject(replacement.revisionOf(saved));
        assertEquals(original.recordedAt,revision.getLong("recorded_at"));assertEquals(101,revision.getJSONObject("proof").getLong("block"));
        assertEquals(saved,original.json);assertEquals(row.submitMs,revision.getJSONObject("expected").getLong("submitMs"));
        assertTrue(replacement.sameCompletion(revision.toString()));
    }
    @Test public void revisionRequiresCompleteFreshTimeAndOrdering()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt original=OwnerReceipt.capture(row,moved(row));
        for(int fault=0;fault<3;fault++) {
            Map<String,DexHistory.Spend> found=moved(row);
            for(DexHistory.Spend p:found.values()){if(fault==0)p.inclusionTimeMs=0;if(fault==1)p.proofOrder=0;if(fault==2)p.proofTimeMs=0;}
            OwnerReceipt candidate=OwnerReceipt.capture(row,found);assertThrows(ChainReview.Conflict.class,()->candidate.revisionOf(original.json));
        }
    }
    @Test public void changedEconomicIntentCannotRewriteOwnerArchive()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt original=OwnerReceipt.capture(row,moved(row));
        row.minima=row.minima.add(java.math.BigDecimal.ONE);OwnerReceipt different=OwnerReceipt.capture(row,moved(row));
        assertThrows(ChainReview.Conflict.class,()->different.revisionOf(original.json));
    }
    @Test public void immutableTxpowCannotAcquireDifferentOutcome()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt original=OwnerReceipt.capture(row,moved(row));
        String wrong=new JSONObject(original.json).put("outcome","CANCELLED").toString();
        assertThrows(ChainReview.Conflict.class,()->original.revisionOf(wrong));
    }
    @Test public void malformedOriginalNeverBecomesRevisionAuthority()throws Exception {
        Pending.Row row=CreationEvidenceTest.receipt();OwnerReceipt original=OwnerReceipt.capture(row,moved(row));
        for(String raw:Arrays.asList(original.json+" trailing",new JSONObject(original.json).put("version",1.5).toString(),new JSONObject(original.json).put("recorded_at",-1).toString()))
            assertThrows(ChainReview.Conflict.class,()->original.revisionOf(raw));
    }
}

package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class PendingIntegrityTest {
    static String rows(Pending.Row... rows)throws Exception {JSONArray a=new JSONArray();for(Pending.Row r:rows)a.put(r.json());return a.toString();}
    static void refusesWithoutRewrite(String raw) {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();memory.data=raw;Pending pending=new Pending(memory);
        assertFalse(pending.healthy());assertEquals("RECOVERY_ERROR",pending.rows().get(0).kind);
        assertThrows(IllegalStateException.class,()->pending.add(PendingRecoveryTest.row("0xbb")));
        assertThrows(IllegalStateException.class,pending::unresolvedOwnerCoins);assertEquals(raw,memory.data);
    }
    @Test public void validPrefixCannotHideTrailingReceiptBytes()throws Exception {
        for(String suffix:Arrays.asList(" trailing"," []"," {}"," // hidden"," /* hidden */"," # hidden","\f","\u0001"))refusesWithoutRewrite(rows(PendingRecoveryTest.row("0xaa"))+suffix);
    }
    @Test public void rawNulCannotTerminateTheReceiptFileEarly()throws Exception {
        refusesWithoutRewrite("[]"+(char)0+"hidden receipts");
        refusesWithoutRewrite(rows(PendingRecoveryTest.row("0xaa"))+(char)0);
    }
    @Test public void duplicateIdentityCannotRetireAnotherTransactionReceipt()throws Exception {
        Pending.Row first=CreationEvidenceTest.receipt(),other=PendingRecoveryTest.row("0xffff");other.receiptId=first.receiptId;
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();memory.data=rows(first,other);String original=memory.data;
        Pending pending=new Pending(memory);CreationEvidenceTest.reconcile(pending,first,PendingRecoveryTest.listener(()->fail("ambiguous identity confirmed"),()->{}));
        assertFalse(pending.healthy());assertEquals(original,memory.data);
    }
    @Test public void identicalLegacyRowsRemainPreservedInsteadOfSharingDeletionIdentity()throws Exception {
        JSONObject old=PendingRecoveryTest.row("0xaa").json();old.remove("receiptId");
        refusesWithoutRewrite(new JSONArray().put(old).put(new JSONObject(old.toString())).toString());
    }
    @Test public void explicitlyMalformedIdentityDoesNotBecomeALegacyIdentity()throws Exception {
        for(Object bad:Arrays.asList(""," ",true,12,JSONObject.NULL,"a"+(char)0+"b",new JSONArray())) {
            JSONObject row=PendingRecoveryTest.row("0xaa").json();row.put("receiptId",bad);
            refusesWithoutRewrite(new JSONArray().put(row).toString());
        }
    }
    @Test public void legacyMissingAndOpaqueNonemptyIdsRemainStable()throws Exception {
        JSONObject old=PendingRecoveryTest.row("0xaa").json();old.remove("receiptId");Pending.Row explicit=PendingRecoveryTest.row("0xbb");explicit.receiptId="legacy-opaque-id";
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();memory.data=" \n"+new JSONArray().put(old).put(explicit.json())+" \n";
        Pending a=new Pending(memory),b=new Pending(memory);assertTrue(a.healthy());String migrated=a.rows().get(0).receiptId;
        assertEquals(migrated,b.rows().get(0).receiptId);a.add(PendingRecoveryTest.row("0xcc"));
        assertEquals(migrated,new Pending(memory).rows().get(0).receiptId);assertEquals("legacy-opaque-id",new Pending(memory).rows().get(1).receiptId);
    }
    @Test public void invalidNewIdentityCannotPoisonAnOtherwiseHealthyStore()throws Exception {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending p=new Pending(memory);p.add(PendingRecoveryTest.row("0xaa"));String original=memory.data;
        for(String bad:Arrays.asList("",null," ","x"+(char)0)) {
            Pending.Row next=PendingRecoveryTest.row("0xbb");next.receiptId=bad;
            assertThrows(IllegalStateException.class,()->p.add(next));assertEquals(original,memory.data);assertTrue(p.healthy());
        }
    }
    @Test public void duplicateStoreCannotAdvanceOwnerIntentOrSubmission()throws Exception {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending.Row row=PendingRecoveryTest.row("0xaa");memory.data=rows(row,row);String original=memory.data;
        Pending p=new Pending(memory);Order5 source=Order5.from(TransactionHardeningTest.orderCoin());
        DexTxn.Result cb=p.cancellationResult(Collections.singletonList(source),100,CreationEvidenceTest.callback());
        assertFalse(cb.onPrepared("blocked_duplicate"));assertFalse(cb.beforePost());cb.onFailed("fixture stopped");assertEquals(original,memory.data);
    }
    @Test public void validSameIdentityUpdateStillReplacesOnlyItsOwnRow()throws Exception {
        PendingRecoveryTest.Memory memory=new PendingRecoveryTest.Memory();Pending p=new Pending(memory);Pending.Row row=PendingRecoveryTest.row("0xaa"),other=PendingRecoveryTest.row("0xbb");
        p.add(row);p.add(other);row.minima=new BigDecimal("50");p.add(row);assertTrue(p.healthy());assertEquals(2,p.rows().size());
        for(Pending.Row saved:p.rows())if(saved.receiptId.equals(row.receiptId))assertEquals(new BigDecimal("50"),saved.minima);
        assertTrue(p.rows().stream().anyMatch(saved->saved.receiptId.equals(other.receiptId)));
    }
}

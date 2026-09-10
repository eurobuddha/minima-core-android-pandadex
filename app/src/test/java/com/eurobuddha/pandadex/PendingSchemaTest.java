package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class PendingSchemaTest {
    static JSONObject row()throws Exception{return PendingRecoveryTest.row("0xaa").json();}
    static void refused(JSONObject row)throws Exception {PendingIntegrityTest.refusesWithoutRewrite(new JSONArray().put(row).toString());}
    @Test public void missingOriginalFieldsCannotTurnIntoDefaultReceipts()throws Exception {
        for(String field:new String[]{"kind","orderId","coinid","buy","minima","price","submitMs","submitBlock"}) {
            JSONObject r=row();r.remove(field);refused(r);
        }
    }
    @Test public void unknownOperationKindCannotEscapeAutomaticRecoveryHold()throws Exception {
        DexProcessorRecoveryTest.Fixture f=new DexProcessorRecoveryTest.Fixture();f.run(DexProcessorRecoveryTest.due());
        assertEquals(1,f.tx.renews);JSONArray rows=new JSONArray(f.receipts.data);rows.getJSONObject(0).put("kind","EDlT");f.receipts.data=rows.toString();String original=f.receipts.data;
        f.restart();f.run(DexProcessorRecoveryTest.due()+20);assertEquals(1,f.tx.renews);assertFalse(f.pending.healthy());assertEquals(original,f.receipts.data);
    }
    @Test public void explicitTextFieldsCannotCoerceNativeTypes()throws Exception {
        for(String field:new String[]{"kind","orderId","coinid","phase","transactionHandle","postedId","cancelSource","editSource","editWant"})
            for(Object value:new Object[]{true,12,JSONObject.NULL,new JSONObject(),new JSONArray(),"x\0y"})refused(row().put(field,value));
    }
    @Test public void unknownPhaseCannotMasqueradeAsOrdinaryWaiting()throws Exception {
        for(String phase:new String[]{"CONFIRMED","NOT_SUBMITTED ","unknown","garbage"})refused(row().put("phase",phase));
    }
    @Test public void flagsMustBeNativeBooleans()throws Exception {
        for(String field:new String[]{"buy","delayedNotified"})for(Object value:new Object[]{"true","false",1,JSONObject.NULL,new JSONArray()})refused(row().put(field,value));
    }
    @Test public void malformedAmountsDoNotBecomeZero()throws Exception {
        for(String field:new String[]{"minima","price"})for(Object value:new Object[]{"garbage","1,2","1e100000", "-1",true,JSONObject.NULL,new JSONArray()})refused(row().put(field,value));
    }
    @Test public void timestampsAndHeightsCannotBeTruncatedOrOverflowed()throws Exception {
        for(String field:new String[]{"submitMs","submitBlock"})for(Object value:new Object[]{1.5,"1.5","9223372036854775808",-1,"garbage",true,JSONObject.NULL})refused(row().put(field,value));
    }
    @Test public void malformedCreationContainerCannotBecomeLegacyReceipt()throws Exception {
        for(Object value:new Object[]{true,JSONObject.NULL,"{}",new JSONArray()})refused(row().put("creation",value));
    }
    @Test public void invalidNewRowCannotPoisonReadableStore()throws Exception {
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();Pending pending=new Pending(m);pending.add(PendingRecoveryTest.row("0xaa"));String original=m.data;
        Pending.Row bad=PendingRecoveryTest.row("0xbb");bad.kind="UNKNOWN_KIND";
        assertThrows(IllegalStateException.class,()->pending.add(bad));assertEquals(original,m.data);assertTrue(pending.healthy());
    }
    @Test public void originalLegacyShapeAndZeroDisplayPriceRemainReadable()throws Exception {
        JSONObject old=row();for(String field:new String[]{"receiptId","delayedNotified","creation","phase","transactionHandle","postedId","cancelSource","editSource","editWant"})old.remove(field);
        PendingRecoveryTest.Memory m=new PendingRecoveryTest.Memory();m.data=new JSONArray().put(old).toString();Pending p=new Pending(m);
        assertTrue(p.healthy());Pending.Row saved=p.rows().get(0);assertEquals("",saved.phase);assertNull(saved.creation);
        saved.price=BigDecimal.ZERO;saved.minima=BigDecimal.ZERO;p.add(saved);assertTrue(p.healthy());assertEquals(0,p.rows().get(0).price.signum());
    }
}

package com.eurobuddha.pandadex;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubmissionIdsTest {
    static class Memory implements Pending.Store {
        String data; boolean fail,changeMemoryOnFailure;
        Memory(String data){this.data=data;}
        public String read(){return data;}
        public boolean write(String next){if(!fail || changeMemoryOnFailure)data=next;return !fail;}
    }
    static JSONObject intent(String handle) throws Exception {
        return new JSONObject().put("intent",handle).put("txpowid",handle)
                .put("sources",new JSONArray().put("0xaa")).put("proceeds","12.34");
    }
    static JSONObject reply(String posted,String transaction) throws Exception {
        return new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid",posted)
                .put("body",new JSONObject().put("txn",new JSONObject().put("transactionid",transaction))));
    }
    @Test public void lateReplyPinsIntentBeforeCacheAndSurvivesEvictionAndReload() throws Exception {
        Memory p=new Memory(intent("sweep_1").toString()),c=new Memory("[]");
        assertTrue(SubmissionIds.remember(p,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        for(int i=0;i<520;i++)assertTrue(SubmissionIds.remember(p,c,reply(String.format(java.util.Locale.ROOT,"0x%04x",i+256),"0xdd"),""));
        assertEquals(512,new JSONArray(c.data).length());
        assertFalse(c.data.contains("sweep_1"));
        assertEquals("0xcc",SubmissionIds.transactionFor(new Memory(p.data),new Memory(c.data),"sweep_1"));
        assertEquals("12.34",new JSONObject(p.data).getString("proceeds"));
    }
    @Test public void restoredLegacyReceiptPinsAvailableIdentityBeforeLookupReturns() throws Exception {
        Memory p=new Memory(intent("sweep_1").put("txpowid","0xbb").toString()),c=new Memory("[]");
        assertTrue(SubmissionIds.remember(new Memory(""),c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        assertEquals("0xcc",SubmissionIds.transactionFor(p,c,"0xbb"));c.data="[]";
        assertEquals("0xcc",SubmissionIds.transactionFor(new Memory(p.data),c,"sweep_1"));
    }
    @Test public void postedCallbackPreservesPinnedIdEvenWithEmptyCache() throws Exception {
        Memory p=new Memory(intent("sweep_1").put("transactionid","0xcc").toString()),c=new Memory("[]");
        assertTrue(SubmissionIds.savePending(p,c,intent("sweep_1").put("txpowid","0xbb")));
        assertEquals("0xcc",SubmissionIds.transactionFor(p,c,"0xBB"));
        assertEquals("0xcc",SubmissionIds.transactionFor(p,c,"sweep_1"));
        assertEquals("",SubmissionIds.transactionFor(p,c,"sweep_2"));
    }
    @Test public void legacyPendingCanCopyAvailableCacheIdentity() throws Exception {
        Memory p=new Memory(intent("sweep_1").toString()),c=new Memory("[]");
        Memory noPending=new Memory("");
        assertTrue(SubmissionIds.remember(noPending,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        assertTrue(SubmissionIds.savePending(p,c,intent("sweep_1").put("txpowid","0xbb")));
        c.data="[]";
        assertEquals("0xcc",SubmissionIds.transactionFor(p,c,"0xbb"));
    }
    @Test public void staleReplyCannotAttachToAnotherIntentOrOverwriteExistingIdentity() throws Exception {
        Memory p=new Memory(intent("sweep_2").toString()),c=new Memory("[]");String before=p.data;
        assertTrue(SubmissionIds.remember(p,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        assertEquals(before,p.data);assertEquals("",SubmissionIds.transactionFor(p,c,"sweep_2"));
        assertTrue(SubmissionIds.remember(p,c,reply("0xee","0xff"),"txnpost id:sweep_2"));
        before=p.data;String oldCache=c.data;
        assertFalse(SubmissionIds.remember(p,c,reply("0xee","0xaa"),"txnpost id:sweep_2"));
        assertEquals(before,p.data);assertEquals(oldCache,c.data);
        assertFalse(SubmissionIds.savePending(p,c,intent("sweep_1")));assertEquals(before,p.data);
    }
    @Test public void failedPinKeepsCacheForRetryAndNeverAcknowledgesSuccess() throws Exception {
        Memory p=new Memory(intent("sweep_1").toString()),c=new Memory("[]");String before=p.data;p.fail=true;
        assertFalse(SubmissionIds.remember(p,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        assertEquals(before,p.data);assertEquals("",SubmissionIds.transactionFor(p,c,"sweep_1"));
        p.fail=false;assertTrue(SubmissionIds.savePending(p,c,intent("sweep_1")));c.data="[]";
        assertEquals("0xcc",SubmissionIds.transactionFor(p,c,"sweep_1"));
    }
    @Test public void memoryUpdatedByFailedCommitStillRequiresSuccessfulRetry() throws Exception {
        Memory p=new Memory(intent("sweep_1").toString()),c=new Memory("[]");p.fail=true;p.changeMemoryOnFailure=true;
        assertFalse(SubmissionIds.remember(p,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));
        assertFalse(SubmissionIds.savePending(p,c,intent("sweep_1")));
        p.fail=false;assertTrue(SubmissionIds.savePending(p,c,intent("sweep_1")));
    }
    @Test public void malformedEvidenceAndUnacceptedRepliesDoNotOverwritePending() throws Exception {
        Memory p=new Memory("original corrupt receipt"),c=new Memory("[]");String before=p.data;
        assertFalse(SubmissionIds.savePending(p,c,intent("sweep_1")));assertEquals(before,p.data);
        assertFalse(SubmissionIds.remember(p,c,reply("0xbb","0xcc"),"txnpost id:sweep_1"));assertEquals(before,p.data);
        p.data=intent("sweep_1").put("transactionid","broken").toString();before=p.data;
        assertFalse(SubmissionIds.savePending(p,c,intent("sweep_1")));assertEquals(before,p.data);
        p.data=intent("sweep_1").toString();before=p.data;
        assertFalse(SubmissionIds.remember(p,c,reply("0xbb","0xcc").put("status",false),"txnpost id:sweep_1"));
        assertFalse(SubmissionIds.remember(p,c,reply("0xbb","0xcc").put("pending",true),"txnpost id:sweep_1"));
        assertEquals(before,p.data);
    }
}

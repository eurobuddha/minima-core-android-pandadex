package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class PendingRecoveryTest {
    static class Memory implements Pending.Store {
        String data="[]"; boolean fail;
        final Map<String,OwnerReceipt> completed=new LinkedHashMap<>();boolean archiveFail;
        public void completed(OwnerReceipt receipt){
            if(fail||archiveFail)throw new IllegalStateException("fixture archive failed");
            OwnerReceipt previous=completed.get(receipt.id);
            if(previous!=null&&!receipt.sameCompletion(previous.json))throw new ChainReview.Conflict();
            if(previous==null)completed.put(receipt.id,receipt);
        }
        public String read(){return data;}
        public boolean write(String next){if(fail)return false;data=next;return true;}
    }
    static Pending.Row row(String id){
        Pending.Row r=new Pending.Row();r.kind=Pending.PLACE;r.orderId=id;r.coinid="";
        r.minima=new BigDecimal("100");r.price=new BigDecimal("0.01");r.submitMs=1;return r;
    }
    static Pending.Listener listener(Runnable live,Runnable late){return new Pending.Listener(){
        public void onLive(Pending.Row r){live.run();} public void onSettled(Pending.Row r){}
        public void onGaveUp(Pending.Row r){late.run();}
    };}
    @Test public void delayedReceiptsSurviveReloadAndNotifyOnlyOnce(){
        Memory m=new Memory();Pending a=new Pending(m);a.add(row("0xaa"));int[] notices={0};
        Pending.Listener l=listener(()->fail("not included"),()->notices[0]++);
        assertFalse(a.resolve(Collections.emptyMap(),100000,l));
        Pending restarted=new Pending(m);assertEquals(1,restarted.rows().size());
        restarted.resolve(Collections.emptyMap(),200000,l);assertEquals(1,notices[0]);
    }
    @Test public void interleavedHostsDoNotOverwriteEachOthersReceipts(){
        Memory m=new Memory();Pending a=new Pending(m),b=new Pending(m);
        a.add(row("0xaa"));b.add(row("0xbb"));a.add(row("0xcc"));
        assertEquals(3,new Pending(m).rows().size());
    }
    @Test public void resolutionCallbackCanAddAnotherReceiptWithoutLosingIt() throws Exception {
        Memory m=new Memory();Pending a=new Pending(m),b=new Pending(m);Pending.Row receipt=CreationEvidenceTest.receipt();a.add(receipt);
        CreationEvidenceTest.reconcile(a,receipt,listener(()->b.add(row("0xee")),()->{}));
        assertEquals(1,a.rows().size());assertEquals("0xee",a.rows().get(0).orderId);
    }
    @Test public void failedPersistenceDoesNotAnnounceResolution() throws Exception {
        Memory m=new Memory();Pending a=new Pending(m);Pending.Row receipt=CreationEvidenceTest.receipt();a.add(receipt);String saved=m.data;m.fail=true;
        assertThrows(IllegalStateException.class,()->CreationEvidenceTest.reconcile(a,receipt,listener(()->fail("not durable"),()->{})));
        assertEquals(saved,m.data);
    }
    @Test public void malformedStoredEvidenceIsNeverOverwritten(){
        Memory m=new Memory();m.data="broken original receipt";Pending a=new Pending(m);
        assertFalse(a.healthy());assertEquals("RECOVERY_ERROR",a.rows().get(0).kind);
        assertThrows(IllegalStateException.class,()->a.add(row("0xaa")));
        assertEquals("broken original receipt",m.data);
    }
    @Test public void legacyIdsStayStableAcrossInstancesAndArePersistedOnUpdate() throws Exception {
        Memory m=new Memory();JSONObject old=row("0xaa").json();old.remove("receiptId");old.remove("delayedNotified");
        m.data=new JSONArray().put(old).toString();Pending a=new Pending(m),b=new Pending(m);
        assertEquals(a.rows().get(0).receiptId,b.rows().get(0).receiptId);
        a.resolve(Collections.emptyMap(),100,listener(()->{},()->{}));
        assertEquals(a.rows().get(0).receiptId,b.rows().get(0).receiptId);
        assertEquals(1,b.rows().size());
    }
    @Test public void copiedOrderIdInBookCannotConfirmAnEdit(){
        Memory m=new Memory();Pending pending=new Pending(m);Pending.Row r=row("0xcc");
        r.kind=Pending.EDIT;r.coinid="0xff";pending.add(r);
        Order5 copy=Order5.from(TransactionHardeningTest.orderCoin());
        pending.resolve(Collections.singletonMap(copy.coinid,copy),100,listener(()->fail("copied row"),()->{}));
        assertEquals(1,pending.rows().size());
    }
    @Test public void editNeedsIncludedLinkedOutputWithExactRequestedAmountsAndPreservedIdentity() throws Exception {
        JSONObject raw=TransactionHardeningTest.orderCoin();Order5 original=Order5.from(raw);
        Pending.Row r=row(original.orderId);r.kind=Pending.EDIT;r.coinid=original.coinid;r.price=new BigDecimal("0.04");
        JSONObject output=new JSONObject(raw.toString()).put("coinid","0xee").put("address",DexContract.ADDR_V5).put("storestate",true).put("state",new JSONArray());
        JSONArray state=new JSONArray();JSONObject source=raw.getJSONObject("state");
        for(java.util.Iterator<String> it=source.keys();it.hasNext();) { String key=it.next(); state.put(new JSONObject().put("port",Integer.parseInt(key)).put("data",key.equals("2")?"4":source.getString(key))); }
        DexHistory.Spend spend=new DexHistory.Spend("0xaabb",0,new JSONArray().put(output),"0xccdd",0);
        spend.transactionState=state;spend.input=raw;
        assertTrue(Pending.editMatches(r,spend,original));
        spend.input = new JSONObject(raw.toString()).put("coinid", "0xOTHER");
        assertFalse(Pending.editMatches(r,spend,original));spend.input=raw;
        for (int i=0;i<state.length();i++) {
            JSONObject field=state.getJSONObject(i);int port=field.getInt("port");
            if (port==0 || port==1 || port==3 || port==4 || port==5 || port==7 || port==8) {
                String before=field.getString("data");
                field.put("data",port==5 || port==7 ? ("1".equals(before)?"0":"1") : port==8 ? "2" : "0xdead");
                assertFalse("Pinned port "+port, Pending.editMatches(r,spend,original));
                field.put("data",before);
            }
        }
        r.price=new BigDecimal("0.05");assertFalse(Pending.editMatches(r,spend,original));r.price=new BigDecimal("0.04");
        output.put("amount","99");assertFalse(Pending.editMatches(r,spend,original));output.put("amount","100");
        output.put("storestate",false);assertFalse(Pending.editMatches(r,spend,original));output.put("storestate",true);
        output.put("address","0xdead");assertFalse(Pending.editMatches(r,spend,original));output.put("address",DexContract.ADDR_V5);
        DexHistory.Spend mempool=new DexHistory.Spend("0xaabb",0,new JSONArray().put(output),"0xccdd",-1);mempool.transactionState=state;mempool.input=raw;
        assertFalse(Pending.editMatches(r,mempool,original));
    }
}

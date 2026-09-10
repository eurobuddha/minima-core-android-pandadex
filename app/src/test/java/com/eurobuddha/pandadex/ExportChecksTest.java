package com.eurobuddha.pandadex;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ExportChecksTest {
    static ExplorerVerifier.Result ok(String id){ExplorerVerifier.Result r=new ExplorerVerifier.Result();r.txpowid=id;r.status="EXPLORER_OK";r.block=100;return r;}
    @Test public void repeatedCaseVariantIdsReuseOneActualLookup(){
        int[] calls={0};ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->0,(v,id,n)->{calls[0]++;return v.lookup(id);});
        assertSame(checks.lookup("0xaabb"),checks.lookup("0xAABB"));assertEquals(1,calls[0]);
    }
    @Test public void unavailableRepliesAreAlsoReused(){
        int[] calls={0};ExportChecks checks=new ExportChecks(id->null,()->0,(v,id,n)->{calls[0]++;return null;});
        assertSame(checks.lookup("0xaa"),checks.lookup("0xAA"));assertEquals(1,calls[0]);
    }
    @Test public void elapsedBudgetStopsRequestsWithoutDroppingAnyReceipt(){
        long[] now={0};int[] calls={0};
        ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->now[0],(v,id,n)->{assertTrue(n<=ExportChecks.LOOKUP_NANOS);calls[0]++;now[0]+=TimeUnit.SECONDS.toNanos(6);return v.lookup(id);});
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();
        for(int i=0;i<100;i++)snapshot.rows.add(new TradeExport.TradeRow("source"+i,1000+i,100,BigDecimal.ONE,BigDecimal.ONE,true,true,"order",String.format("0x%04x",i),"BOOK","source"+i,"","CHAIN_VERIFIED","Original",100));
        TradeExport.Snapshot out=TradeExport.verifiedCopy(snapshot,checks);
        assertEquals(5,calls[0]);assertEquals(100,out.rows.size());
        assertTrue(out.rows.get(99).verificationNote.contains("time budget reached"));
        assertEquals(snapshot.rows.get(99).timeMs,out.rows.get(99).timeMs);
        assertEquals(new BigDecimal("100"),TradeExport.build(out).totals.minimaBought);
    }
    @Test public void uniqueIdLimitDoesNotHideAlreadyCheckedEvidence(){
        int[] calls={0};ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->0,(v,id,n)->{calls[0]++;return v.lookup(id);});
        for(int i=0;i<ExportChecks.MAX_IDS;i++)assertEquals("EXPLORER_OK",checks.lookup(String.format("0x%04x",i)).status);
        assertEquals("EXPLORER_SKIPPED",checks.lookup("0xffff").status);
        assertEquals("EXPLORER_OK",checks.lookup("0x0000").status);assertEquals(ExportChecks.MAX_IDS,calls[0]);
    }
    @Test public void timeoutAndBusyWorkerStopFurtherRequests(){
        for(Exception problem:new Exception[]{new TimeoutException(),new RejectedExecutionException(),new ExecutionException(new IllegalStateException())}) {
            int[] calls={0};ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->0,(v,id,n)->{calls[0]++;throw problem;});
            assertEquals("EXPLORER_SKIPPED",checks.lookup("0xaa").status);
            assertEquals("EXPLORER_SKIPPED",checks.lookup("0xbb").status);assertEquals(1,calls[0]);
        }
    }
    @Test public void interruptionIsPreservedAndDoesNotStartMoreWork(){
        ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->0,(v,id,n)->{throw new InterruptedException();});
        try{assertEquals("EXPLORER_SKIPPED",checks.lookup("0xaa").status);assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
    }
    @Test public void remainingDeadlineIsPassedToTheActualWait(){
        long[] now={0};ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->now[0],(v,id,n)->{assertEquals(TimeUnit.SECONDS.toNanos(1),n);return v.lookup(id);});
        now[0]=ExportChecks.TOTAL_NANOS-TimeUnit.SECONDS.toNanos(1);assertEquals("EXPLORER_OK",checks.lookup("0xaa").status);
    }
    @Test public void invalidIdsConsumeNoNetworkWork(){
        ExportChecks checks=new ExportChecks(ExportChecksTest::ok,()->0,(v,id,n)->{fail("invalid ID submitted");return null;});
        for(String id:new String[]{null,"","0xa","0xaa;send amount:1"})assertEquals("EXPLORER_SKIPPED",checks.lookup(id).status);
    }
    @Test public void timedOutNoninterruptibleWorkCannotCreateReplacementNetworkThreads() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        TradeExport.ExternalVerifier stuck=id->{calls.incrementAndGet();entered.countDown();
            boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
            finished.countDown();return ok(id);
        };
        try {
            try{ExportChecks.waitFor(stuck,"0xaa",TimeUnit.SECONDS.toNanos(1));fail("must time out");}catch(TimeoutException expected){}
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            for(int i=0;i<3;i++) {
                try{ExportChecks.waitFor(stuck,"0xbb",TimeUnit.MILLISECONDS.toNanos(20));fail("queued work must time out");}catch(TimeoutException expected){}
            }
            assertEquals("one physical request; cancelled queued work removed",1,calls.get());
        } finally {release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));}
        assertEquals("EXPLORER_OK",ExportChecks.waitFor(ExportChecksTest::ok,"0xcc",TimeUnit.SECONDS.toNanos(2)).status);
    }
}

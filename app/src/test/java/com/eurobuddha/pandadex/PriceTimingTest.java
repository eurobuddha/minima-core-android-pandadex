package com.eurobuddha.pandadex;

import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Deterministic elapsed-time and slow-connection faults; no network or node. */
public class PriceTimingTest {
    @After public void reset(){MarketPrice.testSnapshot(0,0);}
    @Test public void receiptAgeIncludesRequestTimeAndSleep() {
        long[] elapsed={0};MarketPrice.testSnapshot(0,-1,()->elapsed[0]);
        elapsed[0]=29_999;assertTrue(MarketPrice.acceptMid(1,0));
        assertEquals(29_999,MarketPrice.ageMs());
        elapsed[0]=6*60_000;assertFalse(MarketPrice.fresh());assertTrue(MarketPrice.widenFactor()>1);
        elapsed[0]=20*60_000;assertTrue(MarketPrice.mustWithdraw());
    }
    @Test public void lateSuccessfulPriceCannotRefreshPreviousSnapshot() {
        long[] elapsed={0};MarketPrice.testSnapshot(1,0,()->elapsed[0]);
        elapsed[0]=30_000;assertFalse(MarketPrice.acceptMid(1.1,0));
        assertEquals(1,MarketPrice.mid(),0);assertEquals(30_000,MarketPrice.ageMs());
        assertTrue(MarketPrice.lastError().contains("deadline"));
    }
    @Test public void elapsedDomainDoesNotRequireAnEpochTimestamp() {
        long[] elapsed={1000};MarketPrice.testSnapshot(1,1000,()->elapsed[0]);
        assertTrue(MarketPrice.fresh());elapsed[0]=1050;assertEquals(50,MarketPrice.ageMs());
        elapsed[0]+=300_001;assertFalse(MarketPrice.fresh());
        elapsed[0]=1000+MarketPrice.WITHDRAW_MS;assertTrue(MarketPrice.mustWithdraw());
    }
    @Test public void invalidElapsedClockFailsClosed() {
        long[] elapsed={100};MarketPrice.testSnapshot(1,100,()->elapsed[0]);elapsed[0]=99;
        assertEquals(Long.MAX_VALUE,MarketPrice.ageMs());assertTrue(MarketPrice.mustWithdraw());
        assertFalse(MarketPrice.acceptMid(1.1,100));
    }
    @Test public void staleSuspectReadingCannotCorroborateANewJump() {
        long[] elapsed={0};MarketPrice.testSnapshot(1,0,()->elapsed[0]);
        assertFalse(MarketPrice.acceptMid(10));elapsed[0]=MarketPrice.WITHDRAW_MS;
        assertFalse(MarketPrice.acceptMid(10));assertTrue(MarketPrice.mustWithdraw());
        elapsed[0]+=30_000;assertTrue(MarketPrice.acceptMid(10));assertTrue(MarketPrice.fresh());
    }
    @Test public void normalBackgroundCadenceCanResolveASuspectJump() {
        long[] elapsed={0};MarketPrice.testSnapshot(1,0,()->elapsed[0]);
        assertFalse(MarketPrice.acceptMid(10));elapsed[0]=15*60_000;
        assertTrue(MarketPrice.acceptMid(10));assertTrue(MarketPrice.fresh());
    }
    private static final class Connection extends HttpURLConnection {
        final long[] now;final byte[] body;
        long headers,perRead,closeDelay;int status=200,reads,opened;boolean disconnected,closed;
        Connection(long[] now,String body)throws Exception{super(new URL("https://fixture.invalid/price"));this.now=now;this.body=body.getBytes(StandardCharsets.UTF_8);}
        public int getResponseCode(){now[0]+=headers;return status;}
        public InputStream getInputStream(){opened++;return new ByteArrayInputStream(body){
            public synchronized int read(byte[] b,int off,int len){reads++;now[0]+=perRead;return super.read(b,off,Math.min(len,3));}
            public void close(){closed=true;now[0]+=closeDelay;}
        };}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public void connect(){}
    }
    private static String body(){return "{\"bids\":[[\"1\",\"30\"]],\"asks\":[[\"1.1\",\"30\"]]}";}
    private void rejected(Connection c)throws Exception {
        try{MarketPrice.httpGet(c,()->c.now[0],0);fail("late or invalid response accepted");}catch(IOException expected){}
        assertTrue(c.disconnected);
    }
    @Test public void ordinaryResponseRetainsDepthAndDisablesRedirectsAndCache()throws Exception {
        long[] now={0};Connection c=new Connection(now,body());c.perRead=10;
        assertEquals(1.1,MarketPrice.httpGet(c,()->now[0],0).getJSONArray("asks").getJSONArray(0).getDouble(0),0);
        assertTrue(c.closed&&c.disconnected);assertFalse(c.getInstanceFollowRedirects());assertFalse(c.getUseCaches());
        assertTrue(c.getReadTimeout()>0&&c.getReadTimeout()<=10_000);
    }
    @Test public void dribblingBodyCannotExtendTotalDeadline()throws Exception {
        Connection c=new Connection(new long[]{0},body());c.perRead=3000;rejected(c);
        assertEquals(10,c.reads);assertTrue(c.closed);
    }
    @Test public void lateHeadersAreRejectedBeforeReadingBody()throws Exception {
        Connection c=new Connection(new long[]{0},body());c.headers=30_000;rejected(c);assertEquals(0,c.opened);
    }
    @Test public void closeTimeIsPartOfTheDeadline()throws Exception {
        Connection c=new Connection(new long[]{0},body());c.closeDelay=30_000;rejected(c);assertTrue(c.closed);
    }
    @Test public void remainingTimeShrinksPerReadTimeout()throws Exception {
        Connection c=new Connection(new long[]{0},body());c.headers=25_000;
        MarketPrice.httpGet(c,()->c.now[0],0);assertEquals(5000,c.getReadTimeout());
    }
    @Test public void redirectsAndErrorsDoNotReadAnotherBody()throws Exception {
        for(int code:new int[]{301,302,429,500}){Connection c=new Connection(new long[]{0},body());c.status=code;rejected(c);assertEquals(0,c.opened);}
    }
    @Test public void oversizedBodyClosesAndDisconnects()throws Exception {
        Connection c=new Connection(new long[]{0},new String(new char[16*1024+1]).replace('\0','x'));rejected(c);assertTrue(c.closed);
    }
    @Test public void malformedJsonIsNotAccepted()throws Exception {
        Connection c=new Connection(new long[]{0},"invalid");rejected(c);assertTrue(c.closed);
    }
}

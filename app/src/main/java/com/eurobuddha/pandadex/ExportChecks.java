package com.eurobuddha.pandadex;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Optional external evidence has a work budget; original records never depend on it. */
final class ExportChecks implements TradeExport.ExternalVerifier {
    static final long TOTAL_NANOS=TimeUnit.SECONDS.toNanos(30);
    static final long LOOKUP_NANOS=TimeUnit.SECONDS.toNanos(10);
    static final int MAX_IDS=128;
    // A stuck DNS/socket call cannot create replacement threads or an unbounded request queue.
    private static final ThreadPoolExecutor NETWORK=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"pandadex-export-check");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    interface Runner { ExplorerVerifier.Result run(TradeExport.ExternalVerifier verifier,String id,long waitNanos) throws Exception; }
    private final TradeExport.ExternalVerifier verifier;
    private final LongSupplier clock;
    private final Runner runner;
    private final long started;
    private final Map<String,ExplorerVerifier.Result> checked=new HashMap<>();
    private String stopped="";
    ExportChecks(TradeExport.ExternalVerifier verifier) {this(verifier,System::nanoTime,ExportChecks::waitFor);}
    ExportChecks(TradeExport.ExternalVerifier verifier,LongSupplier clock,Runner runner) {
        this.verifier=verifier;this.clock=clock;this.runner=runner;started=clock.getAsLong();
    }
    static ExplorerVerifier.Result waitFor(TradeExport.ExternalVerifier verifier,String id,long waitNanos) throws Exception {
        Future<ExplorerVerifier.Result> pending=NETWORK.submit(()->verifier.lookup(id));
        try {return pending.get(waitNanos,TimeUnit.NANOSECONDS);}
        finally {
            if(!pending.isDone()) pending.cancel(true);
            // Cancellation need not interrupt DNS/HTTP. Keep that physical worker occupied;
            // remove only a cancelled queued task, never start a replacement worker.
            if(pending instanceof Runnable) NETWORK.remove((Runnable)pending);
        }
    }
    @Override public ExplorerVerifier.Result lookup(String id) {
        if(!FundingCoins.hex(id)) return skipped("No usable TxPoW ID; original receipt retained");
        String key=id.toLowerCase(Locale.ROOT);
        if(checked.containsKey(key)) return checked.get(key);
        if(!stopped.isEmpty()) return skipped(stopped);
        long elapsed=clock.getAsLong()-started;
        if(elapsed<0 || elapsed>=TOTAL_NANOS) stopped="Public explorer time budget reached";
        else if(checked.size()>=MAX_IDS) stopped="Public explorer lookup limit reached";
        if(!stopped.isEmpty()) return skipped(stopped);
        ExplorerVerifier.Result result;
        try {
            result=runner.run(verifier,key,Math.min(LOOKUP_NANOS,TOTAL_NANOS-elapsed));
            if(result==null) result=skipped("Public explorer returned no usable response");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();stopped="Public explorer checks interrupted";result=skipped(stopped);
        } catch(TimeoutException timeout) {
            stopped="Public explorer response timed out";result=skipped(stopped);
        } catch(RejectedExecutionException busy) {
            stopped="A previous public explorer request is still finishing";result=skipped(stopped);
        } catch(Exception failure) {
            stopped="Public explorer checks unavailable";result=skipped(stopped);
        }
        checked.put(key,result);
        return result;
    }
    private static ExplorerVerifier.Result skipped(String why) {
        ExplorerVerifier.Result r=new ExplorerVerifier.Result();r.status="EXPLORER_SKIPPED";
        r.note=why+"; not externally verified in this export. All original receipt evidence is retained.";
        return r;
    }
}

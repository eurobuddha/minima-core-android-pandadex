package com.eurobuddha.pandadex;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class KeySetRecoveryTest {
    static final class Node implements FundingCoins.Command {
        final List<String> commands = new ArrayList<>();
        final List<NodeApi.Cb> replies = new ArrayList<>();
        public void run(String command, NodeApi.Cb cb) { commands.add(command); replies.add(cb); }
        NodeApi.Cb last() { return replies.get(replies.size()-1); }
    }
    static final class Timer implements PoolLiquidityRepository.Scheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        public void after(long delay, Runnable task) { delays.add(delay); tasks.add(task); }
        void cancel() { /* Keep cancelled tasks accessible to inject already-queued delivery. */ }
        void runLast() { tasks.get(tasks.size()-1).run(); }
    }
    static final class Fixture {
        final MakerWithdrawalDurabilityTest.Memory memory = new MakerWithdrawalDurabilityTest.Memory();
        final Node node = new Node(); final Timer timer = new Timer();
        int notifications;
        Runnable onReady = () -> {};
        final KeySet keys;
        Fixture() {
            memory.visible.put("node_keys", "[\"0xaa\"]");
            memory.visible.put("node_addrs", "[\"0x11\"]");
            memory.disk.putAll(memory.visible);
            SharedPreferences base = memory.prefs();
            // Reuse the acknowledged preference fixture; translate cache apply to its commit.
            SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class[]{SharedPreferences.class}, (p,m,a) -> {
                if (!m.getName().equals("edit")) return m.invoke(base,a);
                SharedPreferences.Editor editor=base.edit();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(e,em,ea)->{
                    if (em.getName().equals("apply")) { editor.commit(); return null; }
                    Object result=em.invoke(editor,ea);
                    return result instanceof SharedPreferences.Editor ? e : result;
                });
            });
            keys=new KeySet(prefs,()->{notifications++;onReady.run();},timer,timer::cancel);
        }
        void start() { keys.refresh(node); }
        void success(String key,String address) { start();node.last().onResult(keyReply(key));node.last().onResult(addressReply(address)); }
    }
    static JSONObject keyReply(String... keys) {
        return new TestJson().put("status",true).put("response",new JSONArray(Arrays.asList(keys)));
    }
    static JSONObject addressReply(String address) {
        return new TestJson().put("status",true).put("response",new TestJson().put("parseok",true)
                .put("script",new TestJson().put("address",address)));
    }
    static void cached(Fixture f) {
        assertEquals(Collections.singleton("0xaa"),f.keys.keys());
        assertEquals(Collections.singleton("0x11"),f.keys.addrs());
    }
    @Test public void cachedKeysCannotAuthorizeAndCompleteLoadPublishesBothFactorsTogether() {
        Fixture f=new Fixture();assertFalse(f.keys.ready());cached(f);f.start();
        f.node.last().onResult(keyReply("0xbb","0xcc"));cached(f);assertEquals(0,f.memory.writes);
        f.node.last().onResult(addressReply("0x22"));cached(f);assertFalse(f.keys.ready());
        f.node.last().onResult(addressReply("0x33"));assertTrue(f.keys.ready());assertEquals(1,f.notifications);
        assertEquals(new HashSet<>(Arrays.asList("0xbb","0xcc")),f.keys.keys());
        assertEquals(new HashSet<>(Arrays.asList("0x22","0x33")),f.keys.addrs());
        assertEquals(1,f.memory.writes);assertEquals(f.memory.disk,f.memory.visible);
    }
    @Test public void invalidationRejectsOldKeysReplyBeforeDerivation() {
        Fixture f=new Fixture();f.start();NodeApi.Cb old=f.node.last();f.keys.invalidate();old.onResult(keyReply("0xbb"));
        assertEquals(1,f.node.commands.size());assertFalse(f.keys.ready());cached(f);assertEquals(0,f.notifications);
    }
    @Test public void invalidationRejectsOldAddressReplyAndAllowsFreshLoad() {
        Fixture f=new Fixture();f.start();f.node.last().onResult(keyReply("0xbb"));NodeApi.Cb old=f.node.last();
        f.keys.invalidate();f.start();old.onResult(addressReply("0x22"));assertFalse(f.keys.ready());cached(f);
        f.node.last().onResult(keyReply("0xcc"));f.node.last().onResult(addressReply("0x33"));
        assertTrue(f.keys.ready());assertEquals(Collections.singleton("0xcc"),f.keys.keys());assertEquals(1,f.notifications);
    }
    @Test public void obsoleteErrorCannotCancelOrScheduleRetryOverANewerLoad() {
        Fixture f=new Fixture();f.start();NodeApi.Cb old=f.node.last();f.keys.invalidate();f.start();old.onError("late");
        assertTrue(f.timer.tasks.isEmpty());f.node.last().onResult(keyReply("0xbb"));f.node.last().onResult(addressReply("0x22"));
        old.onError("duplicate");assertTrue(f.keys.ready());assertTrue(f.timer.tasks.isEmpty());assertEquals(1,f.notifications);
    }
    @Test public void failedSecondAddressRetainsCompleteOldCacheAndCannotPublishLateSuccess() {
        Fixture f=new Fixture();f.start();f.node.last().onResult(keyReply("0xbb","0xcc"));f.node.last().onResult(addressReply("0x22"));
        NodeApi.Cb last=f.node.last();last.onError("offline");last.onResult(addressReply("0x33"));
        cached(f);assertFalse(f.keys.ready());assertEquals(0,f.memory.writes);assertEquals(0,f.notifications);assertEquals(1,f.timer.tasks.size());
    }
    @Test public void repeatedDerivationFailuresUseTheFullBackoffInsteadOfResettingAtKeys() {
        Fixture f=new Fixture();f.start();
        for(int i=0;i<5;i++) { f.node.last().onResult(keyReply("0xbb"));f.node.last().onError("derive failed");f.timer.runLast(); }
        assertEquals(Arrays.asList(10_000L,30_000L,90_000L,300_000L,300_000L),f.timer.delays);cached(f);
    }
    @Test public void unchangedVerifiedKeysCanReuseAddressesButInvalidatedKeysMustRederive() {
        Fixture f=new Fixture();f.success("0xbb","0x22");int before=f.node.commands.size();
        f.start();assertFalse(f.keys.ready());f.node.last().onResult(keyReply("0xbb"));
        assertTrue(f.keys.ready());assertEquals(before+1,f.node.commands.size());
        f.keys.invalidate();f.start();f.node.last().onResult(keyReply("0xbb"));assertFalse(f.keys.ready());
        assertTrue(f.node.commands.get(f.node.commands.size()-1).startsWith("runscript "));
        f.node.last().onResult(addressReply("0x22"));assertTrue(f.keys.ready());
    }
    @Test public void cancelledRetryCannotStartAnotherLoadAfterManualRefreshOrInvalidation() {
        Fixture f=new Fixture();f.start();f.node.last().onError("offline");Runnable old=f.timer.tasks.get(0);
        f.start();f.start();assertEquals(2,f.node.commands.size());old.run();assertEquals(2,f.node.commands.size());
        f.node.last().onError("offline again");Runnable newer=f.timer.tasks.get(1);f.keys.invalidate();newer.run();
        assertEquals(2,f.node.commands.size());assertFalse(f.keys.ready());
    }
    @Test public void closeRejectsPendingCallbacksAndAlreadyQueuedRetries() {
        Fixture f=new Fixture();f.start();NodeApi.Cb old=f.node.last();old.onError("offline");Runnable retry=f.timer.tasks.get(0);
        f.keys.close();retry.run();old.onResult(keyReply("0xbb"));f.start();
        assertEquals(1,f.node.commands.size());assertFalse(f.keys.ready());cached(f);assertEquals(0,f.notifications);
    }
    @Test public void malformedOrEmptyKeyRepliesPreserveCacheAndNeverAuthorize() {
        for(JSONObject reply:Arrays.asList(keyReply(),keyReply("bad"),new TestJson().put("status",false),new TestJson().put("status",true))) {
            Fixture f=new Fixture();f.start();f.node.last().onResult(reply);cached(f);assertFalse(f.keys.ready());
            assertEquals(0,f.notifications);assertEquals(0,f.memory.writes);assertEquals(1,f.timer.tasks.size());
        }
    }
    @Test public void watcherResumesExactlyOnceAfterTheLastDerivedAddress() {
        Fixture f=new Fixture();WatcherPassGate gate=new WatcherPassGate(300_000);
        int[] scans={0};
        f.onReady=()->{if(gate.resumeAfterKeys(f.keys.ready()))scans[0]++;};
        assertEquals(WatcherPassGate.Action.SCAN,gate.next(0,true,false,true));
        f.start();f.node.last().onResult(keyReply("0xbb","0xcc"));
        f.node.last().onResult(addressReply("0x22"));assertEquals(0,f.notifications);assertEquals(0,scans[0]);
        f.node.last().onResult(addressReply("0x33"));assertEquals(1,f.notifications);
        f.onReady.run(); // Duplicate ready notification cannot start another book scan.
        assertEquals(1,scans[0]);
    }
    @Test public void watcherCannotResumeAfterForegroundHandoffOrDisconnect() {
        WatcherPassGate gate=new WatcherPassGate(300_000);
        assertEquals(WatcherPassGate.Action.SCAN,gate.next(0,true,false,true));
        assertFalse(gate.resumeAfterKeys(false));assertFalse(gate.resumeAfterKeys(true));
        assertEquals(WatcherPassGate.Action.SCAN,gate.next(300_000,true,false,true));
        gate.invalidate();assertFalse(gate.resumeAfterKeys(true));
    }

}

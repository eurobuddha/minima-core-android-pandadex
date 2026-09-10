package com.eurobuddha.pandadex;
import org.junit.Test;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class CommandSessionTest {
    static class Sender implements CommandSession.Sender {
        final List<String> queued=new ArrayList<>(),sent=new ArrayList<>();
        final List<NodeApi.Cb> callbacks=new ArrayList<>();
        final List<BooleanSupplier> guards=new ArrayList<>();
        public void run(String command,NodeApi.Cb cb,BooleanSupplier guard){queued.add(command);callbacks.add(cb);guards.add(guard);}
        void dispatch(int index){if(CommandSession.authorized(guards.get(index)))sent.add(queued.get(index));else callbacks.get(index).onError(CommandSession.CHANGED);}
    }
    static NodeApi.Cb failure(List<String> errors){return new NodeApi.Cb(){public void onResult(org.json.JSONObject r){fail("obsolete request succeeded");}public void onError(String e){errors.add(e);}};}
    @Test public void currentSnapshotCanReachDispatch(){
        Sender sender=new Sender();CommandSession session=new CommandSession(sender);List<String> errors=new ArrayList<>();
        session.snapshot().run("txnsign id:fixture publickey:auto",failure(errors));sender.dispatch(0);
        assertEquals(sender.queued,sender.sent);assertTrue(errors.isEmpty());
    }
    @Test public void invalidSnapshotNeverEntersTheQueue(){
        Sender sender=new Sender();CommandSession session=new CommandSession(sender);CommandSession.Snapshot old=session.snapshot();
        session.invalidate();List<String> errors=new ArrayList<>();old.run("txnpost id:fixture",failure(errors));
        assertTrue(sender.queued.isEmpty());assertEquals(Collections.singletonList(CommandSession.CHANGED),errors);
    }
    @Test public void invalidationWhileWaitingPreventsPhysicalDispatch(){
        Sender sender=new Sender();CommandSession session=new CommandSession(sender);List<String> errors=new ArrayList<>();
        session.snapshot().run("txnpost id:fixture",failure(errors));session.invalidate();sender.dispatch(0);
        assertTrue(sender.sent.isEmpty());assertEquals(Collections.singletonList(CommandSession.CHANGED),errors);
    }
    @Test public void anotherHostDisconnectInvalidatesBothSessions(){
        Object[] connection={new Object()};Sender sender=new Sender();
        CommandSession first=new CommandSession(sender,()->connection[0]),second=new CommandSession(sender,()->connection[0]);
        CommandSession.Snapshot a=first.snapshot(),b=second.snapshot();connection[0]=new Object();
        assertFalse(a.current());assertFalse(b.current());
        assertFalse(first.snapshot().current());assertFalse(second.snapshot().current());
        first.bindConnection();assertTrue(first.snapshot().current());assertFalse(second.snapshot().current());
        assertFalse(a.current());
    }
    @Test public void oldChainCannotContinueSigningOrDeleteOnTheNewConnection(){
        Sender sender=new Sender();CommandSession session=new CommandSession(sender);List<String> errors=new ArrayList<>();
        CmdChain.run(session.snapshot(),Arrays.asList("txncreate id:fixture","txnsign id:fixture publickey:auto","txnpost id:fixture"),"txndelete id:fixture",new CmdChain.Done(){
            public void ok(org.json.JSONObject last){fail("obsolete chain completed");}public void fail(String e){errors.add(e);}});
        sender.dispatch(0);session.invalidate();sender.callbacks.get(0).onResult(new TestJson().put("status",true));
        assertEquals(Collections.singletonList("txncreate id:fixture"),sender.sent);
        assertEquals(sender.sent,sender.queued);assertEquals(Collections.singletonList(CommandSession.CHANGED),errors);
    }
    @Test public void unchangedSessionPreservesSequentialConstructionAndCleanup(){
        Sender sender=new Sender();CommandSession session=new CommandSession(sender);List<String> errors=new ArrayList<>();
        CmdChain.run(session.snapshot(),Arrays.asList("txncreate id:fixture","txnsign id:fixture publickey:auto"),"txndelete id:fixture",new CmdChain.Done(){
            public void ok(org.json.JSONObject last){fail("failed sign accepted");}public void fail(String e){errors.add(e);}});
        sender.dispatch(0);sender.callbacks.get(0).onResult(new TestJson().put("status",true));
        sender.dispatch(1);sender.callbacks.get(1).onResult(new TestJson().put("status",false).put("error","fixture failed"));
        sender.dispatch(2);assertEquals(Arrays.asList("txncreate id:fixture","txnsign id:fixture publickey:auto","txndelete id:fixture"),sender.sent);assertEquals(1,errors.size());
    }
    static CommandSession session(DexTxn tx)throws Exception {
        java.lang.reflect.Field field=DexTxn.class.getDeclaredField("commandSession");field.setAccessible(true);return (CommandSession)field.get(tx);
    }
    @Test public void changedIdentityCannotReauthorizeAnOldSnapshotAfterChangingBack()throws Exception {
        DexTxn tx=new DexTxn(null,null);tx.setIdentity("0xaa","0x11");CommandSession.Snapshot old=session(tx).snapshot();
        tx.setIdentity("0xaa","0x11");assertTrue(old.current());
        tx.setIdentity("0xbb","0x22");tx.setIdentity("0xaa","0x11");assertFalse(old.current());
        assertTrue(session(tx).snapshot().current());
    }
    @Test public void disconnectInvalidatesOwnerWorkEvenWhenIdentityWasEmpty()throws Exception {
        DexTxn tx=new DexTxn(null,null);CommandSession.Snapshot old=session(tx).snapshot();tx.invalidateIdentity();
        assertFalse(old.current());assertEquals("",tx.pubkey());assertEquals("",tx.hexAddr());
    }
    @Test public void missingOrThrowingDispatchGuardFailsClosed(){
        assertFalse(CommandSession.authorized(null));assertFalse(CommandSession.authorized(()->{throw new IllegalStateException("fixture");}));
        assertTrue(CommandSession.authorized(()->true));
    }
}

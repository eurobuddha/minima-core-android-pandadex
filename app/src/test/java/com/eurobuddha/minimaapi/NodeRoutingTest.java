package com.eurobuddha.minimaapi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the public SDK with simulated Android broadcast delivery, without a live wallet. */
public class NodeRoutingTest {
    static final String CLASSIC = MinimaAPIMessages.MINIMA_BASE_CLASS;
    static final String BLOCK = MinimaAPI.BLOCK_PACKAGE;

    @Test public void pandamoniumOnlyReceivesQueuedCommand() throws Exception { route(Collections.singleton(MinimaAPI.PANDAMONIUM_PACKAGE), MinimaAPI.PANDAMONIUM_PACKAGE); }
    @Test public void pandamoniumAndClassicRefuseCommand() throws Exception { route(new HashSet<>(Arrays.asList(CLASSIC, MinimaAPI.PANDAMONIUM_PACKAGE)), null); }
    @Test public void allThreeRefuseCommand() throws Exception { route(new HashSet<>(Arrays.asList(CLASSIC, BLOCK, MinimaAPI.PANDAMONIUM_PACKAGE)), null); }
    @Test public void blockOnlyReceivesQueuedCommand() throws Exception { route(Collections.singleton(BLOCK), BLOCK); }
    @Test public void classicOnlyReceivesQueuedCommand() throws Exception { route(Collections.singleton(CLASSIC), CLASSIC); }
    @Test public void twoNodesRefuseCommand() throws Exception { route(new HashSet<>(Arrays.asList(CLASSIC, BLOCK)), null); }
    @Test public void noNodesRefuseCommand() throws Exception { route(Collections.emptySet(), null); }

    private void route(Set<String> responders, String expected) throws Exception {
        List<Runnable> timers = new ArrayList<>();
        List<Intent> broadcasts = new ArrayList<>();
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getPackageName()).thenReturn("test.companion");
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(eq("myapp_uid"), anyString())).thenReturn("0x"+"ab".repeat(16));
        when(prefs.getString(eq("minima_uid"), anyString())).thenReturn("0x"+"cd".repeat(16));
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        doAnswer(i -> { broadcasts.add(i.getArgument(0)); return null; }).when(context).sendBroadcast(any());
        try (org.mockito.MockedStatic<android.os.Looper> looper = mockStatic(android.os.Looper.class);
             org.mockito.MockedStatic<androidx.core.content.ContextCompat> receiver = mockStatic(androidx.core.content.ContextCompat.class);
             MockedConstruction<android.content.IntentFilter> filters = mockConstruction(android.content.IntentFilter.class);
             MockedConstruction<MinimaAPIReceive> callbacks = mockConstruction(MinimaAPIReceive.class);
             MockedConstruction<Handler> handlers = mockConstruction(Handler.class, (h, c) -> {
            when(h.post(any())).thenAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return true; });
            when(h.postDelayed(any(), anyLong())).thenAnswer(i -> { if ((Long)i.getArgument(1) == 1500L) timers.add(i.getArgument(0)); return true; });
        }); MockedConstruction<Intent> intents = mockConstruction(Intent.class, (intent, construction) -> {
            Map<String, String> extras = new HashMap<>();
            String[] target = {null};
            when(intent.getAction()).thenReturn(construction.arguments().isEmpty() ? "" : (String)construction.arguments().get(0));
            when(intent.setPackage(anyString())).thenAnswer(i -> { target[0] = i.getArgument(0); return intent; });
            when(intent.getPackage()).thenAnswer(i -> target[0]);
            when(intent.putExtra(anyString(), anyString())).thenAnswer(i -> { extras.put(i.getArgument(0), i.getArgument(1)); return intent; });
            when(intent.getStringExtra(anyString())).thenAnswer(i -> extras.get(i.getArgument(0)));
        })) {
            MinimaAPI api = new MinimaAPI(context, null);
            try {
                List<JSONObject> replies = new ArrayList<>();
                api.Command("send address:TEST amount:1", replies::add);
                assertEquals("Only registration may fan out", 3, broadcasts.size());
                for (Intent request : new ArrayList<>(broadcasts)) {
                    assertEquals(MinimaAPIMessages.MINIMA_API_REGISTER, request.getAction());
                    if (responders.contains(request.getPackage())) {
                        Intent reply = new Intent("reply");
                        reply.putExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID, "0x"+"cd".repeat(16));
                        reply.putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID, request.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID));
                        reply.putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT, "{\"status\":true}");
                        api.ResponseReceived(reply);
                    }
                }
                timers.remove(0).run();
                if (expected == null) {
                    assertEquals(3, broadcasts.size());
                    assertEquals(1, replies.size());
                    assertFalse(replies.get(0).has("status"));
                    assertTrue(replies.get(0).has("transporterror"));
                } else {
                    assertEquals(4, broadcasts.size());
                    assertEquals(expected, broadcasts.get(3).getPackage());
                    assertEquals(MinimaAPIMessages.MINIMA_API_CMD, broadcasts.get(3).getAction());
                    api.FileCommand("list", "/", null, null, replies::add);
                    assertEquals(5, broadcasts.size());
                    assertEquals(expected, broadcasts.get(4).getPackage());
                    assertEquals(MinimaAPIMessages.MINIMA_API_FILE, broadcasts.get(4).getAction());
                }
                int count = broadcasts.size();
                api.onDestroy();
                api.Command("send address:TEST amount:1", replies::add);
                assertEquals("Destroyed owner cannot send", count, broadcasts.size());
            } finally { api.onDestroy(); }
        }
    }
}

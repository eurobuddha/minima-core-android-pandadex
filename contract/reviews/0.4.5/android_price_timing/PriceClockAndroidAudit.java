package com.eurobuddha.pandadex;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.function.LongSupplier;

/** Actual framework clock/alarm check. No node, network, production Activity or service. */
public final class PriceClockAndroidAudit extends Instrumentation {
    private int checks;private boolean cleanup;
    @Override public void onCreate(Bundle args){super.onCreate(args);cleanup="cleanup".equals(args.getString("phase"));start();}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context ctx=getTargetContext();AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(cleanup){
            PendingIntent pi=PendingIntent.getBroadcast(ctx,71,new Intent(ctx,HeartbeatReceiver.class),PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            check(pi!=null,"heartbeat pending intent exists");am.cancel(pi);pi.cancel();
        }else{
            long before=SystemClock.elapsedRealtime();
            check(MarketPrice.acceptMid(1),"first real elapsed-clock quote accepted");
            long after=SystemClock.elapsedRealtime();
            java.lang.reflect.Field stamp=MarketPrice.class.getDeclaredField("fetchedAt");stamp.setAccessible(true);
            long captured=stamp.getLong(null);check(captured>=before&&captured<=after,"production oracle uses elapsed request time");
            check(MarketPrice.ageMs()>=0&&MarketPrice.ageMs()<1000&&MarketPrice.fresh(),"initial quote fresh in actual elapsed domain");
            MakerEngine engine=new MakerEngine(new MakerConfig(),null,o->false);
            java.lang.reflect.Field timer=MakerEngine.class.getDeclaredField("clock");timer.setAccessible(true);
            long value=((LongSupplier)timer.get(engine)).getAsLong();
            check(value>=before&&value<=SystemClock.elapsedRealtime(),"production maker clock uses elapsed realtime");
            check(Math.abs(System.currentTimeMillis()-value)>365L*24*60*60_000,"elapsed domain differs from calendar epoch");
            HeartbeatReceiver.schedule(ctx);
            PendingIntent pi=PendingIntent.getBroadcast(ctx,71,new Intent(ctx,HeartbeatReceiver.class),PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            check(pi!=null,"real heartbeat pending intent registered");
            result.putLong("alarm_expected_earliest_elapsed",before+15*60_000);
            result.putLong("alarm_expected_latest_elapsed",SystemClock.elapsedRealtime()+15*60_000);
        }
        result.putString("stream","PASS "+checks+" assertions\nActual Android elapsed clock "+(cleanup?"cleanup":"and alarm scheduling")+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" assertions\n"+android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}}
}

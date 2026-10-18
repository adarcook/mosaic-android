package life.mosaic.tensorllm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Visible, bounded microphone/playback lifetime for a nonce-authorized assistant turn. */
public final class TurnForegroundService extends Service {
    static final String STOPPED="life.mosaic.tensorllm.TURN_STOPPED";
    private static volatile boolean active;
    private PowerManager.WakeLock lock;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable timeout=()->requestStop();
    static boolean active(){return active;}
    static void start(Context context){context.startForegroundService(new Intent(context,TurnForegroundService.class));}
    static void stop(Context context){context.stopService(new Intent(context,TurnForegroundService.class));}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP".equals(intent.getAction())){requestStop();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        try{
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Visible notification required");
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("mosaic_turn","שיחה עם מוזאיק",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,91,new Intent(this,TurnForegroundService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,"mosaic_turn").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("מוזאיק · שיחה פעילה").setContentText("מקשיב ומשיב גם כשהמסך כבוי. אפשר לעצור כאן.")
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,"עצירת הפנייה",stop).build()).build();
            startForeground(55,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"mosaic:active-turn");lock.acquire(360000);
            active=true;main.postDelayed(timeout,360000);
        }catch(Exception error){requestStop();}
        return START_NOT_STICKY;
    }
    private void requestStop(){active=false;sendBroadcast(new Intent(STOPPED).setPackage(getPackageName()));stopSelf();}
    @Override public void onDestroy(){active=false;main.removeCallbacks(timeout);if(lock!=null&&lock.isHeld())lock.release();lock=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}

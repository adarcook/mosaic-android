package life.mosaic.tensorllm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import life.mosaic.audio.HeadsetAudio;

/** One route/focus owner across companion wake, capture, feedback and isolated TTS. */
public final class HeadsetRouteService extends Service {
    private static HeadsetRouteService instance;
    private static volatile HeadsetAudio selected;
    private static boolean starting;
    private static Runnable ready;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AndroidHeadsetRoute route;
    private boolean closing;
    private int sinkId;
    private long deadline;
    static boolean required(Context c){return c.getSharedPreferences("handsfree",0).getBoolean("headset_mode",false);}
    static HeadsetAudio current(){return selected;}
    static void ensure(Context c,Runnable done){
        if(selected!=null){done.run();return;}
        ready=done;if(starting)return;starting=true;
        try{c.startForegroundService(new Intent(c,HeadsetRouteService.class));}
        catch(RuntimeException error){starting=false;ready=null;HandsFreeController.status(c,"Android חסם ניתוב לאוזניות · יש להפעיל מתוך מוזאיק");}
    }
    static void stop(Context c){selected=null;starting=false;ready=null;if(instance!=null)instance.releaseNow();c.stopService(new Intent(c,HeadsetRouteService.class));}
    private void releaseNow(){closing=true;main.removeCallbacksAndMessages(null);try{if(route!=null)route.close();}catch(RuntimeException ignored){}}
    @Override public void onCreate(){super.onCreate();instance=this;}
    static int standbySink(Context c){AudioDeviceInfo device=choose(c.getSystemService(AudioManager.class));return device==null?-1:device.getId();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP".equals(intent.getAction())){HandsFreeController.disable(this,"האזנת האוזניות הופסקה");return START_NOT_STICKY;}
        try{
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED))throw new SecurityException("Visible microphone consent required");
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("mosaic_headset","האזנה דרך האוזניות",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,92,new Intent(this,HeadsetRouteService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            startForeground(56,new Notification.Builder(this,"mosaic_headset").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("מוזאיק · מיקרופון האוזניות")
                .setContentText("ניתוב שיחה פעיל לאוזניות. עלול להשפיע על מדיה וסוללה.")
                .setOngoing(true).addAction(new Notification.Action.Builder(null,"הפסקת האזנה",stop).build()).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            if(!required(this)||!HandsFreeController.persistent(this)||!HandsFreeController.selected(this)){stopSelf();return START_NOT_STICKY;}
            if(route!=null)return START_NOT_STICKY;
            AudioManager audio=getSystemService(AudioManager.class);
            AudioDeviceInfo sink=choose(audio);
            if(sink==null)throw new IllegalStateException("Connect one headset");
            sinkId=sink.getId();deadline=SystemClock.elapsedRealtime()+7000;
            route=new AndroidHeadsetRoute(audio,main,this::lost);
            route.start(sinkId);main.post(this::poll);
        }catch(Exception error){lost();}
        return START_NOT_STICKY;
    }
    private static AudioDeviceInfo choose(AudioManager audio){
        AudioDeviceInfo chosen=null;
        for(AudioDeviceInfo device:audio.getAvailableCommunicationDevices())if(AndroidHeadsetRoute.headset(device)){
            if(chosen!=null)return null;chosen=device;
        }
        return chosen;
    }
    private void poll(){
        if(closing)return;
        try{
            AudioManager audio=getSystemService(AudioManager.class);
            AudioDeviceInfo sink=audio.getCommunicationDevice();
            if(route.state()==HeadsetRouteLease.State.SELECTED&&sink!=null&&sink.getId()==sinkId){
                AudioDeviceInfo input=null;
                // Source and sink IDs differ. Refuse ambiguous same-profile inputs.
                for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_INPUTS))
                    if(device.isSource()&&device.getType()==sink.getType()){
                        if(input!=null)throw new IllegalStateException("Ambiguous headset input");input=device;
                    }
                if(input!=null){
                    selected=new HeadsetAudio(sinkId,input.getId(),sink.getType());starting=false;
                    Runnable done=ready;ready=null;if(done!=null)done.run();return;
                }
            }
            if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("Headset route timeout");
            main.postDelayed(this::poll,50);
        }catch(Exception error){lost();}
    }
    private void lost(){
        if(closing)return;closing=true;selected=null;starting=false;ready=null;
        HandsFreeController.headsetLost(this);
        try{if(route!=null)route.close();}catch(RuntimeException ignored){}
        stopSelf();
    }
    @Override public void onDestroy(){
        if(instance==this)instance=null;
        closing=true;selected=null;starting=false;ready=null;main.removeCallbacksAndMessages(null);
        try{if(route!=null)route.close();}catch(RuntimeException ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}

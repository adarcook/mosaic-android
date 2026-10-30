package life.mosaic.tensorllm;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.voice.VoiceInteractionService;
import java.util.UUID;

/** Main-thread controller. No stored audio/text; only a bounded status journal. */
final class HandsFreeController {
    static final String CHANGED="life.mosaic.tensorllm.HANDSFREE_CHANGED";
    static final String COMPANION="life.mosaic.zipformergate";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static ServiceConnection connection;
    private static IBinder worker;
    private static String token="",launchToken="",session="";
    private static WakeHandoff handoff;
    private static boolean bound, enabled, standbyRequested;
    private static boolean turnRunning;
    private static int recoveryAttempts;
    private static String timingId="";
    enum TimingPoint { WAKE_DETECTED, WAKE_CALLBACK_RECEIVED, TURN_DISPATCHED, READY_REQUESTED, READY_PLAYBACK_HEAD, READY_COMPLETED, CAPTURE_RECORDING_STARTED }
    static String timingId(){return timingId;}
    static void mark(Context c,String id,TimingPoint point,long at){
        if(id.isEmpty())return;
        try{append(c,new org.json.JSONObject().put("type","interaction_timing").put("wake_id",id)
                .put("point",point.name()).put("elapsed_realtime_ms",at));}catch(Exception ignored){}
    }

    static boolean selected(Context c){return VoiceInteractionService.isActiveService(c,new ComponentName(c,MosaicAssistantService.class));}
    static boolean enabled(){return enabled;}
    static boolean requested(){return standbyRequested;}
    static void turnRunning(boolean value){turnRunning=value;}
    static boolean configured(Context c){String value=android.provider.Settings.Secure.getString(c.getContentResolver(),"voice_interaction_service");return new ComponentName(c,MosaicAssistantService.class).equals(ComponentName.unflattenFromString(value==null?"":value));}
    static boolean persistent(Context c){return ListeningIntent.open(c).enabled();}
    static boolean optIn(Context c){recoveryAttempts=0;return ListeningIntent.open(c).set(true);}
    static void disable(Context c,String reason){ListeningIntent.open(c).set(false);stop(c,reason);}
    static void restore(Context context){
        Context c=context.getApplicationContext();
        android.os.UserManager user=c.getSystemService(android.os.UserManager.class);
        if(user==null||!user.isUserUnlocked())return;
        if(!persistent(c)||enabled||turnRunning||TurnForegroundService.expected()||!selected(c)||!MosaicAssistantService.ready())return;
        if(c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED
                ||(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)){
            status(c,"האזנה קבועה מושהית · נדרשות הרשאות מיקרופון והתראה");return;
        }
        if(!FeedbackClips.valid(c.getFilesDir())){status(c,"האזנה קבועה מושהית · יש להכין משוב קולי פעם אחת");return;}
        if(session.isEmpty())verifiedSession(UUID.randomUUID().toString());
        arm(c);
    }
    static void recover(Context context){Context c=context.getApplicationContext();if(!persistent(c))return;if(recoveryAttempts++>=3){status(c,"האזנה קבועה מושהית אחרי תקלה חוזרת · בדוק הרשאות וחיבור עוזר Android");return;}main.postDelayed(()->restore(c),3000);}
    static String session(){return session;}
    static void verifiedSession(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid standby session");session=value;}
    private static void append(Context c,org.json.JSONObject event)throws Exception{
        java.io.File file=new java.io.File(c.getFilesDir(),"handsfree-status.jsonl");
        if(file.length()>128*1024){java.io.File archived=new java.io.File(c.getFilesDir(),"handsfree-status-"+System.currentTimeMillis()+".jsonl");if(!file.renameTo(archived))throw new java.io.IOException("Cannot rotate status journal");}
        event.put("at_ms",System.currentTimeMillis());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file,true)){out.write((event+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    static String status(Context c){return c.getSharedPreferences("handsfree",0).getString("status","האזנה כבויה · צריך להפעיל פעם אחת לפני נעילה");}
    static void status(Context c,String text){
        c.getSharedPreferences("handsfree",0).edit().putString("status",text).apply();
        try {
            append(c,new org.json.JSONObject().put("status",text));
        }catch(Exception ignored){}
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    static void arm(Context context){
        Context c=context.getApplicationContext();
        if(enabled)return;
        if(!selected(c)){status(c,"יש לבחור את מוזאיק כעוזר ברירת המחדל");return;}
        if(!MosaicAssistantService.ready()){status(c,"האזנה קבועה מושהית · ממתין לחיבור עוזר Android");return;}
        if(session.isEmpty()||!FeedbackClips.valid(c.getFilesDir())){stop(c,"יש להכין משוב קולי והאזנה מתוך מוזאיק");return;}
        if(c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){status(c,"נדרשת הרשאת מיקרופון");return;}
        try{
            if(c.getPackageManager().checkSignatures(c.getPackageName(),COMPANION)!=PackageManager.SIGNATURE_MATCH
                    ||c.getPackageManager().getPackageInfo(COMPANION,0).getLongVersionCode()<9)throw new IllegalStateException();
            token=UUID.randomUUID().toString();final String current=token;
            enabled=true;standbyRequested=true;handoff=new WakeHandoff();worker=null;
            PendingIntent callback=PendingIntent.getBroadcast(c,87,new Intent(c,WakeEventReceiver.class).setAction(current),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            Intent service=service(c).putExtra("callback",callback);
            connection=new ServiceConnection(){
                public void onServiceConnected(ComponentName name,IBinder binder){
                    if(!enabled||!current.equals(token))return;
                    worker=binder;
                    try{binder.linkToDeath(()->main.post(()->died(c,current)),0);}
                    catch(RemoteException error){died(c,current);}
                }
                public void onServiceDisconnected(ComponentName name){died(c,current);}
                public void onNullBinding(ComponentName name){stop(c,"שירות ההאזנה אינו זמין");}
            };
            bound=c.bindService(service,connection,Context.BIND_AUTO_CREATE|Context.BIND_INCLUDE_CAPABILITIES);
            if(!bound)throw new IllegalStateException();
            status(c,"מכין זיהוי מילת עוררות מקומי · Zipformer על CPU");
            try{c.startForegroundService(service);}catch(Exception error){stop(c,"Android חסם התחלת האזנה. הפעל אותה שוב מתוך מוזאיק.");return;}
            main.postDelayed(()->{if(current.equals(token)&&enabled&&status(c).startsWith("מכין"))stop(c,"ההאזנה לא התחילה בזמן");},30000);
        }catch(Exception error){stop(c,"נדרש עדכון חתום של Zipformer והרשאת מיקרופון בחבילת התמלול");}
    }

    static void receive(Context context,Intent intent){
        Context c=context.getApplicationContext();
        if(!enabled||!token.equals(intent.getAction())||!selected(c))return;
        if(intent.getBooleanExtra("owner_stop",false)){disable(c,"ההאזנה הקבועה הופסקה דרך ההתראה");return;}
        String state=intent.getStringExtra("state");
        if("health".equals(state)){
            try{org.json.JSONObject event=new org.json.JSONObject().put("type","wake_health").put("interactive",intent.getBooleanExtra("interactive",false));
                for(String key:new String[]{"wall_ms","audio_ms","decode_ms","nonzero_blocks","max_rms"}){long value=intent.getLongExtra(key,-1);if(value<0||value>600000)return;event.put(key,value);}append(c,event);if(intent.getLongExtra("audio_ms",0)>=4000)recoveryAttempts=0;
            }catch(Exception ignored){}return;
        }
        if("listening".equals(state)){status(c,"ממתין ל־מוזאיק · האזנה מקומית פעילה");TurnForegroundService.stop(c);return;}
        if(!"wake".equals(state)&&!"error".equals(state)&&!"stopped".equals(state))return;
        handoff.result(state);
        if("wake".equals(state)){
            timingId=UUID.randomUUID().toString();
            long detected=intent.getLongExtra("detected_elapsed_ms",-1),now=SystemClock.elapsedRealtime();
            if(detected>=0&&detected<=now&&now-detected<=30000)mark(c,timingId,TimingPoint.WAKE_DETECTED,detected);
            mark(c,timingId,TimingPoint.WAKE_CALLBACK_RECEIVED,SystemClock.elapsedRealtime());
            status(c,"זוהתה מילת העוררות · ממתין לשחרור מנוע");
            dispatch(c);
            final String current=token;
            main.postDelayed(()->{if(enabled&&current.equals(token)&&worker!=null)stop(c,"מנוע ההמתנה לא השתחרר; הפנייה לא התחילה");},5000);
        }else{
            String reason="error".equals(state)?"ההאזנה הושהתה בתקלה או שהמיקרופון אינו זמין":"ההאזנה הופסקה";
            if(intent.getBooleanExtra("owner_stop",false))disable(c,reason);
            else {stop(c,reason);recover(c);}
        }
    }
    private static void died(Context c,String current){
        if(!current.equals(token)||handoff==null)return;
        worker=null;handoff.died();unbind(c);dispatch(c);
        main.postDelayed(()->{if(enabled&&current.equals(token)&&worker==null)stop(c,"תהליך ההאזנה נסגר; יש להפעיל שוב במוזאיק");},2000);
    }
    private static void dispatch(Context c){
        if(handoff!=null&&handoff.takeReady()){
            mark(c,timingId,TimingPoint.TURN_DISPATCHED,SystemClock.elapsedRealtime());
            enabled=false;token="";unbind(c);
            status(c,"מפעיל שיחה מעל מסך הנעילה");
            if(!MosaicAssistantService.wake()){
                status(c,"חיבור עוזר Android אינו זמין · ההאזנה תתחדש כשהעוזר יתחבר");recover(c);
            }
        }
    }
    static void stop(Context context,String reason){
        Context c=context.getApplicationContext();enabled=false;standbyRequested=false;token="";launchToken="";session="";
        TurnForegroundService.stop(c);
        try{c.stopService(service(c));}catch(Exception ignored){}
        unbind(c);worker=null;handoff=null;status(c,reason);
    }
    private static Intent service(Context c){return new Intent().setComponent(new ComponentName(COMPANION,COMPANION+".WakeListenService"));}
    private static void unbind(Context c){if(bound&&connection!=null){bound=false;try{c.unbindService(connection);}catch(Exception ignored){}}connection=null;}
    static Intent turnIntent(Context c){launchToken=UUID.randomUUID().toString();return new Intent(c,VoiceActivity.class).putExtra("handsfree_token",launchToken);}
    static boolean consume(Intent intent){String value=intent.getStringExtra("handsfree_token");if(value==null||!value.equals(launchToken)||launchToken.isEmpty())return false;launchToken="";return true;}
}

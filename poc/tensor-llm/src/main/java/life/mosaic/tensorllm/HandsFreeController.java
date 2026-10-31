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
    private static final RecoveryBudget recovery=new RecoveryBudget(3);
    private static String timingId="";
    private static VoiceFeedback wakeAck;
    static Runnable stopHeadsetTurn;
    static void headsetLost(Context c){
        if(wakeAck!=null)wakeAck.cancel();
        if(stopHeadsetTurn!=null)stopHeadsetTurn.run();
        stop(c,"האזנה דרך האוזניות מושהית · חבר מחדש את האוזניות או הפעל שוב");
    }
    static void communicationModeChanged(Context c,int mode){
        if(HeadsetResumeGate.allowed(mode,persistent(c),HeadsetRouteService.required(c),enabled,turnRunning))recover(c);
    }
    static void headsetConnected(Context c){if(HeadsetRouteService.required(c)&&persistent(c)){recovery.reset();recover(c);}}

    enum TimingPoint { WAKE_DETECTED, ACK_REQUESTED, ACK_PLAYBACK_HEAD, ACK_COMPLETED, WAKE_CALLBACK_RECEIVED, TURN_DISPATCHED, READY_REQUESTED, READY_PLAYBACK_HEAD, READY_COMPLETED, CAPTURE_RECORDING_STARTED }
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
    private static boolean dataReady(Context c){android.os.UserManager u=c.getSystemService(android.os.UserManager.class);return u!=null&&u.isUserUnlocked();}
    static boolean persistent(Context c){return dataReady(c)&&ListeningIntent.open(c).enabled();}
    static boolean optIn(Context c){recovery.reset();return ListeningIntent.open(c).set(true);}
    static void disable(Context c,String reason){recovery.cancel();if(dataReady(c))ListeningIntent.open(c).set(false);if(stopHeadsetTurn!=null&&HeadsetRouteService.required(c))stopHeadsetTurn.run();stop(c,reason);}
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
    static void recover(Context context){
        Context c=context.getApplicationContext();if(!persistent(c))return;
        long ticket=recovery.request();
        if(ticket==RecoveryBudget.EXHAUSTED){status(c,"האזנה קבועה מושהית אחרי תקלה חוזרת · בדוק הרשאות וחיבור עוזר Android");return;}
        if(ticket==RecoveryBudget.ALREADY_PENDING)return;
        main.postDelayed(()->{if(recovery.consume(ticket)&&persistent(c))restore(c);},3000);
    }
    static void interrupted(Context c,String reason){stop(c,reason);recover(c);}
    static String session(){return session;}
    static void verifiedSession(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid standby session");session=value;}
    private static void append(Context c,org.json.JSONObject event)throws Exception{
        java.io.File file=new java.io.File(c.getFilesDir(),"handsfree-status.jsonl");
        if(file.length()>128*1024){java.io.File archived=new java.io.File(c.getFilesDir(),"handsfree-status-"+System.currentTimeMillis()+".jsonl");if(!file.renameTo(archived))throw new java.io.IOException("Cannot rotate status journal");}
        event.put("at_ms",System.currentTimeMillis());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file,true)){out.write((event+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    static String status(Context c){return dataReady(c)?c.getSharedPreferences("handsfree",0).getString("status","האזנה קבועה כבויה · נדרשת הפעלה ראשונית"):"ממתין לפתיחת המכשיר הראשונה";}
    static void status(Context c,String text){
        if(!dataReady(c))return;
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
        if(HeadsetRouteService.required(c)&&HeadsetRouteService.current()==null){
            status(c,"מכין ניתוב למיקרופון האוזניות");HeadsetRouteService.ensure(c,()->arm(c));return;
        }
        recovery.cancel();
        try{
            if(c.getPackageManager().checkSignatures(c.getPackageName(),COMPANION)!=PackageManager.SIGNATURE_MATCH
                    ||c.getPackageManager().getPackageInfo(COMPANION,0).getLongVersionCode()<(HeadsetRouteService.required(c)?10:9))throw new IllegalStateException();
            token=UUID.randomUUID().toString();final String current=token;
            enabled=true;standbyRequested=true;handoff=new WakeHandoff();worker=null;
            PendingIntent callback=PendingIntent.getBroadcast(c,87,new Intent(c,WakeEventReceiver.class).setAction(current),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            Intent service=service(c).putExtra("callback",callback);
            life.mosaic.audio.HeadsetAudio headset=HeadsetRouteService.current();
            if(HeadsetRouteService.required(c)){if(headset==null)throw new IllegalStateException();Bundle route=new Bundle();headset.put(route);service.putExtras(route);}
            connection=new ServiceConnection(){
                public void onServiceConnected(ComponentName name,IBinder binder){
                    if(!enabled||!current.equals(token))return;
                    worker=binder;
                    try{binder.linkToDeath(()->main.post(()->died(c,current)),0);}
                    catch(RemoteException error){died(c,current);}
                }
                public void onServiceDisconnected(ComponentName name){died(c,current);}
                public void onBindingDied(ComponentName name){died(c,current);}
                public void onNullBinding(ComponentName name){if(enabled&&current.equals(token))interrupted(c,"שירות ההאזנה אינו זמין · מנסה להתחבר מחדש");}
            };
            bound=c.bindService(service,connection,Context.BIND_AUTO_CREATE|Context.BIND_INCLUDE_CAPABILITIES);
            if(!bound)throw new IllegalStateException();
            status(c,"מכין זיהוי מילת עוררות מקומי · Zipformer על CPU");
            try{c.startForegroundService(service);}catch(Exception error){interrupted(c,"Android חסם התחלת האזנה · מנסה לחדש ברקע");return;}
            main.postDelayed(()->{if(current.equals(token)&&enabled&&status(c).startsWith("מכין"))interrupted(c,"ההאזנה לא התחילה בזמן · מנסה לחדש ברקע");},30000);
        }catch(Exception error){interrupted(c,"נדרש עדכון חתום של Zipformer והרשאת מיקרופון בחבילת התמלול");}
    }

    static void receive(Context context,Intent intent){
        Context c=context.getApplicationContext();
        if(!enabled||!token.equals(intent.getAction())||!selected(c))return;
        if(intent.getBooleanExtra("owner_stop",false)){disable(c,"ההאזנה הקבועה הופסקה דרך ההתראה");return;}
        String state=intent.getStringExtra("state");
        if("detected".equals(state)){
            timingId=UUID.randomUUID().toString();
            long detected=intent.getLongExtra("detected_elapsed_ms",-1),now=SystemClock.elapsedRealtime();
            if(detected>=0&&detected<=now&&now-detected<=30000)mark(c,timingId,TimingPoint.WAKE_DETECTED,detected);
            if(wakeAck!=null)wakeAck.cancel();wakeAck=new VoiceFeedback(c);
            wakeAck.headset(HeadsetRouteService.current(),HeadsetRouteService.required(c));
            final String current=token,id=timingId;handoff.ackStarted();
            mark(c,id,TimingPoint.ACK_REQUESTED,SystemClock.elapsedRealtime());
            wakeAck.play("ack",at->mark(c,id,TimingPoint.ACK_PLAYBACK_HEAD,at),ok->{
                if(!enabled||!current.equals(token)||handoff==null)return;
                if(!ok&&HeadsetRouteService.required(c)){interrupted(c,"משוב האוזניות לא הושמע · ההאזנה מושהית");return;}
                if(ok)mark(c,id,TimingPoint.ACK_COMPLETED,SystemClock.elapsedRealtime());
                handoff.ackCompleted();dispatch(c);
            });return;
        }
        if(("health".equals(state)||"listening".equals(state))&&HeadsetRouteService.required(c)&&!intent.getBooleanExtra("headset_verified",false)){headsetLost(c);return;}
        if("health".equals(state)){
            try{org.json.JSONObject event=new org.json.JSONObject().put("type","wake_health").put("interactive",intent.getBooleanExtra("interactive",false));
                if(HeadsetRouteService.required(c))event.put("headset_verified",true).put("headset_route_type",intent.getIntExtra("headset_route_type",-1));
                for(String key:new String[]{"wall_ms","audio_ms","decode_ms","nonzero_blocks","max_rms"}){long value=intent.getLongExtra(key,-1);if(value<0||value>600000)return;event.put(key,value);}append(c,event);if(intent.getLongExtra("audio_ms",0)>=4000)recovery.reset();
            }catch(Exception ignored){}return;
        }
        if("listening".equals(state)){status(c,HeadsetRouteService.required(c)?"ממתין ל־מוזאיק באוזניות · המיקרופון אומת":"ממתין ל־מוזאיק · האזנה מקומית פעילה");TurnForegroundService.stop(c);return;}
        if(!"wake".equals(state)&&!"error".equals(state)&&!"stopped".equals(state))return;
        handoff.result(state);
        if("wake".equals(state)){
            if(timingId.isEmpty())timingId=UUID.randomUUID().toString();
            mark(c,timingId,TimingPoint.WAKE_CALLBACK_RECEIVED,SystemClock.elapsedRealtime());
            status(c,"זוהתה מילת העוררות · ממתין לשחרור מנוע");
            dispatch(c);
            final String current=token;
            main.postDelayed(()->{if(enabled&&current.equals(token)&&worker!=null)interrupted(c,"מנוע ההמתנה לא השתחרר; מחדש האזנה ברקע");},5000);
        }else{
            String reason="error".equals(state)?"ההאזנה הושהתה בתקלה או שהמיקרופון אינו זמין":"ההאזנה הופסקה";
            if(intent.getBooleanExtra("owner_stop",false))disable(c,reason);
            else interrupted(c,reason);
        }
    }
    private static void died(Context c,String current){
        if(!current.equals(token)||handoff==null)return;
        worker=null;handoff.died();unbind(c);dispatch(c);
        main.postDelayed(()->{if(enabled&&current.equals(token)&&worker==null)interrupted(c,"תהליך ההאזנה נסגר · מחדש האזנה ברקע");},2000);
    }
    private static void dispatch(Context c){
        if(handoff!=null&&handoff.takeReady()){
            mark(c,timingId,TimingPoint.TURN_DISPATCHED,SystemClock.elapsedRealtime());
            enabled=false;token="";unbind(c);
            status(c,"מפעיל שיחה מעל מסך הנעילה");
            if(!MosaicAssistantService.wake()){
                interrupted(c,"חיבור עוזר Android אינו זמין · ההאזנה תתחדש כשהעוזר יתחבר");
            }
        }
    }
    static void stop(Context context,String reason){
        Context c=context.getApplicationContext();enabled=false;standbyRequested=false;token="";launchToken="";session="";
        if(wakeAck!=null)wakeAck.cancel();
        HeadsetRouteService.stop(c);
        TurnForegroundService.stop(c);
        try{c.stopService(service(c));}catch(Exception ignored){}
        unbind(c);worker=null;handoff=null;status(c,reason);
    }
    private static Intent service(Context c){return new Intent().setComponent(new ComponentName(COMPANION,COMPANION+".WakeListenService"));}
    private static void unbind(Context c){if(bound&&connection!=null){bound=false;try{c.unbindService(connection);}catch(Exception ignored){}}connection=null;}
    static void stopAck(){if(wakeAck!=null)wakeAck.cancel();}
    static Intent turnIntent(Context c){launchToken=UUID.randomUUID().toString();return new Intent(c,VoiceActivity.class).putExtra("handsfree_token",launchToken);}
    static boolean consume(Intent intent){String value=intent.getStringExtra("handsfree_token");if(value==null||!value.equals(launchToken)||launchToken.isEmpty())return false;launchToken="";return true;}
}

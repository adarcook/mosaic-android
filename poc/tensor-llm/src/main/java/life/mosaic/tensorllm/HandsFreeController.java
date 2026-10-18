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

    static boolean selected(Context c){return VoiceInteractionService.isActiveService(c,new ComponentName(c,MosaicAssistantService.class));}
    static boolean enabled(){return enabled;}
    static boolean requested(){return standbyRequested;}
    static String session(){return session;}
    static void verifiedSession(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid standby session");session=value;}
    static String status(Context c){return c.getSharedPreferences("handsfree",0).getString("status","האזנה כבויה · צריך להפעיל פעם אחת לפני נעילה");}
    static void status(Context c,String text){
        c.getSharedPreferences("handsfree",0).edit().putString("status",text).apply();
        try {
            java.io.File file=new java.io.File(c.getFilesDir(),"handsfree-status.jsonl");
            if(file.length()>128*1024){java.io.File archived=new java.io.File(c.getFilesDir(),"handsfree-status-"+System.currentTimeMillis()+".jsonl");if(!file.renameTo(archived))throw new java.io.IOException("Cannot rotate status journal");}
            org.json.JSONObject event=new org.json.JSONObject().put("at_ms",System.currentTimeMillis()).put("status",text);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file,true)){out.write((event+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    static void arm(Context context){
        Context c=context.getApplicationContext();
        if(enabled)return;
        if(!selected(c)){status(c,"יש לבחור את מוזאיק כעוזר ברירת המחדל");return;}
        if(session.isEmpty()||!FeedbackClips.valid(c.getFilesDir())){stop(c,"יש להכין משוב קולי והאזנה מתוך מוזאיק");return;}
        if(c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){status(c,"נדרשת הרשאת מיקרופון");return;}
        try{
            if(c.getPackageManager().checkSignatures(c.getPackageName(),COMPANION)!=PackageManager.SIGNATURE_MATCH
                    ||c.getPackageManager().getPackageInfo(COMPANION,0).getLongVersionCode()<5)throw new IllegalStateException();
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
            status(c,"מכין האזנה מקומית · Zipformer על CPU");
            try{c.startForegroundService(service);}catch(Exception error){stop(c,"Android חסם התחלת האזנה. הפעל אותה שוב מתוך מוזאיק.");return;}
            main.postDelayed(()->{if(current.equals(token)&&enabled&&status(c).startsWith("מכין"))stop(c,"ההאזנה לא התחילה בזמן");},30000);
        }catch(Exception error){stop(c,"נדרש עדכון חתום של Zipformer והרשאת מיקרופון בחבילת התמלול");}
    }

    static void receive(Context context,Intent intent){
        Context c=context.getApplicationContext();
        if(!enabled||!token.equals(intent.getAction())||!selected(c))return;
        String state=intent.getStringExtra("state");
        if("listening".equals(state)){status(c,"ממתין ל־מוזאיק · האזנה מקומית פעילה");return;}
        if(!"wake".equals(state)&&!"error".equals(state)&&!"stopped".equals(state))return;
        handoff.result(state);
        if("wake".equals(state)){
            status(c,"זוהתה מילת העוררות · ממתין לשחרור מנוע");
            dispatch(c);
            final String current=token;
            main.postDelayed(()->{if(enabled&&current.equals(token)&&worker!=null)stop(c,"מנוע ההמתנה לא השתחרר; הפנייה לא התחילה");},5000);
        }else stop(c,"error".equals(state)?"ההאזנה נעצרה בתקלה או שהמיקרופון אינו זמין":"ההאזנה הופסקה");
    }
    private static void died(Context c,String current){
        if(!current.equals(token)||handoff==null)return;
        worker=null;handoff.died();unbind(c);dispatch(c);
        main.postDelayed(()->{if(enabled&&current.equals(token)&&worker==null)stop(c,"תהליך ההאזנה נסגר; יש להפעיל שוב במוזאיק");},2000);
    }
    private static void dispatch(Context c){
        if(handoff!=null&&handoff.takeReady()){
            enabled=false;token="";unbind(c);
            status(c,"מפעיל שיחה מעל מסך הנעילה");
            if(!MosaicAssistantService.wake())status(c,"עוזר המערכת אינו זמין; פתח את מוזאיק");
        }
    }
    static void stop(Context context,String reason){
        Context c=context.getApplicationContext();enabled=false;standbyRequested=false;token="";launchToken="";session="";
        try{c.stopService(service(c));}catch(Exception ignored){}
        unbind(c);worker=null;handoff=null;status(c,reason);
    }
    private static Intent service(Context c){return new Intent().setComponent(new ComponentName(COMPANION,COMPANION+".WakeListenService"));}
    private static void unbind(Context c){if(bound&&connection!=null){bound=false;try{c.unbindService(connection);}catch(Exception ignored){}}connection=null;}
    static Intent turnIntent(Context c){launchToken=UUID.randomUUID().toString();return new Intent(c,VoiceActivity.class).putExtra("handsfree_token",launchToken);}
    static boolean consume(Intent intent){String value=intent.getStringExtra("handsfree_token");if(value==null||!value.equals(launchToken)||launchToken.isEmpty())return false;launchToken="";return true;}
}

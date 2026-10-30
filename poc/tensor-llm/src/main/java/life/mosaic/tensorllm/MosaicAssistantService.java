package life.mosaic.tensorllm;

import android.service.voice.VoiceInteractionService;
import android.os.Bundle;

public final class MosaicAssistantService extends VoiceInteractionService {
    private static MosaicAssistantService instance;
    private boolean destroyed,systemReady;
    private final android.media.AudioDeviceCallback headsetConnections=new android.media.AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] devices){for(android.media.AudioDeviceInfo d:devices)if(life.mosaic.audio.HeadsetAudio.headset(d.getType())){HandsFreeController.headsetConnected(MosaicAssistantService.this);break;}}
    };
    private final android.content.BroadcastReceiver unlocked=new android.content.BroadcastReceiver(){public void onReceive(android.content.Context c,android.content.Intent i){HandsFreeController.restore(c);}};
    @Override public void onCreate(){super.onCreate();getSystemService(android.media.AudioManager.class).registerAudioDeviceCallback(headsetConnections,new android.os.Handler(getMainLooper()));android.content.IntentFilter filter=new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED);filter.addAction(android.content.Intent.ACTION_USER_PRESENT);registerReceiver(unlocked,filter,android.content.Context.RECEIVER_NOT_EXPORTED);}
    @Override public void onReady(){super.onReady();if(destroyed)return;systemReady=true;instance=this;setDisabledShowContext(3);android.os.UserManager user=getSystemService(android.os.UserManager.class);if(user!=null&&user.isUserUnlocked()){HandsFreeController.status(this,HandsFreeController.persistent(this)?"עוזר Android מחובר · מחדש האזנה קבועה":"מוזאיק נבחר כעוזר · אפשר להפעיל האזנה קבועה");HandsFreeController.restore(this);}}
    static boolean ready(){return instance!=null&&instance.systemReady&&!instance.destroyed;}
    static boolean wake(){if(!ready())return false;try{instance.showSession(new Bundle(),0);return true;}catch(Exception error){HandsFreeController.status(instance,"פתיחת עוזר Android נכשלה · "+error.getClass().getSimpleName());return false;}}
    @Override public void onLaunchVoiceAssistFromKeyguard(){wake();}
    @Override public void onShutdown(){systemReady=false;if(instance==this){instance=null;if(HandsFreeController.configured(this))HandsFreeController.stop(this,"חיבור העוזר הושהה · בחירת ההאזנה הקבועה נשמרה");else HandsFreeController.disable(this,"העוזר הוחלף · האזנת מוזאיק הופסקה");}super.onShutdown();}
    @Override public void onDestroy(){getSystemService(android.media.AudioManager.class).unregisterAudioDeviceCallback(headsetConnections);destroyed=true;systemReady=false;boolean owned=instance==this;if(owned)instance=null;try{unregisterReceiver(unlocked);}catch(Exception ignored){}if(owned)HandsFreeController.stop(this,"חיבור העוזר נסגר · בחירת ההאזנה הקבועה נשמרה");super.onDestroy();}
}

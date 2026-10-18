package life.mosaic.tensorllm;

import android.service.voice.VoiceInteractionService;
import android.os.Bundle;

public final class MosaicAssistantService extends VoiceInteractionService {
    private static MosaicAssistantService instance;
    @Override public void onReady(){super.onReady();instance=this;setDisabledShowContext(3);if(!HandsFreeController.enabled())HandsFreeController.status(this,"מוזאיק נבחר כעוזר · אפשר להפעיל האזנה");}
    static boolean wake(){if(instance==null)return false;try{instance.showSession(new Bundle(),0);return true;}catch(Exception error){return false;}}
    @Override public void onLaunchVoiceAssistFromKeyguard(){wake();}
    @Override public void onShutdown(){instance=null;HandsFreeController.stop(this,"העוזר הוחלף · האזנת מוזאיק הופסקה");super.onShutdown();}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
}

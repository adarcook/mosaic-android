package life.mosaic.tensorllm;

import android.os.Bundle;
import android.service.voice.*;

public final class MosaicSessionService extends VoiceInteractionSessionService {
    private static VoiceInteractionSession current;
    @Override public VoiceInteractionSession onNewSession(Bundle args){
        current=new VoiceInteractionSession(this){
            @Override public void onShow(Bundle args,int flags){super.onShow(args,flags);setUiEnabled(false);setDisabledShowContext(3);if(HandsFreeController.enabled()){finish();return;}try{startAssistantActivity(HandsFreeController.turnIntent(getContext()));}catch(Exception error){HandsFreeController.stop(getContext(),"Android לא פתח את מסך השיחה; יש לבדוק את בחירת העוזר");finish();}}
            @Override public void onDestroy(){if(current==this)current=null;super.onDestroy();}
        };
        return current;
    }
    static void close(){VoiceInteractionSession session=current;current=null;if(session!=null)try{session.finish();}catch(Exception ignored){}}
}

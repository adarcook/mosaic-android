package life.mosaic.tensorllm;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
/** Debug-only, owner-requested setup. Changes one setting; never grants permissions or claims acoustic tests. */
public final class HeadsetSetupInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        boolean saved=getTargetContext().getSharedPreferences("handsfree",0).edit().putBoolean("headset_mode",true).commit();
        result.putBoolean("headset_mode_saved",saved);
        result.putBoolean("listening_already_opted_in",ListeningIntent.open(getTargetContext()).enabled());
        result.putBoolean("microphone_tested",false);
        finish(saved?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}

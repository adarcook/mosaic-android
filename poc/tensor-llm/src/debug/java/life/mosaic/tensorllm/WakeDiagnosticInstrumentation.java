package life.mosaic.tensorllm;
import android.app.Activity;import android.app.Instrumentation;import android.os.Bundle;
/** Owner-coordinated, one-shot volatile microphone diagnostic; never enables listening consent. */
public final class WakeDiagnosticInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();boolean consent=ListeningIntent.open(getTargetContext()).enabled();
        boolean saved=consent&&getTargetContext().getSharedPreferences("handsfree",0).edit().putBoolean("wake_diagnostic_once",true).commit();
        out.putBoolean("one_shot_volatile_probe_requested",saved);finish(saved?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
}

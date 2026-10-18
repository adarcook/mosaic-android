package life.mosaic.tensorllm;

import android.content.*;

/** Non-exported, explicit mutable PendingIntent; only allowlisted status fields are consumed. */
public final class WakeEventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){HandsFreeController.receive(context,intent);}
}

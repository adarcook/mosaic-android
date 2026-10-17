package life.mosaic.tensorllm;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;

/**
 * OS-bound listener. Messages are read on demand from ACTIVE Android notifications only.
 * No event cache, message database, network traffic, or background audio is created.
 */
public final class WhatsAppNotificationListener extends NotificationListenerService {
    private static volatile WhatsAppNotificationListener connected;

    @Override public void onListenerConnected() { super.onListenerConnected(); connected=this; }
    @Override public void onListenerDisconnected() {
        if(connected==this)connected=null;
        super.onListenerDisconnected();
    }
    @Override public void onDestroy() { if(connected==this)connected=null;super.onDestroy(); }

    public static boolean hasAccess(Context context) {
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager!=null&&manager.isNotificationListenerAccessGranted(
                new ComponentName(context,WhatsAppNotificationListener.class));
    }

    /** Call only in response to an explicit foreground user request. */
    public static WhatsAppNotificationTool.Result readLatest(Context context) {
        if(!hasAccess(context))
            return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.PERMISSION_DENIED);
        KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        if(keyguard!=null&&keyguard.isDeviceLocked())
            return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.DEVICE_LOCKED);
        WhatsAppNotificationListener service=connected;
        if(service==null)
            return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.LISTENER_UNAVAILABLE);
        try {
            StatusBarNotification[] active=service.getActiveNotifications();
            if(active==null)
                return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.LISTENER_UNAVAILABLE);
            List<WhatsAppNotificationTool.Snapshot> candidates=new ArrayList<>();
            for(StatusBarNotification item:active){
                if(item==null||!"com.whatsapp".equals(item.getPackageName()))continue;
                Notification n=item.getNotification();
                if(n==null)continue;
                if(Notification.CATEGORY_CALL.equals(n.category)||Notification.CATEGORY_SERVICE.equals(n.category))continue;
                Bundle extras=n.extras;
                if(extras==null)continue;
                String title=asString(extras.getCharSequence(Notification.EXTRA_TITLE));
                String text=asString(extras.getCharSequence(Notification.EXTRA_TEXT));
                boolean hasMessages=extras.containsKey(Notification.EXTRA_MESSAGES);
                if(hasMessages){
                    @SuppressWarnings("deprecation")
                    Parcelable[] bundles=extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                    if(bundles!=null){
                        List<Notification.MessagingStyle.Message> messages=
                                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
                        long newest=Long.MIN_VALUE;
                        for(Notification.MessagingStyle.Message message:messages){
                            if(message==null||message.getText()==null)continue;
                            if(message.getTimestamp()>=newest){
                                text=message.getText().toString();newest=message.getTimestamp();
                            }
                        }
                    }
                }
                boolean isMessage=Notification.CATEGORY_MESSAGE.equals(n.category)
                        ||hasMessages||extras.containsKey(Notification.EXTRA_TEXT);
                candidates.add(new WhatsAppNotificationTool.Snapshot(item.getPackageName(),title,text,
                        item.getPostTime(),(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0,isMessage));
            }
            return WhatsAppNotificationTool.selectLatest(candidates);
        }catch(SecurityException|IllegalArgumentException|IllegalStateException failure){
            return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.READ_ERROR);
        }
    }
    private static String asString(CharSequence value){return value==null?"":value.toString();}
}

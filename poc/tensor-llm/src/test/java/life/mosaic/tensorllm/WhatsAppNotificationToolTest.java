package life.mosaic.tensorllm;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WhatsAppNotificationToolTest {
    private WhatsAppNotificationTool.Snapshot msg(String pkg,String text,long when,boolean summary){
        return new WhatsAppNotificationTool.Snapshot(pkg,"שיחה",text,when,summary,true);
    }
    @Test public void ignoresOtherAppsAndGroupSummaries() {
        var r=WhatsAppNotificationTool.selectLatest(List.of(
            msg("com.whatsapp","ישן",100,false),
            msg("com.whatsapp","סיכום קבוצה",300,true),
            msg("com.example","לא וואטסאפ",400,false),
            msg("com.whatsapp","חדש",200,false)));
        assertEquals(WhatsAppNotificationTool.Status.OK,r.status);
        assertEquals("חדש",r.text);assertEquals(200,r.postedAtMillis);
    }
    @Test public void newestHiddenDoesNotLeakOlderMessage(){
        var r=WhatsAppNotificationTool.selectLatest(List.of(
            msg("com.whatsapp","הודעה ישנה",100,false),
            msg("com.whatsapp",null,200,false)));
        assertEquals(WhatsAppNotificationTool.Status.CONTENT_UNAVAILABLE,r.status);
        assertFalse(WhatsAppNotificationTool.spoken(r).contains("הודעה ישנה"));
    }
    @Test public void rejectsNonMessageAndEmptyInput(){
        assertEquals(WhatsAppNotificationTool.Status.NO_MESSAGE,
            WhatsAppNotificationTool.selectLatest(List.of(
                new WhatsAppNotificationTool.Snapshot("com.whatsapp","Incoming call","Call",500,false,false))).status);
        assertEquals(WhatsAppNotificationTool.Status.NO_MESSAGE,WhatsAppNotificationTool.selectLatest(null).status);
    }
    @Test public void redactsControlCharactersAndBoundsText(){
        var r=WhatsAppNotificationTool.selectLatest(List.of(msg("com.whatsapp","שלום\nעולם\u200b",100,false)));
        assertEquals("שלום עולם",r.text);
        var longResult=WhatsAppNotificationTool.selectLatest(List.of(msg("com.whatsapp","א".repeat(1100),100,false)));
        assertTrue(longResult.text.length()<=851);
    }
    @Test public void permissionDenialHasNoFabricatedMessage(){
        var r=WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.PERMISSION_DENIED);
        assertFalse(r.found());assertNull(r.text);
        assertTrue(WhatsAppNotificationTool.spoken(r).contains("הרשאה"));
    }
}

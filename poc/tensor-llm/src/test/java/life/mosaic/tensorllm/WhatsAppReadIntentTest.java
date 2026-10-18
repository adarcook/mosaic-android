package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class WhatsAppReadIntentTest {
    @Test public void explicitHebrewRequests(){
        assertTrue(WhatsAppReadIntent.matches("מוזאיק, תקריא לי את ההודעה האחרונה בוואטסאפ"));
        assertTrue(WhatsAppReadIntent.matches("מה ההודעה האחרונה שקיבלתי בווטסאפ?"));
        assertTrue(WhatsAppReadIntent.matches("תקרא את ההודעות החדשות ב WhatsApp"));
    }
    @Test public void unrelatedRequestsDoNotInvokeTool(){
        assertFalse(WhatsAppReadIntent.matches("האם שלחת את ההודעה בוואטסאפ?"));
        assertFalse(WhatsAppReadIntent.matches("תקריא לי את ההודעה האחרונה במסרונים"));
        assertFalse(WhatsAppReadIntent.matches("מה דעתך על וואטסאפ"));
        assertFalse(WhatsAppReadIntent.matches(""));
    }
    @Test public void lastConversationRequestAndAsrVariantRouteToNotification(){
        assertTrue(WhatsAppReadIntent.matches("מוזאיק, תקרי לי את שיחת הווטסאפ האחרונה"));
        assertTrue(WhatsAppReadIntent.matches("תקריא לי את השיחה האחרונה בוואטסאפ"));
        assertTrue(WhatsAppReadIntent.matches("תקרא לי את הצ׳אט האחרון ב WhatsApp"));
    }
    @Test public void vocalizedAndSplitBrandRequestsAreRecognized(){
        assertTrue(WhatsAppReadIntent.matches("תקריא לי את הַהוֹדָעָה האחרונה בוואטסאפ"));
        assertTrue(WhatsAppReadIntent.matches("תקרי את שיחת הוואטס אפ האחרונה"));
        assertTrue(WhatsAppReadIntent.matches("תקרא הודעה אחרונה ב Whats App"));
    }
    @Test public void conversationAndCallDiscussionIsNotReadAuthorization(){
        assertFalse(WhatsAppReadIntent.matches("מי התקשר בשיחת הוואטסאפ האחרונה"));
        assertFalse(WhatsAppReadIntent.matches("איך להקריא את הודעת הוואטסאפ האחרונה"));
        assertFalse(WhatsAppReadIntent.matches("תקריא לי את כל היסטוריית השיחות בוואטסאפ"));
        assertFalse(WhatsAppReadIntent.matches("תקריא לי את כל שיחות הוואטסאפ האחרונות"));
        assertFalse(WhatsAppReadIntent.matches("תקריא לי את שיחת הטלפון האחרונה"));
    }
    @Test public void negationAndOtherActionsDoNotReadPrivateContent(){
        assertFalse(WhatsAppReadIntent.matches("אל תקריא את ההודעה האחרונה בוואטסאפ"));
        assertFalse(WhatsAppReadIntent.matches("לא לקרוא לי את שיחת הוואטסאפ האחרונה"));
        assertFalse(WhatsAppReadIntent.matches("שלח הודעה חדשה בוואטסאפ"));
        assertFalse(WhatsAppReadIntent.matches("תמחק את הודעת הוואטסאפ האחרונה"));
        assertFalse(WhatsAppReadIntent.matches(null));
    }
}

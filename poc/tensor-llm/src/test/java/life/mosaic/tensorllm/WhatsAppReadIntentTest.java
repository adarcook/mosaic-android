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
}

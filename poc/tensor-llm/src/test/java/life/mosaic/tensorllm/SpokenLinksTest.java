package life.mosaic.tensorllm;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpokenLinksTest {
    @Test public void trackingUrlIsNotReadButSurroundingTextAndPunctuationRemain(){
        assertEquals("לפרטים: קישור מצורף. נתראה מחר!",SpokenLinks.forSpeech("לפרטים: https://example.com/news?id=123&tracking=abcdef. נתראה מחר!"));
    }
    @Test public void handlesCommonUrlFormsAndUnicodeSchemeUrls(){
        for(String url:List.of("HTTP://example.org/a","www.example.org/news","example.co.il/path?q=1","wa.me/1234","https://אתר.ישראל/כתבה"))
            assertEquals(url,"קישור מצורף",SpokenLinks.forSpeech(url));
    }
    @Test public void adjacentLinksBecomeOnePluralPlaceholder(){
        assertEquals("קישורים מצורפים",SpokenLinks.forSpeech("https://example.com/one https://example.org/two"));
    }
    @Test public void numbersAndEmailAddressesAreNotLinks(){
        String text="גרסה 2.5 בשעה 10:30, מחיר 12.50. דואר user@sub.example.com";
        assertEquals(text,SpokenLinks.forSpeech(text));
    }
    @Test public void parenthesesAndHebrewPrefixRemainReadable(){
        assertEquals("פרטים בקישור מצורף (קישור מצורף).",SpokenLinks.forSpeech("פרטים בhttps://example.com (https://example.org)."));
    }
    @Test public void hebrewAfterCommaWithoutSpaceIsNotSwallowed(){
        assertEquals("קישור מצורף,נתראה מחר",SpokenLinks.forSpeech("https://example.com/news,נתראה מחר"));
    }
    @Test public void displayedNotificationRetainsUrlButSpokenNormalizationDoesNotSpellIt(){
        String url="https://example.com/private-fixture?id=123";
        var result=WhatsAppNotificationTool.selectLatest(List.of(new WhatsAppNotificationTool.Snapshot("com.whatsapp","בדיקה","לפרטים "+url,1,false,true)));
        assertTrue(WhatsAppNotificationTool.displayed(result).contains(url));
        assertTrue(result.text.contains(url));
        String speech=WhatsAppNotificationTool.spoken(result);
        assertTrue(speech.contains("קישור מצורף"));
        assertFalse(speech.contains("example"));
        assertFalse(speech.contains("123"));
        assertTrue(VoiceText.normalize(speech).contains("קישור מצורף"));
    }
    @Test public void unavailableResultIsUnchanged(){
        for(var state:WhatsAppNotificationTool.Status.values())if(state!=WhatsAppNotificationTool.Status.OK){
            var result=WhatsAppNotificationTool.unavailable(state);
            assertEquals(WhatsAppNotificationTool.displayed(result),WhatsAppNotificationTool.spoken(result));
        }
    }
    @Test public void manyShortLinksDoNotOverflowBoundedSpeechInput(){
        String text="a.io פרטים ".repeat(75);
        var result=WhatsAppNotificationTool.selectLatest(List.of(new WhatsAppNotificationTool.Snapshot("com.whatsapp","בדיקה",text,1,false,true)));
        String speech=WhatsAppNotificationTool.spoken(result);
        assertTrue(speech.length()<=1200);
        assertFalse(speech.contains("a.io"));
        assertTrue(speech.contains("פרטים"));
        assertFalse(VoiceText.normalize(speech).isBlank());
    }
}

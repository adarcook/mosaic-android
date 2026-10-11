package life.mosaic.tensorllm;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceTextTest {
    @Test public void normalizesDynamicNumbersLatinAndPreservesNegation(){String text=VoiceText.normalize("אל תשלח SMS. הפגישה ב-14:30, 5%.");assertTrue(text.startsWith("אל תשלח אס אם אס."));assertTrue(text.contains("אחת ארבע: שלוש אפס"));assertTrue(text.contains("חמש אחוזים"));}
    @Test public void chunksCoverAllNormalizedWordsInOrder(){String text=VoiceText.normalize("שלום לך. "+"הפגישה תתקיים מחר בבוקר ".repeat(30));var chunks=VoiceText.chunks(text);assertTrue(chunks.size()>1);assertEquals(text,String.join(" ",chunks));for(String chunk:chunks)assertTrue(chunk.length()<=96);}
    @Test public void removesNiqqudAndUnsupportedSymbols(){assertEquals("שלום לך",VoiceText.normalize("שָׁלוֹם ✅ לך"));}
    @Test public void rejectsOversizedReplyInsteadOfSilentTruncation(){assertThrows(IllegalArgumentException.class,()->VoiceText.normalize("א".repeat(1201)));}
}

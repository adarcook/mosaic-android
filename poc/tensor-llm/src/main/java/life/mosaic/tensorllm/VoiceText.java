package life.mosaic.tensorllm;

import java.text.Normalizer;
import java.util.*;

/** Explicit first-release normalization: digits and Latin letters are spelled in Hebrew. */
public final class VoiceText {
    private static final String[] DIGITS = {"אפס","אחת","שתיים","שלוש","ארבע","חמש","שש","שבע","שמונה","תשע"};
    private static final String[] LETTERS = {"איי","בי","סי","די","אי","אף","ג׳י","אייץ׳","איי","ג׳יי","קיי","אל","אם","אן","או","פי","קיו","אר","אס","טי","יו","וי","דאבליו","אקס","וואי","זד"};
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1200) throw new IllegalArgumentException("הקראה דורשת תשובה של עד 1,200 תווים");
        StringBuilder out = new StringBuilder();
        String text = Normalizer.normalize(raw, Normalizer.Form.NFD);
        text.codePoints().forEach(c -> {
            if (c >= '0' && c <= '9') out.append(' ').append(DIGITS[c-'0']).append(' ');
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) out.append(' ').append(LETTERS[Character.toUpperCase(c)-'A']).append(' ');
            else if ((c >= 0x591 && c <= 0x5c7) && c != 0x5be) { /* strip input niqqud */ }
            else if (c >= 0x5d0 && c <= 0x5ea || c == 0x5f3 || c == 0x5f4 || " .,!?:;'\"-\n".indexOf(c) >= 0) out.appendCodePoint(c);
            else if (c == '%') out.append(" אחוזים ");
            else if (c == '+') out.append(" ועוד ");
            else out.append(' ');
        });
        String result = out.toString().replace('׳', '\'').replace('״', '"').replaceAll("\\s+", " ").replaceAll(" +([.,!?:;])", "$1").trim();
        if (!result.matches(".*[א-ת].*")) throw new IllegalArgumentException("לא נשאר טקסט עברי להקראה");
        return result;
    }
    public static List<String> chunks(String normalized) {
        List<String> chunks = new ArrayList<>(); StringBuilder chunk = new StringBuilder();
        for (String word : normalized.split(" ")) {
            if (word.length() > 96) throw new IllegalArgumentException("מילה ארוכה מדי להקראה");
            if (chunk.length() + word.length() + 1 > 96) { chunks.add(chunk.toString().trim()); chunk.setLength(0); }
            chunk.append(word).append(' ');
            if (word.matches(".*[.!?]$")) { chunks.add(chunk.toString().trim()); chunk.setLength(0); }
        }
        if (!chunk.isEmpty()) chunks.add(chunk.toString().trim());
        return chunks;
    }
}

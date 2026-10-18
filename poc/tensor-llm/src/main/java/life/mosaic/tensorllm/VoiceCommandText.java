package life.mosaic.tensorllm;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared command spelling rules; no fuzzy or substring matching for personal phrases. */
final class VoiceCommandText {
    private VoiceCommandText() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFD).toLowerCase(Locale.ROOT)
                .replaceAll("[\\u0591-\\u05bd\\u05bf\\u05c1-\\u05c2\\u05c4-\\u05c5\\u05c7]", "").replaceAll("['׳’]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }

    static boolean blocked(String normalized) {
        return Pattern.compile("(?:^| )(?:אל|לא|בלי|ללא|איך|כיצד|למה|מדוע|שלח|תשלח|לשלוח|מחק|תמחק|למחוק|חייג|תחייג|התקשר|תתקשר|ענה|תענה|לענות)(?: |$)")
                .matcher(normalized).find();
    }
}

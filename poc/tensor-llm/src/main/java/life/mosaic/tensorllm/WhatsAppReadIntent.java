package life.mosaic.tensorllm;

import java.util.Locale;

/** Explicit, read-only command gate until the on-device agent has a typed tool planner. */
public final class WhatsAppReadIntent {
    private WhatsAppReadIntent(){}
    public static boolean matches(String recognized){
        if(recognized==null||recognized.isBlank())return false;
        String s=recognized.toLowerCase(Locale.ROOT).replaceAll("[\\u0591-\\u05c7]"," ")
             .replaceAll("[^\\p{L}\\p{N}]+"," ").replaceAll("\\s+"," ").trim();
        boolean whatsapp=s.contains("וואטסאפ")||s.contains("ווטסאפ")||s.contains("וואצאפ")||s.contains("whatsapp");
        boolean message=s.contains("הודע")||s.contains("מסרון");
        boolean read=s.contains("תקריא")||s.contains("תקרי")||s.contains("תקרא")||s.contains("הקרא")||s.contains("להקריא")||s.contains("לקרוא");
        boolean latest=s.contains("אחרונ")||s.contains("חדש")||s.contains("קיבלתי");
        return whatsapp&&message&&(read||latest);
    }
}

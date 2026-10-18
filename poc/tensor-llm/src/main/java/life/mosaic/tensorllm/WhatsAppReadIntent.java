package life.mosaic.tensorllm;

/** Explicit, read-only command gate until the on-device agent has a typed tool planner. */
public final class WhatsAppReadIntent {
    private WhatsAppReadIntent(){}
    public static boolean matches(String recognized){
        if(recognized==null||recognized.isBlank())return false;
        String s=VoiceCommandText.normalize(recognized);
        // A read-only tool still needs an affirmative request, not a how-to or another action.
        if(VoiceCommandText.blocked(s))return false;
        String compact=s.replace(" ","");
        boolean whatsapp=compact.contains("וואטסאפ")||compact.contains("ווטסאפ")||compact.contains("וואצאפ")||compact.contains("whatsapp");
        boolean message=s.contains("הודע")||s.contains("מסרון");
        boolean conversation=word(s,"ה?שיח(?:ה|ת|ות)|ה?צאט(?:ים)?");
        boolean read=s.contains("תקריא")||s.contains("תקרי")||s.contains("תקרא")||s.contains("הקרא")||s.contains("להקריא")||s.contains("לקרוא");
        boolean latest=s.contains("אחרונ")||s.contains("אחרון")||s.contains("חדש")||s.contains("קיבלתי");
        boolean question=word(s,"מה|יש|קיבלתי");
        // "Read my last WhatsApp conversation" is scoped to the latest active notification.
        // Conversation/call nouns alone never authorize reading message content.
        boolean fullHistory=conversation&&word(s,"כל|היסטוריה|ההיסטוריה|היסטוריית");
        return whatsapp&&!fullHistory&&((message&&(read||(latest&&question)))||(conversation&&read&&latest));
    }
    private static boolean word(String text,String alternatives){return java.util.regex.Pattern.compile("(?:^| )(?:"+alternatives+")(?: |$)").matcher(text).find();}
}

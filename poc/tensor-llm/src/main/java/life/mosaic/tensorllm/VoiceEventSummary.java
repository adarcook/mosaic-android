package life.mosaic.tensorllm;

import org.json.JSONObject;

/** Allowlisted presentation of journal metadata. Never renders message/audio/transcript fields. */
final class VoiceEventSummary {
    static String describe(JSONObject event) {
        String detail = switch (event.optString("type")) {
            case "start" -> "פנייה חדשה";
            case "phase" -> stage(event.optString("stage")) + " · התחיל";
            case "stage_end" -> stage(event.optString("stage")) +
                    ("complete".equals(event.optString("state")) ? " · הושלם" : " · נכשל");
            case "capture_end" -> "ההקלטה נשמרה במכשיר";
            case "feedback_start" -> "משוב קולי קבוע · התחיל";
            case "feedback_end" -> event.optBoolean("played",false)?"משוב קולי קבוע · הושמע":"משוב קולי קבוע · לא הושמע";
            case "worker_release_start" -> stage(event.optString("stage")) + " · משחרר מנוע";
            case "worker_release_end" -> stage(event.optString("stage")) + " · שחרור המנוע אושר";
            case "worker_release_error", "worker_stop_send_error" -> "תקלה בשחרור מנוע";
            case "tool_invocation" -> "כלי WhatsApp · " + toolStatus(event.optString("status"));
            case "intent_route" -> switch(event.optString("route")){
                case "whatsapp_latest_notification" -> ("personal_command".equals(event.optString("source"))?"ביטוי אישי זוהה":"הבקשה זוהתה")+" · התראת WhatsApp אחרונה";
                case "conversation" -> "הבקשה הועברה למודל השיחה";
                default -> "";
            };
            case "end" -> switch (event.optString("state")) {
                case "complete" -> "הפנייה הושלמה";
                case "cancelled" -> "הפנייה נעצרה";
                default -> "הפנייה הסתיימה בתקלה";
            };
            default -> "";
        };
        if (detail.isEmpty()) return "";
        long milliseconds = Math.max(0, event.optLong("since_start_ms", 0));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", milliseconds / 60000,
                (milliseconds / 1000) % 60) + "   " + detail;
    }

    private static String stage(String value) {
        return switch (value) {
            case "capture" -> "הקשבה";
            case "asr" -> "תמלול";
            case "llm" -> "תשובה";
            case "tts" -> "הקראה";
            default -> "שיחה";
        };
    }

    private static String toolStatus(String value) {
        return switch (value) {
            case "OK" -> "נמצאה התראה";
            case "PERMISSION_DENIED" -> "נדרשת הרשאה";
            case "DEVICE_LOCKED" -> "המכשיר נעול";
            case "NO_MESSAGE" -> "אין הודעה זמינה";
            case "CONTENT_UNAVAILABLE" -> "תוכן מוסתר או לא זמין";
            case "LISTENER_UNAVAILABLE" -> "החיבור להתראות אינו זמין";
            default -> "הכלי החזיר מצב לא זמין";
        };
    }
}

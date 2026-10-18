package life.mosaic.tensorllm;

import java.util.Map;
import org.json.JSONObject;

/** The same app-owned catalog is used for model schemas and execution validation. */
final class AgentTools {
    static final String WHATSAPP_FUNCTION = "read_latest_whatsapp_notification";
    static final String DESCRIPTION = "Read aloud the latest accessible WhatsApp notification, only when the user asks to read or hear a received message. "
            + "This is read-only, requires notification permission and an unlocked phone, and cannot access chat history, send, reply, delete, or make calls. "
            + "If the user's intent is unclear, ask a clarification instead. הקראת התראת וואטסאפ אחרונה לפי בקשת המשתמש.";

    static String schema() {
        try {
            return new JSONObject().put("name", WHATSAPP_FUNCTION).put("description", DESCRIPTION)
                    .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject())
                            .put("required", new org.json.JSONArray()).put("additionalProperties", false)).toString();
        } catch (Exception impossible) { throw new IllegalStateException("Tool schema unavailable"); }
    }

    static String validate(String name, Map<String, ?> arguments) {
        if (!WHATSAPP_FUNCTION.equals(name) || arguments == null || !arguments.isEmpty())
            throw new IllegalArgumentException("בקשת הכלי אינה תקינה או אינה נתמכת");
        return WhatsAppNotificationTool.ID;
    }

    /** An exact saved preference informs the model; it never invokes a capability. */
    static String personalHint(String request, PersonalCommands dictionary) {
        return WhatsAppNotificationTool.ID.equals(dictionary.toolFor(request)) ? WHATSAPP_FUNCTION : "";
    }

    static String system(String hint) {
        if (!hint.isEmpty() && !WHATSAPP_FUNCTION.equals(hint)) throw new IllegalArgumentException("Unsupported personal preference");
        return ModelSpec.VOICE_SYSTEM + (hint.isEmpty() ? "" :
                " משמעות בקשת המשתמש בפנייה הנוכחית, לפי הביטוי האישי שבעל הטלפון שמר: "
                + "המשתמש מבקש להקריא את התראת הוואטסאפ האחרונה. זו המשמעות של הבקשה הנוכחית, ולא בקשה לעדכון כללי. "
                + "הפעולה המתאימה למשמעות הזאת היא " + WHATSAPP_FUNCTION +
                ". פרש את הבקשה לפי המשמעות שנשמרה; בדיקות ההרשאה והנעילה עדיין נדרשות.");
    }
}

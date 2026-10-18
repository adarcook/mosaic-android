package life.mosaic.tensorllm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded local phrase mappings. The only supported target is the read-only notification tool. */
final class PersonalCommands {
    static final int LIMIT = 32, PHRASE_LIMIT = 96;
    private static final int JSON_LIMIT = 24000;
    final List<Entry> entries;

    static final class Entry {
        final String id, phrase, toolId;
        final boolean enabled;

        Entry(String id, String phrase, String toolId, boolean enabled) {
            this.id = id;
            this.phrase = phrase == null ? "" : phrase.trim();
            this.toolId = toolId;
            this.enabled = enabled;
        }
    }

    PersonalCommands(List<Entry> entries) {
        if (entries.size() > LIMIT) throw new IllegalArgumentException("אפשר לשמור עד 32 ביטויים");
        Set<String> ids = new HashSet<>(), phrases = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null || entry.id == null || !entry.id.matches("[a-zA-Z0-9-]{1,64}") || !ids.add(entry.id))
                throw new IllegalArgumentException("מזהה הביטוי אינו תקין");
            String normalized = VoiceCommandText.normalize(entry.phrase);
            if (entry.phrase.length() > PHRASE_LIMIT || normalized.length() < 2)
                throw new IllegalArgumentException("יש להזין ביטוי באורך 2 עד 96 תווים");
            if (VoiceCommandText.blocked(normalized))
                throw new IllegalArgumentException("בחר ביטוי ללא שלילה, שאלת הסבר או בקשת שליחה, מחיקה או שיחה");
            if (!phrases.add(normalized)) throw new IllegalArgumentException("הביטוי הזה כבר קיים במילון");
            if (!WhatsAppNotificationTool.ID.equals(entry.toolId))
                throw new IllegalArgumentException("הפעולה אינה נתמכת במילון");
        }
        this.entries = List.copyOf(entries);
    }

    static PersonalCommands empty() { return new PersonalCommands(List.of()); }

    String toolFor(String request) {
        String normalized = VoiceCommandText.normalize(request);
        if (normalized.isEmpty() || VoiceCommandText.blocked(normalized)) return null;
        for (Entry entry : entries)
            if (entry.enabled && normalized.equals(VoiceCommandText.normalize(entry.phrase))) return entry.toolId;
        return null;
    }

    PersonalCommands put(Entry replacement) {
        List<Entry> changed = new ArrayList<>(entries);
        for (int i = 0; i < changed.size(); i++) {
            if (changed.get(i).id.equals(replacement.id)) {
                changed.set(i, replacement);
                return new PersonalCommands(changed);
            }
        }
        changed.add(replacement);
        return new PersonalCommands(changed);
    }

    PersonalCommands remove(String id) {
        List<Entry> changed = new ArrayList<>(entries);
        changed.removeIf(entry -> entry.id.equals(id));
        return new PersonalCommands(changed);
    }

    String encode() throws JSONException {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(new JSONObject().put("id", entry.id).put("phrase", entry.phrase)
                .put("tool", entry.toolId).put("enabled", entry.enabled));
        return new JSONObject().put("version", 1).put("entries", array).toString();
    }

    static PersonalCommands decode(String raw) throws JSONException {
        if (raw == null) return empty(); // Only an absent key is a fresh dictionary.
        if (raw.length() > JSON_LIMIT) throw new IllegalArgumentException("קובץ המילון גדול מדי");
        JSONObject object = new JSONObject(raw);
        if (!(object.get("version") instanceof Integer) || object.getInt("version") != 1)
            throw new IllegalArgumentException("גרסת המילון אינה נתמכת");
        JSONArray array = object.getJSONArray("entries");
        if (array.length() > LIMIT) throw new IllegalArgumentException("המילון מכיל יותר מדי ביטויים");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            if (!(entry.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("מצב הביטוי אינו תקין");
            entries.add(new Entry(entry.getString("id"), entry.getString("phrase"), entry.getString("tool"), entry.getBoolean("enabled")));
        }
        return new PersonalCommands(entries);
    }
}

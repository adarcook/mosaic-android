package life.mosaic.tensorllm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalCommandsTest {
    private PersonalCommands.Entry entry(String id, String phrase, boolean enabled) {
        return new PersonalCommands.Entry(id, phrase, WhatsAppNotificationTool.ID, enabled);
    }

    @Test public void freshDictionaryHasNoAutomaticCommands() throws Exception {
        PersonalCommands commands = PersonalCommands.decode(null);
        assertTrue(commands.entries.isEmpty());
        assertNull(commands.toolFor("תן לי עדכון"));
    }

    @Test public void wholePhraseMatchesAcrossPunctuationNikudCaseAndWhitespace() {
        PersonalCommands commands = new PersonalCommands(List.of(entry("one", "תן לי עדכון", true), entry("two", "My update", true)));
        assertEquals(WhatsAppNotificationTool.ID, commands.toolFor("  תֵּן  לִי עִדְכּוּן! "));
        assertEquals(WhatsAppNotificationTool.ID, commands.toolFor("MY UPDATE?"));
        assertNull(commands.toolFor("בבקשה תן לי עדכון"));
        assertNull(commands.toolFor("תן לי עדכון אחר כך"));
        assertNull(commands.toolFor("אל תן לי עדכון"));
        assertNull(commands.toolFor(null));
    }

    @Test public void editsDisableEnableAndDeleteTakeEffectWithoutMutatingEarlierSnapshot() {
        PersonalCommands first = PersonalCommands.empty().put(entry("one", "תן לי עדכון", true));
        PersonalCommands edited = first.put(entry("one", "מה שלחו לי", true));
        assertNull(edited.toolFor("תן לי עדכון"));
        assertNotNull(first.toolFor("תן לי עדכון"));
        assertNotNull(edited.toolFor("מה שלחו לי?"));
        PersonalCommands disabled = edited.put(entry("one", "מה שלחו לי", false));
        assertNull(disabled.toolFor("מה שלחו לי"));
        assertNotNull(disabled.put(entry("one", "מה שלחו לי", true)).toolFor("מה שלחו לי"));
        assertTrue(disabled.remove("one").entries.isEmpty());
    }

    @Test public void normalizedDuplicatesAreRejectedEvenWhenDisabled() {
        assertThrows(IllegalArgumentException.class, () -> new PersonalCommands(List.of(
                entry("one", "תן לי עדכון", true), entry("two", "תֵּן לי עדכון!", false))));
    }

    @Test public void negationAndUnsupportedActionsCannotBeSavedAsReadAliases() {
        for (String phrase : new String[]{"אל תקריא", "לא לקרוא", "אל־תקרא הודעה", "איך לקרוא הודעה",
                "שלח הודעה", "תמחק הודעה", "התקשר בוואטסאפ"})
            assertThrows(IllegalArgumentException.class, () -> PersonalCommands.empty().put(entry("one", phrase, true)));
        assertFalse(WhatsAppReadIntent.matches("אל־תקריא לי את ההודעה האחרונה בוואטסאפ"));
    }

    @Test public void invalidPayloadAndUnknownToolAreRejected() {
        for (String phrase : new String[]{"", "...", "א", "א".repeat(97)})
            assertThrows(IllegalArgumentException.class, () -> PersonalCommands.empty().put(entry("one", phrase, true)));
        assertThrows(IllegalArgumentException.class, () -> PersonalCommands.empty().put(
                new PersonalCommands.Entry("one", "עדכון", "arbitrary.tool", true)));
        assertThrows(IllegalArgumentException.class, () -> PersonalCommands.empty().put(entry("bad id", "עדכון", true)));
    }

    @Test public void boundedRegistryCanStillEditAtCapacity() {
        PersonalCommands commands = PersonalCommands.empty();
        for (int i = 0; i < PersonalCommands.LIMIT; i++) commands = commands.put(entry("id" + i, "עדכון " + i, true));
        PersonalCommands full = commands;
        assertEquals(32, full.put(entry("id0", "ניסוח חדש", false)).entries.size());
        assertThrows(IllegalArgumentException.class, () -> full.put(entry("extra", "ביטוי נוסף", true)));
    }

    @Test public void codecRoundTripPreservesOrderPhrasesAndState() throws Exception {
        PersonalCommands original = new PersonalCommands(List.of(entry("one", "מה שלחו לי?", false), entry("two", "תן לי עדכון", true)));
        PersonalCommands restored = PersonalCommands.decode(original.encode());
        assertEquals("מה שלחו לי?", restored.entries.get(0).phrase);
        assertFalse(restored.entries.get(0).enabled);
        assertEquals("two", restored.entries.get(1).id);
        assertNotNull(restored.toolFor("תן לי עדכון"));
        assertNull(restored.toolFor("מה שלחו לי"));
    }

    @Test public void corruptAndUnsupportedSnapshotsAreNotTreatedAsEmpty() throws Exception {
        for (String raw : new String[]{"", "not json", "{}", "{\"version\":2,\"entries\":[]}",
                "{\"version\":\"1\",\"entries\":[]}", " ".repeat(24001)})
            assertThrows(Exception.class, () -> PersonalCommands.decode(raw));
        JSONObject invalid = new JSONObject(PersonalCommands.empty().put(entry("one", "תן לי עדכון", true)).encode());
        invalid.getJSONArray("entries").getJSONObject(0).put("enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> PersonalCommands.decode(invalid.toString()));
    }

    @Test public void duplicateIdsAndMutableInputAreHandled() {
        assertThrows(IllegalArgumentException.class, () -> new PersonalCommands(List.of(
                entry("one", "עדכון אחד", true), entry("one", "עדכון שני", false))));
        List<PersonalCommands.Entry> input = new ArrayList<>(List.of(entry("one", "עדכון", true)));
        PersonalCommands snapshot = new PersonalCommands(input);
        input.clear();
        assertEquals(1, snapshot.entries.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries.clear());
    }

    @Test public void savedSnapshotSurvivesStoreRecreationAndLaterEdits() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        PersonalCommandStore first = new PersonalCommandStore(backend);
        first.save(PersonalCommands.empty().put(entry("one", "תן לי עדכון", true)));
        PersonalCommandStore reopened = new PersonalCommandStore(backend);
        assertNotNull(reopened.load().toolFor("תן לי עדכון"));
        reopened.save(reopened.load().put(entry("one", "מה שלחו לי", false)));
        assertNull(first.load().toolFor("תן לי עדכון"));
        assertFalse(first.load().entries.get(0).enabled);
        reopened.save(reopened.load().remove("one"));
        assertTrue(first.load().entries.isEmpty());
    }

    @Test public void corruptLoadDoesNotWriteOrDisclosePayload() {
        MemoryBackend backend = new MemoryBackend(); backend.value = "PRIVATE_CORRUPT_PHRASE";
        IOException failure = assertThrows(IOException.class, () -> new PersonalCommandStore(backend).load());
        assertFalse(failure.toString().contains("PRIVATE"));
        assertEquals("PRIVATE_CORRUPT_PHRASE", backend.value);
        assertEquals(0, backend.writes);
    }

    @Test public void writeFailureIsReported() {
        MemoryBackend backend = new MemoryBackend(); backend.fail = true;
        assertThrows(IOException.class, () -> new PersonalCommandStore(backend).save(PersonalCommands.empty()));
    }

    private static final class MemoryBackend implements PersonalCommandStore.Backend {
        String value; boolean fail; int writes;
        public String read() { return value; }
        public boolean write(String next) { writes++; if (fail) return false; value = next; return true; }
    }
}

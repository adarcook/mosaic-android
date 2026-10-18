package life.mosaic.tensorllm;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptSourceTest {
    private static final String START = "{\"type\":\"start\",\"version\":3,\"mode\":\"archive\",\"window_s\":12,\"overlap_s\":1,\"pcm_format\":\"s16le_mono_16000\",\"beam_size\":5}\n";
    private static final String CAPTURE = "{\"type\":\"capture_end\",\"captured_samples\":200000}\n";
    private static final String FIRST = "{\"type\":\"segment\",\"index\":0,\"start_sample\":0,\"end_sample\":192000,\"rate\":16000,\"overlap_samples\":0,\"beam_size\":5,\"text\":\"דוגמה ראשונה\"}\n";
    private static final String TAIL = "{\"type\":\"segment\",\"index\":1,\"start_sample\":176000,\"end_sample\":200000,\"rate\":16000,\"overlap_samples\":16000,\"beam_size\":5,\"text\":\"דוגמה שנייה\"}\n";
    private static final String END = "{\"type\":\"end\",\"state\":\"complete\",\"captured_samples\":200000,\"processed_end_sample\":200000,\"unprocessed_samples\":0,\"error\":\"\"}\n";
    private TranscriptSource parse(String input) throws Exception { return TranscriptSource.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))); }
    @Test public void completeRecoveredPrefixHasStableSourceHashAndRawText() throws Exception {
        String input = START + CAPTURE + FIRST + "{\"type\":\"resume\",\"next_index\":1}\n" + TAIL + END;
        TranscriptSource source = parse(input);
        assertEquals("[מקטע 1] דוגמה ראשונה\n[מקטע 2] דוגמה שנייה\n", source.text);
        assertEquals(64, source.sha256.length());
        assertEquals(source.sha256, parse(input).sha256);
        assertNotEquals(source.sha256, parse(input.replace("ראשונה", "אחרת")).sha256);
    }
    @Test public void incompleteAndTornJournalRejected() throws Exception {
        reject(START + CAPTURE + FIRST); reject(START + CAPTURE + FIRST + TAIL + END.trim());
        reject(START + CAPTURE + FIRST + END);
    }
    @Test public void duplicateGapChangedBeamAndForgedCoverageRejected() throws Exception {
        reject(START + CAPTURE + FIRST + FIRST + TAIL + END);
        reject(START + CAPTURE + TAIL + END);
        reject(START + CAPTURE + FIRST + TAIL.replace("\"beam_size\":5", "\"beam_size\":2") + END);
        reject(START + CAPTURE + FIRST + TAIL + END.replace("\"processed_end_sample\":200000", "\"processed_end_sample\":199999"));
    }
    @Test public void resumeAfterCompletionNeedsNewTerminal() throws Exception {
        reject(START + CAPTURE + FIRST + TAIL + END + "{\"type\":\"resume\"}\n");
        parse(START + CAPTURE + FIRST + TAIL + END + "{\"type\":\"resume\"}\n" + END);
    }
    private void reject(String input) throws Exception {
        try { parse(input); fail("Invalid archive accepted"); } catch (java.io.IOException expected) { }
    }
}

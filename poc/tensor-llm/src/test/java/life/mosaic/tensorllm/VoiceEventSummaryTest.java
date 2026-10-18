package life.mosaic.tensorllm;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceEventSummaryTest {
    @Test public void personalRouteShowsSourceWithoutPhraseOrUnknownSource() throws Exception {
        JSONObject event = new JSONObject().put("type", "intent_route").put("route", "whatsapp_latest_notification")
                .put("source", "personal_command").put("phrase", "PRIVATE_PHRASE");
        String line = VoiceEventSummary.describe(event);
        assertTrue(line.contains("ביטוי אישי"));
        assertFalse(line.contains("PRIVATE"));
        event.put("source", "PRIVATE_SOURCE");
        assertFalse(VoiceEventSummary.describe(event).contains("PRIVATE"));
    }
    @Test public void privatePayloadNeverAppearsInTimeline() throws Exception {
        JSONObject event = new JSONObject().put("type", "tool_invocation").put("status", "OK")
                .put("since_start_ms", 12500).put("answer", "PRIVATE_MESSAGE")
                .put("normalized_text", "PRIVATE_MESSAGE").put("transcript", "PRIVATE_TRANSCRIPT")
                .put("tool", "PRIVATE_TOOL_VALUE");
        String line = VoiceEventSummary.describe(event);
        assertTrue(line.startsWith("00:12"));
        assertTrue(line.contains("WhatsApp"));
        assertFalse(line.contains("PRIVATE"));
    }

    @Test public void untrustedMetadataIsNotCopiedIntoPresentation() throws Exception {
        assertEquals("", VoiceEventSummary.describe(new JSONObject().put("type", "PRIVATE_EVENT")));
        assertFalse(VoiceEventSummary.describe(new JSONObject().put("type", "tool_invocation")
                .put("status", "PRIVATE_STATUS")).contains("PRIVATE"));
        assertFalse(VoiceEventSummary.describe(new JSONObject().put("type", "phase")
                .put("stage", "PRIVATE_STAGE")).contains("PRIVATE"));
        assertFalse(VoiceEventSummary.describe(new JSONObject().put("type", "feedback_start")
                .put("cue", "PRIVATE_PAYLOAD")).contains("PRIVATE"));
    }

    @Test public void failureAndCancellationRemainDistinguishable() throws Exception {
        String error = VoiceEventSummary.describe(new JSONObject().put("type", "end")
                .put("state", "error").put("message", "PRIVATE_FAILURE"));
        String cancelled = VoiceEventSummary.describe(new JSONObject().put("type", "end").put("state", "cancelled"));
        assertNotEquals(error, cancelled);
        assertFalse(error.contains("PRIVATE"));
        assertTrue(error.contains("תקלה"));
    }
    @Test public void routeSummaryUsesOnlyAllowlistedRoutes()throws Exception{
        String line=VoiceEventSummary.describe(new JSONObject().put("type","intent_route")
                .put("route","whatsapp_latest_notification").put("transcript","PRIVATE_REQUEST"));
        assertTrue(line.contains("WhatsApp"));
        assertFalse(line.contains("PRIVATE"));
        assertEquals("",VoiceEventSummary.describe(new JSONObject().put("type","intent_route").put("route","PRIVATE_ROUTE")));
        assertTrue(VoiceEventSummary.describe(new JSONObject().put("type","intent_route").put("route","conversation")).contains("מודל השיחה"));
    }
}

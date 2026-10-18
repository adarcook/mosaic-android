package life.mosaic.tensorllm;

import life.mosaic.models.VoiceAsrBackend;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceAsrBackendTest {
    @Test public void selectionPointsToIsolatedSignatureProtectedCompanions() {
        assertEquals("life.mosaic.tensorwhisper", VoiceAsrBackend.packageName(VoiceAsrBackend.WHISPER));
        assertEquals("life.mosaic.zipformergate", VoiceAsrBackend.packageName(VoiceAsrBackend.ZIPFORMER));
        assertEquals("life.mosaic.zipformergate.ZipformerVoiceService",
                VoiceAsrBackend.serviceName(VoiceAsrBackend.ZIPFORMER));
    }
    @Test public void rejectWrongEngineEvenIfTranscriptLooksValid() {
        assertFalse(VoiceAsrBackend.matchesResult(VoiceAsrBackend.ZIPFORMER, null, 5, 5));
        assertFalse(VoiceAsrBackend.matchesResult(VoiceAsrBackend.ZIPFORMER, VoiceAsrBackend.WHISPER, 5, 5));
        assertFalse(VoiceAsrBackend.matchesResult(VoiceAsrBackend.WHISPER, VoiceAsrBackend.ZIPFORMER, 5, 5));
        assertTrue(VoiceAsrBackend.matchesResult(VoiceAsrBackend.ZIPFORMER, VoiceAsrBackend.ZIPFORMER, 5, -1));
    }
    @Test public void preserveWhisperLegacyBeamProtection() {
        assertTrue(VoiceAsrBackend.matchesResult(VoiceAsrBackend.WHISPER, null, 5, 5));
        assertTrue(VoiceAsrBackend.matchesResult(VoiceAsrBackend.WHISPER, null, 2, 2));
        assertFalse(VoiceAsrBackend.matchesResult(VoiceAsrBackend.WHISPER, null, 2, 5));
        assertFalse(VoiceAsrBackend.matchesResult(VoiceAsrBackend.WHISPER, null, 5, 2));
    }
    @Test public void rejectUnknownBackend() {
        assertFalse(VoiceAsrBackend.matchesResult("other", "other", 5, 5));
        try { VoiceAsrBackend.packageName("other"); fail("must reject"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
}

package life.mosaic.tensorllm;

import life.mosaic.models.VoiceAsrBeam;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceAsrBeamTest {
    @Test public void baselineRemainsBeamFive() {
        assertEquals(5, VoiceAsrBeam.BASELINE);
        assertEquals(5, VoiceAsrBeam.requireSupported(VoiceAsrBeam.BASELINE));
    }

    @Test public void explicitBeamTwoExperimentIsAllowed() {
        assertEquals(2, VoiceAsrBeam.EXPERIMENT);
        assertEquals(2, VoiceAsrBeam.requireSupported(VoiceAsrBeam.EXPERIMENT));
    }

    @Test public void unsupportedVoiceProfilesFailClosed() {
        for (int beam : new int[]{0, 1, 3, 4, 6, -1}) {
            try {
                VoiceAsrBeam.requireSupported(beam);
                fail("Unexpected acceptance of beam " + beam);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(Integer.toString(beam)));
            }
        }
    }
}

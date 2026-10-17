package life.mosaic.models;

/** Supported spoken-turn Whisper decoding profiles; Beam 5 remains the baseline. */
public final class VoiceAsrBeam {
    public static final int BASELINE = 5;
    public static final int EXPERIMENT = 2;

    private VoiceAsrBeam() {}

    public static int requireSupported(int beam) {
        if (beam != BASELINE && beam != EXPERIMENT) {
            throw new IllegalArgumentException("Unsupported voice ASR beam size: " + beam);
        }
        return beam;
    }
}

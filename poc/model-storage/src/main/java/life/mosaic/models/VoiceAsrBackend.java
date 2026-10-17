package life.mosaic.models;

/** Pure-Java selection and result guard for the optional local Zipformer ASR companion. */
public final class VoiceAsrBackend {
    public static final String WHISPER = "whisper";
    public static final String ZIPFORMER = "zipformer";
    private VoiceAsrBackend() {}

    public static String packageName(String engine) {
        return switch(engine) {
            case WHISPER -> "life.mosaic.tensorwhisper";
            case ZIPFORMER -> "life.mosaic.zipformergate";
            default -> throw new IllegalArgumentException("Unsupported ASR engine: " + engine);
        };
    }

    public static String serviceName(String engine) {
        return switch(engine) {
            case WHISPER -> "life.mosaic.tensorwhisper.VoiceAsrService";
            case ZIPFORMER -> "life.mosaic.zipformergate.ZipformerVoiceService";
            default -> throw new IllegalArgumentException("Unsupported ASR engine: " + engine);
        };
    }

    public static boolean matchesResult(String requestedEngine, String actualEngine,
                                        int requestedBeam, int actualBeam) {
        if (ZIPFORMER.equals(requestedEngine)) return ZIPFORMER.equals(actualEngine);
        // Legacy Whisper workers predate the asr_engine result field.
        if (WHISPER.equals(requestedEngine))
            return (actualEngine == null || WHISPER.equals(actualEngine)) &&
                    requestedBeam == actualBeam;
        return false;
    }
}

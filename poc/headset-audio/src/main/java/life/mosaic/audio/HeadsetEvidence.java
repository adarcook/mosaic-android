package life.mosaic.audio;

/** Route IDs are transient and directional: communication sink identity cannot prove input. */
public final class HeadsetEvidence {
    private HeadsetEvidence() {}
    public static boolean output(int expectedSink,int expectedType,int selectedSink,int actualSink,int actualType) {
        return expectedSink>0 && selectedSink==expectedSink && actualSink==expectedSink && actualType==expectedType;
    }
    public static boolean input(int expectedInput,int expectedType,int actualInput,int actualType,boolean configurationAvailable,boolean silenced) {
        return expectedInput>0 && actualInput==expectedInput && actualType==expectedType && configurationAvailable && !silenced;
    }
}

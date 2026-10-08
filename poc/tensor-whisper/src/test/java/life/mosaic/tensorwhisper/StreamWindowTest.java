package life.mosaic.tensorwhisper;

public final class StreamWindowTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        StreamWindow window = new StreamWindow();
        StreamWindow.Chunk first = null, second = null;
        int samples = StreamWindow.RATE * 25 + 123;
        for (int i = 0; i < samples; i++) {
            StreamWindow.Chunk chunk = window.add((short)(i % 32767));
            if (chunk != null) { if (first == null) first = chunk; else second = chunk; }
        }
        StreamWindow.Chunk tail = window.finish();
        check(first != null && second != null && tail != null, "Two full windows and partial tail");
        check(first.startSample == 0 && first.endSample == 192000, "First window times");
        check(second.startSample == 176000 && second.endSample == 368000, "Second window times");
        check(tail.startSample == 352000 && tail.endSample == samples, "Tail ends at exact capture sample");
        check(first.newSamples() + second.newSamples() + tail.newSamples() == samples, "No lost or double-counted samples");
        for (int i = 0; i < StreamWindow.OVERLAP; i++) {
            check(first.pcm[StreamWindow.WINDOW - StreamWindow.OVERLAP + i] == second.pcm[i], "Exact overlap samples");
        }
        StreamWindow exact = new StreamWindow();
        for (int i = 0; i < StreamWindow.WINDOW; i++) exact.add((short)123);
        check(exact.finish() == null, "Do not emit overlap-only tail");
        StreamWindow small = new StreamWindow();
        for (int i = 0; i < 8000; i++) small.add((short)-32768);
        StreamWindow.Chunk shortChunk = small.finish();
        check(shortChunk.pcm.length == 8000 && shortChunk.pcm[0] == -1f, "Short final chunk and PCM normalization");
        check(StreamText.append("אחת שתיים שלוש", "שתיים שלוש ארבע").equals("אחת שתיים שלוש ארבע"), "Exact multiword overlap");
        check(StreamText.append("כן", "כן שוב").equals("כן כן שוב"), "Do not erase one-word natural repetition");
        check(StreamText.append("אחת שתיים", "שלוש ארבע").equals("אחת שתיים שלוש ארבע"), "Retain unmatched words");
        check(StreamText.append("אחת", "").equals("אחת"), "Empty chunk leaves text intact");
        System.out.println("Streaming window and text boundary tests passed");
    }
}

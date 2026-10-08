package life.mosaic.tensorwhisper;

import java.util.Arrays;

/** Bounded PCM windowing independent of Android, so boundary cases can be tested. */
final class StreamWindow {
    static final int RATE = 16000, WINDOW = RATE * 12, OVERLAP = RATE;
    private final float[] buffer = new float[WINDOW];
    private int used;
    private long start;
    private boolean emitted;

    static final class Chunk {
        final float[] pcm;
        final long startSample, endSample;
        final int overlapSamples;
        Chunk(float[] pcm, long start, int overlap) {
            this.pcm = pcm; startSample = start; endSample = start + pcm.length; overlapSamples = overlap;
        }
        long newSamples() { return pcm.length - overlapSamples; }
    }

    Chunk add(short value) {
        buffer[used++] = value / 32768.0f;
        if (used != WINDOW) return null;
        Chunk result = new Chunk(Arrays.copyOf(buffer, used), start, emitted ? OVERLAP : 0);
        System.arraycopy(buffer, WINDOW - OVERLAP, buffer, 0, OVERLAP);
        start += WINDOW - OVERLAP; used = OVERLAP; emitted = true;
        return result;
    }

    Chunk finish() {
        int overlap = emitted ? OVERLAP : 0;
        if (used <= overlap) return null;
        // A first recording shorter than 0.5 s is explicitly rejected by caller.
        Chunk result = new Chunk(Arrays.copyOf(buffer, used), start, overlap);
        used = 0;
        return result;
    }
}

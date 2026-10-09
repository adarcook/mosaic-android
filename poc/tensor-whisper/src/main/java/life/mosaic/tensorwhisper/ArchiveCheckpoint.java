package life.mosaic.tensorwhisper;

import java.io.IOException;

/** Only an exact contiguous committed prefix may be reused; never skip a failed window. */
final class ArchiveCheckpoint {
    private final ArchivePcm pcm;
    private final int beam;
    int nextIndex;
    long processedEnd, computeMs;

    ArchiveCheckpoint(ArchivePcm pcm, int beam) { this.pcm = pcm; this.beam = beam; }

    void accept(int index, long start, long end, int rate, int overlap, int recordBeam, long millis) throws IOException {
        StreamWindow.Chunk expected = pcm.chunk(nextIndex);
        if (expected == null || index != nextIndex || start != expected.startSample || end != expected.endSample
                || rate != StreamWindow.RATE || overlap != expected.overlapSamples || recordBeam != beam || millis < 0)
            throw new IOException("Non-contiguous or incompatible transcript checkpoint; audio retained");
        processedEnd = end; computeMs += millis; nextIndex++;
    }
}

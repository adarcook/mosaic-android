package life.mosaic.tensorwhisper;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.Arrays;

/** Private PCM16-LE, 16 kHz mono. One locked owner; inference reads one window at a time. */
final class ArchivePcm implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileLock lock;

    ArchivePcm(File path, boolean create) throws IOException {
        if (create && !path.createNewFile()) throw new IOException("Recording already exists");
        file = new RandomAccessFile(path, "rw");
        FileLock acquired;
        try {
            acquired = file.getChannel().tryLock();
            if (acquired == null) throw new IOException("Recording is already in use");
        } catch (Exception e) {
            file.close(); throw new IOException("Cannot lock recording", e);
        }
        lock = acquired;
        // A process may die during the final write. Keep complete PCM samples only.
        if (file.length() % 2 != 0) { file.setLength(file.length() - 1); file.getFD().sync(); }
    }

    void append(short[] samples, int count) throws IOException {
        if (count < 0 || count > samples.length) throw new IllegalArgumentException("PCM count");
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            bytes[i * 2] = (byte)samples[i]; bytes[i * 2 + 1] = (byte)(samples[i] >>> 8);
        }
        file.seek(file.length()); file.write(bytes); file.getFD().sync();
    }

    long samples() throws IOException { return file.length() / 2; }

    StreamWindow.Chunk chunk(int index) throws IOException {
        if (index < 0) throw new IllegalArgumentException("Chunk index");
        long start = index * (long)(StreamWindow.WINDOW - StreamWindow.OVERLAP);
        long total = samples();
        int overlap = index == 0 ? 0 : StreamWindow.OVERLAP;
        long length = Math.min(StreamWindow.WINDOW, total - start);
        if (length <= overlap) return null;
        byte[] bytes = new byte[(int)length * 2];
        file.seek(start * 2); file.readFully(bytes);
        float[] pcm = new float[(int)length];
        for (int i = 0; i < pcm.length; i++) {
            short value = (short)((bytes[i * 2] & 255) | ((bytes[i * 2 + 1] & 255) << 8));
            pcm[i] = value / 32768.0f;
        }
        return new StreamWindow.Chunk(pcm, start, overlap);
    }

    static float[] inferenceInput(StreamWindow.Chunk chunk) {
        // Never discard a sub-0.5 s first recording. Pad the model input only;
        // the journal retains true sample offsets and duration for coverage/RTF.
        return chunk.pcm.length < StreamWindow.RATE / 2
                ? Arrays.copyOf(chunk.pcm, StreamWindow.RATE / 2) : chunk.pcm;
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { file.close(); }
    }
}

package life.mosaic.tensorllm;

import java.io.IOException;

/** Bounded mono PCM transport; short chunks must not wait for a whole streaming buffer. */
final class PlaybackLoop {
    interface Output {
        int start(int thresholdFrames) throws IOException;
        int write(float[] audio, int offset, int count);
        long head();
    }
    interface Clock {
        long now();
        void pause() throws InterruptedException;
    }
    static void play(float[] audio, int rate, Output output, Clock clock) throws Exception {
        if (audio.length == 0 || rate <= 0) throw new IOException("Invalid playback input");
        // A short utterance can be smaller than AudioTrack's default capacity threshold.
        int threshold = output.start(Math.min(audio.length, Math.max(1, rate / 50)));
        if (threshold < 1 || threshold > audio.length)
            throw new IOException("Playback start threshold exceeds chunk: " + threshold + "/" + audio.length);
        long deadline = clock.now() + (long)Math.ceil(audio.length * 1000.0 / rate) + 5000;
        int offset = 0;
        while (offset < audio.length || output.head() < audio.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Playback cancelled");
            if (clock.now() >= deadline)
                throw new IOException("Playback timeout: written=" + offset + "/" + audio.length + ", head=" + output.head());
            if (offset < audio.length) {
                int count = Math.min(4096, audio.length - offset);
                int written = output.write(audio, offset, count);
                if (written < 0 || written > count) throw new IOException("Audio write failed: " + written);
                offset += written;
            }
            clock.pause();
        }
    }
}

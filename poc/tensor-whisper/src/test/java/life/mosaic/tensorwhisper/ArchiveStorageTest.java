package life.mosaic.tensorwhisper;

import java.io.*;
import java.nio.file.Files;
import java.util.List;

public final class ArchiveStorageTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("mosaic-archive-test").toFile();
        try {
            File path = new File(dir, "audio.pcm");
            long total = 25 * 16000 + 123;
            try (ArchivePcm pcm = new ArchivePcm(path, true)) {
                short[] block = new short[1600]; long count = 0;
                while (count < total) {
                    int n = (int)Math.min(block.length, total - count);
                    for (int i = 0; i < n; i++) block[i] = (short)((count + i) % 65536 - 32768);
                    pcm.append(block, n); count += n;
                }
                check(pcm.samples() == total, "Every recorded sample is retained");
                boolean locked = false;
                try (ArchivePcm other = new ArchivePcm(path, false)) { } catch (IOException e) { locked = true; }
                check(locked, "Second owner cannot open active recording");
            }
            try (ArchivePcm pcm = new ArchivePcm(path, false)) {
                long unique = 0; int index = 0; StreamWindow.Chunk chunk;
                while ((chunk = pcm.chunk(index++)) != null) {
                    unique += chunk.newSamples();
                    for (int i = 0; i < chunk.pcm.length; i++) {
                        short expected = (short)((chunk.startSample + i) % 65536 - 32768);
                        check(chunk.pcm[i] == expected / 32768f, "Exact PCM16-LE replay including negatives");
                    }
                }
                check(unique == total, "Replay window union covers full audio exactly once");
                check(pcm.chunk(2).endSample == total, "Resume at partial final window");
                check(pcm.chunk(2).startSample == 22 * 16000, "Resume uses same overlap offsets");
            }
            try (ArchivePcm pcm = new ArchivePcm(path, false)) {
                ArchiveCheckpoint checkpoint = new ArchiveCheckpoint(pcm, 5);
                checkpoint.accept(0, 0, 192000, 16000, 0, 5, 14000);
                check(checkpoint.nextIndex == 1 && checkpoint.processedEnd == 192000, "Failed next window resumes after committed first window");
                boolean rejected = false;
                try { checkpoint.accept(0, 0, 192000, 16000, 0, 5, 14000); } catch (IOException e) { rejected = true; }
                check(rejected && checkpoint.nextIndex == 1, "Duplicate cannot be counted twice");
                rejected = false;
                try { checkpoint.accept(2, 352000, total, 16000, 16000, 5, 1000); } catch (IOException e) { rejected = true; }
                check(rejected, "Cannot skip an uncommitted window");
                rejected = false;
                try { checkpoint.accept(1, 176000, 368000, 16000, 16000, 2, 1000); } catch (IOException e) { rejected = true; }
                check(rejected, "Cannot mix beam settings during recovery");
                checkpoint.accept(1, 176000, 368000, 16000, 16000, 5, 15000);
                checkpoint.accept(2, 352000, total, 16000, 16000, 5, 1000);
                check(checkpoint.processedEnd == total && checkpoint.computeMs == 30000, "Resume keeps exact coverage and cumulative compute");
            }
            // Simulate process interruption during one trailing sample.
            try (FileOutputStream out = new FileOutputStream(path, true)) { out.write(42); }
            try (ArchivePcm pcm = new ArchivePcm(path, false)) {
                check(pcm.samples() == total && path.length() == total * 2, "Only torn final byte discarded");
            }
            File tiny = new File(dir, "tiny.pcm");
            try (ArchivePcm pcm = new ArchivePcm(tiny, true)) {
                pcm.append(new short[]{-32768, 32767}, 2);
                StreamWindow.Chunk chunk = pcm.chunk(0);
                check(chunk.endSample == 2 && chunk.newSamples() == 2, "Tiny recording offsets remain truthful");
                float[] padded = ArchivePcm.inferenceInput(chunk);
                check(padded.length == 8000 && padded[0] == -1f && padded[2] == 0f, "Pad inference without losing real audio");
            }
            File exact = new File(dir, "exact.pcm");
            try (ArchivePcm pcm = new ArchivePcm(exact, true)) {
                pcm.append(new short[StreamWindow.WINDOW], StreamWindow.WINDOW);
                check(pcm.chunk(0) != null && pcm.chunk(1) == null, "Do not replay overlap-only tail");
            }
            File journal = new File(dir, "session.jsonl");
            try (ArchiveJournal out = new ArchiveJournal(journal)) {
                out.append("{\"type\":\"start\"}"); out.append("{\"type\":\"segment\",\"text\":\"שלום\"}");
            }
            try (FileOutputStream out = new FileOutputStream(journal, true)) { out.write("{\"type\":\"seg".getBytes("UTF-8")); }
            try (ArchiveJournal out = new ArchiveJournal(journal)) {
                List<String> lines = out.lines();
                check(lines.size() == 2 && lines.get(1).contains("שלום"), "Recover synced UTF-8 prefix only");
                out.append("{\"type\":\"end\",\"state\":\"incomplete\"}");
            }
            try (ArchiveJournal out = new ArchiveJournal(journal)) {
                check(out.lines().size() == 3, "Appending after recovery does not corrupt previous records");
            }
            System.out.println("Archive PCM durability, exact replay, locking, tails and journal recovery passed");
        } finally {
            for (File file : dir.listFiles()) file.delete(); dir.delete();
        }
    }
}

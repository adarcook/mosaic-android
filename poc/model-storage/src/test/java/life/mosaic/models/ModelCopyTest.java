package life.mosaic.models;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

public final class ModelCopyTest {
    private static final byte[] GOOD = "verified model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x", b & 255));
        return text.toString();
    }
    private static void expectFailure(File target, byte[] source, long size, String sha, boolean cancel) throws Exception {
        boolean failed = false;
        try { ModelCopy.install(new ByteArrayInputStream(source), target, size, sha, () -> cancel); }
        catch (IOException expected) { failed = true; }
        if (!failed || !java.util.Arrays.equals(GOOD, Files.readAllBytes(target.toPath())))
            throw new AssertionError("Failure changed committed model");
        if (new File(target.getParentFile(), target.getName() + ".import-part").exists())
            throw new AssertionError("Unverified partial remains");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mosaic-model-copy-"); File target = root.resolve("model.bin").toFile();
        try {
            String sha = hash(GOOD);
            ModelCopy.verify(new ByteArrayInputStream(GOOD), GOOD.length, sha, () -> false);
            try { ModelCopy.verify(new ByteArrayInputStream(new byte[GOOD.length]), GOOD.length, sha, () -> false);
                throw new AssertionError("Accepted damaged independent source"); } catch (IOException expected) { }

            ModelCopy.install(new ByteArrayInputStream(GOOD), target, GOOD.length, sha, () -> false);
            if (!ModelCopy.valid(target, GOOD.length, sha, () -> false)) throw new AssertionError("Not verified");
            expectFailure(target, "corrupt data!!".getBytes(), GOOD.length, sha, false);
            expectFailure(target, new byte[]{1}, GOOD.length, sha, false);
            expectFailure(target, new byte[GOOD.length + 1], GOOD.length, sha, false);
            expectFailure(target, GOOD, GOOD.length, sha, true);
            InputStream broken = new InputStream() { public int read() throws IOException { throw new IOException("Interrupted source"); } };
            try { ModelCopy.install(broken, target, GOOD.length, sha, () -> false); throw new AssertionError("Accepted broken source"); }
            catch (IOException expected) { }
            if (!ModelCopy.valid(target, GOOD.length, sha, () -> false)) throw new AssertionError("Committed model lost");
            ModelCopy.install(new ByteArrayInputStream(GOOD), target, GOOD.length, sha, () -> false);
            if (!ModelCopy.valid(target, GOOD.length, sha, () -> false)) throw new AssertionError("Retry failed");
            System.out.println("PASS: verified install, size/hash rejection, cancellation, I/O failure and retry preserve committed models");
        } finally { target.delete(); Files.deleteIfExists(root); }
    }
}

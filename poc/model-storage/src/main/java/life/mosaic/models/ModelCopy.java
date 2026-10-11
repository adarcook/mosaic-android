package life.mosaic.models;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Verified, bounded, atomic cache install. Never changes the shared source. */
public final class ModelCopy {
    private ModelCopy() { }
    public static String sha256(File file, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new IOException("הייבוא נעצר");
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }
    public static boolean valid(File file, long size, String hash, BooleanSupplier cancelled) throws Exception {
        return file.isFile() && file.length() == size && hash.equals(sha256(file, cancelled));
    }
    public static void verify(InputStream input, long size, String hash, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0;
        byte[] buffer = new byte[1024 * 1024]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (cancelled.getAsBoolean()) throw new IOException("הייבוא נעצר");
            total += n;
            if (total > size) throw new IOException("קובץ המקור גדול מהצפוי");
            digest.update(buffer, 0, n);
        }
        if (total != size || !hash.equals(hex(digest.digest())))
            throw new IOException("גודל או SHA-256 של המקור אינם תואמים");
    }
    public static void install(InputStream input, File target, long size, String hash,
                               BooleanSupplier cancelled) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".import-part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long copied = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new IOException("הייבוא נעצר");
                    copied += n;
                    if (copied > size) throw new IOException("קובץ המודל גדול מהצפוי");
                    digest.update(buffer, 0, n); output.write(buffer, 0, n);
                }
                if (copied != size || !hash.equals(hex(digest.digest())))
                    throw new IOException("גודל או SHA-256 אינם תואמים: " + target.getName());
                output.getFD().sync();
            }
            if (cancelled.getAsBoolean()) throw new IOException("הייבוא נעצר");
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder();
        for (byte b : value) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}

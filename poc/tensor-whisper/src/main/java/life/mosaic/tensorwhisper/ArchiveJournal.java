package life.mosaic.tensorwhisper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Newline is the commit delimiter. Only a torn final line is removed on recovery. */
final class ArchiveJournal implements AutoCloseable {
    private final RandomAccessFile file;

    ArchiveJournal(File path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        long length = file.length(), end = length;
        while (end > 0) { file.seek(end - 1); if (file.read() == '\n') break; end--; }
        if (end != length) { file.setLength(end); file.getFD().sync(); }
    }

    List<String> lines() throws IOException {
        if (file.length() > 16 * 1024 * 1024) throw new IOException("Journal exceeds diagnostic limit");
        file.seek(0); byte[] bytes = new byte[(int)file.length()]; file.readFully(bytes);
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    void append(String json) throws IOException {
        if (json.contains("\n") || json.contains("\r")) throw new IOException("Journal record must be one line");
        file.seek(file.length()); file.write((json + "\n").getBytes(StandardCharsets.UTF_8)); file.getFD().sync();
    }

    @Override public void close() throws IOException { file.close(); }
}

package life.mosaic.tensorllm;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** One private, versioned snapshot. No cache, network, journal or automatic sample insertion. */
final class PersonalCommandStore {
    interface Backend {
        String read() throws IOException;
        boolean write(String value) throws IOException;
    }
    private final Backend backend;

    PersonalCommandStore(Backend backend) { this.backend = backend; }

    static PersonalCommandStore open(Context context) {
        File path = new File(context.getFilesDir(), "personal-commands.json");
        AtomicFile file = new AtomicFile(path);
        return new PersonalCommandStore(new Backend() {
            public String read() throws IOException {
                try (InputStream input = file.openRead(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] block = new byte[1024]; int count;
                    while ((count = input.read(block)) != -1) {
                        if (buffer.size() + count > 64000) throw new IOException();
                        buffer.write(block, 0, count);
                    }
                    return buffer.toString(StandardCharsets.UTF_8.name());
                } catch (FileNotFoundException missing) {
                    if (path.exists() || new File(path + ".bak").exists()) throw missing;
                    return null;
                }
            }
            public boolean write(String value) throws IOException {
                FileOutputStream output = null;
                try {
                    output = file.startWrite();
                    output.write(value.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                    file.finishWrite(output);
                    output = null;
                    return value.equals(read());
                } catch (IOException failure) {
                    if (output != null) file.failWrite(output);
                    throw failure;
                }
            }
        });
    }

    PersonalCommands load() throws IOException {
        try { return PersonalCommands.decode(backend.read()); }
        catch (Exception invalid) { throw new IOException("לא ניתן לקרוא את המילון האישי. הביטויים השמורים לא שונו."); }
    }

    void save(PersonalCommands commands) throws IOException {
        try {
            if (!backend.write(commands.encode())) throw new IOException();
        } catch (Exception failure) {
            throw new IOException("לא ניתן לשמור את המילון האישי. נסה שוב.");
        }
    }
}

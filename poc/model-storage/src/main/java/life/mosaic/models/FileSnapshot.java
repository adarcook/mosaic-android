package life.mosaic.models;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory invalidation for already verified/loaded session files; never a persisted hash receipt. */
public final class FileSnapshot {
    private final Map<File,String> identity=new LinkedHashMap<>();
    public FileSnapshot(File... files)throws IOException {
        for(File file:files)identity.put(file.getCanonicalFile(),stamp(file));
    }
    private static String stamp(File file)throws IOException {
        BasicFileAttributes a=Files.readAttributes(file.toPath(),BasicFileAttributes.class);
        if(!a.isRegularFile())throw new IOException("Model is not a regular file: "+file.getName());
        return a.size()+"|"+a.lastModifiedTime()+"|"+a.creationTime()+"|"+a.fileKey();
    }
    public void requireUnchanged()throws IOException {
        for(Map.Entry<File,String> entry:identity.entrySet())
            if(!entry.getValue().equals(stamp(entry.getKey())))
                throw new IOException("Model changed during warm session; prepare again: "+entry.getKey().getName());
    }
}

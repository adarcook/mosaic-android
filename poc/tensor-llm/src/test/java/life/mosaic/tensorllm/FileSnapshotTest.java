package life.mosaic.tensorllm;

import life.mosaic.models.FileSnapshot;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileSnapshotTest {
    @Test public void unchangedFileCanBeCheckedAcrossRequests()throws Exception {
        Path dir=Files.createTempDirectory("warm-identity");Path file=dir.resolve("model");
        try{Files.write(file,new byte[]{1,2,3});FileSnapshot snapshot=new FileSnapshot(file.toFile());snapshot.requireUnchanged();snapshot.requireUnchanged();}
        finally{Files.deleteIfExists(file);Files.delete(dir);}
    }
    @Test public void modificationDeletionAndReplacementInvalidateSession()throws Exception {
        Path dir=Files.createTempDirectory("warm-identity");Path file=dir.resolve("model"),next=dir.resolve("new");
        try{
            Files.write(file,new byte[]{1,2,3});FileSnapshot first=new FileSnapshot(file.toFile());
            FileTime time=Files.getLastModifiedTime(file);Files.write(file,new byte[]{3,2,1});Files.setLastModifiedTime(file,FileTime.fromMillis(time.toMillis()+5000));
            assertThrows(IOException.class,first::requireUnchanged);
            FileSnapshot second=new FileSnapshot(file.toFile());Files.write(next,new byte[]{3,2,1});Files.setLastModifiedTime(next,Files.getLastModifiedTime(file));
            Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IOException.class,second::requireUnchanged);
            FileSnapshot third=new FileSnapshot(file.toFile());Files.delete(file);assertThrows(IOException.class,third::requireUnchanged);
        }finally{Files.deleteIfExists(next);Files.deleteIfExists(file);Files.delete(dir);}
    }
    @Test public void missingOrDirectoryCannotSeedLoadedIdentity()throws Exception {
        Path dir=Files.createTempDirectory("warm-identity");
        try{assertThrows(IOException.class,()->new FileSnapshot(dir.toFile()));assertThrows(IOException.class,()->new FileSnapshot(dir.resolve("missing").toFile()));}
        finally{Files.delete(dir);}
    }
}

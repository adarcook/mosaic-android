package life.mosaic.tensorllm;

import life.mosaic.models.SessionVerification;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.Assert.*;

public class SessionVerificationTest {
    private static void write(Path path,String value)throws IOException {Files.write(path,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void receiptSurvivesWorkerRestartOnlyWithStableFileIdentity()throws Exception {
        Path root=Files.createTempDirectory("receipt");File model=root.resolve("model").toFile(),receipt=root.resolve("private/receipt").toFile();write(model.toPath(),"model");
        String session=UUID.randomUUID().toString();int[] calls={0};
        boolean stableFileIdentity=Files.readAttributes(model.toPath(),java.nio.file.attribute.BasicFileAttributes.class).fileKey()!=null;
        assertFalse(SessionVerification.verify(receipt,session,"hash",new File[]{model},()->calls[0]++));
        assertEquals(stableFileIdentity,SessionVerification.verify(receipt,session,"hash",new File[]{model},()->calls[0]++));
        assertFalse(SessionVerification.verify(receipt,UUID.randomUUID().toString(),"hash",new File[]{model},()->calls[0]++));
        assertEquals(stableFileIdentity?2:3,calls[0]);
        assertEquals(stableFileIdentity,receipt.exists());
    }
    @Test public void missingFileKeyNeverAllowsMetadataOnlyReceiptReuse()throws Exception {
        Path root=Files.createTempDirectory("receipt");File model=root.resolve("model").toFile(),receipt=root.resolve("receipt").toFile();write(model.toPath(),"abc");
        org.junit.Assume.assumeTrue(Files.readAttributes(model.toPath(),java.nio.file.attribute.BasicFileAttributes.class).fileKey()==null);
        String session=UUID.randomUUID().toString();int[] calls={0};
        assertFalse(SessionVerification.verify(receipt,session,"hash",new File[]{model},()->calls[0]++));
        assertFalse(SessionVerification.verify(receipt,session,"hash",new File[]{model},()->calls[0]++));
        assertEquals(2,calls[0]);
        assertFalse(receipt.exists());
    }
    @Test public void changedContractOrAtomicReplacementInvalidatesReceipt()throws Exception {
        Path root=Files.createTempDirectory("receipt");File model=root.resolve("model").toFile(),receipt=root.resolve("receipt").toFile();write(model.toPath(),"abc");String session=UUID.randomUUID().toString();int[] calls={0};
        SessionVerification.verify(receipt,session,"hash",new File[]{model},()->calls[0]++);
        assertFalse(SessionVerification.verify(receipt,session,"new-hash",new File[]{model},()->calls[0]++));
        Path replacement=root.resolve("replacement");write(replacement,"abc");Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(model.toPath()));Files.move(replacement,model.toPath(),StandardCopyOption.REPLACE_EXISTING);
        assertFalse(SessionVerification.verify(receipt,session,"new-hash",new File[]{model},()->calls[0]++));assertEquals(3,calls[0]);
    }
    @Test public void failedVerificationAndMutationNeverLeaveReusableReceipt()throws Exception {
        Path root=Files.createTempDirectory("receipt");File model=root.resolve("model").toFile(),receipt=root.resolve("receipt").toFile();write(model.toPath(),"abc");String session=UUID.randomUUID().toString();
        SessionVerification.verify(receipt,session,"hash",new File[]{model},()->{});write(model.toPath(),"changed");
        try{SessionVerification.verify(receipt,session,"hash",new File[]{model},()->{throw new IOException("bad hash");});fail();}catch(IOException expected){}assertFalse(receipt.exists());
        try{SessionVerification.verify(receipt,session,"hash",new File[]{model},()->write(model.toPath(),"mutated again"));fail();}catch(IOException expected){}assertFalse(receipt.exists());
    }
    @Test public void absentSessionAlwaysFullyVerifiesWithoutReceipt()throws Exception {
        Path root=Files.createTempDirectory("receipt");File model=root.resolve("model").toFile(),receipt=root.resolve("receipt").toFile();write(model.toPath(),"abc");int[] calls={0};
        for(int i=0;i<2;i++)assertFalse(SessionVerification.verify(receipt,"","hash",new File[]{model},()->calls[0]++));assertEquals(2,calls[0]);assertFalse(receipt.exists());
    }
}

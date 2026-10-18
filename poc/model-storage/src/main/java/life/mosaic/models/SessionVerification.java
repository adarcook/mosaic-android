package life.mosaic.models;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/** Private verification receipt scoped to an explicit interaction session, including opted-in standby.
 * Survives worker disposal; callers must replace the session on a new activation/process lifetime. */
public final class SessionVerification {
    public interface Verify { void run() throws Exception; }
    public static boolean verify(File receipt,String session,String contract,File[] files,Verify full)throws Exception {
        if(session==null||!session.matches("[a-f0-9-]{36}")){full.run();return false;}
        Properties current=identity(session,contract,files),saved=new Properties();
        // Without a file key, replacing a file can preserve every other observed attribute
        // (notably on Windows). Never reuse a metadata-only receipt in that case.
        boolean stableFileIdentity=Boolean.parseBoolean(current.getProperty("stableFileIdentity"));
        if(stableFileIdentity&&receipt.isFile()&&receipt.length()<65536)try(InputStream in=new FileInputStream(receipt)){saved.load(in);}catch(IOException ignored){}
        if(stableFileIdentity&&current.equals(saved))return true;
        // Remove a stale receipt before verification; failure must never leave reusable evidence.
        Files.deleteIfExists(receipt.toPath());
        full.run();
        if(!current.equals(identity(session,contract,files)))throw new IOException("Model files changed during verification");
        // Verification succeeded, but no durable file identity exists for safe receipt reuse.
        if(!stableFileIdentity)return false;
        File parent=receipt.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot save verification receipt");
        File temp=File.createTempFile("verify-",".tmp",parent);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){current.store(out,"Mosaic foreground session verification");out.getFD().sync();}
            Files.move(temp.toPath(),receipt.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temp.toPath());}
        return false;
    }
    private static Properties identity(String session,String contract,File[] files)throws IOException {
        Properties p=new Properties();p.setProperty("session",session);p.setProperty("contract",contract);
        p.setProperty("count",Integer.toString(files.length));
        boolean stableFileIdentity=true;
        for(int i=0;i<files.length;i++){
            File f=files[i].getCanonicalFile();BasicFileAttributes a=Files.readAttributes(f.toPath(),BasicFileAttributes.class);
            if(!a.isRegularFile())throw new IOException("Model is not a regular file: "+f.getName());
            Object fileKey=a.fileKey();
            if(fileKey==null)stableFileIdentity=false;
            p.setProperty("file."+i,f.getPath()+"|"+a.size()+"|"+a.lastModifiedTime()+"|"+a.creationTime()+"|"+fileKey);
        }
        p.setProperty("stableFileIdentity",Boolean.toString(stableFileIdentity));
        return p;
    }
    private SessionVerification(){}
}

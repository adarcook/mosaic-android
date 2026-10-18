package life.mosaic.tensorllm;

import org.junit.Test;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class FeedbackClipsTest {
    private Map<String,FeedbackClips.Clip> clips(){Map<String,FeedbackClips.Clip> result=new LinkedHashMap<>();for(String id:FeedbackClips.TEXT.keySet())result.put(id,new FeedbackClips.Clip(16000,new float[]{.2f,-.3f,.01f}));return result;}
    @Test public void normalizedCatalogRoundTrips()throws Exception{File root=Files.createTempDirectory("feedback").toFile();FeedbackClips.save(root,clips());assertTrue(FeedbackClips.valid(root));for(float sample:FeedbackClips.read(root,"ready").pcm)assertTrue(Math.abs(sample)<=.98f);}
    @Test public void modifiedPcmIsRejected()throws Exception{File root=Files.createTempDirectory("feedback").toFile();FeedbackClips.save(root,clips());Path pcm=new File(FeedbackClips.directory(root),"ready.pcm").toPath();byte[] bytes=Files.readAllBytes(pcm);bytes[0]^=1;Files.write(pcm,bytes);assertFalse(FeedbackClips.valid(root));}
    @Test public void truncatedPcmIsRejected()throws Exception{File root=Files.createTempDirectory("feedback").toFile();FeedbackClips.save(root,clips());Files.write(new File(FeedbackClips.directory(root),"received.pcm").toPath(),new byte[4]);assertFalse(FeedbackClips.valid(root));}
    @Test public void unknownIdCannotReadArbitraryFile()throws Exception{try{FeedbackClips.read(Files.createTempDirectory("feedback").toFile(),"../private");fail();}catch(IOException expected){}}
    @Test public void missingCuePreventsActivation()throws Exception{File root=Files.createTempDirectory("feedback").toFile();Map<String,FeedbackClips.Clip> catalog=clips();catalog.remove("received");try{FeedbackClips.save(root,catalog);fail();}catch(IOException expected){}assertFalse(FeedbackClips.valid(root));}
    @Test public void nonFiniteAudioIsRejected()throws Exception{Map<String,FeedbackClips.Clip> catalog=clips();catalog.put("ready",new FeedbackClips.Clip(16000,new float[]{Float.NaN}));try{FeedbackClips.save(Files.createTempDirectory("feedback").toFile(),catalog);fail();}catch(IOException expected){}}
    @Test public void staleContractCannotBeReused()throws Exception{File root=Files.createTempDirectory("feedback").toFile();FeedbackClips.save(root,clips());Path manifest=new File(FeedbackClips.directory(root),"manifest.json").toPath();String text=new String(Files.readAllBytes(manifest),java.nio.charset.StandardCharsets.UTF_8);Files.write(manifest,text.replace("mosaic-feedback-v1","mosaic-feedback-old").getBytes(java.nio.charset.StandardCharsets.UTF_8));assertFalse(FeedbackClips.valid(root));}
}

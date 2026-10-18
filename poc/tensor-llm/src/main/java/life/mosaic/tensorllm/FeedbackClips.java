package life.mosaic.tensorllm;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Fixed, locally synthesized UI speech. Never accepts a user transcript or notification. */
final class FeedbackClips {
    static final Map<String,String> TEXT = Collections.unmodifiableMap(new LinkedHashMap<>() {{
        put("ready", "אני פה. דבר אליי.");
        put("received", "אני על זה, רק רגע.");
        put("error", "נתקעתי רגע. צריך להפעיל אותי שוב באפליקציה.");
    }});
    private static final String CONTRACT="mosaic-feedback-v1|"+TtsPack.REV+"|"+new TreeMap<>(TtsPack.PINNED)+"|"+TEXT;
    static final class Clip {
        final int rate; final float[] pcm;
        Clip(int rate,float[] pcm){this.rate=rate;this.pcm=pcm;}
    }
    static File directory(File files){return new File(files,"voice-feedback-v1");}
    static boolean valid(File files){try{for(String id:TEXT.keySet())read(files,id);return true;}catch(Exception e){return false;}}
    static void prepare(File files,BlueVoice voice)throws Exception {
        if(valid(files))return;
        Map<String,Clip> clips=new LinkedHashMap<>();
        for(var entry:TEXT.entrySet()){voice.resetNoise();clips.put(entry.getKey(),new Clip(voice.rate,voice.synthesize(entry.getValue())));}
        save(files,clips);
    }
    static void save(File files,Map<String,Clip> clips)throws Exception {
        if(!clips.keySet().equals(TEXT.keySet()))throw new IOException("Feedback catalog mismatch");
        File dir=directory(files);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create feedback cache");
        JSONObject manifest=new JSONObject().put("contract",CONTRACT);JSONObject entries=new JSONObject();
        for(var entry:clips.entrySet()){
            Clip clip=entry.getValue();if(clip.rate<16000||clip.rate>48000||clip.pcm.length<1||clip.pcm.length>clip.rate*12)throw new IOException("Feedback bounds");
            float peak=0;for(float sample:clip.pcm){if(!Float.isFinite(sample))throw new IOException("Invalid feedback PCM");peak=Math.max(peak,Math.abs(sample));}
            if(peak<0.00001f)throw new IOException("Silent feedback");
            float gain=Math.min(6f,.92f/peak);ByteBuffer bytes=ByteBuffer.allocate(clip.pcm.length*4).order(ByteOrder.LITTLE_ENDIAN);
            for(float sample:clip.pcm)bytes.putFloat(Math.max(-.98f,Math.min(.98f,sample*gain)));
            byte[] pcm=bytes.array();atomic(new File(dir,entry.getKey()+".pcm"),pcm);
            entries.put(entry.getKey(),new JSONObject().put("rate",clip.rate).put("bytes",pcm.length).put("sha256",hash(pcm)));
        }
        manifest.put("clips",entries);atomic(new File(dir,"manifest.json"),manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!valid(files))throw new IOException("Feedback cache verification failed");
    }
    static Clip read(File files,String id)throws Exception {
        if(!TEXT.containsKey(id))throw new IOException("Unknown feedback ID");
        File dir=directory(files),manifestFile=new File(dir,"manifest.json");
        if(!manifestFile.isFile()||manifestFile.length()>16384)throw new IOException("Feedback manifest unavailable");
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        if(!CONTRACT.equals(manifest.getString("contract")))throw new IOException("Feedback contract mismatch");
        JSONObject entries=manifest.getJSONObject("clips");if(entries.length()!=TEXT.size())throw new IOException("Feedback catalog mismatch");
        JSONObject item=entries.getJSONObject(id);int rate=item.getInt("rate"),length=item.getInt("bytes");
        if(rate<16000||rate>48000||length<4||length%4!=0||length>rate*12*4)throw new IOException("Feedback bounds");
        File source=new File(dir,id+".pcm");if(source.length()!=length)throw new IOException("Feedback length mismatch");
        byte[] bytes=Files.readAllBytes(source.toPath());if(!hash(bytes).equals(item.getString("sha256")))throw new IOException("Feedback digest mismatch");
        float[] pcm=new float[length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm);
        for(float sample:pcm)if(!Float.isFinite(sample)||Math.abs(sample)>1)throw new IOException("Invalid feedback PCM");
        return new Clip(rate,pcm);
    }
    private static String hash(byte[] bytes)throws Exception {return TranscriptSource.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void atomic(File target,byte[] bytes)throws Exception {
        File temporary=File.createTempFile("cue-",".tmp",target.getParentFile());
        try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temporary.toPath());}
    }
    private FeedbackClips(){}
}

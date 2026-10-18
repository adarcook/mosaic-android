package life.mosaic.tensorllm;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class TtsPackTest {
    private JSONObject pack()throws Exception {JSONObject files=new JSONObject();for(var item:TtsPack.PINNED.entrySet())files.put(item.getKey(),new JSONObject().put("bytes",1).put("sha256",item.getValue()));files.put("reply.json",new JSONObject().put("bytes",1).put("sha256","0".repeat(64)));return new JSONObject().put("format",1).put("voice","libri_male_6209").put("blue_revision","468da64b4a51795a7594a3637727dbaf876b6df2").put("renikud_revision",TtsPack.REV).put("files",files);}
    private InputStream input(JSONObject p){return new ByteArrayInputStream(p.toString().getBytes(StandardCharsets.UTF_8));}
    @Test public void pinnedManifestAcceptedButWrongWeightRejected()throws Exception {JSONObject p=pack();assertEquals(7,TtsPack.manifest(input(p)).getJSONObject("files").length());p.getJSONObject("files").getJSONObject("renikud.onnx").put("sha256","0".repeat(64));assertThrows(IOException.class,()->TtsPack.manifest(input(p)));}
    @Test public void rejectsOtherModelRevisionOrVoice()throws Exception {JSONObject p=pack().put("renikud_revision","other");assertThrows(IOException.class,()->TtsPack.manifest(input(p)));JSONObject q=pack().put("voice","other");assertThrows(IOException.class,()->TtsPack.manifest(input(q)));}
    @Test public void rejectsExtraTraversalFileAndOversizedSidecar()throws Exception {JSONObject p=pack();p.getJSONObject("files").put("../escape",new JSONObject().put("bytes",1).put("sha256","0".repeat(64)));assertThrows(IOException.class,()->TtsPack.manifest(input(p)));JSONObject q=pack();q.getJSONObject("files").getJSONObject("reply.json").put("bytes",4000001);assertThrows(IOException.class,()->TtsPack.manifest(input(q)));}
}

package life.mosaic.tensorllm;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import life.mosaic.models.ModelCopy;

final class TtsPack {
    static final String REV="679c56ca449d41873fb8ff7711ddf7563d28198f";
    static final Map<String,String> PINNED=Map.of(
        "renikud.onnx","c3a2bdc35afbf53f28cdece4672dcca9b530dfa43a0d65b4c5bbc47150b32d0d",
        "vocab.json","13fba5e735222fcf30d9c062d898982a1ba86d481f11560fbbdf6e0af79417b3",
        "duration_predictor_style.onnx","3d0347cfc09234b78a20d5fa50b5c402d338fb99825f40ada8c70301edbb60ea",
        "text_encoder.onnx","f971aa25315b93efff1850b39fe293aa93ae85df69fabf5e5022318cc632973c",
        "vector_estimator.onnx","50557b34acfafa4b34f21dae263faa74d3f3169f114ff927599a1eff110d0b6e",
        "vocoder.onnx","b0709df316ff44cc263c04fbf9d17371b16897071a48b79d3634d5f379fb3067");
    static JSONObject manifest(InputStream input)throws Exception {
        byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IOException("TTS manifest too large");
        JSONObject manifest=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if(manifest.getInt("format")!=1 || !"468da64b4a51795a7594a3637727dbaf876b6df2".equals(manifest.getString("blue_revision")) || !"libri_male_6209".equals(manifest.getString("voice")))throw new IOException("Unsupported BlueTTS pack contract");
        if(!REV.equals(manifest.getString("renikud_revision")))throw new IOException("Unsupported Renikud revision");
        JSONObject files=manifest.getJSONObject("files");
        if(files.length()!=7)throw new IOException("Expected seven TTS files");
        for(String name:new String[]{"renikud.onnx","duration_predictor_style.onnx","text_encoder.onnx","vector_estimator.onnx","vocoder.onnx","vocab.json","reply.json"}) {
            JSONObject entry=files.getJSONObject(name);String sha=entry.getString("sha256");long size=entry.getLong("bytes");
            if(!sha.matches("[0-9a-f]{64}")||size<=0||size>1300000000L)throw new IOException("Invalid TTS identity");
            if(PINNED.containsKey(name)&&!PINNED.get(name).equals(sha))throw new IOException("TTS model identity mismatch");
            if(name.endsWith(".json") && size>4000000)throw new IOException("TTS sidecar too large");
        }
        return manifest;
    }
    static void verify(File root)throws Exception {
        JSONObject manifest;try(InputStream input=new FileInputStream(new File(root,"manifest.json"))){manifest=manifest(input);}
        JSONObject files=manifest.getJSONObject("files");
        for(java.util.Iterator<String> names=files.keys();names.hasNext();){String name=names.next();
            JSONObject spec=files.getJSONObject(name);
            if(!ModelCopy.valid(new File(root,name),spec.getLong("bytes"),spec.getString("sha256"), () -> false))throw new IOException("Missing/corrupt TTS file: "+name);
        }
    }
}

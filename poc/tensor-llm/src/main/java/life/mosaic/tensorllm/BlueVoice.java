package life.mosaic.tensorllm;

import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** The existing BlueTTS 2.5 acoustic graphs and voice style, with fresh text/latents per chunk. */
final class BlueVoice implements AutoCloseable {
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final List<OrtSession> sessions=new ArrayList<>();
    private OrtSession duration,encoder,vector,vocoder;
    private RenikudFrontend frontend;
    private final JSONObject fixture, vocabulary;
    final int rate,frame,channels,factor; private final int steps;
    long lastG2pMs,lastAcousticMs;
    private final Random random=new Random(123);
    private static final class Tensor { final float[] values;final long[] shape;Tensor(float[] v,long... s){values=v;shape=s;} }
    BlueVoice(File root)throws Exception {
        fixture=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(root,"reply.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject vocab=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(root,"vocab.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        vocabulary=vocab.has("char_to_id")?vocab.getJSONObject("char_to_id"):vocab;
        rate=fixture.getInt("sample_rate");channels=fixture.getInt("ldim");factor=fixture.getInt("compress");
        frame=fixture.getInt("base_chunk_size")*factor;steps=fixture.getInt("steps");
        if(rate<16000||rate>48000||channels<1||channels>256||factor<1||factor>16||frame<1||steps<1||steps>16)throw new IOException("Invalid BlueTTS configuration");
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            duration=load(root,"duration_predictor_style",options);encoder=load(root,"text_encoder",options);
            vector=load(root,"vector_estimator",options);vocoder=load(root,"vocoder",options);
            frontend=new RenikudFrontend(new File(root,"renikud.onnx"));
        }catch(Exception|Error failure){close();throw failure;}
    }
    private OrtSession load(File root,String name,OrtSession.SessionOptions options)throws Exception {
        OrtSession s=env.createSession(new File(root,name+".onnx").getPath(),options);sessions.add(s);return s;
    }
    private Tensor fixture(String name)throws Exception {JSONObject item=fixture.getJSONObject(name);JSONArray data=item.getJSONArray("data"),shape=item.getJSONArray("shape");float[] a=new float[data.length()];long[] dims=new long[shape.length()];for(int i=0;i<a.length;i++)a[i]=(float)data.getDouble(i);for(int i=0;i<dims.length;i++)dims[i]=shape.getLong(i);return new Tensor(a,dims);}
    private Tensor run(OrtSession session,Map<String,Tensor> values,long[] ids)throws Exception {
        Map<String,OnnxTensor> tensors=new HashMap<>();
        try {
            for(var entry:values.entrySet()){Tensor t=entry.getValue();tensors.put(entry.getKey(),OnnxTensor.createTensor(env,FloatBuffer.wrap(t.values),t.shape));}
            if(ids!=null)tensors.put("text_ids",OnnxTensor.createTensor(env,LongBuffer.wrap(ids),new long[]{1,ids.length}));
            try(OrtSession.Result result=session.run(tensors)){OnnxTensor t=(OnnxTensor)result.get(0);FloatBuffer b=t.getFloatBuffer();float[] a=new float[b.remaining()];b.get(a);return new Tensor(a,((TensorInfo)t.getInfo()).getShape());}
        }finally{for(OnnxTensor t:tensors.values())t.close();}
    }
    float[] synthesize(String text)throws Exception {
        return synthesize(text, phase -> {});
    }
    float[] synthesize(String text,java.util.function.Consumer<String> progress)throws Exception {
        progress.accept("הכנת הגייה — RenikudPlus על CPU");
        long start=System.nanoTime();
        String ipa=java.text.Normalizer.normalize(frontend.phonemize(text),java.text.Normalizer.Form.NFKD).replaceAll("\\s+"," ").trim();
        lastG2pMs=(System.nanoTime()-start)/1000000;long acousticStart=System.nanoTime();
        progress.accept("יצירת קול — BlueTTS על CPU");
        if(!ipa.matches(".*[.!?;:,]$"))ipa+=".";
        int[] points=ipa.codePoints().toArray();if(points.length<1||points.length>500)throw new IOException("Invalid phoneme length");
        long[] ids=new long[points.length];for(int i=0;i<ids.length;i++){
            String ch=new String(Character.toChars(points[i]));
            if(vocabulary.has(ch))ids[i]=vocabulary.getLong(ch);
            else if(vocabulary.has(""+points[i]))ids[i]=vocabulary.getLong(""+points[i]);
            else throw new IOException("Phoneme missing from pinned vocabulary: U+"+Integer.toHexString(points[i]));
        }
        float[] ones=new float[ids.length];Arrays.fill(ones,1);Tensor mask=new Tensor(ones,1,1,ids.length);
        Tensor ttl=fixture("style_ttl"),dp=fixture("style_dp");
        float seconds=run(duration,Map.of("style_dp",dp,"text_mask",mask),ids).values[0];
        if(!Float.isFinite(seconds)||seconds<=0||seconds>25)throw new IOException("BlueTTS chunk duration out of bounds");
        Tensor embedded=run(encoder,Map.of("style_ttl",ttl,"text_mask",mask),ids);
        int time=((int)(seconds*rate)+frame-1)/frame;if(time<1||time>1200)throw new IOException("Latent size out of bounds");
        float[] noise=new float[channels*factor*time];for(int i=0;i<noise.length;i++)noise[i]=(float)random.nextGaussian();
        float[] lm=new float[time];Arrays.fill(lm,1);Tensor latentMask=new Tensor(lm,1,1,time);
        Tensor x=new Tensor(noise,1,channels*factor,time);float cfg=(float)fixture.getDouble("cfg");
        for(int step=0;step<steps;step++){
            Map<String,Tensor> inputs=new HashMap<>();inputs.put("noisy_latent",x);inputs.put("latent_mask",latentMask);
            inputs.put("current_step",new Tensor(new float[]{step},1));inputs.put("total_step",new Tensor(new float[]{steps},1));
            inputs.put("text_emb",embedded);inputs.put("style_ttl",ttl);inputs.put("text_mask",mask);
            if(vector.getInputNames().contains("cfg_scale")){inputs.put("cfg_scale",new Tensor(new float[]{cfg},1));x=run(vector,inputs,null);}
            else {Tensor cond=run(vector,inputs,null);inputs.put("text_emb",fixture("u_text"));inputs.put("style_ttl",fixture("u_ref"));inputs.put("text_mask",new Tensor(new float[]{1},1,1,1));Tensor uncond=run(vector,inputs,null);float[] guided=new float[cond.values.length];for(int i=0;i<guided.length;i++)guided[i]=uncond.values[i]+cfg*(cond.values[i]-uncond.values[i]);x=new Tensor(guided,cond.shape);}
        }
        if(((TensorInfo)vocoder.getInputInfo().get("latent").getInfo()).getShape()[1]==channels){
            float[] mean=fixture("mean").values,std=fixture("std").values;float scale=(float)fixture.getDouble("normalizer_scale");
            if(mean.length!=channels*factor||std.length!=mean.length||scale<=0)throw new IOException("Invalid latent normalization");
            float[] z=new float[x.values.length];for(int c=0;c<channels;c++)for(int t=0;t<time;t++)for(int f=0;f<factor;f++){int cf=c*factor+f;z[c*time*factor+t*factor+f]=x.values[cf*time+t]/scale*std[cf]+mean[cf];}x=new Tensor(z,1,channels,time*factor);
        }
        float[] audio=run(vocoder,Map.of("latent",x),null).values;
        int trim=audio.length>frame*2?frame:0;audio=Arrays.copyOfRange(audio,trim,audio.length-trim);
        float peak=0;for(float v:audio){if(!Float.isFinite(v))throw new IOException("Non-finite synthesized audio");peak=Math.max(peak,Math.abs(v));}
        if(audio.length==0)throw new IOException("Empty synthesized audio");if(peak>.95f)for(int i=0;i<audio.length;i++)audio[i]*=.95f/peak;
        lastAcousticMs=(System.nanoTime()-acousticStart)/1000000;return audio;
    }
    @Override public void close()throws Exception {if(frontend!=null)frontend.close();for(int i=sessions.size()-1;i>=0;i--)sessions.get(i).close();}
}

package life.mosaic.tensorllm;

import ai.onnxruntime.*;
import java.io.File;
import java.nio.LongBuffer;
import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** RenikudPlus character model; exact cascade MAP when embedded conditioning metadata exists. */
final class RenikudFrontend implements AutoCloseable {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final JSONObject vocab, consonants, vowels, constraints, geresh;
    private final Map<String,String> meta;
    RenikudFrontend(File model) throws Exception {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            session = env.createSession(model.getPath(), options);
        }
        meta = session.getMetadata().getCustomMetadata();
        vocab = new JSONObject(meta.get("vocab")); consonants = new JSONObject(meta.get("consonant_vocab"));
        vowels = new JSONObject(meta.get("vowel_vocab")); constraints = new JSONObject(meta.get("letter_consonant_constraints"));
        geresh = new JSONObject(meta.getOrDefault("geresh_map", "{}"));
    }
    String phonemize(String input) throws Exception {
        String text = Normalizer.normalize(input, Normalizer.Form.NFD);
        if (text.length() > 200) throw new IllegalArgumentException("G2P chunk too long");
        long[] ids = new long[text.length()+2], mask = new long[ids.length]; Arrays.fill(mask, 1);
        ids[0] = Long.parseLong(meta.get("cls_token_id")); ids[ids.length-1] = Long.parseLong(meta.get("sep_token_id"));
        for (int i=0;i<text.length();i++) ids[i+1] = vocab.optLong(text.substring(i,i+1), vocab.optLong("[UNK]",0));
        Map<String,OnnxTensor> feeds = new HashMap<>();
        try {
            feeds.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1,ids.length}));
            feeds.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1,ids.length}));
            for (String key : new String[]{"speaker","target_speaker"}) if(session.getInputNames().contains(key))
                feeds.put(key, OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{0}), new long[]{1}));
            try (OrtSession.Result outputs = session.run(feeds)) {
                float[][] c = ((float[][][])outputs.get("consonant_logits").orElseThrow().getValue())[0];
                float[][] v = ((float[][][])outputs.get("vowel_logits").orElseThrow().getValue())[0];
                float[][] s = ((float[][][])outputs.get("stress_logits").orElseThrow().getValue())[0];
                int n = ids.length, C=c[0].length, V=v[0].length;
                int[][] uc = new int[n][2], sc = new int[n][2]; double[] gain = new double[n];
                boolean exact = meta.containsKey("vowel_cond_consonant") && meta.containsKey("stress_cond_consonant") && meta.containsKey("stress_cond_vowel");
                double[][] cv=exact?matrix(meta.get("vowel_cond_consonant")):null, cs=exact?matrix(meta.get("stress_cond_consonant")):null, vs=exact?matrix(meta.get("stress_cond_vowel")):null;
                for(int t=1;t<n-1;t++) {
                    String letter=text.substring(t-1,t); JSONArray allowed=constraints.optJSONArray(letter);
                    if(!exact) {
                        int ci=argmax(c[t],allowed), vi=argmax(v[t],null); uc[t]=new int[]{ci,vi}; sc[t]=uc[t];
                        gain[t]=vowels.optString(""+vi,"∅").equals("∅")?-Double.MAX_VALUE:s[t][1]-s[t][0]; continue;
                    }
                    boolean[] legal=null;if(allowed!=null){legal=new boolean[C];for(int i=0;i<C;i++)legal[i]=contains(allowed,i);}
                    CascadeMap.Result decoded=CascadeMap.decode(c[t],v[t],s[t],cv,cs,vs,legal,meta.getOrDefault("cascade_cond","softmax").equals("softmax"));
                    uc[t]=new int[]{decoded.unstressed().consonant(),decoded.unstressed().vowel()};
                    sc[t]=new int[]{decoded.stressed().consonant(),decoded.stressed().vowel()};gain[t]=decoded.gain();
                }
                Set<Integer> stressed=new HashSet<>();
                java.util.regex.Matcher words=java.util.regex.Pattern.compile("\\S+").matcher(text);
                while(words.find()) { int best=-1;for(int i=words.start();i<words.end();i++)if(isHebrew(text.charAt(i)) && gain[i+1]>-1e100 && (best<0 || gain[i+1]>gain[best]))best=i+1;if(best>=0)stressed.add(best); }
                StringBuilder out=new StringBuilder();
                for(int i=0;i<text.length();i++) {
                    char ch=text.charAt(i);if(!isHebrew(ch)){if(ch!='\''&&ch!='"')out.append(ch);continue;}
                    int t=i+1;boolean stress=stressed.contains(t);int[] pair=stress?sc[t]:uc[t];
                    String co=consonants.optString(""+pair[0],"∅"), vo=vowels.optString(""+pair[1],"∅");
                    if(i+1<text.length() && text.charAt(i+1)=='\'' && geresh.has(""+ch))co=geresh.getString(""+ch);
                    if(ch=='ח' && (i+1==text.length() || !Character.isLetter(text.charAt(i+1))) && vo.equals("a"))out.append(stress?"ˈaχ":"aχ");
                    else {if(!co.equals("∅"))out.append(co);if(stress)out.append('ˈ');if(!vo.equals("∅"))out.append(vo);}
                }
                return out.toString();
            }
        } finally { for(OnnxTensor tensor:feeds.values())tensor.close(); }
    }
    private static double[][] matrix(String raw)throws Exception{JSONArray a=new JSONArray(raw);double[][] x=new double[a.length()][];for(int i=0;i<x.length;i++){JSONArray r=a.getJSONArray(i);x[i]=new double[r.length()];for(int j=0;j<x[i].length;j++)x[i][j]=r.getDouble(j);}return x;}
    private static boolean contains(JSONArray a,int value){for(int i=0;i<a.length();i++)if(a.optInt(i,-1)==value)return true;return false;}
    private static int argmax(float[] row,JSONArray allowed){int best=-1;for(int i=0;i<row.length;i++)if((allowed==null||contains(allowed,i))&&(best<0||row[i]>row[best]))best=i;if(best<0)throw new IllegalArgumentException("No legal G2P class");return best;}
    private static boolean isHebrew(char c){return c>='א'&&c<='ת';}
    @Override public void close() throws OrtException {session.close();}
}

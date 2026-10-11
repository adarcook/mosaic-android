package life.mosaic.tensorllm;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class CascadeMapTest {
    @Test public void matchesPinnedPythonCascadeOnSyntheticHeadsAndWordStress()throws Exception{
        JSONObject root=new JSONObject(new String(getClass().getResourceAsStream("/cascade-parity.json").readAllBytes(),StandardCharsets.UTF_8));JSONArray cases=root.getJSONArray("fixtures");
        assertEquals(32,cases.length());
        for(int x=0;x<cases.length();x++){
            JSONObject test=cases.getJSONObject(x);CascadeMap.Result[] decoded=new CascadeMap.Result[3];int stressed=-1;
            for(int t=0;t<3;t++){
                JSONArray a=test.getJSONArray("allowed").getJSONArray(t);boolean[] allowed=new boolean[a.length()];for(int i=0;i<a.length();i++)allowed[i]=a.getBoolean(i);
                decoded[t]=CascadeMap.decode(row(test.getJSONArray("c").getJSONArray(t)),row(test.getJSONArray("v").getJSONArray(t)),row(test.getJSONArray("s").getJSONArray(t)),matrix(test.getJSONArray("cv")),matrix(test.getJSONArray("cs")),matrix(test.getJSONArray("vs")),allowed,test.getBoolean("softmax"));
                if(stressed<0||decoded[t].gain()>decoded[stressed].gain())stressed=t;
            }
            assertEquals(test.getJSONArray("stress").getInt(0),stressed);
            for(int t=0;t<3;t++){CascadeMap.Choice choice=t==stressed?decoded[t].stressed():decoded[t].unstressed();assertEquals("case "+x+" token "+t,test.getJSONArray("gold_c").getInt(t),choice.consonant());assertEquals(test.getJSONArray("gold_v").getInt(t),choice.vowel());}
        }
    }
    @Test public void rejectsNonFiniteInputs(){assertThrows(IllegalArgumentException.class,()->CascadeMap.logSoftmax(new double[]{Double.NaN,0}));}
    private static float[] row(JSONArray a)throws Exception{float[] x=new float[a.length()];for(int i=0;i<x.length;i++)x[i]=(float)a.getDouble(i);return x;}
    private static double[][] matrix(JSONArray a)throws Exception{double[][] x=new double[a.length()][];for(int i=0;i<x.length;i++){float[] r=row(a.getJSONArray(i));x[i]=new double[r.length];for(int j=0;j<r.length;j++)x[i][j]=r[j];}return x;}
}

package life.mosaic.tensorllm;

import java.util.Arrays;

/** Pure exact-MAP arithmetic from the RenikudPlus embedded cascade heads. */
final class CascadeMap {
    record Choice(int consonant, int vowel, double score) {}
    record Result(Choice unstressed, Choice stressed) {double gain(){return stressed.score-unstressed.score;}}
    static Result decode(float[] consonant,float[] vowel,float[] stress,double[][] cv,double[][] cs,double[][] vs,boolean[] allowed,boolean softmax){
        int C=consonant.length,V=vowel.length;
        double[] pc=condition(consonant,softmax),pv=condition(vowel,softmax);
        double[] bv=new double[V],bs=new double[2];
        for(int j=0;j<V;j++){bv[j]=vowel[j];for(int i=0;i<C;i++)bv[j]-=pc[i]*cv[i][j];}
        for(int j=0;j<2;j++){bs[j]=stress[j];for(int i=0;i<C;i++)bs[j]-=pc[i]*cs[i][j];for(int i=0;i<V;i++)bs[j]-=pv[i]*vs[i][j];}
        double[] lc=logSoftmax(doubles(consonant));Choice u=new Choice(0,0,-Double.MAX_VALUE),s=new Choice(0,0,-Double.MAX_VALUE);
        for(int i=0;i<C;i++){
            if(allowed!=null&&!allowed[i])continue;
            double[] vv=new double[V];for(int j=0;j<V;j++)vv[j]=bv[j]+cv[i][j];double[] lv=logSoftmax(vv);
            for(int j=0;j<V;j++){
                double[] ls=logSoftmax(new double[]{bs[0]+cs[i][0]+vs[j][0],bs[1]+cs[i][1]+vs[j][1]});
                double us=lc[i]+lv[j]+ls[0],ss=lc[i]+lv[j]+ls[1];
                if(us>u.score)u=new Choice(i,j,us);
                if(j!=0&&ss>s.score)s=new Choice(i,j,ss);
            }
        }
        if(!Double.isFinite(u.score)||u.score==-Double.MAX_VALUE)throw new IllegalArgumentException("No legal cascade output");
        return new Result(u,s);
    }
    private static double[] condition(float[] row,boolean softmax){double[] values=doubles(row);if(softmax){double[] log=logSoftmax(values);for(int i=0;i<log.length;i++)log[i]=Math.exp(log[i]);return log;}int best=0;for(int i=1;i<row.length;i++)if(row[i]>row[best])best=i;Arrays.fill(values,0);values[best]=1;return values;}
    static double[] logSoftmax(double[] row){double max=Arrays.stream(row).max().orElseThrow(),sum=0;for(double x:row){if(!Double.isFinite(x))throw new IllegalArgumentException("Non-finite cascade logits");sum+=Math.exp(x-max);}double norm=max+Math.log(sum);double[] result=row.clone();for(int i=0;i<result.length;i++)result[i]-=norm;return result;}
    private static double[] doubles(float[] row){double[] result=new double[row.length];for(int i=0;i<result.length;i++)result[i]=row[i];return result;}
}

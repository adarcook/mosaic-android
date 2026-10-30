package life.mosaic.tensorllm;

/** Initial energy-based end-of-speech experiment; sample counts, not wall-clock inference time. */
final class SpeechEndpoint {
    private long total, voiced, silence;
    private double noise = 30;
    boolean accept(short[] samples,int count) {
        double energy=0;for(int i=0;i<count;i++)energy+=(double)samples[i]*samples[i];
        total+=count;
        double rms=count>0?Math.sqrt(energy/count):0;
        // The observed request peaked below the old fixed RMS=450 gate.
        // Track quiet background conservatively; speech cannot raise its own threshold.
        double threshold=Math.max(100,Math.min(450,noise*3));
        if(count>0&&rms>=threshold){voiced+=count;silence=0;}
        else {silence+=count;noise=Math.min(150,noise*.95+rms*.05);}
        return (voiced>=2560&&silence>=17600&&total>=9600)||(voiced==0&&total>=128000);
    }
}

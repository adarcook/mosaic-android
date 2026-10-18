package life.mosaic.tensorllm;

/** Initial energy-based end-of-speech experiment; sample counts, not wall-clock inference time. */
final class SpeechEndpoint {
    private long total, voiced, silence;
    boolean accept(short[] samples,int count) {
        double energy=0;for(int i=0;i<count;i++)energy+=(double)samples[i]*samples[i];
        total+=count;
        if(count>0&&Math.sqrt(energy/count)>=450){voiced+=count;silence=0;}else silence+=count;
        return (voiced>=2560&&silence>=17600&&total>=9600)||(voiced==0&&total>=128000);
    }
}

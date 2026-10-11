package life.mosaic.tensorllm;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class PlaybackLoopTest {
    static final class Fake implements PlaybackLoop.Output, PlaybackLoop.Clock {
        int threshold=24000, written, starts, maxWrite=137;
        long played, time;
        boolean stalled, rejectThreshold, failWrite;
        public int start(int requested) { starts++; if(!rejectThreshold)threshold=requested; return threshold; }
        public int write(float[] audio,int offset,int count) { if(failWrite)return -6;if(stalled)return 0;int n=Math.min(count,maxWrite);written+=n;return n; }
        public long head() {return played;}
        public long now() {return time;}
        public void pause() {time+=10;if(!stalled&&written>=threshold)played=written;}
    }
    @Test public void shortChunkStartsBelowOneSecondCapacityAndDrainsPartialWrites() throws Exception {
        Fake f=new Fake();PlaybackLoop.play(new float[2400],24000,f,f);
        assertEquals(480,f.threshold);assertEquals(2400,f.written);assertEquals(2400,f.played);assertEquals(1,f.starts);
    }
    @Test public void veryShortChunkThresholdFitsAllFrames() throws Exception {
        Fake f=new Fake();PlaybackLoop.play(new float[17],24000,f,f);assertEquals(17,f.threshold);assertEquals(17,f.played);
    }
    @Test public void stalledZeroWritesHaveBoundedDeadline() {
        Fake f=new Fake();f.stalled=true;IOException e=assertThrows(IOException.class,()->PlaybackLoop.play(new float[2400],24000,f,f));
        assertTrue(e.getMessage().contains("written=0/2400"));assertEquals(5100,f.time);
    }
    @Test public void unusableThresholdFailsBeforeWriting() {
        Fake f=new Fake();f.rejectThreshold=true;assertThrows(IOException.class,()->PlaybackLoop.play(new float[2400],24000,f,f));assertEquals(0,f.written);
    }
    @Test public void negativeWriteIsNotReportedAsCompletion() {
        Fake f=new Fake();f.failWrite=true;assertThrows(IOException.class,()->PlaybackLoop.play(new float[2400],24000,f,f));assertEquals(0,f.played);
    }
}

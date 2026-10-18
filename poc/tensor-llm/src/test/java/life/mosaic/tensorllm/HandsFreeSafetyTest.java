package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class HandsFreeSafetyTest {
    @Test public void requiresWakeAndObservedDeathInEitherOrder(){
        WakeHandoff first=new WakeHandoff();first.result("wake");assertFalse(first.takeReady());first.died();assertTrue(first.takeReady());assertFalse(first.takeReady());
        WakeHandoff second=new WakeHandoff();second.died();assertFalse(second.takeReady());second.result("wake");assertTrue(second.takeReady());
    }
    @Test public void stoppedWorkerCannotBecomeWake(){WakeHandoff gate=new WakeHandoff();gate.result("stopped");gate.result("wake");gate.died();assertFalse(gate.takeReady());}
    @Test public void endpointWaitsForSpeechAndTrailingSilence(){
        SpeechEndpoint endpoint=new SpeechEndpoint();short[] speech=new short[1600];java.util.Arrays.fill(speech,(short)1200);short[] silence=new short[1600];
        for(int i=0;i<3;i++)assertFalse(endpoint.accept(speech,1600));
        for(int i=0;i<10;i++)assertFalse(endpoint.accept(silence,1600));
        assertTrue(endpoint.accept(silence,1600));
    }
    @Test public void noSpeechHasBoundedWait(){SpeechEndpoint endpoint=new SpeechEndpoint();short[] silence=new short[1600];for(int i=0;i<79;i++)assertFalse(endpoint.accept(silence,1600));assertTrue(endpoint.accept(silence,1600));}
}

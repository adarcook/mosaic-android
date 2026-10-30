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
    @Test public void quietSpeechBelowOldThresholdEndsAfterTrailingSilence(){
        SpeechEndpoint endpoint=new SpeechEndpoint();short[] quiet=new short[1600];java.util.Arrays.fill(quiet,(short)180);short[] noise=new short[1600];java.util.Arrays.fill(noise,(short)25);
        for(int i=0;i<3;i++)assertFalse(endpoint.accept(quiet,quiet.length));
        for(int i=0;i<10;i++)assertFalse(endpoint.accept(noise,noise.length));
        assertTrue(endpoint.accept(noise,noise.length));
    }
    @Test public void lowBackgroundAloneDoesNotCountAsSpeech(){
        SpeechEndpoint endpoint=new SpeechEndpoint();short[] noise=new short[1600];java.util.Arrays.fill(noise,(short)30);
        for(int i=0;i<79;i++)assertFalse(endpoint.accept(noise,noise.length));assertTrue(endpoint.accept(noise,noise.length));
    }
    @Test public void cancelledReadinessCannotBeReopenedByLateCueCompletion(){
        CaptureGate gate=new CaptureGate(true);assertFalse(gate.accepting());gate.cancel();gate.ready();assertFalse(gate.accepting());assertTrue(gate.cancelled());
    }
    @Test public void captureRequiresCueDrainAndManualCaptureDoesNot(){
        CaptureGate gate=new CaptureGate(true);assertFalse(gate.accepting());gate.ready();assertTrue(gate.accepting());gate.ready();assertTrue(gate.accepting());assertTrue(new CaptureGate(false).accepting());
    }
}

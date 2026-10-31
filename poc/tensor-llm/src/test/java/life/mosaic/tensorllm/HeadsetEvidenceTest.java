package life.mosaic.tensorllm;
import life.mosaic.audio.HeadsetEvidence;
import life.mosaic.audio.HeadsetAudio;
import org.junit.Test;
import static org.junit.Assert.*;
public class HeadsetEvidenceTest {
    @Test public void outputSinkDoesNotProveInput(){assertFalse(HeadsetEvidence.input(12,7,11,7,true,false));assertTrue(HeadsetEvidence.input(12,7,12,7,true,false));}
    @Test public void sameDeviceIdWithWrongProfileIsRejected(){assertFalse(HeadsetEvidence.input(12,7,12,26,true,false));assertFalse(HeadsetEvidence.output(11,7,11,11,26));}
    @Test public void silencedOrMissingConfigurationIsNeverReady(){assertFalse(HeadsetEvidence.input(12,7,12,7,true,true));assertFalse(HeadsetEvidence.input(12,7,12,7,false,false));}
    @Test public void changedCommunicationRouteInvalidatesOutput(){assertFalse(HeadsetEvidence.output(11,7,2,11,7));assertFalse(HeadsetEvidence.output(11,7,11,2,2));assertTrue(HeadsetEvidence.output(11,7,11,11,7));}
    @Test public void missingIdsAreRejected(){assertFalse(HeadsetEvidence.input(-1,7,-1,7,true,false));assertFalse(HeadsetEvidence.output(-1,7,-1,-1,7));}
    @Test public void a2dpAndInvalidContractCannotBecomeMicrophoneRoutes(){for(int type:new int[]{2,8,-1})try{new HeadsetAudio(11,12,type);fail();}catch(IllegalArgumentException expected){}try{new HeadsetAudio(11,-1,7);fail();}catch(IllegalArgumentException expected){}}
}

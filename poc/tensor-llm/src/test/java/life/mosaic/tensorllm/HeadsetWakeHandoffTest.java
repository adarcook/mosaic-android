package life.mosaic.tensorllm;
import org.junit.Test;
import static org.junit.Assert.*;
public class HeadsetWakeHandoffTest {
    @Test public void quickNativeDeathCannotCutOffAcknowledgement(){WakeHandoff h=new WakeHandoff();h.ackStarted();h.result("wake");h.died();assertFalse(h.takeReady());h.ackCompleted();assertTrue(h.takeReady());assertFalse(h.takeReady());}
    @Test public void completedAcknowledgementStillWaitsForNativeDeath(){WakeHandoff h=new WakeHandoff();h.ackStarted();h.ackCompleted();h.result("wake");assertFalse(h.takeReady());h.died();assertTrue(h.takeReady());}
    @Test public void errorCannotBeRevivedByLateAcknowledgement(){WakeHandoff h=new WakeHandoff();h.ackStarted();h.result("wake");h.died();h.result("error");h.ackCompleted();assertFalse(h.takeReady());}
}

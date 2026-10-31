package life.mosaic.tensorllm;
import org.junit.Test;
import static org.junit.Assert.*;
public class HeadsetResumeGateTest {
    @Test public void endingCommunicationCanRestoreWithoutUi(){assertTrue(HeadsetResumeGate.allowed(0,true,true,false,false));assertFalse(HeadsetResumeGate.allowed(3,true,true,false,false));assertFalse(HeadsetResumeGate.allowed(2,true,true,false,false));}
    @Test public void explicitStopCannotBeOverriddenByModeOrConnectionChanges(){assertFalse(HeadsetResumeGate.allowed(0,false,true,false,false));}
    @Test public void activeTurnsAndPhoneModeNeverStartACompetingListener(){assertFalse(HeadsetResumeGate.allowed(0,true,true,true,false));assertFalse(HeadsetResumeGate.allowed(0,true,true,false,true));assertFalse(HeadsetResumeGate.allowed(0,true,false,false,false));}
    @Test public void repeatedModeChangesCannotResetTheFailureBudget(){RecoveryBudget budget=new RecoveryBudget(3);for(int i=0;i<3;i++){assertTrue(HeadsetResumeGate.allowed(0,true,true,false,false));long ticket=budget.request();assertTrue(ticket>0);assertTrue(budget.consume(ticket));}assertEquals(RecoveryBudget.EXHAUSTED,budget.request());}
}

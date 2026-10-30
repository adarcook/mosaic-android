package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListeningIntentTest {
    static class Memory implements ListeningIntent.Backend {
        boolean value,fail;
        public boolean read(){return value;}
        public boolean write(boolean enabled){if(fail)return false;value=enabled;return true;}
    }
    @Test public void startsOffAndSurvivesProcessStyleRecreationAfterOwnerOptIn(){Memory disk=new Memory();assertFalse(new ListeningIntent(disk).enabled());assertTrue(new ListeningIntent(disk).set(true));assertTrue(new ListeningIntent(disk).enabled());}
    @Test public void explicitStopSurvivesRecreationAndLateRecoveryChecks(){Memory disk=new Memory();ListeningIntent first=new ListeningIntent(disk);first.set(true);ListeningIntent earlierRecovery=new ListeningIntent(disk);assertTrue(first.set(false));assertFalse(earlierRecovery.enabled());assertFalse(new ListeningIntent(disk).enabled());}
    @Test public void failedOptInDoesNotEnableBackgroundMicrophone(){Memory disk=new Memory();disk.fail=true;assertFalse(new ListeningIntent(disk).set(true));assertFalse(new ListeningIntent(disk).enabled());}
}

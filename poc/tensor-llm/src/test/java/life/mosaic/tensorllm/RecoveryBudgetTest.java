package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryBudgetTest {
    @Test public void concurrentFailureCallbacksShareOnePendingRetry(){
        RecoveryBudget recovery=new RecoveryBudget(3);
        long first=recovery.request();
        assertTrue(first>0);
        assertEquals(RecoveryBudget.ALREADY_PENDING,recovery.request());
        assertEquals(RecoveryBudget.ALREADY_PENDING,recovery.request());
        assertTrue(recovery.consume(first));
        assertFalse(recovery.consume(first));
        long second=recovery.request();assertTrue(recovery.consume(second));
        long third=recovery.request();assertTrue(recovery.consume(third));
        assertEquals(RecoveryBudget.EXHAUSTED,recovery.request());
    }
    @Test public void repeatedFailedRestartsStopAtTheBudget(){
        RecoveryBudget recovery=new RecoveryBudget(3);
        for(int i=0;i<3;i++){long ticket=recovery.request();assertTrue(ticket>0);assertTrue(recovery.consume(ticket));}
        assertEquals(RecoveryBudget.EXHAUSTED,recovery.request());
        assertFalse(recovery.consume(RecoveryBudget.EXHAUSTED));
    }
    @Test public void explicitStopInvalidatesDelayedRecoveryEvenAfterNewOptIn(){
        RecoveryBudget recovery=new RecoveryBudget(3);
        long beforeStop=recovery.request();
        recovery.cancel();
        assertFalse(recovery.consume(beforeStop));
        recovery.reset();
        long afterOptIn=recovery.request();
        assertFalse(recovery.consume(beforeStop));
        assertTrue(recovery.consume(afterOptIn));
    }
    @Test public void manualRestartInvalidatesOldCallbackWithoutClearingFailureBudget(){
        RecoveryBudget recovery=new RecoveryBudget(1);
        long stale=recovery.request();
        recovery.cancel();
        assertFalse(recovery.consume(stale));
        assertEquals(RecoveryBudget.EXHAUSTED,recovery.request());
    }
    @Test public void healthyAudioStartsANewFailureBudgetAndInvalidatesOldCallback(){
        RecoveryBudget recovery=new RecoveryBudget(1);
        long stale=recovery.request();
        recovery.reset();
        long next=recovery.request();
        assertFalse(recovery.consume(stale));
        assertTrue(recovery.consume(next));
        assertEquals(RecoveryBudget.EXHAUSTED,recovery.request());
    }
}

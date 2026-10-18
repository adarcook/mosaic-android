package life.mosaic.tensorllm;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class WorkerReleaseTest {
    private static final class Harness implements WorkerRelease.Endpoint,WorkerRelease.Queue,WorkerRelease.Result {
        final List<String> events=new ArrayList<>();final List<Runnable> posted=new ArrayList<>();Runnable death,timer;
        boolean registered=true,stopThrows,watchThrows;int completed,failed;String evidence;
        WorkerRelease gate=new WorkerRelease(this,this,this);
        public boolean watch(Runnable callback)throws Exception{events.add("watch");if(watchThrows)throw new Exception("watch failed");death=callback;return registered;}
        public void stop()throws Exception{events.add("stop");if(stopThrows)throw new Exception("send failed");}
        public void unbind(){events.add("unbind");}
        public void unwatch(){events.add("unwatch");}
        public void post(Runnable action){posted.add(action);}
        public void later(Runnable action,long delay){assertEquals(8000,delay);timer=action;}
        public void remove(Runnable action){if(timer==action)timer=null;}
        public void released(String value){completed++;evidence=value;}
        public void failed(String value){failed++;evidence=value;}
        public void stopError(Exception error){events.add("stop_error");}
        void drain(){List<Runnable> copy=new ArrayList<>(posted);posted.clear();for(Runnable r:copy)r.run();}
    }
    @Test public void observesDeathBeforeStopAndAdvancesWithoutPollingCachedAliveState(){
        Harness h=new Harness();h.gate.start();assertEquals(Arrays.asList("watch","stop","unbind"),h.events);assertEquals(0,h.completed);
        h.death.run();h.drain();assertEquals(1,h.completed);assertEquals("death_notification",h.evidence);assertNull(h.timer);
    }
    @Test public void alreadyDeadRegistrationAdvancesWithoutSendingStop(){Harness h=new Harness();h.registered=false;h.gate.start();assertEquals(1,h.completed);assertEquals("already_dead_at_registration",h.evidence);assertFalse(h.events.contains("stop"));assertTrue(h.events.contains("unbind"));}
    @Test public void timeoutNeverAdvancesAndLateDeathIsIgnored(){Harness h=new Harness();h.gate.start();h.timer.run();h.death.run();h.drain();assertEquals(1,h.failed);assertEquals(0,h.completed);}
    @Test public void cancellationDetachesAndIgnoresQueuedDeath(){Harness h=new Harness();h.gate.start();h.death.run();h.gate.cancel();h.drain();assertNull(h.timer);assertEquals(0,h.completed);assertEquals(0,h.failed);}
    @Test public void sendErrorStillRequiresDeathEvidence(){Harness h=new Harness();h.stopThrows=true;h.gate.start();assertEquals(0,h.completed);assertTrue(h.events.contains("stop_error"));h.death.run();h.drain();assertEquals(1,h.completed);}
    @Test public void duplicateDeathAndTimeoutCompleteOnlyOnce(){Harness h=new Harness();h.gate.start();Runnable timeout=h.timer;h.death.run();h.death.run();h.drain();timeout.run();assertEquals(1,h.completed);assertEquals(0,h.failed);}
    @Test public void registrationFailureAttemptsStopButNeverAdvances(){Harness h=new Harness();h.watchThrows=true;h.gate.start();assertEquals(1,h.failed);assertEquals(0,h.completed);assertTrue(h.events.contains("stop"));assertTrue(h.events.contains("unbind"));}
}

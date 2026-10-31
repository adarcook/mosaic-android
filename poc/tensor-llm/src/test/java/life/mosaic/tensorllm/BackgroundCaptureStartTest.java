package life.mosaic.tensorllm;
import org.junit.Test;import static org.junit.Assert.*;
public class BackgroundCaptureStartTest {
    @Test public void doesNotCaptureBeforeForegroundServiceIsPromoted(){BackgroundCaptureStart g=new BackgroundCaptureStart();long t=g.request();assertEquals(BackgroundCaptureStart.Result.WAIT,g.observe(t,true,false,false));assertEquals(BackgroundCaptureStart.Result.START,g.observe(t,true,true,false));assertEquals(BackgroundCaptureStart.Result.STALE,g.observe(t,true,true,false));}
    @Test public void resumeOrNewIntentCannotDuplicatePendingStart(){BackgroundCaptureStart g=new BackgroundCaptureStart();long t=g.request();assertTrue(t>0);assertEquals(0,g.request());assertEquals(BackgroundCaptureStart.Result.START,g.observe(t,true,true,false));}
    @Test public void explicitStopInvalidatesLatePromotion(){BackgroundCaptureStart g=new BackgroundCaptureStart();long old=g.request();g.cancel();long next=g.request();assertEquals(BackgroundCaptureStart.Result.STALE,g.observe(old,true,true,false));assertEquals(BackgroundCaptureStart.Result.START,g.observe(next,true,true,false));}
    @Test public void lossOfWakeAuthorizationAbortsEvenAfterPromotion(){BackgroundCaptureStart g=new BackgroundCaptureStart();long t=g.request();assertEquals(BackgroundCaptureStart.Result.ABORT,g.observe(t,false,true,false));assertEquals(BackgroundCaptureStart.Result.STALE,g.observe(t,true,true,false));}
    @Test public void promotionTimeoutCannotBeRevivedByDelayedCallback(){BackgroundCaptureStart g=new BackgroundCaptureStart();long t=g.request();assertEquals(BackgroundCaptureStart.Result.ABORT,g.observe(t,true,false,true));assertEquals(BackgroundCaptureStart.Result.STALE,g.observe(t,true,true,false));}
}

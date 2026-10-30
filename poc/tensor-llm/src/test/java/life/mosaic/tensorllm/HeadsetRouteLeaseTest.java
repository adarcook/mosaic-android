package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeadsetRouteLeaseTest {
    static final class Fake implements HeadsetRouteLease.Port {
        boolean focus = true, select = true, throwing;
        int selections, releases, stopped;
        final HeadsetRouteLease lease = new HeadsetRouteLease(this, () -> stopped++);
        public boolean acquireFocus() { return focus; }
        public boolean select(int id) { selections++; if (throwing) throw new SecurityException(); return select; }
        public void release() { releases++; }
    }
    @Test public void acceptedRequestWaitsForMatchingRoute() {
        Fake f = new Fake(); long t = f.lease.start(7);
        assertEquals(HeadsetRouteLease.State.SELECTING, f.lease.state());
        f.lease.selected(t, 2);
        assertEquals(HeadsetRouteLease.State.SELECTING, f.lease.state());
        f.lease.selected(t, 7);
        assertEquals(HeadsetRouteLease.State.SELECTED, f.lease.state());
        f.lease.timeout(t); assertEquals(0, f.stopped);
    }
    @Test public void focusDeniedNeverSelects() {
        Fake f = new Fake(); f.focus = false; f.lease.start(7);
        assertEquals(0, f.selections); assertEquals(1, f.releases);
        assertEquals(HeadsetRouteLease.State.STOPPED, f.lease.state());
    }
    @Test public void unavailableRouteCleansUp() {
        Fake f = new Fake(); f.select = false; f.lease.start(7);
        assertEquals(1, f.releases); assertEquals(1, f.stopped);
    }
    @Test public void timeoutRejectsLateSelection() {
        Fake f = new Fake(); long t = f.lease.start(7); f.lease.timeout(t);
        f.lease.selected(t, 7); assertEquals(HeadsetRouteLease.State.STOPPED, f.lease.state());
    }
    @Test public void disconnectStopsRatherThanFallingBackToPhone() {
        Fake f = new Fake(); long t = f.lease.start(7); f.lease.selected(t, 7);
        f.lease.selected(t, -1); f.lease.selected(t, 7);
        assertEquals(HeadsetRouteLease.State.STOPPED, f.lease.state()); assertEquals(1, f.stopped);
    }
    @Test public void oldCallbacksCannotStopExplicitReconnect() {
        Fake f = new Fake(); long old = f.lease.start(7); f.lease.close();
        long next = f.lease.start(9); f.lease.interrupted(old); f.lease.timeout(old); f.lease.selected(old, 7);
        assertEquals(HeadsetRouteLease.State.SELECTING, f.lease.state());
        f.lease.selected(next, 9); assertEquals(HeadsetRouteLease.State.SELECTED, f.lease.state());
    }
    @Test public void focusLossAndRepeatedStopReleaseOnce() {
        Fake f = new Fake(); long t = f.lease.start(7); f.lease.selected(t, 7);
        f.lease.interrupted(t); f.lease.close(); f.lease.interrupted(t);
        assertEquals(1, f.releases); assertEquals(1, f.stopped);
    }
    @Test public void permissionRevocationReleasesBeforePropagating() {
        Fake f = new Fake(); f.throwing = true;
        try { f.lease.start(7); fail(); } catch (SecurityException expected) { }
        assertEquals(1, f.releases); assertEquals(1, f.stopped);
    }
    @Test public void activeLeaseCannotBeOverwritten() {
        Fake f = new Fake(); f.lease.start(7);
        try { f.lease.start(9); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, f.selections);
    }
    @Test public void stopAudioFailureStillReleasesRoute() {
        Fake f = new Fake(); HeadsetRouteLease lease = new HeadsetRouteLease(f, () -> { throw new IllegalStateException(); });
        lease.start(7);
        try { lease.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, f.releases);
    }
}

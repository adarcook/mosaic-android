package life.mosaic.tensorllm;

import java.util.concurrent.atomic.AtomicInteger;

/** A cancelled turn can never accept a late cue completion as microphone readiness. */
final class CaptureGate {
    private final AtomicInteger state;
    CaptureGate(boolean waitingForCue) { state=new AtomicInteger(waitingForCue?0:1); }
    void ready() { state.compareAndSet(0,1); }
    void cancel() { state.set(2); }
    boolean accepting() { return state.get()==1; }
    boolean cancelled() { return state.get()==2; }
}

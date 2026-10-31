package life.mosaic.tensorllm;

/** A wake may start heavy workers only after both the wake event and process death. */
final class WakeHandoff {
    private boolean wake, dead, consumed, cancelled, ackPending;
    void result(String state) { if ("wake".equals(state) && !cancelled) wake=true; else if ("error".equals(state)||"stopped".equals(state)) cancelled=true; }
    void ackStarted(){ackPending=true;}
    void ackCompleted(){ackPending=false;}
    boolean dispatched(){return consumed;}
    void died() { dead=true; }
    boolean takeReady() { if(!wake||!dead||consumed||cancelled||ackPending)return false;consumed=true;return true; }
}

package life.mosaic.tensorllm;

/** A wake may start heavy workers only after both the wake event and process death. */
final class WakeHandoff {
    private boolean wake, dead, consumed, cancelled;
    void result(String state) { if ("wake".equals(state) && !cancelled) wake=true; else if ("error".equals(state)||"stopped".equals(state)) cancelled=true; }
    void died() { dead=true; }
    boolean takeReady() { if(!wake||!dead||consumed||cancelled)return false;consumed=true;return true; }
}

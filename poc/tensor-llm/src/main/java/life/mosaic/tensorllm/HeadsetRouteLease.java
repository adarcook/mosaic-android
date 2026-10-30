package life.mosaic.tensorllm;

/** Serialized on the owning service thread. Never treats route selection as microphone proof. */
final class HeadsetRouteLease implements AutoCloseable {
    enum State { IDLE, SELECTING, SELECTED, STOPPED }
    interface Port {
        boolean acquireFocus();
        boolean select(int sinkId);
        void release();
    }
    private final Port port;
    private final Runnable stopAudio;
    private State state = State.IDLE;
    private int sinkId;
    private long generation;

    HeadsetRouteLease(Port port, Runnable stopAudio) {
        this.port = port;
        this.stopAudio = stopAudio;
    }
    State state() { return state; }
    long generation() { return generation; }

    // Caller supplies a currently enumerated HFP/LE headset sink, never a name/address.
    long start(int selectedSinkId) {
        if (state != State.IDLE && state != State.STOPPED) throw new IllegalStateException("Route already active");
        sinkId = selectedSinkId;
        state = State.SELECTING;
        long ticket = ++generation;
        try {
            if (!port.acquireFocus() || !port.select(sinkId)) close();
        } catch (RuntimeException error) {
            close();
            throw error;
        }
        return ticket;
    }
    void selected(long ticket, int actualSinkId) {
        if (ticket != generation || state == State.STOPPED || state == State.IDLE) return;
        if (actualSinkId == sinkId) state = State.SELECTED;
        else if (state == State.SELECTED) close();
    }
    void timeout(long ticket) {
        if (ticket == generation && state == State.SELECTING) close();
    }
    void interrupted(long ticket) {
        if (ticket == generation) close();
    }
    @Override public void close() {
        if (state == State.IDLE || state == State.STOPPED) return;
        state = State.STOPPED;
        ++generation; // Invalidates delayed focus, route and timeout callbacks.
        try { stopAudio.run(); } finally { port.release(); }
    }
}

package life.mosaic.tensorllm;

/** A local read-only capability. Matching must not read private data or execute the tool. */
interface VoiceTool {
    String id();
    String route();
    boolean matches(String request);

    /** The adapter must check current Android permissions and device lock on every call. */
    Result execute();

    enum Status { OK, PERMISSION_DENIED, LISTENER_UNAVAILABLE, DEVICE_LOCKED, NO_MESSAGE, CONTENT_UNAVAILABLE, READ_ERROR }

    /** Ephemeral payload: only status and tool identity belong in diagnostics. */
    final class Result {
        final Status status;
        final String displayed, spoken;

        Result(Status status, String displayed, String spoken) {
            this.status = java.util.Objects.requireNonNull(status);
            this.displayed = java.util.Objects.requireNonNull(displayed);
            this.spoken = java.util.Objects.requireNonNull(spoken);
        }
    }
}

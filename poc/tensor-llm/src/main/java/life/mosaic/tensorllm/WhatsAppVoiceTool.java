package life.mosaic.tensorllm;

import java.util.Objects;
import java.util.function.Supplier;

/** Adapts the existing permission-scoped Android reader to the voice capability contract. */
final class WhatsAppVoiceTool implements VoiceTool {
    private final Supplier<WhatsAppNotificationTool.Result> reader;

    WhatsAppVoiceTool(Supplier<WhatsAppNotificationTool.Result> reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public String id() { return WhatsAppNotificationTool.ID; }
    public String route() { return "whatsapp_latest_notification"; }
    public boolean matches(String request) { return WhatsAppReadIntent.matches(request); }

    public Result execute() {
        WhatsAppNotificationTool.Result result = reader.get();
        return new Result(Status.valueOf(result.status.name()),
                WhatsAppNotificationTool.displayed(result), WhatsAppNotificationTool.spoken(result));
    }
}

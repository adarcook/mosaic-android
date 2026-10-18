package life.mosaic.tensorllm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable explicit registry. Routing never executes a capability or retains the request. */
final class VoiceToolRouter {
    private final List<VoiceTool> tools;

    VoiceToolRouter(List<VoiceTool> tools) {
        this.tools = List.copyOf(tools);
        Set<String> ids = new HashSet<>(), routes = new HashSet<>();
        for (VoiceTool tool : this.tools) {
            if (tool.id() == null || tool.id().isBlank() || tool.route() == null ||
                    tool.route().isBlank() || tool.route().equals("conversation") ||
                    !ids.add(tool.id()) || !routes.add(tool.route())) {
                throw new IllegalArgumentException("Tool identities and routes must be unique");
            }
        }
    }

    /** Null means normal conversation; ambiguous matches fail before any private read. */
    VoiceTool route(String request) {
        if (request == null || request.isBlank()) return null;
        VoiceTool selected = null;
        for (VoiceTool tool : tools) {
            if (!tool.matches(request)) continue;
            if (selected != null) throw new IllegalStateException("Ambiguous local tool request");
            selected = tool;
        }
        return selected;
    }
}

package life.mosaic.tensorllm;

import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One model decision, at most one validated native call, then a model-written final answer. */
final class ManualToolAgent {
    interface Model { Reply generate(Message message) throws Exception; }
    interface Executor { VoiceTool.Result execute(String function, Map<String, ?> arguments) throws Exception; }

    static final class Reply {
        final String text;
        final List<ToolCall> calls;
        Reply(String text, List<ToolCall> calls) {
            this.text = java.util.Objects.requireNonNull(text);
            this.calls = List.copyOf(calls);
        }
    }

    static String run(String request, Model model, Executor executor, BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        Reply reply = model.generate(Message.Companion.user(request));
        check(cancelled);
        if (reply.calls.isEmpty()) return answer(reply);
        if (reply.calls.size() != 1) throw new IllegalArgumentException("אפשר להפעיל כלי אחד בלבד בפנייה זו");
        ToolCall call = reply.calls.get(0);
        AgentTools.validate(call.getName(), call.getArguments());
        if (VoiceCommandText.blocked(VoiceCommandText.normalize(request)))
            throw new IllegalArgumentException("בקשת הכלי אינה מאשרת קריאת מידע");
        check(cancelled);
        VoiceTool.Result result = executor.execute(call.getName(), call.getArguments());
        check(cancelled);
        // Tool data has its own role and is never concatenated into system/user instructions.
        Message toolMessage = Message.Companion.tool(Contents.Companion.of(List.of(new Content.ToolResponse(
                call.getName(), Map.of("status", result.status.name(), "content", result.displayed)))));
        Reply finalReply = model.generate(toolMessage);
        check(cancelled);
        if (!finalReply.calls.isEmpty()) throw new IllegalArgumentException("המודל ביקש כלי נוסף מעבר לגבול הפנייה");
        return answer(finalReply);
    }

    private static String answer(Reply reply) {
        if (reply.text.isBlank() || reply.text.length() > 1200)
            throw new IllegalArgumentException("המודל לא החזיר תשובה קצרה ותקינה");
        return reply.text.trim();
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new IllegalStateException("הפנייה בוטלה");
    }
}

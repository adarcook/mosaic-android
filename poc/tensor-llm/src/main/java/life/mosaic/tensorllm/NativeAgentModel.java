package life.mosaic.tensorllm;

import android.os.SystemClock;
import com.google.ai.edge.litertlm.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pinned LiteRT-LM structured callbacks; no parsing of text as executable JSON. */
final class NativeAgentModel implements ManualToolAgent.Model {
    private final Conversation conversation;
    private final long deadline;
    private long firstTextMs = -1;
    private final long started = SystemClock.elapsedRealtime();

    NativeAgentModel(Conversation conversation, long deadline) { this.conversation = conversation; this.deadline = deadline; }
    long firstTextMs() { return firstTextMs; }

    public ManualToolAgent.Reply generate(com.google.ai.edge.litertlm.Message input) throws Exception {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        conversation.sendMessageAsync(input, new MessageCallback() {
            public void onMessage(com.google.ai.edge.litertlm.Message chunk) {
                synchronized (text) {
                    String value = chunk.toString();
                    if (!value.isEmpty() && firstTextMs < 0) firstTextMs = SystemClock.elapsedRealtime() - started;
                    if (text.length() + value.length() > 12000 || calls.size() + chunk.getToolCalls().size() > 1) {
                        failure.compareAndSet(null, new IOException("Model output limit"));
                        done.countDown();
                        return;
                    }
                    text.append(value);
                    calls.addAll(chunk.getToolCalls());
                }
            }
            public void onDone() { done.countDown(); }
            public void onError(Throwable error) { failure.compareAndSet(null, error); done.countDown(); }
        });
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || !done.await(remaining, TimeUnit.MILLISECONDS) || failure.get() != null)
            throw new IOException("המודל לא השלים החלטה תקינה בזמן");
        synchronized (text) { return new ManualToolAgent.Reply(text.toString(), calls); }
    }

    static List<ToolProvider> providers() {
        return List.of(ToolKt.tool(new OpenApiTool() {
            public String getToolDescriptionJsonString() { return AgentTools.schema(); }
            public String execute(String ignored) { throw new IllegalStateException("Automatic tool execution is disabled"); }
        }));
    }
}

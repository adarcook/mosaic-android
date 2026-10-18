package life.mosaic.tensorllm;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A single-use correlated reply; stale, duplicate, malformed and late replies cannot advance it. */
final class AgentToolExchange implements AutoCloseable {
    final String id = UUID.randomUUID().toString();
    private final CountDownLatch ready = new CountDownLatch(1);
    private VoiceTool.Result result;
    private boolean closed;

    synchronized boolean complete(String replyId, String status, String content) {
        if (closed || result != null || !id.equals(replyId) || content == null || content.isBlank() || content.length() > 1200) return false;
        try { result = new VoiceTool.Result(VoiceTool.Status.valueOf(status), content, content); }
        catch (RuntimeException invalid) { return false; }
        ready.countDown();
        return true;
    }

    VoiceTool.Result await(long milliseconds) throws Exception {
        if (!ready.await(milliseconds, TimeUnit.MILLISECONDS)) {
            close();
            throw new IOException("Tool response timeout");
        }
        synchronized (this) {
            if (closed || result == null) throw new IOException("Tool response cancelled");
            return result;
        }
    }

    public synchronized void close() { closed = true; result = null; ready.countDown(); }
}

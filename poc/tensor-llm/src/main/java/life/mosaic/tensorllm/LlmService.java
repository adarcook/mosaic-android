package life.mosaic.tensorllm;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.google.ai.edge.litertlm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** A foreground warm engine: NPU requested explicitly, fresh conversations, no fallback or tools. */
public final class LlmService extends Service {
    private volatile boolean busy;
    private Engine engine;private life.mosaic.models.FileSnapshot snapshot;
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == 9) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        if (message.what == 1 && !busy) {
            busy = true; Messenger reply = message.replyTo; Bundle data = new Bundle(message.getData());
            send(reply, 1, "", android.os.Process.myPid());
            new Thread(() -> run(reply, data), "mosaic-llm").start();
        }
        return true;
    }));
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_CRITICAL)android.os.Process.killProcess(android.os.Process.myPid());}
    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }

    private void run(Messenger reply, Bundle data) {
        Conversation conversation = null; File journal = null;
        try {
            if (!"Google".equalsIgnoreCase(Build.MANUFACTURER)
                    || !Build.SOC_MODEL.toLowerCase(java.util.Locale.ROOT).contains("tensor g5"))
                throw new IOException("ניסוי זה דורש Google Tensor G5");
            if(data.getBoolean("prepare",false)){Bundle ready=ensureEngine(reply,null,data.getString("foreground_session",""));ready.putBoolean("prepared",true);life.mosaic.models.WarmMemory.add(this,ready);busy=false;send(reply,4,"מנוע Gemma מוכן",0,ready);return;}
            String text = data.getString("text", ""), question = data.getString("question", "");
            boolean voice = data.getBoolean("voice", false);
            if (text.trim().isEmpty() || text.length() > ModelSpec.MAX_TEXT || question.length() > ModelSpec.MAX_QUESTION)
                throw new IOException("תמליל או שאלה מעבר לגבולות הניסוי");
            File directory = new File(getFilesDir(), "llm-sessions");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("לא ניתן לשמור תשובה");
            journal = new File(directory, "llm-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".jsonl");
            append(journal, new JSONObject().put("type", "start").put("build", BuildConfig.VERSION_NAME)
                    .put("runtime", "0.18.0").put("dispatch_source", "gallery-1.0.20-tensor-g5")
                    .put("dispatch_sha256", ModelSpec.DISPATCH_SHA256).put("requested_backend", "NPU").put("fallback", false)
                    .put("source_sha256", data.getString("source_sha256", "manual"))
                    .put("reviewed_text_sha256", hash(text.getBytes(StandardCharsets.UTF_8)))
                    .put("voice_mode",voice).put("system_prompt_sha256",hash((voice?ModelSpec.VOICE_SYSTEM:ModelSpec.SYSTEM).getBytes(StandardCharsets.UTF_8)))
                    .put("text", text).put("question", question).put("context_tokens", "model_default").put("max_output_tokens", 512));
            Bundle engineMetrics=ensureEngine(reply,journal,data.getString("foreground_session",""));
            long verifyMs=engineMetrics.getLong("verify_ms"),loadMs=engineMetrics.getLong("load_ms");
            conversation = engine.createConversation(new ConversationConfig(
                    Contents.Companion.of(voice ? ModelSpec.VOICE_SYSTEM : ModelSpec.SYSTEM), Collections.emptyList(), Collections.emptyList(),
                    new SamplerConfig(20, 0.9, 0.2, 0), false, Collections.emptyList(), Collections.emptyMap(),
                    null, false, 512, new ThinkingConfig(false), false, false, null));
            send(reply, 2, "מייצר תשובה מקומית…", 0);
            final StringBuilder response = new StringBuilder();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final long generateStart = SystemClock.elapsedRealtime();
            final long[] firstChunkMs = {-1};
            String prompt = "התמליל לבדיקה:\n<transcript>\n" + text + "\n</transcript>\n"
                    + "בקשת המשתמש: " + (question.trim().isEmpty() ? "סכם את השיחה והצג נקודות עיקריות ומשימות שהוזכרו." : question);
            if (voice) prompt = text;
            conversation.sendMessageAsync(prompt, new MessageCallback() {
                @Override public void onMessage(com.google.ai.edge.litertlm.Message chunk) {
                    String value = chunk.toString();
                    if (!value.isEmpty() && firstChunkMs[0] < 0) firstChunkMs[0] = SystemClock.elapsedRealtime() - generateStart;
                    response.append(value);
                    // Bound Binder/UI memory even for a malformed runtime response.
                    if (response.length() > 24000) { failure.set(new IOException("תשובת המודל חרגה מהגבול")); done.countDown(); return; }
                    send(reply, 3, response.toString(), 0);
                }
                @Override public void onDone() { done.countDown(); }
                @Override public void onError(Throwable error) { failure.set(error); done.countDown(); }
            });
            if (!done.await(150, TimeUnit.SECONDS)) throw new IOException("TIMEOUT — לא התקבלה תשובה בזמן הניסוי");
            if (failure.get() != null) throw new IOException("יצירת התשובה נכשלה", failure.get());
            if (response.toString().trim().isEmpty()) throw new IOException("המודל החזיר תשובה ריקה");
            long generateMs = SystemClock.elapsedRealtime() - generateStart;
            send(reply,2,"מסיים פנייה ושומר מנוע חם…",0);append(journal,new JSONObject().put("type","phase").put("phase","conversation_cleanup").put("at_ms",System.currentTimeMillis()));
            long cleanupStart=SystemClock.elapsedRealtime();conversation.close();conversation=null;
            long conversationCleanupMs=SystemClock.elapsedRealtime()-cleanupStart;
            android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory);
            append(journal, new JSONObject().put("type", "end").put("state", "complete").put("answer", response.toString())
                    .put("generate_ms", generateMs).put("first_text_chunk_ms", firstChunkMs[0])
                    .put("pss_kb", memory.getTotalPss()).put("thermal_status", ((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus())
                    .put("cleanup_pending", true));
            Bundle terminal = new Bundle(engineMetrics);terminal.putLong("conversation_cleanup_ms",conversationCleanupMs); terminal.putString("answer", response.toString());
            terminal.putLong("verify_ms",verifyMs);terminal.putInt("worker_pss_kb",memory.getTotalPss());terminal.putInt("thermal_status",((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus());
            terminal.putLong("load_ms", loadMs); terminal.putLong("generate_ms", generateMs);
            terminal.putLong("first_text_chunk_ms", firstChunkMs[0]); terminal.putString("journal", journal.getName());
            snapshot.requireUnchanged();life.mosaic.models.WarmMemory.add(this,terminal);busy=false;send(reply, 4, response + "\n\nטעינה: " + loadMs + " ms | תשובה: " + generateMs
                    + " ms | טקסט ראשון: " + firstChunkMs[0] + " ms\nנשמר: " + journal.getName()
                    + "\nBackend מבוקש: NPU; ללא fallback", 0, terminal);
        } catch (Throwable error) {
            if (journal != null) try { append(journal, new JSONObject().put("type", "end").put("state", "error")
                    .put("error", error.toString()).put("cleanup_pending", true)); } catch (Exception ignored) { }
            busy=false;send(reply, 5, "המודל לא השלים תשובה: " + error + (journal == null ? "" : "\nיומן: " + journal.getName()), 0);
        } finally {
            // Warm voice requests close each conversation before accepting another; errors are disposed by the parent.
            if (conversation != null) try { conversation.close(); } catch (Throwable ignored) { }

        }
    }
    @Override public boolean onUnbind(Intent intent){android.os.Process.killProcess(android.os.Process.myPid());return false;}
    private Bundle ensureEngine(Messenger reply,File journal,String session)throws Exception {
        Bundle metrics=new Bundle();metrics.putBoolean("engine_reused",engine!=null);metrics.putString("verification_mode",engine!=null?"loaded_engine_metadata":"full_sha256");
        if(engine!=null){life.mosaic.models.WarmMemory.beforeInference(this);snapshot.requireUnchanged();metrics.putLong("verify_ms",0);metrics.putLong("load_ms",0);return metrics;}
        life.mosaic.models.WarmMemory.beforeLoad(this);
            File dispatch = new File(getApplicationInfo().nativeLibraryDir, "libLiteRtDispatch_GoogleTensor.so");
            File model = new File(getExternalFilesDir(null), ModelSpec.FILE);
            life.mosaic.models.FileSnapshot before=new life.mosaic.models.FileSnapshot(model,dispatch);
            send(reply, 2, "בודק אימות מודלים…", 0);
            long verifyStart=SystemClock.elapsedRealtime();
            boolean cached=life.mosaic.models.SessionVerification.verify(new File(getFilesDir(),"voice-llm-verification.properties"),session,ModelSpec.SHA256+"|"+ModelSpec.DISPATCH_SHA256,new File[]{model,dispatch},()->{
                if(model.length()!=ModelSpec.BYTES||!ModelSpec.SHA256.equals(fileHash(model)))throw new IOException("Gemma model identity mismatch");
                if(!ModelSpec.DISPATCH_SHA256.equals(fileHash(dispatch)))throw new IOException("Tensor dispatch identity mismatch");
            });
            metrics.putString("verification_mode",cached?"session_receipt_metadata":"full_sha256");
            before.requireUnchanged();long verifyMs=SystemClock.elapsedRealtime()-verifyStart;
            if(journal!=null)append(journal, new JSONObject().put("type", "verified").put("model_sha256", ModelSpec.SHA256)
                    .put("verify_ms", SystemClock.elapsedRealtime() - verifyStart));
            send(reply, 2, "טוען Gemma 4 על TPU…", 0);
            long loadStart = SystemClock.elapsedRealtime();
            engine = new Engine(new EngineConfig(model.getAbsolutePath(), new Backend.NPU(getApplicationInfo().nativeLibraryDir),
                    null, null, null, null, getCacheDir().getAbsolutePath()));
            if(journal!=null)append(journal, new JSONObject().put("type", "phase").put("phase", "engine_initialize")
                    .put("at_ms", System.currentTimeMillis()));
            android.util.Log.i("MosaicLocalLlm", "phase=engine_initialize backend=NPU");
            engine.initialize();
            long loadMs = SystemClock.elapsedRealtime() - loadStart;before.requireUnchanged();snapshot=before;
            if(journal!=null)append(journal, new JSONObject().put("type", "loaded").put("load_ms", loadMs));
        metrics.putLong("verify_ms",verifyMs);metrics.putLong("load_ms",loadMs);return metrics;
    }
    private static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return TranscriptSource.hex(digest.digest());
    }
    private static String hash(byte[] bytes) throws Exception { return TranscriptSource.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void append(File file, JSONObject event) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write((event + "\n").getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
    }
    private static void send(Messenger reply, int what, String text, int pid) {
        send(reply, what, text, pid, new Bundle());
    }
    private static void send(Messenger reply, int what, String text, int pid, Bundle bundle) {
        if (reply == null) return;
        android.os.Message message = android.os.Message.obtain(null, what); message.arg1 = pid;
        bundle.putString("text", text); message.setData(bundle);
        try { reply.send(message); } catch (RemoteException ignored) { }
    }
}

package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Record first, then decode saved PCM. A killed attempt can resume its committed prefix. */
public final class ArchiveGateService extends Service {
    private volatile boolean stopping, aborting;
    private boolean started;
    private Messenger reply;
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == 9) { stopping = true; return true; }
        if (message.what == 10) { aborting = true; stopping = true; return true; }
        if (message.what == 1 && !started && message.replyTo != null) {
            started = true; reply = message.replyTo;
            String saved = message.getData().getString("session", "");
            int beam = message.getData().getInt("beam_size", 5);
            Message pid = Message.obtain(null, 1); pid.arg1 = android.os.Process.myPid();
            try { reply.send(pid); } catch (RemoteException e) { android.os.Process.killProcess(android.os.Process.myPid()); }
            new Thread(() -> runSession(saved, beam), "mosaic-archive").start();
        }
        return true;
    }));

    private void send(int code, String text) {
        Message message = Message.obtain(null, code); Bundle data = new Bundle(); data.putString("text", text); message.setData(data);
        try { reply.send(message); } catch (RemoteException e) { aborting = true; stopping = true; }
    }

    private static void record(ArchiveJournal journal, JSONObject data) throws Exception {
        journal.append(data.toString());
    }
    private static void phase(ArchiveJournal journal, String value) throws Exception {
        record(journal, new JSONObject().put("type", "phase").put("phase", value).put("at_ms", System.currentTimeMillis()));
    }

    private void capture(ArchivePcm pcm, ArchiveJournal journal, String id) throws Exception {
        phase(journal, "capture_begin");
        int min = AudioRecord.getMinBufferSize(StreamWindow.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("Microphone buffer unavailable");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, StreamWindow.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32000));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            if (stopping || aborting) return;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start");
            send(7, "מקליט ושומר אודיו; התמלול יתחיל אחרי עצירה.\n" + id);
            short[] block = new short[1600]; long limit = StreamWindow.RATE * 600L, lastStatus = 0;
            while (!stopping && !aborting && pcm.samples() < limit) {
                int n = recorder.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) throw new IOException("Microphone read failed: " + n);
                int keep = (int)Math.min(n, limit - pcm.samples());
                pcm.append(block, keep); // Write and sync before acknowledging any samples.
                long saved = pcm.samples();
                if (saved - lastStatus >= StreamWindow.RATE) {
                    lastStatus = saved;
                    send(4, "נשמרו " + String.format(Locale.ROOT, "%.1f", saved / 16000.0) + " שניות אודיו. אין תור תמלול בזמן ההקלטה.");
                }
            }
        } finally {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            recorder.release();
        }
        record(journal, new JSONObject().put("type", "capture_end").put("captured_samples", pcm.samples())
                .put("reason", aborting ? "cancelled" : stopping ? "stop" : "limit").put("at_ms", System.currentTimeMillis()));
        phase(journal, "capture_closed");
    }

    private String sha256(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] block = new byte[65536]; int n;
            while ((n = input.read(block)) != -1) {
                if (aborting) throw new IOException("Cancelled during artifact verification");
                digest.update(block, 0, n);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hash.toString();
    }

    private String modelIdentity() throws Exception {
        File dir = getExternalFilesDir(null);
        if (dir == null) throw new IOException("Model directory unavailable");
        StringBuilder result = new StringBuilder();
        for (String name : new String[]{"ivrit-whisper-decoder-q5_0.bin",
                "ivrit_whisper_encoder_Google_Tensor_G5.tflite", "ivrit_whisper_cross_attention_Google_Tensor_G5.tflite"}) {
            File file = new File(dir, name);
            result.append(name).append(':').append(file.length()).append(':').append(sha256(file)).append(';');
        }
        return result.toString();
    }

    private void runSession(String saved, int requestedBeam) {
        WarmHybridEngine engine = null;
        String id = saved;
        String error = "";
        int beam = requestedBeam, index = 0;
        long total = 0, processed = 0, compute = 0;
        StringBuilder text = new StringBuilder();
        boolean terminalSaved = false;
        String sealedHash = null, savedModels = null;
        try {
            if (beam != 1 && beam != 2 && beam != 5) throw new IOException("Invalid beam size");
            File dir = new File(getFilesDir(), "asr-sessions");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Session directory unavailable");
            boolean resume = !saved.isEmpty();
            if (!resume) id = "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
            if (!id.matches("session-[0-9]+-[0-9a-f-]{36}")) throw new IOException("Invalid session ID");
            File audio = new File(dir, id + ".pcm"), events = new File(dir, id + ".jsonl");
            if (resume && (!audio.isFile() || !events.isFile())) throw new IOException("Saved audio/journal unavailable; old streaming sessions have no audio");
            try (ArchivePcm pcm = new ArchivePcm(audio, !resume); ArchiveJournal journal = new ArchiveJournal(events)) {
                List<String> lines = journal.lines();
                if (!resume) {
                    record(journal, new JSONObject().put("type", "start").put("version", 3).put("mode", "archive")
                            .put("started_at_ms", System.currentTimeMillis()).put("window_s", 12).put("overlap_s", 1)
                            .put("beam_size", beam).put("cpu_threads", 2).put("build", BuildConfig.VERSION_NAME)
                            .put("engine_contract", "ivrit-tensor-cross-q5-v1").put("pcm_format", "s16le_mono_16000").put("audio_file", audio.getName()).put("max_capture_s", 600));
                } else {
                    if (lines.isEmpty()) throw new IOException("Journal header is missing; audio retained");
                    JSONObject header = new JSONObject(lines.get(0));
                    if (!"start".equals(header.getString("type")) || !"archive".equals(header.optString("mode"))
                            || header.getInt("version") != 3 || header.getInt("window_s") != 12 || header.getInt("overlap_s") != 1
                            || header.getInt("cpu_threads") != 2 || !"s16le_mono_16000".equals(header.getString("pcm_format"))
                            || !"ivrit-tensor-cross-q5-v1".equals(header.getString("engine_contract"))
                            || !audio.getName().equals(header.getString("audio_file"))) throw new IOException("Incompatible archive metadata");
                    beam = header.getInt("beam_size");
                    if (beam != 1 && beam != 2 && beam != 5) throw new IOException("Invalid saved beam size");
                    total = pcm.samples();
                    if (total > 16000L * 600) throw new IOException("Audio exceeds archive limit");
                    ArchiveCheckpoint checkpoint = new ArchiveCheckpoint(pcm, beam);
                    for (int i = 1; i < lines.size(); i++) {
                        JSONObject event = new JSONObject(lines.get(i));
                        if ("audio_sealed".equals(event.getString("type"))) sealedHash = event.getString("sha256");
                        if ("models".equals(event.getString("type"))) savedModels = event.getString("identity");
                        if (!"segment".equals(event.getString("type"))) continue;
                        checkpoint.accept(event.getInt("index"), event.getLong("start_sample"), event.getLong("end_sample"),
                                event.getInt("rate"), event.getInt("overlap_samples"), event.getInt("beam_size"), event.getLong("compute_ms"));
                        processed = checkpoint.processedEnd; compute = checkpoint.computeMs; index = checkpoint.nextIndex;
                        String merged = StreamText.append(text.toString(), event.getString("text")); text.setLength(0); text.append(merged);
                    }
                    record(journal, new JSONObject().put("type", "resume").put("at_ms", System.currentTimeMillis())
                            .put("next_index", index).put("captured_samples", total).put("build", BuildConfig.VERSION_NAME));
                }
                try {
                    if (!resume) capture(pcm, journal, id);
                    total = pcm.samples();
                    if (aborting) throw new IOException("Cancelled; saved audio can resume");
                    if (total == 0) throw new IOException("No audio was recorded");
                    phase(journal, "audio_verify_begin"); send(2, "האודיו שמור. בודק את ההקלטה…");
                    String actualHash = sha256(audio);
                    if (sealedHash != null && !sealedHash.equals(actualHash)) throw new IOException("Audio checksum changed; refusing to mix transcripts");
                    if (sealedHash == null) record(journal, new JSONObject().put("type", "audio_sealed")
                            .put("captured_samples", total).put("sha256", actualHash));
                    if (processed < total) {
                        phase(journal, "model_verify_begin"); send(2, "האודיו שמור. מאמת מודלים וטוען מנוע…\n" + id);
                        String actualModels = modelIdentity();
                        if (savedModels != null && !savedModels.equals(actualModels)) throw new IOException("Model checksums changed; cannot resume old decoder results");
                        if (savedModels == null) record(journal, new JSONObject().put("type", "models").put("identity", actualModels));
                        phase(journal, "model_load_begin");
                        engine = new WarmHybridEngine(getExternalFilesDir(null), getApplicationInfo().nativeLibraryDir);
                        record(journal, new JSONObject().put("type", "loaded").put("load_ms", engine.loadMs));
                    }
                    while (processed < total && !aborting) {
                        StreamWindow.Chunk chunk = pcm.chunk(index);
                        if (chunk == null) throw new IOException("Missing audio window");
                        record(journal, new JSONObject().put("type", "phase").put("phase", "decode_begin").put("index", index)
                                .put("tail", chunk.pcm.length < StreamWindow.WINDOW).put("at_ms", System.currentTimeMillis()));
                        send(5, "מתמלל מהקלטה שמורה: מקטע " + (index + 1) + (chunk.pcm.length < StreamWindow.WINDOW ? " (סיום)" : "") + "…");
                        WarmHybridEngine.Result result = engine.transcribe(ArchivePcm.inferenceInput(chunk), beam);
                        long pss = Debug.getPss(); int thermal = ((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
                        record(journal, new JSONObject().put("type", "segment").put("index", index)
                                .put("start_sample", chunk.startSample).put("end_sample", chunk.endSample).put("rate", StreamWindow.RATE)
                                .put("overlap_samples", chunk.overlapSamples).put("beam_size", beam).put("text", result.text).put("status", "provisional")
                                .put("mel_ms", result.melMs).put("encoder_ms", result.encoderMs).put("cross_ms", result.crossMs)
                                .put("decode_ms", result.decodeMs).put("compute_ms", result.totalMs).put("native_timings", result.nativeTimings)
                                .put("backlog_s", Math.max(0, total - chunk.endSample) / 16000.0).put("queue_depth", 0)
                                .put("pss_kb", pss).put("thermal_status", thermal).put("input_padding_samples", Math.max(0, 8000 - chunk.pcm.length)));
                        processed = chunk.endSample; compute += result.totalMs; index++;
                        String merged = StreamText.append(text.toString(), result.text); text.setLength(0); text.append(merged);
                        send(3, summary(id, beam, index, total, processed, compute, text, "תמלול בתהליך; האודיו שמור"));
                    }
                    if (aborting) error = "Cancelled; audio retained";
                } catch (Exception | Error e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                total = pcm.samples();
                record(journal, new JSONObject().put("type", "end").put("ended_at_ms", System.currentTimeMillis())
                        .put("state", error.isEmpty() && processed == total ? "complete" : "incomplete")
                        .put("captured_samples", total).put("processed_end_sample", processed)
                        .put("unprocessed_samples", Math.max(0, total - processed)).put("error", error)
                        .put("audio_retained", true).put("cleanup_pending", true));
                terminalSaved = true;
                // Deliver a durable terminal result BEFORE native teardown, which may block.
                send(6, summary(id, beam, index, total, processed, compute, text,
                        error.isEmpty() && processed == total ? "התמלול הסתיים; האודיו והתמליל נשמרו" : "התמלול לא הושלם: " + error));
            }
        } catch (Exception | Error e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            send(6, summary(id, beam, index, total, processed, compute, text, "שגיאה; קבצים קיימים נשמרו: " + error));
        } finally {
            // No persisted data depends on close completing. UI disposes this private process.
            if (terminalSaved) send(8, "תוצאת הסיום נשמרה; משחרר מנוע…");
            if (engine != null) engine.close();
            new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
        }
    }

    private static String summary(String id, int beam, int count, long total, long processed, long compute, StringBuilder text, String state) {
        return state + "\nDecoder: " + (beam == 1 ? "Greedy" : "Beam " + beam) + " | מקטעים: " + count
                + "\nאודיו שמור: " + String.format(Locale.ROOT, "%.2f", total / 16000.0) + " s"
                + " | טרם תומלל: " + String.format(Locale.ROOT, "%.2f", Math.max(0, total - processed) / 16000.0) + " s"
                + "\nRTF חישוב: " + (processed == 0 ? "—" : String.format(Locale.ROOT, "%.3f", compute / (processed / 16.0)))
                + "\nקובץ: " + id + ".jsonl\n\nתמלול זמני:\n" + text;
    }

    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public boolean onUnbind(Intent intent) {
        aborting = true; stopping = true; android.os.Process.killProcess(android.os.Process.myPid()); return false;
    }
}

package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground-screen diagnostic only. One engine and bounded capture queue in :stream. */
public final class StreamingGateService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<StreamWindow.Chunk> queue = new ArrayBlockingQueue<>(2);
    private final AtomicLong captured = new AtomicLong();
    private volatile boolean stopping, captureDone, aborting;
    private volatile String failure;
    private volatile AudioRecord recorder;
    private boolean started;
    private int beamSize = 2;
    private Messenger reply;
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) { stopCapture(); return true; }
        if (msg.what == 10) { aborting = true; stopCapture(); return true; }
        if (msg.what == 1 && !started && msg.replyTo != null) {
            int selectedBeam = msg.getData().getInt("beam_size", 2);
            if (selectedBeam != 1 && selectedBeam != 2 && selectedBeam != 5) return true;
            beamSize = selectedBeam;
            started = true; reply = msg.replyTo;
            Message pid = Message.obtain(null, 1); pid.arg1 = android.os.Process.myPid();
            try { reply.send(pid); } catch (Exception e) { android.os.Process.killProcess(android.os.Process.myPid()); }
            new Thread(this::session, "mosaic-stream-inference").start();
        }
        return true;
    }));

    private void send(int code, String text) {
        Message message = Message.obtain(null, code); Bundle bundle = new Bundle();
        bundle.putString("text", text); message.setData(bundle);
        try { reply.send(message); } catch (Exception e) { aborting = true; stopCapture(); }
    }

    private void stopCapture() {
        stopping = true;
        AudioRecord active = recorder;
        if (active != null) try { active.stop(); } catch (RuntimeException ignored) { }
    }

    private boolean enqueue(StreamWindow.Chunk chunk) {
        if (chunk == null) return true;
        if (chunk.pcm.length < StreamWindow.RATE / 2) {
            failure = "הקלטה קצרה מחצי שנייה"; return false;
        }
        if (!queue.offer(chunk)) {
            failure = "העיבוד לא עומד בקצב: התור התמלא. השיחה נעצרה; חלק מהאודיו לא תומלל.";
            stopping = true; return false;
        }
        return true;
    }

    private void capture() {
        StreamWindow window = new StreamWindow();
        AudioRecord active = null;
        try {
            int min = AudioRecord.getMinBufferSize(StreamWindow.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Microphone buffer unavailable");
            active = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, StreamWindow.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32000));
            recorder = active;
            if (active.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            if (stopping) return;
            active.startRecording();
            if (active.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Microphone did not start");
            short[] block = new short[1600];
            long limit = StreamWindow.RATE * 600L;
            long lastStatus = 0;
            while (!stopping && captured.get() < limit) {
                int n = active.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) { if (stopping) break; throw new IOException("Microphone read failed: " + n); }
                for (int i = 0; i < n && captured.get() < limit; i++) {
                    captured.incrementAndGet();
                    if (!enqueue(window.add(block[i]))) break;
                }
                if (captured.get() - lastStatus >= StreamWindow.RATE) {
                    lastStatus = captured.get();
                    send(4, "נקלטו " + captured.get() / StreamWindow.RATE + " שניות; ממתינים בתור: " + queue.size());
                }
            }
            if (failure == null && !aborting) {
                StreamWindow.Chunk tail = window.finish();
                if (tail != null && tail.pcm.length >= StreamWindow.RATE / 2) {
                    // Normal Stop drains queued chunks before committing the final tail.
                    if (!queue.offer(tail, 90, TimeUnit.SECONDS)) failure = "תור הסיום לא התרוקן בזמן; אודיו אחרון לא תומלל";
                } else if (tail != null) failure = "הקלטה קצרה מחצי שנייה";
            }
        } catch (Exception e) {
            if (!stopping) failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            recorder = null;
            if (active != null) { try { active.stop(); } catch (RuntimeException ignored) { } active.release(); }
            captureDone = true;
        }
    }

    private static void journal(FileOutputStream file, JSONObject event) throws Exception {
        file.write((event.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        file.flush(); file.getFD().sync();
    }

    private void session() {
        File directory = new File(getFilesDir(), "asr-sessions");
        File sessionFile = new File(directory, "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jsonl");
        StringBuilder transcript = new StringBuilder();
        long processedEnd = 0, computeMs = 0, newSamples = 0;
        int segments = 0;
        Thread captureThread = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Session directory unavailable");
            try (FileOutputStream file = new FileOutputStream(sessionFile)) {
                journal(file, new JSONObject().put("type", "start").put("version", 2)
                        .put("started_at_ms", System.currentTimeMillis()).put("window_s", 12)
                        .put("overlap_s", 1).put("beam_size", beamSize).put("cpu_threads", 2)
                        .put("build", BuildConfig.VERSION_NAME).put("max_capture_s", 600).put("state", "provisional"));
                try {
                    send(2, "טוען מודלים פעם אחת; ההקלטה תתחיל לאחר הטעינה…");
                    try (WarmHybridEngine engine = new WarmHybridEngine(getExternalFilesDir(null), getApplicationInfo().nativeLibraryDir)) {
                        if (stopping || aborting) { aborting = true; return; }
                        journal(file, new JSONObject().put("type", "loaded").put("load_ms", engine.loadMs));
                        send(7, "המודלים טעונים (" + engine.loadMs + " ms). מקליט; חלון ראשון עד 12 שניות.");
                        captureThread = new Thread(this::capture, "mosaic-stream-capture"); captureThread.start();
                        while (!aborting) {
                            StreamWindow.Chunk chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                            if (chunk == null) { if (captureDone) break; continue; }
                            send(5, "מתמלל מקטע " + (segments + 1) + "; ההקלטה ממשיכה במקביל…");
                            WarmHybridEngine.Result result = engine.transcribe(chunk.pcm, beamSize);
                            long lagSamples = Math.max(0, captured.get() - chunk.endSample);
                            long pssKb = Debug.getPss();
                            int thermal = ((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
                            JSONObject event = new JSONObject().put("type", "segment").put("index", segments)
                                    .put("start_sample", chunk.startSample).put("end_sample", chunk.endSample)
                                    .put("rate", StreamWindow.RATE).put("overlap_samples", chunk.overlapSamples)
                                    .put("text", result.text).put("status", "provisional")
                                    .put("mel_ms", result.melMs).put("encoder_ms", result.encoderMs)
                                    .put("cross_ms", result.crossMs).put("decode_ms", result.decodeMs)
                                    .put("native_timings", result.nativeTimings).put("beam_size", beamSize)
                                    .put("compute_ms", result.totalMs).put("backlog_s", lagSamples / 16000.0)
                                    .put("queue_depth", queue.size()).put("pss_kb", pssKb).put("thermal_status", thermal);
                            journal(file, event); // Commit raw chunk transcript before UI overlap reconciliation.
                            String merged = StreamText.append(transcript.toString(), result.text);
                            transcript.setLength(0); transcript.append(merged);
                            segments++; processedEnd = chunk.endSample;
                            computeMs += result.totalMs; newSamples += chunk.newSamples();
                            double rtf = computeMs / (newSamples / 16.0);
                            send(3, "Decoder: " + (beamSize == 1 ? "Greedy" : "Beam " + beamSize) + " | מקטעים: " + segments + " | עיבוד: " + result.totalMs + " ms"
                                    + "\nRTF מצטבר: " + String.format(java.util.Locale.ROOT, "%.3f", rtf)
                                    + " | פיגור: " + String.format(java.util.Locale.ROOT, "%.2f", lagSamples / 16000.0) + " s"
                                    + "\nזיכרון תהליך: " + (pssKb / 1024) + " MB | מצב חום: " + thermal
                                    + "\n\nתמלול זמני:\n" + transcript + "\n\nנשמר: " + sessionFile.getName());
                        }
                    }
                } catch (Exception | Error e) {
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                    aborting = true;
                } finally {
                    stopCapture();
                    if (captureThread != null) captureThread.join(2000);
                    long unprocessed = Math.max(0, captured.get() - processedEnd);
                    if (unprocessed > 0 && failure == null) failure = "השיחה הסתיימה עם אודיו שלא תומלל";
                    journal(file, new JSONObject().put("type", "end").put("ended_at_ms", System.currentTimeMillis())
                            .put("state", failure == null && !aborting ? "complete" : "incomplete")
                            .put("captured_samples", captured.get()).put("processed_end_sample", processedEnd)
                            .put("unprocessed_samples", unprocessed).put("error", failure == null ? "" : failure));
                }
            }
        } catch (Exception | Error e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            stopCapture();
            queue.clear();
            send(6, (failure == null && !aborting ? "הבדיקה הסתיימה" : "הבדיקה לא הושלמה: " + failure)
                    + "\nDecoder: " + (beamSize == 1 ? "Greedy" : "Beam " + beamSize)
                    + "\nמקטעים שנשמרו: " + segments
                    + " | אודיו: " + String.format(java.util.Locale.ROOT, "%.2f", captured.get() / 16000.0) + " s"
                    + "\nRTF מצטבר: " + (newSamples == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.3f", computeMs / (newSamples / 16.0)))
                    + " | אודיו שלא תומלל: " + String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, captured.get() - processedEnd) / 16000.0) + " s"
                    + "\nקובץ: " + sessionFile.getName()
                    + "\n\nתמלול זמני:\n" + transcript);
            main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public boolean onUnbind(Intent intent) {
        aborting = true; stopCapture(); android.os.Process.killProcess(android.os.Process.myPid()); return false;
    }
}

package life.mosaic.tensorwhisper;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Staged Tensor G5 gate for the full ivrit.ai Whisper encoder.
 *
 * Stages 1/2 preserve the already-verified backend gates. Stage 3 records a real
 * short Hebrew utterance, computes Whisper's exact log-mel frontend, runs the
 * full encoder on Tensor G5 and passes its embeddings to the existing
 * whisper.cpp Hebrew Beam-5 decoder.
 */
public final class WhisperGateActivity extends Activity {
    private static final int MIC_PERMISSION = 41;
    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_RECORD_SECONDS = 8;
    private static final String MODEL_NAME =
            "ivrit_whisper_encoder_Google_Tensor_G5.tflite";
    private static final String DECODER_NAME = "ivrit-whisper-decoder-q5_0.bin";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService recorderWorker = Executors.newSingleThreadExecutor();

    private TextView status;
    private Button load;
    private Button zeroRun;
    private Button realAsr;
    private Button stopRecording;

    private boolean running;
    private boolean recording;
    private boolean bound;
    private int workerPid;
    private int generation;
    private int activeRequest;
    private Messenger remote;
    private ServiceConnection connection;
    private Runnable timeout;
    private Runnable recordingTimeout;
    private volatile AudioRecord recorder;
    private File pendingPcmFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 100, 28, 48);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTextIsSelectable(true);

        load = new Button(this);
        load.setText("1. טעינת מודל בלבד");

        zeroRun = new Button(this);
        zeroRun.setText("2. הרצת ENCODER עם ZERO LOG-MEL");

        realAsr = new Button(this);
        realAsr.setText("3. תמלול עברית אמיתי");

        stopRecording = new Button(this);
        stopRecording.setText("סיום משפט");
        stopRecording.setEnabled(false);

        Button copy = new Button(this);
        copy.setText("העתקת תוצאה");

        load.setOnClickListener(v -> begin(WhisperGateService.REQUEST_LOAD_ONLY, null));
        zeroRun.setOnClickListener(
                v -> begin(WhisperGateService.REQUEST_RUN_ZERO_MEL, null));
        realAsr.setOnClickListener(v -> requestRealRecording());
        stopRecording.setOnClickListener(v -> stopMic());

        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("Mosaic ivrit.ai TPU gate", status.getText()));
            Toast.makeText(this, "התוצאה הועתקה", Toast.LENGTH_SHORT).show();
        });

        layout.addView(load);
        layout.addView(zeroRun);
        layout.addView(realAsr);
        layout.addView(stopRecording);
        layout.addView(copy);
        layout.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        File external = getExternalFilesDir(null);
        boolean loadPassed = getPreferences(0).getBoolean("loadPassed", false);
        zeroRun.setEnabled(loadPassed);
        realAsr.setEnabled(loadPassed);

        String last = getPreferences(0).getString("result", "");
        status.setText(
                "ivrit.ai Whisper Large v3 Turbo — Tensor G5 + CPU decoder.\n"
                        + "שלב 3 הוא תמלול אמיתי מהמיקרופון, עד 8 שניות.\n"
                        + "האודיו נשמר זמנית ב-cache ונמחק אחרי הניסיון.\n\n"
                        + device() + "\n\n"
                        + "Tensor model:\n"
                        + (external == null
                                ? "external files unavailable"
                                : new File(external, MODEL_NAME).getAbsolutePath())
                        + "\n\nDecoder model:\n"
                        + (external == null
                                ? "external files unavailable"
                                : new File(external, DECODER_NAME).getAbsolutePath())
                        + (last.isEmpty() ? "" : "\n\nתוצאה אחרונה:\n" + last));
    }

    private String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + Build.SOC_MODEL + "\n" + Build.DISPLAY;
    }

    private void requestRealRecording() {
        if (running || recording) return;
        if (!getPreferences(0).getBoolean("loadPassed", false)) {
            status.setText(device() + "\n\nיש להריץ בהצלחה את שלב 1 לפני תמלול אמיתי.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        startMic();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_PERMISSION) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMic();
        } else {
            status.setText(device() + "\n\nלא אושרה הרשאת מיקרופון.");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startMic() {
        if (running || recording) return;

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            status.setText(device() + "\n\nFAIL — microphone format unavailable");
            return;
        }

        AudioRecord next = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, 8192));
        if (next.getState() != AudioRecord.STATE_INITIALIZED) {
            next.release();
            status.setText(device() + "\n\nFAIL — microphone unavailable");
            return;
        }

        pendingPcmFile = new File(getCacheDir(), "hybrid-input.pcm");
        //noinspection ResultOfMethodCallIgnored
        pendingPcmFile.delete();

        recorder = next;
        recording = true;
        updateButtons();
        status.setText(
                device() + "\n\n"
                        + "מקליט עכשיו — דבר בעברית.\n"
                        + "לחץ ‘סיום משפט’ כשסיימת (מקסימום 8 שניות).");

        recordingTimeout = this::stopMic;
        main.postDelayed(recordingTimeout, MAX_RECORD_SECONDS * 1000L);

        recorderWorker.execute(() -> capturePcm(next, pendingPcmFile));
    }

    private void capturePcm(AudioRecord audioRecord, File target) {
        int samples = 0;
        try (FileOutputStream out = new FileOutputStream(target)) {
            audioRecord.startRecording();
            short[] chunk = new short[1024];
            ByteBuffer bytes = ByteBuffer.allocate(chunk.length * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);

            while (recording && samples < SAMPLE_RATE * MAX_RECORD_SECONDS) {
                int want = Math.min(chunk.length,
                        SAMPLE_RATE * MAX_RECORD_SECONDS - samples);
                int n = audioRecord.read(
                        chunk, 0, want, AudioRecord.READ_BLOCKING);
                if (n < 0) {
                    if (!recording) break;
                    throw new IOException("Microphone read failed: " + n);
                }
                if (n == 0) continue;

                bytes.clear();
                for (int i = 0; i < n; i++) bytes.putShort(chunk[i]);
                out.write(bytes.array(), 0, n * 2);
                samples += n;
            }

            out.flush();
            final int capturedSamples = samples;
            main.post(() -> recordingFinished(target, capturedSamples));
        } catch (Throwable e) {
            main.post(() -> recordingFailed(e));
        } finally {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            if (recorder == audioRecord) recorder = null;
        }
    }

    private void stopMic() {
        if (!recording) return;
        recording = false;
        if (recordingTimeout != null) main.removeCallbacks(recordingTimeout);
        AudioRecord active = recorder;
        if (active != null) {
            try {
                active.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        stopRecording.setEnabled(false);
        status.append("\nמסיים הקלטה…");
    }

    private void recordingFinished(File pcmFile, int samples) {
        recording = false;
        if (recordingTimeout != null) main.removeCallbacks(recordingTimeout);
        updateButtons();

        if (samples < SAMPLE_RATE / 2) {
            //noinspection ResultOfMethodCallIgnored
            pcmFile.delete();
            status.setText(device() + "\n\nהמשפט קצר מדי — נסה לפחות חצי שנייה.");
            return;
        }

        status.setText(
                device() + "\n\n"
                        + String.format(java.util.Locale.ROOT,
                                "נקלטו %.2f שניות. מתחיל Tensor G5 + decoder…",
                                samples / (double) SAMPLE_RATE));
        begin(WhisperGateService.REQUEST_HYBRID_PCM, pcmFile);
    }

    private void recordingFailed(Throwable e) {
        recording = false;
        if (recordingTimeout != null) main.removeCallbacks(recordingTimeout);
        File file = pendingPcmFile;
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        pendingPcmFile = null;
        updateButtons();
        status.setText(
                device() + "\n\nFAIL — "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private void updateButtons() {
        boolean idle = !running && !recording;
        load.setEnabled(idle);
        boolean loadPassed = getPreferences(0).getBoolean("loadPassed", false);
        zeroRun.setEnabled(idle && loadPassed);
        realAsr.setEnabled(idle && loadPassed);
        stopRecording.setEnabled(recording);
    }

    private void begin(int request, File pcmFile) {
        if (running || recording) return;
        if (!"Google".equalsIgnoreCase(Build.MANUFACTURER)
                || !"Pixel 10 Pro".equals(Build.MODEL)
                || !Build.SOC_MODEL.toLowerCase().contains("tensor g5")) {
            status.setText("הבדיקה מוגבלת ל-Pixel 10 Pro עם Tensor G5.\n" + device());
            return;
        }

        if (request != WhisperGateService.REQUEST_LOAD_ONLY
                && !getPreferences(0).getBoolean("loadPassed", false)) {
            status.setText(
                    device() + "\n\n"
                            + "שלב זה נעול עד ששלב 1 (load-only) עובר בהצלחה.");
            return;
        }

        activeRequest = request;
        pendingPcmFile = pcmFile;
        running = true;
        updateButtons();

        final int attempt = ++generation;
        if (request != WhisperGateService.REQUEST_HYBRID_PCM) {
            String label = request == WhisperGateService.REQUEST_LOAD_ONLY
                    ? "פותח תהליך TPU מבודד ל-load-only…"
                    : "פותח תהליך TPU מבודד להרצת encoder…";
            status.setText(device() + "\n\n" + label);
        }

        getPreferences(0).edit()
                .putString("result", "הבדיקה הקודמת התחילה ללא תוצאה סופית")
                .apply();

        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (attempt != generation || !running) {
                if (message.what == 1 && message.arg1 > 0) {
                    android.os.Process.killProcess(message.arg1);
                }
                return true;
            }

            if (message.what == 1) workerPid = message.arg1;
            if (message.what == 2) {
                status.append("\n" + message.getData().getString("text", ""));
            }
            if (message.what == 3) {
                finishGate(message.getData().getString("text", "ללא תוצאה"));
            }
            return true;
        }));

        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote = new Messenger(binder);
                if (!running || attempt != generation) {
                    dispose();
                    return;
                }

                Message requestMessage = Message.obtain(null, request);
                requestMessage.replyTo = reply;
                if (request == WhisperGateService.REQUEST_HYBRID_PCM) {
                    if (pendingPcmFile == null || !pendingPcmFile.isFile()) {
                        finishGate("FAIL — PCM file disappeared before worker start");
                        return;
                    }
                    Bundle data = new Bundle();
                    data.putString("pcmPath", pendingPcmFile.getAbsolutePath());
                    requestMessage.setData(data);
                }
                try {
                    remote.send(requestMessage);
                } catch (RemoteException e) {
                    finishGate("שגיאת חיבור לתהליך TPU");
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                if (running) finishGate("תהליך TPU נסגר ללא תוצאה");
            }

            @Override public void onNullBinding(ComponentName name) {
                if (running) finishGate("לא ניתן לפתוח תהליך TPU");
            }

            @Override public void onBindingDied(ComponentName name) {
                if (running) finishGate("החיבור לתהליך TPU מת");
            }
        };

        long timeoutMs = request == WhisperGateService.REQUEST_LOAD_ONLY
                ? 60_000L : 90_000L;
        timeout = () -> {
            if (running && attempt == generation) {
                finishGate(
                        "TIMEOUT — הבדיקה נעצרה אחרי "
                                + (timeoutMs / 1000) + " שניות");
            }
        };
        main.postDelayed(timeout, timeoutMs);

        try {
            bound = bindService(
                    new Intent(this, WhisperGateService.class),
                    connection,
                    BIND_AUTO_CREATE);
            if (!bound) finishGate("לא ניתן להפעיל תהליך TPU");
        } catch (RuntimeException e) {
            finishGate(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void finishGate(String result) {
        if (!running) return;

        boolean loadPass = activeRequest == WhisperGateService.REQUEST_LOAD_ONLY
                && result.startsWith("PASS");
        if (loadPass) {
            getPreferences(0).edit().putBoolean("loadPassed", true).apply();
        }

        running = false;
        ++generation;
        if (timeout != null) main.removeCallbacks(timeout);

        status.setText(device() + "\n\n" + result);
        getPreferences(0).edit().putString("result", result).apply();

        dispose();

        File pcm = pendingPcmFile;
        pendingPcmFile = null;
        if (pcm != null && pcm.exists()) {
            // Worker normally deletes it; this covers bind failure/timeout.
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
        }
        updateButtons();
    }

    private void dispose() {
        if (workerPid > 0) {
            android.os.Process.killProcess(workerPid);
            workerPid = 0;
        } else if (remote != null) {
            try {
                remote.send(Message.obtain(null, 9));
            } catch (RemoteException ignored) {
            }
        }

        remote = null;
        if (bound) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            bound = false;
        }
    }

    @Override protected void onPause() {
        if (recording) stopMic();
        if (running) finishGate("הבדיקה בוטלה כשהאפליקציה עברה לרקע");
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (recording) stopMic();
        recorderWorker.shutdownNow();
        super.onDestroy();
    }
}

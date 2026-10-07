package life.mosaic.tensorwhisper;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

import java.io.File;

/**
 * Two-stage Tensor G5 gate for the full ivrit.ai Whisper encoder.
 *
 * Stage 1 only loads the AOT model in the disposable :tpu process. Stage 2,
 * enabled only after a successful load, runs a deterministic zero log-mel
 * tensor. No microphone, transcription, network, or user audio is involved.
 */
public final class WhisperGateActivity extends Activity {
    private static final String MODEL_NAME =
            "ivrit_whisper_encoder_Google_Tensor_G5.tflite";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button load;
    private Button run;
    private boolean running;
    private boolean bound;
    private int workerPid;
    private int generation;
    private int activeRequest;
    private Messenger remote;
    private ServiceConnection connection;
    private Runnable timeout;

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

        run = new Button(this);
        run.setText("2. הרצת encoder עם zero log-mel");

        Button copy = new Button(this);
        copy.setText("העתקת תוצאה");

        load.setOnClickListener(v -> begin(WhisperGateService.REQUEST_LOAD_ONLY));
        run.setOnClickListener(v -> begin(WhisperGateService.REQUEST_RUN_ZERO_MEL));
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("Mosaic ivrit.ai TPU gate", status.getText()));
            Toast.makeText(this, "התוצאה הועתקה", Toast.LENGTH_SHORT).show();
        });

        layout.addView(load);
        layout.addView(run);
        layout.addView(copy);
        layout.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        // Calling this also creates the app-specific external directory that adb
        // uses for the large AOT model.
        File external = getExternalFilesDir(null);
        boolean loadPassed = getPreferences(0).getBoolean("loadPassed", false);
        run.setEnabled(loadPassed);

        String last = getPreferences(0).getString("result", "");
        status.setText(
                "בדיקת ivrit.ai Whisper Large v3 Turbo על Pixel 10 Pro / Tensor G5.\n"
                        + "הבדיקה בשני שלבים בכוונה: קודם load-only, ורק אחר כך inference.\n"
                        + "אין מיקרופון ואין תמלול בשלב הזה.\n\n"
                        + device() + "\n\n"
                        + "העתק את המודל לכאן עם adb:\n"
                        + (external == null
                                ? "תיקיית external files לא זמינה"
                                : external.getAbsolutePath() + "/" + MODEL_NAME)
                        + (last.isEmpty() ? "" : "\n\nתוצאה אחרונה:\n" + last));
    }

    private String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + Build.SOC_MODEL + "\n" + Build.DISPLAY;
    }

    private void begin(int request) {
        if (running) return;
        if (!"Google".equalsIgnoreCase(Build.MANUFACTURER)
                || !"Pixel 10 Pro".equals(Build.MODEL)
                || !Build.SOC_MODEL.toLowerCase().contains("tensor g5")) {
            status.setText("הבדיקה מוגבלת ל-Pixel 10 Pro עם Tensor G5.\n" + device());
            return;
        }

        if (request == WhisperGateService.REQUEST_RUN_ZERO_MEL
                && !getPreferences(0).getBoolean("loadPassed", false)) {
            status.setText(
                    device() + "\n\n"
                            + "שלב 2 נעול עד ששלב 1 (load-only) עובר בהצלחה.");
            return;
        }

        activeRequest = request;
        running = true;
        load.setEnabled(false);
        run.setEnabled(false);

        final int attempt = ++generation;
        String label = request == WhisperGateService.REQUEST_LOAD_ONLY
                ? "פותח תהליך TPU מבודד ל-load-only…"
                : "פותח תהליך TPU מבודד להרצת encoder…";
        status.setText(device() + "\n\n" + label);

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

        // A hard application-level timeout keeps the experiment bounded. The
        // prior CPU experiment froze the device, so this gate never permits an
        // unbounded native call.
        long timeoutMs = request == WhisperGateService.REQUEST_LOAD_ONLY ? 60_000L : 90_000L;
        timeout = () -> {
            if (running && attempt == generation) {
                finishGate(
                        "TIMEOUT — הבדיקה נעצרה אחרי " + (timeoutMs / 1000) + " שניות");
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

        load.setEnabled(true);
        run.setEnabled(getPreferences(0).getBoolean("loadPassed", false));
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
        if (running) finishGate("הבדיקה בוטלה כשהאפליקציה עברה לרקע");
        super.onPause();
    }
}

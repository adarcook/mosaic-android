package life.mosaic.tensorwhisper;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

/**
 * One-shot Tensor G5 Whisper encoder gate.
 *
 * This APK deliberately does not request microphone permission yet. It proves that
 * a real Whisper encoder graph can execute through the Tensor G5 AOT/dispatch path
 * before that backend is connected to user audio or the Hebrew decoder.
 */
public final class WhisperGateActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start;
    private boolean running;
    private boolean bound;
    private int workerPid;
    private int generation;
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

        start = new Button(this);
        start.setText("בדיקת Whisper על TPU");
        Button copy = new Button(this);
        copy.setText("העתקת תוצאה");

        start.setOnClickListener(v -> begin());
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("Mosaic Whisper TPU gate", status.getText()));
            Toast.makeText(this, "התוצאה הועתקה", Toast.LENGTH_SHORT).show();
        });

        layout.addView(start);
        layout.addView(copy);
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        String last = getPreferences(0).getString("result", "");
        status.setText(
                "בדיקת Whisper encoder אמיתית על Pixel 10 Pro / Tensor G5.\n" +
                "אין מיקרופון ואין תמלול בשלב הזה.\n" +
                "הבדיקה מריצה log-mel אפס דרך encoder של Whisper כדי לאמת את נתיב ה-TPU.\n\n" +
                device() + "\n" + last);
    }

    private String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                Build.SOC_MODEL + "\n" + Build.DISPLAY;
    }

    private void begin() {
        if (running) return;
        if (!"Google".equalsIgnoreCase(Build.MANUFACTURER)
                || !"Pixel 10 Pro".equals(Build.MODEL)
                || !Build.SOC_MODEL.toLowerCase().contains("tensor g5")) {
            status.setText("הבדיקה מוגבלת ל-Pixel 10 Pro עם Tensor G5.\n" + device());
            return;
        }

        running = true;
        start.setEnabled(false);
        final int attempt = ++generation;
        status.setText(device() + "\n\nפותח תהליך TPU מבודד…");
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
            if (message.what == 2) status.append("\n" + message.getData().getString("text", ""));
            if (message.what == 3) finishGate(message.getData().getString("text", "ללא תוצאה"));
            return true;
        }));

        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote = new Messenger(binder);
                if (!running || attempt != generation) {
                    dispose();
                    return;
                }
                Message request = Message.obtain(null, 1);
                request.replyTo = reply;
                try {
                    remote.send(request);
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

        // Whisper graph is much heavier than the ADD probe but we still refuse an
        // unbounded run after the previous device freeze.
        timeout = () -> {
            if (running && attempt == generation) {
                finishGate("TIMEOUT — הבדיקה נעצרה אחרי 45 שניות");
            }
        };
        main.postDelayed(timeout, 45_000);

        try {
            bound = bindService(new Intent(this, WhisperGateService.class), connection, BIND_AUTO_CREATE);
            if (!bound) finishGate("לא ניתן להפעיל תהליך TPU");
        } catch (RuntimeException e) {
            finishGate(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void finishGate(String result) {
        if (!running) return;
        running = false;
        ++generation;
        if (timeout != null) main.removeCallbacks(timeout);
        status.setText(device() + "\n\n" + result);
        getPreferences(0).edit().putString("result", result).apply();
        dispose();
        start.setEnabled(true);
    }

    private void dispose() {
        if (workerPid > 0) {
            android.os.Process.killProcess(workerPid);
            workerPid = 0;
        } else if (remote != null) {
            try { remote.send(Message.obtain(null, 9)); } catch (RemoteException ignored) {}
        }
        remote = null;
        if (bound) {
            try { unbindService(connection); } catch (IllegalArgumentException ignored) {}
            bound = false;
        }
    }

    @Override protected void onPause() {
        if (running) finishGate("הבדיקה בוטלה כשהאפליקציה עברה לרקע");
        super.onPause();
    }
}

package life.mosaic.tensorprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;

/** Explicit one-shot probe. The UI process never loads LiteRT. */
public final class ProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start, cancel;
    private boolean running, bound;
    private int workerPid, generation;
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
        start = new Button(this); start.setText("בדיקת TPU אחת");
        cancel = new Button(this); cancel.setText("ביטול"); cancel.setEnabled(false);
        Button copy = new Button(this); copy.setText("העתקת התוצאה");
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("Mosaic TPU probe", status.getText()));
            Toast.makeText(this, "התוצאה הועתקה", Toast.LENGTH_SHORT).show();
        });
        start.setOnClickListener(v -> begin());
        cancel.setOnClickListener(v -> finishProbe("הבדיקה בוטלה"));
        layout.addView(start); layout.addView(cancel); layout.addView(copy); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        String last = getPreferences(0).getString("result", "");
        status.setText("בדיקת תשתית ל־Pixel 10 Pro בלבד.\nחיבור שמונה מספרים, ללא הקלטה או תמלול.\nלחץ פעם אחת ושלח את התוצאה.\n\n" + device() + "\n" + last);
    }

    private String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + "\n" + Build.SOC_MODEL + "\n" + Build.DISPLAY;
    }

    private void begin() {
        if (running) return;
        if (!"Google".equalsIgnoreCase(Build.MANUFACTURER) || !"Pixel 10 Pro".equals(Build.MODEL)) {
            status.setText("הבדיקה מוגבלת ל־Pixel 10 Pro.\n" + device()); return;
        }
        running = true; start.setEnabled(false); cancel.setEnabled(true);
        final int attempt = ++generation;
        status.setText(device() + "\nמתחיל בדיקת TPU…");
        // Persist a marker so even a native crash/restart leaves useful evidence.
        getPreferences(0).edit().putString("result", "הבדיקה הקודמת התחילה ללא תוצאה סופית").apply();
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (attempt != generation || !running) {
                if (message.what == 1) android.os.Process.killProcess(message.arg1);
                return true;
            }
            if (message.what == 1) workerPid = message.arg1;
            if (message.what == 2) status.append("\n" + message.getData().getString("text", ""));
            if (message.what == 3) finishProbe(message.getData().getString("text", "ללא תוצאה"));
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote = new Messenger(binder);
                if (!running || attempt != generation) { dispose(); return; }
                Message request = Message.obtain(null, 1); request.replyTo = reply;
                try { remote.send(request); } catch (RemoteException e) { finishProbe("שגיאת חיבור לתהליך הבדיקה"); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { if (running) finishProbe("תהליך הבדיקה נסגר ללא תוצאה"); }
            @Override public void onNullBinding(ComponentName name) { if (running) finishProbe("לא ניתן לפתוח תהליך בדיקה"); }
            @Override public void onBindingDied(ComponentName name) { if (running) finishProbe("החיבור לתהליך הבדיקה נסגר"); }
        };
        timeout = () -> { if (running && attempt == generation) finishProbe("הבדיקה נעצרה לאחר 15 שניות"); };
        main.postDelayed(timeout, 15_000);
        try {
            bound = bindService(new Intent(this, ProbeService.class), connection, BIND_AUTO_CREATE);
            if (!bound) finishProbe("לא ניתן להפעיל תהליך בדיקה");
        } catch (RuntimeException e) { finishProbe(e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    private void finishProbe(String result) {
        if (!running) return;
        running = false; ++generation;
        main.removeCallbacks(timeout);
        String text = device() + "\n\n" + result;
        status.setText(text); getPreferences(0).edit().putString("result", result).apply();
        dispose(); start.setEnabled(true); cancel.setEnabled(false);
    }

    private void dispose() {
        if (workerPid > 0) { android.os.Process.killProcess(workerPid); workerPid = 0; }
        else if (remote != null) try { remote.send(Message.obtain(null, 9)); } catch (RemoteException ignored) {}
        remote = null;
        if (bound) { unbindService(connection); bound = false; }
    }

    @Override protected void onPause() {
        if (running) finishProbe("הבדיקה בוטלה כשהאפליקציה עברה לרקע");
        super.onPause();
    }
}

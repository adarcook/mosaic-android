package life.mosaic.tensorwhisper;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;

/** Visible-screen 10-minute trial; no background microphone implementation. */
public final class StreamingGateActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, captureStatus;
    private Button start, stop;
    private Spinner decoderProfile;
    private Messenger remote;
    private boolean running, bound;
    private int workerPid, generation;
    private Runnable timeout;
    private String lastSaved = "";
    private ServiceConnection connection;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(28, 80, 28, 30);
        TextView heading = new TextView(this);
        heading.setText("בדיקת שיחה רציפה — עד 10 דקות\nהמודלים נטענים פעם אחת. חלונות 12 שניות עם חפיפה של שנייה.\nהשאר מסך פתוח; מעבר לרקע מבטל את הניסוי.\nהתמלול נשמר במכשיר בלבד. טקסט בגבולות עדיין זמני.");
        layout.addView(heading);
        decoderProfile = new Spinner(this);
        decoderProfile.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Beam 2 — ניסוי מהירות", "Beam 5 — הבדיקה הקודמת", "Greedy — ניסוי מהירות מרבית"}));
        layout.addView(decoderProfile);
        start = new Button(this); start.setText("התחלת שיחה");
        stop = new Button(this); stop.setText("עצירה וסיום עיבוד המקטעים"); stop.setEnabled(false);
        captureStatus = new TextView(this);
        status = new TextView(this); status.setTextSize(17); status.setTextIsSelectable(true);
        lastSaved = getPreferences(0).getString("last", ""); status.setText(lastSaved);
        Button copy = new Button(this); copy.setText("העתקת תוצאה");
        copy.setOnClickListener(v -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Mosaic streaming gate", status.getText())));
        start.setOnClickListener(v -> requestStart());
        stop.setOnClickListener(v -> {
            stop.setEnabled(false); captureStatus.setText("עוצר הקלטה ומסיים עיבוד…");
            if (remote != null) try { remote.send(Message.obtain(null, 9)); } catch (RemoteException e) { finish("החיבור לתהליך נסגר"); }
        });
        layout.addView(start); layout.addView(stop); layout.addView(copy);
        layout.addView(captureStatus); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }

    private void requestStart() {
        if (running) return;
        if (!getSharedPreferences("WhisperGateActivity", 0).getBoolean("crossParityPassedV1", false)) {
            status.setText("יש להשלים בהצלחה את שלב 6 במסך הבדיקות לפני שיחה רציפה."); return;
        }
        if (!"Google".equalsIgnoreCase(Build.MANUFACTURER) || !"Pixel 10 Pro".equals(Build.MODEL)
                || !Build.SOC_MODEL.toLowerCase(java.util.Locale.ROOT).contains("tensor g5")) {
            status.setText("הניסוי מוגבל ל-Pixel 10 Pro עם Tensor G5"); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42); return;
        }
        begin();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestStart();
    }

    private void armTimeout(long millis) {
        if (timeout != null) main.removeCallbacks(timeout);
        final int attempt = generation;
        timeout = () -> { if (running && attempt == generation) finish("TIMEOUT — התהליך נעצר; רק מקטעים שכבר נשמרו זמינים.\n" + lastSaved); };
        main.postDelayed(timeout, millis);
    }

    private void begin() {
        running = true; lastSaved = ""; start.setEnabled(false); stop.setEnabled(false);
        decoderProfile.setEnabled(false);
        final int beamSize = decoderProfile.getSelectedItemPosition() == 0 ? 2
                : decoderProfile.getSelectedItemPosition() == 1 ? 5 : 1;
        final int attempt = ++generation;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("פותח מנוע מבודד לשיחה רציפה…"); captureStatus.setText("");
        armTimeout(90_000);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (!running || attempt != generation) {
                if (message.what == 1 && message.arg1 > 0) android.os.Process.killProcess(message.arg1);
                return true;
            }
            String text = message.getData().getString("text", "");
            if (message.what == 1) workerPid = message.arg1;
            if (message.what == 2) { captureStatus.setText(text); armTimeout(90_000); }
            if (message.what == 7) { captureStatus.setText(text); stop.setEnabled(true); armTimeout(30_000); }
            if (message.what == 5) { captureStatus.setText(text); armTimeout(90_000); }
            if (message.what == 4) captureStatus.setText(text);
            if (message.what == 3) {
                lastSaved = text; status.setText(text);
                getPreferences(0).edit().putString("last", text).apply(); armTimeout(30_000);
            }
            if (message.what == 6) { lastSaved = text; getPreferences(0).edit().putString("last", text).apply(); finish(text); }
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!running || attempt != generation) return;
                remote = new Messenger(binder); Message request = Message.obtain(null, 1); request.replyTo = reply;
                Bundle options = new Bundle(); options.putInt("beam_size", beamSize); request.setData(options);
                try { remote.send(request); } catch (RemoteException e) { finish("לא ניתן להתחיל שיחה"); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (running) finish("תהליך השיחה נסגר. השמירה כוללת רק מקטעים שהושלמו.\n" + lastSaved);
            }
            @Override public void onBindingDied(ComponentName name) { if (running) finish("החיבור לתהליך השיחה מת\n" + lastSaved); }
            @Override public void onNullBinding(ComponentName name) { if (running) finish("לא ניתן לפתוח תהליך שיחה"); }
        };
        try {
            bound = bindService(new Intent(this, StreamingGateService.class), connection, BIND_AUTO_CREATE);
            if (!bound) finish("לא ניתן לפתוח תהליך שיחה");
        } catch (RuntimeException e) { finish(e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    private void finish(String result) {
        if (!running) return;
        running = false; ++generation;
        if (timeout != null) main.removeCallbacks(timeout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (workerPid > 0) { android.os.Process.killProcess(workerPid); workerPid = 0; }
        else if (remote != null) try { remote.send(Message.obtain(null, 10)); } catch (RemoteException ignored) { }
        remote = null;
        if (bound) { try { unbindService(connection); } catch (IllegalArgumentException ignored) { } bound = false; }
        start.setEnabled(true); stop.setEnabled(false); decoderProfile.setEnabled(true); captureStatus.setText(""); status.setText(result);
    }

    @Override protected void onPause() {
        if (running) finish("הניסוי בוטל במעבר לרקע; אודיו שטרם תומלל לא נשמר.\n" + lastSaved);
        super.onPause();
    }
}

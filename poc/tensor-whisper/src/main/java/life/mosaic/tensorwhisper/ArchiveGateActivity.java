package life.mosaic.tensorwhisper;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;

/** Foreground-only archive POC: record durable PCM, then decode or resume. */
public final class ArchiveGateActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, captureStatus;
    private Button start, stop, resume;
    private Spinner recordings;
    private String[] sessionIds = new String[0];
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
        heading.setText("ארכיון שיחה — עד 10 דקות\nהאודיו נשמר מקומית בזמן ההקלטה. התמלול מתחיל אחרי עצירה.\nהשאר את המסך פתוח; מעבר לרקע עוצר את הניסוי, ואפשר להשלים מהאודיו שנשמר.\nאודיו ותמליל נשארים במכשיר עד הסרה ידנית. אין העלאה או סיכום LLM בשלב זה.");
        layout.addView(heading);
        decoderProfile = new Spinner(this);
        decoderProfile.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Beam 5 — בסיס איכות", "Beam 2 — ניסוי מהירות", "Greedy — ניסוי"}));
        layout.addView(decoderProfile);
        start = new Button(this); start.setText("הקלטת שיחה חדשה");
        stop = new Button(this); stop.setText("עצירה והתחלת תמלול מההקלטה"); stop.setEnabled(false);
        captureStatus = new TextView(this);
        status = new TextView(this); status.setTextSize(17); status.setTextIsSelectable(true);
        lastSaved = getPreferences(0).getString("last", ""); status.setText(lastSaved);
        Button copy = new Button(this); copy.setText("העתקת תוצאה");
        copy.setOnClickListener(v -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Mosaic archive gate", status.getText())));
        start.setOnClickListener(v -> requestStart(""));
        recordings = new Spinner(this);
        resume = new Button(this); resume.setText("השלמת תמלול ההקלטה שנבחרה");
        resume.setOnClickListener(v -> {
            int selected = recordings.getSelectedItemPosition();
            if (selected >= 0 && selected < sessionIds.length) requestStart(sessionIds[selected]);
        });
        stop.setOnClickListener(v -> {
            stop.setEnabled(false); captureStatus.setText("עוצר הקלטה ומסיים עיבוד…");
            if (remote != null) try { remote.send(Message.obtain(null, 9)); } catch (RemoteException e) { finish("החיבור לתהליך נסגר"); }
        });
        layout.addView(start); layout.addView(stop); layout.addView(copy);
        layout.addView(recordings); layout.addView(resume);
        refreshRecordings();
        layout.addView(captureStatus); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }

    private String pendingSession = "";

    private void requestStart(String session) {
        pendingSession = session;
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
        begin(session);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestStart(pendingSession);
    }

    private void armTimeout(long millis) {
        if (timeout != null) main.removeCallbacks(timeout);
        final int attempt = generation;
        timeout = () -> { if (running && attempt == generation) finish("TIMEOUT — התהליך נעצר. האודיו השמור זמין להשלמת תמלול.\n" + lastSaved); };
        main.postDelayed(timeout, millis);
    }

    private void begin(String savedSession) {
        running = true; lastSaved = ""; start.setEnabled(false); stop.setEnabled(false);
        decoderProfile.setEnabled(false); resume.setEnabled(false); recordings.setEnabled(false);
        final int beamSize = decoderProfile.getSelectedItemPosition() == 0 ? 5
                : decoderProfile.getSelectedItemPosition() == 1 ? 2 : 1;
        final int attempt = ++generation;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText(savedSession.isEmpty() ? "פותח הקלטה מקומית…" : "קורא הקלטה ומקטעים שכבר נשמרו…"); captureStatus.setText("");
        armTimeout(90_000);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (!running || attempt != generation) {
                if (message.what == 1 && message.arg1 > 0) android.os.Process.killProcess(message.arg1);
                return true;
            }
            String text = message.getData().getString("text", "");
            if (message.what == 1) workerPid = message.arg1;
            if (message.what == 2) { captureStatus.setText(text); stop.setEnabled(false); armTimeout(90_000); }
            if (message.what == 7) { captureStatus.setText(text); stop.setEnabled(true); armTimeout(30_000); }
            if (message.what == 5) { captureStatus.setText(text); stop.setEnabled(false); armTimeout(90_000); }
            if (message.what == 4) { captureStatus.setText(text); armTimeout(30_000); }
            if (message.what == 3) {
                lastSaved = text; status.setText(text);
                getPreferences(0).edit().putString("last", text).apply(); armTimeout(90_000);
            }
            if (message.what == 6) { lastSaved = text; getPreferences(0).edit().putString("last", text).apply(); finish(text); }
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!running || attempt != generation) return;
                remote = new Messenger(binder); Message request = Message.obtain(null, 1); request.replyTo = reply;
                Bundle options = new Bundle(); options.putInt("beam_size", beamSize); options.putString("session", savedSession); request.setData(options);
                try { remote.send(request); } catch (RemoteException e) { finish("לא ניתן להתחיל שיחה"); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (running) finish("תהליך השיחה נסגר. האודיו השמור זמין להשלמת תמלול.\n" + lastSaved);
            }
            @Override public void onBindingDied(ComponentName name) { if (running) finish("החיבור לתהליך השיחה מת\n" + lastSaved); }
            @Override public void onNullBinding(ComponentName name) { if (running) finish("לא ניתן לפתוח תהליך שיחה"); }
        };
        try {
            bound = bindService(new Intent(this, ArchiveGateService.class), connection, BIND_AUTO_CREATE);
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
        getPreferences(0).edit().putString("last", result).apply(); refreshRecordings();
    }

    private void refreshRecordings() {
        java.io.File dir = new java.io.File(getFilesDir(), "asr-sessions");
        java.io.File[] files = dir.listFiles((parent, name) -> name.endsWith(".pcm"));
        if (files == null) files = new java.io.File[0];
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (java.io.File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4);
            if (new java.io.File(dir, id + ".jsonl").isFile() && id.matches("session-[0-9]+-[0-9a-f-]{36}")) {
                ids.add(id);
                long timestamp = Long.parseLong(id.substring(8, id.indexOf('-', 8)));
                labels.add(new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(timestamp))
                        + " | " + String.format(java.util.Locale.ROOT, "%.1f", file.length() / 32000.0) + " שניות");
            }
        }
        sessionIds = ids.toArray(new String[0]);
        recordings.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                sessionIds.length == 0 ? new String[]{"אין עדיין הקלטות שמורות"} : labels.toArray(new String[0])));
        resume.setEnabled(!running && sessionIds.length > 0); recordings.setEnabled(!running);
    }

    @Override protected void onPause() {
        if (running) finish("הניסוי נעצר במעבר לרקע; האודיו שכבר נשמר זמין להשלמת תמלול.\n" + lastSaved);
        super.onPause();
    }
}

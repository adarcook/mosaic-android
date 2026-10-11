package life.mosaic.tensorllm;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import java.io.InputStream;

/** Review an archived transcript, then ask/summarize explicitly. No message/notification access. */
public final class LlmActivity extends Activity {
    private EditText transcript, question;
    private TextView status;
    private Button ask, summarize, importFile, cancel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String sourceHash = "manual";
    private int workerPid, generation;
    private boolean running, bound, importing;
    private Messenger remote;
    private ServiceConnection connection;
    private Runnable deadline;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Create the model destination before the first adb push.
        getExternalFilesDir(null);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 70, 24, 24);
        TextView heading = new TextView(this); heading.setText("מוזאיק — שיחה מקומית\nGemma 4 E2B · Tensor G5\nבדוק את התמליל ואז בקש סיכום או שאל עליו. השאר את המסך פתוח בזמן העיבוד."); layout.addView(heading);
        importFile = new Button(this); importFile.setText("פתיחת תמליל שמור"); importFile.setOnClickListener(v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, 42);
        }); layout.addView(importFile);
        transcript = new EditText(this); transcript.setHint("תמליל לבדיקה או טקסט לשיחה"); transcript.setMinLines(5);
        transcript.setText(getPreferences(0).getString("transcript", "")); layout.addView(transcript);
        sourceHash = getPreferences(0).getString("source_hash", "manual");
        question = new EditText(this); question.setHint("מה תרצה לשאול על השיחה?"); layout.addView(question);
        summarize = new Button(this); summarize.setText("סיכום השיחה"); summarize.setOnClickListener(v -> start("")); layout.addView(summarize);
        ask = new Button(this); ask.setText("שאלה על השיחה"); ask.setOnClickListener(v -> {
            if (question.getText().toString().trim().isEmpty()) { status.setText("כתוב שאלה תחילה"); return; }
            start(question.getText().toString());
        }); layout.addView(ask);
        cancel = new Button(this); cancel.setText("עצירת העיבוד"); cancel.setEnabled(false); cancel.setOnClickListener(v -> finishRun("העיבוד נעצר; אפשר לנסות שוב")); layout.addView(cancel);
        status = new TextView(this); status.setTextSize(17); status.setTextIsSelectable(true); status.setText(getPreferences(0).getString("last", "")); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        receive(getIntent());
    }
    private void receive(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class); if (uri != null) load(uri);
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42 && result == RESULT_OK && data != null && data.getData() != null) load(data.getData());
    }
    private void load(Uri uri) {
        if (running || importing) return;
        importing = true; controls(false); status.setText("קורא תמליל שהושלם…");
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("הקובץ אינו זמין");
                TranscriptSource source = TranscriptSource.read(input);
                main.post(() -> { importing = false; sourceHash = source.sha256; transcript.setText(source.text);
                    saveSource(); status.setText("התמליל נטען. אפשר לתקן אותו לפני סיכום או שאלה."); controls(true); });
            } catch (Exception error) { main.post(() -> { importing = false; controls(true); status.setText("לא ניתן לפתוח: " + error.getMessage()); }); }
        }, "mosaic-transcript-import").start();
    }
    private void controls(boolean enabled) {
        transcript.setEnabled(enabled); question.setEnabled(enabled); ask.setEnabled(enabled);
        summarize.setEnabled(enabled); importFile.setEnabled(enabled); cancel.setEnabled(running);
    }
    private void saveSource() { getPreferences(0).edit().putString("transcript", transcript.getText().toString()).putString("source_hash", sourceHash).apply(); }
    private void start(String requestedQuestion) {
        String text = transcript.getText().toString();
        if (running || importing) return;
        if (text.trim().isEmpty() || text.length() > ModelSpec.MAX_TEXT || requestedQuestion.length() > ModelSpec.MAX_QUESTION) {
            status.setText("נדרש תמליל עד 12,000 תווים ושאלה עד 2,000 תווים. אין קיצור אוטומטי."); return;
        }
        saveSource(); running = true; controls(false); final int attempt = ++generation;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); status.setText("פותח מודל מקומי…");
        deadline = () -> { if (running && attempt == generation) finishRun("TIMEOUT — העיבוד נעצר; בדוק את יומן הניסוי"); };
        main.postDelayed(deadline, 240_000);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (!running || attempt != generation) {
                if (message.what == 1 && message.arg1 > 0) android.os.Process.killProcess(message.arg1); return true;
            }
            if (message.what == 1) workerPid = message.arg1;
            else if (message.what == 4 || message.what == 5) finishRun(message.getData().getString("text", ""));
            else status.setText(message.getData().getString("text", ""));
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!running || attempt != generation) return;
                remote = new Messenger(binder); Message request = Message.obtain(null, 1); request.replyTo = reply;
                Bundle data = new Bundle(); data.putString("text", text); data.putString("question", requestedQuestion);
                data.putString("source_sha256", sourceHash); request.setData(data);
                try { remote.send(request); } catch (RemoteException error) { finishRun("החיבור למודל נסגר"); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { if (running) finishRun("תהליך המודל נסגר. אפשר לנסות שוב."); }
            @Override public void onBindingDied(ComponentName name) { if (running) finishRun("החיבור למודל אבד"); }
            @Override public void onNullBinding(ComponentName name) { if (running) finishRun("לא ניתן לפתוח מודל"); }
        };
        try { bound = bindService(new Intent(this, LlmService.class), connection, BIND_AUTO_CREATE);
            if (!bound) finishRun("לא ניתן לפתוח מודל");
        } catch (RuntimeException error) { finishRun(error.toString()); }
    }
    private void finishRun(String result) {
        if (!running) return;
        running = false; generation++; if (deadline != null) main.removeCallbacks(deadline);
        if (workerPid > 0) android.os.Process.killProcess(workerPid);
        else if (remote != null) try { remote.send(Message.obtain(null, 9)); } catch (RemoteException ignored) { }
        workerPid = 0; remote = null;
        if (bound) { try { unbindService(connection); } catch (IllegalArgumentException ignored) { } bound = false; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        controls(!importing); status.setText(result); getPreferences(0).edit().putString("last", result).apply();
    }
    @Override protected void onPause() {
        saveSource(); if (running) finishRun("העיבוד נעצר במעבר לרקע; התמליל נשמר ואפשר לנסות שוב."); super.onPause();
    }
}

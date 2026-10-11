package life.mosaic.models;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Shared, user-owned source folder; private native-compatible copies are disposable. */
public final class ModelSetupActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE = new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status;
    private Button choose;
    private volatile boolean cancelled, importing;
    private static final String GEMMA = "gemma-4-E2B-it_Google_Tensor_G5.litertlm";
    private static final String[] WHISPER = {"ivrit-whisper-decoder-q5_0.bin",
        "ivrit_whisper_encoder_Google_Tensor_G5.tflite", "ivrit_whisper_cross_attention_Google_Tensor_G5.tflite"};
    private static final long[] SIZES = {574041195L, 1327807744L, 26846992L};
    private static final String[] HASHES = {
        "6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572",
        "28541df9741e207c14c3e6b20513fc27a278c5c5835c7fbe01304dbea9057108",
        "fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3"};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(28, 80, 28, 28);
        TextView explanation = new TextView(this);
        explanation.setText("מודלים עצמאיים\nשמור את קובצי המודל בתיקיית Documents/Mosaic/models בפיקסל ובחר אותה כאן. המקור נשאר גם אחרי הסרת האפליקציה.\nהאפליקציה מכינה עותק עבודה מקומי מאומת, ללא הורדה מהרשת. נדרש מקום נוסף: כ־1.93GB ל־Whisper או 3.11GB ל־Gemma.\nאחרי התקנה מחדש בחר שוב את התיקייה. אין כאן גיבוי של הקלטות ותמלילים. השאר את המסך פתוח בזמן הייבוא.");
        layout.addView(explanation);
        choose = new Button(this); choose.setText("בחירת תיקיית המקור וייבוא מודלים");
        choose.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 70));
        layout.addView(choose);
        status = new TextView(this); status.setTextIsSelectable(true); layout.addView(status);
        Button back = new Button(this); back.setText("חזרה"); back.setOnClickListener(v -> finish()); layout.addView(back);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 70 || result != RESULT_OK || data == null || data.getData() == null || importing) return;
        Uri tree = data.getData();
        // Prevent selecting an app-owned directory as the supposedly independent source.
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.getTreeDocumentId(tree).startsWith("primary:Documents/")) {
            status.setText("בחר תיקייה מקומית בתוך Documents, למשל Documents/Mosaic/models."); return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException error) { status.setText("לא ניתן לשמור הרשאת קריאה: " + error.getMessage()); return; }
        cancelled = false; importing = true; choose.setEnabled(false);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> importModels(tree), "mosaic-model-import").start();
    }
    private void show(String text) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); }); }
    private void importModels(Uri tree) {
        boolean ownsImport = false;
        try {
            ownsImport = ACTIVE.compareAndSet(false, true);
            if (!ownsImport) throw new IOException("ייבוא קודם עדיין נסגר; נסה שוב בעוד רגע");
            boolean llm = "life.mosaic.tensorllm".equals(getPackageName());
            String[] names = llm ? new String[]{GEMMA} : WHISPER;
            long[] sizes = llm ? new long[]{3113545589L} : SIZES;
            String[] hashes = llm ? new String[]{"af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"} : HASHES;
            Map<String, Uri> sources = new HashMap<>();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException("התיקייה אינה זמינה");
                int count = 0;
                while (cursor.moveToNext()) {
                    if (++count > 100) throw new IOException("בחר תיקייה ייעודית עם עד 100 קבצים");
                    String name = cursor.getString(1);
                    if (Arrays.asList(names).contains(name)) {
                        if (sources.containsKey(name)) throw new IOException("שם מודל כפול בתיקייה");
                        sources.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
                    }
                }
            }
            for (String name : names) if (!sources.containsKey(name)) throw new IOException("חסר בתיקיית המקור: " + name);
            File destination = getExternalFilesDir(null);
            if (destination == null) throw new IOException("תיקיית עבודה אינה זמינה");
            for (int i = 0; i < names.length; i++) {
                show("בודק/מייבא " + (i + 1) + "/" + names.length + ": " + names[i] + "\nהמקור העצמאי אינו משתנה. הפעולה עשויה לקחת מספר דקות.");
                File target = new File(destination, names[i]);
                if (ModelCopy.valid(target, sizes[i], hashes[i], () -> cancelled)) continue;
                if (destination.getUsableSpace() < sizes[i] + 64 * 1024 * 1024L)
                    throw new IOException("אין מספיק מקום לעותק עבודה מאומת. המקור נשמר.");
                try (InputStream input = getContentResolver().openInputStream(sources.get(names[i]))) {
                    if (input == null) throw new IOException("קובץ המקור אינו זמין");
                    ModelCopy.install(input, target, sizes[i], hashes[i], () -> cancelled);
                }
            }
            if (!getSharedPreferences("model-source", 0).edit().putString("tree", tree.toString()).commit())
                throw new IOException("לא ניתן לשמור בחירת תיקייה");
            show("המודלים מוכנים. מקור עצמאי ב־Documents נשמר; אפשר לחזור ולבצע בדיקה.");
        } catch (Exception error) { show("הייבוא לא הושלם: " + error.getMessage() + "\nהמקור וקובצי עבודה תקינים קיימים נשמרו. אפשר לנסות שוב."); }
        finally { if (ownsImport) ACTIVE.set(false); importing = false; runOnUiThread(() -> { if (!isDestroyed()) {
            choose.setEnabled(true); getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }}); }
    }
    @Override protected void onStop() { if (importing) cancelled = true; super.onStop(); }
    @Override protected void onDestroy() { cancelled = true; super.onDestroy(); }
}

package life.mosaic.tensorllm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.io.IOException;
import java.util.UUID;

/** Unexported local editor; no microphone, notification reads or model invocation. */
public final class PersonalCommandsActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(8, 15, 26), PANEL = Color.rgb(17, 28, 44);
    private PersonalCommandStore store;
    private PersonalCommands commands;
    private LinearLayout root, content;
    private ScrollView scroll;
    private AlertDialog dialog;
    private boolean foreground;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = PersonalCommandStore.open(this);
        root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout header = column();
        header.setPadding(dp(20), dp(12), dp(20), 0);
        header.addView(button("חזרה לשיחה", false, this::finish));
        header.addView(text("המילון האישי", 27, VoiceScreen.INK));
        root.addView(header);
        scroll = new ScrollView(this);
        content = column();
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    private boolean editable() {
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (!foreground || keyguard == null || keyguard.isDeviceLocked()) {
            finish();
            return false;
        }
        return true;
    }

    private void reload() {
        content.removeAllViews();
        try {
            commands = store.load();
            render();
        } catch (IOException failure) {
            commands = null;
            content.addView(text(failure.getMessage(), 16, VoiceScreen.ERROR));
            content.addView(button("ניסיון נוסף", false, this::reload));
        }
    }

    private void render() {
        int position = scroll.getScrollY();
        content.removeAllViews();
        content.addView(text("בחר איך לבקש ממוזאיק לבצע פעולה. הביטויים נשמרים רק במכשיר.", 16, VoiceScreen.INK));
        content.addView(text("ההתאמה היא לכל הביטוי, עם התעלמות מניקוד וסימני פיסוק. ביטוי אישי אינו עוקף הרשאות או נעילת מכשיר.", 13, VoiceScreen.MUTED));
        content.addView(text(commands.entries.size() + " מתוך " + PersonalCommands.LIMIT + " ביטויים", 13, VoiceScreen.ACCENT));
        Button add = button("הוספת ביטוי", true, () -> edit(null, ""));
        add.setEnabled(commands.entries.size() < PersonalCommands.LIMIT);
        content.addView(add);
        if (commands.entries.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("עדיין אין ביטויים אישיים", 19, VoiceScreen.INK));
            empty.addView(text("אפשר להתחיל מאחת הדוגמאות ולשמור אותה:", 14, VoiceScreen.MUTED));
            empty.addView(button("תן לי עדכון", false, () -> edit(null, "תן לי עדכון")));
            empty.addView(button("מה שלחו לי?", false, () -> edit(null, "מה שלחו לי?")));
        }
        for (PersonalCommands.Entry entry : commands.entries) {
            LinearLayout row = card();
            row.addView(text(entry.phrase, 20, VoiceScreen.INK));
            row.addView(text("הקראת התראת WhatsApp האחרונה", 14, VoiceScreen.MUTED));
            Switch enabled = new Switch(this);
            enabled.setText(entry.enabled ? "פעיל" : "כבוי");
            enabled.setTextColor(VoiceScreen.ACCENT);
            enabled.setMinHeight(dp(48));
            enabled.setChecked(entry.enabled);
            enabled.setContentDescription("הפעלת הביטוי " + entry.phrase);
            enabled.setOnCheckedChangeListener((view, checked) -> {
                if (!editable()) return;
                try {
                    PersonalCommands updated = commands.put(new PersonalCommands.Entry(entry.id, entry.phrase, entry.toolId, checked));
                    store.save(updated);
                    commands = updated;
                    render();
                } catch (Exception failure) {
                    render();
                    showError(failure.getMessage());
                }
            });
            row.addView(enabled);
            LinearLayout actions = new LinearLayout(this);
            actions.addView(button("עריכה", false, () -> edit(entry, entry.phrase)), new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(button("מחיקה", false, () -> delete(entry)), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(actions);
        }
        content.addView(text("הפעולה קוראת רק התראה זמינה, ללא גישה להיסטוריית השיחות. אפשר לנהל את הגישה להתראות במסך השיחה.", 13, VoiceScreen.MUTED));
        content.addView(text("כיבוי או מחיקת ביטוי מסירים רק את ההתאמה האישית. בקשות מובנות ממשיכות לעבוד.", 13, VoiceScreen.MUTED));
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void edit(PersonalCommands.Entry entry, String initial) {
        if (!editable()) return;
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView label = text("הביטוי שאומר למוזאיק", 15, VoiceScreen.INK);
        form.addView(label);
        EditText phrase = new EditText(this);
        phrase.setId(View.generateViewId());
        label.setLabelFor(phrase.getId());
        phrase.setTextColor(VoiceScreen.INK);
        phrase.setHintTextColor(VoiceScreen.MUTED);
        phrase.setHint("למשל: תן לי עדכון");
        phrase.setText(initial);
        phrase.setSingleLine(true);
        phrase.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PersonalCommands.PHRASE_LIMIT)});
        form.addView(phrase);
        form.addView(text("פעולה: הקראת התראת WhatsApp האחרונה", 15, VoiceScreen.ACCENT));
        CheckBox enabled = new CheckBox(this);
        enabled.setText("הביטוי פעיל");
        enabled.setTextColor(VoiceScreen.INK);
        enabled.setChecked(entry == null || entry.enabled);
        enabled.setMinHeight(dp(48));
        form.addView(enabled);
        TextView error = text("", 14, VoiceScreen.ERROR);
        form.addView(error);
        dialog = new AlertDialog.Builder(this).setTitle(entry == null ? "ביטוי חדש" : "עריכת ביטוי")
                .setView(form).setNegativeButton("ביטול", null).setPositiveButton("שמירה", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!editable()) return;
            try {
                String id = entry == null ? UUID.randomUUID().toString() : entry.id;
                PersonalCommands updated = commands.put(new PersonalCommands.Entry(id, phrase.getText().toString(),
                        WhatsAppNotificationTool.ID, enabled.isChecked()));
                store.save(updated);
                commands = updated;
                dismissDialog();
                render();
            } catch (Exception failure) {
                error.setText(failure.getMessage());
                phrase.requestFocus();
            }
        }));
        dialog.show();
    }

    private void delete(PersonalCommands.Entry entry) {
        if (!editable()) return;
        dialog = new AlertDialog.Builder(this).setTitle("מחיקת ביטוי")
                .setMessage("למחוק את הביטוי ״" + entry.phrase + "״?")
                .setNegativeButton("ביטול", null).setPositiveButton("מחיקה", (ignored, which) -> {
                    if (!editable()) return;
                    try {
                        PersonalCommands updated = commands.remove(entry.id);
                        store.save(updated);
                        commands = updated;
                        render();
                    } catch (Exception failure) { showError(failure.getMessage()); }
                }).create();
        dialog.show();
    }

    private void showError(String message) {
        if (!editable()) return;
        dialog = new AlertDialog.Builder(this).setTitle("השינוי לא נשמר").setMessage(message)
                .setPositiveButton("הבנתי", null).create();
        dialog.show();
    }

    private void dismissDialog() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (!editable()) return;
        root.setVisibility(View.VISIBLE);
        reload();
    }
    @Override protected void onPause() {
        foreground = false;
        dismissDialog();
        root.setVisibility(View.INVISIBLE);
        super.onPause();
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8)); view.setGravity(Gravity.START);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); return view;
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setMinHeight(dp(52)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setTextColor(primary ? BACKGROUND : VoiceScreen.INK);
        view.setBackgroundTintList(ColorStateList.valueOf(primary ? VoiceScreen.ACCENT : Color.rgb(29, 53, 64)));
        view.setOnClickListener(ignored -> click.run()); return view;
    }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(PANEL); background.setCornerRadius(dp(22));
        view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(14);
        content.addView(view, params); return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

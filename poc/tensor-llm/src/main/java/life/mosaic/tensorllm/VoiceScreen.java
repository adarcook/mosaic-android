package life.mosaic.tensorllm;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.util.ArrayDeque;
import org.json.JSONObject;

/** Presentation only; VoiceActivity retains ownership of permissions, recording and workers. */
final class VoiceScreen {
    static final int INK = Color.rgb(235, 242, 252);
    static final int MUTED = Color.rgb(157, 175, 199);
    static final int ACCENT = Color.rgb(111, 232, 214);
    static final int SUCCESS = Color.rgb(145, 233, 179);
    static final int ERROR = Color.rgb(255, 167, 181);
    private static final int BACKGROUND = Color.rgb(8, 15, 26);
    private static final int PANEL = Color.rgb(17, 28, 44);
    private static final int BORDER = Color.rgb(42, 59, 80);
    private final Activity activity;
    final TextView asr, llm, tts, transcript, answer, status;
    final Button talk, stop, setup, export, archive, warm, notificationAccess, notificationCheck;
    final CheckBox beam2, zipformer;
    final Button handsfreeButton, assistantButton, wakeExport, serviceProbe, feedbackPreview, personalCommands;
    final TextView handsfreeStatus;
    final ProgressBar asrProgress, llmProgress, ttsProgress;
    private final TextView heroTitle, heroDetail, stateBadge, elapsed, permission, trace;
    private final TextView[] steps = new TextView[3];
    private final Orb orb;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private String outcome = "";

    VoiceScreen(Activity activity) {
        this.activity = activity;
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(BACKGROUND);
        activity.getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        LinearLayout header = row();
        header.setPadding(dp(24), dp(20), dp(24), dp(12));
        TextView brand = text("MOSAIC", 24, INK);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setLetterSpacing(.16f);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = text("●  שיחה מקומית", 12, ACCENT);
        local.setPadding(dp(12), dp(8), dp(12), dp(8));
        local.setBackground(shape(Color.rgb(17, 43, 44), 24, Color.rgb(36, 77, 77)));
        header.addView(local);
        root.addView(header);

        LinearLayout tabs = row();
        tabs.setPadding(dp(20), 0, dp(20), dp(12));
        Button conversation = button("שיחה", false);
        Button diagnostics = button("אבחון", false);
        tabs.addView(conversation, weighted());
        tabs.addView(diagnostics, weighted());
        root.addView(tabs);

        FrameLayout pages = new FrameLayout(activity);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView homeScroll = scroll();
        ScrollView diagnosticsScroll = scroll();
        LinearLayout home = column();
        LinearLayout debug = column();
        home.setPadding(dp(20), dp(4), dp(20), dp(24));
        debug.setPadding(dp(20), dp(4), dp(20), dp(24));
        homeScroll.addView(home);
        diagnosticsScroll.addView(debug);
        pages.addView(homeScroll);
        pages.addView(diagnosticsScroll);
        diagnosticsScroll.setVisibility(View.GONE);
        Runnable selectHome = () -> selectTab(conversation, diagnostics, homeScroll, diagnosticsScroll);
        conversation.setOnClickListener(v -> selectHome.run());
        diagnostics.setOnClickListener(v -> selectTab(diagnostics, conversation, diagnosticsScroll, homeScroll));
        selectHome.run();

        LinearLayout hero = panel(home);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(27, 43, 65), Color.rgb(17, 27, 46), Color.rgb(27, 28, 53)});
        gradient.setCornerRadius(dp(28));
        gradient.setStroke(dp(1), BORDER);
        hero.setBackground(gradient);
        stateBadge = text("מתחילים כאן", 13, ACCENT);
        stateBadge.setGravity(Gravity.CENTER);
        hero.addView(stateBadge);
        orb = new Orb(activity);
        hero.addView(orb, new LinearLayout.LayoutParams(-1, dp(146)));
        heroTitle = text("נעים לדבר עם מוזאיק", 27, INK);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", 0));
        hero.addView(heroTitle);
        heroDetail = text("הכנה קצרה, ואז אפשר להתחיל שיחה.", 15, MUTED);
        heroDetail.setGravity(Gravity.CENTER);
        heroDetail.setPadding(0, dp(10), 0, dp(14));
        hero.addView(heroDetail);
        talk = button("הכנת מוזאיק", true);
        addButton(hero, talk);
        stop = button("סיום משפט", true);
        addButton(hero, stop);
        stop.setVisibility(View.GONE);
        elapsed = text("הפעלה בלחיצה · עד 12 שניות לפנייה", 12, MUTED);
        elapsed.setGravity(Gravity.CENTER);
        elapsed.setPadding(0, dp(12), 0, 0);
        hero.addView(elapsed);

        LinearLayout handsfree = panel(home);
        handsfree.addView(text("מוזאיק, בלי לגעת", 20, INK));
        handsfreeStatus = content(handsfree, "האזנה כבויה");
        assistantButton = button("בחירת מוזאיק כעוזר", false);
        handsfreeButton = button("הפעלת האזנה מקומית", false);
        addButton(handsfree, assistantButton);
        addButton(handsfree, handsfreeButton);
        feedbackPreview = button("איך מוזאיק נשמע?", false);
        addButton(handsfree, feedbackPreview);
        handsfree.addView(text("אמור מוזאיק, המתן ל־‘אני פה, דבר אליי’ ואז דבר. משוב קולי מלווה את השלבים גם בלי מסך. ההפעלה הראשונית מכינה את הקול. מצב ניסוי משתמש במיקרופון ובסוללה; אודיו ההמתנה אינו נשמר. תוכן אישי דורש פתיחת נעילה.", 12, MUTED));

        LinearLayout strip = row();
        for (int i = 0; i < steps.length; i++) {
            steps[i] = text(new String[]{"01  הקשבה", "02  הבנה / כלי", "03  קול"}[i], 12, MUTED);
            steps[i].setGravity(Gravity.CENTER);
            steps[i].setPadding(dp(4), dp(14), dp(4), dp(14));
            strip.addView(steps[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        home.addView(strip);

        LinearLayout tools = panel(home);
        tools.addView(text("WhatsApp", 18, INK));
        permission = text("", 13, MUTED);
        permission.setPadding(0, dp(6), 0, dp(10));
        tools.addView(permission);
        notificationAccess = button("ניהול גישה להתראות", false);
        addButton(tools, notificationAccess);
        personalCommands = button("המילון האישי", false);
        addButton(tools, personalCommands);
        tools.addView(text("הוסף ביטויים משלך להקראת התראת WhatsApp האחרונה.", 12, MUTED));

        heading(home, "השיחה שלך", "התמלול והתשובה מופיעים כאן במהלך הפנייה");
        LinearLayout userMessage = panel(home);
        userMessage.addView(text("אתה", 12, ACCENT));
        transcript = content(userMessage, "כשתדבר, התמלול יופיע כאן.");
        LinearLayout assistantMessage = panel(home);
        assistantMessage.addView(text("מוזאיק", 12, Color.rgb(190, 172, 252)));
        answer = content(assistantMessage, "התשובה הבאה מתחילה בקול שלך.");
        TextView privacy = text("ההקלטות והיומנים נשמרים במכשיר. אפשר לייצא יומן דרך אבחון.", 12, MUTED);
        privacy.setPadding(dp(4), dp(4), dp(4), 0);
        home.addView(privacy);

        heading(debug, "מאחורי השיחה", "שלבים, זמני ביצוע ומצב המנועים — בזמן אמת");
        LinearLayout statusPanel = panel(debug);
        statusPanel.addView(text("מצב נוכחי", 12, ACCENT));
        status = content(statusPanel, "פרטי הפנייה יופיעו כאן.");
        LinearLayout stagePanel = panel(debug);
        asr = content(stagePanel, "");
        asr.setText("1 · תמלול — ממתין");
        asrProgress = progress(stagePanel);
        llm = content(stagePanel, "");
        llm.setText("2 · תשובה / כלי — ממתין");
        llmProgress = progress(stagePanel);
        tts = content(stagePanel, "");
        tts.setText("3 · הקראה — ממתין");
        ttsProgress = progress(stagePanel);
        LinearLayout tracePanel = panel(debug);
        tracePanel.addView(text("אירועי הפנייה הנוכחית", 14, ACCENT));
        trace = content(tracePanel, "אירועים יופיעו כאן לאחר התחלת פנייה.");
        trace.setTextSize(13);
        export = button("ייצוא היומן המלא", false);
        addButton(tracePanel, export);
        TextView exportNote = text("יומן מלא עשוי לכלול תמלילים ותשובות אישיים. תוכן הודעות WhatsApp אינו נשמר ביומן הכלי.", 12, MUTED);
        exportNote.setPadding(0, dp(8), 0, 0);
        tracePanel.addView(exportNote);
        wakeExport = button("ייצוא אבחון ההאזנה", false);
        addButton(tracePanel, wakeExport);
        serviceProbe = button("בדיקת רציפות הרקע · ללא הקלטה", false);
        addButton(tracePanel, serviceProbe);

        heading(debug, "מנועים והרשאות", "ההגדרות הקיימות נשמרות בין עדכונים");
        LinearLayout settings = panel(debug);
        warm = button("הכנת מנועים מחדש", false);
        setup = button("הגדרת הקול · BlueTTS", false);
        archive = button("מודלים ותמלילים שמורים", false);
        addButton(settings, warm);
        addButton(settings, setup);
        addButton(settings, archive);
        zipformer = checkbox("ניסוי: תמלול עברי עם Zipformer");
        beam2 = checkbox("ניסוי: Beam 2 ב־Whisper (ברירת מחדל: 5)");
        settings.addView(zipformer);
        settings.addView(beam2);
        TextView experimentNote = text("מנועי הניסוי עשויים לשנות דיוק וזמן תגובה. מעבר מנוע דורש הכנה מחדש.", 12, MUTED);
        settings.addView(experimentNote);
        notificationCheck = button("בדיקת זמינות כלי WhatsApp", false);
        addButton(settings, notificationCheck);
        TextView build = text("MOSAIC · " + BuildConfig.VERSION_NAME + "\nגרסת פיתוח · האזנה מקומית היא מצב ניסוי", 12, MUTED);
        build.setGravity(Gravity.CENTER);
        build.setPadding(0, dp(12), 0, dp(12));
        debug.addView(build);
        activity.setContentView(root);
        root.requestApplyInsets();
    }

    void session(boolean prepared, boolean active, boolean recording, boolean warming, String stage) {
        talk.setText(prepared ? "דבר עם מוזאיק" : "הכנת מוזאיק");
        talk.setVisibility(active ? View.GONE : View.VISIBLE);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        String title, detail, badge;
        int current = -1;
        if (active) {
            badge = warming ? "הכנה מקומית" : "שיחה פעילה";
            switch (stage) {
                case "capture" -> { title = "אני מקשיב"; detail = "בסיום המשפט לחץ על הכפתור למטה."; current = 0; }
                case "asr" -> { title = warming ? "מכין את התמלול" : "מקשיב למילים שלך"; detail = "מנוע התמלול עובד על המכשיר."; current = 0; }
                case "llm" -> { title = warming ? "מכין את המודל" : "מרכיב תשובה"; detail = "עוד רגע, מוזאיק מעבד את הבקשה."; current = 1; }
                case "tts" -> { title = warming ? "מכין את הקול" : "מכין קול ומקריא"; detail = "אפשר לראות את שלב ההקראה המדויק באבחון."; current = 2; }
                default -> { title = "מוזאיק עובד"; detail = "אפשר לעקוב אחרי כל שלב באבחון."; }
            }
        } else {
            badge = prepared ? "מוכן לשיחה" : "ממתין להכנה";
            title = prepared ? "מה נעשה עכשיו?" : "נעים לדבר עם מוזאיק";
            detail = prepared ? "לחץ, דבר — ומוזאיק יענה בקול." : "הכן את המנועים לפני שמתחילים לדבר.";
            if ("error".equals(outcome)) { badge = "נדרשת תשומת לב"; title = "משהו עצר את הפנייה"; detail = "פרטי התקלה נשמרו באבחון. אפשר לבדוק ולנסות שוב."; }
            else if ("cancelled".equals(outcome)) { badge = "הפנייה נעצרה"; detail = "אפשר להתחיל שוב כשתרצה."; }
        }
        heroTitle.setText(title);
        heroDetail.setText(detail);
        stateBadge.setText(badge);
        stateBadge.setTextColor("error".equals(outcome) && !active ? ERROR : ACCENT);
        orb.active = active;
        orb.invalidate();
        for (int i = 0; i < steps.length; i++) {
            steps[i].setTextColor(i == current ? ACCENT : MUTED);
            steps[i].setBackground(i == current ? shape(Color.rgb(20, 46, 49), 14, Color.TRANSPARENT) : null);
        }
        if (!active) elapsed.setText("הפעלה בלחיצה · עד 12 שניות לפנייה");
        permission.setText(WhatsAppNotificationListener.hasAccess(activity)
                ? "גישה להתראות מאושרת · קריאה לפי בקשה"
                : "לקריאת התראה בקול, יש לאשר גישה להתראות");
    }

    void tick(long totalMilliseconds) {
        elapsed.setText("זמן הפנייה · " + totalMilliseconds / 1000 + " שניות");
    }

    void problem() { outcome = "error"; }

    void event(JSONObject value) {
        if ("start".equals(value.optString("type"))) { events.clear(); outcome = ""; }
        if ("end".equals(value.optString("type"))) outcome = value.optString("state");
        String line = VoiceEventSummary.describe(value);
        if (line.isEmpty()) return;
        events.addLast(line);
        while (events.size() > 48) events.removeFirst();
        trace.setText(String.join("\n", events));
    }

    private void selectTab(Button selected, Button other, View show, View hide) {
        selected.setBackground(shape(Color.rgb(29, 53, 64), 16, Color.rgb(62, 104, 113)));
        selected.setTextColor(ACCENT);
        selected.setSelected(true);
        other.setBackground(shape(BACKGROUND, 16, BORDER));
        other.setTextColor(MUTED);
        other.setSelected(false);
        show.setVisibility(View.VISIBLE);
        hide.setVisibility(View.GONE);
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private ScrollView scroll() { ScrollView v = new ScrollView(activity); v.setFillViewport(true); v.setClipToPadding(false); v.setVerticalScrollBarEnabled(false); return v; }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private LinearLayout panel(LinearLayout parent) {
        LinearLayout v = column(); v.setPadding(dp(20), dp(20), dp(20), dp(20));
        v.setBackground(shape(PANEL, 22, BORDER));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12);
        parent.addView(v, p); return v;
    }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern"); v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        v.setGravity(Gravity.START); v.setLineSpacing(dp(3), 1); return v;
    }
    private TextView content(LinearLayout parent, String hint) {
        TextView v = text("", 16, INK); v.setHint(hint); v.setHintTextColor(MUTED);
        v.setPadding(0, dp(8), 0, dp(10)); v.setTextIsSelectable(true); parent.addView(v); return v;
    }
    private void heading(LinearLayout parent, String title, String detail) {
        TextView label = text(title, 21, INK); label.setPadding(dp(2), dp(12), dp(2), dp(6)); parent.addView(label);
        TextView description = text(detail, 13, MUTED); description.setPadding(dp(2), 0, dp(2), dp(16)); parent.addView(description);
    }
    private Button button(String label, boolean primary) {
        Button v = new Button(activity); v.setText(label); v.setAllCaps(false); v.setTextSize(16);
        v.setMinHeight(dp(52)); v.setMinimumHeight(dp(52)); v.setPadding(dp(16), dp(12), dp(16), dp(12));
        v.setStateListAnimator(null); v.setBackgroundTintList(null);
        GradientDrawable background = shape(primary ? ACCENT : PANEL, 16, primary ? ACCENT : BORDER);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(68, 115, 124)), background, null));
        v.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, primary ? BACKGROUND : INK}));
        return v;
    }
    private void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); parent.addView(button, p);
    }
    private CheckBox checkbox(String label) {
        CheckBox v = new CheckBox(activity); v.setText(label); v.setTextColor(INK); v.setTextSize(14);
        v.setPadding(0, dp(12), 0, dp(12)); v.setMinHeight(dp(52));
        v.setButtonTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{MUTED, ACCENT}));
        return v;
    }
    private ProgressBar progress(LinearLayout parent) {
        ProgressBar v = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        v.setIndeterminate(true); v.setIndeterminateTintList(ColorStateList.valueOf(ACCENT)); v.setVisibility(View.GONE);
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(4))); return v;
    }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d;
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    /** Static drawing: no continuous animation, microphone capture or model work. */
    private static final class Orb extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean active;
        Orb(Activity context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) * .31f;
            paint.setShader(new RadialGradient(x, y, r * 1.65f, new int[]{0x5547BDCA, 0x224C75B0, 0x004C75B0}, null, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, r * 1.65f, paint);
            paint.setShader(new SweepGradient(x, y, new int[]{ACCENT, 0xFF9B8CD9, 0xFF32475F, ACCENT}, null));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(r * .035f); canvas.drawCircle(x, y, r, paint);
            paint.setShader(null); paint.setColor(0xFF344B63); paint.setStrokeWidth(r * .012f); canvas.drawCircle(x, y, r * .83f, paint);
            paint.setColor(active ? ACCENT : INK); paint.setStrokeWidth(r * .055f); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawRoundRect(x - r * .13f, y - r * .31f, x + r * .13f, y + r * .12f, r * .13f, r * .13f, paint);
            canvas.drawArc(x - r * .26f, y - r * .17f, x + r * .26f, y + r * .27f, 0, 180, false, paint);
            canvas.drawLine(x, y + r * .27f, x, y + r * .39f, paint);
            canvas.drawLine(x - r * .12f, y + r * .39f, x + r * .12f, y + r * .39f, paint);
        }
    }
}

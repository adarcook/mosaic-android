# ארכיון אודיו והשלמת תמלול — ASR-18, ניסוי 0.8

מסמך זה מתאר את שלב 9 החדש. [יומן הניסויים המרכזי](ASR_EXPERIMENT_HISTORY.md) מתעד את הבסיס, המדידות והכשלים. אין כאן עדיין סיכום/שאלות LLM, זיהוי דוברים, הקלטה ברקע או מוכנות לשיחות ארוכות משעות. אין שינוי ב־roadmap completion.

## מטרת הניסוי

ASR-17 הגיע ל־188 שניות מכוסות ו־RTF 1.0293, עם שגיאות איכות ובלי רשומת סיום. המשתמש דיווח שגם במסך לא קיבל סיכום. ה־slice הזה מפריד הקלטה מתמלול ומוסיף שחזור, כך שעלות decoder גבוהה אינה ממלאת תור בזמן ההקלטה.

שלב 8 נשאר ניסוי הקצב הקודם. שלב 9 **מקליט תחילה ורק אחרי Stop מתמלל**. אין לפרש ריצה מוצלחת בשלב 9 כהוכחת realtime; תהליך זה יכול להיות שימושי לארכיון גם כשהעיבוד איטי מהקלט. מקור התקלה הישנה לא שוחזר; מסלול הסיום החדש מקטין סיכון מסוים באמצעות שמירת ושליחת terminal result לפני native cleanup, ואינו טענה שסיבת התקלה הישנה הוכחה ותוקנה.

## מה נשמר ומה ניתן לשחזר

לכל שיחה חדשה נוצרים שני קבצים באחסון הפרטי של האפליקציה:

```text
files/asr-sessions/session-TIMESTAMP-UUID.pcm
files/asr-sessions/session-TIMESTAMP-UUID.jsonl
```

PCM הוא אודיו גולמי, mono / 16 kHz / signed 16-bit little-endian. הקוד קורא עד 1600 דגימות בכל פעם (0.1 שנייה), כותב ומבצע `fsync` לפני דיווח שהדגימות נשמרו. האודיו אינו מוטען כולו ל־RAM; עיבוד התמלול קורא חלון אחד בכל פעם. הקלטה של עשר דקות היא כ־19.2 MB של PCM.

הבטחת השחזור היא לדגימות שנשמרו, לא לקול שלא הגיע מה־AudioRecord או שטרם נכתב. סגירה כפויה במהלך כתיבה, כשל אחסון או תקלה במערכת עשויים לאבד את החלק שלא הושלם; אין כאן הוכחת אפס אובדן בזמן power loss או AudioRecord overrun. יש למדוד זאת על המכשיר. ב־recovery מסירים רק byte אחרון חלקי של PCM16, אם קיים.

ב־JSONL נשמרים:

- header version 3, mode archive, פורמט אודיו, build, beam, threads ו־engine contract;
- capture_begin/capture_end/capture_closed, אימות אודיו/מודלים, load ו־decode_begin כולל זיהוי tail;
- SHA-256 של האודיו שנחתם ושל שלושת קובצי המודל בפועל;
- תמלילים גולמיים, offsets מדויקים ומדדי כל מקטע, הנכתבים ומסונכרנים לפני הצגה;
- terminal record עם captured/processed/unprocessed, error ו־audio_retained, לפני סגירת המנוע.

`complete` מתייחס לכיסוי תמלול, ולא לאישור דיוק או השלמת native cleanup. `cleanup_pending=true` מתעד שהסיכום נשמר לפני cleanup. ה־UI רשאי לסיים את התהליך המבודד אחרי שקיבל סיכום. אם inference, אימות מודלים או טעינה נתקעים לפני terminal, timeout עדיין עשוי להשאיר journal בלי end; ההקלטה והמקטעים שכבר נשמרו נשארים לשחזור.

בפתיחת הקלטה שמורה: הקובץ ננעל כדי למנוע שני כותבים/מעבדים; רק שורה אחרונה שאינה מסתיימת ב־newline נחתכת. שורה שלמה פגומה או רצף מקטעים לא תואם אינם מתוקנים בשקט: העיבוד נכשל והאודיו נשמר. checkpoint חייב להיות prefix רציף לפי index, start/end, rate, overlap ו־beam. משחזרים את התמליל והמדדים ומתחילים במקטע החסר הראשון. hash אודיו/מודלים משתנה או engine contract אחר חוסמים ערבוב תוצאות.

שיחה שכבר תומללה במלואה מוצגת שוב בלי inference. Resume שומר את הגדרת ה־beam המקורית, בלי קשר לבחירה הנוכחית במסך. השוואת שתי הגדרות על אותו קובץ באמצעות runs נפרדים אינה ממומשת עדיין.

## מודל ותזמון

Beam 5 הוא ברירת המחדל להקלטות חדשות; Beam 2 ו־Greedy זמינים כניסויים. family, native CPU threads (2), גרף encoder ו־K/V, 12 s window ו־1 s overlap נשארו כמו קודם. חיבור החלונות עדיין זמני.

Tail קצר נשמר עם offsets מקוריים. אם חלון ראשון קצר מ־0.5 s, רק input המודל מרופד לאפסים עד 8000 samples; journal coverage אינו מתאר את הריפוד כאודיו שהוקלט. איכות/הזיות על tail קצר מחייבות בדיקת מכשיר. RTF מחושב על אודיו אמיתי שעובד, בלי טעינה ובלי ריפוד/כפל overlap. `backlog_s` בשלב 9 הוא יתרת קובץ ולא פיגור של קלט חי.

המסך חייב להישאר פתוח. מעבר לרקע או timeout עוצרים את התהליך; פתיחת שלב 9 מחדש מאפשרת השלמת תמלול. אין כאן המשך **הקלטה** אחרי הריגת תהליך: משלימים עיבוד של האודיו שכבר נשמר. מגבלת ההקלטה נשארת עשר דקות.

## התקנה ובדיקת Pixel

ב־WSL שבו כבר בנית את APK הקודם, עם אותו signing key:

```bash
cd ~/git-workspaces/mosaic-android
git fetch origin
git switch experiment/ivrit-durable-audio-recovery
python3 scripts/prepare_tensor_whisper_runtime.py
./gradlew -p poc/tensor-whisper assembleDebug
find poc/tensor-whisper/build/outputs/apk/debug -name '*.apk' -print
```

התקן את הקובץ שהתקבל באמצעות `adb install -r`; אל תסיר את האפליקציה כדי לפתור signature mismatch. שלושת המודלים שכבר הועברו נשארים באותו מיקום; אין צורך בייצוא/קומפילציה מחדש. שלב 6 צריך להיות מאושר כבעבר.

בדיקות הקבלה של ASR-18:

1. פתח שלב **9**, בחר Beam 5 והקלט 20–30 שניות. ודא שהמסך מציג הקלטה ושמירה, בלי עיבוד תמלול במקביל.
2. לחץ עצירה. ודא שההקלטה מסתיימת, עוברים לאימות/טעינת מודלים, ומגיע סיכום סיום עם `טרם תומלל: 0` ו־end record complete. השווה את תחילת וסוף המקור.
3. הקלט שוב; במהלך התמלול צא מהמסך. פתח שלב 9, בחר את ההקלטה האחרונה ברשימה ולחץ **השלמת תמלול ההקלטה שנבחרה**. ודא שמקטעים קיימים לא נכתבו מחדש והחסר מושלם.
4. בדוק גם מעבר לרקע **בזמן הקלטה**, פתיחה מחדש של האפליקציה, עצירה ליד 12 s, tail קצר מאוד, והקלטה באורך חלון מדויק. השווה sample offsets, audio length והסיכום.
5. בצע את אותו טקסט בדיקה ארוך ב־Beam 5 בשלב 9. איכות עדיין נבדקת בנפרד; אין לכנות הצלחת offline processing כהצלחת realtime.
6. בדיקת fault: force-stop בזמן decode, ואז relaunch/resume. AudioRecord, שכבות Android, native timeout, מקום אחסון, חשמל והבאת הסיכום למסך מחייבים device validation. בדיקות host אינן מחליפות אותן.

מקרי timeout/כשל מודלים/שינוי hash אמורים להסתיים בהודעת שגיאה או incomplete, עם האודיו שמור; אין להציג שלמות אם לא עובדה כל ההקלטה. קבצי ניסוי ישנים משלב 8 שאין לצדם PCM אינם ניתנים לשחזור בדרך זו.

## בדיקה וייצוא מקומי

```bash
adb shell run-as life.mosaic.tensorwhisper ls files/asr-sessions
adb exec-out run-as life.mosaic.tensorwhisper cat files/asr-sessions/SESSION_ID.jsonl > archive.jsonl
adb exec-out run-as life.mosaic.tensorwhisper cat files/asr-sessions/SESSION_ID.pcm > archive.pcm
```

אם נדרש קובץ WAV להאזנה במחשב, אפשר להמיר עותק מקומי בלי לשנות את מקור השחזור:

```bash
ffmpeg -f s16le -ar 16000 -ac 1 -i archive.pcm archive.wav
```

האודיו והתמלילים נשמרים מקומית, ללא העלאה. אין כאן מחיקה אוטומטית או UI למחיקת הקלטות; הקבצים נשארים עד מחיקה ידנית או הסרת האפליקציה. אין להכניס אותם ל־Git. מחיקה ידנית צריכה להסיר את זוג הקבצים של אותה שיחה, כשהתהליך אינו פעיל. אין לבצע מחיקה במהלך resume.

## Validation ומגבלות

בדיקות Java במחשב עברו: PCM16-LE כולל דגימות שליליות, union כיסוי מלא/overlap/tail, replay אחרי פתיחה מחדש, נעילה נגד בעלים שני, התאוששות מ־byte PCM ושורת journal חלקיים, padding בלי שינוי offsets, checkpoints ללא duplicate/gap ובלי ערבוב beam. בדיקות החלונות הקיימות ושמונה בדיקות Python עברו מקומית.

Android/JNI/native ו־APK עברו CI על commit `abbad7ea512b7ba1aa78c15cb3852e41f5f4f200`: [Actions run 37761861552](https://github.com/adarcook/mosaic-android/actions/runs/37761861552). בדיקות storage/window/comparison עברו שם וה־APK הועלה כ־artifact. fault testing על Pixel ויעילות fsync/battery עדיין Pending. לא בוצעה מדידת מכשיר חדשה. סיכום LLM, חיפוש בהיסטוריה, timestamps של מילים, זיהוי דוברים, recording foreground service ושיחות של שעות הם slices נפרדים.

# מוזאיק — דיבור, תשובה והקראה מקומית

ניסוי **ASR-22 / VOICE-01**, מעל ASR-21 / LLM-02. מסך הפתיחה של אפליקציית LLM הוא כעת מסך שיחה: לחיצה על **דבר עם מוזאיק**, הקלטה עד 12 שניות, **סיום משפט**, תמלול → תשובת Gemma → הכנת הגייה והקראה ב־BlueTTS. כל שלב מציג מצב, מחוון עבודה, זמן ותוצאה. המסלול של פתיחת תמליל שמור וסיכום/שאלה עליו נשאר בכפתור נפרד.

הקוד מחובר. שתי הבניות והבדיקות עברו על `4493d2fbe352efa50c327fc46712e13b44185319`: [שיחה/LLM](https://github.com/adarcook/mosaic-android/actions/runs/38128699000), [ASR companion](https://github.com/adarcook/mosaic-android/actions/runs/38128698944). בדיקת שרשרת מלאה במכשיר עדיין Pending. בפרט, החלפת dispatch בניסוי הקודם נבנתה אך עדיין לא הוכחה בפיקסל אחרי ה־SIGSEGV. אין טענה שה־LLM כבר נטען או שהקול החדש נשמע כמו בדיקת הקול הקבועה.

## מה רץ ואיפה

| שלב | מימוש | חומרה וגבולות |
|---|---|---|
| הקלטה ותמלול | PCM16-LE, mono 16kHz; אפליקציית Whisper הקיימת עם מנוע מבודד | encoder ו־cross-attention על TPU; decoder Q5 עם Beam 5 ושני תהליכוני CPU. אותו encoder מלא; הקלטה 0.5–12 שניות |
| תשובה | Gemma 4 E2B G5, LiteRT-LM 0.18.0 | NPU מפורש, dispatch SHA זהה לניסוי ASR-21; אין fallback. מודל ושיחה חדשים בכל פנייה, prompt שיחה קצר בעברית, עד 512 output tokens |
| הגייה וקול | RenikudPlus FP32 → IPA/token IDs → BlueTTS 2.5 | ONNX Runtime 1.24.3, CPU בלבד, שני תהליכונים; קול libri_male_6209 וגרפי הקול מהחבילה הקיימת. חלוקה למקטעי טקסט עד 96 תווים, אודיו עד 25 שניות למקטע |

השיחה אינה טוענת את שני מנועי ה־TPU באותו תהליך. ASR פועל ב־APK ובגרסת LiteRT הקיימים; שירות מקשר עם הרשאת `signature` מעביר תוצאה למסך השיחה. לכן שתי האפליקציות חייבות להיות חתומות באותו מפתח. מנוע ASR נסגר לפני שמתקבלת תשובה סופית ממנו; תהליך LLM מופסק לפני התחלת TTS. עובדי LLM ו־TTS מבודדים מתהליך המסך. מעבר לרקע וביטול עוצרים את הפעולה; תקציבי wall: ASR 120s, LLM 240s, TTS 240s. אין retry אוטומטי.

## למה הקול הקיים דרש השלמה

בדיקת הקול הקודמת סינתזה אודיו בפיקסל, אך `reply.json` הכיל token IDs ורעש למשפט קבוע שהוכן במחשב. חיבור תשובת LLM לקובץ הזה היה מקריא שוב את משפט הבדיקה. כעת נשמרים ממנו רק סגנון הקול, CFG embeddings, normalization והגדרות המנוע; IPA, token IDs, duration, noise ו־latent mask נוצרים מחדש בפיקסל לפי **תשובת המודל בפועל**.

RenikudPlus לא נשלח לטלפון בבדיקה הישנה. מודל FP32 מאותה revision שבה הוכן הקול הוא 1,234,209,530 bytes, SHA `c3a2bdc35afbf53f28cdece4672dcca9b530dfa43a0d65b4c5bbc47150b32d0d`. הסקריפט מחפש אותו ב־HF cache של המחשב; הורדה מתבצעת רק עם `--download-g2p` מפורש. BlueTTS, Whisper ו־Gemma אינם יורדים מחדש. גרסת int8 אינה משמשת בניסוי הזה.

## הכנה פעם אחת ב־WSL

אם `voice-models` הקיים נמצא במחשב, ציין אותו ב־`--existing-pack`. אם הוא קיים רק בפיקסל, משוך את חמשת הקבצים הבאים מהמיקום שבו הותקנה בדיקת הקול. הפקודות עבור `life.mosaic.fit.voicepoc`; אם השתמשת ב־Voice Mobile החלף ל־`life.mosaic.fit.voicepoc.mobile`.

```bash
cd ~/git-workspaces/mosaic-android
ADB=/mnt/c/Users/orele/AppData/Local/Android/Sdk/platform-tools/adb.exe
mkdir -p ~/mosaic-private/blue-existing/blue
VOICE_SOURCE=/sdcard/Android/data/life.mosaic.fit.voicepoc/files/voice-models
"$ADB" pull "$VOICE_SOURCE/reply.json" "$(wslpath -w "$HOME/mosaic-private/blue-existing/reply.json")"
for NAME in duration_predictor_style text_encoder vector_estimator vocoder; do
  "$ADB" pull "$VOICE_SOURCE/blue/$NAME.onnx" "$(wslpath -w "$HOME/mosaic-private/blue-existing/blue/$NAME.onnx")"
done
python3 scripts/prepare_blue_voice_pack.py --existing-pack ~/mosaic-private/blue-existing
```

אם חסר Renikud cache, הסקריפט נעצר לפני ההכנה ואומר מה חסר. אפשר להעביר `--renikud /path/to/cached/model.onnx`, למשל מה־cache הקיים ב־Windows. אם המודל לא ירד בעבר, להכנה חד־פעמית:

```bash
python3 -m venv ~/mosaic-private/voice-pack-venv
~/mosaic-private/voice-pack-venv/bin/python -m pip install huggingface-hub
~/mosaic-private/voice-pack-venv/bin/python scripts/prepare_blue_voice_pack.py --existing-pack ~/mosaic-private/blue-existing --download-g2p
```

הפלט הוא חבילה שטוחה עם שבעה קבצים ו־manifest ב־`~/mosaic-private/blue-voice-pack`. אין שינוי במקורות. SHA/גודל של חמשת גרפי המודל ושל vocab ננעלו; style fixture הקיים מקבל זהות SHA בחבילת ההכנה. העברה למקור העצמאי בפיקסל:

```bash
"$ADB" shell mkdir -p /sdcard/Documents/Mosaic/tts
"$ADB" push "$(wslpath -w "$HOME/mosaic-private/blue-voice-pack/.")" /sdcard/Documents/Mosaic/tts/
```

במסך השיחה לחץ **הכנת הקול BlueTTS**, בחר `Documents/Mosaic/tts` והמתן לסיום האימות/ייבוא. מקור זה נשאר אחרי הסרת האפליקציה. עותק העבודה נמצא ב־`Android/data/life.mosaic.tensorllm/files/blue-voice`, ונדרש מקום נוסף של כ־1.50GB מעבר למקור. הייבוא קורא בלבד מהמקור ומחליף קבצים מאומתים באופן אטומי; manifest מתחייב אחרי הקבצים. runtime מאמת שוב את כל הקבצים לפני טעינת ONNX.

## בנייה והתקנה

בנה **את שתי האפליקציות באותו מחשב ובאותו signing key**, בלי להסיר את ההתקנות הקיימות. אין הורדת מודלים בפקודת הבנייה. artifacts מ־CI נפרד עלולים להיות חתומים במפתחות debug שונים ואינם זוג התקנות מאושר למסלול הזה.

```bash
git pull --ff-only origin experiment/tensor-local-llm
python3 scripts/build_tensor_apps.py
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-whisper/build/outputs/apk/debug/MosaicTensorWhisperGate-debug.apk)")"
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk)")"
```

גרסאות: ASR `0.11-voice-companion`, שיחה `0.4-voice-turn`. gates קיימים של Whisper נשמרים בעדכון: שירות התמלול דורש את בדיקת parity הקיימת של שלב 6; אם אין gate יש להשלים אותו באפליקציית ASR. אין צורך להריץ מחדש אם עבר במתקן הקיים והאפליקציה עודכנה באותה חתימה. מודלי ASR/Gemma באותם נתיבי cache נשארים ללא העתקה נוספת.

## לוגים ושחזור בעיות

יומן פרטי לכל פנייה: `files/voice-turns/voice-<time>-<uuid>.jsonl`, ו־PCM באותה תיקייה. `start` כולל APK version, Android fingerprint, מודל/dispatch identities והגדרות; `phase` ו־`phase_detail` נכתבים לפני/במהלך שלבים; `stage_end` מכיל פלט ומדדים; `end` מבדיל complete/error/cancelled. יומן ללא terminal הוא ריצה שהופרעה, לא הצלחה. המסך מציין הפרעה אם תהליך האפליקציה נסגר לפני התוצאה, ואינו מריץ שוב שאלה או קול בעצמו.

| שלב | מדדים ביומן |
|---|---|
| הקלטה / ASR | אורך אודיו ו־SHA, אימות מודלים, load, mel, encoder, cross, decode, compute ו־wall כולל cleanup |
| LLM | אימות, load, generation, טקסט ראשון, תשובה נקייה והפניה ליומן המנוע הנפרד |
| TTS | אימות, load, G2P, acoustic, synthesis כולל, playback, משך האודיו וטקסט מנורמל שהוקרא |

`worker_pss_kb` ו־`thermal_status` הם דגימות בזמן סיום שלב; ASR נמדד אחרי סגירת משאבים ולכן אינו peak RAM. שלבי load/verify נכללים ב־wall אך מופרדים ממדדי compute. זמן TTS כולל יצירת אודיו וגם השמעה, ואינו RTF של תמלול.

**ייצוא יומן השיחה האחרונה** נותן קובץ JSONL לבחירת המשתמש. הוא כולל תוכן אישי; אין העלאה אוטומטית. Logcat `MosaicVoice` מציג שלבים בלבד, ללא טקסט. PCM והתמלילים עדיין פרטיים לאפליקציה ונמחקים בהסרה; מודלים ב־Documents אינם גיבוי של שיחות. אין retention cleanup אוטומטי. יומן הניסויים בריפו מתעדכן בקומיט כשמנתחים תוצאת ניסוי, לא אוטומטית מכל פנייה בטלפון.

## בדיקת קבלה ראשונה

1. בקש בקול משפט קצר, למשל: “מוזאיק, תגיד לי בעברית שאתה שומע אותי.” סיים לפני 12 שניות.
2. בדוק שהתמליל נכון; שהתשובה עונה לפנייה, ושנשמע **תוכן התשובה** ולא משפט הבדיקה הקבוע.
3. ייצא JSONL. אם שלב נכשל, בדוק את השלב האדום ואת phase האחרון; אל תניח שזמן המתנה הוא כשל ASR כשכבר יש תמליל.
4. בדוק פנייה שנייה עם תוכן אחר; ביטול ב־ASR, ב־LLM ובהקראה; מעבר לרקע; חבילת קול חסרה/פגומה; שם, מספר ושלילה. לתנאי איכות/מהירות השווה PCM זהה, לא קריאות שונות.

הגרסה הראשונה היא push-to-talk ופנייה אחת בכל פעם, ללא wake word, background, interruption/barge-in או זיכרון שיחה בין פניות. תשובה להקראה מוגבלת ל־1,200 תווים; חריגה מוצגת כשגיאת הקראה עם תשובת LLM שמורה, בלי קיצור שקט. מספרים נקראים ספרה־ספרה ואותיות לטיניות בשמותיהן בעברית; זה normalizer ראשוני, לא frontend מלא לתאריכים/כסף/טקסט באנגלית. קול וסטרס של מילים חדשות עדיין דורשים בדיקת מכשיר. אין גישה ל־WhatsApp, SMS או כלים בשלב הזה.

## מקורות והייחוס

BlueTTS acoustic port מבוסס על המימוש הקיים בריפו ועל [BlueTTS revision](https://github.com/maxmelichov/BlueTTS/tree/0e38dbf08ed53f85863d1eab092bd9572c53a503). Renikud frontend הותאם ל־Java מתוך [ה־wrapper המצורף למודל באותה revision](https://huggingface.co/notmax123/RenikudPlus/blob/679c56ca449d41873fb8ff7711ddf7563d28198f/renikud_onnx.py), SHA `2516953777db2bb7dc8bfa446df655ff5013bd82637345209c9fc4daedb18630`. ייחוס ReNikud: Maxim Melichov, Yakov Kolani, Morris Alper; [פרויקט המקור](https://github.com/renikud/renikud). קובצי רישיון ו־vocab metadata בלבד ב־`scripts/voice_metadata`, והייחוס נכלל גם ב־APK כ־assets; אין weights בריפו. בדיקות cascade משתמשות ב־32 fixtures סינתטיים שהופקו מפונקציית `_exact_map` המקורית, ללא טעינת weights, כדי לבדוק חוקיות עיצורים, cascade conditioning והטעם למילה.

## עדכון 0.5 — ניגון ומדידת הכשל

בתוצאת Pixel הראשונה של 0.4, ASR ו־Gemma הושלמו אך BlueTTS נכשל ב־Playback timeout. גרסת 0.5 קובעת start threshold שמאפשר למקטע קצר להתחיל, מגבילה גם כתיבה שנתקעת, ושומרת ביומן מדדי נגן ו־verify/load/G2P/acoustic גם בכשל. אין צורך לייבא מחדש את המודלים לאחר install -r. אימות וטעינה עדיין מתבצעים מחדש בכל פנייה; השיפור במהירות הכוללת טרם בוצע וניסוי הקול המתוקן דורש Pixel. פירוט התוצאה וההשערה: ASR-22 follow-up ו־ASR-23 ביומן הניסויים.

## גרסה 0.6 — הכנה פעם אחת במסך השיחה

בנה והתקן גם LLM 0.6 וגם companion ASR 0.12 באותה חתימה. אין שינוי במודלים ואין צורך בהעתקה מחדש. אחרי פתיחת ‘מוזאיק — שיחה’, לחץ **הכנת מנועים לשיחה**, אשר מיקרופון אם צריך והמתן ל־‘המנועים מוכנים’. ההכנה עושה SHA/load מלאים ומתועדת ב־journal נפרד; היא אינה ‘זמן תגובה’ אך עדיין נמדדת. רק לאחר מכן מופעל כפתור הדיבור.

בצע שתי פניות בלי לעזוב את המסך ובלי לייצא ביניהן; export פותח picker ומפנה את המנועים. לאחר הפנייה השנייה ייצא את היומן. המנועים נפרדים אך נשארים כולם בזיכרון, ומבצעים inference בתורם. כל פנייה מקבלת Conversation חדש ב־Gemma. verify/load אמורים להיות 0 עם engine_reused=true בפניות; CPU/TPU inference עדיין אינו אפס. שינוי קבצי cache, שחרור על ידי Android, יציאה/ביטול/שתי דקות idle דורשים הכנה מחדש.

זו גרסה ניסיונית לזיכרון resident ול־latency, לא אישור ששיחה מהירה כבר הושגה. יש guard לפני preload ונתוני RAM; אין להסיק מה־PSS של 0.5 את הזיכרון החדש. מסלול התמלילים המוקלטים נשמר. ביומן ASR-23 נמדד קול תקין אך כ־32.5s המתנה; ASR-24 מתעד את ניסיון השיפור ואת תוצאותיו כשיגיעו.

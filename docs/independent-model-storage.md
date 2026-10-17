# מודלים עצמאיים בפיקסל — ASR-20

המקור הקבוע של המודלים נשמר בתיקייה משותפת בבעלות המשתמש:
`/sdcard/Documents/Mosaic/models`. הסרת אפליקציית ניסוי אינה מסירה תיקייה זו.

לכל APK יש כפתור **מודלים — מקור עצמאי ב־Documents**. בחר את תיקיית `models` וייבא. הקוד קורא רק את ארבעת השמות והזהויות המקובעים בניסוי; אינו משנה או מוחק את המקור, ואין הורדת רשת באפליקציה.

## איך זה עובד ומה המחיר

זו הפרדה בין **מקור עצמאי** לבין **עותק עבודה שניתן לשחזר**. מנועי native ממשיכים לטעון מאותה תיקיית עבודה שבה עבדו קודם. כעת אפשר לבנות אותה מחדש מהמקור שבטלפון גם אחרי הסרה והתקנה בחתימה אחרת.

- Whisper: שלושה קבצים, יחד 1,928,695,931 bytes (כ־1.93GB).
- Gemma: קובץ אחד, 3,113,545,589 bytes (כ־3.11GB).
- שני המקורות יחד: כ־5.04GB; עם עותקי העבודה, כ־10.08GB בסך הכול. בזמן החלפת עותק קיים ייתכן שנדרש מקום זמני נוסף בגודל אותו קובץ.
- עותק עבודה קיים עם גודל ו־SHA תקינים נבדק ונשאר במקומו; גם המקור העצמאי מאומת לפני הצלחה, כדי שלא נסתיר מקור פגום באמצעות עותק עבודה תקין; אין העתקה מחדש בכל פתיחה/שאלה.
- ייבוא חדש כותב `.import-part`, מאמת גודל ו־SHA תוך כדי ההעתקה, עושה fsync ורק אז מחליף את עותק העבודה באופן אטומי. מקור לא תקין, ביטול או שגיאת I/O אינם מחליפים עותק תקין קיים. החלפה היא לכל קובץ; ייבוא חלקי ניתן לחזרה.
- בחר תיקייה מקומית בתוך Documents. ספק ענן ותיקיות `Android/data` אינם מתקבלים כמקור עצמאי. מעבר לרקע מפסיק ייבוא; השאר מסך פתוח וחזור לייבוא אם נדרש.

טעינה ישירה דרך SAF/file descriptors בלי עותק עבודה לא מומשה בניסוי זה; היא דורשת בדיקת תאימות נפרדת למנועי Whisper/LiteRT/LiteRT-LM. ההפרדה הנוכחית אינה תלויה ביכולת הזאת.

## היום ב־WSL — הכנת הפיקסל בלי הורדת Whisper מחדש

עדכן את ענף הניסוי:

```bash
cd ~/git-workspaces/mosaic-android
git fetch origin
git switch experiment/tensor-local-llm
git pull --ff-only origin experiment/tensor-local-llm
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

ADB=/mnt/c/Users/orele/AppData/Local/Android/Sdk/platform-tools/adb.exe
python3 scripts/prepare_shared_model_folder.py --adb "$ADB" --only whisper
```

הסקריפט מעתיק מתוך תיקיית Whisper הקיימת בפיקסל לתיקייה העצמאית. הוא מאמת SHA לפני ואחרי, ומשאיר את המקור הישן במקומו. קובץ יעד תקין קיים אינו מועתק שוב. כשל אינו מוחק מודלים קודמים. אפשר `--serial DEVICE_SERIAL` אם מחוברים כמה מכשירים. נדרש מקום פנוי לעותק העצמאי.

אם Gemma כבר נמצא בתיקיית אפליקציית ה־LLM בפיקסל:

```bash
python3 scripts/prepare_shared_model_folder.py --adb "$ADB" --only llm
```

אם הורדת Gemma ב־WSL אך עוד לא העברת אותו לפיקסל, העבר **ישירות לתיקייה העצמאית**:

```bash
python3 scripts/prepare_shared_model_folder.py --adb "$ADB" --only llm \
  --llm-model "$HOME/mosaic-private/llm/gemma-4-E2B-it_Google_Tensor_G5.litertlm"
```

אין הורדה בסקריפט ההעברה. אם הקובץ עדיין לא הורד, זו הפעולה החד־פעמית הנפרדת:

```bash
python3 scripts/prepare_tensor_llm.py --model-dir "$HOME/mosaic-private/llm"
```

## התקנת הגרסה ובחירת המקור

```bash
python3 scripts/build_tensor_apps.py
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-whisper/build/outputs/apk/debug/MosaicTensorWhisperGate-debug.apk)")"
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk)")"
```

פתח כל אפליקציה, לחץ על כפתור המודלים, ובחר `Documents/Mosaic/models`. המתן ל־**המודלים מוכנים**. Whisper בודק שלושה קבצים; Gemma בודק אחד. המודלים הקיימים בתיקיית האפליקציה נשארים שמישים גם לפני הבחירה; אין מעבר הרסני או כפוי.

## מחר במק

המודלים כבר בפיקסל, ולא צריך להורידם במק. בנייה עדיין צריכה JDK/Android SDK, תלויות Gradle וספריות runtime ציבוריות; הפרדת המשקלים אינה מבטיחה בנייה offline במחשב חדש. אם הרשת הארגונית חוסמת תלויות אלו, ניתן לקחת את שני ה־APK שנבנו היום ולהתקינם באמצעות adb במק בלי לבנות מחדש.

עדכון ללא הסרה דורש אותה חתימה. עם מקור עצמאי מאומת ניתן גם להסיר ולהתקין בחתימה חדשה ואז לבחור שוב את תיקיית המודלים; המקור נשאר, עותקי העבודה והרשאות/הגדרות האפליקציה נוצרים מחדש.

**ההקלטות והתמלילים עדיין פרטיים לאפליקציה ויימחקו בהסרה.** ההפרדה הזו מוגבלת למודלים. אל תסיר אפליקציה עם שיחות שתרצה לשמור לפני הוצאת JSONL ו־PCM וגיבוי פרטי שלהם. גם בדיקת parity של Whisper תידרש מחדש לאחר הסרה.

## מעבר לדסקטופ Windows — לקח מהתקנת 17.10.2026

בבדיקת Pixel 10 Pro עם Android build `CP3A.261005.005`, העתקת working copies עם `adb shell cp` יצרה קבצים בבעלות shell: SHA היה תקין, אך Gemma נכשל ב־`EACCES` ו־BlueTTS לא היה נגיש לאפליקציה. ייבוא דרך מסכי האפליקציות פתר את בעיית הגישה. בדיקת hash מה־shell אינה תחליף לפתיחת הקובץ על ידי האפליקציה.

אחרי התקנה נקייה או שינוי חתימה, השתמש בזרימה שנבדקה:

1. גבה מחוץ לריפו את כל הנתונים הפרטיים, ה־APKs ומפתח החתימה לפני הסרה. אמת שמקורות Documents שלמים. שינוי מחשב אינו מחייב הסרה אם המפתח המקורי זמין.
2. בנה את שלוש אפליקציות הניסוי עם אותו מפתח. שמור את המפתח גם למעבר המחשב הבא; התקנה מחדש מוחקת הרשאות SAF ו־parity gates.
3. ב־Whisper ובמסך מודל ה־LLM בחר **מודלים — מקור עצמאי ב־Documents** → `Documents/Mosaic/models`, והמתן להודעת **המודלים מוכנים** בכל אפליקציה.
4. במסך השיחה בחר **הכנת הקול BlueTTS** → `Documents/Mosaic/tts/blue-voice-pack` (תיקיית המשנה שמכילה manifest), והמתן להודעת **הקול מוכן**. השאר את האפליקציה בחזית בזמן כל ייבוא.
5. ייבא את Zipformer דרך המסך שלו, או שחזר מודלים ו־lock מאומתים לגיבוי הפרטי באמצעות `run-as` תחת UID החבילה. אל תשחזר receipt/parity gate כהוכחה לבדיקת מכשיר חדשה.
6. בחר את מנוע ה־ASR המבוקש ובצע **הכנת מנועים לשיחה**. ודא ביומן terminal `complete` ושלושה שחרורי worker עם ראיית binder death. הכנה אינה בודקת דיבור או ניגון; Whisper דורש parity stage6 מחדש אחרי הסרה.

בהתקנת הדסקטופ הייבוא המקומי והכנת Zipformer → Gemma → BlueTTS עברו. הזהויות, זמני ההכנה והבדיקות שעדיין חסרות מתועדים ב־[עדכון ASR-20](ASR_EXPERIMENT_HISTORY.md#asr-20--התקנה-מחדש-בדסקטופ-ושחזור-מקומי-17102026-asiajerusalem). אין צורך בהורדת משקלים חדשה, ולא בוצע merge של ענפי הניסוי.

## בדיקה, קבלה ו־rollback

בדיקות host בודקות SHA, גודל, קלט קצר/גדול/פגום, ביטול, כשל מקור וחזרה לייבוא, תוך שימור עותק תקין. שתי הבניות והבדיקות עברו על code commit `582a6496d7df195e271b6cac603145939469613e`: [LLM APK](https://github.com/adarcook/mosaic-android/actions/runs/38124356245), [ASR APK](https://github.com/adarcook/mosaic-android/actions/runs/38124356274). קובצי ה־APK נמצאים ב־Artifacts של הריצות האלה. נבדקה גם סימולציית סקריפט ההעברה לשימור מקור/יעד בכשל; לא בוצעה העברה למכשיר בפועל. בדיקת מכשיר נדרשת ל־SAF, מקום פנוי, ייבוא, טעינת TPU ושימור המקור לאחר הסרה; CI אינו מחליף זאת.

בדוק ייבוא וטעינה עם גרסאות `0.10-independent-model-source` (ASR) ו־`0.3-g5-dispatch-compatibility` (LLM; ראה [ניסוי ASR-21 / LLM-02](ASR_EXPERIMENT_HISTORY.md#asr-21--llm-02--קריסת-dispatch-בטעינת-gemma-ותיקון-תאימות-מועמד-11102026)) תחילה בלי להסיר. בדיקת התקנה מחדש אפשר לבצע ב־LLM אחרי גיבוי יומנים פרטיים; לא נדרש להסתכן בארכיון ASR כדי לבדוק את העיקרון.

Rollback: המשך טעינה מקובצי העבודה הקיימים בגרסה הקודמת. אין שינוי במשקלים, גרפי TPU, Beam או תמלול. המקור ב־Documents נשאר גם ב־rollback.

מקור Android: [Storage Access Framework — independent files survive uninstall](https://developer.android.com/training/data-storage/shared/documents-files).

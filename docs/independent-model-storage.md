# מודלים עצמאיים בפיקסל — ASR-20

המקור הקבוע של המודלים נשמר בתיקייה משותפת בבעלות המשתמש:
`/sdcard/Documents/Mosaic/models`. הסרת אפליקציית ניסוי אינה מסירה תיקייה זו.

לכל APK יש כפתור **מודלים — מקור עצמאי ב־Documents**. בחר את תיקיית `models` וייבא. הקוד קורא רק את ארבעת השמות והזהויות המקובעים בניסוי; אינו משנה או מוחק את המקור, ואין הורדת רשת באפליקציה.

## איך זה עובד ומה המחיר

זו הפרדה בין **מקור עצמאי** לבין **עותק עבודה שניתן לשחזר**. מנועי native ממשיכים לטעון מאותה תיקיית עבודה שבה עבדו קודם. כעת אפשר לבנות אותה מחדש מהמקור שבטלפון גם אחרי הסרה והתקנה בחתימה אחרת.

- Whisper: שלושה קבצים, יחד 1,928,695,931 bytes (כ־1.93GB).
- Gemma: קובץ אחד, 3,113,545,589 bytes (כ־3.11GB).
- שני המקורות יחד: כ־5.04GB; עם עותקי העבודה, כ־10.08GB בסך הכול. בזמן החלפת עותק קיים ייתכן שנדרש מקום זמני נוסף בגודל אותו קובץ.
- עותק עבודה קיים עם גודל ו־SHA תקינים נבדק ונשאר במקומו; אין העתקה מחדש בכל פתיחה/שאלה.
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

## בדיקה, קבלה ו־rollback

בדיקות host בודקות SHA, גודל, קלט קצר/גדול/פגום, ביטול, כשל מקור וחזרה לייבוא, תוך שימור עותק תקין. שני workflows כוללים אותן ובניית מסך Android משותף. בדיקת מכשיר נדרשת ל־SAF, מקום פנוי, ייבוא, טעינת TPU ושימור המקור לאחר הסרה; CI אינו מחליף זאת.

בדוק ייבוא וטעינה עם גרסאות `0.10-independent-model-source` (ASR) ו־`0.2-independent-model-source` (LLM) תחילה בלי להסיר. בדיקת התקנה מחדש אפשר לבצע ב־LLM אחרי גיבוי יומנים פרטיים; לא נדרש להסתכן בארכיון ASR כדי לבדוק את העיקרון.

Rollback: המשך טעינה מקובצי העבודה הקיימים בגרסה הקודמת. אין שינוי במשקלים, גרפי TPU, Beam או תמלול. המקור ב־Documents נשאר גם ב־rollback.

מקור Android: [Storage Access Framework — independent files survive uninstall](https://developer.android.com/training/data-storage/shared/documents-files).

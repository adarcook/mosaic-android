# בניית APK בלי להוריד שוב את המודלים

**עדכון ASR-20:** אפשר לשמור מקור עצמאי בפיקסל ולשחזר ממנו עותק עבודה גם אחרי הסרה: [הכנה היום ב־WSL](independent-model-storage.md). ההוראות הישנות להעברה לתיקיית האפליקציה עדיין נתמכות, אבל המקור העצמאי עדיף למעבר מחשבים.

## מה מופרד כבר עכשיו

| רכיב | איפה נשמר | נדרש במק לבנייה? |
|---|---|---|
| קוד, Gradle והגדרות הניסוי | Git, ענף `experiment/tensor-local-llm` | כן |
| ספריות ריצה ציבוריות של Tensor | `jniLibs` מקומי, נוצר מסקריפטים עם SHA מקובע ונארז ב־APK | כן; הפקודה מכינה אותן |
| משקלי Whisper והגרפים המקופלים ל־G5 | תיקיית האפליקציה הקיימת בפיקסל; עותקי מקור פרטיים מחוץ ל־Git | לא |
| Gemma G5, כ־3.1GB | `/sdcard/Android/data/life.mosaic.tensorllm/files/gemma-4-E2B-it_Google_Tensor_G5.litertlm` | לא, אם כבר הועבר לפיקסל |
| Android SDK, JDK, מפתח חתימה | מקומיים לכל מחשב; אינם נשמרים ב־Git | כן |

ה־APK אינו כולל משקלי מודל. אין צורך לייצא/לקמפל מודלים מחדש עקב מעבר מחשב: הטלפון נשאר Tensor G5. פקודת הבנייה החדשה אינה מורידה, מעתיקה או משנה משקלים. היא עשויה להוריד ספריות ריצה ציבוריות, תלויות Gradle וקוד native שנדרשים לבנייה. `clean` אינו מוחק מודלים בפיקסל.

## תיקון השגיאה הנוכחית ב־WSL

`tensor-whisper` נבנה בהצלחה. `tensor-llm` הוא פרויקט Gradle עצמאי ולכן לא ירש את `local.properties` מהמודול הראשון. שתי אזהרות Manifest אינן הסיבה לכשל ה־SDK.

אחרי עדכון הענף:

```bash
cd ~/git-workspaces/mosaic-android
git fetch origin
git switch experiment/tensor-local-llm
git pull --ff-only origin experiment/tensor-local-llm
python3 scripts/build_tensor_apps.py --only llm
```

הפקודה מאתרת SDK לפי `--sdk`, משתני `ANDROID_HOME`/`ANDROID_SDK_ROOT`, קובצי `local.properties` קיימים (תחילה Whisper), או נתיבי ברירת המחדל של macOS/Linux. הגדרה מפורשת לא תקינה מחזירה שגיאה ולא מוסתרת באמצעות fallback. היא כותבת `sdk.dir` רק ב־`local.properties` של המודולים שבונים, משמרת הגדרות אחרות ומעבירה SDK זהה ל־Gradle. קבצים אלה מתעלמים ב־Git.

אם איתור אוטומטי לא הצליח, הצבע על SDK המקומי של Linux/WSL, למשל:

```bash
python3 scripts/build_tensor_apps.py --sdk "$HOME/Android/Sdk" --only llm
```

נדרש JDK 21 לבניית LLM; ב־WSL קיים אפשר לבחור `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` אחרי התקנת JDK 21. דגל `--check` בודק SDK ו־Java בלי כתיבה, הורדה או בנייה.

## מעבר למק — הכנה חד־פעמית

1. שכפל את הריפו ובחר את הענף הניסיוני לעיל. אל תעתיק `local.properties` או cache של Gradle/NDK מ־WSL למק.
2. התקן Android Studio ואת SDK Platform 35, Platform Tools, NDK `27.0.12077973` ו־CMake `3.22.1` דרך SDK Manager. כלי הבנייה צריכים להיות עבור המק; SDK שנמצא ב־Windows אינו תחליף.
3. התקן JDK 21. הסקריפט משתמש ב־`JAVA_HOME` אם הוגדר; אחרת במק הוא מבקש JDK 21 דרך `/usr/libexec/java_home -v 21`. Python 3 נדרש להרצת הסקריפט.
4. העבר באופן פרטי את **מפתח החתימה המדויק שחתם על האפליקציות המותקנות**. בבניית debug מקומית ברירת המחדל היא `~/.android/debug.keystore` במחשב שבו נבנה ה־APK. אפשר לאמת חתימת APK מול ההתקנה בעזרת `apksigner verify --print-certs`; אין להניח שמפתח CI או מפתח ממחשב אחר זהים.

שמור עותק פרטי של המפתח לפני המעבר. במק צור `~/.android` והעבר את המפתח לשם כ־`debug.keystore`; אם כבר קיים מפתח במק, גבה אותו לפני החלפה. אין להעלות מפתח ל־Git או לשתף אותו כאן. אם השתמשת בחתימה מותאמת, העבר את אותו מפתח ואת ההגדרות המקומיות שלה במקום מפתח debug.

מפתח חדש יוביל ל־`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. במקרה כזה חזור למפתח הקיים; **אל תסיר את האפליקציה כדי לפתור זאת**, כי הסרה מוחקת נתונים ומשקלים מתיקיית האפליקציה. עדכון `adb install -r` עם אותו package ואותה חתימה שומר את הקבצים, כולל המודלים וההקלטות.

## בנייה והתקנה במק בכל עדכון קוד

מתוך הריפו:

```bash
python3 scripts/build_tensor_apps.py --check
python3 scripts/build_tensor_apps.py
```

אפשר `--only whisper` או `--only llm`. הפקודה בונה debug; ל־LLM היא מריצה גם `testDebugUnitTest`. SDK מחוץ לברירת המחדל אפשר לבחור ב־`--sdk /absolute/path/to/sdk`.

התקנה, לאחר העברת מפתח החתימה:

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices
"$ADB" install -r poc/tensor-whisper/build/outputs/apk/debug/MosaicTensorWhisperGate-debug.apk
"$ADB" install -r poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk
```

אם ה־SDK בנתיב אחר, שנה את `ADB` בהתאם. במק משתמשים ב־adb המקומי, ללא `adb.exe` או `wslpath`. אין פקודת push למודלים בכל עדכון.

אם Gemma עוד לא הועבר לפיקסל, הורד/העבר אותו פעם אחת לפי [מדריך LLM](tensor-local-llm.md). אפשר להעביר למק עותק שכבר הורד מהמחשב הישן במקום להוריד שוב. לא נדרש עותק במק אם המודל כבר נמצא בטלפון. שמור גיבוי פרטי של המודלים וההקלטות למקרה של החלפת טלפון או מחיקת אפליקציה; אל תכניס אותם לריפו.

## אימות וגבולות

בדיקות host מכסות איתור SDK מתוך המודול הקיים, ברירת מחדל של מק, נתיב עם רווחים ותווי Unicode, שימור הגדרות מקומיות, כשל על SDK מפורש שגוי ובנייה ללא בקשת משקלים. בניית LLM, בדיקות import והעלאת APK עברו ב־[CI על Linux](https://github.com/adarcook/mosaic-android/actions/runs/38122620186) ב־commit `cd9df39932fcc15a083fd24abb2458e80aa021a1`, באמצעות אותה פקודה וללא הורדת משקלים. בנייה והתקנה בפועל במק ושימור קבצים בפיקסל אחרי מעבר מחשב עדיין דורשים בדיקה אצל המשתמש; אין שינוי במודל, ב־CPU/TPU או בפרמטרי תמלול עקב שינוי זה.

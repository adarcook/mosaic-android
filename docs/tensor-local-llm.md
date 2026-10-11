# תמליל → LLM מקומי על Tensor G5 — LLM-01 / ASR-19

ניסוי נפרד מעל ASR-18, לא שינוי בארכיטקטורת Room/Firebase/Core ולא שלב roadmap שהושלם.
המודל הנבחר: **Gemma 4 E2B IT בקובץ AOT ייעודי ל־Tensor G5**. זהו מועמד למודל כללי מקומי לסיכום ושאלות, לא הבטחה לאיכות עברית או ליכולת כמו מודל ענן גדול. איכות, מהירות וזיכרון דורשים בדיקת Pixel.

## מה מחובר

1. שלב 9 ב־Mosaic TPU ASR Gate מקליט ושומר אודיו ותמליל כבעבר.
2. לאחר השלמה, בוחרים הקלטה ולוחצים **סיכום ושאלות עם המודל המקומי**.
3. Mosaic Local LLM מקבל גישת קריאה ליומן המסוים, בודק terminal complete וכיסוי רציף, ומציג את המקטעים לבדיקה/תיקון.
4. לחיצה על סיכום או שאלה מפעילה Gemma מקומי. אין הפעלה אוטומטית של הוראות מתוך תמליל.
5. התשובה והמדדים נשמרים בקובץ פרטי `files/llm-sessions/llm-*.jsonl` באפליקציית ה־LLM. יומן ה־ASR המקורי נשאר ללא שינוי.

יש שתי אפליקציות ניסוי כדי לבודד את גרסת LiteRT של Whisper מ־LiteRT-LM ומספריותיו. ל־LLM אין הרשאת אינטרנט, גישה להודעות, מיקרופון או כלים. ההעברה מקומית דרך URI עם grant לקריאה של JSONL אחד; אין העברת PCM או העלאה. ניתן גם לפתוח JSONL שהוצא קודם דרך בוחר קבצים, או לכתוב טקסט ידנית.

בחירת `Backend.NPU(nativeLibraryDir)` מפורשת, עם model G5 מקובע ובלי fallback ל־CPU/GPU. היא מבקשת האצת TPU לרשת; tokenization, orchestration ו־sampling עדיין כוללים CPU. **Backend מבוקש אינו לבדו הוכחת ריצת מאיץ**: בדיקת הקבלה כוללת לוג dispatch ו־native initialization. אין להציג את המודל כ־TPU validated לפני הרצת מכשיר.

## זהויות לשחזור

| פריט | ערך |
|---|---|
| baseline | `6639c00eb2ba2da1e2de35dfdca58e8a34b92773`, PR #34 פתוח |
| ענף | `experiment/tensor-local-llm` |
| ASR APK | `life.mosaic.tensorwhisper`, 0.9-local-llm-handoff |
| LLM APK | `life.mosaic.tensorllm`, 0.1-gemma4-g5 |
| מנוע | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| Tensor dispatch | LiteRT v2.2.0 public runtime archive; SHA-256 `b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d` |
| model repo | `litert-community/gemma-4-E2B-it-litert-lm` |
| revision | `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` |
| model file | `gemma-4-E2B-it_Google_Tensor_G5.litertlm` |
| model size | 3,113,545,589 bytes, כ־3.1GB בדיסק; אינו נתון זיכרון runtime |
| SHA-256 | `af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc` (LFS metadata; מאומת גם לאחר הורדה ובכל בקשה במכשיר) |
| sampling | topK 20, topP 0.9, temperature 0.2, seed 0 |
| limits | 512 output tokens, thinking/speculative off; context לפי metadata המודל |

אין export/AOT חדש אצלנו: משתמשים בקובץ G5 שהופץ על ידי LiteRT Community. הקובץ הגנרי או GPU, ומודל ל־G6, אינם תחליף. ABI/runtime/model compatibility של השילוב המקובע עדיין נבחנת בפיקסל. מנוע Whisper ו־dispatch שלו נשארים ב־2.1.6 באפליקציה המקורית.

## בנייה והתקנה ב־WSL

השתמש באותו signing key של APK ה־ASR הקודם. אין להסיר אותו.

LiteRT-LM 0.18.0 פורסם עם class-file Java 21. לפני בניית LLM נדרש JDK 21 (גם כאשר source/target של קוד האפליקציה נשארים 17). ב־Ubuntu/WSL אפשר להתקין ולבחור אותו:

```bash
sudo apt-get update
sudo apt-get install openjdk-21-jdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

```bash
cd ~/git-workspaces/mosaic-android
git fetch origin
git switch experiment/tensor-local-llm
python3 scripts/prepare_tensor_whisper_runtime.py
python3 scripts/prepare_tensor_llm.py --model-dir ~/mosaic-private/llm
./gradlew -p poc/tensor-whisper assembleDebug
./gradlew -p poc/tensor-llm testDebugUnitTest assembleDebug

ADB=/mnt/c/Users/orele/AppData/Local/Android/Sdk/platform-tools/adb.exe
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-whisper/build/outputs/apk/debug/MosaicTensorWhisperGate-debug.apk)")"
"$ADB" install -r "$(wslpath -w "$(realpath poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk)")"
"$ADB" shell am start -n life.mosaic.tensorllm/.LlmActivity
```

אחרי שהאפליקציה החדשה נפתחה פעם אחת (כדי ליצור את תיקיית המודל):

```bash
"$ADB" push "$(wslpath -w "$(realpath ~/mosaic-private/llm/gemma-4-E2B-it_Google_Tensor_G5.litertlm)")" \
  /sdcard/Android/data/life.mosaic.tensorllm/files/gemma-4-E2B-it_Google_Tensor_G5.litertlm
```

הסקריפט בלי `--model-dir` מכין רק את ספריית ה־dispatch הציבורית (גם ב־CI); משקלים אינם בתוך APK או Git. נדרשים כ־3.1GB פנויים במחשב ובטלפון, נוסף על המודלים הקיימים. קובץ `.part` במחשב מוחלף רק אחרי hash/size תקינים. אם Hugging Face מחזיר 401/403, היכנס לאתר ואשר את תנאי המודל אם נדרש; הסקריפט יכול לקרוא `HF_TOKEN` מהסביבה בלי להדפיס אותו. אין להכניס token לפקודה משותפת/לריפו. אין בקשת הרשמה חדשה ל־Tensor SDK: dispatch ציבורי ומודל מוכן.

## בדיקת Pixel

1. פתח Mosaic Local LLM וכתוב ידנית: `דנה אמרה שהפגישה ביום שלישי בשעה 14:30. יואב יכין את המסמך עד יום שני.` בקש סיכום, ואז שאל `מי מכין את המסמך ומתי?`.
2. ודא שהטעינה והיצירה מסתיימות בלי קריסה/timeout, ובעברית עם שמות ותאריך נכונים. קבל את לוג ה־NPU לפני סימון acceleration passed.
3. פתח שלב 9 באפליקציית ASR, בחר את שיחת הבסיס ולחץ על כפתור המודל המקומי. בדוק שאין מקטע חסר, תיקון או קיצור סמוי. המקטעים מוצגים בנפרד עם מספריהם; חזרות קיימות אינן מוסתרות באמצעות LLM.
4. בקש סיכום ושאל שאלה שהתשובה עליה קיימת במקור. שאל גם דבר שלא נאמר ובדוק שהמודל מציין זאת.
5. חזור על prompt זהה, תוך שמירת model/source/reviewed-text hashes, זמני verify/load/generate/first-text-chunk, PSS/thermal. first_text_chunk אינו token count, ואין לחשב ממנו tokens/sec. הרצת ערבוב ASR ו־LLM אינה חלק מהבדיקה; שלח רק אחרי שה־ASR הסתיים.
6. צא מהמסך בזמן יצירה; ודא שהעיבוד נעצר והתמליל נשאר זמין. זה retry של בקשת LLM חדשה, לא resume של tokens.

```bash
"$ADB" logcat -c
# הרץ בקשת סיכום אחת, ואז:
"$ADB" logcat -d > /mnt/c/Users/orele/Downloads/mosaic-llm-logcat.txt
"$ADB" shell run-as life.mosaic.tensorllm ls files/llm-sessions
# החלף ACTUAL_FILE בשם שהוצג:
"$ADB" exec-out run-as life.mosaic.tensorllm cat files/llm-sessions/ACTUAL_FILE.jsonl \
  > /mnt/c/Users/orele/Downloads/mosaic-llm.jsonl
```

logcat עשוי להכיל מידע אחר מהמכשיר; סנן אותו לתהליך הניסוי לפני שיתוף. התמליל, השאלה והתשובה ביומן הם פרטיים, ולא נכנסים לריפו. הם נשארים במכשיר עד מחיקה ידנית/הסרת אפליקציית ה־LLM. אין cloud fallback או telemetry בקוד הניסוי.

## מגבלות ו־rollback

זה slice ראשון: סיכום ושאלה על תמליל נבחר, ללא זיכרון בין שאלות במודל, חיפוש בכלל הארכיון, tools, wake word, TTS, תמונות או רקע. כל שאלה משתמשת בתמליל הנוכחי; שאלות/תשובות קודמות נשמרות ביומנים אך אינן מוזנות אוטומטית כ־chat history. הוראות system הן הכוונה ולא הוכחת עמידות מול prompt injection; אין כלים לביצוע ולכן אין פעולות צד שלישי.

תמליל מוגבל ל־12,000 תווים ושאלה ל־2,000. אלה גבולות העברה/UI, לא מספר tokens; context overflow עשוי עדיין להחזיר שגיאה, ואין truncation שקט. פלט מוגבל ל־512 tokens ועשוי להסתיים בקצה התקציב. load/hash בכל בקשה, מסך פתוח, worker פרטי עם timeout UI של 240 s ו־generation budget של 150 s. timeout/exit אינם מבטיחים terminal ביומן; רק end complete הוא תשובה שהושלמה. סיכום נשמר ונשלח לפני native cleanup.

rollback: המשך שימוש בשלב 9 בלי שליחה ל־LLM, או בנייה של baseline `6639c00` עם אותו signing key. אין צורך למחוק נתוני ASR. ענף זה לא מוזג ל־main. בדיקות host/CI אינן מאשרות האצת TPU או עברית במכשיר.

## מקורות

- [Google LiteRT-LM Android API](https://developers.google.com/edge/litert-lm/android)
- [Google NPU / Tensor setup](https://developers.google.com/edge/litert/next/litert_lm_npu)
- [Gemma 4 E2B model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [G5 artifact at pinned revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_Google_Tensor_G5.litertlm)

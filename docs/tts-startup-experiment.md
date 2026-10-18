# ASR-31 / VOICE-08 — טעינת קול בפניות קצרות

17.10.2026 Asia/Jerusalem. סטטוס: בדיקת רכיב במכשיר עברה, מועמד מותקן; פנייה מלאה חדשה ויציבות ממושכת Pending. אין השלמת roadmap או merge.

## השערה, בקרות וקבלה

הפנייה האחרונה ב־Voice0.10 עברה והמשתמש אישר שתיקון סוף המילה עבד, אך המהירות אינה מספקת. capture-end→first-play marker=12,237ms; BlueVoice load3,595ms, synthesis2,504ms, playback5,626ms. Playback אינו כולו המתנה לפני תשובה. השערה: packing של משקלי Renikud עולה יותר ממה שהוא חוסך בפנייה עם מעט מקטעים, משום שה־worker נסגר בכל פעם.

תנאי הקבלה לפני השוואת המועמד: קיצור טעינה וזמן עד יצירת מקטע ראשון באותו טקסט, תוך שימור IPA וגלי הקול שנבדקים. לא נקבע מראש סף מספרי לשיפור. לא נמדדה כאן השרשרת המלאה.

| זהות | ערך |
|---|---|
| Baseline / rollback | `5e28e357e06fd22930f7b61cfc71501ec41d88ee`, Voice0.10(8), APK SHA=`9aef7997c346942cd992642c798a80824effecd86d46c8acfc90a94bf765d5c7` |
| Candidate | קוד `c7b2ce0353703a9034f9c4173a9be88fc3eee95f`, Voice`0.11-tts-load-trial`(9), APK28,802,191bytes, SHA=`655331548818f31d66604c3d70c5157b8a079557fb27342103504fa0636bd84a` |
| מכשיר / runtime | Pixel10 Pro / Tensor G5; `google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys`; ORT Android1.24.3, JDK22/Gradle8.9, אותה חתימת desktop |
| Renikud | FP32, revision `679c56ca449d41873fb8ff7711ddf7563d28198f`, 1,234,209,530bytes; SHA=`c3a2bdc35afbf53f28cdece4672dcca9b530dfa43a0d65b4c5bbc47150b32d0d` |
| TTS וקלט | BlueTTS2.5, קול libri_male_6209, אותו pinned pack של ASR-22, style/steps/seed123 ו־chunking. אותה תשובה פרטית: 73תווים/126UTF-8bytes, SHA=`4bc7e5cff2051af03585b20b8e3f8942377627f77a6e02bef809d1e7d1de6ec6`, שני מקטעים |

הגורם ששונה: `options.addConfigEntry("session.disable_prepacking", "1")` ב־Renikud בלבד. [הגדרת ORT1.24.3](https://github.com/microsoft/onnxruntime/blob/v1.24.3/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h) מאשרת שזה מבטל prepacking. שאר graph optimizations נשארות ברירת מחדל; אין שינוי במשקלים, shapes, precision, קוד MAP או prompt. ייצוג משקלים פנימי וחישוב kernel עשויים להיות שונים. `g2p_load_policy=cpu_no_prepack` נוסף למדדי TTS כולל prepare. Renikud וכל BlueTTS נשארים CPU/2 intra-op threads/1 inter-op. Gemma NPU ו־ASR לא הופעלו ב־benchmark; אין GPU או חפיפת native engines חדשה.

## בדיקת המכשיר ותוצאות

harness פרטי חתום מפעיל Instrumentation נגד חבילת Voice. הוא יוצר את BlueVoice האמיתי באמצעות reflection, מאפס seed, משתמש ב־VoiceText של ה־APK, מודד constructor ו־synthesize ושומר SHA של float32-LE לכל מקטע. אחר כך נבדק IPA בארבעה טקסטים סינתטיים ובתשובה. AudioTrack אינו מופעל; זה אינו acoustic onset או השלמת playback. baseline נמדד לפני `install -r` של candidate. אין הקלטה חוזרת או microphone playback, אך זו השוואה אחת בסדר קבוע וללא cache/order randomization.

| מדד באותו טקסט | Baseline | Candidate |
|---|---:|---:|
| BlueVoice constructor, כולל native initialization | 5,204ms | 2,406ms |
| synthesis מקטע ראשון | 1,328ms | 1,676ms |
| טעינה + יצירת מקטע ראשון | 6,532ms | 4,082ms |
| synthesis שני מקטעים | 2,586ms | 2,879ms |
| טעינה + כל יצירת הקול | 7,790ms | 5,285ms |
| frames | 125,952 / 110,592 | זהה |
| IPA בארבע דוגמאות ובתשובה | baseline | זהה |

גלי הקול זהים bit-for-bit: SHA=`6ac78e86deebad3ea64c6c0afebdee63865aec4bb3dedfa637a397e5ba3dd852` ו־`499c1cbf7bf035389af29dab534f1046d0f49340b3b81662d1465f1dc4a867ab`. חיסכון עד מקטע ראשון2,450ms, ועד כל יצירת הקול2,505ms. מקור5.204s כאן שונה מ־load3.595s ביומן הפנייה; אין לערבב גבולות תזמון כאילו הם אותה ריצה. אין עדיין מדידת פנייה מלאה חדשה במועמד.

בדיקת Renikud מוקדמת: load3,283→1,295ms, ארבעה inputs652→987ms; logits max abs=`2.47955322265625e-5`, 12מערכי פלט. זה אינו bit-parity. ה־IPA והגלים נבדקו בנפרד בהמשך; אין הוכחת זהות בכל טקסט או corpus של שמות/שלילות/מספרים.

חלופות שנפסלו לפני שינוי המוצר: optimized ONNX נטען ב־2,658ms מול מקור2,556ms, יצירה4,447ms, cache1,231,137,213bytes; ORT format עם mmap ללא העתקת initializers ועם prepacking רגיל נטען ב־2,772ms מול מקור2,376ms, יצירה5,878ms, קובץ1,231,469,192bytes. logits בשתיהן היו זהים אך לא היה שיפור טעינה. [תיעוד serialization של ORT](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html) אינו מבטיח שזה צוואר הבקבוק במודל הזה. אין cache חדש במוצר.

40 בדיקות JUnit ו־assembleDebug עברו ב־Windows. בדיקת native היא ראיית השינוי; לא נוספה בדיקה שרק מחקה דגל קונפיגורציה. CI ופנייה מלאה במועמד עדיין Pending. אין peak RAM, sustained throughput, תדרים או מסקנה תרמית. תשובות ארוכות עלולות לשלם יותר inference בגלל היעדר packing ויש למדוד אותן לפני אימוץ קבוע.

## שימור, החלטה ו־rollback

חבילת `latency-20261017` פרטית ומחוץ ל־Git כוללת harness source/APK, טקסט ו־APKs. תוצאת native baseline877bytes SHA=`e24b529c17cca6543b563b2c2b26ea1fb1b7bb2f48e866d35982701f92085229`; candidate869bytes SHA=`5270c5927ab6ef3f1ad9b042c6ef5e1f4d8ebc19806ce972c613ed57d22cf35b`. probes מוקדמים: optimized ONNX315bytes SHA=`634be103b17577f2237f0277ab288660e9e70b775c4461c3433174b013121eaa`; prepacking218bytes SHA=`063d0310e86eec470b5211bc0aa18ebeeb70ca8c90e332db855806722e62b2b2`; ORT mmap250bytes SHA=`de5d0125ffab24cb4c1cba6e1c7af0be6eee2d0a770ad3fe83493d2b1f46ac12`.

החלטה: לשמור כמועמד מותקן לפניות קצרות. הצעד הבא: פנייה מלאה רגילה ואחריה פניות חוזרות ותשובה ארוכה, עם first-play/load/synthesis וגודל תשובה. rollback ל־Voice0.10 החתום במפתח הדסקטופ, `install -r -d` אם נדרש downgrade; אין הסרה, איפוס או שינוי למודלים. main וניסויי הקול אינם merged.

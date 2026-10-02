# Hebrew voice feasibility probe (Pixel 10 Pro)

Experimental, debug-only work authorized on 2026-10-02. This is a feasibility
checkpoint before an architecture re-baseline, not a completed roadmap stage.
The current architecture remains in force until device results are reviewed.

## Scope

Open **Mosaic Voice POC**, grant microphone permission, say one Hebrew sentence,
inspect the transient transcript and hear a fixed Hebrew reply. The protein value
in the reply is explicitly sample data; no meal or memory is created.

The small diagnostics screen is test equipment, not the proposed product UI.
The experiment uses the installed default Android TTS engine and explicitly
selects an installed Hebrew voice with `network=false`. Recognition uses
`createOnDeviceSpeechRecognizer`, checks installed Hebrew language support,
and fails visibly rather than switching to a cloud recognizer. An available
on-device service does **not** imply that Hebrew is installed or supported.

The separate **בדיקת קול בלבד** button tests TTS even when Hebrew STT fails.
Leaving the activity stops recognition and playback. Transcripts exist only in
the screen's memory; the probe does not log or persist them. There is no LLM,
background service, wake word, conversational loop, barge-in or domain write.
Neither background/locked-phone activation nor Hebrew LLM quality is proven by
this experiment. Existing application features retain their existing behavior.

All code, microphone permission and launcher entry are under `app/src/debug`;
release builds do not include the probe. No new library or model is bundled.
On this experimental branch the debug application ID is
`life.mosaic.fit.voicepoc`, so installation is separate from existing Mosaic and
cannot downgrade its Room database (including an installed PR #21 v4 database).
The usual Mosaic launcher activity is disabled in the probe's debug manifest.

## Build and install on Windows

From the existing checkout (do not uninstall Mosaic or clear its data):

```powershell
git fetch origin
git switch feature/hebrew-voice-poc
gradle :app:assembleDebug
gradle :app:installDebug
```

If your local checkout has a Gradle wrapper, use `./gradlew.bat` in place of
`gradle`. The repository CI uses Gradle 8.9 / Java 17 / Android SDK 35.

Launch **Mosaic Voice POC** from the phone launcher, or:

```powershell
adb shell am start -n life.mosaic.fit.voicepoc/life.mosaic.fit.voicepoc.VoicePocActivity
```

If the installed TTS engine has no local Hebrew voice, use Android Settings →
Text-to-speech output → engine settings → install voice data, where supported,
then reopen the probe. Downloading voice data is setup, not evidence that
synthesis subsequently works offline. If Hebrew STT is missing, record the
reported installed languages/error; keyboard dictation availability alone is
not evidence for this API. Model provisioning or a bundled STT is a follow-up.

## Device acceptance gate

First prepare any voice data with connectivity. Then enable airplane mode and
explicitly disable Wi-Fi and mobile data. Keep media volume audible.

1. Open the probe, allow mic access and speak: `כמה חלבון נשאר לי היום?`
2. Repeat with `אכלתי מאתיים גרם עוף ושתי ביצים` and `מחר אני רוצה לשחות`.
3. Compare each transcript to what was said and rate the Hebrew reply for
   pronunciation and intelligibility, especially the number 42.
4. Record `STT ready→result`, `STT end→result` where present, `TTS start`, selected
   voice name and any error. TTS start is a service callback, not a measurement
   of acoustic speaker latency. No language support is claimed before this run.
5. Press Home while listening and while speaking; both must stop. Reopen and
   press retry. Deny microphone permission once and verify a clear error.
6. Test **בדיקת קול בלבד** independently, including if recognition is unavailable.

Record results locally; do not commit personal transcripts:

| Check | Result |
|---|---|
| Android version / speech-engine version | Pending device test |
| Local Hebrew STT available | Pending device test |
| Three phrase accuracy | Pending device test |
| Local Hebrew voice / quality | Pending device test |
| Latency measurements | Pending device test |
| Fully offline run | Pending device test |
| Home / permission / retry behavior | Pending device test |

A positive result supports trying an on-device conversational slice. A negative
result identifies which speech component needs an alternative; it does not
decide the entire Mosaic architecture.

## API references

- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/reference/android/speech/RecognitionSupport
- https://developer.android.com/reference/android/speech/tts/Voice
- https://developer.android.com/reference/android/speech/tts/TextToSpeech

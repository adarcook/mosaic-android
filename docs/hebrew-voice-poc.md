# Local Hebrew voice POC — ivrit.ai and BlueTTS

User-authorized feasibility checkpoint on the existing `feature/hebrew-voice-poc`
branch / Draft PR #22. No architecture re-baseline or completed roadmap stage.
The first device probe found only English installed in Android's on-device STT
service, and the user judged the built-in Hebrew TTS voice too robotic.

## New behavior

The debug app installs separately as `life.mosaic.fit.voicepoc`. Open **Mosaic
Voice POC**, tap **דבר בעברית**, grant mic access and tap again. Record up to 12
seconds and press **סיום משפט**. The app transcribes the microphone's 16 kHz mono
PCM using the Hebrew-tuned ivrit.ai Whisper Large v3 Turbo model through a pinned
whisper.cpp JNI bridge, with language `he` explicitly selected. Then BlueTTS 2.5
runs its acoustic ONNX graphs on-device and plays a fixed Hebrew reply.

**בדיקת BlueTTS בלבד** tests synthesis without the microphone or transcription.
Both start buttons first check for missing/empty model files. An absent pack
shows setup instructions and does not request the microphone or load native
engines. APK installation alone does not install these model files. Native
library linkage failures are shown in the screen rather than escaping the
worker; pausing only cancels Whisper after its library was successfully loaded
and transcription began. Native process crashes still require Android crash logs.
The controls respect system-bar/cutout insets and have 48 dp extra top spacing.
Diagnostics show cold model-load plus inference/synthesis times. Every turn loads
and frees the models; this POC intentionally measures cold behavior, not an
optimized warm conversational service. CPU inference uses four threads. GPU/NPU
acceleration, free-form TTS, wake words, barge-in and background activation are
not implemented. The usual Mosaic launcher is disabled only in this debug probe.

### Important fixed-reply boundary

This is real acoustic synthesis on the phone, not playback of a pre-rendered WAV.
However, the fixed reply's Hebrew pronunciation/token IDs are prepared on the
host using BlueTTS's G2P frontend. The phone does **not** yet accept arbitrary
Hebrew text for speech. Preparing G2P input on the host keeps this one-turn probe
small and lets us assess voice quality and phone synthesis latency before porting
Renikud and text normalization. The reply explicitly marks protein values as
sample data. No nutrition, memory or other domain record is written.

The model preparation script also generates a host reference WAV using the same
fixed random noise. This reference is for quality/parity comparison only; the
phone never reads it. User audio/transcripts are neither persisted nor logged.
Home stops capture/playback and requests cancellation of native transcription;
BlueTTS checks cancellation between inference steps. An ONNX call already running
may finish in the worker before its resources can be freed, but its result is not
played after the activity is left. Keep the app foreground during the probe.

## Prepare models once on Windows (network required only for setup)

Use Python **3.12**, Git and ADB. The public model download is several GB; leave
ample disk space. Do not add weights or generated packs to Git. Run from the
repository root in PowerShell:

```powershell
git fetch origin
git switch feature/hebrew-voice-poc
git pull --ff-only
py -3.12 -m venv .voice-poc-venv
.\.voice-poc-venv\Scripts\python.exe -m pip install "git+https://github.com/maxmelichov/BlueTTS.git@0e38dbf08ed53f85863d1eab092bd9572c53a503" huggingface-hub
.\.voice-poc-venv\Scripts\python.exe scripts\prepare_voice_poc.py
.\gradlew.bat :app:installDebug
```

The checked-in wrapper pins Gradle 8.9, matching AGP 8.7.3 and CI. In Android
Studio's Gradle settings select the wrapper (`gradle-wrapper.properties`) and
JDK 17. Gradle 9 removes `Project.exec`, which this AGP native-build path needs;
using a locally generated Gradle 9 wrapper fails with `NoSuchMethodError`.
Android Studio may request NDK
27.0.12077973 and CMake 3.22.1. The first native build fetches whisper.cpp v1.8.3
at an exact commit. Only the debug app links this native module / ONNX dependency;
release APKs exclude the probe and its mic permission.

Launch the probe once to create its app-specific external directory. Copy the
pack (app must be stopped during replacement):

```powershell
adb shell am force-stop life.mosaic.fit.voicepoc
adb shell mkdir -p /sdcard/Android/data/life.mosaic.fit.voicepoc/files
adb push voice-models /sdcard/Android/data/life.mosaic.fit.voicepoc/files/
adb shell am start -n life.mosaic.fit.voicepoc/life.mosaic.fit.voicepoc.VoicePocActivity
```

The app verifies SHA-256 hashes from the generated manifest once per process,
then uses only local files. It never fetches speech models or sends audio to a
server. The setup pack uses exact upstream model revisions, a public LibriTTS
reference voice, a fixed reply, and matched stats/vocabulary. Model integrity
checks detect interrupted copies; they are not a security signature against an
attacker able to modify both the pack and manifest.

## Acceptance / validation

1. Try `כמה חלבון נשאר לי היום?`, `אכלתי מאתיים גרם עוף ושתי ביצים`,
   and `מחר אני רוצה לשחות`. Compare actual transcripts to what was spoken.
2. Test BlueTTS by itself and compare its Hebrew reply against `voice-models/reference.wav`.
3. Enable airplane mode, explicitly turn off Wi-Fi, and repeat both tests.
4. Record cold load/inference timings, voice quality, transcript errors and any
   heat. A 16 GB phone is not evidence of acceptable latency.
5. Deny microphone permission; TTS-only must still work. Press Home during
   capture, transcription, synthesis and playback; no delayed speech should occur.
6. Reopen and retry; rotate the device and ensure the old activity does not play
   audio. The UI is temporary test equipment, not the proposed product UI.

CI compiles the native bridge and APK, runs existing Fit tests, and tests CFG and
channel/time denormalization math. Device ABI/loading, voice quality, transcription
accuracy, waveform parity, offline performance and thermal behavior remain pending
until tested on the Pixel. No successful device result is claimed in this PR.

Host validation: the full model preparation script completed with the pinned
weights. A separate NumPy/ONNX replay of the Android inference sequence produced
304,128 finite samples at 44,100 Hz (about 6.9 seconds). Its maximum absolute
error against the upstream reference WAV was 0.000031, within 16-bit WAV rounding.
This validates the fixture and inference sequence on the host; it does not
establish Android runtime or device waveform parity.

## Sources and license notices

- whisper.cpp v1.8.3: `2eeeba56e9edd762b4b38467bab96c2517163158` (MIT)
  https://github.com/ggml-org/whisper.cpp
- ivrit.ai GGML weights: `2130c78e4a9cb4914cc4df91a1c3031407789705` (Apache-2.0)
  https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml
- BlueTTS ONNX bundle: `468da64b4a51795a7594a3637727dbaf876b6df2`
  https://huggingface.co/notmax123/BlueTTS2.5-onnx
- Acoustic inference port derived from BlueTTS (MIT), copyright its contributors:
  https://github.com/maxmelichov/BlueTTS
  Keep its license notice with redistributed implementations; see
  `app/src/debug/assets/blue-tts-license.txt`.
- Public voice `libri_male_6209`: source LibriTTS-R (CC BY 4.0, Google LLC),
  documented in the model card. This is not the model card's in-house female voice.
- ONNX Runtime for Android: https://onnxruntime.ai/docs/tutorials/mobile/
- Host-only RenikudPlus G2P model: `679c56ca449d41873fb8ff7711ddf7563d28198f`
  https://huggingface.co/notmax123/RenikudPlus
  The pinned repository has no optional datastore sidecar, so setup passes the
  model path explicitly and uses the model's own pronunciation predictions.

Production redistribution and arbitrary-text G2P require reviewing all model,
frontend and voice terms, plus device validation. This PR remains a personal
feasibility experiment.

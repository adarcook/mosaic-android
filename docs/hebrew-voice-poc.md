# Local Hebrew voice POC — Whisper Small Q5 and BlueTTS

User-authorized feasibility checkpoint on the existing `feature/hebrew-voice-poc`
branch / Draft PR #22. No architecture re-baseline or completed roadmap stage.
The first device probe found only English installed in Android's on-device STT
service, and the user judged the built-in Hebrew TTS voice too robotic.

## New behavior

The debug app installs separately as `life.mosaic.fit.voicepoc`. Open **Mosaic
Voice POC**, tap **דבר בעברית**, grant mic access and tap again. Record up to 12
seconds and press **סיום משפט**. The app transcribes the microphone's 16 kHz mono
PCM using multilingual Whisper Small Q5_1 through a pinned whisper.cpp JNI
bridge, with language `he` explicitly selected. This replaces the original
ivrit.ai Large v3 Turbo probe, which the user found unusably slow on the Pixel.
The smaller model is not Hebrew fine-tuned; its accuracy must be measured. Then BlueTTS 2.5
runs its acoustic ONNX graphs on-device and plays a fixed Hebrew reply.

**בדיקת BlueTTS בלבד** tests synthesis without the microphone or transcription.
Both start buttons first check for missing/empty model files. An absent pack
shows setup instructions and does not request the microphone or load native
engines. APK installation alone does not install these model files. Native
library linkage failures are shown in the screen rather than escaping the
worker; pausing only cancels Whisper after its library was successfully loaded
and transcription began. Native process crashes still require Android crash logs.
The controls respect system-bar/cutout insets and have 48 dp extra top spacing.
Whisper loads before microphone capture and is reused while this Activity exists;
it is freed after pending work completes when the Activity is destroyed. Diagnostics
separate STT load (including cache reuse), inference, and captured audio duration.
BlueTTS continues to load/free its sessions per turn. CPU inference uses four
threads. Native Debug builds now optimize inference with `-O3` while retaining
debug symbols. The stop control ends recording or cancels active processing;
inference displays elapsed seconds and uses a cooperative 30-second abort budget,
not a guaranteed hard timeout for every backend operation. Decoder fallback retries
are disabled, with one segment and at most 96 tokens for short commands. GPU/NPU
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
Home stops capture/playback and requests cancellation of that Activity's native transcription;
BlueTTS checks cancellation between inference steps. An ONNX call already running
may finish in the worker before its resources can be freed, but its result is not
played after the activity is left. Keep the app foreground during the probe.

## Upgrade an existing pack (STT only)

User's device result: BlueTTS sounded very good; the original transcription was
unusably slow. Keep the installed TTS model files and fixture unchanged. From the
repository root, after pulling the branch and installing the updated debug APK:

```powershell
.\.voice-poc-venv\Scripts\python.exe -X utf8 scripts\prepare_voice_poc.py --stt-only
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell am force-stop life.mosaic.fit.voicepoc
& $adb push voice-models/whisper/ggml-model.bin /sdcard/Android/data/life.mosaic.fit.voicepoc/files/voice-models/whisper/ggml-model.bin
& $adb push voice-models/manifest.json /sdcard/Android/data/life.mosaic.fit.voicepoc/files/voice-models/manifest.json
& $adb shell am start -n life.mosaic.fit.voicepoc/life.mosaic.fit.voicepoc.VoicePocActivity
```

This downloads approximately 190 MB and replaces only the STT weights and manifest.
It does not import NumPy/BlueTTS, rerun G2P, synthesize a reference, or alter TTS
hashes. The app explicitly rejects legacy STT packs for transcription so it cannot
silently continue loading the slow 1.6 GB model. TTS-only remains available with
an intact old pack. A full preparation below defaults to the small model too.

Test a short sentence twice in one Activity session: the second should report
`cached` for STT load. Compare transcript accuracy and inference time; a smaller
model and optimized native code are not proof of acceptable Hebrew performance.
Test cancel while transcribing, Home, and rotation; no delayed response should play.

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
.\.voice-poc-venv\Scripts\python.exe -X utf8 scripts\prepare_voice_poc.py
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
channel/time denormalization math. The user has run the APK and judged the fixed-reply TTS voice very good.
The original large-model STT was unusably slow. Smaller-model Hebrew accuracy,
latency, cancellation, cache lifetime, offline operation and thermal behavior
remain to be verified on the Pixel. No small-model device speedup is claimed.

Historical host validation for the original STT pack: the full model preparation
script completed with the pinned
weights. A separate NumPy/ONNX replay of the Android inference sequence produced
304,128 finite samples at 44,100 Hz (about 6.9 seconds). Its maximum absolute
error against the upstream reference WAV was 0.000031, within 16-bit WAV rounding.
This validates the fixture and inference sequence on the host; it does not
establish Android runtime or device waveform parity.

## Sources and license notices

- whisper.cpp v1.8.3: `2eeeba56e9edd762b4b38467bab96c2517163158` (MIT)
  https://github.com/ggml-org/whisper.cpp
- Multilingual Whisper Small Q5_1 GGML: `c521a4b02f422512d734391fdf08bb08c0862f68` (MIT)
  https://huggingface.co/ggerganov/whisper.cpp
  `ggml-small-q5_1.bin`, SHA-256:
  `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`
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

## Accuracy comparison: Small FP16 vs Q5

Beam 5 stays selected by default. To test the same multilingual Small model in
FP16 (487,601,967 bytes), preserving the BlueTTS fixture:

```powershell
.\.voice-poc-venv\Scripts\python.exe -X utf8 scripts\prepare_voice_poc.py --stt-only --stt-profile small-fp16
```

Then force-stop the app and push only `whisper/ggml-model.bin` and `manifest.json`
using the upgrade commands above. The app displays the installed profile and
per-stage timings. Compare the same Hebrew sentences in a quiet room. Accuracy
and speed improvement are unverified until measured on the Pixel.

To return to Q5, repeat preparation with `--stt-profile small-q5_1`, then push
those same two files. Hugging Face caches both downloads on the host.
FP16 SHA-256: `1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b`.

## Hebrew fine-tune trial

`ivrit-turbo-q5_0` uses the Hebrew fine-tuned ivrit.ai Large v3 Turbo model,
quantized by JoaoZaokk using whisper.cpp's quantizer. This is the family tried
before CPU optimizations, now Q5_0 rather than the original 1.62 GB FP16 file.
Source: https://huggingface.co/ivrit-ai/whisper-large-v3-turbo
Quantized artifact: https://huggingface.co/JoaoZaokk/ivrit-whisper-large-v3-turbo-ggml
Pinned revision: `7caf56da903afb6adf616b56ac4bbe59485e15aa`.
SHA-256: `6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572`.
Size: 574,041,195 bytes. This community quantization has no published Hebrew
Pixel benchmark; neither latency nor accuracy is guaranteed. It uses the same
ARM CPU backend and Hebrew language token, not GPU/TPU acceleration.

```powershell
.\.voice-poc-venv\Scripts\python.exe -X utf8 scripts\prepare_voice_poc.py --stt-only --stt-profile ivrit-turbo-q5_0
```

Push the same model and manifest files using the STT-only upgrade commands above.
Keep Beam 5 selected. The UI displays `ivrit.ai Turbo Q5 (עברית)` and a 60-second
cooperative inference budget (Small keeps 30 seconds). Cancellation stays enabled.
Compare natural speech, including the existing problematic Hebrew sentences,
recording both transcript and warm inference time. This is an accuracy trial,
not a production architecture change. If it times out, record that as a failed
latency result rather than raising the budget repeatedly.
To revert, prepare `--stt-profile small-fp16` and push the same two files.
BlueTTS is preserved, and user audio remains local and is not persisted.

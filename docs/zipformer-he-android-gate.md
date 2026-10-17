# Hebrew streaming ASR — isolated Pixel 10 Pro gate

Status: **Draft; not yet validated on-device**. Investigatory work tracked in #39. Stacked atop PR #40. This is **not** a change to the main Mosaic voice pipeline or the roadmap.

## Scope and safety

- Separate Android package `life.mosaic.zipformergate`: no network permission; no access to the installed Whisper, Gemma or BlueTTS apps or their private data.
- CPU-only sherpa-onnx v1.13.8 with a verified release AAR (source https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8, SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`). The binary AAR is **not committed** to Git.
- INT8 model sourced from `ivrit-ai/zipformer2-66m-streaming-he`, pinned revision `ee769a2a13b887f91572eec349458bfb0fe79e7a` and verified local private model lock. Code anchors all three ONNX SHA-256 values and asset sizes. `tokens.txt` uses 17,086-byte size and the private SHA-256 lock generated from the downloaded pinned revision.
- Import via Android's folder picker, copying four verified files into app-private storage. No raw microphone PCM is stored or uploaded. If user chooses to export a private JSONL diagnostic file it **contains transcripts**; do not commit or publish.
- Session-based streaming capture limited to 30 s, 16 kHz mono PCM16, greedy decoding on a single worker thread, two CPU threads for inference. No background continuous recording. The UI shows partial text and a final transcript.
- Failure and lifecycle paths call `AudioRecord.stop/release` and `OnlineStream.release`. The recognizer is released on Activity destruction; avoid starting another test until the previous one completes. Any freeze/reboot/OOM: **stop** and report private diagnostic, do not use the app unattended.

## Windows 11 build

In PowerShell, from the repo root after fetching the new experimental branch:

```powershell
git fetch origin
git switch --track origin/experiment/zipformer-he-android-gate
python scripts/prepare_zipformer_he.py --verify-only
python scripts/prepare_zipformer_android_runtime.py
.\gradlew.bat -p poc/zipformer-gate assembleDebug
```

The last command produces `poc/zipformer-gate/build/outputs/apk/debug/zipformer-gate-debug.apk` (actual Gradle APK name may vary; list with `Get-ChildItem poc/zipformer-gate/build/outputs/apk/debug/*.apk`). The AAR is fetched by SHA outside Git. Never uninstall the Whisper or LLM APKs.

## Copy already-downloaded model files to the Pixel

Using the same ADB Wi-Fi connection as previously tested:

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$LOCAL = "$HOME\Mosaic\zipformer2-66m-streaming-he"
$PHONE = "/sdcard/Documents/Mosaic/models/zipformer2-66m-streaming-he"
& $ADB shell "mkdir -p $PHONE"
& $ADB push "$LOCAL\sherpa-onnx" "$PHONE/"
& $ADB push "$LOCAL\zipformer-lock.json" "$PHONE/"
```

These commands do not delete any existing model. In the experiment APK, select **1. ייבוא תיקיית Zipformer**, choose the **`zipformer2-66m-streaming-he` folder** (not its `sherpa-onnx` child), and grant read access. Import verifies SHA-256, hashes, sizes and the pinned model revision, and preserves it privately. It does not require reimport if the APK is updated with the same signing key.

Install **this separate** APK using `adb install -r <path>`; do not install a CI artifact signed by another machine on top of an existing installation. Open **Mosaic Hebrew Streaming Gate**, press **2. טעינת מנוע Streaming**, wait for loaded, then tap **3. דבר**. Speak a short Hebrew sentence, watch partial text change **during speech**, then press **סיום משפט**. Inspect measured CPU decode, RTF, first-partial time, end-of-audio finalization and thermal status. Export the JSONL (to Downloads) for analysis. Do not infer speed or WER from publication benchmarks.

## Device validation checkpoints

- Both real model copies and the Android library must load without crash or forced retry.
- Verify words appear **while speaking**, not only after Stop.
- Measure time-to-first partial and finalization separately; do not confuse first partial measured from **start of microphone capture** with response-after-speech latency.
- Compare several utterances with known reference text: short everyday Hebrew, negation, numbers, mixed English, names and two sequential turns. Review accuracy with the user, especially for future tool commands.
- Record model load latency, memory availability and thermal status. Reject excessive lag, OOM/reboot, runaway decoder, speech loss.
- Controlled same-PCM A/B against Whisper and WER/CER benchmarks are **future work**. Only after acceptance should the app be integrated into the voice chain; no merge without user's explicit request.

## Known limits

- This is a mic demo, not a wake-word, skill interface or connected LLM/TTS loop.
- sherpa-onnx AAR is a separate verified binary dependency downloaded at build time; CI builds must also download and verify it. There is no bundled ASR weight file.
- The model can produce inaccurate words or number normalization. External benchmark WER isn't device correctness proof.
- Phone privacy: no INTERNET permission, Android permission is only RECORD_AUDIO, and an optional explicit SAF import/export.
- No claim that Tensor G5 NPU is used; the initial investigation runs CPU-only to measure reality.

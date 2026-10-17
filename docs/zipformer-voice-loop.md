# VOICE-07: Zipformer opt-in end-to-end spoken turn

**Status:** experimental Draft, **no full-loop Pixel acceptance yet**. Parent #41 (standalone Zipformer streaming mic demo), earlier #40 (pinned model preflight), baseline Whisper + Gemma + BlueTTS voice PRs #35/#36/#38. Relevant issue #39. Not on `main`; no roadmap stage completed.

## Goal

Check whether the device-validated small Hebrew Zipformer recognizer can reduce **speech-end → first audible response** latency through the already working voice assistant chain: microphone → local ASR → Gemma 4 E2B → BlueTTS. Baseline Whisper Beam 2: 24.0s ASR for ~4.7s audio, 34.1s capture-end → playback-start journal marker. Standalone Zipformer ASR: 4.0s/6.8s short-phrase device tests with near-instant finalization **because decoding happened while speaking**. Neither standalone measurement predicts full-loop latency by itself.

## Exact experimental slice

- **No change to the original default**: existing Whisper Beam 5 (or manual Beam 2) remains available. Persisted **Zipformer** checkbox is opt-in and frozen at turn start. Beam 2 option disabled when Zipformer is selected, since beam size is Whisper-only.
- The controller uses the existing foreground, two-minute-idle, single-stage-at-a-time, private journal, timeout and worker binder-death release path. The Zipformer worker is a new **same-signature, exported-but-permission-protected** `ZipformerVoiceService` in `life.mosaic.zipformergate:voice`, parallel to `life.mosaic.tensorwhisper.VoiceAsrService`. Only explicitly signature-authorized `life.mosaic.tensorllm` can bind.
- On prewarm, the worker rechecks pinned model files and their private SHA lock, loads CPU sherpa-onnx recognizer, and acknowledges `prepared=true`. On full turn, it accepts up to 12s PCM16 captured by the controller, feeds 100ms frames to sherpa-onnx, flushes the transducer, returns transcript + decoder/verification/load/backend/provenance timings. Worker is **killed and binder-death-confirmed before Gemma**; memory isolation and process-reuse rules unchanged.
- Critical accuracy of numbers and negation is not guaranteed by an LLM. No phone action executes in this slice; the model only replies. Do not trust commands to send messages without independent confirmation.
- **Not yet live streaming in the integrated voice screen:** the existing controller records the entire utterance before sending PCM to Zipformer. The already tested standalone Zipformer gate can stream partial words *while recording*, but this first integration intentionally preserves the known-working capture path. To get partial words during capture in the main app, a later slice needs chunked IPC or shared ringbuffer and carefully bounded worker latency. End-to-end latency should still improve if post-stop batch Zipformer decode is fast.
- The controller records `start.asr_engine` and exact chosen companion package version. It validates completion from the matching ASR engine, retaining legacy Whisper `beam_size` verification. Any package mismatch or missing local model hard-fails with a private journal entry, **no silent network fallback**.

## Validation sequence on Windows 11

From `C:\Users\orele\StudioProjects\mosaic-android`, fetch and check out the new branch only when CI compiles:

```powershell
git fetch origin
git switch --track origin/experiment/zipformer-voice-loop-opt-in
python scripts/prepare_zipformer_android_runtime.py --verify-only
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat -p poc/zipformer-gate assembleDebug
.\gradlew.bat -p poc/tensor-llm testDebugUnitTest assembleDebug
```

**Do not uninstall APKs** or reset app data / private imported models. Before updating any installed APK, check both signatures match the Windows debug keystore already used on this Pixel (SHA-256 `ddcd0a67735d21987d3e0360521f5c06a806c30b3f62a4286bbbc1952d6b9a6d`). Install **both new builds with `adb install -r`**:
- `poc/zipformer-gate/build/outputs/apk/debug/MosaicZipformerGate-debug.apk`
- `poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk`

Existing `life.mosaic.tensorwhisper` APK stays installed and unchanged.

## Device acceptance / rollback

1. Open Mosaic voice. Leave **Zipformer unchecked**, prepare engines, perform one safe short baseline spoken turn if needed; do not repeatedly run costly Whisper if a fresh baseline exists.
2. Check **Zipformer**, which invalidates prior prewarm; press **הכנת מנועים לשיחה**. It should finish without touching Whisper, then Gemma, then TTS; verify `start.asr_engine=zipformer` and the signature permission binding.
3. Make one short spoken turn; ensure transcript reasonably matches expected, Gemma answers and BlueTTS reads aloud. Export private JSONL. Inspect `stage_end(asr).asr_engine`, `backend`, `verify_ms`, `load_ms`, `compute_ms`, `decode_ms`, three binder-release events, time from `capture_end` to `stage=tts` playback-start marker, end `complete`, thermal and memory. Do not claim “spoken audio starts” from a journal marker alone.
4. Run a second turn and check no restart regression, leaks or phone slowdown. Be cautious: previous ASR experiments on this hardware froze or rebooted the device. Stop immediately on freeze/thermal/memory pressure.
5. On failure or bad transcript, uncheck Zipformer to return to existing Whisper path. No uninstall, no deleted local models, no merge without explicit user request.

## Open work after slice

- Actual streamed live partial transcription in full voice interface, with background capture/worker IPC and bounded queue/timeout.
- Controlled same-PCM quality/latency comparisons including Hebrew numbers/negation/mixed languages and device memory/thermal profiling over multiple turns.
- Downstream first-token/first-audio latency optimization after reliable full-loop timing.

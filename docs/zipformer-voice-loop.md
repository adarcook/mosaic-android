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


## First Pixel full-loop validation — 2026-10-17

**Status: one local device end-to-end turn completed successfully; subsequent repeated-turn and manual playback acceptance remain pending.** The user locally compiled the separate Zipformer and Mosaic voice APKs with Gradle, confirmed both APKs use the established device debug-signing certificate, passed the packaged Tensor dispatch verification, updated both via ADB, opted in to Zipformer, and reported successful engine preparation. A user-supplied **private** controller JSONL with 35 journal events recorded the first actual spoken turn. **This document contains only generalized operational metrics; no personal PCM, transcript, answers or user journal have been committed.**

| Measurement | Real-device result |
|---|---:|
| Voice/Zipformer version | \`0.10-zipformer-voice-opt-in\` / \`0.2-zipformer-signed-voice-companion\` |
| Chosen ASR path | \`zipformer\` (validated in start and stage end); \`zipformer2-66m-int8; cpu; buffered_after_capture\` |
| Recorded audio duration | 4.239875 s |
| Capture phase elapsed (start to capture_end marker) | 4.522 s |
| ASR stage | **2.343 s** (verify **177 ms**, load **1,654 ms**, decode compute **303 ms**, including **263 ms** inside sherpa decode calls) |
| ASR process release | **268 ms**, binder-death-confirmed |
| LLM stage | **4.882 s** (load **1,522 ms**, generate **2,842 ms**, first-text-chunk **1,525 ms** as worker-reported metric) |
| LLM process release | **270 ms**, binder-death-confirmed |
| TTS stage | **11.200 s** (load **3,809 ms**; two speech chunks, cumulative playback reported **4,554 ms**) |
| First TTS playback marker since turn start | **17.481 s** |
| Capture-end → first TTS playback marker | **12.959 s** |
| TTS process release | **732 ms**, binder-death-confirmed |
| Full turn including input, processing, output playback and worker shutdown | **24.242 s**, state **complete** |
| Thermal status | **0** at ASR, LLM, and TTS stage checkpoints |
| Memory | No low-memory signal on any of three release acknowledgements; TTS worker PSS **~1.5 GiB**; no crash observed in log |

The ASR transcript had a benign inflection error (imperative gender/segmentation), with intent preserved; the answer responded appropriately and generated two Hebrew TTS chunks. Android playback completion was logged for both chunks with playback status running and full head frames. There was one reported playback underrun counter reading per chunk; **user subsequently confirmed hearing the entire response smoothly, without noticeable gaps**, so the journal's underrun counter was not perceptible in this single run. This does not establish robust playback quality across repeated or long turns.

**Baseline context:** Earlier separate Beam 2 journal observed ~24.030 s ASR, ~34.086 s after speech-end to TTS first playback marker, and ~47.164 s complete full turn. Current Zipformer run observes **2.343 s ASR** (~90.3% shorter), **12.959 s end-of-capture to playback marker** (~62.0% shorter), and **24.242 s complete turn** (~48.6% shorter). These use *different utterances*; not identical-PCM tests. Zipformer is still buffered **after** capture within the integrated app, in contrast to standalone live-streaming demo. The playback event time is an instrumented output marker, not independently measured acoustic onset.

**Performance implications, not yet implemented:** Per-turn Zipformer worker reloaded 1,654 ms despite previous prepare because the lifecycle intentionally terminates the worker after prepare; any reuse optimization needs explicit memory and thermal gating rather than retaining overlapping native workers. The TTS phase (~11.2s, including ~3.8s voice pack load) is now the largest remaining stage, followed by Gemma (~4.9s). Prioritize stability, correct words, repeated-turn reliability and user confirmation of audible playback before altering sequencing, inter-process memory lifecycle or switching the full controller to chunked ASR IPC. The opt-in checkbox stays off by default; PR remains Draft, do not merge without explicit request.

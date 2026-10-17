# VOICE-06 / ASR-28 — opt-in Beam 2 spoken-turn latency trial

Status: **draft experiment, not Android-validated**. Branch `experiment/voice-beam2-opt-in` stacked on `fix/receipt-missing-filekey-fallback` (draft PR #36), itself on the experimental voice chain (draft PR #35). No `main` roadmap stage is complete as a result of this trial.

## Motivation / observed baseline

The user-confirmed Pixel 10 Pro end-to-end Hebrew speech test completed and sounded correct, but ASR took ~45.0 seconds, including ~38.8 seconds in the CPU-side cache injection + decoding step for a ~5.2-second utterance. The current spoken-turn path runs Whisper Q5 with Beam 5 and two CPU threads. Earlier Beam 2 continuous-ASR tests were not controlled voice-turn A/B tests and showed quality risks. This trial must not silently sacrifice transcription reliability.

## Small change

- The LLM voice activity has a persisted, user-controlled **experimental Beam 2** checkbox, default **off** (Beam 5 unchanged).
- Beam choice is frozen at the start of a turn, sent over the existing signature-permission companion service, and recorded in the private per-turn JSONL `start.beam` and ASR `stage_end.beam_size` / `backend`.
- The companion validates the choice (only 2 or 5), defaults missing values to 5 for old callers, and calls the existing `WarmHybridEngine.transcribe(audio, beamSize)`. JNI already supports both; no model, NPU tensor shape, decoding token limit, or audio capture changes.
- The controller fails after the ASR stage if the effective beam is not the requested one. Therefore installing only one updated APK cannot silently produce a false Beam 2 result.
- Separate experiment-only version names distinguish builds; `versionCode` stays unchanged so an APK update with the **same signing key** is reversible without uninstalling and losing private models and gate state.

## Build on Windows 11; never install mismatched APK signatures

From the checked-out branch, after checking Java 21/Android SDK setup:

```powershell
python scripts/prepare_tensor_whisper_runtime.py
python scripts/prepare_tensor_llm.py
.\gradlew.bat -p poc/tensor-whisper assembleDebug
.\gradlew.bat -p poc/tensor-llm testDebugUnitTest assembleDebug
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $ADB devices -l
& $ADB install -r "poc/tensor-whisper/build/outputs/apk/debug/MosaicTensorWhisperGate-debug.apk"
& $ADB install -r "poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk"
```

Both debug APKs **must be built on the same Windows computer with the same debug keystore** as the already-installed pair; do not install independent CI artifacts as updates. Both apps are needed for the new setting. Existing model sources in `Documents/Mosaic` remain independent of app reinstalls; do **not uninstall** to update. Pixel build/testing is pending.

## Safe test / rollback

1. After both APK updates, open the LLM voice app, leave **Beam 2 unchecked**, press **Prepare engines**, make a single short Hebrew spoken turn, listen, and export the JSONL.
2. Within the foreground session, turn on **Beam 2** (only when idle), repeat *the same sentence* with similar pacing (not identical PCM), export the second JSONL and compare transcription manually, `stage_end` timing, and overall time-to-playback. Keep the first JSONL private.
3. Confirm that `start.beam == stage_end(asr).beam_size`; `backend` agrees; no ASR errors, missing words, invented text, truncated commands or unsafe reinterpretation. Repeat on numbers, names, and negation if the quick smoke passes.
4. **These two voice turns are not a controlled same-audio A/B:** microphone input and acoustic conditions differ. Compare as exploratory evidence only, not a quantified optimization claim. A future identical-PCM replay gate would be required for confident per-beam speed and WER attribution.
5. For safety-critical commands or poor transcription, untick Beam 2 to restore **Beam 5** without reinstalling. If an APK update crashes or freezes the device, stop testing, collect the private journal/logcat and revert both APKs using same-key baseline builds.
6. Do not push personal JSONL, PCM, voice/text, model files or local keystores to GitHub. Do not merge before Pixel verification and explicit user approval.

## Acceptance boundary

Draft PR review and local Windows tests/compilation are only host-level checks. Device acceptance requires a correct ASR → Gemma → audible BlueTTS response on both settings, effective-beam journal verification, acceptable Hebrew word accuracy, memory/thermal checks and no regressions in worker release. The code does not add phone Skills or a wake word.

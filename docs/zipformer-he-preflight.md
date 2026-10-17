# Zipformer2 66M Hebrew Streaming — preflight (ASR-29)

Status: **model identity and tooling preflight only**. No Android Zipformer APK exists in this slice; no device speed/accuracy result is claimed. Follow-up: [Issue #39](https://github.com/adarcook/mosaic-android/issues/39). Experimental stacked branch from draft PR #38; unrelated to the completed roadmap gates.

## Why this model

Creator's card: [thewh1teagle/zipformer2-66m-streaming-he](https://huggingface.co/thewh1teagle/zipformer2-66m-streaming-he); also published under [ivrit-ai/zipformer2-66m-streaming-he](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he). Streaming transducer with 1.28-second chunks, 16 kHz mono and INT8 sherpa-onnx export. The creator reports 5.3% / 8.8% WER on two test sets *for its INT8 export*, not tested on Mosaic's Pixel, and notes weakness on English and number normalization. These are **published claims only**, not our measurements.

The present Whisper Large Turbo route is too slow for natural voice interaction (user's Oct 17 Pixel Beam 2: ~24 s ASR including ~18.4 s CPU decode, ~34 s from speech-end to first TTS-playback marker). Replacing the on-device ASR engine is a separate experimental question, not an automatic swap or a production decision.

## First slice: inspect and pin the source files on Windows

Requirements: Python 3.12+ on the Windows laptop; network access to Hugging Face; a local installation of the `huggingface_hub` client. No WSL or Docker needed. All downloaded weights stay **outside Git**.

```powershell
cd C:\Users\orele\StudioProjects\mosaic-android
git fetch origin
git switch --track origin/experiment/zipformer-he-preflight

python -m pip install huggingface_hub
python scripts/prepare_zipformer_he.py --inspect
```

`--inspect` contacts Hugging Face for metadata only, reports the exact 40-hex commit and required INT8 file list, and **does not download weights**.

If inspection reports four files, download the model to a durable private directory (default `$HOME\Mosaic\zipformer2-66m-streaming-he`):

```powershell
python scripts/prepare_zipformer_he.py
python scripts/prepare_zipformer_he.py --verify-only
```

This fetches **only** `sherpa-onnx/encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, and `tokens.txt` using the returned immutable commit SHA. It validates available HF LFS SHA metadata, always computes local SHA-256 and file sizes, writes `zipformer-lock.json`, and checks the lock offline. If a different repo/revision is found in the same directory, the script **refuses** to overwrite it. To test the original author's model instead of the ivrit.ai-hosted copy, run `--repo thewh1teagle/zipformer2-66m-streaming-he --dest "$HOME\Mosaic\zipformer2-creator"` (use the same options on follow-up verification). If the files or their metadata are missing, stop and investigate rather than guessing paths.

Unit tests (do not require network or huggingface_hub):

```powershell
python -m unittest discover -s scripts/tests -p test_prepare_zipformer_he.py -v
```

Do not upload model binaries or local lockfile to GitHub. The private lockfile is for reproducibility; it is not a trusted production manifest until specific fixed hashes are reviewed and committed to the Android code. An upstream commit may change between separate runs: the script refuses revision changes within one destination and prints the revision. The first run has not yet been executed against the user's Windows network.

## Android integration (next slice, not implemented here)

- Pin the returned source commit, expected file names, sizes and SHA-256 in the gate code after reviewing the Windows preflight. Treat any asset mismatch as a hard error.
- Use a **separate** experimental `poc/zipformer-gate` Android APK. Keep the current Whisper, Gemma and BlueTTS installations and weights untouched. Import model files via Android Storage Access Framework from `Documents/Mosaic/models/zipformer2-66m-streaming-he` into private app-controlled storage; do not assume raw `/sdcard/Documents` file-path read permission.
- Evaluate sherpa-onnx Android AAR **v1.13.8** (upstream release `sherpa-onnx-1.13.8.aar`, SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`) or pinned later version only after verification. Do not bundle the weights with the APK.
- Foreground microphone 16 kHz PCM; `OnlineRecognizer` / `OnlineStream`; processing on background worker; partial text while speaking, stable finalized text on Stop, no Internet permissions in the app.
- Record load, per-chunk compute, first partial, finalization delay, peak/available memory and thermal status; avoid logging raw private speech to Git. Run same-audio A/B where possible against Whisper; begin with names, negation, numbers and Hebrew-English mixed speech.
- Only consider switching the main voice companion after measurable device speed, accuracy and memory acceptance, with Whisper fallback. Two turns and lifecycle tests required. No merge without user approval.

## Validation and bounds

The model is published as CC-BY-4.0; ivrit.ai's dataset has custom restrictions and should not be confused with the weights' model card license. Review provenance and redistribution conditions before any public release. A future Pixel APK **must not** infer that published benchmark WER means command-critical reliability. No GPU/TPU acceleration promised by this CPU sherpa-onnx path; its value is streaming and smaller computation.

Last roadmap Complete: Stage 0 architecture/contracts; first incomplete gate: Stage 1 Windows Core foundation host verification. This experiment is outside the current roadmap's main offline nutrition focus, and is not marked Complete by this work.

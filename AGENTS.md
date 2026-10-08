# Mosaic Android contributor instructions

## ASR experiment documentation

For any change or device-result analysis involving Whisper, Hebrew ASR, Tensor/LiteRT, decoder search, model weights, PCM windowing, transcript reconciliation or inference performance:

1. Read `docs/ASR_EXPERIMENT_HISTORY.md` before choosing the next experiment. Inspect current main and related open PRs; an unmerged experiment is not a completed roadmap stage.
2. Update that history in the same PR as the experiment. Assign a stable `ASR-XX` ID, or append dated evidence to the existing ID for a later result. Do not overwrite previous outcomes.
3. Use `docs/ASR_EXPERIMENT_TEMPLATE.md` to record hypothesis, baseline/candidate commits, changed factors, artifact identities, reproduction settings, acceptance criteria, actual results, evidence limitations, decision and rollback point. Explicitly mark missing evidence and pending device results.
4. Keep host parity, CI success, device probe, real-audio quality and sustained throughput distinct. Do not infer device success from a successful APK build, or thermal causality from thermal_status alone.
5. Record which components run on CPU/TPU/GPU and document changes to graph shapes, cache layout, quantization or decoding behavior. Update the relevant detailed setup/architecture guide when those assumptions change.
6. Compare identical audio and settings where possible. Record when text is reread or microphone playback changes the input. Separate loading, full windows, partial tails, overlap and shared audio coverage in timing comparisons.
7. Keep raw audio, private transcripts/journals, model binaries, private SDKs, credentials and signing keys out of Git. Refer to private artifacts by non-sensitive identifiers and SHA-256. Never invent a missing hash, APK identity, metric or test outcome.

The history is maintained by commits after each experiment/result; the Android journal does not automatically upload to GitHub. Preserve the user's main/PR approval rules and do not merge without an explicit request.

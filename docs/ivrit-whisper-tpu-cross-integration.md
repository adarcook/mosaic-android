# TPU cross-attention handoff to the Hebrew Whisper decoder

Draft slice stacked on PR #29; no roadmap stage is Complete.

## Device evidence for the preceding gate

User-supplied Pixel 10 Pro screenshot on 2026-10-08:
- cross-attention model load: 33 ms;
- input write: 1 ms;
- NPU run: 37 ms;
- output read (61.44 MB): 53 ms;
- computation + transfers: 91 ms;
- diagnostic full-array Java validation: 1067 ms;
- total worker: 1410 ms; all 15,360,000 output floats finite and non-zero.

This synthetic runtime gate is not evidence of real-audio or Q5 parity.

## New experimental stages

6. Record one short utterance and compare TPU K/V against CPU Q5 K/V on the
   same real encoder embeddings. This deliberately computes the CPU cache once
   and is expected to be slow. It does not decode tokens.
7. After stage 6 passes, record an utterance and run the encoder and cross
   projections on Tensor G5, inject K/V, then run the existing Beam-5 CPU
   decoder. Stage 3 retains the previous CPU-cross baseline.

Stage 6 compares all eight layer/K-or-V tensors after converting TPU output to
the actual cache dtype. It reports max absolute error, relative L2 error and
cosine similarity per tensor. Provisional diagnostic acceptance requires
relative L2 <= 0.10 and cosine >= 0.99 for every tensor. These Q5-vs-FP32
thresholds are experiment criteria, not Hebrew accuracy validation. A failure
locks stage 7; do not loosen criteria without inspecting the metrics.

Stage 7 requires local stage-6 success. This gate establishes candidate cache
compatibility on one utterance only; the user must independently verify the
transcript, especially numbers, names and negations.

## Native handoff

The opt-in external-encoder context gains an external-cross buffer and two
APIs: cache injection, and CPU-cache numerical comparison. Normal contexts and
the old hybrid API retain CPU preparation.

The Tensor output is `[4,2,1,1500,1280]`, with channels contiguous inside frames.
The native path is restricted to flash attention and full 1500-frame context.
Each layer's K and V are copied to their respective GGML cache tensors, converted
to F16 when necessary, with 1536-frame padded strides. Padding is explicitly
zero-filled. Unsupported cache types/context sizes fail instead of falling back.
CPU cross projection graphs are bypassed only when an external cache is present.
Preparing a new mel or encoder input clears stale cross data.

The CPU comparison uses the pinned Whisper CPU encoder seam plus its original
cross graph; a 60-second cooperative native budget is protected by the existing
90-second UI/process timeout. Backgrounding cancels the isolated worker. PCM is
cache-only and deleted after the attempt. Model weights stay outside Git/APK.

The accelerated path omits the duplicated full Java diagnostic scan from step 5;
the native injection still validates every FP32 value before cache conversion.
No change is made to decoding strategy, model family or microphone duration.

## Validation

Locally passed: third patch application on the exact pinned source, repeated
CMake unwind/reapply sequence, g++ C++17 syntax check of modified whisper.cpp,
existing four export/compiler contract tests and git diff checks.
Host CMake build could not run because cmake is not installed in the authoring
environment. Android/JNI/native link and APK build are delegated to GitHub CI.
No real-audio cache comparison, accelerated transcript, latency or streaming
claim is made before device testing.

## Installation and test

Use the existing WSL checkout and debug signing key. Update the integration
branch, prepare the public runtime, build the standalone POC and install with
`adb install -r`. All three models stay in the same app-specific external files
directory; existing models need not be downloaded or compiled again.

Run stage 6 with the same short Hebrew phrase used for the baseline, retaining
its metrics. Only if it passes, run stage 7 and retain transcript and timings.
A numerical gate PASS alone does not establish product quality or 1–3 s latency.

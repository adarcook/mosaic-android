# Tensor G5 Whisper encoder gate

This is the next device gate after the tiny Tensor G5 ADD probe in Draft PR #24.
It uses a real public LiteRT Whisper Tiny graph, compiles it with the authorized
Google Tensor SDK, and runs the encoder graph in a disposable Android process.

It is intentionally **not yet a microphone/transcription APK**. The purpose is
to prove the exact expensive stage that dominated the earlier Hebrew run can use
the Pixel 10 Pro Tensor G5 path without freezing the phone.

## Why encoder-first

The measured ivrit.ai Turbo CPU run took about 29.5 seconds for a short sentence,
with roughly 28 seconds spent in Whisper's encoder. Moving the encoder is
therefore the first useful acceleration target. The decoder can remain CPU-backed
initially; Tensor G5 compiler support for decoder-style transformer graphs is
still less mature than perception/encoder graphs.

## Inputs and provenance

Public source model:

- repo: `litert-community/whisper-tiny`
- file: `whisper_tiny_30s_f32.tflite`
- SHA-256: `0c8f0e2a1855909a0c027b4ac3c586fdd299e2b47bf1a4fdab51191bca1e0e89`

The Tensor SDK archive/compiler and every compiled Tensor G5 model remain outside
Git. The repository contains only scripts and the standalone test application.

## Host preparation

Use the same authorized Tensor SDK environment that successfully compiled the
PR #24 ADD/segmentation probes.

```bash
python scripts/prepare_whisper_tiny_litert.py "$HOME/mosaic-private/whisper"
python scripts/compile_tensor_whisper.py \
  "$HOME/mosaic-private/whisper/whisper_tiny_30s_f32.tflite" \
  "$HOME/mosaic-private/whisper-g5"
```

The compiler script refuses to continue if:

- subgraph 0 is not a single-input `[1,80,3000]` Whisper encoder;
- no operations are offloaded to Tensor G5;
- the compiled artifact contains no `DISPATCH_OP`.

Read the generated `whisper-tensor-g5-compilation-report.txt`. Compiler success
does not prove device runtime compatibility.

## Build the APK

From the repository root:

```bash
python scripts/prepare_tensor_whisper_runtime.py
mkdir -p poc/tensor-whisper/src/main/assets
cp "$HOME/mosaic-private/whisper-g5/whisper_tiny_30s_Google_Tensor_G5.tflite" \
  poc/tensor-whisper/src/main/assets/
./gradlew -p poc/tensor-whisper assembleDebug
```

APK:

```text
poc/tensor-whisper/build/outputs/apk/debug/tensor-whisper-debug.apk
```

Install without uninstalling the existing Mosaic/voice POCs:

```bash
adb install -r poc/tensor-whisper/build/outputs/apk/debug/tensor-whisper-debug.apk
```

The package is `life.mosaic.tensorwhisper`, so it installs alongside them.

## Device test

1. Keep the phone cool and unplug it from charging.
2. Close other heavy apps.
3. Open **Mosaic TPU ASR Gate**.
4. Tap **בדיקת Whisper על TPU** exactly once.
5. Do not background the app during the test.
6. Copy the result and send it back.

Expected success resembles:

```text
PASS — Whisper encoder רץ דרך נתיב NPU/TPU
זמן טעינה: ...
זמן encoder: ...
output floats: ...
```

The test uses an all-zero log-mel tensor, not microphone audio. It requests no
microphone/network/storage permissions. A 45-second UI timeout and private
`:tpu` process reduce application-level risk, but cannot protect against a
kernel/firmware failure.

## After PASS

The next slice is the actual Hebrew transcription integration:

```text
16 kHz microphone PCM
  → Whisper log-mel frontend
  → Tensor G5 encoder
  → CPU decoder + Hebrew token constraints
  → transcript
```

The first production candidate remains the Hebrew fine-tuned ivrit.ai
Whisper Large v3 Turbo family because it produced accurate Hebrew in the earlier
device trial. We should not switch the user-facing model to Whisper Tiny merely
because Tiny is convenient for this backend gate.

The final acceptance test is the same natural Hebrew sentence set already used
in the voice POC, measured for transcript accuracy, encoder time, total latency,
temperature and cancellation behavior.

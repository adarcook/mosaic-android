# ivrit.ai Whisper Large v3 Turbo → LiteRT → Tensor G5

See the [central ASR explanation and experiment history](ASR_EXPERIMENT_HISTORY.md) for later results, evidence limits and rollback points. This guide describes its specific experimental stage.

This is the accuracy-first path for Mosaic Hebrew ASR.

The source model is `ivrit-ai/whisper-large-v3-turbo`, the same Hebrew
fine-tuned Whisper family that produced the best device transcript in the
existing POC. Whisper Tiny remains only a backend diagnostic; it is not the
planned user-facing ASR model.

## Why the encoder first

The model configuration is strongly encoder-heavy:

- 32 encoder layers;
- 4 decoder layers;
- d_model 1280;
- 128 mel bins;
- 30-second / 3000-frame input.

The previous Pixel CPU trial spent approximately 28 seconds of a 29.5-second
transcription in the encoder. Moving this stage to Tensor G5 therefore targets
the dominant measured cost while keeping the existing Hebrew decoding behavior.

## No Hugging Face access required on the work network

Download the complete Transformers checkpoint elsewhere, then copy the directory
to the build host. The export script uses `local_files_only=True` and never
contacts Hugging Face.

At minimum the directory must include:

```text
config.json
preprocessor_config.json
model.safetensors
generation_config.json
tokenizer.json
tokenizer_config.json
vocab.json
merges.txt
added_tokens.json
special_tokens_map.json
normalizer.json
```

The weight file is large (roughly 3.24 GB). Keep it outside Git.

## Important host requirement

LiteRT Torch conversion is currently a Linux workflow. Use **Python 3.11**.
Do not use the macOS Python 3.14 installation for this step.

The Mac can still be used for Android Studio/APK work. The model-export/AOT step
needs a Linux environment. A local Linux VM/container is acceptable for this
developer-only conversion step; no Docker requirement is introduced into Mosaic
runtime architecture.

Example Linux environment:

```bash
python3.11 -m venv ~/venvs/mosaic-litert
source ~/venvs/mosaic-litert/bin/activate
python -m pip install --upgrade pip
python -m pip install torch transformers accelerate safetensors numpy \
  litert-torch ai-edge-litert
```

Use a separate Python 3.11 AOT environment provisioned through the official
Google Tensor SDK wrapper. The full ivrit.ai encoder was successfully compiled
with the SDK v2.0 archive and:

```text
ai-edge-litert==2.1.6
ai-edge-litert-sdk-google-tensor==2.1.6
```

Point `GOOGLE_TENSOR_SDK_BETA` at the authorized SDK archive before installing
the wrapper package. Do not manually wire `GOOGLE_TENSOR_COMPILER_LIB` or
`LD_LIBRARY_PATH` for the production AOT path.

## Step 1 — direct PyTorch → LiteRT encoder export

No ONNX step is used.

```bash
python scripts/export_ivrit_whisper_encoder.py \
  --model-dir /private/ivrit-ai-whisper-large-v3-turbo \
  --output /private/ivrit-export/ivrit-whisper-encoder-f32.tflite
```

The script verifies the expected model architecture and preprocessing contract,
exports only the encoder, and compares the LiteRT host output with the original
PyTorch encoder before writing the model.

Expected encoder input:

```text
[1, 128, 3000] float32 log-mel features
```

Expected encoder output:

```text
[1, 1500, 1280]
```

Do not proceed if conversion or parity fails.

## Step 2 — Tensor G5 AOT compile

```bash
python scripts/compile_ivrit_encoder_tensor_g5.py \
  /private/ivrit-export/ivrit-whisper-encoder-f32.tflite \
  /private/ivrit-g5
```

The compile script intentionally mirrors the Google reference path:

- Tensor G5 is the only compilation target;
- `keep_going=False`;
- no CPU fallback target;
- no truncation override;
- no large-model flag;
- the official `ai-edge-litert-sdk-google-tensor` wrapper supplies the SDK.

Success requires the entire encoder subgraph to be offloaded and the generated
Tensor G5 artifact to contain `DISPATCH_OP`.

The verified full encoder result is:

```text
Subgraph 0 fully compiled: 1693 / 1693 ops offloaded to 1 partitions.
```

The same path also fully compiled the 8-layer diagnostic graph:

```text
Subgraph 0 fully compiled: 445 / 445 ops offloaded to 1 partitions.
```

The report is written to:

```text
/private/ivrit-g5/ivrit-encoder-tensor-g5-compilation-report.txt
```

## Decision gate

### If export fails

Stop. Capture the first unsupported Torch/LiteRT operation. Fix or rewrite only
that operation; do not switch models yet.

### If export passes but Tensor compilation fails

First verify the official SDK wrapper path with a known-good Google reference
model before changing the Whisper graph. During this experiment, manually wiring
the compiler library, adding a fallback target, `keep_going=True`, and extra
compiler flags produced misleading INTERNAL failures even for tiny diagnostic
graphs. The official wrapper + single-target fail-fast path successfully
compiled the complete encoder.

### If Tensor compilation succeeds

Then build the Android runtime slice:

```text
16 kHz PCM
  → 128-bin Whisper Large-v3 log-mel frontend
  → ivrit.ai encoder on Tensor G5
  → existing Hebrew decoder on CPU
  → transcript
```

Only after that end-to-end path preserves Hebrew transcript quality do we measure
latency, thermal behavior and battery cost.

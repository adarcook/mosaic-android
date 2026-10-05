# ivrit.ai Whisper Large v3 Turbo → LiteRT → Tensor G5

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

Install the authorized Tensor SDK packages in the same or a separate compatible
Python 3.11 environment according to the beta instructions already used for the
Tensor G5 probe.

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

Success requires:

- a Tensor G5 target in the compilation report;
- at least one encoder operation offloaded;
- a generated Tensor G5 artifact containing `DISPATCH_OP`.

The report is written to:

```text
/private/ivrit-g5/ivrit-encoder-tensor-g5-compilation-report.txt
```

## Decision gate

### If export fails

Stop. Capture the first unsupported Torch/LiteRT operation. Fix or rewrite only
that operation; do not switch models yet.

### If export passes but Tensor compilation offloads little or nothing

The model is accurate but not currently a useful Tensor G5 target. Inspect the
compiler report before trying quantization or graph surgery.

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

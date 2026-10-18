# ivrit.ai cross-attention Tensor G5 feasibility gate

See the [central ASR explanation and experiment history](ASR_EXPERIMENT_HISTORY.md) for later results, evidence limits and rollback points. This guide describes its specific experimental stage.

Draft experiment stacked on PR #28; no roadmap stage is complete.
Latest main was checked and is included in the experiment ancestry.

## Why this slice

User-reported Pixel 10 Pro trial on 2026-10-08: 5.90 s audio,
2,528 ms Tensor encoder, 59,314 ms CPU stage, 65,267 ms worker total.
whisper.cpp reports 32,115.89 ms as `encode`; this timer also includes
cross-attention K/V preparation, even when its audio encoder is bypassed.
It is not a separate measurement of cross-attention and does not prove
that the audio encoder was executed twice. Whisper logging is disabled.

The first compiler experiment targets the eight K/V projections in the four
text decoder layers. It does not export the autoregressive decoder loop,
change Beam 5, or claim streaming speech recognition.

## Export contract

- input: FP32 `[1,1500,1280]`, the existing Tensor encoder embeddings;
- output: FP32 `[4,2,1,1500,1280]`, layer / K-or-V / batch / frame / channel;
- K: `hidden @ k_proj.weight.T * 64**(-1/4)`;
- V: `hidden @ v_proj.weight.T + v_proj.bias`;
- no K bias, no extra head reshape, no GGML padded-cache layout;
- matches the existing flash-attention path's projection convention;
- output size: 61,440,000 bytes (transfer cost must be measured).

Only the required weights are read from a local, unsharded `model.safetensors`
checkpoint. Config validation rejects other Whisper architectures. Export
checks PyTorch against independent NumPy formulas and LiteRT against those
formulas for two deterministic non-zero inputs. These are host conversion
checks, not device correctness or Hebrew accuracy evidence.

**Numerical boundary:** the current CPU decoder uses quantized Q5 GGML weights;
this export uses original FP32 weights. Before injecting its outputs, compare
against the actual GGML cross cache, including FP16 conversion, padding and
per-layer layout. Transcript parity must be tested on real Hebrew audio.
An exported file alone cannot replace the application's current cache.

## Host commands

Use the existing Linux Python 3.11 export environment with torch, transformers,
safetensors, numpy and litert-torch. Use the proven separate AOT environment
with `ai-edge-litert==2.1.6` and the enrolled official
`ai-edge-litert-sdk-google-tensor==2.1.6` wrapper. No SDK or model weights in Git.
Set `IVRIT_MODEL_DIR` to the previously used local Transformers checkpoint,
`EXPORT_PYTHON` to that export environment's Python, and `AOT_PYTHON` to the AOT
Python. Run from the repository root:

```bash
"$EXPORT_PYTHON" scripts/export_ivrit_whisper_cross_attention.py \
  --model-dir "$IVRIT_MODEL_DIR" \
  --output /tmp/mosaic-cross/ivrit_whisper_cross_attention.tflite

"$AOT_PYTHON" scripts/compile_ivrit_cross_attention_tensor_g5.py \
  /tmp/mosaic-cross/ivrit_whisper_cross_attention.tflite \
  /tmp/mosaic-cross/tensor-g5
```

Compilation requires full offload, a static FP32 input/output contract and a
`DISPATCH_OP`. It uses only Tensor G5, `keep_going=False`, no CPU fallback and
no special compiler overrides. Compiler errors must be preserved as evidence.

## Validation and next gates

Implemented: exporter, fail-closed compiler gate, formula/report tests, CI and
isolated Pixel load/run probe.

User-run host evidence on 2026-10-08:
- FP32 LiteRT export: 52,455,716 bytes;
- host parity max absolute errors: 2.0861626e-7 and 1.9371510e-7;
- mean absolute errors: 7.8668858e-9 and 7.8709874e-9;
- official Tensor G5 compiler: 18/18 ops, one fully compiled partition;
- uploaded compiled artifact: 26,846,992 bytes;
- SHA-256: `fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3`.

GGML Q5 cache comparison, Pixel execution and full-ASR performance remain untested.
The successful AOT environment was `~/venvs/mosaic-tensor-official`; the older
`mosaic-tensor-aot216` environment did not contain the official SDK wrapper.

Next gates, sequentially:

1. Full host export and compile with the authorized SDK; retain parity/report.
2. Add isolated Pixel load/run probe and measure execution plus transfer costs.
3. Add native GGML cache comparison and injection only after layout validation.
4. Measure cross-cache preparation separately from token decoding.
5. Compare Hebrew accuracy and full latency on identical audio; then consider
   decoder-step export if the remaining CPU stage still exceeds the target.

Product target: final short-utterance result within 1–3 seconds after speech ends,
with user-approved Hebrew accuracy. Compilation alone is not this gate, and
accelerating Whisper does not itself establish native streaming support.


## Pixel probe

The existing standalone package gains two independent steps:
4. load the SHA-256-pinned cross-attention artifact only;
5. run one deterministic non-zero synthetic embedding tensor.

Step 5 is enabled only after step 4 succeeds. Both operate in the disposable
`:tpu` process, enforce a 45-second UI timeout, and cancel when backgrounded.
No encoder or decoder model is needed for steps 4–5. They do not capture audio
or inject anything into the Whisper decoder.

Push `ivrit_whisper_cross_attention_Google_Tensor_G5.tflite` to
`/sdcard/Android/data/life.mosaic.tensorwhisper/files/`. The probe checks its
exact size and SHA-256, then requires a single output of 15,360,000 finite floats
and non-zero magnitude. This is a runtime/smoke gate, not a numerical parity gate.

Result reports model verification/load, input write, one NPU run, output read,
run plus transfer total, and full output validation time. The output read can
include synchronization; interpret `run + transfers` as the practical stage cost.
The 61.44 MB output transfer is intentionally measured, not hidden.

Build from the same WSL environment/signing key as the existing installation:

```bash
python3 scripts/prepare_tensor_whisper_runtime.py
./gradlew -p poc/tensor-whisper clean assembleDebug
```

Install with `adb install -r`, push the model, run step 4 then step 5 and retain
the result. A CI-built debug APK may have a different signature from the local
installation and cannot necessarily update it. Do not uninstall to resolve that.

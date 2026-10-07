# ivrit.ai Whisper Tensor G5 device probe

This is the first on-device runtime gate for the fully AOT-compiled
`ivrit-ai/whisper-large-v3-turbo` encoder.

The host-side gate is already proven:

```text
Subgraph 0 fully compiled: 1693 / 1693 ops offloaded to 1 partitions.
```

The generated Tensor G5 artifact is intentionally kept outside Git:

```text
ivrit_whisper_encoder_Google_Tensor_G5.tflite
1,327,807,744 bytes
```

## Safety boundary

The previous CPU experiment froze and rebooted the Pixel. This probe therefore
does not connect the model to microphone audio or the Hebrew decoder yet.

It runs in the existing private `:tpu` process and exposes two stages:

1. **Load-only** — open the complete compiled model and stop without inference.
2. **Zero log-mel run** — enabled only after load-only passes; run one
   deterministic `[1,128,3000]` float32 zero tensor through the encoder.

The UI process enforces a bounded timeout and terminates the disposable worker
after each attempt. This reduces application-level risk but cannot guarantee
protection from a driver/kernel failure.

## Build the small probe APK

The 1.24 GiB Tensor model is not embedded in the APK.

From the repository root:

```bash
python scripts/prepare_tensor_whisper_runtime.py
./gradlew -p poc/tensor-whisper assembleDebug
```

APK:

```text
poc/tensor-whisper/build/outputs/apk/debug/tensor-whisper-debug.apk
```

Install/update without uninstalling first:

```bash
adb install -r poc/tensor-whisper/build/outputs/apk/debug/tensor-whisper-debug.apk
```

The package remains:

```text
life.mosaic.tensorwhisper
```

## Prepare the model file

Launch the app once after installation. This creates its app-specific external
files directory. The screen also prints the exact expected destination.

For the current package the destination is normally:

```text
/sdcard/Android/data/life.mosaic.tensorwhisper/files/ivrit_whisper_encoder_Google_Tensor_G5.tflite
```

Push the already-compiled artifact:

```bash
adb push \
  "$HOME/mosaic-private/ivrit-g5-full-official/ivrit_whisper_encoder_Google_Tensor_G5.tflite" \
  /sdcard/Android/data/life.mosaic.tensorwhisper/files/ivrit_whisper_encoder_Google_Tensor_G5.tflite
```

Verify the device-side size before testing:

```bash
adb shell stat -c '%s' \
  /sdcard/Android/data/life.mosaic.tensorwhisper/files/ivrit_whisper_encoder_Google_Tensor_G5.tflite
```

Expected:

```text
1327807744
```

The Android probe also refuses to load the file if its byte length differs.

## Device test

Before each stage:

- keep the Pixel cool;
- disconnect charging;
- close other heavy apps;
- keep the app foregrounded;
- tap the requested button only once.

### Stage 1: load-only

Tap:

```text
1. טעינת מודל בלבד
```

Expected success:

```text
PASS — מודל ivrit.ai המלא נטען דרך נתיב NPU/TPU
זמן טעינה: ... ms
לא בוצע inference בשלב הזה.
```

Only after this PASS does the app enable stage 2.

### Stage 2: deterministic encoder run

Tap:

```text
2. הרצת encoder עם zero log-mel
```

Expected success includes:

```text
PASS — ivrit.ai encoder המלא רץ דרך נתיב NPU/TPU
זמן טעינה: ... ms
זמן encoder: ... ms
output floats: ...
sample checksum: ...
```

This is still not a transcription accuracy test.

## Acceptance gate

Proceed to real Hebrew microphone integration only if both stages pass without:

- device freeze or reboot;
- process crash;
- NPU disappearance;
- timeout;
- non-finite output.

After this gate, the next slice is:

```text
16 kHz microphone PCM
  → 128-bin Whisper Large-v3 log-mel frontend
  → compiled ivrit.ai encoder on Tensor G5
  → existing Hebrew decoder on CPU
  → transcript
```

The first end-to-end acceptance run should reuse the same short Hebrew sentence
set that previously produced the accurate ~29.5 s CPU transcript, then compare
encoder latency, total latency, transcript quality, temperature, and stability.


## Verified Pixel load-only result

On Pixel 10 Pro / Tensor G5, the full compiled ivrit.ai encoder artifact loaded
successfully through the NPU/TPU runtime path without running inference:

```text
PASS — full ivrit.ai model loaded through NPU/TPU path
load time: 1884 ms
inference: not executed
```

This clears stage 1 of the device safety gate. Stage 2 remains the deterministic
zero-log-mel encoder run.


## Verified Pixel encoder runtime result

The deterministic full-encoder stage also passed on Pixel 10 Pro / Tensor G5:

```text
PASS — full ivrit.ai encoder ran through NPU/TPU path
model load: 1335 ms
encoder run: 2623 ms
output floats: 1920000
sample checksum: -42.65475845336914
```

The output size matches the expected Large-v3 encoder tensor exactly:

```text
1500 * 1280 = 1,920,000 float values
```

The earlier CPU trial spent about 28 seconds in the encoder, so this single
device gate indicates roughly a 10.7x encoder speedup. This is not yet an
end-to-end transcription benchmark because the input was deterministic zero
log-mel and no microphone frontend or decoder was connected.

Both device safety gates passed without a freeze, reboot, process crash, timeout,
or non-finite output.

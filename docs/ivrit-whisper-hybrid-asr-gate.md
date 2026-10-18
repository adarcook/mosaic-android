# ivrit.ai real-audio hybrid ASR gate

See the [central ASR explanation and experiment history](ASR_EXPERIMENT_HISTORY.md) for later results, evidence limits and rollback points. This guide describes its specific experimental stage.

This experiment is stacked on the verified full-encoder Tensor G5 work in Draft
PR #26. It does not change the Mosaic roadmap completion state.

## Goal

Run one short real Hebrew microphone utterance through the production-candidate
model family while preserving the decoder behavior that previously produced the
accurate Hebrew CPU result:

```text
16 kHz mono PCM
  -> pinned whisper.cpp Large-v3 log-mel frontend
  -> [1,128,3000] float32
  -> ivrit.ai full encoder on Tensor G5
  -> [1,1500,1280] float32
  -> pinned whisper.cpp cross-attention + Hebrew Beam-5 CPU decoder
  -> transcript
```

The Tensor encoder is the verified artifact from PR #26:

```text
ivrit_whisper_encoder_Google_Tensor_G5.tflite
1,327,807,744 bytes
```

The CPU decoder uses the already-tested ivrit.ai Turbo Q5_0 GGML model:

```text
device filename: ivrit-whisper-decoder-q5_0.bin
expected size:   574,041,195 bytes
SHA-256:         6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572
```

The GGML file still contains encoder weights, but this gate bypasses its audio
encoder. Only its model metadata/tokenizer, cross-attention preparation and CPU
decoder path are used after Tensor G5 returns encoder embeddings.

## whisper.cpp seam

The repository keeps whisper.cpp pinned to v1.8.3 commit
`2eeeba56e9edd762b4b38467bab96c2517163158`.

A second pinned patch, applied after the existing cancellation patch, adds an
opt-in external-encoder mode. Normal CPU Whisper contexts remain unchanged.

The seam deliberately reuses whisper.cpp's own frontend:

1. `whisper_pcm_to_mel()` computes the exact Large-v3 mel representation.
2. `whisper_mosaic_copy_encoder_input()` copies the exact padded
   `128 x 3000` encoder input.
3. LiteRT runs the previously verified Tensor G5 AOT encoder.
4. `whisper_mosaic_set_encoder_output()` injects exactly `1500 x 1280`
   floats.
5. `whisper_full(..., samples=nullptr, n_samples=0)` keeps the existing mel and
   runs the external-encoder seam, cross-attention setup and normal Hebrew
   Beam-5 decode loop.

This avoids reimplementing Whisper preprocessing or decoding in Java/Kotlin.

## Device safety boundary

The standalone package remains `life.mosaic.tensorwhisper` and inference runs
in the disposable `:tpu` process.

Real audio is:

- captured as 16 kHz mono PCM16;
- limited to 0.5–8 seconds;
- written only to the app cache;
- deleted after the attempt, including failure/timeout cleanup paths.

The existing load-only and zero-log-mel gates remain available. The hybrid gate
does not write Mosaic domain records and does not use the network.

## Build

The speech runtime requires the pinned Android native toolchain:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"

cd ~/git-workspaces/mosaic-android
./gradlew -p poc/tensor-whisper clean assembleDebug
```

The actual APK filename is determined by Gradle; locate it with:

```bash
find poc/tensor-whisper/build/outputs/apk -type f -name "*.apk" -print
```

## Model placement

Both files live outside the APK in the app-specific external files directory:

```text
/sdcard/Android/data/life.mosaic.tensorwhisper/files/
  ivrit_whisper_encoder_Google_Tensor_G5.tflite
  ivrit-whisper-decoder-q5_0.bin
```

The app refuses files with unexpected byte lengths.

## Acceptance gate

This PR is successful only after a physical Pixel 10 Pro run shows:

- no freeze, reboot or native process crash;
- non-empty Hebrew transcript for a real short utterance;
- Tensor encoder output shape remains 1,920,000 floats;
- mel, encoder, decoder and total timings are reported separately;
- transcript quality is compared with the previously accurate ivrit.ai CPU
  result.

Current status: implementation only. The hybrid APK has not yet been built or
run on device.

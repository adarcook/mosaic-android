# Tensor G5 runtime probe (experimental)

See the [central ASR explanation and experiment history](ASR_EXPERIMENT_HISTORY.md) for later results, evidence limits and rollback points. This guide describes its specific experimental stage.

This standalone debug APK is the first device gate before moving the Hebrew
Whisper encoder to LiteRT. It installs as `life.mosaic.tensorprobe`, alongside
Mosaic Voice Mobile. It cannot transcribe speech and requires no microphone,
storage, network, or personal-data permissions.

## State

SDK v2.0 host setup and AOT compilation succeeded for a one-op ADD graph and
Google's official selfie segmentation sample (175/175 operations offloaded).
Initial INTERNAL compilation failures were resolved after restoring executable
permissions for the bundled LLVM toolchain. Host compilation does not establish
Pixel runtime compatibility, stability, or transcription performance.

The main roadmap remains unchanged: this is experimental voice work stacked on
the CPU diagnostic branch/PR #23. No stage is marked complete and no merge is
requested. The old Vulkan experiment remains excluded.

## Probe behavior

- Manual button; restricted to the user's Pixel 10 Pro; no auto-run or retry.
- Eight-element floating-point addition with exact expected output (all 8s).
- AOT model must contain exactly one Google Tensor `DISPATCH_OP`, with no
  original CPU ADD op. Runtime requests NPU only and checks the output.
- Native work runs in a private `:tpu` process. The UI cancels after 15 seconds,
  on leaving the foreground, or on explicit cancellation, and disposes the
  process. This is not protection against a kernel/driver failure.
- Result and build identification appear on screen; the user can copy them.
- A previous-started marker survives process death/restart. No inference-time
  result from this tiny graph should be interpreted as a speech speedup.

## Build and private packaging

The host compiler, SDK archive, and compiled model are kept outside Git and CI.
The CI artifact is deliberately a **template without a model**, not a device
deliverable. Only the public pinned LiteRT Android runtime is fetched in CI.

1. Install the authorized SDK v2.0 using Google's documented environment variable
   and `ai-edge-litert-sdk-google-tensor==2.1.6`, with
   `ai-edge-litert-nightly==2.2.0.dev20260809`.
2. Run `python scripts/compile_tensor_probe.py /private/output` on Linux x86_64.
3. Build: `python scripts/prepare_tensor_runtime.py`, then
   `./gradlew -p poc/tensor-probe assembleDebug`.
4. Add the locally compiled model to the template under
   `assets/probe_Google_Tensor_G5.tflite`, uncompressed, remove old signing
   metadata, align and re-sign using the existing public test-only debug key.
5. Verify signature, manifest, private service, ABI, runtime dependency and
   embedded model hash before handing the APK to the enrolled tester.

The APK uses synchronous LiteRT APIs from Java. Its standalone dependency set
omits optional lifecycle/Play-delivery/coroutine providers. The dispatch library
is extracted to the app's native-library directory and supplied to Environment;
`libedgetpu_litert.so` is declared as an optional device-native library.

Google's version matrix associates SDK v2.0/G5 with platform release 26D1.
Actual driver compatibility must be established on the user's installed build;
do not infer it from the Android major version alone.

References:
- https://developers.google.com/edge/tensor-sdk/release-notes
- https://developers.google.com/edge/litert/next/tensor-sdk
- https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.6

Next gate after a successful device probe: export the original ivrit.ai encoder,
validate numerical and Hebrew transcript parity, compile it, and integrate it
with the existing Whisper decoder. No TPU speech APK exists at this stage.

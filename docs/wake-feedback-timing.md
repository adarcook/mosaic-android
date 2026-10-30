# ASR-39 / VOICE-17 — Wake-to-capture timing baseline

Date: 2026-10-30 (Asia/Jerusalem). Status: instrumentation candidate; physical
voice acceptance pending. This is a small observation slice before changing the
wake/service handoff or ready-cue sequencing.

## Baseline, hypothesis and controls

Baseline/rollback source: `30a660a9e0eac3560b3be1e204c7e49bcba5b8ee`, draft #52,
stacked on #51 and #50. Main remains `613b71ebd3d94dcb7e84f70af058a818739fb3f9`
(Voice 0.22); all three drafts and independent bridge #47 remain unmerged.
The authoritative [roadmap](https://github.com/adarcook/mosaic-docs/blob/main/ROADMAP.md)
and [release contract](https://github.com/adarcook/mosaic-docs/blob/main/docs/releases/VOICE_V1_HANDSFREE_ACCEPTANCE.md)
require measured acknowledgement latency and actual capture readiness.

Hypothesis: status-only monotonic markers can expose the gap between the wake
callback, fixed feedback playback and microphone recording without loading a model
or retaining idle audio. Candidate: the commit containing this record,
Voice `0.24.3-wake-timing` / code25. APK hashes and CI runs belong in follow-up
evidence once available; none are invented here.

Changed factors: controller timing markers, one playback-head observation callback,
and an explicit microphone recording-state check. Unchanged: PCM clips, wake
matching, cue/capture order, permissions, tool privacy, models, inference settings,
and CPU/NPU backends. Zipformer standby and BlueTTS use CPU; Gemma uses the existing
NPU path; the selected ASR path remains as in the baseline. No model is newly pinned
in memory. No audio input was used for the host tests.

## Marker semantics and reproduction

The existing bounded local `handsfree-status.jsonl` gains `interaction_timing`
records with a random non-authorizing `wake_id`, enum `point`, and Android
`elapsed_realtime_ms`. Existing wall time is for identification, never latency.
No token, transcript, notification, device serial or PCM enters these records.

| Point | Exact boundary |
|---|---|
| WAKE_CALLBACK_RECEIVED | Controller accepts the authenticated wake callback, after companion cleanup |
| TURN_DISPATCHED | Existing handoff permits assistant dispatch |
| READY_REQUESTED | Immediately before requesting the existing fixed ready clip |
| READY_PLAYBACK_HEAD | First observed nonzero AudioTrack playback head; worker timestamp, delivered later on main |
| READY_COMPLETED | Successful ready-clip completion callback |
| CAPTURE_RECORDING_STARTED | AudioRecord.startRecording returned and recording state is RECORDING |

Group by wake_id and sort by monotonic timestamp, not file order: microphone
markers are delivered asynchronously. Missing markers are incomplete attempts,
not zero latency or successful trials. Playback head progress is a software
observation, not speaker audibility. A recording state is not proof of unsilenced
input or untruncated first syllables. WAKE_CALLBACK_RECEIVED is **not** the detector
recognition timestamp; detector-to-ack P95 cannot be derived from this slice.

Known baseline defect remains visible: the ready clip completes before capture
starts. There is also no separate immediate wake acknowledgement before the
handoff. This PR makes neither behavior a passed release gate. The next slice
should correct sequencing after baseline observation, with real audio and
cancellation checks, and instrument detector confirmation separately.

Host reproduction (JDK 21+, Gradle 8.9, installed Android SDK):

```text
python scripts/prepare_tensor_llm.py
gradle -p poc/tensor-llm testDebugUnitTest assembleDebug
python scripts/verify_tensor_llm_apk.py poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk
```

Local result: all 125 controller JVM tests passed, zero failures/errors/skips;
Android compilation, APK assembly, pinned dispatch/package verification and
`git diff --check` passed. The new partial-write regression test and extended
short/stalled/failed playback tests verify one real head-progress observation,
including a clip that completes in a single write. Hosted CI and candidate
physical acceptance were pending when this record was authored.

Acceptance defined before device testing: normal/short PCM reports head progress
exactly once; stalled/failed output never reports it; APK compilation and package
verification pass. Device follow-up requires a signer-compatible exact-commit CI
APK, local install opt-in, verified Pixel connection, then owner-armed real wake
trials. Keep at least 20 attempts and all failures; do not claim the <500 ms P95
target, screen-off reliability, ASR quality, power or thermal acceptance from host
tests. Stop/revocation, repeated turns and actual notification readout remain
separate physical gates. Private journals remain local.

## Existing Pixel and CI evidence — 2026-10-30

The Pixel 10 Pro reports `0.24.2-model-tools` / code24. Pulled installed APK SHA-256:
`7ac8100aa6951aaeea6123d8969f535556ce603a82ebd9434d1316b5802dcbc9`.
This matches the earlier #52 installation record. Activity launch and process
presence passed; no microphone, notification readout or full spoken turn was run.

Latest pre-change successful Tensor CI run:
[39136641652](https://github.com/adarcook/mosaic-android/actions/runs/39136641652),
exact source `30a660a9e0eac3560b3be1e204c7e49bcba5b8ee`, artifact `12140127035`.
ZIP SHA-256 `3e7bddceccdc6407d25d4cf113fe42f18a8adc120c4f2acd9de93786b9d5888e`
matches GitHub's digest. Extracted APK remains code24 / 0.24.2; APK SHA-256:
`61235c7826775cdf3bfb1006ad92fcb20d9f832a12ad5ddaa0f797fa6c335a5c`.

Verified certificate SHA-256 values differ:

- CI APK: `caada1a9cff14d1092cbcf74e3d294c02b0922fdea862645cd5569e0a2ca9677`.
- Installed APK: `ef154770847924693d77aaceda155a8ef66751f30d2219c72e967ea0a6a61e35`.

Installation was **not attempted**. No re-signing, uninstall, data/model deletion,
downgrade, permission or local opt-in change was made. A matching signing strategy
requires separate explicit scope; this task forbids bypassing the mismatch.

The bridge's loopback health reports running. Local `allow_install` is false;
configured aapt/apksigner paths do not exist. Two authorized ADB transports were
observed to identify the same physical Pixel; the unconfigured bridge can reject
multiple transports. Read-only certificate/identity checks and launch smoke used
the verified USB transport and existing SDK tools without changing bridge config.
Issue #46 is open; last recorded result `6247485745` blocks an older install for
allow_install=false, and local checkpoint is at that result. No fresh queue command
or full remote round-trip is claimed. Resolve each blocker before installation.

Decision: keep this as a draft observation candidate. No merge, roadmap completion,
physical latency/accuracy result, new model identity or battery claim.

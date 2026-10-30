# ASR-40 / VOICE-18 — Repair observed wake/capture/alias failures

Date: 2026-10-30, Asia/Jerusalem. Baseline implementation/installed Voice 0.24.3:
`6dd43ad3f8fcc20679f7c1d128f594dc64d1e3ec`; documentation base #54:
`89c98aa21d35cfcd93a04ed779f1ffed01e37492`. Companion baseline 0.7/code6.
Main remains `613b71ebd3d94dcb7e84f70af058a818739fb3f9`; #50–#55 are drafts,
not completed release acceptance. This candidate does not merge anything.

## Observed failures and evidence boundaries

Owner reports very late wake feedback with the display on, no response at all
with display off, and the saved update alias failing to read WhatsApp. Private
recordings/journals/dictionary were inspected locally, under ignored build storage;
none are committed or uploaded. No new microphone recording was initiated.

- The saved alias exists and is enabled. The request ASR omitted the short pronoun
  from that alias, so exact-match preference expansion did not run and the model
  chose conversation. This is a failed acoustic integration result, superseding
  any interpretation of the earlier fixed synthetic 5/5 as voice acceptance.
- The 8.000 s request PCM had peak block RMS 409.7, median 27.8, and zero blocks
  reaching the old fixed endpoint threshold 450. It reached the no-speech timeout
  despite containing a transcribed request. Private source SHA-256:
  `5d6d2ffe935ab34efbb6041cefd5ee0804f9062195b83494ab46f3e0dad666f1`.
- One logged wake callback reached first observed ready playback-head progress in
  280 ms. This starts **after** companion cleanup, not at speech onset, and does
  not refute the owner's experienced wait. Detector recognition delay is unmeasured.
- Display-off health windows continued processing nonzero PCM with normal decode
  times but no wake callback. This does not prove speech intelligibility or the
  cause of the miss; it places this attempt before assistant dispatch, rather
  than establishing an Activity/foreground-service startup failure.
- Ready playback still preceded microphone start by about 162 ms. Standby setup
  also unnecessarily warmed/released ASR, Gemma and BlueTTS for ~17 s even though
  fixed feedback clips already existed.

## Candidate and controlled factors

Voice `0.25-voice-recovery` / code26, companion `0.8-keyword-wake` / code7.
The candidate SHA is the PR commit containing this record. APK identities and
exact CI/device outcomes are appended after observation, never inferred.

1. Saved personal update phrases accept only the narrow observed pronoun-omission
   and spelling variants. There is no new built-in update command, substring/edit
   distance matching or direct tool execution. Negation/unsupported-action veto,
   model selection and execution-time permission/keyguard checks remain. Existing
   dictionary entries are not rewritten or newly rejected as semantic duplicates.
2. Endpoint threshold adapts to quiet background, bounded to RMS 100–450 with a
   3x background multiplier. Speech cannot raise its own threshold. The existing
   1.1 s trailing silence, minimum speech, 8 s no-speech and 12 s capture bounds
   remain. This addresses an observed low-level input; it is not a general VAD
   accuracy or noisy-room acceptance claim.
3. AudioRecord starts and verifies recording state before requesting the ready
   clip. The worker continuously drains cue-time microphone input without saving
   it; a cancellation-safe gate opens at fixed-cue playback drain, before delayed
   main-thread delivery. The request deadline begins after cue completion. One
   boundary block may include the clip tail; real echo/truncation testing remains.
4. Explicit standby activation reuses verified fixed clips and defers heavy model
   loading to the actual request. First-time clip creation retains the preparation
   path; no engine is permanently kept in RAM. Full model checks still run when
   each heavy stage actually loads.
5. Standby changes from general greedy ASR plus text matching to sherpa's native
   keyword spotter using the **same** pinned Hebrew streaming transducer weights,
   tokens, runtime 1.13.8 and CPU/two-thread backend. It enumerates the 32 valid
   existing BPE token paths of the two fixed Hebrew wake spellings. Defaults are
   four active paths, score 1.5, threshold .25, two trailing blanks; streams remain
   bounded to 30 s. This is not a hardware DSP or a measured low-power model.
   No idle transcript exists in the new path. Numeric health adds only peak RMS;
   confirmed detection gets a monotonic timestamp before cleanup. No arbitrary
   wake phrases, new models, microphone permissions or keyguard policy changes.

These factors address three reported failures together, so no single-factor
end-to-end speedup claim is made. Request ASR model/beam, Gemma model/sampler/NPU,
BlueTTS CPU graphs/voice and the offline tool privacy boundary remain unchanged.

## Validation and acceptance defined before device retry

Local: 130 controller tests and 11 companion tests pass, zero failures/errors/skips;
both APKs assemble and controller package/dispatch verification passes. Regressions
cover quiet speech/noise, cancellation before cue completion, exact saved-alias
variants and blocked/disabled/unregistered forms, BPE alternatives and missing
tokens, and Hebrew punctuation boundaries. Host Windows x86 native sherpa 1.13.8
loads the existing verified 66M model and all 32 keyword paths. The private 8 s
non-wake request triggers zero wake detections; host compute 172 ms is not Pixel
performance. Replaying identical PCM through the production endpoint changes its
stop from baseline 8.000 s to 6.528 s; this is endpoint-only host replay, not an
ASR-quality or new acoustic measurement.

Debug-only probes accept no arbitrary prompts or microphone input: six fixed
Gemma cases use synthetic NO_MESSAGE results, including the reported short alias;
a fixed BlueTTS fixture generator writes synthetic wake/music/greeting PCM locally
without playing it. Synthetic recognition tests remain separate from owner audio.

Release acceptance still requires exact-source CI artifacts, signer-compatible
data-preserving update of **both** packages, then explicit owner activation and
untouched screen-off wake/ready/request/rearm trials. Test immediate speech at the
cue boundary, negation, permission revocation/lock, repeated stop/rearm, quiet
speech, music/false wakes and sustained power. Record misses, not only successes.
Do not claim screen-off reliability, detector-to-human-audible P95, reduced battery
use or daily-use readiness from compilation, host replay or synthetic probes.

Rollback: rebuild baseline source with non-decreasing version codes and the same
enrolled signer, retaining existing models/data. Baseline private APK copies exist
on Windows. Never uninstall or clear data to recover.

## Primary implementation references

- [Pinned KeywordSpotter Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/KeywordSpotter.kt).
- [Pinned keyword token parser](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/utils.cc).
- [Voice v0.1 acceptance](https://github.com/adarcook/mosaic-docs/blob/main/docs/releases/VOICE_V1_HANDSFREE_ACCEPTANCE.md).

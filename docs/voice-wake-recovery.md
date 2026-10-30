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

## Follow-up: native model probe and threshold correction

Initial source `b8085e09899edb5df48b6cd31e280f3794d94d1a` passed all four hosted
workflows, and both initial candidates (Voice 0.25/code26, wake 0.8/code7) were
installed through queue #56 with unchanged payload, matching original signer,
and pulled-back APK verification. Controller installed hash
`a535f1c405e5f99f4d1c71948664996446ed631b67eba915261bf1a1c9c56eec`;
companion `984b31feeb58d5283fa54b7d38ad50553eae4ee27000dbf9708e82b74e65dff7`.
No uninstall, data/model deletion, Android permission change or merge.

The real Pixel Gemma/NPU probe passed **6/6** fixed synthetic-result cases:
explicit read, exact saved alias and the short ASR alias each emitted one call;
greeting, negation and explanation emitted none. It did not read notifications,
record microphones or exercise actual Binder/TTS/private output.

The initial native KWS threshold .25 failed a fixed offline English TTS “Mosaic”
positive fixture. A separate known-word sanity fixture also exposed an excessive
acceptance threshold. This is failed recognition evidence despite build success.
An isolated BlueTTS brand-name fixture was not recognized correctly by either
baseline or candidate; it remains inconclusive/failed positive evidence and is
not silently counted as a passing wake sample. No owner wake audio was saved.

Correction candidate: Voice **0.25.1/code27**, wake **0.8.1/code8**. Only the
version/dependency gate and KWS acceptance threshold change: .25 → .01, retaining
score1.5, four active paths, two trailing blanks, 32 token paths and the same
verified model/runtime. A lower threshold may increase false wakes; no general
false-wake, battery or owner-voice acceptance is claimed.

Before device retry, Windows native runtime tests with fixed offline synthetic
PCM passed **7/7**: “Mosaic” from two different Windows voices, one of them also
attenuated to 15% amplitude, was detected; English music, Hebrew music, Hebrew
greeting and silence were not detected. The existing private non-wake request
also produced no wake. This is a small development/tuning set, not held-out
accuracy, Android acoustic input or screen-off proof. First detections were at
1500 ms of fed input; host compute (62–110 ms per case) is not audible latency.
Encoder metadata decode_chunk_len=128 also precludes inferring an instantaneous
speech-onset detector from fast host compute. Native fixture report is private
under ignored build/recovery-probes; fixed synthetic source hashes:

- Windows Zira Mosaic: `1f07f526a92635b0f4591a0044ba884f74bbf740b0a74c30e6b366a792870266`.
- Windows Hazel Mosaic: `c127a03531bbbad07dcea4c3ae495a336a71d0f50dfc013df21ee5a4b35e5ba7`.
- Windows English music: `431326ae105b1981ee6858f12e1a8a8e60d4943c2af1e5ab5343b167f9fa87f7`.
- BlueTTS Hebrew music: `4fb72b40c56bbe36d153fc0464bdcb3c627f8f8257705fe689b158967c1637cd`.
- BlueTTS Hebrew greeting: `c8b05ac93e955cb4c0bfb008616d6ea988dbeb48123e007355fbb7a0006121a1`.

## Primary implementation references (continued)

- [Pinned KeywordSpotter Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/KeywordSpotter.kt).
- [Pinned keyword token parser](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/utils.cc).
- [Voice v0.1 acceptance](https://github.com/adarcook/mosaic-docs/blob/main/docs/releases/VOICE_V1_HANDSFREE_ACCEPTANCE.md).

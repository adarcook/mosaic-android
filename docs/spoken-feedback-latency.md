# ASR-34 / VOICE-12 — Audible interaction and scoped standby verification

Date: 2026-10-18, Asia/Jerusalem. Draft follow-up within PR #49. Main remains `7d2a4d4198fe74b81b7cd2630bc28cb74a0c71f9`; related PRs #36, #43, #44, #48 and #49 remain open. Roadmap `adarcook/mosaic-docs/ROADMAP.md` still identifies Stage 0 as last Complete; none is completed by this experiment.

## Hypothesis and baseline

Baseline code `3fccda881869f40aa0d0bb33e668b1b2209f59cb`, evidence-only commit `def7273c4ebc8cd872484cd007fcbd95bba0d9e0`, Voice 0.14 / Zipformer 0.6. Baseline local Voice APK SHA-256 `d6f174389dc6e5d846a5ac343e0c95cfc8be41dfe18aa25079c2633d2d05cf84`. Pixel 10 Pro / Tensor G5 / Android 17 fingerprint `google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys`.

Baseline detected-wake to ready-tone API was 491 ms, request completion 32,108 ms including 6.848 s capture, and total about 33 s. The user confirmed a faster cue but said it was barely audible and the answer still too slow for interaction without looking at the screen. The task is faster feedback **and** reduced real answer latency; fixed speech is not evidence that inference became faster.

Candidate Voice `0.15-spoken-feedback` / code 13, companion unchanged. Hypothesis: privately cached, fixed spoken cues provide clear immediate acknowledgement and stage/failure feedback without model startup. Full model verification can be moved to the explicit standby activation, with the existing fail-closed file-identity receipt safely reused across worker disposal and subsequent locked turns in that same in-memory activation.

## Changed and controlled factors

- On explicit enable while unlocked, the existing sequential preparation verifies and loads/releases ASR, Gemma and BlueTTS. During the TTS prepare stage, five fixed cues are synthesized locally once and cached in private app storage. No network or system/cloud TTS is used.
- Wake feedback says “אני כאן. דבר אליי.”; capture starts only after its actual playback completes plus 100 ms. The cue itself is never recorded as the request. After mic release, cues acknowledge receipt, answer preparation and speech preparation. A failed turn has a fixed spoken failure message before the assistant window is closed.
- Cue audio is float PCM normalized to a bounded peak, played at track gain 1 using assistant speech attributes. Global device/media volume is not changed. Cue files have bounded duration/rate, a fixed catalog/model contract and SHA-256 verification. Missing/corrupt/unknown cues cannot silently activate standby. No personal speech or model binary is stored in the cache or committed to Git.
- The existing verification implementation, pinned digests, file-key requirement and before/after snapshots are unchanged. Its session token is now held in main-process memory for the explicitly opted-in standby activation. The same token is supplied for subsequent actual stages, including after UI pause. Stop, error, assistant replacement or process loss clears it. New activation gets a fresh token and full checks; changed file identity/contract causes full verification, and failure remains fatal. Missing filesystem file keys still force full verification. This is a documented scope extension from foreground UI lifetime, not a persistent cross-activation hash exemption.
- Models still load on demand and are killed sequentially with observed Binder death; none stays resident beside the waiting ASR. The tiny PCM cue player can run during engine loading/inference. It is stopped when the actual answer playback begins. Waiting audio remains memory-only; lock/privacy/WhatsApp guards, matching signatures and no-reboot-restart remain unchanged.
- Gemma inference settings, output token limit/system prompt, ASR models/beam, BlueTTS graphs/steps, quantization, native runtime/dispatch and backend placement are unchanged: waiting Zipformer and BlueTTS on CPU (two threads), Gemma requests Tensor G5 NPU. No new acceleration claim.

## Reproduction and acceptance

Build/test the controller with `testDebugUnitTest assembleDebug`; inspect relevant CI on exact code SHA, APK identity and installed/candidate signer before data-preserving update. Retain baseline APKs. The first explicit standby activation must finish while Mosaic is foreground. Subsequent turns must work with screen off/keyguard showing without reselecting the assistant or reopening the app.

Acceptance: audible and understandable initial cue, mic only after cue completion, meaningful receipt/stage/failure cues without answer overlap, two consecutive locked turns, full activation verification followed by metadata receipt hits on unchanged files, lower measured end-of-speech-to-first-answer latency, successful automatic rearm. Report initial activation cost separately. Tests cover cache tampering/truncation, unknown path IDs, missing catalog entries, invalid PCM and normalized round-trip; existing receipt invalidation, Binder ordering, playback and endpoint tests remain in scope. Build/JVM success does not establish acoustic quality, volume or Android lifecycle behavior.

Measure detected wake to first cue, cue end to capture, capture duration, ASR/Gemma/TTS verification/load/compute, time from end of speech to first actual answer audio, output duration/playback, worker death and rearm. Fixed feedback timing must be separate from actual answer timing. Use same command/input where possible; report reread audio/duration differences instead of claiming controlled A/B. Raw requests/transcripts and signing/model material stay private. Model revisions and original locked-turn evidence remain in [handsfree-assistant.md](handsfree-assistant.md).

## Evidence, decision and rollback

Local controller `testDebugUnitTest assembleDebug` passed: 61 tests, zero failures/errors, including existing receipt invalidation and seven cue-cache tests. CI/device outcomes will be appended with exact candidate SHA/APK identity. At writing, acoustic feedback, two-turn stability, actual speedup, battery, false wakes, call/mic contention and locked WhatsApp rejection are pending. Decision: pending device acceptance. Rollback source is `3fccda881869f40aa0d0bb33e668b1b2209f59cb`; rebuild with a non-decreasing versionCode and existing signer, preserving all data/models. Do not uninstall, clear data or bypass signature checks.

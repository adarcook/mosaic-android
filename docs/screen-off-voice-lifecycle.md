# ASR-35 / VOICE-13 — Screen-off turn lifetime and standby health

2026-10-18, Asia/Jerusalem. Baseline `be78292dc4f05f5e4d8074ec2476215fd5f0926a`, Voice 0.15 / Zipformer 0.6. Baseline Voice APK SHA-256 `106c67ff9c26b317f183e3199ef97c285a4bc693ea2d13ed9e5e08c4179493d1`; companion `762debe61c3ce8131ba5c572bdbfe13ed80e4f98a05bf05228fbc646c7407558`. Candidate Voice 0.16 (14) / Zipformer 0.7 (6), Draft PR #49. Main and roadmap completion are unchanged.

## Observed problem and correction of evidence

On Pixel 10 Pro / Android 17, the 0.15 activation completed in 19,589 ms; fixed-cue generation was 6,009 ms and standby then started. Full initial checks measured Gemma verification 4,303 ms and TTS verification 1,857 ms. All five CI workflows passed (`39118026827`, `39118026812`, `39118026692`, `39118026695`, `39118026716`); controller artifact `12133665023` ZIP digest `e46f59cbc9b029e6243f42665a2da05e659e5beff4018007faab8f69036ccbd1` is distinct from the installed locally signed APK.

The readiness clip completed in 1,338 ms from its request; capture started 104 ms later. The request was cancelled on Activity pause after 5,620 ms capture, before ASR completion. The user reported manually turning the screen on, remaining at keyguard, and no navigation away. They reported no response while the screen stayed off but apparent response with the locked screen lit. A system DreamActivity was top in the later inspection; that snapshot does not prove the cause of pause.

**Evidence correction:** prior snapshots of screen-off followed by a later wake event do not establish that detection happened without manual screen activation between snapshots. Earlier results establish locked interaction, not reliable end-to-end screen-off operation. No 0.15 answer speedup or repeated-turn acceptance was established. The two failures (standby detection with display off; active turn cancellation on pause) require separate measurements.

## Hypothesis and changed factors

- A valid one-use assistant nonce still opens the controller. While that Activity is foreground, it starts a non-exported microphone/media-playback foreground service before readiness audio. A visible notification has Stop; a partial wake lock and a six-minute watchdog bound the turn. The controller now requests its own notification permission through OS UI before enabling standby. There is no boot restart or background shell permission grant.
- A hands-free turn may continue through Activity pause only while that foreground service is active. Manual touch turns keep their original foreground cancellation rule. The orchestrator remains a retained Activity in this experiment; Activity destruction still cancels and cleans up, and common configuration changes are handled without recreation. This is not yet a complete service-owned pipeline refactor.
- On success, the turn service stays alive while the companion rearms; it stops when the signed, token-matched listener reports listening. Stop/error/assistant replacement also stops it. On errors the fixed failure cue remains available. Keyguard is not dismissed and private WhatsApp content remains guarded.
- Session UI is disabled in `onPrepareShow`, before `onShow`, as documented by Android, instead of disabling it after window display starts. No assist context/screenshot is requested.
- The companion sends five-second health windows containing only processed audio milliseconds, wall milliseconds, decode milliseconds, count of blocks with any nonzero samples, and `isInteractive`. No PCM, recognized text, word hashes or voice-activity classification is persisted. Nonzero samples can be ambient noise; the count is not proof of intelligible speech. Allowlisted numeric fields are bounded and appended to the private rotating status journal without changing the listening state.
- Existing ASR model, beam/decoding settings, model hashes, Gemma/BlueTTS settings, scoped verification receipts, cached cues and sequential heavy-worker death gates are unchanged. Standby and TTS remain CPU/two threads; Gemma requests Tensor G5 NPU. The new foreground service contains no model.

## Acceptance and reproduction

Build/test both APKs; inspect CI and artifacts on exact candidate SHA, compare signers and retain installed APK backups before `install -r`. Never uninstall/clear data or bypass a signature mismatch. Existing controller/companion JVM tests cannot establish OS foreground microphone eligibility or screen-off behavior.

After native notification permission and explicit standby preparation while unlocked: put the phone to sleep deliberately, verify keyguard and non-interactive state, then have the user say Mosaic without touching the device. Collect coarse health windows while off. Verify readiness cue, automatic capture endpoint, metadata verification reuse, first actual answer audio, terminal completion, listener rearm and a second untouched locked turn. Test Stop, watchdog, Activity destruction and mic/call contention separately. Keep feedback latency separate from answer latency; compare identical input where possible and report reread differences. Screen-off snapshots alone are insufficient acceptance.

Local builds and 61 controller + 9 companion tests passed. Candidate SHA, CI, install identities and device outcomes will be appended; device screen-off acceptance, actual speedup, battery, false wakes and sustained stability remain pending. Rollback source `be78292dc4f05f5e4d8074ec2476215fd5f0926a`, rebuilt with non-decreasing versionCode and the existing signer, preserving data/models. No merge or roadmap completion.

References: [Android assistant-session lifecycle](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession), [foreground microphone/playback types](https://developer.android.com/develop/background-work/services/fgs/service-types).

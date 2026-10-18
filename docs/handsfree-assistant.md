# ASR-33 / VOICE-11 — Local hands-free assistant experiment

Date: 2026-10-18, Asia/Jerusalem. Baseline/rollback source: `ef67ed9099921a1b2936707385dc8ef5d863824b`, Voice 0.13 / Zipformer 0.5. Candidate: Voice `0.14-handsfree-experiment` (12), Zipformer `0.6-local-wake` (5). Draft stacked on UI PR #48; no merge or roadmap completion.

## Hypothesis and changed factors

An explicitly enabled microphone foreground service can use the already imported, pinned Hebrew Zipformer model for software wake-word recognition while the screen is off. A user-selected Android VoiceInteractionService can then start an assistant Activity above keyguard without dismissing it. The existing buffered ASR/Gemma/BlueTTS chain can capture a new request and finish it without touch using an initial energy endpoint detector.

This is **full streaming ASR on CPU during standby**, not the privileged Pixel/DSP hotword detector and not a proven low-power wake-word model. The ASR architecture, greedy streaming endpoint behavior and microphone lifecycle differ from the buffered voice baseline. Battery, false wakes, missed wakes and long-running stability need measurement before everyday use.

## Data and permission boundaries

- The user chooses Mosaic as Android's default assistant using the OS role screen. Existing Google assistant selection is replaced only by that choice. No shell role/permission grant or keyguard dismissal is used.
- The user enables standby from Mosaic while unlocked, with controller microphone permission and companion microphone/notification permission granted through OS dialogs.
- Standby uses a visible microphone foreground-service notification with Stop, the OS mic indicator and a partial wake lock. It is not restarted automatically after reboot/force-stop. Changing assistant, stopping the service, permission/runtime errors or owner-process loss stops standby.
- Waiting audio and all incidental recognition text are memory-only. Streams reset on endpoint or at 30 seconds, bounding recognition history. No waiting PCM/transcript is stored, broadcast, logged or uploaded.
- Only exact recognized words `מוזאיק`, `מוזייק`, or `mosaic` trigger; `מוזיקה` and substrings do not. This is a transcription matcher, not speaker authentication.
- Same-signature service protection and explicit token-scoped PendingIntent callbacks guard cross-app startup. The controller accepts only allowlisted states and waits for both a wake event and observed worker Binder death before launching the heavy pipeline.
- The default-assistant session requests neither assist context nor screenshots. A one-use in-process nonce protects automatic recording from arbitrary exported Activity intents.
- Above keyguard, previously stored transcript/answer text is cleared from the visible screen, and settings/history/export controls are disabled. WhatsApp's existing `DEVICE_LOCKED` guard stays in place; sensitive notification content still requires unlocking. No unlock credential is requested or bypassed.
- Captured requests after the ready tone retain existing private voice-journal/audio behavior. The wake-status JSONL contains fixed status strings and timestamps only, rotates without deleting prior files, and has a separate export button.

## Handoff and operation

1. Choose Mosaic as default assistant. Enable local listening and approve the companion's requested permissions once.
2. Wait for the status to say it is listening, then turn off/lock the screen.
3. Say Mosaic, **wait for the ready tone**, then give the request. Speech in the wake phrase itself is not forwarded as the command. The hands-free path captures first and loads each engine only when its stage is reached, preserving stage verification and release gates. Manual touch preparation remains available.
4. Initial energy-based endpointing stops after speech followed by roughly 1.1s quiet, or no speech for 8s; the existing 12s capture cap remains. Quiet speech/noisy rooms need acceptance testing.
5. After a completed turn, standby rearms only if the user previously enabled it. A system assistant gesture alone does not opt the user into continuous listening. Errors/cancellation fail closed and require explicit reactivation.

The wake worker is a separate `:wake` process and releases mic/native recognizer/wake lock before sending wake completion and exiting. Buffered request ASR, Gemma and BlueTTS retain their sequential isolation/release gates. No model weights, quantization, dispatch files or Gemma/BlueTTS inference settings changed. Backend: waiting Zipformer CPU INT8, two threads; speech engines retain user-selected baseline settings. No GPU or privileged hotword acceleration is added.

## Reproduction and acceptance

The existing sherpa-onnx 1.13.8 AAR and `ivrit-ai/zipformer2-66m-streaming-he` revision `ee769a2a13b887f91572eec349458bfb0fe79e7a` are pinned as before. Startup verifies the existing private lock and model hashes. No model weights are downloaded by this change.

Build and test both modules with `testDebugUnitTest assembleDebug`; verify runtime packaging and compare each installed/candidate APK signer before `adb install -r`. Keep both baseline APKs privately. Never uninstall/clear data to resolve signer or version problems. CI must be inspected on the implementation SHA before device installation. Unit tests cover exact wake matching/rejections, event/death ordering and one-shot dispatch, stopped-worker rejection, speech/silence endpointing and no-speech bounds. They do not establish acoustic recognition quality or Android lifecycle behavior.

Physical gates: role selection, permissions, visible stop notification, actual audio capture allowed when locked, wake handoff/Binder death, ready tone, automatic capture end, local spoken reply, second locked turn, no-message/locked-notification failure, stop and restart behavior. Also test CPU/memory, calls/mic contention, false wake rate, missed wake rate, long silence and overnight battery separately. Report Android build identity, APK hashes and measured outcomes without private speech content. Pending until observed; passing compilation is not physical acceptance.

### First locked acoustic turn and latency follow-up

At 12:48 local time, the installed implementation above detected the wake word with keyguard showing. Listener cleanup/death-to-session dispatch took 116 ms by controller timestamps. Preparation then took 16,305 ms, automatic capture contained 5.12 s, and the request completed in 25,744 ms (ASR 3,046 ms; Gemma 4,440 ms; TTS 11,545 ms including synthesis and playback). TTS generated 4.319 s of output; playback was 4,762 ms. Standby rearmed and reported listening 2,008 ms after rearm. These are one actual microphone turn, not a controlled performance benchmark. The user confirmed wake and spoken reply without unlocking, and reported it was very slow. Fingerprint: `google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys`.

The latency follow-up removes the redundant hands-free prepare/load/release pass before capture. Engines still verify their inputs and load sequentially at their actual stage; none are kept resident alongside the listener. Manual push-to-talk preparation is unchanged. Diagnostics now distinguish `local_wake` activation and `handsfree_request`/`handsfree_prepare` modes instead of labelling those requests push-to-talk. This changed ordering requires a new device observation before claiming a speedup. Second locked turn, locked WhatsApp rejection, notification-stop interaction, acoustic quality, battery and false wakes remain pending.

References: [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [foreground microphone services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [background microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [privileged hotword API boundary](https://source.android.com/docs/whatsnew/android-12-release#alwaysonhotworddetector).

## Device setup evidence — 2026-10-18, Asia/Jerusalem

Implementation `709beb61719dca6502ad835524d227e2e0bdf552` passed all four Actions workflows: Android Build `39116166490`, Hebrew Zipformer Android Gate `39116166462`, Tensor local LLM gate `39116166531`, and Zipformer voice loop opt-in checks `39116166467`. Local tests: 54 controller + 9 companion passed. CI artifacts were inspected; the installed packages were rebuilt locally with the existing matching debug signer, not the CI signer.

Both updates installed with `adb install -r`, preserving data/models. Voice APK SHA-256: `538a3f8aa99bcfaeb69363ecf1294d0d52cdecee8629d2dfaec9e9ebe3940ac5`; companion: `762debe61c3ce8131ba5c572bdbfe13ed80e4f98a05bf05228fbc646c7407558`. Both candidate and prior installed packages verified signer SHA-256 `ef154770847924693d77aaceda155a8ef66751f30d2219c72e967ea0a6a61e35`. Private baseline APK backups retained; no uninstall, data clear, shell permission/role grant or keyguard dismissal.

On Pixel 10 Pro / API 37, the user unlocked for setup. The role-request intent returned without a chooser; the standard voice-input settings screen correctly listed Mosaic. Mosaic was selected and confirmed there, then companion microphone (while using) and notification permissions were approved through OS dialogs. The assistant-selection button now opens that observed working settings screen directly. At 12:46 local time, status-only evidence showed `listening` 2,046 ms after startup request; Android reported foreground microphone service type `0x80`, notification ID 54 with one action, and the separate wake process alive. This proves startup only. Locked acoustic wake, tone, automatic request completion, rearm, battery and false-wake acceptance remain pending user/device observation.

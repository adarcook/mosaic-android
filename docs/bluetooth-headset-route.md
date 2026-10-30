# Pixel Buds Pro 2 — route foundation (2026-10-31)

Status: integrated opt-in candidate; physical headset acceptance pending.
The earlier unwired foundation below is retained as the design baseline. The
following integration section supersedes its disabled/unwired status.
Baseline and rollback: #57, `0f421abc7e569d7e15d986cc01730ca8e60c1f3f`.
Main remains `613b71ebd3d94dcb7e84f70af058a818739fb3f9` (Voice 0.22).
Reviewed open #54 timing, #55 bridge signing/install and #57 persistent recovery;
#50–#52 remain unmerged dependencies. This branch stacks on #57, not bridge #55.

## Implemented boundary

`HeadsetRouteLease` and `AndroidHeadsetRoute` introduce a service-thread-owned,
explicitly started communication-route lease for API 31+ (current minimum).
The caller chooses an enumerated HFP/SCO or LE headset sink ID; A2DP alone is
not a microphone route. No matching by product name or stored Bluetooth address.
Focus must be granted immediately and existing communication/call mode is refused.
The adapter requests MODE_IN_COMMUNICATION and setCommunicationDevice, observes
OnCommunicationDeviceChangedListener and waits at most five seconds. SELECTED
means only that the selected communication sink matches; it never means capture
is ready, microphone input is verified or a cue was audible. Delayed callbacks
carry generation tickets. Focus loss including duck, changed selected route,
timeout, selection refusal and explicit stop terminate the lease. Audio stops
before route/focus cleanup. Reconnect requires a new explicit start; no retry loop.
Cleanup unregisters callbacks, clears this selection, restores mode when still in
communication mode, and abandons focus. A single owner within the process is
required; this adapter must not compete with another app-local mode owner.

The adapter is compiled but has no production call site, no new permission grant,
no manifest or APK version change, no IPC contract change and no automatic startup.
This deliberately isolates route lifetime from the existing assistant's recovery.
It is a prerequisite, not an end-to-end Pixel Buds implementation.

## Android constraints and next integration

Android's selection API takes a sink and chooses its paired source automatically.
A successful boolean is acceptance, not route completion. Use setCommunicationDevice
for both HFP and LE Audio; do not combine legacy startBluetoothSco calls. HFP can
change media bandwidth/quality; LE Audio availability depends on OS/device settings.
Do not assume Pixel Buds built-in Google hotword DSP exposes custom “מוזאיק”.

The future owner must declare MODIFY_AUDIO_SETTINGS. RECORD_AUDIO and microphone
FGS permissions/type remain necessary in the recording process. BLUETOOTH_CONNECT
requires explicit runtime consent on API 31+ if accessing paired-device Bluetooth
APIs; this slice uses AudioManager enumeration only, with no scanning/pairing or
permission request. Handle SecurityException and revocation without fallback.
Start the microphone FGS from an eligible visible/assistant context, after consent;
Bluetooth presence does not exempt while-in-use, background-start or boot limits.
Android 15 focus requests require top-app or a running FGS. Do not impersonate a
phone call or add phoneCall FGS to obtain a route.

WakeListenService is in the signed companion :wake process; request capture is in
VoiceActivity and TTS in :tts. A reviewed single-owner handoff protocol is needed
before enabling this adapter: keep the route through wake → immediate cached ack
→ recording-before-ready → ASR/tool → headset TTS → rearm, with no competing owners.
Verify AudioRecord.getRoutedDevice after recording starts and routing changes,
requiring the expected headset input type/identity; output sink ID is not the input
ID. Drain/discard frames until verified, stop on mismatch/silencing and never
silently use the phone microphone. Verify playback routing too, use communication
AudioAttributes consistently for cues and TTS, and prevent speaker fallback of
private answers. Existing USAGE_ASSISTANT playback is not changed here.
Preserve keyguard/notification privacy checks; a connected headset is not identity.
Do not persist idle audio, transcripts, device names/addresses or new telemetry.

## Verification and physical gate

Host tests exercise matching/late callbacks, denial, timeout, disconnect, reconnect,
focus loss, idempotent stop and cleanup after permission/stop failures. Android
adapter compilation validates API compatibility, not platform route behavior.
Reproduce with Gradle 8.9/JDK21: `gradle -p poc/tensor-llm testDebugUnitTest compileDebugJavaWithJavac`.
Exact results and hosted CI are recorded in the draft PR; no APK installation in
this slice. Engine weights, CPU Zipformer/BlueTTS and Gemma NPU are unchanged.

Before enabled product acceptance on Pixel + Pixel Buds Pro 2:

1. Record exact OS, APK commits/hashes, signer and negotiated HFP/LE route privately.
2. Arm while unlocked, move the phone outside acoustic pickup but within Bluetooth
   range, screen off; verify actual headset input and output throughout the turn.
3. Run at least 20 wake-to-audible-ack trials (P95 target <500 ms, not a result),
   immediate speech after ready, ten full Hebrew request/answer/rearm cycles.
4. Exercise calls, transient/permanent focus loss, disconnect mid-capture/playback,
   reconnect, multipoint, revoked permission, microphone privacy switch, Stop,
   process death and ordinary reboot. Require no phone-mic/speaker fallback or
   automatic restart after Stop; discard stale callbacks/partial requests.
5. Compare sustained screen-off baseline, phone-mic wake and headset-mic wake
   under matched charge/media/radio conditions for phone AND buds drain, thermal,
   false/missed wakes and media quality. Continuous headset capture can keep a
   communication link active; there is no low-power or battery claim yet.

All acoustic, routing, power and sustained screen-off gates remain pending.

## ASR-42 / VOICE-20 — integration, 2026-10-31

Candidate: Voice 0.27-headset-route/code30 + wake 0.9-headset-input/code10.
Baseline/rollback: #58 foundation `bd8701a17a0358d80d7f7042f53919ffdcc7f3d9`.
HeadsetRouteService is now the one foreground route/focus owner across the whole
interaction. It promotes microphone/mediaPlayback FGS before requesting focus,
requires existing assistant/microphone/notification opt-in, and uses the normal
MODIFY_AUDIO_SETTINGS permission. No Bluetooth scanning, pairing, device-address
storage or additional runtime permission grant is introduced.

The visible headset checkbox defaults off. In headset mode, exactly one available
HFP/LE communication sink and exactly one same-profile input are required; ambiguity
or missing input pauses listening rather than choosing another device. Signed wake
IPC and same-package TTS requests carry `headset_required`, `headset_sink`,
`headset_input`, `headset_type` as transient positive IDs/type. These IDs expire on
route loss and are never saved in preferences or logs. Controller requires companion
code10 for headset mode. Companion and capture prefer VOICE_COMMUNICATION input,
discard startup PCM until AudioRecord confirms the expected source, and check the
source/configuration/silencing on each block before inference/storage. Source IDs
are distinct from sink IDs. HeadsetEvidence defines directional ID/type checks.

A new authenticated `detected` callback precedes companion/native teardown. It
requests an 80ms generated acknowledgement tone without any model. Final `wake`
plus Binder-death still gates heavy-worker dispatch. The normal ready clip follows
verified capture; receiving an acknowledgement is not permission to start speaking.
Headset turns do not request screen wake or KEEP_SCREEN_ON. Existing lock-screen
private-notification refusal remains in force; headset presence is not identity.

Feedback and TTS use communication AudioAttributes, explicitly prefer the chosen
sink, and submit only silence until AudioTrack reports that sink. Silence frames
are excluded from speech playback-head accounting. Route callbacks pause/flush on
loss; per-write/head checks stop further speech submission. There is no application
fallback to the speaker. Android route changes are asynchronous: device trials
must still verify no residual buffered speech leaks during a disconnect. The route
owner stops capture/feedback/workers before releasing focus on interruption.

Stop cancels saved listening intent. Disconnect/focus loss pauses the current
request; stale/partial requests are not resumed. A later headset-device-added event
may initiate a fresh bounded restore only while saved listening consent remains
on. No automatic restart after explicit Stop. Without a connected headset the
opt-in mode remains paused and the user can select phone mode visibly.

Host result: 154 controller tests (including route evidence/lifecycle tests) and
11 unchanged companion tests pass; both local APK builds and pinned dispatch check
pass. Exact-source CI, guarded installation and physical route observations are
recorded in #58 follow-up evidence. No acoustic wake, latency, intelligibility,
reconnect/call, screen-off full turn or battery pass is claimed by host tests.
CPU keyword/ASR and BlueTTS, Gemma NPU, model hashes and thresholds are unchanged.
No model download, notification grant or keyguard bypass is needed for installation.

Owner-requested setup can use debug-only HeadsetSetupInstrumentation to set only
`headset_mode=true`; it does not grant permissions or silently enable saved
listening consent. The normal visible checkbox provides the same mode choice.
Acceptance and matched phone/buds battery protocol remain the physical gate above.

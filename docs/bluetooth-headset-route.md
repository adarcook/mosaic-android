# Pixel Buds Pro 2 — route foundation (2026-10-31)

Status: implementation slice, disabled/unwired; no physical headset test or support claim.
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

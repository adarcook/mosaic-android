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

Host result: 157 controller tests (including route evidence/lifecycle tests) and
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

ASR-42 integration refinement: request dispatch also waits for acknowledgement
completion so a fast native teardown cannot cancel the short ack. Three new JVM
race tests pass. ACK_REQUESTED/ACK_PLAYBACK_HEAD/ACK_COMPLETED are monotonic
software markers, not measured audibility. Verified headset health records contain
only a boolean and route type, never device IDs or addresses.


## ASR-42 deployment outcome — 2026-10-31 (Asia/Hebron)

Implementation source `c82b174e2e3c293e413b70434fc99394a85ecf17` passed all five
exact-source hosted gates: Tensor 41140691759, voice-loop 41140691731, Hebrew
Zipformer 41140691728, Tensor probe 41140691714 and Android Build 41140691761.
157 controller tests and 11 companion tests pass. Both CI ZIPs were obtained via
GitHub connector after a stalled direct bridge download was terminated before
extraction/install. Downloaded hashes match GitHub artifact digests:

- Wake artifact 13017579262, ZIP SHA-256
  `7dfd9f308950ed0cc5cc16ff561bcc16f36f850663da176238836b88d024906d`;
  original CI APK `f70ffed4ea51e36f7c4625eb447414e3abb6aae37dd2d2b61da2477a606623b5`.
- Voice artifact 13018803223, ZIP SHA-256
  `9428d1ba9e4762b56a727bd381b372102617062543ab92387066efe179260494`;
  original CI APK `1b776f8ec52b1e0c5a77419d7584008ad4c0dacde293a065d045e076c155faa8`.

The existing bridge's exact-source, digest, enrolled-device, package, signer and
payload checks were retained with the verified local ZIP as its download source.
Installation stopped before adb install: configured enrolled development key is
absent on this computer. The existing standard local development key was tested
on a separate temporary APK copy and failed the unchanged installed-certificate
check. No certificate pin was changed, no key replaced/generated/uploaded, no
uninstall/data/model deletion, no runtime setting/permission or headset opt-in
change. The user confirms the other computer is currently unavailable.

**Not installed.** Pixel still has Voice 0.26/code28 + wake0.8.2/code9.
User reports buds connected/worn, but no new-route microphone/playback or acoustic
probe was performed. Physical acceptance and battery/latency remain pending.
Resume with the existing matching key on its original machine (or an existing
local backup), reverify exact artifacts and installed certificate, install companion
before controller, then enable the visible headset mode and verify source/output.
The remote queue #56 received status only; no deferred install command was queued.
Local queue/config is #59 on port8766. Private local evidence stays under ignored
build/qa-headset. No merge or roadmap completion.


### ASR-42 follow-up — service restoration after a communication session, 2026-10-31

Source c82b174 was installed with the user-copied original development key after
certificate/payload/digest verification: Voice 0.27/code30 installed APK SHA-256
`256ff8a60e4dc26ec0cc8ddc22eb8ec4648887d60dce14d80c949532a22da3ba`,
wake0.9/code10 installed APK `5ad7144849e0f27b59374340c8a90aeafcd076b05f730b7c49e5e93c9c667c41`.
Saved listening consent was already true; debug owner setup changed only headset_mode.
No model/data deletion or new permission/assistant-role grant. Earlier missing-key
block remains historical, superseded by these verified installation receipts.

After setup instrumentation, the system assistant binding was null; ordinary main
process termination and reasserting the same setting did not repair it. A controlled
refresh of the voice_interaction_service setting restored the original selected
Mosaic component and unchanged assistant setting, without activity launch. This is
setup repair, not evidence of autonomous recovery. The restored service correctly
refused to take audio while ChatGPT owned MODE_IN_COMMUNICATION. User ended that
voice session. Review exposed a missing automatic mode-release restore trigger.

Candidate 0.27.1/code31 adds OnModeChangedListener: return to MODE_NORMAL can trigger
bounded background restore under existing headset/listening consent, only when no
listener/turn is active. It never resets the three-attempt failure budget; explicit
Stop still wins. Headset reconnection triggers now consider sink additions only,
not source endpoints created by routing. Companion/model/IPC/backend unchanged.
Baseline/rollback: c82b174e2e3c293e413b70434fc99394a85ecf17. Four new JVM tests cover
mode completion, Stop, competing turns and bounded repeated mode callbacks.
Exact-source CI, installation and ordinary process restart proof are pending here.
No acoustic wake, screen-off full turn or battery acceptance claim.


### ASR-42 deployment/service evidence — 2026-10-31, 0.27.1/code31

Implementation `8dfa345556276413427e726174e04b8a75c2250e` installed successfully
from exact-source Tensor CI 41186510838 / artifact 13037018862. ZIP SHA-256
`98d08adf2790f9b2bc8e0bb297e947fb93e72fe7419752efe87ccebfe09dbe4a` matched
GitHub digest. Original CI APK SHA-256
`c9d8521afe929e1f31d43023787b2a0d17cf0a8cd9d292bd4cd730ad7b4954fb`;
locally signed, pulled-back installed APK SHA-256
`0060a3770473b392aa36f9ef6468fe1d634065dc57c7d0a92d0563a778743c79`.
The enrolled certificate and all non-signature payload entries matched. Existing
wake0.9/code10 from c82b174 remains unchanged/compatible. No app/model deletion,
new Android grant or new assistant-role selection. Local bridge key path was
corrected to the verified user-copied original standard development key; its
certificate pin remained unchanged and the hidden worker restarted on its saved
queue/checkpoint. Keys/tokens stayed local.

161 controller and 11 companion JVM tests pass. Exact-source Tensor, voice loop
41186510804, Hebrew Zipformer 41186510779 and probe 41186510865 passed. General
Android Build 41186510816 was still running at this evidence checkpoint.

Live route inspection found MODE_IN_COMMUNICATION owned by ChatGPT; Mosaic
correctly refused ownership. User ended that voice session. Later AudioPolicy's
actual available output/input lists contained no Bluetooth headset (supported
profile declarations alone are not connected devices). Headset mode stays enabled
and listening consent true; no fallback to the phone microphone was started.

After code31 installation, one controlled ordinary app-owned main-process TERM
(not force-stop) produced a new main PID, a non-null system assistant binder,
fresh "assistant connected / restore listening / prepare headset route" status,
and then a paused-headset status because no headset was available. No Activity
was launched, no setting refresh/instrumentation was used during this restart
probe, and saved consent/headset choice remained true. Private PID/timestamp
receipts are in ignored build/qa-headset/service-restart-{before,after}.json.
This establishes automatic assistant rebinding and a background restore attempt,
not actual microphone processing, sustained screen-off capture or acoustic wake.
The earlier setup-only binding refresh is retained above and is not counted as
this autonomous restart result. Reboot, full headset turn, mode-release restoration
with an attached headset, audible ack, disconnect leakage and power remain pending.

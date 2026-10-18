# ASR-36 / VOICE-14 — Explicit WhatsApp conversation request routing

## Problem, hypothesis and baseline

2026-10-18, Asia/Jerusalem. User screenshot showed a request to read the last WhatsApp conversation (including the ASR spelling `תקרי`) and a generic Gemma answer claiming no access, while the UI displayed notification permission. Baseline `0249d01d5f4d6f870bc95929ed1788bf5a58693c` / installed Voice 0.20 (implementation `7d9cf9a8e183bd94386765121ddbe9ccc10b767a`). The matcher required a message/SMS noun, so the conversation noun failed before the native notification tool was reached. Permission alone cannot correct dispatch. No private notification body/sender or original audio was collected from the screenshot.

Main verified `7d2a4d4198fe74b81b7cd2630bc28cb74a0c71f9`; related open PRs #44 (tool), #48 (UI), #49 (hands-free) inspected. The roadmap still prioritizes the latest accessible notification, not unrestricted chat history. Stage 0 remains last Complete; no merge or roadmap completion.

## Candidate / changed and controlled factors

Voice 0.21 / code19 in Draft #49. Recognize explicit read + last/new conversation/chat nouns as a request for the existing latest-active-notification tool. Preserve the ASR read variant; strip Hebrew vocalization without splitting letters, accept split brand spelling and both final/non-final nun in latest. Conversation/call mention alone, explanation questions, negation, sending/deleting/calling/replying and whole-history requests do not authorize notification reads. Message noun + implicit latest requires a question cue instead of any mention of a new message. This remains a conservative deterministic rule, not a typed Gemma planner or general language understanding claim.

The adapter is unchanged: current Android `com.whatsapp` notifications only, bounded content, same native notification permission, connected listener and DEVICE_LOCKED guard. No chat database, dismissed history, contact selection, write action or keyguard bypass. The spoken success already identifies the last notification; unavailable states remain truthful. User-specified latest conversation wording does not add full-conversation access.

After ASR, the private journal gets an `intent_route` event with one of two fixed enum values: `whatsapp_latest_notification` or `conversation`. The diagnostic timeline renders an allowlisted Hebrew description; never the request, message, sender or arbitrary route value. Existing tool invocation keeps status-only metadata and tool TTS excludes private normalized message text from the journal. ASR model/weights/beam/windowing, Gemma/BlueTTS settings, clear cached cues, sequential worker release, wake/lifecycle and backend placement unchanged: Zipformer and TTS CPU/two threads, Gemma requests Tensor G5 NPU. No performance/ASR-quality claim.

## Acceptance and reproduction

Text regression for the screenshot's non-sensitive command: conversation + WhatsApp + explicit read + latest must dispatch to latest-notification. Cover message phrasing, chat apostrophe, vocalization, split brand, masculine latest; negatives above must not read. Route presentation must reject untrusted fields. Build controller tests/APK, inspect exact-head CI/artifact, compare installed/candidate signer and retain baseline APK before data-preserving update. No uninstall/reset/signature bypass.

After user unlock and explicit preparation, repeat the request. Collect only `intent_route`, `tool_invocation` status, stage/end and timing metadata. Expect ASR -> tool -> TTS (no Gemma), or a truthful native failure. Verify no message content in route/tool/TTS journal metadata; retain the user's private journals locally. Native locked request must return DEVICE_LOCKED; successful notification playback requires unlocked device and an active accessible notification. No test notification is sent to anyone by this task. Text tests and CI do not establish acoustic recognition, actual notification availability, voice clarity or sustained wake reliability. An acoustic reread is a different input; no controlled timing comparison.

## Evidence and rollback

Local build and 72 existing/new controller tests passed before the final whole-history negative addition; the final build is recorded below. Candidate SHA, CI/artifact, installed APK and native runtime outcomes pending until appended. Real audio WER, sustained throughput, battery and parity are not measured by this routing change. Roll back source `0249d01d5f4d6f870bc95929ed1788bf5a58693c` rebuilt with non-decreasing versionCode and existing signer, preserving all model/data/journal directories.

Final local controller test/build passed: 72 tests, zero failures/errors, including the whole-history negative case.


## Installation follow-up — 2026-10-18, Asia/Jerusalem

Code `737a5e903beee3f9645dad18be0d6609678c6ce4` passed exact-head LLM workflow `39126419921`; controller artifact `12135973775`, ZIP digest `ab3566e3f4b70eea0be318c055435c42b301bdcc55d83c4969a48a7e872786be`. Other workflows were still running at pre-install observation. Local controller 0.21/code19 SHA-256 `62167b3632a6fb3234823995825d27ccc8686de85f2b98217e1747ea995fa0be`; prior 0.20 backup `664745dfdca06ed1eed955cc8b44551d604f4ceda67f382b970c08985eba3841`. Both certificates matched existing signer `ef154770847924693d77aaceda155a8ef66751f30d2219c72e967ea0a6a61e35` before replacement. Data-preserving install succeeded and installed 0.21 version was confirmed. Companion/model/data/journal directories retained. The phone was locked; user asked to unlock for explicit preparation and same-request acoustic routing check. Actual native route/status/playback still pending, not inferred from text tests/build. No merge.


## Activation follow-up — 2026-10-18, 15:17 Asia/Jerusalem

After the user unlocked, installed Voice 0.21 and unlocked/interactive state were verified. Explicit activation completed in 15,504 ms (ASR 1,796 ms, Gemma 7,108 ms, TTS 5,505 ms); unchanged cached clear cues verified/reused in 81 ms. Listener reported listening 2,258 ms after prepare terminal. No notification content was read by this activation. User was asked to keep the device unlocked and repeat the last-conversation command; route, native tool status and audible outcome remain pending. No full-history access or acoustic/notification success is inferred from preparation.


## Native route observation — 2026-10-18, 15:19 Asia/Jerusalem

All five workflows on code `737a5e903beee3f9645dad18be0d6609678c6ce4` completed successfully: LLM `39126419921`, Zipformer `39126419850`, voice-loop `39126419855`, Whisper hybrid `39126419834`, Android Build `39126419859`.

The repeated spoken request produced capture 11.899875 s (12-second cap), ASR 2,694 ms, route `whatsapp_latest_notification` at `1792325965942` and tool status OK at `1792325965950`. The tool event contained only the fixed tool ID, status, read_only=true and numeric timestamps. No LLM stage ran. Receipt cue played. This establishes native dispatch to an available notification and avoids the generic model response seen in the screenshot. At this observation TTS had not yet produced its terminal result; audible completeness, final TTS privacy-field omission and listener rearm remain pending. No message body/title or private transcript is published. Different reread audio, not a controlled performance/ASR accuracy comparison.


The native request subsequently completed in 104,974 ms, with TTS 89,272 ms and 56.703 s output audio; first answer playback API `1792325971793` was 8,784 ms after capture-end logging. No LLM stage ran and no normalized message text appeared in TTS journal records, including terminal output. User confirmed it was now OK, but reported full URL letter-by-letter reading was undesirable. Existing VoiceText normalization spells Latin letters/digits; the next slice will replace URLs before this step while retaining the display text. These timings include reading the actual private notification and must not be compared with short generic commands. Routing/native tool read and terminal completion accepted for this trial; long-message acoustic completeness and sustained reliability are not established by user OK alone.

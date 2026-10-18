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

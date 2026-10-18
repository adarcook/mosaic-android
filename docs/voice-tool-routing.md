# Voice tool routing — first extraction before capability expansion

## Baseline and recovered work list (2026-10-18)

Android main is `613b71ebd3d94dcb7e84f70af058a818739fb3f9`, merged via
[PR #49](https://github.com/adarcook/mosaic-android/pull/49). The approved Voice 0.22
interface remains the development baseline. The linked ChatGPT conversation's
assistant reply could not be recovered as text; the following list is reconstructed
from the preceding local chat and verified against the source, rather than claimed
to be a verbatim agreement:

1. Separate request orchestration and tool routing from `VoiceActivity`.
2. Introduce a common typed tool boundary, permission checks and structured results.
3. Preserve route/tool/status diagnostics without persisting notification payloads.
4. Add an editable local personal phrase dictionary in a later slice.
5. Evaluate validated Gemma tool calls separately; consider fine-tuning only after
   measuring failures on held-out phrases and validating runtime/export support.

The authoritative [mosaic-docs ROADMAP.md](https://github.com/adarcook/mosaic-docs/blob/main/ROADMAP.md)
prioritizes local voice/tools and the latest accessible WhatsApp notification. It
does not mark this milestone complete from CI alone. Android has no root ROADMAP.md.
No roadmap completion status changes in this slice.

Open Android drafts were inspected: #44 and #48 are already ancestors of main
through #49, despite remaining open against their older stacked bases. #47 (Pixel
Bridge) and #45 (CI-first policy) remain independent unmerged work. Earlier ASR
experiment drafts remain open; this change starts directly from main and does not
merge or modify those branches.

## This slice

`VoiceTool` defines a read-only capability and typed ephemeral result with separate
display/speech text. `VoiceToolRouter` owns an immutable registry and selects a
single explicit match without running the tool or retaining the request. Duplicate
IDs/routes fail at setup; ambiguous matches fail before execution.

`WhatsAppVoiceTool` adapts the existing conservative matcher and on-demand reader.
`VoiceActivity` registers it once, routes after the ASR worker has exited, executes
the selected capability and passes only its spoken text to TTS. General conversation
continues through Gemma. Every execution calls the existing Android listener again,
so permission and lock are checked at execution time and no prior payload is reused.
The boundary does not itself grant permissions. It accepts only app-registered code,
not model-supplied IDs or commands.

The journal retains the existing route, tool ID, status and read-only flag. Tool
display/speech payloads remain outside preferences and TTS journal payload fields.
Existing route names and the allowlisted diagnostic UI are retained. General voice
transcripts/answers still follow the existing local storage behavior.

Full turn orchestration remains in the activity. Personal phrases, Gemma planning,
new tools, side effects and inference/model changes are deferred. This is a routing
extraction, with no speedup or device-acceptance claim.

## Pixel / installation state

Read-only checks on 2026-10-18 found one authorized ADB device, installed controller
`versionCode=20`, `versionName=0.22-spoken-links`. The bridge loopback health reported
`running` with outbound GitHub transport. Local `allow_install` remains **false**.
The latest recorded issue #46 install result was blocked for that reason:
[bridge result](https://github.com/adarcook/mosaic-android/issues/46#issuecomment-6247485745).
The bridge is in draft #47 and is not part of current Android main.

No new APK was installed for this change and no local opt-in was altered. A later
device update requires an approved exact-head CI artifact, matching existing signer
and explicit installation scope. Remote installation also requires the owner's
local opt-in; never uninstall or clear data to bypass a signer mismatch.

## Validation and remaining device gate

JVM tests cover selection without reads, rejected/negated commands, execution-time
permission/lock changes, all unavailable statuses, separate URL speech/display,
no reuse of a previous message, immutable registration and ambiguous routes.
Existing WhatsApp matcher, selection, spoken-link and diagnostic tests remain.
Local `testDebugUnitTest` passed: **89 tests, zero failures/errors/skips**, including
eight new router/adapter tests. Android Java compilation and `git diff --check`
passed. APK assembly is delegated to the exact-head GitHub PR workflow; no device
runtime result is claimed for this candidate.

After an approved install, verify a normal conversation, explicit notification
request, revoked permission, locked device, no message, cancellation and repeated
turns. Check the private local journal for status-only tool events and no notification
text in TTS results; do not upload private content. Host tests cannot establish
Android permission behavior, intelligibility, screen-off reliability or battery use.

Rollback is the main baseline above. Models, companions, permissions and UI layout
are unchanged by this extraction.

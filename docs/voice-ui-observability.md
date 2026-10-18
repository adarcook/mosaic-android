# VOICE-10 / ASR-32 — Mosaic conversation UI and visible diagnostics

Date: 2026-10-18 (Asia/Jerusalem). Candidate: `0.13-voice-ui`, versionCode 11.
Baseline and rollback source: `3c27ab79c4d9b2feea5fb6363f7e65dede87f2ad`, Voice 0.12, Draft PR #44.

## Purpose

Give the existing voice controller a usable Hebrew RTL interface while preserving the working voice/tool path and its diagnostics. The home screen has a dark navy palette, turquoise and violet details, a static microphone illustration, a prepare/speak action, an active stop/cancel action, notification access and conversation cards. The separate Diagnostics tab remains in the same Activity, so opening it does not pause a turn.

The user reported a successful WhatsApp read-aloud turn on installed Voice 0.12 in this conversation on 2026-10-18. This is user-reported acceptance of one turn; no private message, recording or journal was collected for that report. It does not establish repeated-turn stability or failure-case coverage.

## Retained behavior

- Existing package, signing identity, models, engine/beam preferences, stored transcripts and JSONL remain in place.
- The first home action prepares the engines; after successful preparation it starts the existing push-to-talk path. Stop ends capture or cancels processing. Capture remains limited to 12 seconds.
- ASR/Gemma/BlueTTS remain sequential isolated workers with the existing binder-death release gate. No model, quantization, runtime, dispatch, CPU/TPU/GPU selection or decoding change is introduced.
- WhatsApp remains the narrow explicit read-only intent route from PR #44; this UI does not add Gemma tool planning.
- Notification text remains transient in the conversation view and is cleared when leaving the Activity. It is not added to tool journal events or preferences.
- Setup/model screens, engine experiments, permission management, tool availability check and full journal export remain reachable.

## Diagnostic presentation

The UI shows current stage details, stage progress, total time, failure/cancellation state, journal identity and a bounded 48-line event summary. The summary is generated from known event types and allowlisted stage/status values only. It does not render arbitrary fields, transcripts, notification bodies, answers or raw error messages. Full existing journals remain local and exportable with a personal-content notice. Normal voice journals/recordings retain their existing behavior; this change does not silently delete or redact older files.

The new event summary is scoped to the current Activity instance. After a process restart, the existing last result and journal reference are restored; the summary itself is not replayed. The complete durable evidence remains in the JSONL. Durable history browsing and export preview are later work.

## Acceptance and evidence

- Local JDK 22 / Gradle 8.9: `testDebugUnitTest assembleDebug` passed, 50 tests with no failures/errors. New tests exercise content exclusion and distinguish cancellation from failure.
- The packaged Tensor dispatch passed its existing pinned-hash verifier.
- GitHub Actions on the candidate head and physical-device UI acceptance must be observed separately and recorded in the PR validation section before declaring the candidate ready.
- Check home/diagnostic navigation, default and large text sizing, status-bar/cutout insets, preparation, capture stop, cancellation and setup return. Confirm that controls remain usable when the screen scrolls.
- New repeated spoken turns, ASR accuracy, audio latency, sustained memory/thermal behavior and battery drain have not been measured by the UI build/tests.

## Scope and release discipline

This is a Draft stacked on PR #44. Main and the roadmap completion gates are unchanged. No merge is requested. It is a first app shell, not a production release/signing migration.

Wake word, background microphone operation, default-assistant role, automatic end-of-speech and interruption by a spoken stop command are not implemented. The UI states that activation is by touch; the decorative orb does not imply a listening microphone. There is no continuous animation or background model work added by the screen.

Rollback artifacts must retain the matching signing key and existing model set privately. An older versionCode may require an explicitly reviewed Android downgrade path; do not uninstall or clear data as a shortcut.

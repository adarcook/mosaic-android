# Mosaic Android

The single Android application for the Mosaic personal system.

## Voice interface development baseline

On 2026-10-18 the user approved the current Voice 0.22 conversation interface as
the baseline for future Mosaic app work and explicitly requested integration into
`main` through [PR #49](https://github.com/adarcook/mosaic-android/pull/49).
Preserve its Hebrew RTL layout, dark navy/turquoise/violet styling, conversation
cards and separate Diagnostics tab when extending the assistant.

The current voice controller lives in [`poc/tensor-llm`](poc/tensor-llm), with the
opt-in CPU wake companion in [`poc/zipformer-gate`](poc/zipformer-gate). This source
baseline retains the installed package identities and does not move the voice UI
into the separate nutrition `app` module. See [UI](docs/voice-ui-observability.md),
[hands-free operation](docs/handsfree-assistant.md),
[WhatsApp routing](docs/whatsapp-intent-routing.md) and
[spoken links](docs/notification-spoken-links.md) for implementation and evidence.
Generalized tool routing, personal command mappings, sustained wake reliability
and battery acceptance remain follow-up work. Historical experiment results below
remain dated evidence rather than a production-readiness claim.

Mosaic Android is designed as one installed app with independently maintained product areas such as nutrition, photos, swimming, personal insights, settings, and synchronization.

## Current capabilities

An experimental debug-only [Hebrew voice POC](docs/hebrew-voice-poc.md) checks
local Whisper Small Q5 transcription and BlueTTS fixed-reply synthesis on Pixel before any
architecture re-baseline. BlueTTS sounded good in the device probe; the smaller
STT model is pending device verification after the large-model probe was too slow.

- Photograph and analyze meals
- Review and correct detected foods and nutrition estimates
- Store meal history locally with Room
- Track daily nutrition totals and recent trends
- Configure the Mosaic Server endpoint and visual theme

## Product areas

- **Mosaic Fit** — nutrition, body progress, workouts, and health tracking
- **Mosaic Photos** — photo organization, archive rules, local face and object indexing
- **Mosaic Swim** — planned workouts, recorded sessions, and swimming analysis
- **Mosaic Settings and Sync** — device settings and future synchronization with Mosaic Core

## Architecture direction

The application will remain one APK while the codebase is gradually split into isolated Gradle modules:

```text
app
core:ui
core:model
core:database
core:settings
core:sync
feature:fit
feature:photos
feature:swim
feature:settings
ml:runtime
ml:faces
ml:objects
```

The `app` module owns application startup and top-level navigation. Feature modules own their screens and domain logic. Core modules provide shared infrastructure without coupling features to each other.

See [`docs/android-architecture.md`](docs/android-architecture.md) for the migration plan and module boundaries.

## Technology

- Native Android and Kotlin
- Jetpack Compose
- Room for local persistence
- WorkManager for background indexing and synchronization
- TensorFlow Lite or MediaPipe for on-device ML

## Repository boundaries

This repository owns the Android experience and local mobile data. Long-term cross-domain intelligence belongs in `mosaic-core`; remote API orchestration belongs in `mosaic-server`; shared network contracts belong in `mosaic-contracts`.

## Migration safety

The first architecture stage changes branding and documents the target structure only. It intentionally keeps the existing `life.mosaic.fit` package, application ID, Room database, preferences, and API behavior unchanged.

## Hebrew ASR experiments

Start with the [Hebrew explanation and experiment history](docs/ASR_EXPERIMENT_HISTORY.md): model conversion, CPU/TPU responsibilities, measured breakthroughs/regressions, reproducibility gaps and rollback commits. The integrated voice baseline remains experimental; merging its source does not complete the production acceptance gates.

For each new experiment or later device result, update the history using the [record template](docs/ASR_EXPERIMENT_TEMPLATE.md) and follow [AGENTS.md](AGENTS.md).

The [ASR-18 audio archive trial](docs/ivrit-whisper-audio-archive.md) adds durable local recording and resumable post-recording transcription. Device validation is pending; it is not background or realtime transcription support.

The [local TPU LLM trial](docs/tensor-local-llm.md) connects completed ASR archives to a separate Gemma 4 E2B / Tensor G5 companion APK for local summaries and questions. Device acceleration and Hebrew quality remain pending.

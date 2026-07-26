# Mosaic Android

The single Android application for the Mosaic personal system.

Mosaic Android is designed as one installed app with independently maintained product areas such as nutrition, photos, swimming, personal insights, settings, and synchronization.

## Current capabilities

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

# Mosaic Fit

Android-first application for nutrition, fitness, swimming, weight, and progress tracking.

## Initial scope

- Photograph meals and review detected ingredients
- Estimate calories, protein, carbohydrates, and fat
- Confirm or correct quantities before saving
- Track body weight and progress
- Import or record workouts and swimming sessions
- Synchronize structured data with `mosaic-server`

## Technology direction

- Native Android
- Kotlin
- Jetpack Compose
- Room for local persistence
- WorkManager for reliable synchronization

## Repository boundaries

This repository owns the mobile user experience and local mobile data. It does not own long-term cross-domain intelligence or server-side image analysis.

## Related repositories

- `mosaic-server` — always-on VPS API and meal-analysis orchestration
- `mosaic-contracts` — shared API schemas
- `mosaic-core` — local long-term intelligence and indexing
- `mosaic-docs` — system-wide documentation

## Status

Foundation stage. No production application code yet.

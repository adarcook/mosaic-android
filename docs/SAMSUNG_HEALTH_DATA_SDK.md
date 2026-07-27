# Samsung Health Data SDK integration

Mosaic Training supports an optional direct Samsung Health source in addition to Health Connect.

## Requirements

- Android 10 / API 29 or newer
- Java 17
- Samsung Health 6.30.2 or newer
- Samsung Health Data SDK 1.1.0
- A physical Android device; the SDK does not support emulators

## Install the SDK locally

1. Download **Samsung Health Data SDK 1.1.0** from the official Samsung Developer Health Data page.
2. Extract the archive.
3. Copy the AAR from the SDK `libs` directory to:

```text
feature/training/libs/samsung-health-data-api-1.1.0.aar
```

The AAR is intentionally ignored by Git and is not committed to this repository.

After the file is present, Gradle automatically:

- adds the AAR dependency;
- enables the `src/samsungHealth/java` implementation;
- exposes `BuildConfig.SAMSUNG_HEALTH_SDK_AVAILABLE = true`.

Without the AAR, the project continues to compile with the fallback implementation in `src/noSamsungHealth/java`.

## Enable Samsung Health developer mode

Reading data during local development does not require a partner approval, but Samsung Health Data SDK developer mode must be enabled:

1. Open Samsung Health.
2. Open **Settings → About Samsung Health**.
3. Tap the version area at least ten times.
4. Open **Developer mode (Samsung Health Data SDK)**.
5. Enable **Developer Mode for Data Read**.

Developer mode is only for local development and testing.

## Test

```bash
./gradlew :feature:training:assembleDebug
./gradlew :app:installDebug
```

Open Mosaic → Training. The Samsung Health direct-import card should allow requesting Exercise read permission and reading exercise sessions from the previous year.

## Distribution

For an app distributed to users, Samsung requires a partnership request and registration of:

- the application package name;
- the release signing certificate SHA-256;
- the requested health-data scope.

Developer mode must not be relied on for distributed builds.

## Architecture

```text
Training feature
├── Health Connect source
├── Samsung Health Data SDK source (optional local AAR)
└── future Room-backed unified training repository
```

The next step after validating Samsung exercise reads is to normalize both sources into one local training model and deduplicate sessions by time, duration and source identifiers.

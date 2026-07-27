# Samsung Health Data SDK

This directory contains the local Samsung Health Data SDK dependency used by `feature:training`.

## Required file

Place the following file in this directory:

```text
samsung-health-data-api-1.1.0.aar
```

Expected full path from the repository root:

```text
feature/training/libs/samsung-health-data-api-1.1.0.aar
```

## Official source

Download the SDK from Samsung Developer:

https://developer.samsung.com/health/data/overview.html

After downloading and extracting the Samsung Health Data SDK archive, copy the AAR file from the extracted package into this directory.

## Why the AAR is not committed

The AAR is distributed by Samsung, is not published through Maven Central, and should remain a local dependency. The binary is ignored by Git; this README is committed so the source and setup steps are not lost.

## Version note

This integration currently expects Samsung Health Data SDK version `1.1.0`. If the SDK is upgraded, update the file name and Gradle configuration together.

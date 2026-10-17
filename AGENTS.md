# Mosaic Android — instructions for coding agents

## Source of truth

Before coding, inspect [Mosaic roadmap](https://github.com/adarcook/mosaic-docs/blob/main/ROADMAP.md), the relevant repository's current `main`, open PRs and recently merged PRs. Implement one small testable slice on a feature branch and open a Draft PR. Never merge without an explicit user request. Work in a branch or PR is not `Complete` on the roadmap.

The accepted product focus is the on-device Hebrew voice agent (ASR → Gemma/tool routing → local tools → TTS). Photo and nutrition UIs are deferred; preserve existing merged work and personal data. Keep microphone, notification access and side effects permission-scoped.

## Mandatory GitHub build-first gate

**The user may not be at a computer. Do not tell them to build a new version in Android Studio/PowerShell just to discover compile errors.**

Whenever a change produces a **new Android version, APK, companion or tool implementation**:

1. Push code to a Draft PR from the correct latest base branch; ensure its required stacked voice dependencies are explicit. Do not replace `main` with an experimental branch by accident.
2. Make sure GitHub Actions actually runs the relevant CI for the **current head SHA**. For `poc/tensor-llm`, the normal gate is `testDebugUnitTest` plus `assembleDebug` (or `compileDebugJavaWithJavac` where appropriate), including dependent companion tests/compilation when an IPC contract changes. For other Android modules, select the corresponding Gradle variant/tasks. Include tests for new logic and build without private model weights.
3. Read GitHub Actions run/job conclusions for that exact commit, not the status of an older commit. If `queued`/`in_progress`, say **pending**. If CI fails, inspect errors, correct them in GitHub and rerun before asking the user to run commands locally. If a suitable workflow does not exist, add/adjust one or clearly disclose that it could not run.
4. Report PR, commit SHA, what CI compiled/tested, precise `passed`/`failed`/`pending`/`not-run`, and GitHub workflow/artifact URLs. Don't claim a build passed until it actually passed.
5. **Only once the relevant GitHub gate is green**, provide optional local install/build instructions. A GitHub Actions APK is **not automatically safe** for `adb install -r`: compare signing certificates with the installed app. Never advise uninstall/reinstallation/data reset or moving model weights merely to work around signing incompatibility.
6. On-device voice accuracy, Android Notification Listener permission flow, NPU/thermal, multi-turn stability, audio playback and lock-screen privacy still require separate **physical Pixel acceptance**. CI success is not device success.

For the canonical and fuller cross-repository workflow, see [Mosaic development workflow](https://github.com/adarcook/mosaic-docs/blob/docs/require-github-ci-before-device-build/docs/DEVELOPMENT_WORKFLOW.md) (Draft until merged into docs `main`).

No credentials, private messages/transcripts, signing keys, SDK secrets or model weights go into Git.

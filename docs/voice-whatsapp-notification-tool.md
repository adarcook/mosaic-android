# VOICE-09 — First local Android tool: WhatsApp notification read-aloud

**Status:** Draft implementation stacked on the unmerged Voice 0.11 candidate (Android PR #43). No local Pixel validation or CI result is asserted by this document.

## Goal and boundaries

The user explicitly says, for example, **"מוזאיק, תקריא לי את ההודעה האחרונה בוואטסאפ"** while the Mosaic voice screen is in the foreground. The existing Hebrew ASR returns a transcript. A conservative, deterministic `WhatsAppReadIntent` matcher invokes **read-only** `notifications.read_latest_whatsapp` and sends the bounded local result to the existing BlueTTS worker.

**Current first slice does not yet use Gemma to select tools.** General voice queries continue through Gemma. This narrow rule demonstrates the Android capability and lets us validate accuracy, permissions and TTS without adding uncontrolled LLM access to notifications. Replace it with a typed, validated model tool-selection interface in the next slice after the native tool passes on-device acceptance.

What it can read:
- only **currently active** `com.whatsapp` notifications supplied by Android's `NotificationListenerService.getActiveNotifications()`;
- per-message notification extras (including `Notification.EXTRA_MESSAGES`) when present;
- a bounded title/body in memory, requested explicitly by the user.

What it cannot read:
- WhatsApp's internal conversation database, cleared/dismissed notifications, arbitrary chat history, or messages hidden by app/OS notification privacy;
- WhatsApp Business (`com.whatsapp.w4b`) in this initial personal-WhatsApp-only slice;
- send, reply, delete, open an arbitrary attachment or invoke any network request.

The tool fails closed when permission is denied, the listener is disconnected, the device is locked, or notification text is inaccessible. No hidden retry or remote fallback. Tool logs include its ID and **status only**, never message text or sender. The Voice UI shows the spoken result only during that screen session; the TTS completion journal omits `normalized_text` for this path. Existing non-tool voice logs still contain personal transcripts and model answers, as previously documented.

## Implementation files

- `WhatsAppNotificationTool` — pure selection and bounded result contract (success/error, timestamp, title and text).
- `WhatsAppNotificationListener` — system-bound listener; no persistent message collection.
- `WhatsAppReadIntent` — limited explicit Hebrew/WhatsApp command matcher.
- `VoiceActivity` — grant-access settings entry, safe diagnostic availability button, ASR → tool → TTS path.
- `AndroidManifest.xml` — listener binding permission held by Android system.
- Two JVM unit test classes — filter, ordering, group summary, hidden newest message, bounding, permissions and routing.

The APK increments versionCode 9 → 10 to support updating without uninstall/reinstall.

## Windows / Pixel gate

Start from the existing Android repo with the established signing key; do **not** uninstall the three existing voice companion APKs or erase private installed model directories.

```powershell
git fetch origin
git switch --track origin/feature/voice-whatsapp-notification-tool
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat -p poc/tensor-llm testDebugUnitTest assembleDebug
python scripts/verify_tensor_llm_apk.py poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r poc/tensor-llm/build/outputs/apk/debug/MosaicTensorLlm-debug.apk
```

Confirm the installed package and new APK have the *same signer* before replacing; if they differ, **stop** rather than uninstalling and losing model data. Update *only* the voice controller APK for this slice. The existing Whisper and Zipformer companions are unchanged.

### Foreground acceptance

1. Open **מוזאיק — שיחה**; tap **אישור גישה להתראות WhatsApp**; enable notification access for this app in Android settings. Confirm it is displayed as approved on return. You can revoke it later in the same settings.
2. Receive a harmless test WhatsApp message and leave its notification active. Tap **בדיקת Tool** — it should report `OK` without printing message content. Check permission revoked (`PERMISSION_DENIED`), not connected (`LISTENER_UNAVAILABLE`), no notification (`NO_MESSAGE`) and content-hidden behavior.
3. Prepare engines if necessary. Speak the explicit Hebrew request, stop speech and listen to the returned notification text or truthful failure. Make a second turn; check no delayed crash or memory distress.
4. Test device lock and revoked permission, a grouped notification, and normal no-message cases. Content must not be read aloud from a locked device. Do not export/share private journals or message bodies.
5. Inspect the local JSONL for `tool_invocation` with tool ID, read-only flag and status; no WhatsApp message content should be copied into that event or TTS `normalized_text`.

### Incomplete before merge

- CI `testDebugUnitTest` / `assembleDebug` on PR head needs observation.
- Actual physical Pixel notification permission and read-aloud tests are pending.
- Gemma-driven **typed tool planning** is a separate next slice (not faked by this fixed command matcher).
- Broader sender/contact disambiguation, notification group semantics and long/private payload behavior need real-world testing.
- This candidate remains **Draft** and must not be merged until explicitly requested.

# Mosaic Pixel Test Bridge (Windows 11)

An opt-in local worker that polls one PUBLIC GitHub issue over outbound HTTPS, performs a narrow set of Android Debug Bridge (ADB) operations, and writes status-only results to that issue. No Internet-facing desktop API and no general shell execution.

Queue: https://github.com/adarcook/mosaic-android/issues/46

This first bridge can check whether the Pixel is connected, inspect whether the Mosaic voice app is installed, launch it, run a basic process smoke check and optionally install a CI-built Mosaic Voice APK. It does NOT record microphones, read WhatsApp content, capture arbitrary logcat, execute arbitrary commands, test actual ASR/LLM/TTS, click Android permission dialogs or control the screen.

## Setup on Windows 11 (when home)

Requirements: Python 3.10+ (3.12 recommended), Android Studio with SDK platform-tools and build-tools, an authorized Pixel connected by USB or Wireless debugging, and GitHub credentials. The desktop must remain awake while handling remote commands.

1. Open PowerShell from your existing mosaic-android repository. Make a separate checkout without changing your current development branch:

        git fetch origin
        git worktree add --detach ..\mosaic-pixel-bridge origin/feature/pixel-test-bridge
        cd ..\mosaic-pixel-bridge\tools\pixel-test-bridge
        Copy-Item config.example.json config.json

2. Open config.json in Notepad. The example already contains queue issue number 46. Replace the adb, aapt and apksigner values with the installed SDK tools. For example, these JSON strings use forward slashes and need YOUR actual Windows username and build-tools version:

        adb: C:/Users/YOU/AppData/Local/Android/Sdk/platform-tools/adb.exe
        aapt: C:/Users/YOU/AppData/Local/Android/Sdk/build-tools/35.0.0/aapt.exe
        apksigner: C:/Users/YOU/AppData/Local/Android/Sdk/build-tools/35.0.0/apksigner.bat

   Edit the JSON values rather than pasting these illustrative lines into the JSON. Leave allow_install set to false initially. If there are multiple devices, set serial to the Pixel serial shown by adb devices.

3. On Pixel open Developer options > Wireless debugging > Pair device with pairing code. Connect the Pixel and Windows to accessible Wi-Fi for initial pairing. In PowerShell, use the PAIRING port and code shown by Android:

        & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pair PHONE_WIFI_IP:PAIRING_PORT

   Then use the DIFFERENT debugging IP:port from the main Wireless debugging screen:

        py -3 bridge.py connect --endpoint PHONE_WIFI_IP:DEBUG_PORT
        py -3 bridge.py check

   An already paired USB/ADB device can skip pairing. Android may change debug ports or disable Wireless debugging. Tailscale can help phone-to-PC routing on some networks, but remote ADB over the phone's Tailscale IP is NOT guaranteed. Verify local Wi-Fi first, and test Tailscale separately; a phone on mobile data may not expose the ADB listener.

4. Create a fine-grained GitHub personal access token, restricted to ONLY adarcook/mosaic-android with Issues Read and write plus Actions Read-only; Metadata read is implicit. Keep it only on your Windows PC. NEVER paste it into ChatGPT, issue comments, source files or logs. In the current PowerShell session, enter it without screen echo:

        $secret = Read-Host "Fine-grained GitHub token" -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        try {
          $env:MOSAIC_GITHUB_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        } finally {
          [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }

   This first prototype uses a temporary process environment variable; do not leave an unattended admin session running.

5. Initialize the queue, then run the worker:

        py -3 bridge.py init
        py -3 bridge.py run

   Init SKIPS previously posted comments. Keep the PowerShell window running for remote actions. Stop with Ctrl+C. A local health endpoint is available only at http://127.0.0.1:8765/health. It is not reachable directly from ChatGPT or through the tailnet.

6. Once the worker is running, post a NEW comment to issue #46 using exactly one of these formats (one action per comment):

        /mosaic-test {"action":"status"}
        /mosaic-test {"action":"device"}
        /mosaic-test {"action":"inspect"}
        /mosaic-test {"action":"launch"}
        /mosaic-test {"action":"smoke"}

   GitHub replies contain only status fields. A successful smoke check means only that the fixed Mosaic Voice activity launched and its process was briefly present. It is NOT acceptance testing of speech quality, Android notification permission or WhatsApp.

## Optional GitHub CI APK installation

The local config has allow_install set to false. To enable the guarded install command temporarily, change it to true and restart the worker. Then post an exact 40-character commit SHA that has a SUCCESSFUL trusted pull_request GitHub Action named Tensor local LLM gate:

        /mosaic-test {"action":"install_ci_apk","sha":"REPLACE_WITH_EXACT_40_HEX_SHA"}

Requirements enforced by code:
- Only repository adarcook/mosaic-android, an owner-repo feature/ or experiment/ branch, a successful build of that EXACT SHA, and the pinned mosaic-tensor-local-llm-apk artifact.
- Exactly one appropriately sized APK. aapt must report package ID life.mosaic.tensorllm.
- The APK must be correctly signed. If the voice package is already installed, compare new and existing base APK signer SHA-256. On mismatch STOP. Never uninstall, clear app data, or remove models as a workaround.
- GitHub-hosted debug signing may NOT match the APK you built with Android Studio: remote installation will then correctly refuse to proceed. A safe matching-signature strategy would be a later separate task.
- Installing an approved source branch is security sensitive. Restrict write access to the repository and token, and disable remote install when not actively needed.

## Security

- PUBLIC GitHub issue: no secrets, personal messages, transcripts, logcat, device serials, image or audio content should be posted. The worker processes only allowlisted JSON actions authored by GitHub login adarcook; it ignores all other comments.
- No arbitrary shell commands, arbitrary package names, commands selected by an LLM, custom URLs, or remote filesystem paths.
- Operations are checkpointed BEFORE execution. Crash/restart does not replay a prior install. If a result post fails, submit a new command rather than editing an old one.
- Local API listens only on loopback. No port forwarding, VPS or home Core exposure.
- The GitHub token is restricted to one repository and must be kept local. No install service or Windows auto-start is created.
- The desktop is only reachable while it is awake and the worker is actively running.

## Validation

The GitHub Actions workflow at .github/workflows/pixel-test-bridge.yml runs Python syntax compilation and unit tests for authorization, command parsing, durable checkpointing, ADB device selection and artifact guardrails. GitHub CI is NOT proof of real Pixel connectivity or device behavior. This work is a standalone Draft PR based on main, not on the unmerged voice experiment stack.

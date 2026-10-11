"""Build experimental APKs on macOS/Linux/WSL without downloading model weights."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"whisper": "poc/tensor-whisper", "llm": "poc/tensor-llm"}


def read_sdk(properties: Path) -> str | None:
    if not properties.is_file():
        return None
    for line in properties.read_text().splitlines():
        match = re.match(r"\s*sdk\.dir\s*[=:]\s*(.*)", line)
        if match:
            return re.sub(r"\\u([0-9a-fA-F]{4})|\\(.)",
                          lambda m: chr(int(m[1], 16)) if m[1] else m[2], match[1])
    return None


def find_sdk(root: Path, home: Path, env: dict, explicit: str | None) -> Path:
    # Explicit settings must not silently fall back to another machine's SDK.
    configured = explicit or env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    candidates = [configured] if configured else [
        read_sdk(root / module / "local.properties") for module in MODULES.values()
    ] + [read_sdk(root / "local.properties"), str(home / "Library/Android/sdk"),
         str(home / "Android/Sdk")]
    for value in candidates:
        if value:
            path = Path(value).expanduser().resolve()
            if (path / "platforms").is_dir() and (path / "platform-tools").is_dir():
                return path
    raise ValueError("Android SDK not found. Install it with Android Studio, then pass --sdk PATH "
                     "(macOS usually ~/Library/Android/sdk; WSL usually ~/Android/Sdk).")


def configure_sdk(root: Path, sdk: Path, modules: list[str]) -> None:
    # Machine-local files only. Preserve unrelated existing Gradle properties.
    escaped = str(sdk).replace("\\", "\\\\").replace(":", "\\:").replace(" ", "\\ ")
    escaped = escaped.encode("ascii", "backslashreplace").decode("ascii")
    # Java properties expect Unicode escapes, not Python's short \x escapes.
    escaped = re.sub(r"\\x([0-9a-fA-F]{2})", r"\\u00\1", escaped)
    for module in modules:
        properties = root / MODULES[module] / "local.properties"
        lines = properties.read_text().splitlines() if properties.exists() else []
        lines = [line for line in lines if not re.match(r"\s*sdk\.dir\s*[=:]", line)]
        properties.write_text("\n".join(lines + [f"sdk.dir={escaped}"]) + "\n")


def java_environment(env: dict, minimum: int) -> dict:
    result = dict(env)
    if sys.platform == "darwin" and not result.get("JAVA_HOME"):
        found = subprocess.run(["/usr/libexec/java_home", "-v", str(minimum)],
                               capture_output=True, text=True)
        if found.returncode == 0:
            result["JAVA_HOME"] = found.stdout.strip()
    java = str(Path(result["JAVA_HOME"]) / "bin/java") if result.get("JAVA_HOME") else "java"
    try:
        version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"Cannot run Java. Set JAVA_HOME to a JDK {minimum} installation.") from error
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match[1]) < minimum:
        raise ValueError(f"JDK {minimum} or newer required; set JAVA_HOME before building.")
    return result


def build(root: Path, modules: list[str], env: dict) -> None:
    for module in modules:
        script = "prepare_tensor_whisper_runtime.py" if module == "whisper" else "prepare_tensor_llm.py"
        # No --model-dir: these prepare public runtime libraries only.
        subprocess.run([sys.executable, str(root / "scripts" / script)], cwd=root, env=env, check=True)
        tasks = ["testDebugUnitTest", "assembleDebug"] if module == "llm" else ["assembleDebug"]
        subprocess.run(["bash", str(root / "gradlew"), "-p", MODULES[module], *tasks],
                       cwd=root, env=env, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", help="Host Android SDK path")
    parser.add_argument("--only", choices=MODULES, help="Build one APK instead of both")
    parser.add_argument("--check", action="store_true", help="Check SDK/Java; do not write or download/build")
    args = parser.parse_args()
    try:
        modules = [args.only] if args.only else list(MODULES)
        sdk = find_sdk(ROOT, Path.home(), os.environ, args.sdk)
        env = java_environment(os.environ, 21 if "llm" in modules else 17)
        env.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
        print(f"SDK: {sdk}\nAPKs: {', '.join(modules)}; model weights are never downloaded by this command.", flush=True)
        if not args.check:
            configure_sdk(ROOT, sdk, modules)
            build(ROOT, modules, env)
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Build setup failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

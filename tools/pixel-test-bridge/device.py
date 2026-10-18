"""Allowlisted ADB operations only. No arbitrary shell commands or device data uploads."""
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit
from urllib.request import Request, urlopen, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
from zipfile import ZipFile, BadZipFile
from github_queue import BridgeError

PACKAGE = "life.mosaic.tensorllm"
ACTIVITY = PACKAGE + "/.VoiceActivity"
MAX_ZIP = 120 * 1024 * 1024
MAX_APK = 100 * 1024 * 1024


def process(argv, timeout=35, success=(0,)):
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise BridgeError("ADB/Android SDK command unavailable or timed out.") from None
    if proc.returncode not in success:
        raise BridgeError("Local Android command failed (exit %d)." % proc.returncode)
    return proc.stdout


def selected_serial(adb, configured):
    output = process([adb, "devices"], timeout=15)
    connected = [columns[0] for row in output.splitlines()[1:]
                 if len(columns := row.split()) >= 2 and columns[1] == "device"]
    if configured:
        if configured not in connected:
            raise BridgeError("Configured Pixel is not connected and authorized.")
        return configured
    if len(connected) != 1:
        raise BridgeError("Connect exactly one authorized Pixel, or set serial in config.json.")
    return connected[0]


def signer(apksigner, path):
    output = process([apksigner, "verify", "--print-certs", str(path)], timeout=45)
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if not match:
        raise BridgeError("Cannot verify APK certificate.")
    return match.group(1).lower()


def extract_single_apk(zip_path, target):
    try:
        with ZipFile(zip_path) as archive:
            entries = [item for item in archive.infolist()
                       if item.filename.endswith(".apk") and ".." not in Path(item.filename).parts
                       and not item.filename.startswith("/")]
            if len(entries) != 1 or not (0 < entries[0].file_size <= MAX_APK):
                raise BridgeError("CI artifact must contain exactly one bounded APK.")
            with archive.open(entries[0]) as source, target.open("wb") as dest:
                total = 0
                while chunk := source.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_APK:
                        raise BridgeError("APK exceeds size limit.")
                    dest.write(chunk)
    except BadZipFile:
        raise BridgeError("Invalid GitHub artifact ZIP.") from None


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download_verified_artifact(github, sha, target):
    artifact_id = github.successful_artifact(sha)
    # First request uses PAT; redirected short-lived blob request does not.
    source_url = github.root + "/actions/artifacts/%d/zip" % artifact_id
    original = Request(source_url, headers={
        "Authorization": "Bearer " + github.token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "MosaicPixelBridge/0.1"})
    try:
        with build_opener(NoRedirect).open(original, timeout=35):
            raise BridgeError("Expected GitHub artifact redirect.")
    except HTTPError as error:
        if error.code not in (301, 302, 303, 307, 308):
            raise BridgeError("GitHub artifact download failed.") from None
        redirect = error.headers.get("Location", "")
    parsed = urlsplit(redirect)
    hostname = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or not (hostname.endswith(".blob.core.windows.net")
                                        or hostname.endswith(".githubusercontent.com")):
        raise BridgeError("Untrusted GitHub artifact redirect.")
    try:
        with urlopen(Request(redirect, headers={"User-Agent": "MosaicPixelBridge/0.1"}), timeout=90) as reader, target.open("wb") as output:
            size = 0
            while chunk := reader.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_ZIP:
                    raise BridgeError("Artifact is too large.")
                output.write(chunk)
    except (URLError, HTTPError):
        raise BridgeError("Artifact blob could not be downloaded.") from None


class Pixel:
    def __init__(self, config):
        self.adb = config.get("adb") or "adb"
        self.apksigner = config.get("apksigner") or "apksigner"
        self.aapt = config.get("aapt") or "aapt"
        self.serial = config.get("serial") or None
        self.allow_install = config.get("allow_install") is True

    def adb_command(self, *args, timeout=35, success=(0,)):
        serial = selected_serial(self.adb, self.serial)
        return process([self.adb, "-s", serial, *args], timeout=timeout, success=success)

    def installed(self):
        return self.adb_command("shell", "pm", "path", PACKAGE)

    def execute(self, command, github):
        action = command["action"]
        if action == "status":
            return {"bridge": "online", "install_enabled": self.allow_install}
        if action == "device":
            selected_serial(self.adb, self.serial)
            return {"device": "connected"}
        if action == "inspect":
            return {"voice_app_installed": "package:" in self.installed()}
        if action in ("launch", "smoke"):
            if "package:" not in self.installed():
                raise BridgeError("Voice app is not installed.")
            output = self.adb_command("shell", "am", "start", "-n", ACTIVITY)
            if "Error" in output or "Exception" in output:
                raise BridgeError("Android rejected the activity start.")
            if action == "launch":
                return {"activity_start_sent": True}
            # No content-rich logcat, notifications, contacts, images, or audio are uploaded.
            import time
            time.sleep(2)
            pid = self.adb_command("shell", "pidof", PACKAGE, success=(0, 1))
            return {"activity_start_sent": True, "voice_process_detected": bool(pid.strip()),
                    "note": "Basic launch smoke only; not voice or notification acceptance."}
        if action == "install_ci_apk":
            if not self.allow_install:
                raise BridgeError("Remote installation disabled locally (allow_install=false).")
            sha = command["sha"]
            selected_serial(self.adb, self.serial)
            with tempfile.TemporaryDirectory(prefix="mosaic-test-") as tmp:
                folder = Path(tmp)
                archive, apk = folder / "artifact.zip", folder / "voice.apk"
                download_verified_artifact(github, sha, archive)
                extract_single_apk(archive, apk)
                manifest = process([self.aapt, "dump", "badging", str(apk)])
                if not re.search(r"^package: name='life\.mosaic\.tensorllm'", manifest, re.MULTILINE):
                    raise BridgeError("APK package does not match the voice-app allowlist.")
                new_signer = signer(self.apksigner, apk)
                installed = self.installed()
                if "package:" in installed:
                    base = next((line.removeprefix("package:") for line in installed.splitlines()
                                 if line.startswith("package:") and line.endswith("/base.apk")), None)
                    if not base:
                        raise BridgeError("Could not identify installed base APK.")
                    existing = folder / "installed.apk"
                    self.adb_command("pull", base, str(existing), timeout=150)
                    if signer(self.apksigner, existing) != new_signer:
                        raise BridgeError("APK signing mismatch: no installation, no uninstall.")
                result = self.adb_command("install", "-r", str(apk), timeout=180)
                if "Success" not in result:
                    raise BridgeError("Android did not confirm successful installation.")
                return {"installed": True, "commit": sha[:12], "package": PACKAGE}
        raise BridgeError("Operation not allowed.")

"""Allowlisted ADB operations only. No arbitrary shell commands or device data uploads."""
import hashlib
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


def selected_serial(adb, configured, identity=None):
    output = process([adb, "devices"], timeout=15)
    connected = [columns[0] for row in output.splitlines()[1:]
                 if len(columns := row.split()) >= 2 and columns[1] == "device"]
    if identity:
        matches = [serial for serial in connected
                   if process([adb, "-s", serial, "shell", "getprop", "ro.serialno"]).strip() == identity]
        if not matches:
            raise BridgeError("Enrolled Pixel is not connected and authorized.")
        return next((serial for serial in matches if ":" not in serial and "." not in serial), matches[0])
    if configured:
        if configured not in connected:
            raise BridgeError("Configured Pixel is not connected and authorized.")
        return configured
    if len(connected) != 1:
        raise BridgeError("Connect exactly one authorized Pixel, or set serial in config.json.")
    return connected[0]


def sdk_command(tool, *args):
    return (list(tool) if isinstance(tool, list) else [tool]) + list(args)


def signer(apksigner, path):
    output = process(sdk_command(apksigner, "verify", "--print-certs", str(path)), timeout=45)
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if len(matches) != 1:
        raise BridgeError("Cannot verify APK certificate.")
    return matches[0].lower()


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_payload(path):
    """Compare every non-signature ZIP entry, including non-signature META-INF files."""
    result, total = {}, 0
    try:
        with ZipFile(path) as archive:
            for item in archive.infolist():
                name = item.filename
                if name in result:
                    raise BridgeError("Duplicate APK payload entry.")
                if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I):
                    continue
                total += item.file_size
                if total > 500 * 1024 * 1024:
                    raise BridgeError("APK payload exceeds size limit.")
                digest = hashlib.sha256()
                with archive.open(item) as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
                result[name] = (item.file_size, digest.hexdigest())
    except BadZipFile:
        raise BridgeError("Invalid APK ZIP.") from None
    return result


def locally_signed_copy(apksigner, source, target, keystore, trusted_signer):
    before = apk_payload(source)
    if not Path(keystore).is_file():
        raise BridgeError("Enrolled local debug signing key is unavailable.")
    # Only the standard existing Android development key is supported. Never upload it.
    process(sdk_command(apksigner, "sign", "--ks", str(keystore),
                        "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                        "--key-pass", "pass:android", "--out", str(target), str(source)), timeout=90)
    if signer(apksigner, target) != trusted_signer:
        raise BridgeError("Local signing key does not match enrolled certificate.")
    if apk_payload(target) != before:
        raise BridgeError("APK payload changed during local signing; installation refused.")


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
    artifact = github.successful_artifact(sha)
    artifact_id = artifact["id"]
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
    if sha256(target) != artifact["digest"].removeprefix("sha256:"):
        raise BridgeError("GitHub artifact ZIP SHA-256 mismatch.")
    return artifact


class Pixel:
    def __init__(self, config):
        self.adb = config.get("adb") or "adb"
        self.apksigner = config.get("apksigner") or "apksigner"
        self.aapt = config.get("aapt") or "aapt"
        self.serial = config.get("serial") or None
        self.identity = config.get("device_identity") or None
        self.allow_install = config.get("allow_install") is True
        self.allow_local_signing = config.get("allow_local_debug_signing") is True
        self.keystore = config.get("local_debug_keystore")
        self.trusted_signer = config.get("trusted_signer_sha256")

    def adb_command(self, *args, timeout=35, success=(0,)):
        serial = selected_serial(self.adb, self.serial, self.identity)
        return process([self.adb, "-s", serial, *args], timeout=timeout, success=success)

    def installed(self):
        return self.adb_command("shell", "pm", "path", PACKAGE)

    def execute(self, command, github):
        action = command["action"]
        if action == "status":
            return {"bridge": "online", "install_enabled": self.allow_install}
        if action == "device":
            selected_serial(self.adb, self.serial, self.identity)
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
            serial = selected_serial(self.adb, self.serial, self.identity)
            # Freeze one enrolled transport for the complete update transaction.
            def adb(*args, timeout=35):
                return process([self.adb, "-s", serial, *args], timeout=timeout)
            if adb("shell", "getprop", "ro.product.model").strip() != "Pixel 10 Pro":
                raise BridgeError("Installation requires the enrolled Pixel 10 Pro.")
            with tempfile.TemporaryDirectory(prefix="mosaic-test-") as tmp:
                folder = Path(tmp)
                archive, apk = folder / "artifact.zip", folder / "voice.apk"
                artifact = download_verified_artifact(github, sha, archive)
                extract_single_apk(archive, apk)
                manifest = process([self.aapt, "dump", "badging", str(apk)])
                if not re.search(r"^package: name='life\.mosaic\.tensorllm'", manifest, re.MULTILINE):
                    raise BridgeError("APK package does not match the voice-app allowlist.")
                new_signer = signer(self.apksigner, apk)
                original_hash = sha256(apk)
                installed = adb("shell", "pm", "path", PACKAGE)
                if "package:" in installed:
                    base = next((line.removeprefix("package:") for line in installed.splitlines()
                                 if line.startswith("package:") and line.endswith("/base.apk")), None)
                    if not base:
                        raise BridgeError("Could not identify installed base APK.")
                    existing = folder / "installed.apk"
                    adb("pull", base, str(existing), timeout=150)
                    old_signer = signer(self.apksigner, existing)
                    if self.trusted_signer and old_signer != self.trusted_signer:
                        raise BridgeError("Installed signer does not match enrolled certificate.")
                    if old_signer != new_signer:
                        if not (self.allow_local_signing and self.identity and self.keystore
                                and self.trusted_signer == old_signer):
                            raise BridgeError("APK signing mismatch: no installation, no uninstall.")
                        signed = folder / "voice-local.apk"
                        locally_signed_copy(self.apksigner, apk, signed, self.keystore, old_signer)
                        apk = signed
                    if signer(self.apksigner, apk) != old_signer:
                        raise BridgeError("Final signer mismatch; no installation.")
                    installed_manifest = process([self.aapt, "dump", "badging", str(existing)])
                    candidate_code = re.search(r"versionCode='(\d+)'", manifest)
                    installed_code = re.search(r"versionCode='(\d+)'", installed_manifest)
                    if not candidate_code or not installed_code or int(candidate_code[1]) < int(installed_code[1]):
                        raise BridgeError("Missing version identity or downgrade refused.")
                elif self.allow_local_signing or self.trusted_signer:
                    raise BridgeError("Enrolled installation is missing; refusing a new installation.")
                candidate_hash = sha256(apk)
                result = adb("install", "-r", str(apk), timeout=180)
                if "Success" not in result:
                    raise BridgeError("Android did not confirm successful installation.")
                current = adb("shell", "pm", "path", PACKAGE)
                current_base = next((line.removeprefix("package:").strip() for line in current.splitlines()
                                     if line.startswith("package:") and line.strip().endswith("/base.apk")), None)
                if not current_base:
                    raise BridgeError("Update reported success but installed APK could not be verified.")
                pulled = folder / "installed-after.apk"
                adb("pull", current_base, str(pulled), timeout=150)
                if sha256(pulled) != candidate_hash or signer(self.apksigner, pulled) != signer(self.apksigner, apk):
                    raise BridgeError("Update reported success but installed hash/signer verification failed.")
                return {"installed": True, "commit": sha[:12], "package": PACKAGE,
                        "artifact_id": artifact["id"], "ci_apk_sha256": original_hash,
                        "installed_apk_sha256": candidate_hash,
                        "local_signing": original_hash != candidate_hash, "verified_after_install": True}
        raise BridgeError("Operation not allowed.")

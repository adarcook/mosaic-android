#!/usr/bin/env python3
"""Pin the Android sherpa-onnx AAR; no native runtime binary is committed to Git."""
import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

NAME = "sherpa-onnx-1.13.8.aar"
SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/" + NAME
MAX_BYTES = 70 * 1024 * 1024
DEFAULT = Path(__file__).resolve().parents[1] / "poc" / "zipformer-gate" / "libs" / NAME


def verify(path: Path):
    if not path.is_file() or not 1 < path.stat().st_size <= MAX_BYTES:
        raise ValueError("AAR missing or implausibly sized: " + str(path))
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != SHA256:
        raise ValueError("AAR checksum mismatch; refuse unverified native code: " + str(path))
    print("Verified sherpa-onnx AAR SHA-256:", digest.hexdigest())


def prepare(destination: Path, offline: bool):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        verify(destination)
        return
    if offline:
        raise ValueError("Pinned AAR not present; cannot offline verify.")
    descriptor, temp_name = tempfile.mkstemp(prefix=".sherpa-", suffix=".tmp", dir=destination.parent)
    try:
        size = 0
        with os.fdopen(descriptor, "wb") as out, urllib.request.urlopen(URL, timeout=90) as response:
            while True:
                data = response.read(1024 * 1024)
                if not data:
                    break
                size += len(data)
                if size > MAX_BYTES:
                    raise ValueError("AAR download exceeds size limit")
                out.write(data)
        verify(Path(temp_name))
        os.replace(temp_name, destination)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--dest", type=Path, default=DEFAULT)
    args = parser.parse_args()
    try:
        prepare(args.dest, args.verify_only)
        print("PASS:", args.dest)
    except (OSError, ValueError) as e:
        parser.exit(1, "FAIL: " + str(e) + "\n")


if __name__ == "__main__":
    main()

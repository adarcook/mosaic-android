"""Download the pinned public LiteRT Whisper Tiny graph used for the Tensor G5 gate."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import urllib.request

URL = (
    "https://huggingface.co/litert-community/whisper-tiny/resolve/main/"
    "whisper_tiny_30s_f32.tflite"
)
SHA256 = "0c8f0e2a1855909a0c027b4ac3c586fdd299e2b47bf1a4fdab51191bca1e0e89"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Private model directory outside Git")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    target = args.output / "whisper_tiny_30s_f32.tflite"
    if target.exists() and sha256_file(target) == SHA256:
        print(f"Reusing verified {target}")
        return

    partial = target.with_suffix(target.suffix + ".part")
    partial.unlink(missing_ok=True)
    print(f"Downloading {URL}")
    with urllib.request.urlopen(URL, timeout=120) as source, partial.open("wb") as sink:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            sink.write(chunk)

    actual = sha256_file(partial)
    if actual != SHA256:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"Whisper model hash mismatch: {actual}")

    partial.replace(target)
    print(f"Verified {target} ({target.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()

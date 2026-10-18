"""Prepare the pinned public LiteRT Whisper Tiny graph used for the Tensor G5 gate.

Works in two modes:
1. online download from the pinned public URL;
2. offline import from a file the user downloaded elsewhere.

In both modes the exact SHA-256 is mandatory.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request

URL = (
    "https://huggingface.co/litert-community/whisper-tiny/resolve/main/"
    "whisper_tiny_30s_f32.tflite"
)
SHA256 = "0c8f0e2a1855909a0c027b4ac3c586fdd299e2b47bf1a4fdab51191bca1e0e89"
FILENAME = "whisper_tiny_30s_f32.tflite"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path) -> None:
    actual = sha256_file(path)
    if actual != SHA256:
        raise RuntimeError(
            f"Whisper model hash mismatch for {path}:\n"
            f"expected {SHA256}\n"
            f"actual   {actual}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Private model directory outside Git")
    parser.add_argument(
        "--local-file",
        type=Path,
        help="Use an already downloaded model file; no Hugging Face/network access",
    )
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    target = args.output / FILENAME

    if target.exists():
        try:
            verify(target)
            print(f"Reusing verified {target} ({target.stat().st_size:,} bytes)")
            return
        except RuntimeError:
            if args.local_file is None:
                raise
            print(f"Existing target is invalid; replacing it from {args.local_file}")

    if args.local_file is not None:
        source = args.local_file.expanduser().resolve()
        if not source.is_file():
            raise SystemExit(f"Local model file not found: {source}")
        print(f"Verifying offline model: {source}")
        verify(source)
        partial = target.with_suffix(target.suffix + ".part")
        partial.unlink(missing_ok=True)
        shutil.copyfile(source, partial)
        verify(partial)
        partial.replace(target)
        print(f"Imported verified {target} ({target.stat().st_size:,} bytes)")
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

    verify(partial)
    partial.replace(target)
    print(f"Verified {target} ({target.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()

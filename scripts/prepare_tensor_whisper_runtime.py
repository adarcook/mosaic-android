"""Fetch the pinned Google Tensor Android dispatch library for the Whisper TPU gate."""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import urllib.request
from zipfile import ZipFile

URL = "https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.6/litert_npu_runtime_libraries.zip"
SHA256 = "98aabbdce8607f6dc6ab7cb92217326eef24a8c97b973b69e62bd0ce14b7495b"
MEMBER = "google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--module",
        type=Path,
        default=Path("poc/tensor-whisper"),
        help="Android module root",
    )
    args = parser.parse_args()

    data = urllib.request.urlopen(URL, timeout=90).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != SHA256:
        raise RuntimeError(f"Runtime archive hash mismatch: {actual}")

    with ZipFile(io.BytesIO(data)) as archive:
        library = archive.read(MEMBER)

    output = (
        args.module
        / "src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(library)
    print(f"Prepared pinned Google Tensor dispatch library: {output}")


if __name__ == "__main__":
    main()

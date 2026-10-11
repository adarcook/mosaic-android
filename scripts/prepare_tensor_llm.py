"""Prepare pinned public G5 dispatch; optionally download the exact Gemma 4 G5 pack."""
from __future__ import annotations

import argparse
import hashlib
import io
import os
from pathlib import Path
import urllib.request
from zipfile import ZipFile

RUNTIME_URL = "https://github.com/google-ai-edge/gallery/releases/download/1.0.20/ai-edge-gallery-tensor-g5.apk"
RUNTIME_SHA256 = "c84e08213c31374e001c3079c5f5fb93bd1b9b7d329d4aa2d04a30fcf13dc59b"
DISPATCH_SHA256 = "1e3ea6dc7ae2d48b95f7147791b18c1de3095165b42470a547909f6e0d72c5ec"
MEMBER = "lib/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
MODEL_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
MODEL_REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
MODEL_FILE = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
MODEL_SHA256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"
MODEL_BYTES = 3113545589


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", type=Path, default=Path("poc/tensor-llm"))
    parser.add_argument("--model-dir", type=Path, help="Optional private destination for the 3.1 GB model")
    args = parser.parse_args()
    destination = args.module / "src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
    if destination.is_file() and file_hash(destination) == DISPATCH_SHA256:
        print(f"Tensor dispatch already verified (Gallery 1.0.20 G5): {destination}")
    else:
        # Extract only dispatch, never Gallery's JNI, app code, signing key or models.
        data = urllib.request.urlopen(RUNTIME_URL, timeout=90).read()
        if hashlib.sha256(data).hexdigest() != RUNTIME_SHA256:
            raise RuntimeError("Gallery runtime source archive SHA-256 mismatch")
        with ZipFile(io.BytesIO(data)) as archive:
            library = archive.read(MEMBER)
        if hashlib.sha256(library).hexdigest() != DISPATCH_SHA256:
            raise RuntimeError("Google Tensor dispatch SHA-256 mismatch")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary_library = destination.with_suffix(".so.part")
        temporary_library.write_bytes(library)
        temporary_library.replace(destination)
        print(f"Prepared Tensor dispatch from Gallery 1.0.20 G5: {destination}")
    if args.model_dir is None:
        return
    args.model_dir.mkdir(parents=True, exist_ok=True)
    model = args.model_dir / MODEL_FILE
    if model.is_file() and model.stat().st_size == MODEL_BYTES and file_hash(model) == MODEL_SHA256:
        print(f"Model already verified: {model}")
        return
    request = urllib.request.Request(f"https://huggingface.co/{MODEL_REPO}/resolve/{MODEL_REVISION}/{MODEL_FILE}")
    token = os.environ.get("HF_TOKEN")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    temporary = model.with_suffix(".part")
    total = 0
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=90) as source, temporary.open("wb") as output:
        while block := source.read(4 * 1024 * 1024):
            total += len(block)
            if total > MODEL_BYTES:
                raise RuntimeError("Model download exceeded pinned size")
            output.write(block)
            digest.update(block)
            if total % (128 * 1024 * 1024) == 0:
                print(f"Downloaded {total // (1024 * 1024)} MiB", flush=True)
    if total != MODEL_BYTES or digest.hexdigest() != MODEL_SHA256:
        raise RuntimeError("Model download size/SHA-256 mismatch; partial file not installed")
    temporary.replace(model)
    print(f"Verified Gemma 4 E2B Tensor G5: {model}")


if __name__ == "__main__":
    main()

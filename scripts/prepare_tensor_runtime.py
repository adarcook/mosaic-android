"""Fetch only the public Google Tensor Android dispatch library, with a pinned hash."""
import hashlib
from pathlib import Path
import urllib.request
from zipfile import ZipFile
import io

URL = "https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.6/litert_npu_runtime_libraries.zip"
SHA256 = "98aabbdce8607f6dc6ab7cb92217326eef24a8c97b973b69e62bd0ce14b7495b"
MEMBER = "google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"

def main():
    data = urllib.request.urlopen(URL, timeout=90).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("Runtime archive hash mismatch")
    with ZipFile(io.BytesIO(data)) as archive:
        library = archive.read(MEMBER)
    output = Path(__file__).resolve().parents[1] / "poc/tensor-probe/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(library)
    print("Prepared pinned Google Tensor dispatch library")

if __name__ == "__main__":
    main()

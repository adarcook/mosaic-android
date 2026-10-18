"""Assert that the final APK contains the pinned candidate Tensor dispatch."""
import argparse
import hashlib
from pathlib import Path
from zipfile import ZipFile
from prepare_tensor_llm import DISPATCH_SHA256, MEMBER


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    with ZipFile(args.apk) as archive:
        digest = hashlib.sha256(archive.read(MEMBER)).hexdigest()
    if digest != DISPATCH_SHA256:
        raise SystemExit("APK contains an unexpected Tensor dispatch: " + digest)
    print("PASS: packaged Tensor dispatch SHA-256 = " + digest)


if __name__ == "__main__":
    main()

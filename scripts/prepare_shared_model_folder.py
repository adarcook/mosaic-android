"""Copy verified model originals to Documents/Mosaic/models on Pixel; never remove originals."""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import shlex
import subprocess

DEST = "/sdcard/Documents/Mosaic/models"
MODELS = [
    ("whisper", "ivrit-whisper-decoder-q5_0.bin", 574041195, "6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572"),
    ("whisper", "ivrit_whisper_encoder_Google_Tensor_G5.tflite", 1327807744, "28541df9741e207c14c3e6b20513fc27a278c5c5835c7fbe01304dbea9057108"),
    ("whisper", "ivrit_whisper_cross_attention_Google_Tensor_G5.tflite", 26846992, "fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3"),
    ("llm", "gemma-4-E2B-it_Google_Tensor_G5.litertlm", 3113545589, "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb binary; WSL may use the Windows adb.exe")
    parser.add_argument("--serial", help="Select one phone if multiple devices are connected")
    parser.add_argument("--only", choices=["whisper", "llm"])
    parser.add_argument("--llm-model", type=Path, help="Already downloaded local Gemma file, if not yet on Pixel")
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def shell(*words, check=True):
        return subprocess.run(adb + ["shell", " ".join(shlex.quote(str(word)) for word in words)],
                              capture_output=True, text=True, check=check)

    def valid(path, sha):
        value = shell("sha256sum", path, check=False)
        return value.returncode == 0 and value.stdout.split()[:1] == [sha]

    try:
        shell("mkdir", "-p", DEST)
        for group, name, size, sha in MODELS:
            if args.only and group != args.only:
                continue
            target = DEST + "/" + name
            print("Verifying shared source:", name, flush=True)
            if valid(target, sha):
                print("Already verified; no copy needed.", flush=True)
                continue
            partial = target + ".transfer-part"
            try:
                if group == "llm" and args.llm_model:
                    host = args.llm_model.expanduser().resolve()
                    if host.stat().st_size != size:
                        raise ValueError("Local Gemma size mismatch")
                    with host.open("rb") as input_file:
                        digest = hashlib.sha256()
                        for block in iter(lambda: input_file.read(1024 * 1024), b""):
                            digest.update(block)
                        actual = digest.hexdigest()
                    if actual != sha:
                        raise ValueError("Local Gemma SHA-256 mismatch")
                    source = str(host)
                    if args.adb.lower().endswith(".exe"):
                        source = subprocess.run(["wslpath", "-w", source], capture_output=True, text=True, check=True).stdout.strip()
                    subprocess.run(adb + ["push", source, partial], check=True)
                else:
                    package = "life.mosaic.tensorwhisper" if group == "whisper" else "life.mosaic.tensorllm"
                    source = f"/sdcard/Android/data/{package}/files/{name}"
                    if not valid(source, sha):
                        raise ValueError(f"Missing or unverified source {name}. For Gemma use --llm-model with the downloaded file.")
                    shell("cp", source, partial)
                if not valid(partial, sha):
                    raise ValueError("Copied model SHA-256 mismatch: " + name)
                shell("mv", partial, target)
                print("Independent source ready:", target, flush=True)
            finally:
                shell("rm", "-f", partial, check=False)
        print("Ready. In each APK choose Documents/Mosaic/models via the model setup button. Original app files were retained.")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("Model transfer failed:", error)
        print("Original app files and previously verified shared models were retained. Retry after fixing the error.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

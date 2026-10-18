"""Reuse an existing BlueTTS pack and a pinned host-cached Renikud model. No build-time weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
REV = "679c56ca449d41873fb8ff7711ddf7563d28198f"
PINS = {
    "renikud.onnx": (1234209530, "c3a2bdc35afbf53f28cdece4672dcca9b530dfa43a0d65b4c5bbc47150b32d0d"),
    "duration_predictor_style.onnx": (1508749, "3d0347cfc09234b78a20d5fa50b5c402d338fb99825f40ada8c70301edbb60ea"),
    "text_encoder.onnx": (27419694, "f971aa25315b93efff1850b39fe293aa93ae85df69fabf5e5022318cc632973c"),
    "vector_estimator.onnx": (132475789, "50557b34acfafa4b34f21dae263faa74d3f3169f114ff927599a1eff110d0b6e"),
    "vocoder.onnx": (101411919, "b0709df316ff44cc263c04fbf9d17371b16897071a48b79d3634d5f379fb3067"),
    "vocab.json": (3157, "13fba5e735222fcf30d9c062d898982a1ba86d481f11560fbbdf6e0af79417b3"),
}


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def locate_renikud(explicit=None, download=False):
    if explicit:
        return Path(explicit).expanduser().resolve()
    cache = Path(os.environ.get("HF_HUB_CACHE", Path(os.environ.get("HF_HOME", Path.home()/".cache/huggingface"))/"hub"))
    candidate = cache / "models--notmax123--RenikudPlus" / "snapshots" / REV / "model.onnx"
    if candidate.is_file():
        return candidate
    if download:
        from huggingface_hub import hf_hub_download
        return Path(hf_hub_download("notmax123/RenikudPlus", "model.onnx", revision=REV))
    raise ValueError("Pinned RenikudPlus is not cached. Pass --renikud /path/to/model.onnx, "
                     "or explicitly use --download-g2p (1.23GB). Existing ASR/LLM/BlueTTS weights are reused.")


def install_file(source, target, size, digest):
    if not source.is_file() or source.stat().st_size != size or sha(source) != digest:
        raise ValueError(f"Wrong/missing source identity: {source}")
    if target.is_file() and target.stat().st_size == size and sha(target) == digest:
        return
    if source.resolve() == target.resolve():
        return
    pending = target.with_name(target.name + ".pending")
    try:
        with source.open("rb") as inp, pending.open("wb") as out:
            shutil.copyfileobj(inp, out, 1024*1024)
            out.flush(); os.fsync(out.fileno())
        if pending.stat().st_size != size or sha(pending) != digest:
            raise ValueError("Copy checksum mismatch")
        pending.replace(target)
    finally:
        pending.unlink(missing_ok=True)


def prepare(existing, renikud, output):
    existing = existing.resolve(); output = output.resolve()
    fixture = existing / "reply.json"
    if not fixture.is_file() or fixture.stat().st_size > 4000000:
        raise ValueError("Existing reply.json fixture is missing/too large")
    data = json.loads(fixture.read_text())
    for key in ("style_ttl", "style_dp", "u_text", "u_ref", "mean", "std", "steps", "cfg", "normalizer_scale"):
        if key not in data:
            raise ValueError(f"Existing BlueTTS fixture missing {key}")
    sources = {name: existing/"blue"/name for name in PINS}
    sources.update({"renikud.onnx": renikud, "vocab.json": ROOT/"scripts/voice_metadata/blue-vocab.json", "reply.json": fixture})
    specs = {name: {"bytes": size, "sha256": digest} for name, (size, digest) in PINS.items()}
    specs["reply.json"] = {"bytes": fixture.stat().st_size, "sha256": sha(fixture)}
    # Validate all inputs before committing any output. Originals are never modified.
    for name, source in sources.items():
        spec = specs[name]
        if not source.is_file() or source.stat().st_size != spec["bytes"] or sha(source) != spec["sha256"]:
            raise ValueError(f"Wrong/missing source identity: {source}")
    output.mkdir(parents=True, exist_ok=True)
    for name, source in sources.items():
        install_file(source, output/name, specs[name]["bytes"], specs[name]["sha256"])
    manifest = {"format": 1, "blue_revision": "468da64b4a51795a7594a3637727dbaf876b6df2", "renikud_revision": REV,
                "voice": "libri_male_6209", "files": specs}
    pending = output/"manifest.pending"
    pending.write_text(json.dumps(manifest, indent=2), encoding="utf-8"); pending.replace(output/"manifest.json")
    print(f"Ready: {output}\nSeven verified files, no ASR or LLM model copied. Keep this source independent of APKs.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--existing-pack", type=Path, default=ROOT/"voice-models")
    parser.add_argument("--renikud", help="Existing pinned FP32 model.onnx")
    parser.add_argument("--download-g2p", action="store_true", help="Explicitly download only missing RenikudPlus (1.23GB)")
    parser.add_argument("--output", type=Path, default=Path.home()/"mosaic-private/blue-voice-pack")
    args = parser.parse_args()
    try:
        prepare(args.existing_pack.expanduser(), locate_renikud(args.renikud, args.download_g2p), args.output.expanduser())
    except (ValueError, OSError) as error:
        parser.exit(1, f"Voice pack setup failed: {error}\n")


if __name__ == "__main__":
    main()

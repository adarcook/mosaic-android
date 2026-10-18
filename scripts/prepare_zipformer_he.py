#!/usr/bin/env python3
"""Inspect, download and pin the four Hebrew Zipformer2 sherpa-onnx INT8 assets.

Weights and local lock records are private; never commit them to Git.
No model training, inference, or phone access is performed here.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

DEFAULT_REPO = "ivrit-ai/zipformer2-66m-streaming-he"
ALLOWED_REPOS = (DEFAULT_REPO, "thewh1teagle/zipformer2-66m-streaming-he")
ASSET_NAMES = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
ASSET_DIR = "sherpa-onnx"
LOCK_NAME = "zipformer-lock.json"
MAX_FILE_BYTES = 400 * 1024 * 1024


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_revision(revision):
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Model revision must be a 40-character immutable Git commit SHA")
    return revision


def validate_siblings(siblings):
    by_name = {}
    for file in siblings:
        name = getattr(file, "rfilename", "")
        if name in {f"{ASSET_DIR}/{item}" for item in ASSET_NAMES}:
            size = getattr(file, "size", None)
            if size is not None and (not isinstance(size, int) or size <= 0 or size > MAX_FILE_BYTES):
                raise ValueError(f"Unreasonable model file size: {name}: {size}")
            lfs = getattr(file, "lfs", None)
            if isinstance(lfs, dict):
                remote_sha256 = lfs.get("sha256") or lfs.get("oid")
            else:
                remote_sha256 = getattr(lfs, "sha256", None) or getattr(lfs, "oid", None)
            if isinstance(remote_sha256, str) and remote_sha256.startswith("sha256:"):
                remote_sha256 = remote_sha256[7:]
            if not isinstance(remote_sha256, str) or not re.fullmatch("[0-9a-f]{64}", remote_sha256):
                remote_sha256 = None  # Pin commit and hash downloaded bytes locally.
            by_name[name] = {"size": size, "remote_sha256": remote_sha256}
    expected = {f"{ASSET_DIR}/{name}" for name in ASSET_NAMES}
    if set(by_name) != expected:
        raise ValueError(f"Missing exact INT8 assets: {sorted(expected - set(by_name))}")
    return by_name


def check_local(root, record):
    if record.get("format") != 1 or record.get("repo_id") not in ALLOWED_REPOS:
        raise ValueError("Invalid Zipformer lock contract")
    validate_revision(record.get("revision"))
    models = record.get("files")
    if not isinstance(models, dict) or set(models) != {f"{ASSET_DIR}/{x}" for x in ASSET_NAMES}:
        raise ValueError("Unexpected Zipformer model files")
    for name, expected in models.items():
        path = root / name
        if not path.is_file() or path.stat().st_size != expected["bytes"]:
            raise ValueError(f"Missing or unexpected model size: {name}")
        if sha256_file(path) != expected["sha256"]:
            raise ValueError(f"Model SHA-256 mismatch: {name}")
    return record


def inspect(args):
    try:
        from huggingface_hub import HfApi, hf_hub_download
    except ImportError as exc:
        raise RuntimeError("Install dependency: python -m pip install huggingface_hub") from exc
    if args.repo not in ALLOWED_REPOS:
        raise ValueError("Unapproved model repository")
    info = HfApi().model_info(args.repo, revision=args.revision, files_metadata=True)
    commit = validate_revision(info.sha)
    files = validate_siblings(info.siblings)
    print("Repository:", args.repo)
    print("Pinned commit:", commit)
    for name, metadata in sorted(files.items()):
        print(" ", name, "bytes:", metadata["size"] or "unknown",
              "LFS SHA-256:", metadata["remote_sha256"] or "to verify after download")
    if args.inspect:
        print("Inspection only: no weights downloaded.")
        return

    root = Path(args.dest).expanduser().resolve()
    lock_path = root / LOCK_NAME
    if lock_path.exists():
        old = json.loads(lock_path.read_text(encoding="utf-8"))
        if old.get("repo_id") != args.repo or old.get("revision") != commit:
            raise ValueError("A different model revision is pinned here. Use another destination.")
        check_local(root, old)
        print("Existing lock validated. No download needed.")
        return

    records = {}
    for name in ASSET_NAMES:
        remote_name = f"{ASSET_DIR}/{name}"
        print("Downloading pinned", remote_name, flush=True)
        downloaded = Path(hf_hub_download(
            repo_id=args.repo, filename=remote_name, revision=commit,
            local_dir=str(root)))
        if not downloaded.is_file():
            raise IOError("Download was not materialized: " + remote_name)
        size = downloaded.stat().st_size
        if not 0 < size <= MAX_FILE_BYTES:
            raise ValueError("Invalid downloaded size: " + remote_name)
        expected_size = files[remote_name]["size"]
        if expected_size is not None and expected_size != size:
            raise ValueError("Remote file-size mismatch: " + remote_name)
        digest = sha256_file(downloaded)
        expected_sha = files[remote_name]["remote_sha256"]
        if expected_sha and digest != expected_sha:
            raise ValueError("Remote LFS SHA-256 mismatch: " + remote_name)
        records[remote_name] = {"bytes": size, "sha256": digest}
        print("Verified:", remote_name, size, digest)

    lock = {"format": 1, "repo_id": args.repo, "revision": commit, "files": records}
    check_local(root, lock)
    temporary = lock_path.with_suffix(".tmp")
    temporary.write_text(json.dumps(lock, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                         encoding="utf-8")
    temporary.replace(lock_path)
    print("PASS — models locked and verified:", lock_path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inspect", action="store_true", help="Metadata only; no model downloads")
    parser.add_argument("--verify-only", action="store_true", help="Offline SHA-256 check of locked files")
    parser.add_argument("--repo", default=DEFAULT_REPO, choices=ALLOWED_REPOS)
    parser.add_argument("--revision", help="Optional specific immutable 40-hex commit")
    parser.add_argument("--dest", default=str(Path.home() / "Mosaic" / "zipformer2-66m-streaming-he"))
    args = parser.parse_args(argv)
    try:
        if args.verify_only:
            root = Path(args.dest).expanduser().resolve()
            check_local(root, json.loads((root / LOCK_NAME).read_text(encoding="utf-8")))
            print("PASS — locally pinned Zipformer2 files verified:", root)
        else:
            inspect(args)
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print("FAIL:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

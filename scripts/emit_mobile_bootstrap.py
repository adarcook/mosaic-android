"""Package only the fixed reply and a pinned model download index in the APK."""
import argparse
import json
import shutil
from pathlib import Path
from urllib.parse import quote

import prepare_voice_poc as prep


def emit(root, output):
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    repo, revision, filename, _ = prep.stt_source(manifest['stt_profile'])
    downloads = []
    for name, digest in manifest['files'].items():
        path = root / name
        if prep.sha256(path) != digest:
            raise ValueError(f'Bootstrap source checksum mismatch: {name}')
        if name == 'reply.json':
            continue
        if name == 'whisper/ggml-model.bin':
            url = f'https://huggingface.co/{repo}/resolve/{revision}/{quote(filename)}'
        elif name.startswith('blue/'):
            url = f'https://huggingface.co/notmax123/BlueTTS2.5-onnx/resolve/{prep.BLUE_REV}/{quote(name[5:])}'
        else:
            raise ValueError(f'Unsupported bootstrap file: {name}')
        downloads.append(dict(path=name, url=url, sha256=digest, size=path.stat().st_size))
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / 'reply.json', output / 'reply.json')
    (output / 'index.json').write_text(json.dumps(dict(manifest=manifest, downloads=downloads)), encoding='utf-8')
    print(f'Mobile bootstrap: {len(downloads)} downloads, {sum(d["size"] for d in downloads):,} bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--models', type=Path, default=Path('voice-models'))
    parser.add_argument('--output', type=Path, default=Path('app/src/debug/assets/voice-bootstrap'))
    args = parser.parse_args()
    emit(args.models, args.output)

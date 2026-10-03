"""Prepare public model files and a fixed-text input fixture; never sends user audio.
Python 3.12+: pip install "git+https://github.com/maxmelichov/BlueTTS.git@0e38dbf08ed53f85863d1eab092bd9572c53a503" huggingface-hub
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


BLUE_REV = '468da64b4a51795a7594a3637727dbaf876b6df2'
WHISPER_REV = 'c521a4b02f422512d734391fdf08bb08c0862f68'
WHISPER_REPO = 'ggerganov/whisper.cpp'
WHISPER_FILE = 'ggml-small-q5_1.bin'
WHISPER_SHA256 = 'ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb'
WHISPER_FP16_FILE = 'ggml-small.bin'
WHISPER_FP16_SHA256 = '1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b'
RENIKUD_REV = '679c56ca449d41873fb8ff7711ddf7563d28198f'
REPLY = 'שמעתי אותך. זו תשובת הבדיקה של מוזאיק בעברית. נשארו לך ארבעים ושניים גרם חלבון. זה נתון לדוגמה בלבד.'


def tensor(a):
    import numpy as np
    a = np.asarray(a)
    return {'shape': list(a.shape), 'data': a.reshape(-1).tolist()}


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download_stt(root, profile="small-q5_1"):
    filename = WHISPER_FP16_FILE if profile == "small-fp16" else WHISPER_FILE
    expected = WHISPER_FP16_SHA256 if profile == "small-fp16" else WHISPER_SHA256
    from huggingface_hub import hf_hub_download
    source = Path(hf_hub_download(WHISPER_REPO, filename, revision=WHISPER_REV))
    if sha256(source) != expected:
        raise ValueError('Whisper Small download checksum mismatch')
    target = root / 'whisper' / 'ggml-model.bin'
    target.parent.mkdir(parents=True, exist_ok=True)
    pending = target.with_suffix('.pending')
    shutil.copyfile(source, pending)
    pending.replace(target)
    return target


def stt_manifest(manifest, whisper, profile="small-q5_1"):
    updated = dict(manifest)
    updated.update(stt_profile=profile, whisper_repo=WHISPER_REPO,
                   whisper_revision=WHISPER_REV, whisper_source_file=WHISPER_FP16_FILE if profile == "small-fp16" else WHISPER_FILE)
    updated['files'] = dict(manifest['files'])
    updated['files']['whisper/ggml-model.bin'] = sha256(whisper)
    return updated


def write_manifest(root, manifest):
    pending = root / 'manifest.pending'
    pending.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    pending.replace(root / 'manifest.json')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('voice-models'))
    parser.add_argument('--stt-only', action='store_true', help='Upgrade only Whisper in an existing BlueTTS pack')
    parser.add_argument('--stt-profile', choices=['small-q5_1', 'small-fp16'], default='small-q5_1')
    args = parser.parse_args()
    root = args.output.resolve()
    if args.stt_only:
        manifest_path = root / 'manifest.json'
        if not manifest_path.is_file():
            parser.error('--stt-only requires an existing prepared voice-models pack')
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        # Preserve the known-good TTS fixture and all its hashes.
        for name in manifest['files']:
            if name != 'whisper/ggml-model.bin' and not (root / name).is_file():
                parser.error(f'Existing pack is incomplete: {name}')
        whisper = download_stt(root, args.stt_profile)
        write_manifest(root, stt_manifest(manifest, whisper, args.stt_profile))
        print(f'Ready: {root}. Copy whisper/ggml-model.bin and manifest.json to the stopped app.')
        return
    import numpy as np
    from huggingface_hub import hf_hub_download, snapshot_download
    from blue_onnx import BlueTTS
    blue = root / 'blue'
    blue.mkdir(parents=True, exist_ok=True)
    snapshot_download('notmax123/BlueTTS2.5-onnx', revision=BLUE_REV, local_dir=blue,
                      allow_patterns=['*.onnx', '*.onnx.data', '*.npz', '*.json', 'voices/libri_male_6209.json'])
    whisper = download_stt(root, args.stt_profile)
    print('Preparing fixed Hebrew pronunciation on the host; synthesis will run on Android.')
    # Host-only G2P: this revision has no optional datastore.json. Pass an
    # explicit model path so RenikudPlus does not request that absent sidecar.
    renikud = hf_hub_download('notmax123/RenikudPlus', 'model.onnx', revision=RENIKUD_REV)
    engine = BlueTTS(onnx_dir=str(blue), style_json=str(blue / 'voices/libri_male_6209.json'),
                     renikud_path=renikud)
    tts = engine.tts
    phonemes = tts.g2p.phonemize(REPLY, lang='he')
    ids, mask = tts.text_processor([phonemes], ['he'])
    style = engine.style
    duration = tts.dp_ort.run(None, {'text_ids': ids, 'style_dp': style.dp, 'text_mask': mask})[0]
    assert tts._u_text is not None and tts._u_ref is not None, 'This pinned bundle requires CFG embeddings'
    np.random.seed(123)
    noise, latent_mask = tts.sample_noisy_latent(np.asarray(duration, dtype=np.float32).reshape(-1))
    mean, std, scale = tts._latent_mean, tts._latent_std, tts._normalizer_scale
    bundle = {'text': REPLY, 'steps': 5, 'cfg': 4.0, 'sample_rate': tts.sample_rate,
              'base_chunk_size': tts.base_chunk_size, 'compress': tts.chunk_compress_factor,
              'ldim': tts.ldim, 'normalizer_scale': scale,
              'ids': tensor(ids), 'text_mask': tensor(mask),
              'style_ttl': tensor(style.ttl), 'style_dp': tensor(style.dp),
              'u_text': tensor(tts._u_text), 'u_ref': tensor(tts._u_ref),
              'mean': tensor(mean), 'std': tensor(std), 'noise': tensor(noise),
              'latent_mask': tensor(latent_mask)}
    (root / 'reply.json').write_text(json.dumps(bundle, ensure_ascii=False), encoding='utf-8')
    # A deterministic host reference enables waveform/quality comparison to the Android port.
    np.random.seed(123)
    reference, _ = tts._infer([phonemes], ['he'], style, total_step=5, speed=1.0, cfg_scale=4.0)
    reference = np.asarray(reference, dtype=np.float32).reshape(-1)
    peak = np.max(np.abs(reference))
    if peak > 0.95:
        reference *= 0.95 / peak
    sr = tts.sample_rate
    import soundfile as sf
    sf.write(root / 'reference.wav', reference, sr)
    files = [Path(whisper), root / 'reply.json'] + list(blue.glob('*.onnx')) + list(blue.glob('*.onnx.data'))
    manifest = {'blue_revision': BLUE_REV, 'whisper_revision': WHISPER_REV,
                'host_renikud_revision': RENIKUD_REV,
                'files': {p.relative_to(root).as_posix(): sha256(p)
                          for p in files}}
    write_manifest(root, stt_manifest(manifest, Path(whisper), args.stt_profile))
    print(f'Ready: {root}. Model files remain outside Git. See docs/hebrew-voice-poc.md for adb push.')


if __name__ == '__main__':
    main()

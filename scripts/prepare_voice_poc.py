"""Prepare public model files and a fixed-text input fixture; never sends user audio.
Python 3.12+: pip install "git+https://github.com/maxmelichov/BlueTTS.git@0e38dbf08ed53f85863d1eab092bd9572c53a503" huggingface-hub
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from huggingface_hub import hf_hub_download, snapshot_download
from blue_onnx import BlueTTS, load_voice_style

BLUE_REV = '468da64b4a51795a7594a3637727dbaf876b6df2'
WHISPER_REV = '2130c78e4a9cb4914cc4df91a1c3031407789705'  # Resolved and recorded as a full commit in manifest below.
REPLY = 'שמעתי אותך. זו תשובת הבדיקה של מוזאיק בעברית. נשארו לך ארבעים ושניים גרם חלבון. זה נתון לדוגמה בלבד.'


def tensor(a):
    a = np.asarray(a)
    return {'shape': list(a.shape), 'data': a.reshape(-1).tolist()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('voice-models'))
    args = parser.parse_args()
    root = args.output.resolve()
    blue = root / 'blue'
    blue.mkdir(parents=True, exist_ok=True)
    snapshot_download('notmax123/BlueTTS2.5-onnx', revision=BLUE_REV, local_dir=blue,
                      allow_patterns=['*.onnx', '*.onnx.data', '*.npz', '*.json', 'voices/libri_male_6209.json'])
    whisper = hf_hub_download('ivrit-ai/whisper-large-v3-turbo-ggml', 'ggml-model.bin',
                             revision=WHISPER_REV, local_dir=root / 'whisper')
    print('Preparing fixed Hebrew pronunciation on the host; synthesis will run on Android.')
    engine = BlueTTS(onnx_dir=str(blue), style_json=str(blue / 'voices/libri_male_6209.json'))
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
    reference, sr = engine.synthesize(phonemes, text_is_phonemes=True, speed=1.0)
    import soundfile as sf
    sf.write(root / 'reference.wav', reference, sr)
    files = [Path(whisper), root / 'reply.json'] + list(blue.glob('*.onnx')) + list(blue.glob('*.onnx.data'))
    manifest = {'blue_revision': BLUE_REV, 'whisper_revision': WHISPER_REV,
                'files': {p.relative_to(root).as_posix(): hashlib.file_digest(p.open('rb'), 'sha256').hexdigest()
                          for p in files}}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(f'Ready: {root}. Model files remain outside Git. See docs/hebrew-voice-poc.md for adb push.')


if __name__ == '__main__':
    main()

import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import prepare_voice_poc as prep


class SttUpgradeTest(unittest.TestCase):
    def test_upgrade_preserves_tts_and_only_changes_stt_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reply = root / 'reply.json'
            reply.write_bytes(b'known-good TTS fixture')
            old = {'blue_revision': 'pinned-blue', 'files': {
                'reply.json': prep.sha256(reply), 'whisper/ggml-model.bin': 'old-hash'}}
            prep.write_manifest(root, old)
            source = root / 'download.bin'
            source.write_bytes(b'new small model')
            hub = types.SimpleNamespace(hf_hub_download=lambda *a, **k: str(source))
            with patch.dict(sys.modules, huggingface_hub=hub), \
                 patch.object(prep, 'WHISPER_SHA256', prep.sha256(source)), \
                 patch.object(sys, 'argv', ['prepare', '--stt-only', '--output', str(root)]):
                prep.main()
            new = json.loads((root / 'manifest.json').read_text())
            self.assertEqual('small-q5_1', new['stt_profile'])
            self.assertEqual(old['blue_revision'], new['blue_revision'])
            self.assertEqual(old['files']['reply.json'], new['files']['reply.json'])
            self.assertEqual(b'known-good TTS fixture', reply.read_bytes())
            self.assertEqual(b'new small model', (root / 'whisper/ggml-model.bin').read_bytes())
            self.assertFalse((root / 'manifest.pending').exists())

    def test_fp16_download_and_manifest_use_matching_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'download.bin'
            source.write_bytes(b'FP16 model')
            hub = types.SimpleNamespace(hf_hub_download=lambda *a, **k: str(source))
            with patch.dict(sys.modules, huggingface_hub=hub), \
                 patch.object(prep, 'WHISPER_FP16_SHA256', prep.sha256(source)):
                target = prep.download_stt(root, 'small-fp16')
            manifest = prep.stt_manifest({'files': {'reply.json': 'preserved'}}, target, 'small-fp16')
            self.assertEqual('small-fp16', manifest['stt_profile'])
            self.assertEqual('ggml-small.bin', manifest['whisper_source_file'])
            self.assertEqual('preserved', manifest['files']['reply.json'])
            self.assertEqual(prep.sha256(source), manifest['files']['whisper/ggml-model.bin'])

    def test_hebrew_profile_pins_source_and_preserves_tts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'download.bin'
            source.write_bytes(b'Hebrew model')
            calls = []
            def download(repo, filename, **kwargs):
                calls.append((repo, filename, kwargs['revision']))
                return str(source)
            hub = types.SimpleNamespace(hf_hub_download=download)
            with patch.dict(sys.modules, huggingface_hub=hub), \
                 patch.object(prep, 'IVRIT_SHA256', prep.sha256(source)):
                target = prep.download_stt(root, 'ivrit-turbo-q5_0')
            self.assertEqual([(prep.IVRIT_REPO, prep.IVRIT_FILE, prep.IVRIT_REV)], calls)
            manifest = prep.stt_manifest({'files': {'reply.json': 'preserved'}}, target, 'ivrit-turbo-q5_0')
            self.assertEqual(prep.IVRIT_REPO, manifest['whisper_repo'])
            self.assertEqual('ivrit-turbo-q5_0', manifest['stt_profile'])
            self.assertEqual('preserved', manifest['files']['reply.json'])
            reverted = prep.stt_manifest(manifest, target, 'small-fp16')
            self.assertNotIn('whisper_upstream', reverted)
            self.assertEqual(prep.WHISPER_REPO, reverted['whisper_repo'])

    def test_bad_download_keeps_old_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / 'whisper/ggml-model.bin'
            target.parent.mkdir()
            target.write_bytes(b'old model')
            source = root / 'download.bin'
            source.write_bytes(b'corrupt')
            hub = types.SimpleNamespace(hf_hub_download=lambda *a, **k: str(source))
            with patch.dict(sys.modules, huggingface_hub=hub):
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    prep.download_stt(root)
            self.assertEqual(b'old model', target.read_bytes())

    def test_stt_only_rejects_missing_tts_before_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prep.write_manifest(root, {'files': {'reply.json': 'missing'}})
            with patch.object(prep, 'download_stt') as download, \
                 patch.object(sys, 'argv', ['prepare', '--stt-only', '--output', str(root)]):
                with self.assertRaises(SystemExit):
                    prep.main()
                download.assert_not_called()


if __name__ == '__main__':
    unittest.main()

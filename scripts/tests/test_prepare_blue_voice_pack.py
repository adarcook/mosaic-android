import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("blue_pack", Path(__file__).resolve().parents[1]/"prepare_blue_voice_pack.py")
prep = importlib.util.module_from_spec(spec); spec.loader.exec_module(prep)


class BluePackTest(unittest.TestCase):
    def test_bad_source_preserves_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); source=root/"source"; target=root/"target"
            source.write_bytes(b"corrupt");target.write_bytes(b"committed")
            with self.assertRaises(ValueError): prep.install_file(source,target,4,hashlib.sha256(b"good").hexdigest())
            self.assertEqual(target.read_bytes(),b"committed")
            self.assertEqual(source.read_bytes(),b"corrupt")

    def test_verified_copy_and_repeat_do_not_rewrite_weights(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/"source";target=root/"target";source.write_bytes(b"good")
            digest=hashlib.sha256(b"good").hexdigest();prep.install_file(source,target,4,digest)
            modified=target.stat().st_mtime_ns;prep.install_file(source,target,4,digest)
            self.assertEqual(target.read_bytes(),b"good");self.assertEqual(target.stat().st_mtime_ns,modified)
            self.assertFalse((root/"target.pending").exists())

    def test_missing_cache_never_implicitly_downloads(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict("os.environ",{"HF_HUB_CACHE":directory}):
            with self.assertRaisesRegex(ValueError,"explicitly"):prep.locate_renikud()

    def test_pinned_cache_is_reused_without_huggingface_import(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict("os.environ",{"HF_HUB_CACHE":directory}):
            path=Path(directory)/"models--notmax123--RenikudPlus"/"snapshots"/prep.REV/"model.onnx"
            path.parent.mkdir(parents=True);path.write_bytes(b"cache lookup only; verification is a separate step")
            self.assertEqual(prep.locate_renikud(),path)


if __name__=="__main__":unittest.main()

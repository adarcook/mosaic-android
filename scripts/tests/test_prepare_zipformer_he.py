import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "prepare_zipformer_he.py"
spec = importlib.util.spec_from_file_location("zipformer_preflight", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ZipformerPreflightTests(unittest.TestCase):
    def test_rejects_unpinned_revision(self):
        for candidate in ("main", "abcdef", "", "../bad", "G" * 40):
            with self.assertRaises(ValueError):
                module.validate_revision(candidate)
        self.assertEqual("a" * 40, module.validate_revision("a" * 40))

    def test_requires_all_four_expected_int8_files(self):
        names = [f"sherpa-onnx/{name}" for name in module.ASSET_NAMES]
        siblings = [SimpleNamespace(rfilename=name, size=123, lfs=None) for name in names]
        found = module.validate_siblings(siblings)
        self.assertEqual(set(names), set(found))
        with self.assertRaises(ValueError):
            module.validate_siblings(siblings[:-1])
        with self.assertRaises(ValueError):
            module.validate_siblings([SimpleNamespace(rfilename=names[0], size=module.MAX_FILE_BYTES + 1, lfs=None)] + siblings[1:])

    def test_lfs_sha_is_captured_when_available(self):
        value = "d" * 64
        siblings = [
            SimpleNamespace(rfilename=f"sherpa-onnx/{name}", size=123,
                            lfs={"sha256": value}) for name in module.ASSET_NAMES
        ]
        self.assertEqual(value, module.validate_siblings(siblings)["sherpa-onnx/encoder.int8.onnx"]["remote_sha256"])

    def test_lock_detects_corruption_and_missing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            record = {"format": 1, "repo_id": module.DEFAULT_REPO, "revision": "a" * 40, "files": {}}
            for name in module.ASSET_NAMES:
                relative = "sherpa-onnx/" + name
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(name.encode())
                record["files"][relative] = {
                    "bytes": target.stat().st_size,
                    "sha256": module.sha256_file(target),
                }
            module.check_local(root, record)
            path = root / "sherpa-onnx/encoder.int8.onnx"
            path.write_bytes(b"broken at same size!"[:len(path.read_bytes())])
            with self.assertRaises(ValueError):
                module.check_local(root, record)
            path.unlink()
            with self.assertRaises(ValueError):
                module.check_local(root, record)

    def test_lock_rejects_extra_files_and_wrong_repo(self):
        with tempfile.TemporaryDirectory() as folder:
            record = {"format": 1, "repo_id": module.DEFAULT_REPO, "revision": "a"*40, "files": {}}
            with self.assertRaises(ValueError):
                module.check_local(Path(folder), record)
            record["repo_id"] = "malicious/external-model"
            with self.assertRaises(ValueError):
                module.check_local(Path(folder), record)


if __name__ == "__main__":
    unittest.main()

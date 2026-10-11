import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("builder", Path(__file__).parents[1] / "build_tensor_apps.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class HostBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for module in builder.MODULES.values():
            (self.root / module).mkdir(parents=True)

    def sdk(self, path):
        (path / "platforms").mkdir(parents=True)
        (path / "platform-tools").mkdir()
        return path

    def test_existing_whisper_sdk_shared_with_llm_preserving_local_settings(self):
        sdk = self.sdk(self.root / "SDK with space é")
        builder.configure_sdk(self.root, sdk, ["whisper"])
        llm = self.root / builder.MODULES["llm"] / "local.properties"
        llm.write_text("other.setting=keep\nsdk.dir=/stale/linux/path\n")
        found = builder.find_sdk(self.root, self.root, {}, None)
        builder.configure_sdk(self.root, found, ["llm"])
        self.assertEqual(builder.read_sdk(llm), str(sdk))
        self.assertIn("other.setting=keep", llm.read_text())

    def test_mac_default_and_explicit_invalid_path(self):
        sdk = self.sdk(self.root / "Library/Android/sdk")
        self.assertEqual(builder.find_sdk(self.root, self.root, {}, None), sdk)
        with self.assertRaises(ValueError):
            builder.find_sdk(self.root, self.root, {}, "/nonexistent-sdk")

    def test_build_never_requests_weights_or_touches_existing_model(self):
        model = self.root / "private-model.litertlm"
        model.write_bytes(b"existing private weights")
        with patch.object(builder.subprocess, "run") as run:
            builder.build(self.root, ["whisper", "llm"], {"ANDROID_HOME": "/sdk"})
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 4)
        self.assertTrue(any("testDebugUnitTest" in command for command in commands))
        self.assertFalse(any("--model-dir" in command for command in commands))
        self.assertEqual(model.read_bytes(), b"existing private weights")


if __name__ == "__main__":
    unittest.main()

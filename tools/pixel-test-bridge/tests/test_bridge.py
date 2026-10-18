"""Unit tests for parsing, permissions, checkpointing and never-replay behavior."""
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from github_queue import (parse_command, authorized, poll_once,
                          checkpoint_write, BridgeError)
from device import selected_serial, signer, extract_single_apk, Pixel
from bridge import load_config


class CommandTests(unittest.TestCase):
    def test_allowed_commands(self):
        for action in ("status", "device", "inspect", "launch", "smoke"):
            self.assertEqual(parse_command('/mosaic-test {"action":"%s"}' % action), {"action": action})
        self.assertEqual(parse_command('/mosaic-test {"action":"install_ci_apk","sha":"%s"}' % ("a" * 40)),
                         {"action": "install_ci_apk", "sha": "a" * 40})

    def test_reject_malformed_commands(self):
        for text in (
            "/mosaic-test hello", "/mosaic-test []",
            '/mosaic-test {"action":"shell","command":"whoami"}',
            '/mosaic-test {"action":"status","arbitrary":"true"}',
            '/mosaic-test {"action":"install_ci_apk","sha":"abc"}',
            '/mosaic-test {"action":"launch","package":"com.whatsapp"}',
            "mosaic-test " + '{"action":"status"}',
        ):
            with self.subTest(command=text):
                self.assertIsNone(parse_command(text))

    def test_author_only(self):
        accepted = {"id": 12, "user": {"login": "adarcook"},
                    "body": '/mosaic-test {"action":"status"}'}
        self.assertTrue(authorized(accepted, 11))
        self.assertFalse(authorized({**accepted, "user": {"login": "attacker"}}, 11))
        self.assertFalse(authorized(accepted, 12))

    def test_poll_checkpoint_before_execution_no_replay(self):
        class FakeGH:
            def __init__(self):
                self.comments_data = [
                    {"id": 15, "user": {"login": "attacker"},
                     "body": '/mosaic-test {"action":"status"}'},
                    {"id": 16, "user": {"login": "adarcook"},
                     "body": '/mosaic-test {"action":"status"}'}
                ]
                self.replies = []
            def comments(self, issue):
                return self.comments_data
            def reply(self, *args):
                self.replies.append(args)
        class FakeDevice:
            def __init__(self):
                self.called = 0
            def execute(self, cmd, _gh):
                self.called += 1
                return {"bridge": "online"}
        gh, dev = FakeGH(), FakeDevice()
        with tempfile.TemporaryDirectory() as tmp:
            checkpoint = Path(tmp) / "state.json"
            checkpoint_write(checkpoint, 0)
            self.assertEqual(poll_once(gh, dev, 46, checkpoint), 1)
            self.assertEqual(poll_once(gh, dev, 46, checkpoint), 0)
            self.assertEqual(dev.called, 1)
            self.assertEqual(json.loads(checkpoint.read_text())["last_seen"], 16)
            self.assertEqual(len(gh.replies), 1)


class DeviceTests(unittest.TestCase):
    @patch("device.process", return_value="List of devices attached\npixel-serial\tdevice\n")
    def test_one_device(self, run):
        self.assertEqual(selected_serial("adb", None), "pixel-serial")
        with self.assertRaises(BridgeError):
            selected_serial("adb", "unpaired")

    @patch("device.process", return_value="List of devices attached\none\tdevice\ntwo\tdevice\n")
    def test_refuse_multiple_devices(self, _run):
        with self.assertRaises(BridgeError):
            selected_serial("adb", None)

    @patch("device.process", return_value="Signer #1 certificate SHA-256 digest: " + "ab" * 32)
    def test_signature(self, _run):
        self.assertEqual(signer("apksigner", Path("voice.apk")), "ab" * 32)

    def test_zip_single_apk_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, target = Path(tmp) / "a.zip", Path(tmp) / "a.apk"
            with ZipFile(archive, "w") as z:
                z.writestr("MosaicTensorLlm-debug.apk", b"PK-TEST")
            extract_single_apk(archive, target)
            self.assertEqual(target.read_bytes(), b"PK-TEST")

    def test_zip_disallows_two_apks(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, target = Path(tmp) / "a.zip", Path(tmp) / "a.apk"
            with ZipFile(archive, "w") as z:
                z.writestr("one.apk", b"A")
                z.writestr("two.apk", b"B")
            with self.assertRaises(BridgeError):
                extract_single_apk(archive, target)

    def test_install_disabled_by_default(self):
        with self.assertRaises(BridgeError):
            Pixel({"allow_install": False}).execute({"action": "install_ci_apk", "sha": "a" * 40}, None)

    def test_status_has_no_private_data(self):
        data = Pixel({"allow_install": False}).execute({"action": "status"}, None)
        self.assertEqual(data, {"bridge": "online", "install_enabled": False})


class ConfigTests(unittest.TestCase):
    def test_pinned_repo_and_issue(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(json.dumps({"repository": "different/repo", "issue_number": 46}))
            with self.assertRaises(BridgeError):
                load_config(path)
            path.write_text(json.dumps({"repository": "adarcook/mosaic-android", "issue_number": 46}))
            self.assertEqual(load_config(path)["issue_number"], 46)


if __name__ == "__main__":
    unittest.main()

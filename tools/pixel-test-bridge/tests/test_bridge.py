"""Unit tests for parsing, permissions, checkpointing and never-replay behavior."""
import io
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from zipfile import ZipFile
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from github_queue import (parse_command, authorized, poll_once,
                          checkpoint_write, BridgeError)
from device import selected_serial, signer, extract_single_apk, Pixel, apk_payload, locally_signed_copy, download_verified_artifact
from bridge import load_config, github_token


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


class GitHubProvenanceTests(unittest.TestCase):
    def test_exact_sha_owner_repo_and_success_required(self):
        from github_queue import GitHub
        sha = "a" * 40
        gh = GitHub("fake-token-for-local-unit-test")
        good = {"name": "Tensor local LLM gate", "head_sha": sha,
                "conclusion": "success", "event": "pull_request",
                "head_repository": {"full_name": "adarcook/mosaic-android"},
                "head_branch": "feature/voice-whatsapp-notification-tool",
                "id": 123, "run_started_at": "2026-10-18T00:00:00Z"}
        calls = []
        def fake_request(path, data=None):
            calls.append(path)
            if path.startswith("/actions/runs?"):
                return {"workflow_runs": [
                    {**good, "conclusion": "failure", "id": 1},
                    {**good, "head_repository": {"full_name": "someone/fork"}, "id": 2},
                    {**good, "head_sha": "b"*40, "id": 3},
                    good]}
            return {"artifacts": [{"name": "mosaic-tensor-local-llm-apk",
                                   "expired": False, "id": 777, "digest": "sha256:" + "c"*64}]}
        gh.request = fake_request
        self.assertEqual(gh.successful_artifact(sha), {"id":777, "digest":"sha256:" + "c"*64})
        self.assertIn("/actions/runs/123/artifacts?per_page=100", calls)

    def test_rejects_missing_trusted_success(self):
        from github_queue import GitHub
        gh = GitHub("fake-token-for-local-unit-test")
        gh.request = lambda *_args, **_kwargs: {"workflow_runs": [{
            "name": "Tensor local LLM gate", "head_sha": "a"*40,
            "conclusion": "success", "event": "pull_request",
            "head_repository": {"full_name": "untrusted/fork"},
            "head_branch": "feature/voice", "id": 5
        }]}
        with self.assertRaises(BridgeError):
            gh.successful_artifact("a"*40)


class DeviceTests(unittest.TestCase):
    def test_enrolled_identity_accepts_duplicate_transports_but_never_another_phone(self):
        def run(args, **kwargs):
            if args[-1] == "devices":
                return "List of devices attached\nwireless:1234\tdevice\nusb\tdevice\nother\tdevice\n"
            return "another-phone" if args[2] == "other" else "enrolled-phone"
        with patch("device.process", side_effect=run):
            self.assertEqual(selected_serial("adb", None, "enrolled-phone"), "usb")
            with self.assertRaises(BridgeError):
                selected_serial("adb", None, "absent-phone")

    def test_multiple_signers_are_refused(self):
        output = "Signer #1 certificate SHA-256 digest: " + "a"*64 + "\nSigner #2 certificate SHA-256 digest: " + "b"*64
        with patch("device.process", return_value=output):
            with self.assertRaises(BridgeError):
                signer("apksigner", Path("voice.apk"))

    def test_payload_comparison_ignores_only_signature_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            one, two = Path(tmp)/"one.apk", Path(tmp)/"two.apk"
            for path, signature in [(one,b"old"),(two,b"new")]:
                with ZipFile(path,"w") as z:
                    z.writestr("classes.dex", b"code")
                    z.writestr("META-INF/library.version", b"library metadata")
                    z.writestr("META-INF/CERT.RSA", signature)
            self.assertEqual(apk_payload(one), apk_payload(two))
            with ZipFile(two,"w") as z:
                z.writestr("classes.dex", b"altered code")
                z.writestr("META-INF/library.version", b"library metadata")
            self.assertNotEqual(apk_payload(one), apk_payload(two))

    def test_local_signing_refuses_changed_payload_or_wrong_certificate(self):
        with tempfile.TemporaryDirectory() as tmp:
            key=Path(tmp)/"debug.keystore"; key.write_bytes(b"test fixture")
            with patch("device.process"), patch("device.signer", return_value="a"*64), patch("device.apk_payload", side_effect=[{"classes.dex": "old"},{"classes.dex": "changed"}]):
                with self.assertRaisesRegex(BridgeError, "payload changed"):
                    locally_signed_copy("signer", Path("source"), Path("target"), key, "a"*64)
            with patch("device.process"), patch("device.signer", return_value="b"*64), patch("device.apk_payload", return_value={}):
                with self.assertRaisesRegex(BridgeError, "certificate"):
                    locally_signed_copy("signer", Path("source"), Path("target"), key, "a"*64)

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


class InstallFlowTests(unittest.TestCase):
    def fixture(self, root, signing=True):
        config={"allow_install":True,"allow_local_debug_signing":signing,
                "device_identity":"synthetic-enrolled","trusted_signer_sha256":"a"*64,
                "local_debug_keystore":str(root/"debug.keystore"),"adb":"adb","aapt":"aapt"}
        installs=[]
        def write_apk(path):
            with ZipFile(path,"w") as z: z.writestr("classes.dex",b"synthetic code")
        def download(_gh,_sha,path):
            apk=root/"ci.apk";write_apk(apk)
            with ZipFile(path,"w") as z: z.write(apk,"voice.apk")
            return {"id":777,"digest":"sha256:"+"c"*64}
        def run(args,**kwargs):
            if args[0]=="aapt": return "package: name='life.mosaic.tensorllm' versionCode='25'"
            action=args[3:]
            if action==["shell","getprop","ro.product.model"]: return "Pixel 10 Pro"
            if action==["shell","pm","path","life.mosaic.tensorllm"]: return "package:/data/app/test/base.apk\n"
            if action[0]=="pull": write_apk(Path(action[-1]));return ""
            if action[0]=="install": installs.append(args);return "Success"
            raise AssertionError("Unexpected Android operation")
        def sign(_tool,source,target,_key,_pin): target.write_bytes(source.read_bytes())
        def certificate(_tool,path): return "b"*64 if path.name=="voice.apk" else "a"*64
        return config,installs,download,run,sign,certificate

    def test_install_runs_only_after_enrolled_signing_and_verifies_pulled_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            config,calls,download,run,sign,certificate=self.fixture(Path(tmp))
            with patch("device.selected_serial",return_value="enrolled-usb"), patch("device.download_verified_artifact",side_effect=download), patch("device.process",side_effect=run), patch("device.locally_signed_copy",side_effect=sign), patch("device.signer",side_effect=certificate):
                result=Pixel(config).execute({"action":"install_ci_apk","sha":"d"*40},None)
            self.assertTrue(result["verified_after_install"])
            self.assertEqual(len(calls),1)
            self.assertEqual(calls[0][3:5],["install","-r"])

    def test_ci_signer_mismatch_without_local_signing_never_installs(self):
        with tempfile.TemporaryDirectory() as tmp:
            config,calls,download,run,sign,certificate=self.fixture(Path(tmp),signing=False)
            with patch("device.selected_serial",return_value="enrolled-usb"), patch("device.download_verified_artifact",side_effect=download), patch("device.process",side_effect=run), patch("device.locally_signed_copy") as sign_call, patch("device.signer",side_effect=certificate):
                with self.assertRaisesRegex(BridgeError,"signing mismatch"):
                    Pixel(config).execute({"action":"install_ci_apk","sha":"d"*40},None)
            self.assertFalse(calls);sign_call.assert_not_called()

    def test_unknown_installed_certificate_never_signs_or_installs(self):
        with tempfile.TemporaryDirectory() as tmp:
            config,calls,download,run,sign,certificate=self.fixture(Path(tmp))
            config["trusted_signer_sha256"]="e"*64
            with patch("device.selected_serial",return_value="enrolled-usb"), patch("device.download_verified_artifact",side_effect=download), patch("device.process",side_effect=run), patch("device.locally_signed_copy") as sign_call, patch("device.signer",side_effect=certificate):
                with self.assertRaisesRegex(BridgeError,"enrolled certificate"):
                    Pixel(config).execute({"action":"install_ci_apk","sha":"d"*40},None)
            self.assertFalse(calls);sign_call.assert_not_called()

    def test_download_digest_mismatch_is_rejected(self):
        gh=MagicMock();gh.root="https://api.github.com/repos/adarcook/mosaic-android";gh.token="synthetic"
        gh.successful_artifact.return_value={"id":777,"digest":"sha256:"+"0"*64}
        opener=MagicMock();opener.open.side_effect=HTTPError("https://api.github.com",302,"redirect",{"Location":"https://fixture.blob.core.windows.net/artifact"},None)
        with tempfile.TemporaryDirectory() as tmp, patch("device.build_opener",return_value=opener), patch("device.urlopen",return_value=io.BytesIO(b"tampered ZIP")):
            with self.assertRaisesRegex(BridgeError,"ZIP SHA-256 mismatch"):
                download_verified_artifact(gh,"a"*40,Path(tmp)/"artifact.zip")


class ConfigTests(unittest.TestCase):
    @patch.dict(os.environ, {"MOSAIC_GITHUB_TOKEN":"synthetic-local-test"})
    def test_process_token_takes_precedence_without_registry(self):
        self.assertEqual(github_token(), "synthetic-local-test")

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

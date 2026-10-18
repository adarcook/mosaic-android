"""Entry point for Mosaic's Windows 11 GitHub-to-Pixel test bridge."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from github_queue import BridgeError, GitHub, checkpoint_write, poll_once, REPO
from device import Pixel, process

class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        content = b'{"bridge":"running","transport":"outbound-github"}\n'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)
    def log_message(self, *_args):
        pass


def load_config(file):
    try:
        obj = json.loads(file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise BridgeError("Copy config.example.json to config.json and fill it out.") from None
    if not isinstance(obj, dict) or obj.get("repository") != REPO:
        raise BridgeError("Repository must be exactly " + REPO)
    if type(obj.get("issue_number")) is not int or obj["issue_number"] < 1:
        raise BridgeError("Set the dedicated GitHub queue issue_number.")
    if obj.get("allowed_author", "adarcook") != "adarcook":
        raise BridgeError("Only adarcook may issue commands.")
    if type(obj.get("poll_seconds", 45)) is not int or not 10 <= obj.get("poll_seconds", 45) <= 3600:
        raise BridgeError("poll_seconds must be from 10 to 3600.")
    port = obj.get("local_health_port", 8765)
    if type(port) is not int or not 1024 <= port <= 65535:
        raise BridgeError("local_health_port out of range.")
    return obj


def main():
    p = argparse.ArgumentParser(description="Pixel Test Bridge: GitHub issue -> allowlisted ADB")
    p.add_argument("mode", choices=("init", "run", "check", "connect"))
    p.add_argument("--config", default="config.json")
    p.add_argument("--endpoint", help="Android Wireless debugging host:port for connect mode")
    args = p.parse_args()
    try:
        config_path = Path(args.config).resolve()
        config = load_config(config_path)
        pixel = Pixel(config)
        if args.mode == "connect":
            if not args.endpoint or not re.fullmatch("[a-zA-Z0-9._:-]+:[0-9]{2,5}", args.endpoint):
                raise BridgeError("Provide Android Wireless debugging host:port.")
            print(process([pixel.adb, "connect", args.endpoint], timeout=35).strip())
            return 0
        if args.mode == "check":
            print(json.dumps(pixel.execute({"action": "device"}, None)))
            return 0
        github = GitHub(os.getenv("MOSAIC_GITHUB_TOKEN"))
        issue = config["issue_number"]
        github.assert_queue(issue)
        checkpoint = config_path.with_name("state.json")
        if args.mode == "init":
            comments = github.comments(issue)
            current = max((comment["id"] for comment in comments), default=0)
            checkpoint_write(checkpoint, current)
            print("Queue initialized; existing comments ignored. Latest ID:", current)
            return 0
        if not checkpoint.is_file():
            raise BridgeError("Run bridge.py init once to skip old queue messages.")
        server = ThreadingHTTPServer(("127.0.0.1", config.get("local_health_port", 8765)), HealthHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        print("Pixel Bridge active. Local health: http://127.0.0.1:%d/health" %
              config.get("local_health_port", 8765), flush=True)
        while True:
            try:
                count = poll_once(github, pixel, issue, checkpoint)
                if count:
                    print("Handled %d authorized command(s)." % count, flush=True)
            except BridgeError as error:
                print("GitHub polling error:", error, file=sys.stderr, flush=True)
            time.sleep(config.get("poll_seconds", 45))
    except BridgeError as error:
        print("Pixel Bridge:", error, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Pixel Bridge stopped.")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

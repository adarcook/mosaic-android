"""Minimal authorized GitHub issue queue for Mosaic's Pixel bridge."""
import json
import os
import re
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

REPO = "adarcook/mosaic-android"
OWNER = "adarcook"
PREFIX = "/mosaic-test "
ACTIONS = {"status", "device", "inspect", "launch", "smoke", "install_ci_apk"}


class BridgeError(Exception):
    pass


def parse_command(body):
    if not isinstance(body, str) or len(body) > 512 or not body.startswith(PREFIX):
        return None
    try:
        data = json.loads(body[len(PREFIX):])
    except (TypeError, ValueError):
        return None
    if not isinstance(data, dict) or data.get("action") not in ACTIONS:
        return None
    if data["action"] == "install_ci_apk":
        if set(data) != {"action", "sha"} or not re.fullmatch("[0-9a-f]{40}", str(data["sha"])):
            return None
    elif set(data) != {"action"}:
        return None
    return data


def authorized(comment, last_seen):
    return (isinstance(comment.get("id"), int)
            and comment["id"] > last_seen
            and comment.get("user", {}).get("login", "").lower() == OWNER
            and parse_command(comment.get("body")) is not None)


class GitHub:
    def __init__(self, token):
        if not token:
            raise BridgeError("Set MOSAIC_GITHUB_TOKEN in this PowerShell session.")
        self.token = token
        self.root = "https://api.github.com/repos/" + REPO

    def request(self, suffix, data=None):
        if not suffix.startswith("/") or "://" in suffix:
            raise BridgeError("Invalid API endpoint")
        headers = {"Authorization": "Bearer " + self.token,
                   "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "MosaicPixelBridge/0.1"}
        payload = None
        if data is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(data).encode("utf-8")
        try:
            with urlopen(Request(self.root + suffix, data=payload, headers=headers,
                                 method="POST" if payload is not None else "GET"), timeout=40) as response:
                return json.load(response)
        except (HTTPError, URLError, ValueError):
            raise BridgeError("GitHub API request failed; verify token permissions and connectivity.") from None

    def assert_queue(self, issue):
        data = self.request("/issues/" + str(issue))
        if ("pull_request" in data or data.get("state") != "open"
                or data.get("user", {}).get("login", "").lower() != OWNER):
            raise BridgeError("Queue must be an open issue created by repository owner.")

    def comments(self, issue):
        results = []
        for page in range(1, 11):
            batch = self.request("/issues/%d/comments?per_page=100&page=%d" % (issue, page))
            if not isinstance(batch, list):
                raise BridgeError("Unexpected issue comments")
            results.extend(batch)
            if len(batch) < 100:
                return results
        raise BridgeError("Queue too large; start a new dedicated issue.")

    def reply(self, issue, original_id, outcome, details):
        # Public issue: never post raw adb output, private messages, logs or secrets.
        body = {"bridge_job": original_id, "outcome": outcome, **details}
        content = "Mosaic bridge result\n\n" + json.dumps(body, ensure_ascii=False, sort_keys=True)[:900]
        self.request("/issues/%d/comments" % issue, {"body": content})

    def successful_artifact(self, sha):
        result = self.request("/actions/runs?head_sha=%s&per_page=100" % sha)
        candidates = [run for run in result.get("workflow_runs", [])
                      if run.get("name") == "Tensor local LLM gate"
                      and run.get("head_sha") == sha
                      and run.get("conclusion") == "success"
                      and run.get("event") == "pull_request"
                      and (run.get("head_repository") or {}).get("full_name") == REPO
                      and (run.get("head_branch") or "").startswith(("feature/", "experiment/"))]
        if not candidates:
            raise BridgeError("No successful approved GitHub Actions build for exact SHA.")
        run = sorted(candidates, key=lambda x: x.get("run_started_at") or "", reverse=True)[0]
        artifacts = self.request("/actions/runs/%d/artifacts?per_page=100" % run["id"])
        matches = [a for a in artifacts.get("artifacts", [])
                   if a.get("name") == "mosaic-tensor-local-llm-apk" and not a.get("expired")]
        if len(matches) != 1:
            raise BridgeError("No unique current APK artifact found.")
        return matches[0]["id"]


def checkpoint_write(path, last_seen):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps({"last_seen": last_seen}) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def poll_once(github, device, issue, checkpoint):
    last_seen = json.loads(checkpoint.read_text(encoding="utf-8"))["last_seen"]
    total = 0
    for comment in sorted(github.comments(issue), key=lambda c: c["id"]):
        cid = comment["id"]
        if cid <= last_seen:
            continue
        checkpoint_write(checkpoint, cid)  # At most once: never replay install after crash
        last_seen = cid
        if not authorized(comment, cid - 1):
            continue
        command = parse_command(comment["body"])
        try:
            details = device.execute(command, github)
            outcome = "ok"
        except BridgeError as error:
            details, outcome = {"error": str(error)[:160]}, "blocked"
        except Exception:
            details, outcome = {"error": "Unexpected local error; details not made public."}, "error"
        try:
            github.reply(issue, cid, outcome, details)
        except BridgeError:
            print("Could not post result for command", cid)
        total += 1
    return total

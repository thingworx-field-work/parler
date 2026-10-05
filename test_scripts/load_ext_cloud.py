"""
Upload Parler extension/widget ZIPs to a remote ThingWorx, then drive TestMachineHelper services.

Requires repo root ``.env`` with ``DEV_SERVER`` and ``DEV_KEY`` (same as ``import-dev``).

  uv sync
  uv run load-ext-cloud --extension --widget
  uv run load-ext-cloud -e -b my-feature

  Optional ``-b`` / ``--branch``: resolve artifacts under ``../{repo_dirname}-{branch}``
  (``repo_dirname`` is the current checkout folder name, e.g. ``parler`` → ``../parler-my-feature``).
  That directory must already exist or the script exits with an error. ``.env`` is always read
  from this checkout's repo root.

Steps:
  - Optional ``-e`` / ``--extension``: POST ``parler-agent/build/parler-agent.zip`` to ExtensionPackageUploader
    (prints ``ExtensionPackageUploader HTTP <code>``; ``406`` = import accepted, restart required — treated as success).
  - Optional ``-w`` / ``--widget``: POST ``parler-ui-widget/dist/parler-ui-widget.zip`` to ExtensionPackageUploader
    (same status handling as agent ZIP).
  - Sleep 5s (give the platform time after extension import).
  - Generate a 56-character alphanumeric ``nonce`` (no whitespace).
  - POST ``CreateNonce`` then sleep 3s then POST ``TriggerServerService``; each response prints HTTP status code.
  - Poll ``/Thingworx/health`` every 2s: first wait until **not** HTTP 200 (restart in progress), at most 10 tries;
    if still 200 after 10 tries, print a **WARNING** and exit with code **2** (restart may not have run).
  - Then poll every 2s until HTTP 200 again (unbounded).

Design notes (limitations):
  - The Thing ``TestMachineHelper`` and its services must exist on ``DEV_SERVER``.
  - ZIP paths must exist when the corresponding flag is set; build artifacts first.
"""

from __future__ import annotations

import argparse
import json
import secrets
import string
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from test_scripts.dev_import_control import (
    extension_uploader_post_url,
    load_dotenv,
    post_extension_package,
    require_env,
    rest_call_url,
)
from test_scripts.reset_dev import check_health_ok, health_url

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"

SERVICE_CREATE_NONCE = "/Thingworx/Things/TestMachineHelper/Services/CreateNonce"
SERVICE_TRIGGER = "/Thingworx/Things/TestMachineHelper/Services/TriggerServerService"

POST_STABILIZE_S = 5.0
POST_TRIGGER_GAP_S = 3.0
HEALTH_POLL_SLEEP_S = 2.0
UNHEALTHY_WAIT_MAX_ATTEMPTS = 10
REST_TIMEOUT_S = 120.0

_NONCE_ALPHABET = string.ascii_letters + string.digits


def resolve_artifact_root(branch: str | None) -> Path:
    """
    Default: this checkout's repo root (``REPO_ROOT``).

    With ``branch``: ``(REPO_ROOT.parent / f"{REPO_ROOT.name}-{branch}").resolve()`` must exist
    and be a directory (sibling worktree layout ``../parler-<branch>`` when the folder is ``parler``).
    """
    if branch is None:
        return REPO_ROOT
    b = branch.strip()
    if not b:
        print("--branch must not be empty", file=sys.stderr)
        sys.exit(1)
    if "/" in b or "\\" in b or b.startswith(".."):
        print(
            "--branch must be a single path segment (no slashes or leading ..)",
            file=sys.stderr,
        )
        sys.exit(1)
    repo_dirname = REPO_ROOT.name
    sibling = (REPO_ROOT.parent / f"{repo_dirname}-{b}").resolve()
    if not sibling.is_dir():
        print(
            f"Sibling directory does not exist or is not a directory: {sibling}",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"--- artifact root (--branch {b!r}): {sibling} ---", flush=True)
    return sibling


def zip_agent_path(artifact_root: Path) -> Path:
    return artifact_root / "parler-agent" / "build" / "parler-agent.zip"


def zip_widget_path(artifact_root: Path) -> Path:
    return artifact_root / "parler-ui-widget" / "dist" / "parler-ui-widget.zip"


def generate_nonce_56() -> str:
    return "".join(secrets.choice(_NONCE_ALPHABET) for _ in range(56))


def post_json_print_http_code(
    *,
    dev_server: str,
    service_path: str,
    app_key: str,
    payload: dict[str, object],
    label: str,
) -> int:
    url = rest_call_url(dev_server, service_path)
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "appKey": app_key,
    }
    req = urllib.request.Request(url, data=body, method="POST")
    for hk, hv in headers.items():
        req.add_header(hk, hv)
    try:
        with urllib.request.urlopen(req, timeout=REST_TIMEOUT_S) as resp:
            code = resp.getcode()
            resp.read()
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace").strip()
        snippet = (err[:400] + "…") if len(err) > 400 else err
        print(f"{label} HTTP {e.code}", flush=True)
        if snippet:
            print(f"{label} body: {snippet}", file=sys.stderr, flush=True)
        return int(e.code)
    except urllib.error.URLError as e:
        print(f"{label} request failed: {e}", file=sys.stderr, flush=True)
        sys.exit(1)
    except TimeoutError as e:
        print(f"{label} timeout: {e}", file=sys.stderr, flush=True)
        sys.exit(1)
    except OSError as e:
        print(f"{label} request failed: {e}", file=sys.stderr, flush=True)
        sys.exit(1)

    print(f"{label} HTTP {code}", flush=True)
    return int(code)


def wait_unhealthy_then_healthy() -> None:
    """
    After a remote server restart was triggered: require observing health go non-OK
    (not HTTP 200) before accepting OK again, so we know the stack actually bounced.

    If health stays 200 for all ``UNHEALTHY_WAIT_MAX_ATTEMPTS`` polls (2s apart), exit 2
    with a warning — the restart may not have happened.
    """
    url = health_url()
    print(
        f"--- post-trigger: wait until health is NOT 200 (max {UNHEALTHY_WAIT_MAX_ATTEMPTS} tries, "
        f"{HEALTH_POLL_SLEEP_S:g}s apart), then until 200: {url} ---",
        flush=True,
    )

    saw_unhealthy = False
    for attempt in range(1, UNHEALTHY_WAIT_MAX_ATTEMPTS + 1):
        healthy = check_health_ok(url)
        if healthy:
            print(
                f"Health 'unhealthy' wait {attempt}/{UNHEALTHY_WAIT_MAX_ATTEMPTS}: still 200 (healthy)",
                flush=True,
            )
        else:
            print(
                f"Health 'unhealthy' wait {attempt}/{UNHEALTHY_WAIT_MAX_ATTEMPTS}: not 200 (unhealthy)",
                flush=True,
            )
            saw_unhealthy = True
            break
        if attempt < UNHEALTHY_WAIT_MAX_ATTEMPTS:
            time.sleep(HEALTH_POLL_SLEEP_S)

    if not saw_unhealthy:
        print(
            "WARNING: health remained HTTP 200 for every poll in the unhealthy-wait window; "
            "the remote server may not have restarted. Not waiting for recovery.",
            file=sys.stderr,
            flush=True,
        )
        sys.exit(2)

    attempt = 0
    while True:
        attempt += 1
        if check_health_ok(url):
            print(f"Health OK (200): {url}", flush=True)
            return
        print(
            f"Health recovery {attempt}: not 200 yet, sleeping {HEALTH_POLL_SLEEP_S:g}s …",
            flush=True,
        )
        time.sleep(HEALTH_POLL_SLEEP_S)


def main() -> None:
    p = argparse.ArgumentParser(
        description=(
            "Upload Parler agent/widget extension ZIPs, invoke TestMachineHelper services, "
            "then wait for health to go down then back up (see module docstring)."
        ),
    )
    p.add_argument(
        "-e",
        "--extension",
        action="store_true",
        help="Upload parler-agent/build/parler-agent.zip (root from --branch if set).",
    )
    p.add_argument(
        "-w",
        "--widget",
        action="store_true",
        help="Upload parler-ui-widget/dist/parler-ui-widget.zip (root from --branch if set).",
    )
    p.add_argument(
        "-b",
        "--branch",
        metavar="BRANCH",
        default=None,
        help=(
            "Use sibling directory ../{current_repo_folder}-BRANCH for ZIP paths "
            "(must exist). Example: repo folder parler + --branch foo → ../parler-foo."
        ),
    )
    args = p.parse_args()

    artifact_root = resolve_artifact_root(args.branch)
    zip_agent = zip_agent_path(artifact_root)
    zip_widget = zip_widget_path(artifact_root)

    load_dotenv(ENV_PATH)
    dev_server = require_env("DEV_SERVER")
    app_key = require_env("DEV_KEY")
    ext_url = extension_uploader_post_url(dev_server)

    if args.extension:
        print(f"--- ExtensionPackageUploader: {zip_agent} ---", flush=True)
        if not zip_agent.is_file():
            print(f"Missing file: {zip_agent}", file=sys.stderr)
            sys.exit(1)
        post_extension_package(ext_url, app_key, zip_agent)
        print("OK parler-agent.zip (ExtensionPackageUploader accepted)", flush=True)

    if args.widget:
        print(f"--- ExtensionPackageUploader: {zip_widget} ---", flush=True)
        if not zip_widget.is_file():
            print(f"Missing file: {zip_widget}", file=sys.stderr)
            sys.exit(1)
        post_extension_package(ext_url, app_key, zip_widget)
        print("OK parler-ui-widget.zip (ExtensionPackageUploader accepted)", flush=True)

    print(f"--- sleep {POST_STABILIZE_S:g}s ---", flush=True)
    time.sleep(POST_STABILIZE_S)

    nonce = generate_nonce_56()
    print(f"--- nonce (len={len(nonce)}): {nonce[:8]}… ---", flush=True)

    code1 = post_json_print_http_code(
        dev_server=dev_server,
        service_path=SERVICE_CREATE_NONCE,
        app_key=app_key,
        payload={"nonce": nonce},
        label="CreateNonce",
    )
    if not (200 <= code1 < 300):
        sys.exit(1)

    print(f"--- sleep {POST_TRIGGER_GAP_S:g}s ---", flush=True)
    time.sleep(POST_TRIGGER_GAP_S)

    code2 = post_json_print_http_code(
        dev_server=dev_server,
        service_path=SERVICE_TRIGGER,
        app_key=app_key,
        payload={"nonce": nonce, "port": 13309},
        label="TriggerServerService",
    )
    if not (200 <= code2 < 300):
        sys.exit(1)

    wait_unhealthy_then_healthy()


if __name__ == "__main__":
    main()

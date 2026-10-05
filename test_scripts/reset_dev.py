"""
Reset remote dev Docker Compose stack and wait for ThingWorx health.

Reads ``.env`` at the parler repo root (``SSH_REMOTE_HOST``, ``DEV_SERVER``).

Run from repository root with uv:

  uv sync
  uv run reset-dev
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"

REMOTE_COMPOSE_DIR = "/opt/dxu/iot_stream/project/dev_compose"
COMPOSE_ENV = "COMPOSE_FILE=docker-compose.yml:docker-compose.monitoring.yml"
HEALTH_PATH = "/Thingworx/health"
HEALTH_ATTEMPTS = 5
HEALTH_RETRY_SLEEP_S = 10
HTTP_TIMEOUT_S = 30


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, rest = line.partition("=")
        key = key.strip()
        val = rest.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ('"', "'"):
            val = val[1:-1]
        if key and key not in os.environ:
            os.environ[key] = val


def require_env(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        print(f"Missing required environment variable: {name}", file=sys.stderr)
        sys.exit(1)
    return v


def health_url() -> str:
    base = require_env("DEV_SERVER").rstrip("/")
    return f"{base}{HEALTH_PATH}"


def run_ssh_remote_script(host: str, remote_bash_script: str) -> None:
    cmd = ["ssh", host, remote_bash_script]
    print(f"+ {' '.join(cmd[:2])} '<remote script>'", flush=True)
    r = subprocess.run(cmd, check=False)
    if r.returncode != 0:
        print(f"ssh failed with exit code {r.returncode}", file=sys.stderr)
        sys.exit(r.returncode)


def check_health_ok(url: str) -> bool:
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
            return resp.getcode() == 200
    except urllib.error.HTTPError as e:
        return e.code == 200
    except (urllib.error.URLError, TimeoutError, OSError):
        # Server not listening yet often raises reset/closed connection (e.g. WinError 10054).
        return False


def wait_for_health(url: str) -> None:
    for attempt in range(1, HEALTH_ATTEMPTS + 1):
        if check_health_ok(url):
            print(f"Health OK (200): {url}", flush=True)
            return
        print(
            f"Health check {attempt}/{HEALTH_ATTEMPTS} did not return 200: {url}",
            flush=True,
        )
        if attempt < HEALTH_ATTEMPTS:
            time.sleep(HEALTH_RETRY_SLEEP_S)
    print(
        f"ThingWorx health did not return 200 after {HEALTH_ATTEMPTS} attempts.",
        file=sys.stderr,
    )
    sys.exit(1)


def main() -> None:
    load_dotenv(ENV_PATH)
    host = require_env("SSH_REMOTE_HOST")

    down_script = (
        f"set -e; cd {REMOTE_COMPOSE_DIR} && {COMPOSE_ENV} docker compose down -v"
    )
    up_script = f"set -e; cd {REMOTE_COMPOSE_DIR} && {COMPOSE_ENV} docker compose up -d"

    print("--- docker compose down -v ---", flush=True)
    run_ssh_remote_script(host, down_script)

    print("--- docker compose up -d ---", flush=True)
    run_ssh_remote_script(host, up_script)

    url = health_url()
    print(f"--- waiting for health: {url} ---", flush=True)
    wait_for_health(url)


if __name__ == "__main__":
    main()

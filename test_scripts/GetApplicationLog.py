"""
Fetch recent ThingWorx Application Log rows via ``QueryLogEntries``.

Reads ``DEV_SERVER`` and ``DEV_KEY`` from the parler repo root ``.env`` (same loader as ``reset_dev``).

Defaults (UTC):

- ``endDate``: now
- ``startDate``: five minutes before ``endDate``
- ``maxItems``: 100

**Default stdout:** compact JSON: ``meta`` + ``rows`` where each row has only **timestamp** (ISO-8601 **Z** if the
platform sent epoch ms), **thread**, **content** — easy for humans and for pasting into LLM context.

URL: ``{DEV_SERVER}`` is joined with ``/Thingworx/Logs/ApplicationLog/Services/QueryLogEntries`` without
duplicating ``/Thingworx`` when the base URL already ends with ``/Thingworx``.

Run from repo root::

  uv run get-application-log
  uv run get-application-log --full
  uv run get-application-log --no-indent
  uv run get-application-log --end-date 2026-04-16T00:13:41.196Z --max-items 50
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"

HTTP_TIMEOUT_S = 120
SERVICE_PATH = "/Thingworx/Logs/ApplicationLog/Services/QueryLogEntries"
SLIM_KEYS = ("timestamp", "thread", "content")


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
        print(f"Missing required environment variable: {name} (set in {ENV_PATH})", file=sys.stderr)
        sys.exit(1)
    return v


def utc_now_iso_z() -> str:
    dt = datetime.now(timezone.utc)
    ms = dt.microsecond // 1000
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + f".{ms:03d}Z"


def parse_iso_z(s: str) -> datetime:
    t = s.strip()
    if t.endswith("Z"):
        t = t[:-1] + "+00:00"
    return datetime.fromisoformat(t).astimezone(timezone.utc)


def format_iso_z(dt: datetime) -> str:
    dt = dt.astimezone(timezone.utc)
    ms = dt.microsecond // 1000
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + f".{ms:03d}Z"


def normalize_timestamp(value: Any) -> str:
    """Platform often returns epoch milliseconds as a number; normalize to ISO UTC Z for readability."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, (int, float)):
        n = float(value)
        # ThingWorx commonly returns epoch **milliseconds** (13 digits for ~2020+); else treat as seconds.
        sec = n / 1000.0 if n >= 1e12 else n
        dt = datetime.fromtimestamp(sec, tz=timezone.utc)
        return format_iso_z(dt)
    return str(value).strip()


def cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def build_query_url(dev_server: str) -> str:
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    if low.endswith("/thingworx"):
        return base + "/Logs/ApplicationLog/Services/QueryLogEntries"
    return base + SERVICE_PATH


def default_payload(end_date: str, start_date: str, max_items: int) -> dict[str, Any]:
    return {
        "endDate": end_date,
        "fromLogLevel": "ALL",
        "instance": "",
        "isRegex": True,
        "maxItems": max_items,
        "oldestFirst": False,
        "origin": "",
        "searchExpression": ".*.*",
        "startDate": start_date,
        "thread": "",
        "toLogLevel": "ALL",
        "user": "",
    }


def post_json(url: str, app_key: str, payload: dict[str, Any]) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "appKey": app_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        print(f"HTTP {e.code} {e.reason}: {url}\n{err_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"Request failed: {e.reason}", file=sys.stderr)
        sys.exit(1)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        print(raw, file=sys.stderr)
        sys.exit(1)


def slim_rows(full: dict[str, Any]) -> list[dict[str, str]]:
    rows = full.get("rows")
    if not isinstance(rows, list):
        return []
    out: list[dict[str, str]] = []
    for r in rows:
        if not isinstance(r, dict):
            continue
        out.append(
            {
                "timestamp": normalize_timestamp(r.get("timestamp")),
                "thread": cell(r.get("thread")),
                "content": cell(r.get("content")),
            }
        )
    return out


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Query ThingWorx ApplicationLog (QueryLogEntries).")
    p.add_argument(
        "--end-date",
        dest="end_date",
        default=None,
        help='ISO end time in UTC with Z suffix, e.g. "2026-04-16T00:13:41.196Z" (default: now UTC)',
    )
    p.add_argument(
        "--start-date",
        dest="start_date",
        default=None,
        help="ISO start time in UTC with Z (default: endDate minus 5 minutes)",
    )
    p.add_argument(
        "--max-items",
        type=int,
        default=100,
        dest="max_items",
        help="max log rows (default: 100)",
    )
    p.add_argument(
        "--full",
        action="store_true",
        help="print the full platform JSON (includes dataShape and all columns)",
    )
    p.add_argument(
        "--no-indent",
        action="store_true",
        help="single-line JSON (smaller; default is indented=2 for readability)",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    load_dotenv(ENV_PATH)

    dev_server = require_env("DEV_SERVER")
    dev_key = require_env("DEV_KEY")

    end_date = args.end_date or utc_now_iso_z()
    if args.start_date:
        start_date = args.start_date
    else:
        end_dt = parse_iso_z(end_date)
        start_date = format_iso_z(end_dt - timedelta(minutes=5))

    url = build_query_url(dev_server)
    payload = default_payload(end_date, start_date, args.max_items)
    data = post_json(url, dev_key, payload)

    indent = None if args.no_indent else 2

    if args.full:
        print(json.dumps(data, indent=indent, ensure_ascii=False))
        return

    out = {
        "meta": {
            "startDate": start_date,
            "endDate": end_date,
            "maxItems": args.max_items,
            "returnedRows": len(data.get("rows") or []) if isinstance(data.get("rows"), list) else 0,
            "url": url,
            "columns": list(SLIM_KEYS),
        },
        "rows": slim_rows(data),
    }
    print(json.dumps(out, indent=indent, ensure_ascii=False))


if __name__ == "__main__":
    main()

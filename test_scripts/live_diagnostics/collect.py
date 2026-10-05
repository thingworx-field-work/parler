"""
Collect ApplicationLog, AgentMessageStream, AgentThing status, and bounded raw
configuration repository files into a timestamped diagnostics bundle.

Run from a directory that contains ``.env`` with ``DEV_SERVER`` and ``DEV_KEY``::

  parler-collect-live -o ./logs
  parler-collect-live --window 30m --conversation-id my_thread -o ./logs
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import quote

ENV_PATH = Path.cwd() / ".env"

EXIT_OK = 0
EXIT_MISSING_ENV = 2
EXIT_OUTPUT = 3
EXIT_ALL_FAILED = 4

REPOSITORY_COLLECTION_DIRS: dict[str, str] = {
    "taxonomies": "/taxonomies",
    "skills": "/skills",
    "playbooks": "/playbooks",
    "policies": "/policies",
    "tools": "/tools",
    "host-contexts": "/host-contexts",
}

STANDARD_REPOSITORY_FILE_PATHS: tuple[str, ...] = (
    "/taxonomies/identity-types.json",
    "/taxonomies/asset-types.json",
    "/taxonomies/type-taxonomy.md",
    "/tools/extended_tools.json",
    "/policies/invoke_service.json",
)

# Exact field names only (case-insensitive). Do **not** use substring ``token`` — it masks ``promptTokens`` and other
# LLM telemetry. Internal training bundles preserve evidence; only clearly credential-like keys are redacted.
_CREDENTIAL_FIELD_NAMES_LOWER: frozenset[str] = frozenset(
    {
        "appkey",
        "apikey",
        "authorization",
        "password",
        "secret",
        "access_token",
        "accesstoken",
        "refresh_token",
        "refreshtoken",
        "id_token",
        "client_secret",
        "clientsecret",
        "private_key",
        "privatekey",
        "x-api-key",
        "x_api_key",
    }
)


def load_dotenv(path: Path) -> None:
    """Load ``KEY=value`` entries from the current directory ``.env`` without overriding environment variables."""
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


def build_thing_service_url(dev_server: str, thing_name: str, service_name: str) -> str:
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    enc_thing = quote(thing_name, safe="")
    enc_svc = quote(service_name, safe="")
    suffix = f"/Things/{enc_thing}/Services/{enc_svc}"
    if low.endswith("/thingworx"):
        return base + suffix
    return base + "/Thingworx" + suffix


def build_file_repository_download_url(dev_server: str, repository: str, path_under_repo: str) -> str:
    """Build ``GET /Thingworx/FileRepositories/{repository}/{path...}`` with per-segment encoding."""
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    prefix = base if low.endswith("/thingworx") else base + "/Thingworx"
    enc_repo = quote(repository.strip(), safe="")
    parts = [
        quote(seg.strip(), safe="")
        for seg in path_under_repo.strip().strip("/").split("/")
        if seg.strip()
    ]
    suffix = f"/FileRepositories/{enc_repo}"
    if parts:
        suffix += "/" + "/".join(parts)
    return prefix + suffix


def extract_rows_from_service_result(data: Any) -> list[dict[str, Any]]:
    """Normalize ThingWorx REST InfoTable-style payloads to a list of row dicts."""
    if data is None:
        return []
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if not isinstance(data, dict):
        return []
    if isinstance(data.get("rows"), list):
        return [r for r in data["rows"] if isinstance(r, dict)]
    res = data.get("result")
    if isinstance(res, dict) and isinstance(res.get("rows"), list):
        return [r for r in res["rows"] if isinstance(r, dict)]
    return []


def _cell_to_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return str(value)


def extract_scalar_result(data: Any) -> str | None:
    """
    ThingWorx REST returns scalar services as a raw JSON string, ``{"result": "..."}``,
    or an InfoTable-shaped object with ``rows: [{ "result": "..." }]``.
    """
    if data is None:
        return None
    if isinstance(data, str):
        return data
    if not isinstance(data, dict):
        return None
    if "result" in data:
        r = data.get("result")
        if isinstance(r, str):
            return r
        if r is not None and not isinstance(r, (dict, list)):
            return str(r)
    rows = extract_rows_from_service_result(data)
    if len(rows) == 1 and "result" in rows[0]:
        return _cell_to_text(rows[0].get("result"))
    return None


def post_json(url: str, app_key: str, payload: dict[str, Any], *, timeout_s: float) -> Any:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
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
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        raise RuntimeError(f"HTTP {e.code} {e.reason}: {url}: {err_body[:2000]}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"URL error: {e.reason}: {url}") from e
    try:
        return json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError as e:
        raise RuntimeError(f"JSON decode: {e}: {url}: {raw[:2000]}") from e


def get_binary(url: str, app_key: str, *, timeout_s: float, max_bytes: int) -> bytes:
    req = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Accept": "application/octet-stream",
            "appKey": app_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            content_len = resp.headers.get("Content-Length")
            if content_len:
                try:
                    declared_len = int(content_len)
                except ValueError:
                    declared_len = None
                if declared_len is not None and declared_len > max_bytes:
                    raise RuntimeError(f"file exceeds max bytes before download: {declared_len}>{max_bytes}")
            raw = resp.read(max_bytes + 1)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        raise RuntimeError(f"HTTP {e.code} {e.reason}: {url}: {err_body[:2000]}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"URL error: {e.reason}: {url}") from e
    if len(raw) > max_bytes:
        raise RuntimeError(f"file exceeds max bytes after download: {len(raw)}>{max_bytes}")
    return raw


def parse_window(raw: str) -> timedelta:
    s = raw.strip()
    if not s:
        return timedelta(hours=1)
    low = s.lower()
    if low.endswith("h"):
        return timedelta(hours=int(low[:-1]))
    if low.endswith("m"):
        return timedelta(minutes=int(low[:-1]))
    return timedelta(minutes=int(s))


def build_application_log_url(dev_server: str, log_name: str) -> str:
    """
    Join ``DEV_SERVER`` with ApplicationLog ``QueryLogEntries`` path without duplicating ``/Thingworx``
    Uses the same ThingWorx path-joining rule as the legacy ApplicationLog helper.
    """
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    tail = f"/Logs/{log_name}/Services/QueryLogEntries"
    if low.endswith("/thingworx"):
        return base + tail
    return base + "/Thingworx" + tail


def format_iso_z(dt: datetime) -> str:
    dt = dt.astimezone(timezone.utc)
    ms = dt.microsecond // 1000
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + f".{ms:03d}Z"


def normalize_log_timestamp(value: Any) -> str:
    """
    Normalize ApplicationLog / platform timestamps to ISO UTC Z.

    Aligns with ``GetApplicationLog.normalize_timestamp`` (epoch ms vs seconds) and also accepts
    numeric strings (epoch ms or seconds) for stringified platform values.
    """
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, (int, float)):
        n = float(value)
        sec = n / 1000.0 if n >= 1e12 else n
        dt = datetime.fromtimestamp(sec, tz=timezone.utc)
        return format_iso_z(dt)
    if isinstance(value, str):
        s = value.strip()
        if not s:
            return ""
        if len(s) >= 20 and "T" in s[:15] and s.upper().endswith("Z"):
            return s
        try:
            as_num = float(s)
            if as_num == as_num:
                sec2 = as_num / 1000.0 if as_num >= 1e12 else as_num
                return format_iso_z(datetime.fromtimestamp(sec2, tz=timezone.utc))
        except ValueError:
            pass
        return s
    return str(value).strip()


def normalize_stream_timestamp(value: Any) -> str:
    """Stream row timestamp: same normalization as logs; missing values fall back to ``now`` (UTC)."""
    if value is None:
        return format_iso_z(datetime.now(timezone.utc))
    t = normalize_log_timestamp(value)
    return t if t else format_iso_z(datetime.now(timezone.utc))


def normalize_application_log_row(row: dict[str, Any]) -> dict[str, Any]:
    out = dict(row)
    if "timestamp" in out:
        out["timestamp"] = normalize_log_timestamp(out.get("timestamp"))
    return out


def collection_id() -> str:
    now = datetime.now(timezone.utc)
    return now.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:4]


def make_output_dir(parent: Path) -> Path:
    base = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    parent.mkdir(parents=True, exist_ok=True)
    candidate = parent / base
    if not candidate.exists():
        candidate.mkdir(parents=False, exist_ok=True)
        return candidate
    for i in range(2, 1000):
        alt = parent / f"{base}-{i}"
        if not alt.exists():
            alt.mkdir(parents=False, exist_ok=True)
            return alt
    raise OSError(f"could not allocate directory under {parent}")


def _key_is_secret(name: str) -> bool:
    if not isinstance(name, str) or not name.strip():
        return False
    return name.strip().lower() in _CREDENTIAL_FIELD_NAMES_LOWER


def redact_value(obj: Any) -> Any:
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for k, v in obj.items():
            if isinstance(k, str) and _key_is_secret(k):
                out[k] = "[REDACTED]"
            else:
                out[k] = redact_value(v)
        return out
    if isinstance(obj, list):
        return [redact_value(x) for x in obj]
    return obj


def _expand_tool_call_dict(d: dict[str, Any], pw: list[str], prefix: str) -> dict[str, Any]:
    """Expand JSON-encoded ``function.arguments`` / ``arguments`` strings into parsed objects for companions only."""
    out = dict(d)
    fn = out.get("function")
    if isinstance(fn, dict):
        fn_copy = dict(fn)
        arg = fn_copy.get("arguments")
        if isinstance(arg, str) and arg.strip():
            inner, errs = _safe_json_parse(arg)
            for e in errs:
                pw.append(f"{prefix}function.arguments:{e}")
            if inner is not None:
                fn_copy["arguments"] = inner
        out["function"] = fn_copy
    arg_top = out.get("arguments")
    if isinstance(arg_top, str) and arg_top.strip():
        inner, errs = _safe_json_parse(arg_top)
        for e in errs:
            pw.append(f"{prefix}arguments:{e}")
        if inner is not None:
            out["arguments"] = inner
    return out


def expand_tool_calls_for_companion(parsed: Any, pw: list[str], prefix: str = "toolCalls:") -> Any:
    """Parse nested JSON tool-argument strings for ``toolCallsJson`` readability (no content masking)."""
    if isinstance(parsed, list):
        return [
            expand_tool_calls_for_companion(x, pw, f"{prefix}[{i}]:") if isinstance(x, dict) else x
            for i, x in enumerate(parsed)
        ]
    if isinstance(parsed, dict):
        return _expand_tool_call_dict(parsed, pw, prefix)
    return parsed


def scalar_to_json_for_agent_status(
    text: str | None, warnings: list[str], warn_prefix: str
) -> Any:
    """Parse ThingWorx STRING JSON service results for structured ``agent-status.json`` ``json`` fields."""
    parsed, pw = _safe_json_parse(text)
    for w in pw:
        warnings.append(f"{warn_prefix}:{w}")
    return parsed


def _safe_json_parse(text: str | None) -> tuple[Any | None, list[str]]:
    warns: list[str] = []
    if text is None or not str(text).strip():
        return None, warns
    try:
        return json.loads(text), warns
    except json.JSONDecodeError as e:
        warns.append(f"json_parse_error:{e}")
        return None, warns


def extract_request_ids_from_stream_rows(rows: list[dict[str, Any]]) -> list[str]:
    ids: list[str] = []
    seen: set[str] = set()

    def add(v: Any) -> None:
        if isinstance(v, str) and v.strip() and v.strip() not in seen:
            seen.add(v.strip())
            ids.append(v.strip())

    for row in rows:
        for key in ("requestId", "request_id"):
            if key in row:
                add(row.get(key))
        vals = row.get("values")
        if isinstance(vals, dict):
            for key in ("requestId", "request_id"):
                if key in vals:
                    add(vals.get(key))
        for blob_key in ("content", "toolCalls", "llmUsageJson"):
            raw = row.get(blob_key)
            if isinstance(raw, str) and raw.strip():
                parsed, _ = _safe_json_parse(raw)
                if isinstance(parsed, dict):
                    for key in ("requestId", "request_id", "request_id".upper()):
                        if key in parsed:
                            add(parsed.get(key))
        raw_usage = row.get("llmUsageJson")
        if isinstance(raw_usage, str):
            parsed, _ = _safe_json_parse(raw_usage)
            if isinstance(parsed, dict):
                add(parsed.get("requestId") or parsed.get("request_id"))
    return ids


def build_log_search_regex(conversation_id: str | None, request_ids: list[str]) -> str:
    parts: list[str] = []
    if conversation_id and conversation_id.strip():
        parts.append(re.escape(conversation_id.strip()))
    for rid in request_ids:
        parts.append(re.escape(rid))
    if not parts:
        return ".*.*"
    return "(" + "|".join(parts) + ")"


def discover_agent_things(rows: list[dict[str, Any]], env_fallback: str) -> tuple[list[str], str]:
    found: list[str] = []
    seen: set[str] = set()

    def add(name: Any) -> None:
        if isinstance(name, str) and name.strip() and name.strip() not in seen:
            seen.add(name.strip())
            found.append(name.strip())

    for row in rows:
        vals = row.get("values")
        if isinstance(vals, Mapping):
            ag = vals.get("agentThing")
            if isinstance(ag, str):
                add(ag)
        ag2 = row.get("agentThing")
        if isinstance(ag2, str):
            add(ag2)
    mode = "stream_rows"
    if not found and env_fallback.strip():
        add(env_fallback.strip())
        mode = "env_fallback"
    return found, mode


def normalize_stream_row(row: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {"parseWarnings": []}
    pw: list[str] = out["parseWarnings"]

    vals_raw = row.get("values")
    vals: dict[str, Any] = vals_raw if isinstance(vals_raw, dict) else {}
    vals_out: dict[str, Any] = copy.deepcopy(vals) if isinstance(vals_raw, dict) else {}

    ts = row.get("timestamp")
    if ts is None:
        ts = vals.get("timestamp")
    out["timestamp"] = normalize_stream_timestamp(ts)

    if isinstance(vals_raw, dict):
        out["conversationId"] = vals.get("conversationId") or row.get("source") or ""
        out["agentThing"] = vals.get("agentThing") or row.get("agentThing")
        out["requestId"] = vals.get("requestId") or row.get("requestId")
    else:
        out["conversationId"] = row.get("source") or ""
        out["agentThing"] = row.get("agentThing")
        out["requestId"] = row.get("requestId")
    out["source"] = row.get("source")
    out["sourceType"] = row.get("sourceType")
    out["streamEntryId"] = row.get("id") or row.get("streamEntryId")
    out["role"] = row.get("role")
    out["assistantMessageId"] = vals.get("assistantMessageId") or row.get("assistantMessageId")

    def coalesce_field(key: str) -> Any:
        r = row.get(key)
        if isinstance(r, str) and r.strip():
            return r
        if isinstance(r, (dict, list)):
            return r
        vv = vals.get(key)
        if isinstance(vv, str) and vv.strip():
            return vv
        if isinstance(vv, (dict, list)):
            return vv
        if r is not None:
            return r
        return vv

    content_raw = coalesce_field("content")
    out["contentJson"] = None
    if isinstance(content_raw, (dict, list)):
        out["content"] = json.dumps(content_raw, ensure_ascii=False)
        out["contentJson"] = redact_value(copy.deepcopy(content_raw))
    else:
        out["content"] = content_raw if isinstance(content_raw, str) else ""
        if isinstance(content_raw, str):
            cr = content_raw.strip()
            if cr.startswith("{") or cr.startswith("["):
                parsed, errs = _safe_json_parse(content_raw)
                for e in errs:
                    pw.append(f"content:{e}")
                if parsed is not None:
                    out["contentJson"] = redact_value(parsed)

    tc_raw = coalesce_field("toolCalls")
    out["toolCallsJson"] = None
    if isinstance(tc_raw, (dict, list)):
        out["toolCalls"] = json.dumps(tc_raw, ensure_ascii=False)
        out["toolCallsJson"] = redact_value(expand_tool_calls_for_companion(copy.deepcopy(tc_raw), pw))
    elif isinstance(tc_raw, str):
        out["toolCalls"] = tc_raw
        if tc_raw.strip():
            parsed_tc, errs_tc = _safe_json_parse(tc_raw)
            for e in errs_tc:
                pw.append(f"toolCalls:{e}")
            if parsed_tc is not None:
                expanded = expand_tool_calls_for_companion(parsed_tc, pw)
                out["toolCallsJson"] = redact_value(expanded)
    else:
        out["toolCalls"] = ""

    usage_raw = coalesce_field("llmUsageJson")
    out["llmUsage"] = None
    if isinstance(usage_raw, (dict, list)):
        out["llmUsageJson"] = json.dumps(usage_raw, ensure_ascii=False)
        out["llmUsage"] = redact_value(copy.deepcopy(usage_raw))
    elif isinstance(usage_raw, str):
        out["llmUsageJson"] = usage_raw
        if usage_raw.strip():
            parsed_u, errs_u = _safe_json_parse(usage_raw)
            for e in errs_u:
                pw.append(f"llmUsageJson:{e}")
            if parsed_u is not None:
                out["llmUsage"] = redact_value(parsed_u)
    else:
        out["llmUsageJson"] = ""

    snap_raw = coalesce_field("hostContextSnapshotJson")
    out["hostContextSnapshot"] = None
    if isinstance(snap_raw, (dict, list)):
        out["hostContextSnapshotJson"] = json.dumps(snap_raw, ensure_ascii=False)
        out["hostContextSnapshot"] = redact_value(copy.deepcopy(snap_raw))
    elif isinstance(snap_raw, str):
        out["hostContextSnapshotJson"] = snap_raw
        if snap_raw.strip():
            parsed_snap, errs_snap = _safe_json_parse(snap_raw)
            for e in errs_snap:
                pw.append(f"hostContextSnapshotJson:{e}")
            if parsed_snap is not None:
                out["hostContextSnapshot"] = redact_value(parsed_snap)
    else:
        out["hostContextSnapshotJson"] = ""

    for fld in ("content", "toolCalls", "llmUsageJson", "hostContextSnapshotJson"):
        if fld not in vals_out or vals_out.get(fld) in (None, ""):
            vtop = row.get(fld)
            if vtop not in (None, ""):
                vals_out[fld] = vtop
    out["values"] = vals_out

    out["raw"] = dict(row)
    return out


def drift_checks_from_parsed_snapshot(parsed: Any) -> list[dict[str, Any]]:
    if not isinstance(parsed, dict):
        return []
    cfg = parsed.get("configurationRepository")
    if not isinstance(cfg, dict):
        return []
    files = cfg.get("files")
    if not isinstance(files, list):
        return []
    checks: list[dict[str, Any]] = []
    for ent in files:
        if not isinstance(ent, dict):
            continue
        path = ent.get("path")
        loaded = ent.get("loadedSha256")
        current = ent.get("sha256")
        if (
            isinstance(path, str)
            and isinstance(loaded, str)
            and isinstance(current, str)
            and loaded
            and current
            and loaded != current
        ):
            checks.append(
                {
                    "kind": "repository_file_hash_mismatch",
                    "path": path,
                    "loadedSha256": loaded,
                    "currentSha256": current,
                    "confidence": "hash",
                }
            )
    return checks


def safe_fs_component(raw: str, fallback: str = "_") -> str:
    s = raw.strip() if isinstance(raw, str) else ""
    if not s:
        return fallback
    out = re.sub(r"[^A-Za-z0-9._-]+", "_", s)
    out = out.strip("._")
    return out or fallback


def normalize_repository_path(raw: Any) -> str | None:
    if not isinstance(raw, str):
        return None
    s = raw.strip().replace("\\", "/")
    if not s:
        return None
    parts: list[str] = []
    for part in s.split("/"):
        if not part or part == ".":
            continue
        if part == "..":
            return None
        parts.append(part)
    return "/" + "/".join(parts) if parts else None


def local_path_for_repository_file(files_root: Path, repository_path: str) -> Path:
    norm = normalize_repository_path(repository_path)
    if norm is None:
        raise ValueError(f"invalid repository path: {repository_path!r}")
    parts = [safe_fs_component(seg) for seg in norm.strip("/").split("/") if seg]
    target = files_root.joinpath(*parts)
    root_resolved = files_root.resolve(strict=False)
    target_resolved = target.resolve(strict=False)
    if root_resolved not in (target_resolved.parent, *target_resolved.parents):
        raise ValueError(f"repository path escapes output root: {repository_path!r}")
    return target


def snapshot_repository_paths(parsed_snapshot: Any) -> set[str]:
    paths: set[str] = set(STANDARD_REPOSITORY_FILE_PATHS)
    if not isinstance(parsed_snapshot, dict):
        return paths
    cfg = parsed_snapshot.get("configurationRepository")
    if not isinstance(cfg, dict):
        return paths
    files = cfg.get("files")
    if not isinstance(files, list):
        return paths
    for ent in files:
        if not isinstance(ent, dict):
            continue
        p = normalize_repository_path(ent.get("path"))
        if p is not None:
            paths.add(p)
    return paths


def listing_row_name(row: dict[str, Any]) -> str | None:
    for key in ("name", "fileName", "filename", "file", "path"):
        val = row.get(key)
        if isinstance(val, str) and val.strip():
            leaf = val.strip().replace("\\", "/").rstrip("/").split("/")[-1]
            if leaf and leaf not in (".", ".."):
                return leaf
    return None


def listing_row_is_directory(row: dict[str, Any]) -> bool | None:
    for key in ("isDirectory", "isFolder", "directory", "folder"):
        val = row.get(key)
        if isinstance(val, bool):
            return val
        if isinstance(val, str) and val.strip().lower() in ("true", "false"):
            return val.strip().lower() == "true"
    for key in ("type", "fileType", "kind"):
        val = row.get(key)
        if isinstance(val, str):
            low = val.strip().lower()
            if low in ("directory", "folder", "dir", "d"):
                return True
            if low in ("file", "f"):
                return False
    return None


def candidate_paths_from_listing(logical_dir: str, rows: list[dict[str, Any]]) -> set[str]:
    paths: set[str] = set()
    base = REPOSITORY_COLLECTION_DIRS[logical_dir]
    for row in rows:
        name = listing_row_name(row)
        if not name:
            continue
        is_dir = listing_row_is_directory(row)
        if logical_dir == "skills":
            if is_dir is not False and "." not in name:
                paths.add(f"{base}/{name}/SKILL.md")
            continue
        if logical_dir == "playbooks":
            if is_dir is not False and "." not in name:
                paths.add(f"{base}/{name}/playbook.json")
            continue
        if is_dir is True:
            continue
        p = normalize_repository_path(f"{base}/{name}")
        if p is not None:
            paths.add(p)
    return paths


def snapshot_file_meta(parsed_snapshot: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(parsed_snapshot, dict):
        return {}
    cfg = parsed_snapshot.get("configurationRepository")
    if not isinstance(cfg, dict):
        return {}
    files = cfg.get("files")
    if not isinstance(files, list):
        return {}
    out: dict[str, dict[str, Any]] = {}
    for ent in files:
        if not isinstance(ent, dict):
            continue
        p = normalize_repository_path(ent.get("path"))
        if p is not None:
            out[p] = ent
    return out


def collect_repository_files_for_agent(
    *,
    dev_server: str,
    dev_key: str,
    out_dir: Path,
    agent_name: str,
    parsed_snapshot: Any,
    timeout_s: float,
    max_file_bytes: int,
    warnings: list[str],
) -> dict[str, Any] | None:
    if not isinstance(parsed_snapshot, dict):
        return None
    cfg = parsed_snapshot.get("configurationRepository")
    if not isinstance(cfg, dict):
        return None
    repo = cfg.get("thingName")
    if not isinstance(repo, str) or not repo.strip():
        return None

    safe_agent = safe_fs_component(agent_name, "agent")
    safe_repo = safe_fs_component(repo, "repository")
    repo_root = out_dir / safe_agent / "configurationRepository" / safe_repo
    files_root = repo_root / "files"
    listings_root = repo_root / "listings"
    files_root.mkdir(parents=True, exist_ok=True)
    listings_root.mkdir(parents=True, exist_ok=True)

    manifest: dict[str, Any] = {
        "schema": "parler.liveDiagnostics.configurationRepository",
        "schemaVersion": 1,
        "agentThing": agent_name,
        "configurationRepository": repo.strip(),
        "repositoryStatus": cfg.get("status"),
        "source": "GetAgentRuntimeSnapshot.configurationRepository.files + bounded FileRepository directory listing",
        "listingDirs": REPOSITORY_COLLECTION_DIRS,
        "maxFileBytes": max_file_bytes,
        "listings": {},
        "files": [],
    }

    candidate_paths = snapshot_repository_paths(parsed_snapshot)
    for logical, remote_dir in REPOSITORY_COLLECTION_DIRS.items():
        listing_entry: dict[str, Any] = {"path": remote_dir, "ok": False, "rows": []}
        try:
            url = build_thing_service_url(dev_server, repo.strip(), "BrowseDirectory")
            raw = post_json(url, dev_key, {"path": remote_dir}, timeout_s=timeout_s)
            rows = extract_rows_from_service_result(raw)
            listing_entry["ok"] = True
            listing_entry["rows"] = rows
            candidate_paths.update(candidate_paths_from_listing(logical, rows))
        except Exception as e:
            listing_entry["error"] = str(e)
            warnings.append(f"repository_listing_failed:{agent_name}:{repo}:{remote_dir}:{e}")
        listing_doc = redact_value(listing_entry)
        (listings_root / f"{logical}.json").write_text(
            json.dumps(listing_doc, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        manifest["listings"][logical] = {
            "path": remote_dir,
            "ok": bool(listing_entry.get("ok")),
            "rowCount": len(listing_entry.get("rows", [])) if isinstance(listing_entry.get("rows"), list) else 0,
            "localPath": f"listings/{logical}.json",
        }
        if "error" in listing_entry:
            manifest["listings"][logical]["error"] = listing_entry["error"]

    snap_meta = snapshot_file_meta(parsed_snapshot)
    for path in sorted(candidate_paths):
        meta = snap_meta.get(path, {})
        row: dict[str, Any] = {
            "repositoryPath": path,
            "status": "pending",
            "snapshotStatus": meta.get("status"),
            "snapshotExists": meta.get("exists"),
            "snapshotByteSize": meta.get("byteSize"),
            "snapshotSha256": meta.get("sha256"),
            "loadedSha256": meta.get("loadedSha256"),
            "loadedAtUtc": meta.get("loadedAtUtc"),
            "loadedPath": meta.get("loadedPath"),
        }
        try:
            target = local_path_for_repository_file(files_root, path)
            rel = target.relative_to(repo_root).as_posix()
            row["localPath"] = rel
        except Exception as e:
            row["status"] = "skipped_invalid_path"
            row["error"] = str(e)
            manifest["files"].append(row)
            warnings.append(f"repository_file_invalid_path:{agent_name}:{repo}:{path}:{e}")
            continue
        if meta.get("exists") is False or meta.get("status") in ("missing", "read_error"):
            row["status"] = "skipped_snapshot_not_present"
            manifest["files"].append(row)
            continue
        try:
            url = build_file_repository_download_url(dev_server, repo.strip(), path)
            raw = get_binary(url, dev_key, timeout_s=timeout_s, max_bytes=max_file_bytes)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            digest = hashlib.sha256(raw).hexdigest()
            row["status"] = "downloaded"
            row["byteSize"] = len(raw)
            row["downloadedSha256"] = digest
            row["matchesCurrentSnapshot"] = (row.get("snapshotSha256") == digest) if row.get("snapshotSha256") else None
            row["matchesLoadedSnapshot"] = (row.get("loadedSha256") == digest) if row.get("loadedSha256") else None
        except Exception as e:
            row["status"] = "download_failed"
            row["error"] = str(e)
            warnings.append(f"repository_file_download_failed:{agent_name}:{repo}:{path}:{e}")
        manifest["files"].append(row)

    (repo_root / "manifest.json").write_text(
        json.dumps(redact_value(manifest), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return {
        "ok": True,
        "configurationRepository": repo.strip(),
        "localPath": f"{safe_agent}/configurationRepository/{safe_repo}/manifest.json",
        "fileCount": len(manifest["files"]),
        "downloadedCount": sum(1 for f in manifest["files"] if f.get("status") == "downloaded"),
        "listingCount": len(REPOSITORY_COLLECTION_DIRS),
    }


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Collect ApplicationLog, AgentMessageStream, AgentThing status, and bounded repository files."
    )
    p.add_argument("--window", default="1h", help="Lookback duration: e.g. 15m, 1h, 2h, or integer minutes.")
    p.add_argument("--conversation-id", dest="conversation_id", default=None, help="Filter stream source / thread id.")
    p.add_argument(
        "-o",
        "--output-folder",
        dest="output_folder",
        required=True,
        help="Parent directory for the timestamped collection folder.",
    )
    args = p.parse_args(argv)

    load_dotenv(ENV_PATH)
    dev_server = os.environ.get("DEV_SERVER", "").strip()
    dev_key = os.environ.get("DEV_KEY", "").strip()
    if not dev_server or not dev_key:
        print(f"Missing DEV_SERVER or DEV_KEY (set in {ENV_PATH})", file=sys.stderr)
        return EXIT_MISSING_ENV

    out_parent = Path(args.output_folder).expanduser()
    try:
        out_dir = make_output_dir(out_parent)
    except OSError as e:
        print(str(e), file=sys.stderr)
        return EXIT_OUTPUT

    window_td = parse_window(args.window)
    collection_started = datetime.now(timezone.utc)
    end = collection_started
    start = end - window_td
    start_s = format_iso_z(start)
    end_s = format_iso_z(end)
    coll_id = collection_id()
    started_wall = format_iso_z(collection_started)

    stream_thing = os.environ.get("PARLER_DIAG_STREAM_THING", "AgentMessageStream").strip() or "AgentMessageStream"
    log_name = os.environ.get("PARLER_DIAG_LOG_NAME", "ApplicationLog").strip() or "ApplicationLog"
    max_items = int(os.environ.get("PARLER_DIAG_MAX_ITEMS", "20000").strip() or "20000")
    timeout_s = float(os.environ.get("PARLER_DIAG_TIMEOUT_S", "120").strip() or "120")
    agent_fallback = os.environ.get("PARLER_DIAG_AGENT_THING", "").strip()
    collect_repo_files = os.environ.get("PARLER_DIAG_COLLECT_REPOSITORY_FILES", "true").strip().lower() not in (
        "0",
        "false",
        "no",
    )
    repository_file_max_bytes = int(
        os.environ.get("PARLER_DIAG_REPOSITORY_FILE_MAX_BYTES", str(1024 * 1024)).strip() or str(1024 * 1024)
    )

    successes = 0
    warnings: list[str] = []

    # --- Stream ---
    stream_rows: list[dict[str, Any]] = []
    stream_meta: dict[str, Any] = {
        "thingName": stream_thing,
        "primaryService": "QueryStreamEntriesWithData",
        "fallbackService": None,
        "maxItems": max_items,
    }
    stream_url = build_thing_service_url(dev_server, stream_thing, "QueryStreamEntriesWithData")
    stream_payload: dict[str, Any] = {
        "startDate": start_s,
        "endDate": end_s,
        "oldestFirst": True,
        "maxItems": max_items,
    }
    if args.conversation_id and args.conversation_id.strip():
        stream_payload["source"] = args.conversation_id.strip()
    try:
        stream_data = post_json(stream_url, dev_key, stream_payload, timeout_s=timeout_s)
        stream_rows = extract_rows_from_service_result(stream_data)
        successes += 1
    except Exception as e:
        warnings.append(f"stream_primary_failed:{e}")
        stream_meta["fallbackService"] = "QueryStreamData"
        fb_url = build_thing_service_url(dev_server, stream_thing, "QueryStreamData")
        fb_payload = dict(stream_payload)
        try:
            stream_data = post_json(fb_url, dev_key, fb_payload, timeout_s=timeout_s)
            stream_rows = extract_rows_from_service_result(stream_data)
            successes += 1
        except Exception as e2:
            warnings.append(f"stream_fallback_failed:{e2}")

    norm_rows = [normalize_stream_row(r) for r in stream_rows if isinstance(r, dict)]
    request_ids = extract_request_ids_from_stream_rows(stream_rows)
    agent_things, disc_mode = discover_agent_things(stream_rows, agent_fallback)

    # --- Application log ---
    log_rows: list[dict[str, Any]] = []
    log_filter_mode = "all"
    search_expression = ".*.*"
    if args.conversation_id and args.conversation_id.strip():
        log_filter_mode = "conversation_text_best_effort"
        search_expression = build_log_search_regex(args.conversation_id, request_ids)
    log_url = build_application_log_url(dev_server, log_name)
    log_payload = {
        "startDate": start_s,
        "endDate": end_s,
        "fromLogLevel": "ALL",
        "toLogLevel": "ALL",
        "instance": "",
        "origin": "",
        "thread": "",
        "user": "",
        "isRegex": True,
        "maxItems": max_items,
        "searchExpression": search_expression,
        "oldestFirst": False,
    }
    try:
        log_data = post_json(log_url, dev_key, log_payload, timeout_s=timeout_s)
        log_rows_raw = extract_rows_from_service_result(log_data)
        log_rows = [normalize_application_log_row(r) for r in log_rows_raw if isinstance(r, dict)]
        successes += 1
    except Exception as e:
        warnings.append(f"application_log_failed:{e}")

    # --- Agent status ---
    agents_out: list[dict[str, Any]] = []
    snap_opts = {
        "includePrompt": False,
        "includeSkills": True,
        "includeTools": True,
        "includePolicies": True,
        "includeTaxonomy": True,
        "includePlaybooks": True,
        "includeRepositoryFiles": True,
        "refresh": False,
    }
    for agent_name in agent_things:
        entry: dict[str, Any] = {"agentThing": agent_name, "driftChecks": []}
        parsed: Any = None
        snap_url = build_thing_service_url(dev_server, agent_name, "GetAgentRuntimeSnapshot")
        try:
            raw = post_json(snap_url, dev_key, {"options": json.dumps(snap_opts, separators=(",", ":"))}, timeout_s=timeout_s)
            snap_text = extract_scalar_result(raw)
            parsed = scalar_to_json_for_agent_status(
                snap_text if isinstance(snap_text, str) else None,
                warnings,
                f"runtime_snapshot_json_parse:{agent_name}",
            )
            entry["runtimeSnapshot"] = {"service": "GetAgentRuntimeSnapshot", "refresh": False, "ok": True, "json": parsed}
            entry["driftChecks"] = drift_checks_from_parsed_snapshot(parsed)
            if isinstance(parsed, dict):
                playbooks = parsed.get("playbooks")
                if isinstance(playbooks, dict):
                    last_run = playbooks.get("lastRun")
                    if last_run is not None:
                        entry["lastPlaybookRun"] = last_run
            successes += 1
        except Exception as e:
            entry["runtimeSnapshot"] = {
                "service": "GetAgentRuntimeSnapshot",
                "refresh": False,
                "ok": False,
                "error": str(e),
            }
            warnings.append(f"runtime_snapshot_failed:{agent_name}:{e}")

        val_url = build_thing_service_url(dev_server, agent_name, "ValidateAgentConfigurationRepository")
        try:
            raw_v = post_json(val_url, dev_key, {"repositoryName": ""}, timeout_s=timeout_s)
            val_text = extract_scalar_result(raw_v)
            val_parsed = scalar_to_json_for_agent_status(
                val_text if isinstance(val_text, str) else None,
                warnings,
                f"validate_repo_json_parse:{agent_name}",
            )
            entry["configurationRepositoryValidation"] = {
                "service": "ValidateAgentConfigurationRepository",
                "ok": True,
                "json": val_parsed,
            }
            if isinstance(val_parsed, dict):
                playbook_vals = val_parsed.get("playbookValidations")
                if playbook_vals is not None:
                    entry["playbookDocumentValidations"] = playbook_vals
            successes += 1
        except Exception as e:
            entry["configurationRepositoryValidation"] = {
                "service": "ValidateAgentConfigurationRepository",
                "ok": False,
                "error": str(e),
            }
            warnings.append(f"validate_repo_failed:{agent_name}:{e}")

        tax_url = build_thing_service_url(dev_server, agent_name, "GetTaxonomyDiagnostics")
        try:
            raw_t = post_json(tax_url, dev_key, {}, timeout_s=timeout_s)
            tax_text = extract_scalar_result(raw_t)
            tax_parsed = scalar_to_json_for_agent_status(
                tax_text if isinstance(tax_text, str) else None,
                warnings,
                f"taxonomy_diagnostics_json_parse:{agent_name}",
            )
            entry["taxonomyDiagnostics"] = {"service": "GetTaxonomyDiagnostics", "ok": True, "json": tax_parsed}
            successes += 1
        except Exception as e:
            entry["taxonomyDiagnostics"] = {"service": "GetTaxonomyDiagnostics", "ok": False, "error": str(e)}
            warnings.append(f"taxonomy_diagnostics_failed:{agent_name}:{e}")

        if collect_repo_files and parsed is not None:
            try:
                repo_collection = collect_repository_files_for_agent(
                    dev_server=dev_server,
                    dev_key=dev_key,
                    out_dir=out_dir,
                    agent_name=agent_name,
                    parsed_snapshot=parsed,
                    timeout_s=timeout_s,
                    max_file_bytes=repository_file_max_bytes,
                    warnings=warnings,
                )
                if repo_collection is not None:
                    entry["configurationRepositoryCollection"] = repo_collection
                    successes += 1
            except Exception as e:
                entry["configurationRepositoryCollection"] = {"ok": False, "error": str(e)}
                warnings.append(f"repository_collection_failed:{agent_name}:{e}")
        elif not collect_repo_files:
            entry["configurationRepositoryCollection"] = {"ok": False, "skipped": "disabled"}

        agents_out.append(entry)

    finished_wall = format_iso_z(datetime.now(timezone.utc))

    common_meta = {
        "collectionId": coll_id,
        "collectionStartedAt": started_wall,
        "collectionFinishedAt": finished_wall,
        "window": {"duration": args.window, "startDate": start_s, "endDate": end_s},
        "conversationId": args.conversation_id,
    }

    app_log_doc = redact_value(
        {
            "schema": "parler.liveDiagnostics.applicationLog",
            "schemaVersion": 1,
            "meta": {
                **common_meta,
                "service": {
                    "kind": log_name,
                    "maxItems": max_items,
                    "filterMode": log_filter_mode,
                    "requestIdsFromStream": request_ids,
                },
                "returnedRows": len(log_rows),
                "truncatedByMaxItems": len(log_rows) >= max_items,
            },
            "warnings": list(warnings),
            "rows": log_rows,
        }
    )

    stream_doc = redact_value(
        {
            "schema": "parler.liveDiagnostics.agentMessageStream",
            "schemaVersion": 1,
            "meta": {
                **common_meta,
                "service": stream_meta,
                "returnedRows": len(norm_rows),
                "truncatedByMaxItems": len(norm_rows) >= max_items,
            },
            "warnings": list(warnings),
            "rows": norm_rows,
        }
    )

    status_doc = redact_value(
        {
            "schema": "parler.liveDiagnostics.agentStatus",
            "schemaVersion": 1,
            "meta": {
                **common_meta,
                "agentThingDiscovery": {"mode": disc_mode, "agentThings": agent_things},
            },
            "warnings": list(warnings),
            "agents": agents_out,
        }
    )

    for name, doc in (
        ("application-log.json", app_log_doc),
        ("agent-message-stream.json", stream_doc),
        ("agent-status.json", status_doc),
    ):
        (out_dir / name).write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"Wrote {out_dir}")
    print(f"ApplicationLog rows: {len(log_rows)}")
    print(f"AgentMessageStream rows: {len(norm_rows)}")
    print(f"Agent status snapshots: {len(agents_out)}")
    if successes == 0:
        return EXIT_ALL_FAILED
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())

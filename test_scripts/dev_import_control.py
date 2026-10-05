"""
Load ``dev_data/import_control.yaml`` (or ``dev_data/<BASENAME>.yaml`` with ``--import_control``)
and print the import plan (order, actions, files).

With ``--apply``, resolves ``DEV_SERVER`` and app keys from ``.env``, then runs each step:
``import_file`` → ``/Thingworx/Importer``; ``import_data`` → ``/Thingworx/DataImporter`` (same query string);
``import_extension`` → ``/Thingworx/ExtensionPackageUploader``
(import endpoints use multipart field ``file``, appKey / CSRF / Accept headers).
``upload_file`` → ``/Thingworx/FileRepositoryUploader`` with form fields ``upload-repository``, ``upload-path``,
``upload-files`` (file part), ``upload-submit`` = ``Upload`` (same appKey / CSRF headers).
``upload_file_tree`` → recursive directory upload (same behavior as ``uv run load-file-tree``): ``source``
(local directory; repo-relative or absolute, same resolution as ``load-file-tree`` ``-i``), ``target``
(FileRepository Thing name optionally followed by a subdirectory, same as ``-t`` e.g.
``ConfigurationRepository/skills``), optional ``cleanup`` (default ``false``; when ``true``, clears the
remote target subdirectory before upload).
``include`` with ``import_control: BASENAME`` inlines the steps of ``dev_data/BASENAME.yaml`` at that
position (a missing file or an include cycle is an error).
``rest_call`` → ``{DEV_SERVER}{service}`` with optional ``method`` (default ``POST``), merged ``headers``,
body from ``payload_file`` (repo-relative path; file must contain valid JSON, deserialized then re-serialized)
when that field is non-empty, otherwise from ``payload`` (string or YAML object/array), and ``timeout`` in seconds (default 120).

Run from repository root:

  uv sync
  uv run import-dev
  uv run import-dev --apply
  uv run import-dev --import_control import_scpa_utilization
  uv run import-dev --apply --import_control import_scpa_utilization

``--import_control BASENAME`` loads ``dev_data/BASENAME.yaml`` instead of the default
``dev_data/import_control.yaml``. If ``BASENAME`` already ends with ``.yaml``, that
suffix is not doubled.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import secrets
import socket
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Literal

import yaml

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
DEFAULT_CONTROL_PATH = REPO_ROOT / "dev_data" / "import_control.yaml"
DEV_DATA_DIR = REPO_ROOT / "dev_data"


def resolve_control_yaml_path(import_control: str | None) -> Path:
    """
    Default: ``dev_data/import_control.yaml``.

    With ``import_control`` set to a basename (no path separators), use
    ``dev_data/<basename>.yaml`` (or ``dev_data/<name>`` if basename already ends in ``.yaml``).
    """
    if not import_control:
        return DEFAULT_CONTROL_PATH
    stem = import_control.strip()
    if not stem:
        raise ValueError("--import_control value must not be empty")
    if "/" in stem or "\\" in stem or stem.startswith("..") or Path(stem).name != stem:
        raise ValueError(
            "--import_control must be a single filename stem (no directories); "
            f"got {import_control!r}"
        )
    name = stem if stem.endswith(".yaml") else f"{stem}.yaml"
    return (DEV_DATA_DIR / name).resolve()


DEFAULT_ACTION = "import_file"
DEFAULT_APP_KEY = "DEV_KEY"

IMPORTER_QUERY = (
    "IgnoreBadValueStreamData=false"
    "&WithSubsystems=false"
    "&overwriteConfigurationTableValues=true"
    "&overwritePropertyValues=true"
    "&purpose=import"
    "&usedefaultdataprovider=false"
    "&usedefaultqueueprovider=false"
)

EXTENSION_UPLOADER_QUERY = "purpose=import&validate=false"

# Multipart part name matches browser / Gecko-style uploads (``name="file"``).
MULTIPART_FIELD_NAME = "file"
IMPORT_POST_TIMEOUT_S = 600

# ``rest_call``: methods accepted in YAML (normalized to uppercase for the request).
ALLOWED_HTTP_METHODS = frozenset(
    {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}
)
# No request body for these by default.
_METHODS_TYPICALLY_WITHOUT_BODY = frozenset({"GET", "HEAD", "OPTIONS", "TRACE"})

UploadKind = Literal["importer", "extension"]


def _part_content_type(file_path: Path, *, upload: UploadKind) -> str:
    suf = file_path.suffix.lower()
    if suf == ".xml":
        return "text/xml"
    if suf == ".zip":
        return (
            "application/x-zip-compressed"
            if upload == "extension"
            else "application/zip"
        )
    return "application/octet-stream"


def load_dotenv(path: Path) -> None:
    """Match ``reset_dev``: load repo ``.env`` without overriding."""
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


def importer_post_url(dev_server: str) -> str:
    base = dev_server.rstrip("/")
    return f"{base}/Thingworx/Importer?{IMPORTER_QUERY}"


def data_importer_post_url(dev_server: str) -> str:
    base = dev_server.rstrip("/")
    return f"{base}/Thingworx/DataImporter?{IMPORTER_QUERY}"


def extension_uploader_post_url(dev_server: str) -> str:
    base = dev_server.rstrip("/")
    return f"{base}/Thingworx/ExtensionPackageUploader?{EXTENSION_UPLOADER_QUERY}"


def file_repository_uploader_post_url(dev_server: str) -> str:
    base = dev_server.rstrip("/")
    return f"{base}/Thingworx/FileRepositoryUploader"


def rest_call_url(dev_server: str, service: str) -> str:
    """``url = {DEV_SERVER}/{service}`` — ``service`` is usually an absolute path like ``/Thingworx/...``."""
    base = dev_server.rstrip("/")
    path = service.strip()
    if not path.startswith("/"):
        path = "/" + path
    return base + path


def _multipart_body_single_file(file_path: Path, boundary: str, part_content_type: str) -> bytes:
    crlf = b"\r\n"
    file_bytes = file_path.read_bytes()
    disp = (
        f'Content-Disposition: form-data; name="{MULTIPART_FIELD_NAME}"; '
        f'filename="{file_path.name}"'
    )
    parts: list[bytes] = [
        f"--{boundary}".encode("ascii") + crlf,
        disp.encode("utf-8") + crlf,
        f"Content-Type: {part_content_type}".encode("ascii") + crlf,
        crlf,
        file_bytes + crlf,
        f"--{boundary}--".encode("ascii") + crlf,
    ]
    return b"".join(parts)


def normalize_repository_dest_path(path: str) -> str:
    """Path inside the FileRepository virtual tree, always rooted at ``/``."""
    s = path.strip().replace("\\", "/")
    while "//" in s:
        s = s.replace("//", "/")
    if not s:
        return "/"
    if not s.startswith("/"):
        s = "/" + s
    trimmed = s.rstrip("/")
    return trimmed if trimmed else "/"


def _mime_guess_upload(path: Path) -> str:
    mime, _ = mimetypes.guess_type(str(path))
    return mime or "application/octet-stream"


def _multipart_body_file_repository_upload(
    file_path: Path,
    boundary: str,
    *,
    repo_name: str,
    dest_path: str,
    part_content_type: str,
) -> bytes:
    """Match browser / FileRepositoryUploader field order: repository, path, file, submit."""
    crlf = b"\r\n"
    file_bytes = file_path.read_bytes()
    file_name = file_path.name
    parts: list[bytes] = []

    def add_text(name: str, value: str) -> None:
        parts.append(f"--{boundary}".encode("ascii") + crlf)
        parts.append(
            f'Content-Disposition: form-data; name="{name}"'.encode("utf-8") + crlf
        )
        parts.append(crlf)
        parts.append(value.encode("utf-8") + crlf)

    add_text("upload-repository", repo_name)
    add_text("upload-path", dest_path)
    disp_file = (
        f'Content-Disposition: form-data; name="upload-files"; '
        f'filename="{file_name}"'
    )
    parts.append(f"--{boundary}".encode("ascii") + crlf)
    parts.append(disp_file.encode("utf-8") + crlf)
    parts.append(f"Content-Type: {part_content_type}".encode("ascii") + crlf)
    parts.append(crlf)
    parts.append(file_bytes + crlf)
    add_text("upload-submit", "Upload")
    parts.append(f"--{boundary}--".encode("ascii") + crlf)
    return b"".join(parts)


def _post_thingworx_multipart(
    url: str,
    app_key_value: str,
    file_path: Path,
    *,
    part_content_type: str,
    endpoint_label: str,
    extra_success_http_codes: frozenset[int] = frozenset(),
) -> None:
    if not file_path.is_file():
        print(f"File not found: {file_path}", file=sys.stderr)
        sys.exit(1)

    boundary = f"----parler_{secrets.token_hex(12)}"
    body = _multipart_body_single_file(file_path, boundary, part_content_type)
    headers = {
        "appKey": app_key_value,
        "X-XSRF-TOKEN": "TWX-XSRF-TOKEN-VALUE",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "*/*",
        "Accept-Encoding": "gzip,deflate",
        "Connection": "keep-alive",
    }
    req = urllib.request.Request(url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=IMPORT_POST_TIMEOUT_S) as resp:
            code = int(resp.getcode())
            resp.read()
    except urllib.error.HTTPError as e:
        code = int(e.code)
        print(f"{endpoint_label} HTTP {code}", flush=True)
        err_body = e.read().decode("utf-8", errors="replace")
        if code in extra_success_http_codes:
            return
        snippet = err_body[:4000]
        print(
            f"{endpoint_label} body for {file_path.name}: {snippet}",
            file=sys.stderr,
        )
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"{endpoint_label} request failed for {file_path}: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"{endpoint_label} request failed for {file_path}: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"{endpoint_label} HTTP {code}", flush=True)
    if 200 <= code < 300 or code in extra_success_http_codes:
        return
    print(f"{endpoint_label} unexpected status {code} for {file_path}", file=sys.stderr)
    sys.exit(1)


def post_file_repository_upload(
    url: str,
    app_key_value: str,
    file_path: Path,
    *,
    repo_name: str,
    dest_path: str,
) -> None:
    if not file_path.is_file():
        print(f"File not found: {file_path}", file=sys.stderr)
        sys.exit(1)

    part_content_type = _mime_guess_upload(file_path)
    boundary = f"----parler_{secrets.token_hex(12)}"
    body = _multipart_body_file_repository_upload(
        file_path,
        boundary,
        repo_name=repo_name,
        dest_path=dest_path,
        part_content_type=part_content_type,
    )
    headers = {
        "appKey": app_key_value,
        "X-XSRF-TOKEN": "TWX-XSRF-TOKEN-VALUE",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "*/*",
        "Accept-Encoding": "gzip,deflate",
        "Connection": "keep-alive",
    }
    req = urllib.request.Request(url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)

    label = "FileRepositoryUploader"
    try:
        with urllib.request.urlopen(req, timeout=IMPORT_POST_TIMEOUT_S) as resp:
            code = resp.getcode()
            resp.read()
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        snippet = err_body[:4000]
        print(
            f"{label} HTTP {e.code} for {file_path.name}: {snippet}",
            file=sys.stderr,
        )
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"{label} request failed for {file_path}: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"{label} request failed for {file_path}: {e}", file=sys.stderr)
        sys.exit(1)

    if not (200 <= code < 300):
        print(f"{label} unexpected status {code} for {file_path}", file=sys.stderr)
        sys.exit(1)


def post_importer_import_file(url: str, app_key_value: str, file_path: Path) -> None:
    ct = _part_content_type(file_path, upload="importer")
    _post_thingworx_multipart(
        url,
        app_key_value,
        file_path,
        part_content_type=ct,
        endpoint_label="Importer",
    )


def post_data_importer_file(url: str, app_key_value: str, file_path: Path) -> None:
    ct = _part_content_type(file_path, upload="importer")
    _post_thingworx_multipart(
        url,
        app_key_value,
        file_path,
        part_content_type=ct,
        endpoint_label="DataImporter",
    )


def post_extension_package(url: str, app_key_value: str, file_path: Path) -> None:
    ct = _part_content_type(file_path, upload="extension")
    # ThingWorx may return HTTP 406 when the extension is accepted but requires a
    # platform restart to take effect; treat that as success for this upload step.
    _post_thingworx_multipart(
        url,
        app_key_value,
        file_path,
        part_content_type=ct,
        endpoint_label="ExtensionPackageUploader",
        extra_success_http_codes=frozenset({406}),
    )


def _parse_rest_headers(raw: Any, index: int) -> dict[str, Any]:
    """YAML ``headers`` as mapping or JSON string → flat dict (values may be non-str)."""
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return dict(raw)
    if isinstance(raw, str):
        s = raw.strip()
        if not s:
            return {}
        try:
            obj = json.loads(s)
        except json.JSONDecodeError as e:
            raise ValueError(
                f"import_order[{index}].headers must be valid JSON when provided as a string"
            ) from e
        if not isinstance(obj, dict):
            raise ValueError(
                f"import_order[{index}].headers JSON must be an object, got {type(obj).__name__}"
            )
        return dict(obj)
    raise ValueError(
        f"import_order[{index}].headers must be a mapping or JSON string, got {type(raw).__name__}"
    )


def _merge_rest_headers(app_key_value: str, user: dict[str, Any]) -> dict[str, str]:
    defaults: dict[str, str] = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "appKey": app_key_value,
    }
    merged: dict[str, str] = dict(defaults)
    for k, v in user.items():
        key = str(k)
        if v is None:
            merged[key] = ""
        elif isinstance(v, (dict, list)):
            merged[key] = json.dumps(v, separators=(",", ":"))
        else:
            merged[key] = str(v)
    return merged


def _primary_content_type(headers: dict[str, str]) -> str:
    for hk, hv in headers.items():
        if hk.lower() == "content-type":
            return hv.split(";")[0].strip().lower()
    return ""


def _validate_rest_payload_json_object(payload: str) -> None:
    try:
        obj = json.loads(payload)
    except json.JSONDecodeError as e:
        raise ValueError(
            "rest_call: payload must be valid JSON when Content-Type is application/json"
        ) from e
    if not isinstance(obj, dict):
        raise ValueError(
            "rest_call: payload must be a JSON object (mapping), not array or scalar"
        )


def _build_rest_body(
    method: str,
    payload: str,
    headers: dict[str, str],
) -> bytes | None:
    """``None`` means no entity body."""
    if method in _METHODS_TYPICALLY_WITHOUT_BODY and not payload.strip():
        return None
    ct = _primary_content_type(headers)
    if ct == "application/json":
        if not payload.strip():
            return b"{}"
        _validate_rest_payload_json_object(payload)
        return payload.encode("utf-8")
    if not payload:
        return None
    return payload.encode("utf-8")


def _print_rest_success_body(body_bytes: bytes, code: int) -> None:
    text = body_bytes.decode("utf-8", errors="replace")
    snippet = text[:20]
    suffix = "…" if len(text) > 20 else ""
    print(f"   OK HTTP {code} body (≤20 chars): {snippet!r}{suffix}", flush=True)


def execute_rest_call(
    *,
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout_s: float,
    label: str,
) -> None:
    req = urllib.request.Request(url, data=body, method=method)
    for hk, hv in headers.items():
        req.add_header(hk, hv)

    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            code = resp.getcode()
            body_bytes = resp.read()
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        msg = err_body.strip() or "(no response body)"
        print(
            f"{label} HTTP {e.code}: {msg[:4000]}",
            file=sys.stderr,
        )
        sys.exit(1)
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", e)
        if isinstance(reason, TimeoutError) or isinstance(reason, socket.timeout):
            print(f"{label} timeout after {timeout_s:g}s: {e}", file=sys.stderr)
            sys.exit(1)
        print(f"{label} network error: {reason}", file=sys.stderr)
        sys.exit(1)
    except TimeoutError as e:
        print(f"{label} timeout after {timeout_s:g}s: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"{label} request failed: {e}", file=sys.stderr)
        sys.exit(1)

    if not (200 <= code < 300):
        text = body_bytes.decode("utf-8", errors="replace").strip()
        msg = text or "(no response body)"
        print(
            f"{label} HTTP {code}: {msg[:4000]}",
            file=sys.stderr,
        )
        sys.exit(1)

    _print_rest_success_body(body_bytes, code)


def resolve_file_path(repo_root: Path, path_str: str) -> Path:
    p = Path(path_str)
    return p if p.is_absolute() else (repo_root / p).resolve()


def resolve_tree_source(repo_root: Path, path_str: str, *, index: int | None = None) -> Path:
    """Resolve ``source`` the same way as ``load-file-tree`` ``-i`` (repo root for relative paths)."""
    label = f"import_order[{index}]" if index is not None else "upload_file_tree"
    if not isinstance(path_str, str) or not path_str.strip():
        raise ValueError(f"{label}.source must be a non-empty string")
    resolved = resolve_file_path(repo_root, path_str.strip())
    if resolved.exists() and not resolved.is_dir():
        raise ValueError(f"{label}.source exists but is not a directory: {resolved}")
    if not resolved.is_dir():
        raise ValueError(f"{label}.source directory does not exist: {resolved}")
    return resolved


def _parse_cleanup_flag(raw: Any, index: int) -> bool:
    if raw is None:
        return False
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, str):
        s = raw.strip().lower()
        if s in ("true", "yes", "1"):
            return True
        if s in ("false", "no", "0", ""):
            return False
    raise ValueError(
        f"import_order[{index}].cleanup must be a boolean (true/false), got {raw!r}"
    )


def _read_rest_call_payload_file(repo_root: Path, path_str: str, index: int) -> str:
    """Read ``payload_file``, require valid JSON, return minified UTF-8 JSON string for the HTTP body."""
    resolved = resolve_file_path(repo_root, path_str.strip())
    if not resolved.is_file():
        raise ValueError(
            f"import_order[{index}] rest_call payload_file not found or not a file: {resolved}"
        )
    try:
        text = resolved.read_text(encoding="utf-8")
    except OSError as e:
        raise ValueError(
            f"import_order[{index}] rest_call payload_file read failed ({resolved}): {e}"
        ) from e
    if text.startswith("\ufeff"):
        text = text[1:]
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(
            f"import_order[{index}] rest_call payload_file must contain valid JSON ({resolved}): {e}"
        ) from e
    return json.dumps(obj, separators=(",", ":"))


def _normalize_item(raw: Any, index: int, repo_root: Path) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError(f"import_order[{index}] must be a mapping, got {type(raw).__name__}")
    action = raw.get("action")
    if action is None or (isinstance(action, str) and not action.strip()):
        action = DEFAULT_ACTION
    elif isinstance(action, str):
        action = action.strip()
    else:
        raise ValueError(f"import_order[{index}].action must be a string")

    app_key = raw.get("app_key")
    if app_key is None or (isinstance(app_key, str) and not app_key.strip()):
        app_key = DEFAULT_APP_KEY
    elif isinstance(app_key, str):
        app_key = app_key.strip()
    else:
        raise ValueError(f"import_order[{index}].app_key must be a string")

    files_raw = raw.get("files")
    if files_raw is None:
        files: list[str] = []
    elif isinstance(files_raw, list):
        files = []
        for j, p in enumerate(files_raw):
            if not isinstance(p, str) or not p.strip():
                raise ValueError(f"import_order[{index}].files[{j}] must be a non-empty string")
            files.append(p.strip())
    else:
        raise ValueError(f"import_order[{index}].files must be a list")

    out: dict[str, Any] = {"action": action, "app_key": app_key, "files": files}
    if action == "rest_call":
        service = raw.get("service")
        if not isinstance(service, str) or not service.strip():
            raise ValueError(
                f"import_order[{index}] action rest_call requires non-empty string `service`"
            )
        method_raw = raw.get("method", "POST")
        if not isinstance(method_raw, str) or not method_raw.strip():
            method = "POST"
        else:
            method = method_raw.strip().upper()
        if method not in ALLOWED_HTTP_METHODS:
            raise ValueError(
                f"import_order[{index}].method {method_raw!r} not allowed; "
                f"use one of: {', '.join(sorted(ALLOWED_HTTP_METHODS))}"
            )
        headers_user = _parse_rest_headers(raw.get("headers"), index)
        payload_file_raw = raw.get("payload_file")
        payload_source: str
        if payload_file_raw is not None:
            if not isinstance(payload_file_raw, str):
                raise ValueError(
                    f"import_order[{index}].payload_file must be a string when provided, "
                    f"got {type(payload_file_raw).__name__}"
                )
            if payload_file_raw.strip():
                payload = _read_rest_call_payload_file(repo_root, payload_file_raw, index)
                payload_source = f"file:{payload_file_raw.strip()}"
            else:
                payload_raw = raw.get("payload", "")
                if payload_raw is None:
                    payload = ""
                elif isinstance(payload_raw, str):
                    payload = payload_raw
                elif isinstance(payload_raw, (dict, list)):
                    payload = json.dumps(payload_raw, separators=(",", ":"))
                else:
                    raise ValueError(
                        f"import_order[{index}].payload must be a string or YAML object/array"
                    )
                payload_source = "inline"
        else:
            payload_raw = raw.get("payload", "")
            if payload_raw is None:
                payload = ""
            elif isinstance(payload_raw, str):
                payload = payload_raw
            elif isinstance(payload_raw, (dict, list)):
                payload = json.dumps(payload_raw, separators=(",", ":"))
            else:
                raise ValueError(
                    f"import_order[{index}].payload must be a string or YAML object/array"
                )
            payload_source = "inline"
        timeout_raw = raw.get("timeout", 120)
        if isinstance(timeout_raw, bool) or not isinstance(timeout_raw, (int, float)):
            raise ValueError(f"import_order[{index}].timeout must be a number (seconds)")
        timeout_s = float(timeout_raw)
        if timeout_s <= 0:
            raise ValueError(f"import_order[{index}].timeout must be positive")
        out.update(
            {
                "rest_service": service.strip(),
                "rest_method": method,
                "rest_headers_user": headers_user,
                "rest_payload": payload,
                "rest_payload_source": payload_source,
                "rest_timeout_s": timeout_s,
            }
        )
    elif action == "upload_file":
        fr = raw.get("file_repository")
        if not isinstance(fr, str) or not fr.strip():
            raise ValueError(
                f"import_order[{index}] action upload_file requires non-empty string `file_repository`"
            )
        path_raw = raw.get("path")
        if path_raw is None:
            raise ValueError(
                f"import_order[{index}] action upload_file requires string `path` (repository-relative)"
            )
        if not isinstance(path_raw, str) or not path_raw.strip():
            raise ValueError(
                f"import_order[{index}] action upload_file requires non-empty string `path`"
            )
        out["file_repository"] = fr.strip()
        out["dest_path"] = normalize_repository_dest_path(path_raw.strip())
    elif action == "upload_file_tree":
        source_raw = raw.get("source")
        if not isinstance(source_raw, str) or not source_raw.strip():
            raise ValueError(
                f"import_order[{index}] action upload_file_tree requires non-empty string `source`"
            )
        target_raw = raw.get("target")
        if not isinstance(target_raw, str) or not target_raw.strip():
            raise ValueError(
                f"import_order[{index}] action upload_file_tree requires non-empty string `target`"
            )
        from test_scripts.load_file_tree import parse_target

        try:
            target_repo, target_dir = parse_target(target_raw.strip())
        except ValueError as e:
            raise ValueError(f"import_order[{index}].target: {e}") from e
        out["source"] = source_raw.strip()
        out["target"] = target_raw.strip()
        out["target_repo"] = target_repo
        out["target_dir"] = target_dir
        out["cleanup"] = _parse_cleanup_flag(raw.get("cleanup"), index)
    return out


PUBLIC_EXAMPLE_CONTROL = "import_scpa_utilization"


def load_import_plan(
    control_path: Path, repo_root: Path, _including: tuple[Path, ...] = ()
) -> list[dict[str, Any]]:
    if not control_path.is_file():
        hint = ""
        if control_path == DEFAULT_CONTROL_PATH:
            hint = (
                "\nThe default control file is a local working file. Name a shipped one instead, "
                f"for example: uv run import-dev --import_control {PUBLIC_EXAMPLE_CONTROL}"
            )
        raise FileNotFoundError(f"Missing import control file: {control_path}{hint}")
    if control_path in _including:
        chain = " -> ".join(p.name for p in (*_including, control_path))
        raise ValueError(f"include cycle: {chain}")

    data = yaml.safe_load(control_path.read_text(encoding="utf-8"))
    if data is None:
        return []
    if not isinstance(data, dict):
        raise ValueError("Root of import_control.yaml must be a mapping")
    order = data.get("import_order")
    if order is None:
        return []
    if not isinstance(order, list):
        raise ValueError("import_order must be a list")

    plan: list[dict[str, Any]] = []
    for i, item in enumerate(order):
        if isinstance(item, dict) and item.get("action") == "include":
            name = item.get("import_control")
            if not isinstance(name, str) or not name.strip():
                raise ValueError(
                    f"import_order[{i}] action include requires non-empty string `import_control`"
                )
            plan.extend(
                load_import_plan(
                    resolve_control_yaml_path(name), repo_root, (*_including, control_path)
                )
            )
        else:
            plan.append(_normalize_item(item, i, repo_root))
    return plan


def process_plan(plan: list[dict[str, Any]], repo_root: Path, *, apply: bool) -> None:
    dev_server = require_env("DEV_SERVER") if apply else ""
    importer_url = importer_post_url(dev_server) if apply else ""
    data_importer_url = data_importer_post_url(dev_server) if apply else ""
    extension_url = extension_uploader_post_url(dev_server) if apply else ""
    file_repo_upload_url = file_repository_uploader_post_url(dev_server) if apply else ""

    print(f"Import plan ({len(plan)} step(s)), repo root: {repo_root}")
    print("=" * 72)

    for step_idx, step in enumerate(plan, start=1):
        env_var_name = step["app_key"]
        action = step["action"]

        print(
            f"{step_idx}. action={action!r}  app_key={env_var_name!r}  (.env variable name)",
            flush=True,
        )

        if action == "rest_call":
            svc = step["rest_service"]
            method = step["rest_method"]
            timeout_s = step["rest_timeout_s"]
            print(f"   service: {svc}", flush=True)
            print(f"   method: {method}", flush=True)
            print(f"   payload: {step.get('rest_payload_source', 'inline')}", flush=True)
            print(f"   timeout: {timeout_s:g}s", flush=True)
            merge_key = (
                require_env(env_var_name)
                if apply
                else (os.environ.get(env_var_name, "").strip() or "__DRY_RUN__")
            )
            headers = _merge_rest_headers(merge_key, step["rest_headers_user"])
            try:
                body = _build_rest_body(method, step["rest_payload"], headers)
            except ValueError as e:
                print(str(e), file=sys.stderr)
                sys.exit(1)
            ds_show = (
                dev_server.rstrip("/")
                if apply
                else (os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>")
            )
            full_url = rest_call_url(ds_show, svc)
            if apply:
                print(
                    f"   execute: appKey header from merged headers (from .env {env_var_name!r})",
                    flush=True,
                )
                print(f"   {method} {full_url}", flush=True)
                app_key_value = require_env(env_var_name)
                headers = _merge_rest_headers(app_key_value, step["rest_headers_user"])
                body = _build_rest_body(method, step["rest_payload"], headers)
                execute_rest_call(
                    url=full_url,
                    method=method,
                    headers=headers,
                    body=body,
                    timeout_s=timeout_s,
                    label="REST",
                )
            else:
                print(f"   [dry-run] would {method} -> {full_url}", flush=True)
                if body is None:
                    print("   [dry-run] (no request body)", flush=True)
                else:
                    print(f"   [dry-run] body bytes: {len(body)}", flush=True)
            continue

        if action == "upload_file":
            print(f"   file_repository: {step['file_repository']}", flush=True)
            print(f"   path: {step['dest_path']}", flush=True)

        if action == "upload_file_tree":
            try:
                source_resolved = resolve_tree_source(repo_root, step["source"], index=step_idx - 1)
            except ValueError as e:
                print(str(e), file=sys.stderr)
                sys.exit(1)
            target_display = (
                f"{step['target_repo']}{step['target_dir']}"
                if step["target_dir"] != "/"
                else step["target_repo"]
            )
            print(f"   source: {step['source']}", flush=True)
            print(f"   -> {source_resolved}", flush=True)
            print(f"   target: {step['target']}", flush=True)
            print(f"   -> repo={step['target_repo']!r} path={step['target_dir']!r}", flush=True)
            print(f"   cleanup: {step['cleanup']}", flush=True)
            if apply:
                app_key_value = require_env(env_var_name)
                print(
                    f"   execute: appKey from .env[{env_var_name!r}] (length {len(app_key_value)})",
                    flush=True,
                )
                from test_scripts.load_file_tree import (
                    clean_repository_directory,
                    upload_tree,
                )

                if step["cleanup"]:
                    clean_repository_directory(
                        dev_server=dev_server,
                        app_key=app_key_value,
                        repo=step["target_repo"],
                        list_path=step["target_dir"],
                    )
                upload_tree(
                    dev_server=dev_server,
                    app_key=app_key_value,
                    repo=step["target_repo"],
                    base=step["target_dir"],
                    input_dir=source_resolved,
                )
                print(f"   OK upload_file_tree → {target_display}", flush=True)
            else:
                ds = os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>"
                print(f"   [dry-run] would upload tree → {target_display} @ {ds}", flush=True)
                if step["cleanup"]:
                    print(
                        f"   [dry-run] would clean remote {step['target_dir']!r} under "
                        f"{step['target_repo']!r} first",
                        flush=True,
                    )
            continue

        files = step["files"]
        if not files:
            print("   (no files)", flush=True)
        else:
            print("   files:", flush=True)
            for fp in files:
                resolved = resolve_file_path(repo_root, fp)
                print(f"   - {fp}", flush=True)
                print(f"     -> {resolved}", flush=True)

        if apply:
            app_key_value = require_env(env_var_name)
            print(
                f"   execute: appKey from .env[{env_var_name!r}] (length {len(app_key_value)})",
                flush=True,
            )

        if action == "import_file":
            if not step["files"]:
                print("   (no files; skipping)", flush=True)
                continue
            for fp in step["files"]:
                resolved = resolve_file_path(repo_root, fp)
                if apply:
                    print(f"   POST Importer: {resolved.name}", flush=True)
                    post_importer_import_file(importer_url, app_key_value, resolved)
                    print(f"   OK {resolved.name} (HTTP 2xx)", flush=True)
                else:
                    ds = os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>"
                    print(f"   [dry-run] would POST -> {importer_post_url(ds)}", flush=True)
                    print(f"             file: {resolved}", flush=True)
            continue

        if action == "import_data":
            if not step["files"]:
                print("   (no files; skipping)", flush=True)
                continue
            for fp in step["files"]:
                resolved = resolve_file_path(repo_root, fp)
                if apply:
                    print(f"   POST DataImporter: {resolved.name}", flush=True)
                    post_data_importer_file(data_importer_url, app_key_value, resolved)
                    print(f"   OK {resolved.name} (HTTP 2xx)", flush=True)
                else:
                    ds = os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>"
                    print(f"   [dry-run] would POST -> {data_importer_post_url(ds)}", flush=True)
                    print(f"             file: {resolved}", flush=True)
            continue

        if action == "import_extension":
            if not step["files"]:
                print("   (no files; skipping)", flush=True)
                continue
            for fp in step["files"]:
                resolved = resolve_file_path(repo_root, fp)
                if apply:
                    print(f"   POST ExtensionPackageUploader: {resolved.name}", flush=True)
                    post_extension_package(extension_url, app_key_value, resolved)
                    print(f"   OK {resolved.name} (HTTP 2xx)", flush=True)
                else:
                    ds = os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>"
                    print(f"   [dry-run] would POST -> {extension_uploader_post_url(ds)}", flush=True)
                    print(f"             file: {resolved}", flush=True)
            continue

        if action == "upload_file":
            if not step["files"]:
                print("   (no files; skipping)", flush=True)
                continue
            for fp in step["files"]:
                resolved = resolve_file_path(repo_root, fp)
                if apply:
                    print(
                        f"   POST FileRepositoryUploader: {resolved.name} "
                        f"→ repo={step['file_repository']!r} path={step['dest_path']!r}",
                        flush=True,
                    )
                    post_file_repository_upload(
                        file_repo_upload_url,
                        app_key_value,
                        resolved,
                        repo_name=step["file_repository"],
                        dest_path=step["dest_path"],
                    )
                    print(f"   OK {resolved.name} (HTTP 2xx)", flush=True)
                else:
                    ds = os.environ.get("DEV_SERVER", "").strip().rstrip("/") or "<DEV_SERVER from .env>"
                    print(f"   [dry-run] would POST -> {file_repository_uploader_post_url(ds)}", flush=True)
                    print(f"             file: {resolved}", flush=True)
            continue

        print(f"   skip: unknown action {action!r} (not implemented).", flush=True)


def main() -> None:
    p = argparse.ArgumentParser(
        description="Dev import control: plan from YAML; --apply runs Importer / DataImporter / ExtensionPackageUploader / FileRepositoryUploader / upload_file_tree / rest_call.",
    )
    p.add_argument(
        "--apply",
        action="store_true",
        help="Run import_file, import_data, import_extension, upload_file, upload_file_tree, and rest_call steps (requires DEV_SERVER and app keys in .env).",
    )
    p.add_argument(
        "--import_control",
        metavar="BASENAME",
        default=None,
        help=(
            "Use dev_data/BASENAME.yaml instead of dev_data/import_control.yaml "
            "(BASENAME without .yaml; a trailing .yaml on BASENAME is accepted and not doubled)."
        ),
    )
    args = p.parse_args()

    env_path = REPO_ROOT / ".env"
    load_dotenv(env_path)

    if args.import_control is not None:
        try:
            control = resolve_control_yaml_path(args.import_control)
        except ValueError as e:
            print(str(e), file=sys.stderr)
            sys.exit(2)
    else:
        control = Path(os.environ.get("IMPORT_CONTROL_PATH", str(DEFAULT_CONTROL_PATH))).expanduser()

    try:
        plan = load_import_plan(control, REPO_ROOT)
    except (FileNotFoundError, ValueError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)

    process_plan(plan, REPO_ROOT, apply=args.apply)


if __name__ == "__main__":
    main()

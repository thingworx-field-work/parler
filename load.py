#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = [
#     "pyyaml>=6.0",
# ]
# ///
"""
ThingWorx import control executor (standalone PEP 723 script).

Usage:
    uv run load.py -c ./import-control.yaml              # dry-run: print plan
    uv run load.py -c ./import-control.yaml --apply      # execute the plan
    uv run load.py -c ./import-control.yaml --apply --resume 47
                                                         # execute from 1-based step 47
    uv run load.py --config-file custom.yaml --apply     # use custom control file

Requires explicit control file via -c/--config-file parameter.
Supports: import_extension, import_file, upload_file_tree, rest_call, import_data,
          upload_file, download_file_tree, get, export_entity, export_project

files, payload_file, and upload_file_tree source accept a local path or an
http(s) URL. A URL is downloaded first. upload_file_tree source may be a
local directory, a local .zip, or a zip URL. A zip is extracted locally and
each member is uploaded at its path inside the archive.

import_data accepts files_zipped, one local or release zip. Members are
extracted and imported as .twx files, in path order, before files.

A GitHub release download URL may use * in the file name only, for example
.../releases/download/v1.0.0/parler-agent-*.zip or
.../releases/latest/download/parler-agent-*.zip. * matches any sequence in
that file name, case-sensitively. The pattern must match exactly one uploaded
asset. Source-code archives are not assets and do not count. Dry-run checks
every such URL, including steps --resume would skip, and does not download
the file or contact ThingWorx.

{{{env:NAME}}} in a rest_call payload_file, or in an import_file .xml, is
replaced at apply time. NAME is an uppercase environment variable. Other
{{...}} text, including skill slots such as {{assetId}}, is left unchanged.
Values come from --from-env files (later flags win), then .env, then the
process environment. That lookup does not change TWX_URL or TWX_APPKEY.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import mimetypes
import os
import re
import secrets
import shutil
import socket
import ssl
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import yaml

# ============================================================================
# Configuration
# ============================================================================

HTTP_TIMEOUT_S = 600
XSRF_TOKEN = "TWX-XSRF-TOKEN-VALUE"
REMOTE_DOWNLOAD_USER_AGENT = "local-dev-load.py"
PLACEHOLDER_RE = re.compile(r"(?<!\{)\{\{\{env:([A-Z_][A-Z0-9_]*)\}\}\}(?!\})")

# Create SSL context that allows self-signed certificates
SSL_CONTEXT = ssl.create_default_context()
SSL_CONTEXT.check_hostname = False
SSL_CONTEXT.verify_mode = ssl.CERT_NONE

IMPORTER_QUERY = (
    "IgnoreBadValueStreamData=false"
    "&WithSubsystems=false"
    "&overwriteConfigurationTableValues=true"
    "&overwritePropertyValues=true"
    "&purpose=import"
    "&usedefaultdataprovider=false"
    "&usedefaultqueueprovider=false"
)

# ============================================================================
# Entity type definitions
# ============================================================================

# ThingWorx entity wire names (singular, plural)
ENTITY_WIRE_NAMES: tuple[tuple[str, str], ...] = (
    ("ApplicationKey", "ApplicationKeys"),
    ("Authenticator", "Authenticators"),
    ("Dashboard", "Dashboards"),
    ("DataShape", "DataShapes"),
    ("DataTagVocabulary", "DataTags"),
    ("DirectoryService", "DirectoryServices"),
    ("ExtensionPackage", "ExtensionPackages"),
    ("Group", "Groups"),
    ("LocalizationTable", "LocalizationTables"),
    ("Log", "Logs"),
    ("Mashup", "Mashups"),
    ("MediaEntity", "MediaEntities"),
    ("Menu", "Menus"),
    ("ModelTagVocabulary", "ModelTags"),
    ("MCPNamespace", "MCPNamespaces"),
    ("Network", "Networks"),
    ("NotificationContent", "NotificationContents"),
    ("NotificationDefinition", "NotificationDefinitions"),
    ("Organization", "Organizations"),
    ("PersistenceProviderPackage", "PersistenceProviderPackages"),
    ("PersistenceProvider", "PersistenceProviders"),
    ("PermissionGroup", "PermissionGroups"),
    ("Role", "Roles"),
    ("Project", "Projects"),
    ("QueueProviderPackage", "QueueProviderPackages"),
    ("QueueProvider", "QueueProviders"),
    ("Resource", "Resources"),
    ("ScriptFunctionLibrary", "ScriptFunctionLibraries"),
    ("StateDefinition", "StateDefinitions"),
    ("StyleDefinition", "StyleDefinitions"),
    ("StyleTheme", "StyleThemes"),
    ("Subsystem", "Subsystems"),
    ("Thing", "Things"),
    ("ThingGroup", "ThingGroups"),
    ("ThingPackage", "ThingPackages"),
    ("ThingShape", "ThingShapes"),
    ("ThingTemplate", "ThingTemplates"),
    ("User", "Users"),
    ("Widget", "Widgets"),
)

# Build lookup table
_WIRE_LOOKUP: dict[str, str] = {}
for _singular, _plural in ENTITY_WIRE_NAMES:
    _WIRE_LOOKUP[_singular.casefold()] = _plural
    _WIRE_LOOKUP[_plural.casefold()] = _plural
_WIRE_LOOKUP["datatag"] = "DataTags"
_WIRE_LOOKUP["modeltag"] = "ModelTags"

DEFAULT_ENTITY_COLLECTION = "Things"


def normalize_entity_collection(collection: str) -> str:
    """Resolve user input to canonical plural REST collection segment."""
    t = collection.strip()
    if not t:
        raise ValueError("entity collection must not be empty")
    if any(c in t for c in "/\\?#%"):
        raise ValueError(
            f"invalid entity collection {collection!r}: must be a single name without path separators"
        )
    key = t.casefold()
    plural = _WIRE_LOOKUP.get(key)
    if plural is None:
        known = sorted({p for _, p in ENTITY_WIRE_NAMES})
        raise ValueError(
            f"unknown entity collection {collection!r}; expected one of: {', '.join(known)}"
        )
    return plural


def parse_entity_spec(spec: str) -> tuple[str, str]:
    """Parse Collection:EntityName or EntityName (defaults to Things). Returns (plural_collection, entity_name)."""
    raw = spec.strip()
    if not raw:
        raise ValueError("entity spec must not be empty")
    if ":" in raw:
        type_part, _, name_part = raw.partition(":")
        collection = normalize_entity_collection(type_part)
        name = name_part.strip()
    else:
        collection = DEFAULT_ENTITY_COLLECTION
        name = raw
    if not name:
        raise ValueError(f"entity spec {spec!r} has no name")
    return collection, name


def percent_encode_path_segment(name: str) -> str:
    """Percent-encode a single URL path segment."""
    return urllib.parse.quote(name, safe="-._~")


# ============================================================================
# .env loading
# ============================================================================


def iter_env_assignments(text: str) -> Iterator[tuple[str, str]]:
    """Yield KEY=VALUE pairs. Blank lines and comments are skipped."""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, rest = line.partition("=")
        key = key.strip()
        val = rest.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ('"', "'"):
            val = val[1:-1]
        if key:
            yield key, val


def read_env_assignments(path: Path) -> dict[str, str]:
    """Parse KEY=VALUE lines. A later duplicate key replaces an earlier one."""
    values: dict[str, str] = {}
    for key, val in iter_env_assignments(path.read_text(encoding="utf-8")):
        values[key] = val
    return values


def load_dotenv(path: Path) -> None:
    """Load .env file without overriding existing environment variables.

    The first assignment for a key wins. Placeholder lookup uses a separate
    map, where a later assignment wins.
    """
    if not path.is_file():
        return
    for key, val in iter_env_assignments(path.read_text(encoding="utf-8")):
        if key not in os.environ:
            os.environ[key] = val


def read_env_file(path: Path, *, required: bool) -> dict[str, str]:
    """Read an env file for placeholder lookup. A named file must exist."""
    if not path.is_file():
        if required:
            print(f"Env file not found: {path}", file=sys.stderr)
            sys.exit(1)
        return {}
    return read_env_assignments(path)


def build_placeholder_map(launch_dir: Path, from_env: list[str] | None) -> dict[str, str]:
    """Placeholder values: --from-env, then .env, then the process environment.

    Later --from-env files replace earlier ones. This map is not used for
    TWX_URL or TWX_APPKEY.
    """
    table = dict(os.environ)
    table.update(read_env_file(launch_dir / ".env", required=False))
    for name in from_env or []:
        path = Path(name)
        if not path.is_absolute():
            path = launch_dir / path
        table.update(read_env_file(path, required=True))
    return table


def placeholder_token(name: str) -> str:
    """Render the placeholder spelling for messages."""
    return "{{{env:" + name + "}}}"


def substitute_placeholders(text: str, table: dict[str, str]) -> str:
    """Replace {{{env:NAME}}} once. Inserted text is not scanned again."""

    def repl(match: re.Match[str]) -> str:
        name = match.group(1)
        token = placeholder_token(name)
        if name not in table:
            print(f"Missing placeholder value: {token}", file=sys.stderr)
            sys.exit(1)
        value = table[name]
        if "\n" in value or "\r" in value:
            print(f"Placeholder {token} value must be a single line", file=sys.stderr)
            sys.exit(1)
        return value

    return PLACEHOLDER_RE.sub(repl, text)


def read_utf8_text(path: Path) -> str:
    """Read a text file as UTF-8."""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as e:
        print(f"File is not UTF-8: {path}: {e}", file=sys.stderr)
        sys.exit(1)


def require_env(name: str) -> str:
    """Get required environment variable or exit."""
    v = os.environ.get(name, "").strip()
    if not v:
        print(f"Missing required environment variable: {name}", file=sys.stderr)
        sys.exit(1)
    return v


def normalize_base_url(url: str) -> str:
    """Normalize URL to ensure it ends with /Thingworx."""
    t = url.strip().rstrip("/")
    if t.casefold().endswith("/thingworx"):
        return t
    return f"{t}/Thingworx"


def server_root(base_url: str) -> str:
    """Strip one trailing /Thingworx context path (any case) from a base URL, keeping scheme, host and port."""
    t = base_url.strip().rstrip("/")
    if t.casefold().endswith("/thingworx"):
        t = t[: -len("/thingworx")]
    return t


# ============================================================================
# HTTP helpers
# ============================================================================


def build_multipart_body_single_file(
    file_path: Path,
    boundary: str,
    content_type: str,
    *,
    file_bytes: bytes | None = None,
) -> bytes:
    """Build multipart/form-data body for a single file upload."""
    crlf = b"\r\n"
    if file_bytes is None:
        file_bytes = file_path.read_bytes()
    disp = (
        f'Content-Disposition: form-data; name="file"; '
        f'filename="{file_path.name}"'
    )
    parts = [
        f"--{boundary}".encode("ascii") + crlf,
        disp.encode("utf-8") + crlf,
        f"Content-Type: {content_type}".encode("ascii") + crlf,
        crlf,
        file_bytes + crlf,
        f"--{boundary}--".encode("ascii") + crlf,
    ]
    return b"".join(parts)


def build_multipart_body_file_repository(
    file_path: Path,
    boundary: str,
    *,
    repo_name: str,
    dest_path: str,
    content_type: str,
) -> bytes:
    """Build multipart/form-data for FileRepository upload."""
    crlf = b"\r\n"
    file_bytes = file_path.read_bytes()
    parts = []

    def add_text(name: str, value: str) -> None:
        parts.append(f"--{boundary}".encode("ascii") + crlf)
        parts.append(f'Content-Disposition: form-data; name="{name}"'.encode("utf-8") + crlf)
        parts.append(crlf)
        parts.append(value.encode("utf-8") + crlf)

    add_text("upload-repository", repo_name)
    add_text("upload-path", dest_path)

    disp_file = (
        f'Content-Disposition: form-data; name="upload-files"; '
        f'filename="{file_path.name}"'
    )
    parts.append(f"--{boundary}".encode("ascii") + crlf)
    parts.append(disp_file.encode("utf-8") + crlf)
    parts.append(f"Content-Type: {content_type}".encode("ascii") + crlf)
    parts.append(crlf)
    parts.append(file_bytes + crlf)
    add_text("upload-submit", "Upload")
    parts.append(f"--{boundary}--".encode("ascii") + crlf)
    return b"".join(parts)


def post_multipart(
    url: str,
    app_key: str,
    body: bytes,
    boundary: str,
    *,
    timeout: float = HTTP_TIMEOUT_S,
    label: str = "Upload",
    warn_codes: frozenset[int] = frozenset(),
) -> int:
    """POST multipart/form-data to ThingWorx and return the HTTP status (a warn code returns instead of exiting)."""
    headers = {
        "appKey": app_key,
        "X-XSRF-TOKEN": XSRF_TOKEN,
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "*/*",
        "Accept-Encoding": "gzip,deflate",
        "Connection": "keep-alive",
    }
    req = urllib.request.Request(url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
            code = resp.getcode()
            resp.read()
    except urllib.error.HTTPError as e:
        code = e.code
        err_body = e.read().decode("utf-8", errors="replace")
        if code in warn_codes:
            print(f"{label} HTTP {code}: WARNING, continuing", file=sys.stderr, flush=True)
            problems = extension_report_problems(err_body)
            lines = problems or [err_body.strip()[:4000]]
            for line in lines:
                print(f"  {line}", file=sys.stderr, flush=True)
            return code
        print(f"{label} HTTP {code}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, OSError) as e:
        print(f"{label} request failed: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"{label} HTTP {code}", flush=True)
    if not (200 <= code < 300):
        print(f"{label} unexpected status {code}", file=sys.stderr)
        sys.exit(1)
    return code


def execute_rest_call(
    url: str,
    app_key: str,
    *,
    method: str = "POST",
    payload: str = "",
    headers: dict[str, str] | None = None,
    timeout: float = HTTP_TIMEOUT_S,
) -> None:
    """Execute arbitrary REST call to ThingWorx."""
    merged_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "appKey": app_key,
    }
    if headers:
        merged_headers.update(headers)

    body: bytes | None = None
    if payload.strip():
        # Validate JSON if Content-Type is application/json
        if "application/json" in merged_headers.get("Content-Type", ""):
            try:
                obj = json.loads(payload)
                if not isinstance(obj, dict):
                    raise ValueError("payload must be a JSON object (dict)")
            except json.JSONDecodeError as e:
                print(f"Invalid JSON payload: {e}", file=sys.stderr)
                sys.exit(1)
        body = payload.encode("utf-8")
    elif method not in ("GET", "HEAD", "OPTIONS", "TRACE"):
        body = b"{}"

    req = urllib.request.Request(url, data=body, method=method)
    for k, v in merged_headers.items():
        req.add_header(k, v)

    label = f"REST {method}"
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
            code = resp.getcode()
            body_bytes = resp.read()
            text = body_bytes.decode("utf-8", errors="replace")
            snippet = text[:200] if text else ""
            print(f"{label} HTTP {code}: {snippet}", flush=True)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"{label} HTTP {e.code}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, TimeoutError, socket.timeout) as e:
        print(f"{label} request failed: {e}", file=sys.stderr)
        sys.exit(1)


def invoke_repository_service(
    base_url: str,
    app_key: str,
    *,
    repo: str,
    service: str,
    payload: dict[str, Any],
    timeout: float = HTTP_TIMEOUT_S,
    tolerate_errors: tuple[str, ...] = (),
) -> Any:
    """Call a FileRepository Thing service and return parsed result."""
    url = f"{base_url}/Things/{repo}/services/{service}"
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "appKey": app_key,
    }
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
            raw = resp.read()
            if raw:
                return json.loads(raw.decode("utf-8"))
            return None
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        # Check if error is tolerable
        if tolerate_errors:
            for marker in tolerate_errors:
                if marker.lower() in err_body.lower():
                    return None
        print(f"{repo}.{service} HTTP {e.code}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, OSError) as e:
        print(f"{repo}.{service} request failed: {e}", file=sys.stderr)
        sys.exit(1)


def info_table_rows(data: Any) -> list[dict[str, Any]]:
    """Normalize a ThingWorx InfoTable JSON payload to a list of row dicts."""
    if data is None:
        return []
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if not isinstance(data, dict):
        return []
    rows = data.get("rows")
    if isinstance(rows, list):
        return [r for r in rows if isinstance(r, dict)]
    result = data.get("result")
    if isinstance(result, dict) and isinstance(result.get("rows"), list):
        return [r for r in result["rows"] if isinstance(r, dict)]
    if isinstance(result, list):
        return [r for r in result if isinstance(r, dict)]
    return []


def repo_join(parent: str, name: str) -> str:
    parent_n = parent.replace("\\", "/").strip() or "/"
    leaf = name.replace("\\", "/").strip().strip("/")
    if not leaf:
        return parent_n if parent_n != "" else "/"
    if parent_n in ("", "/"):
        return f"/{leaf}"
    return f"{parent_n.rstrip('/')}/{leaf}"


def listing_leaf_name(row: dict[str, Any]) -> str:
    for key in ("name", "fileName", "filename"):
        val = row.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip().replace("\\", "/").rstrip("/").split("/")[-1]
    return ""


def is_skipped_listing_name(name: str) -> bool:
    return (not name) or name in (".", "..") or name.startswith(".")


def zip_member_dest_rel(member_name: str, full_path: str) -> str | None:
    """Map a zip member to a path relative to the download target, stripping ``full_path``."""
    raw = member_name.replace("\\", "/").strip()
    name = raw.rstrip("/").lstrip("/")
    if not name or name == ".":
        return None
    parts = [p for p in name.split("/") if p and p != "."]
    if not parts or any(p == ".." for p in parts):
        return None
    prefix = full_path.replace("\\", "/").strip().strip("/")
    if prefix:
        prefix_parts = prefix.split("/")
        if parts[: len(prefix_parts)] == prefix_parts:
            parts = parts[len(prefix_parts) :]
        if not parts:
            return None
    if any(p.startswith(".") for p in parts):
        return None
    return "/".join(parts)


def is_remote_file_ref(file_str: str) -> bool:
    """True when a control-file path is an http(s) URL rather than a local path."""
    return file_str.casefold().startswith(("https://", "http://"))


def remote_download_name(url: str) -> str:
    """File name from the URL path, before redirects rewrite it."""
    path = urllib.parse.urlparse(url).path
    name = urllib.parse.unquote(Path(path).name)
    if not name or name in (".", "..") or "/" in name or "\\" in name:
        raise ValueError(f"URL has no file name: {url}")
    return name


def _is_github_host(netloc: str) -> bool:
    host = netloc.casefold().split("@")[-1].split(":")[0]
    if host.startswith("www."):
        host = host[4:]
    return host == "github.com"


def asset_name_matches(name: str, pattern: str) -> bool:
    """Case-sensitive file-name match. Only * is special; it does not cross '/'."""
    if "/" in name or "\\" in name:
        return False
    if "*" not in pattern:
        return name == pattern
    regex = "^" + ".*".join(re.escape(part) for part in pattern.split("*")) + "$"
    return re.fullmatch(regex, name) is not None


def parse_github_release_download(
    url: str,
) -> tuple[str, str, str, str, bool] | None:
    """Return owner, repo, tag, asset pattern, and whether the URL asks for latest.

    latest is /releases/latest/download/<name>. A tag written in
    /releases/download/<tag>/<name> is that tag, even when the tag is named latest.
    """
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme.casefold() not in ("https", "http"):
        return None
    if not _is_github_host(parsed.netloc):
        return None
    parts = [urllib.parse.unquote(part) for part in parsed.path.split("/") if part]
    latest = False
    if len(parts) == 6 and parts[2:5] == ["releases", "latest", "download"]:
        owner, repo, name = parts[0], parts[1], parts[5]
        tag = ""
        latest = True
    elif len(parts) == 6 and parts[2:4] == ["releases", "download"]:
        owner, repo, tag, name = parts[0], parts[1], parts[4], parts[5]
    else:
        return None
    if "*" in owner or "*" in repo or "*" in tag:
        raise ValueError(
            "Wildcard is only allowed in the release asset file name: " + url
        )
    if not owner or not repo or not name or (not latest and not tag):
        return None
    return owner, repo, tag, name, latest


def github_path_has_wildcard(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    if not _is_github_host(parsed.netloc):
        return False
    return "*" in urllib.parse.unquote(parsed.path)


class ReleaseAssetIndex:
    """Uploaded assets for GitHub releases, fetched once per release."""

    def __init__(self) -> None:
        self._cache: dict[tuple[str, str, str], tuple[str, list[tuple[str, str]]]] = {}

    def resolve(
        self,
        owner: str,
        repo: str,
        tag: str,
        pattern: str,
        *,
        latest: bool,
    ) -> tuple[str, str, str]:
        """Return tag name, asset name, and browser_download_url. Exactly one match."""
        tag_name, assets = self._load(owner, repo, tag, latest)
        matches = [
            (name, download_url)
            for name, download_url in assets
            if asset_name_matches(name, pattern)
        ]
        matches.sort(key=lambda item: item[0])
        if len(matches) == 1:
            name, download_url = matches[0]
            return tag_name, name, download_url
        where = f"latest release {tag_name}" if latest else f"release {tag_name}"
        if matches:
            listed = ", ".join(name for name, _url in matches)
            detail = f"matched: {listed}"
        else:
            uploaded = ", ".join(name for name, _url in sorted(assets)) if assets else "(none)"
            detail = f"matched none. Uploaded assets: {uploaded}"
        raise ValueError(
            f"{pattern!r} matched {len(matches)} asset(s) on {where}. {detail}"
        )

    def _load(
        self, owner: str, repo: str, tag: str, latest: bool
    ) -> tuple[str, list[tuple[str, str]]]:
        key = (owner.casefold(), repo.casefold(), "*" if latest else tag)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        quoted_owner = urllib.parse.quote(owner, safe="")
        quoted_repo = urllib.parse.quote(repo, safe="")
        if latest:
            api = f"https://api.github.com/repos/{quoted_owner}/{quoted_repo}/releases/latest"
        else:
            quoted_tag = urllib.parse.quote(tag, safe="")
            api = (
                f"https://api.github.com/repos/{quoted_owner}/{quoted_repo}"
                f"/releases/tags/{quoted_tag}"
            )
        data = http_get_public_json(api)
        if not isinstance(data, dict):
            raise ValueError(f"GitHub API returned unexpected data for {api}")
        tag_name = data.get("tag_name")
        raw_assets = data.get("assets")
        if not isinstance(tag_name, str) or not tag_name:
            raise ValueError(f"GitHub release has no tag_name: {api}")
        if not isinstance(raw_assets, list):
            raise ValueError(f"GitHub release has no asset list: {api}")
        assets: list[tuple[str, str]] = []
        for item in raw_assets:
            if not isinstance(item, dict):
                continue
            name = item.get("name")
            download_url = item.get("browser_download_url")
            if (
                isinstance(name, str)
                and name
                and isinstance(download_url, str)
                and download_url
            ):
                assets.append((name, download_url))
        loaded = (tag_name, assets)
        self._cache[key] = loaded
        return loaded


_RELEASE_INDEX = ReleaseAssetIndex()


def http_get_public_json(url: str) -> Any:
    """GET public JSON with default TLS verification, not SSL_CONTEXT."""
    req = urllib.request.Request(
        url,
        method="GET",
        headers={
            "User-Agent": REMOTE_DOWNLOAD_USER_AGENT,
            "Accept": "application/vnd.github+json",
        },
    )
    try:
        with urllib.request.urlopen(
            req,
            timeout=HTTP_TIMEOUT_S,
            context=ssl.create_default_context(),
        ) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:500]
        raise ValueError(f"GitHub API HTTP {e.code} for {url}: {body}") from e
    except (
        urllib.error.URLError,
        TimeoutError,
        socket.timeout,
        OSError,
        json.JSONDecodeError,
    ) as e:
        print(f"GitHub API request failed: {url}: {e}", file=sys.stderr)
        sys.exit(1)


def prepare_remote_download(url: str) -> tuple[str, str, str | None]:
    """Return URL to GET, file name, and release tag when this is a GitHub release asset."""
    parsed = parse_github_release_download(url)
    if parsed is None:
        if github_path_has_wildcard(url):
            raise ValueError(
                "Wildcard is only allowed in the file name of a GitHub release download URL: "
                + url
            )
        return url, remote_download_name(url), None
    owner, repo, tag, pattern, latest = parsed
    tag_name, name, download_url = _RELEASE_INDEX.resolve(
        owner, repo, tag, pattern, latest=latest
    )
    return download_url, name, tag_name


def iter_remote_file_refs(
    steps: list[dict[str, Any]],
) -> Iterator[tuple[int, str, str, bool]]:
    """Yield step number, action, remote URL, and whether the resolved name must be a zip."""
    for index, step in enumerate(steps, 1):
        action = step.get("action", "")
        refs: list[tuple[Any, bool]] = []
        if action in ("import_extension", "import_file", "import_data", "upload_file"):
            for ref in step.get("files") or []:
                refs.append((ref, False))
        elif action == "rest_call":
            payload_file = step.get("payload_file")
            if payload_file is not None:
                refs.append((payload_file, False))
        elif action == "upload_file_tree":
            source = step.get("source")
            if source is not None:
                refs.append((source, True))
        if action == "import_data":
            zipped = step.get("files_zipped")
            if isinstance(zipped, str) and zipped.strip():
                refs.append((zipped, True))
        for ref, require_zip in refs:
            if isinstance(ref, str) and ref.strip() and is_remote_file_ref(ref):
                yield index, str(action), ref.strip(), require_zip


def validate_files_zipped_fields(steps: list[dict[str, Any]]) -> None:
    """files_zipped is optional, and when present it is exactly one zip path or URL."""
    errors: list[tuple[int, str]] = []
    for index, step in enumerate(steps, 1):
        if step.get("action") != "import_data" or "files_zipped" not in step:
            continue
        value = step.get("files_zipped")
        if value is None:
            continue
        if not isinstance(value, str) or not value.strip():
            errors.append((index, "files_zipped must be one zip file"))
    if not errors:
        return
    print("\nimport_data check failed:", file=sys.stderr)
    for index, message in errors:
        print(f"  [{index}] {message}", file=sys.stderr)
    sys.exit(1)


def check_github_release_refs(steps: list[dict[str, Any]]) -> None:
    """Resolve every GitHub release download in the plan. Exit 1 unless each matches one asset."""
    pending: list[tuple[int, str, str, bool, tuple[str, str, str, str, bool]]] = []
    errors: list[tuple[int, str, str]] = []
    saw = False
    for index, action, url, require_zip in iter_remote_file_refs(steps):
        try:
            parsed = parse_github_release_download(url)
        except ValueError as e:
            saw = True
            errors.append((index, url, str(e)))
            continue
        if parsed is None:
            if github_path_has_wildcard(url):
                saw = True
                errors.append(
                    (
                        index,
                        url,
                        "Wildcard is only allowed in the file name of a GitHub release download URL",
                    )
                )
            continue
        saw = True
        pending.append((index, action, url, require_zip, parsed))
    if not saw:
        return

    resolved: list[tuple[int, str, str]] = []
    for index, action, url, require_zip, parsed in pending:
        owner, repo, tag, pattern, latest = parsed
        try:
            tag_name, name, _download_url = _RELEASE_INDEX.resolve(
                owner, repo, tag, pattern, latest=latest
            )
        except ValueError as e:
            errors.append((index, url, str(e)))
            continue
        if require_zip and not name.casefold().endswith(".zip"):
            if action == "import_data":
                message = f"files_zipped must be a .zip: {name}"
            else:
                message = f"Remote upload_file_tree source must be a .zip: {name}"
            errors.append((index, url, message))
            continue
        resolved.append((index, url, f"{name} (release {tag_name})"))

    if resolved:
        print("\nGitHub release check:")
        for index, url, label in resolved:
            print(f"  [{index}] {url}")
            print(f"      -> {label}")

    if errors:
        sys.stdout.flush()
        print("\nGitHub release check failed:", file=sys.stderr)
        for index, url, message in errors:
            print(f"  [{index}] {url}", file=sys.stderr)
            print(f"      {message}", file=sys.stderr)
        sys.exit(1)


def download_remote_file(url: str, dest: Path) -> None:
    """Download a public URL to dest. Uses default TLS verification, not SSL_CONTEXT."""
    req = urllib.request.Request(
        url,
        method="GET",
        headers={"User-Agent": REMOTE_DOWNLOAD_USER_AGENT},
    )
    try:
        with urllib.request.urlopen(
            req,
            timeout=HTTP_TIMEOUT_S,
            context=ssl.create_default_context(),
        ) as resp:
            with dest.open("wb") as out:
                shutil.copyfileobj(resp, out)
    except urllib.error.HTTPError as e:
        print(f"Download failed: HTTP {e.code} {url}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as e:
        print(f"Download failed: {url}: {e}", file=sys.stderr)
        sys.exit(1)


@contextmanager
def open_local_input(work_dir: Path, file_str: str) -> Iterator[Path]:
    """Yield a local file. https inputs are downloaded into a temporary directory."""
    if not isinstance(file_str, str) or not file_str.strip():
        print("File path must be a non-empty string", file=sys.stderr)
        sys.exit(1)
    file_str = file_str.strip()
    if not is_remote_file_ref(file_str):
        file_path = work_dir / file_str
        if not file_path.is_file():
            print(f"File not found: {file_path}", file=sys.stderr)
            sys.exit(1)
        yield file_path
        return

    try:
        download_url, name, release_tag = prepare_remote_download(file_str)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)

    tmp = tempfile.TemporaryDirectory(prefix="load-py-")
    try:
        dest = Path(tmp.name) / name
        if release_tag is None:
            print(f"  Downloading {file_str}")
        else:
            print(f"  Downloading {name} (release {release_tag})")
        download_remote_file(download_url, dest)
        yield dest
    finally:
        tmp.cleanup()


def extract_zip_tree(zip_path: Path, dest: Path) -> None:
    """Extract uploadable zip members into dest. Skips dotfiles and paths that escape dest."""
    dest_resolved = dest.resolve()
    written = 0
    try:
        with zipfile.ZipFile(zip_path) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                rel = zip_member_dest_rel(info.filename, "")
                if rel is None:
                    print(f"      skip zip member: {info.filename!r}")
                    continue
                target = (dest / rel).resolve()
                if dest_resolved != target and dest_resolved not in target.parents:
                    print(f"      skip zip member: {info.filename!r}", file=sys.stderr)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, target.open("wb") as out:
                    shutil.copyfileobj(src, out)
                written += 1
    except zipfile.BadZipFile as e:
        print(f"Not a zip file: {zip_path.name}: {e}", file=sys.stderr)
        sys.exit(1)
    if written == 0:
        print(f"Zip contained no uploadable files: {zip_path.name}", file=sys.stderr)
        sys.exit(1)


@contextmanager
def extracted_zip_tree(zip_path: Path) -> Iterator[Path]:
    """Extract zip_path and yield the directory of uploadable members."""
    tmp = tempfile.TemporaryDirectory(prefix="load-py-tree-")
    try:
        extract_dir = Path(tmp.name) / "tree"
        extract_dir.mkdir()
        print(f"  Extracting {zip_path.name}")
        extract_zip_tree(zip_path, extract_dir)
        yield extract_dir
    finally:
        tmp.cleanup()


@contextmanager
def open_tree_source(work_dir: Path, source: str) -> Iterator[Path]:
    """Yield a local directory. A zip, local or remote, is extracted first."""
    if not isinstance(source, str) or not source.strip():
        print("  (source and target required)", file=sys.stderr)
        sys.exit(1)
    source = source.strip()
    if not is_remote_file_ref(source):
        source_path = work_dir / source
        if source_path.is_dir():
            yield source_path
            return
        if not source_path.is_file():
            print(f"Source not found: {source_path}", file=sys.stderr)
            sys.exit(1)
        if not source_path.name.casefold().endswith(".zip"):
            print(
                f"upload_file_tree source must be a directory or a .zip: {source_path}",
                file=sys.stderr,
            )
            sys.exit(1)
        with extracted_zip_tree(source_path) as extract_dir:
            yield extract_dir
        return

    try:
        download_url, name, release_tag = prepare_remote_download(source)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
    if not name.casefold().endswith(".zip"):
        print(f"Remote upload_file_tree source must be a .zip: {name}", file=sys.stderr)
        sys.exit(1)

    tmp = tempfile.TemporaryDirectory(prefix="load-py-tree-")
    try:
        zip_path = Path(tmp.name) / name
        if release_tag is None:
            print(f"  Downloading {source}")
        else:
            print(f"  Downloading {name} (release {release_tag})")
        download_remote_file(download_url, zip_path)
        with extracted_zip_tree(zip_path) as extract_dir:
            yield extract_dir
    finally:
        tmp.cleanup()


def http_get_json(url: str, app_key: str, *, timeout: float = HTTP_TIMEOUT_S) -> Any:
    """Execute HTTP GET and return JSON response."""
    headers = {
        "appKey": app_key,
        "Accept": "application/json",
        "X-XSRF-TOKEN": XSRF_TOKEN,
    }
    req = urllib.request.Request(url, method="GET")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            return json.loads(text)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"HTTP {e.code} GET {url}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, OSError) as e:
        print(f"GET {url} failed: {e}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Response is not valid JSON from {url}: {e}", file=sys.stderr)
        sys.exit(1)


def http_get_text(url: str, app_key: str, *, accept: str = "text/xml", timeout: float = HTTP_TIMEOUT_S) -> str:
    """Execute HTTP GET and return text response."""
    headers = {
        "appKey": app_key,
        "Accept": accept,
        "X-XSRF-TOKEN": XSRF_TOKEN,
    }
    req = urllib.request.Request(url, method="GET")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
            return resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"HTTP {e.code} GET {url}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, OSError) as e:
        print(f"GET {url} failed: {e}", file=sys.stderr)
        sys.exit(1)


def build_entity_json_url(base_url: str, collection: str, name: str) -> str:
    """Build URL for entity JSON GET."""
    col = normalize_entity_collection(collection)
    path = f"{percent_encode_path_segment(col)}/{percent_encode_path_segment(name.strip())}"
    return f"{base_url}/{path}"


def build_entity_exporter_url(base_url: str, collection: str, name: str) -> str:
    """Build URL for entity XML export."""
    col = normalize_entity_collection(collection)
    path = f"{percent_encode_path_segment(col)}/{percent_encode_path_segment(name.strip())}"
    query = urllib.parse.urlencode({
        "repositoryName": "",
        "universal": "password",
        "Accept": "text/xml",
    })
    return f"{base_url}/Exporter/{path}?{query}"


def build_project_exporter_url(base_url: str, project_name: str) -> str:
    """Build URL for project XML export."""
    query = urllib.parse.urlencode({
        "repositoryName": "",
        "projectName": project_name,
        "includeDependents": "false",
        "universal": "password",
        "Accept": "text/xml",
    })
    return f"{base_url}/Exporter?{query}"


# ============================================================================
# Action implementations
# ============================================================================


def extension_report_problems(body: str) -> list[str]:
    """Messages of the FAILURE (1) and ALERT (3) rows of an ExtensionPackageUploader report, if the body is one.

    Re-importing an installed version is reported as a WARNING row with HTTP 200. HTTP 406 means the report has a
    FAILURE or ALERT row, or the import was refused outright (for example a newer version is already installed);
    then the body is only a status text and the reason is in the ApplicationLog, which the plan reads and prints
    before it continues.
    """
    try:
        report = json.loads(body)
    except ValueError:
        return []
    problems = []
    for phase_row in report.get("rows", []) if isinstance(report, dict) else []:
        for phase, table in phase_row.items():
            for row in table.get("rows", []) if isinstance(table, dict) else []:
                if row.get("extensionReportStatus") in (1, 3):
                    kind = "FAILURE" if row.get("extensionReportStatus") == 1 else "ALERT"
                    problems.append(f"{phase} {kind}: {row.get('reportMessage') or row.get('extensionException')}")
    return problems


def _iso_z(moment: dt.datetime) -> str:
    return moment.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def fetch_extension_log_entries(
    base_url: str, app_key: str, since: dt.datetime, *, max_items: int = 200
) -> list[str]:
    """WARN and ERROR ApplicationLog entries about extensions written since ``since`` (oldest first)."""
    payload = {
        "startDate": _iso_z(since - dt.timedelta(seconds=5)),
        "endDate": _iso_z(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=5)),
        "fromLogLevel": "WARN",
        "toLogLevel": "ERROR",
        "maxItems": max_items,
        "oldestFirst": True,
        "isRegex": True,
        "searchExpression": ".*.*",
        "instance": "",
        "origin": "",
        "thread": "",
        "user": "",
    }
    req = urllib.request.Request(
        f"{base_url}/Logs/ApplicationLog/Services/QueryLogEntries",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", "appKey": app_key},
    )
    with urllib.request.urlopen(req, timeout=60, context=SSL_CONTEXT) as resp:
        rows = json.loads(resp.read().decode("utf-8")).get("rows") or []
    lines = []
    for row in rows:
        text = f"{row.get('origin') or ''} {row.get('content') or ''}"
        if "extension" in text.lower():
            lines.append(f"{row.get('level') or ''} {row.get('content') or ''}".strip())
    return lines


def print_extension_log(base_url: str, app_key: str, since: dt.datetime) -> None:
    """Print the ApplicationLog lines that explain a refused extension import; never stops the plan."""
    try:
        lines = fetch_extension_log_entries(base_url, app_key, since)
    except (urllib.error.URLError, OSError, ValueError) as e:
        print(f"  (could not read the ApplicationLog: {e})", file=sys.stderr, flush=True)
        return
    if not lines:
        print("  (no matching ApplicationLog entries)", file=sys.stderr, flush=True)
        return
    print("  ApplicationLog:", file=sys.stderr, flush=True)
    for line in lines[-20:]:
        print(f"    {line[:2000]}", file=sys.stderr, flush=True)


def action_import_extension(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Import extension ZIP packages."""
    files = step.get("files", [])
    if not files:
        print("  (no files specified)")
        return

    url = f"{base_url}/ExtensionPackageUploader?{IMPORTER_QUERY.replace('purpose=import', 'purpose=import&validate=false')}"

    for file_str in files:
        with open_local_input(work_dir, file_str) as file_path:
            print(f"  Uploading extension: {file_path.name}")
            boundary = f"----pyloader_{secrets.token_hex(12)}"
            content_type = "application/x-zip-compressed"
            body = build_multipart_body_single_file(file_path, boundary, content_type)
            started = dt.datetime.now(dt.timezone.utc)
            code = post_multipart(
                url,
                app_key,
                body,
                boundary,
                label="ExtensionPackageUploader",
                warn_codes=frozenset({406}),
            )
            if code == 406:
                print_extension_log(base_url, app_key, started)


def action_import_file(
    step: dict[str, Any],
    base_url: str,
    app_key: str,
    work_dir: Path,
    placeholders: dict[str, str],
) -> None:
    """Import ThingWorx entity XML files."""
    files = step.get("files", [])
    if not files:
        print("  (no files specified)")
        return

    url = f"{base_url}/Importer?{IMPORTER_QUERY}"

    for file_str in files:
        with open_local_input(work_dir, file_str) as file_path:
            print(f"  Importing: {file_path.name}")
            boundary = f"----pyloader_{secrets.token_hex(12)}"
            file_bytes: bytes | None = None
            if file_path.suffix.lower() == ".xml":
                content_type = "text/xml"
                text = substitute_placeholders(read_utf8_text(file_path), placeholders)
                file_bytes = text.encode("utf-8")
            else:
                content_type = "application/octet-stream"
            body = build_multipart_body_single_file(
                file_path, boundary, content_type, file_bytes=file_bytes
            )
            post_multipart(url, app_key, body, boundary, label="Importer")


def data_zip_members(extract_dir: Path) -> list[tuple[str, Path]]:
    """Return .twx members sorted by path. Any other file fails the step."""
    members: list[tuple[str, Path]] = []
    rejected: list[str] = []
    for root, dirs, filenames in os.walk(extract_dir):
        dirs[:] = sorted(name for name in dirs if not name.startswith("."))
        for filename in sorted(name for name in filenames if not name.startswith(".")):
            path = Path(root) / filename
            rel = path.relative_to(extract_dir).as_posix()
            if path.suffix.casefold() == ".twx":
                members.append((rel, path))
            else:
                rejected.append(rel)
    if rejected:
        print(
            "files_zipped contains files that are not .twx: " + ", ".join(rejected),
            file=sys.stderr,
        )
        sys.exit(1)
    if not members:
        print("files_zipped contained no .twx files", file=sys.stderr)
        sys.exit(1)
    return members


def import_one_data_file(
    url: str,
    app_key: str,
    file_path: Path,
    timeout: float,
    label: str,
) -> None:
    """POST one .twx file to DataImporter."""
    print(f"  Importing data: {label}")
    boundary = f"----pyloader_{secrets.token_hex(12)}"
    body = build_multipart_body_single_file(
        file_path, boundary, "application/octet-stream"
    )
    post_multipart(url, app_key, body, boundary, timeout=timeout, label="DataImporter")


def action_import_data(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Import ThingWorx data (.twx files), optionally from one zip."""
    files = step.get("files") or []
    zipped = step.get("files_zipped")
    if zipped is not None and (not isinstance(zipped, str) or not zipped.strip()):
        print("files_zipped must be one zip file", file=sys.stderr)
        sys.exit(1)
    zipped_ref = zipped.strip() if isinstance(zipped, str) and zipped.strip() else ""
    if not files and not zipped_ref:
        print("  (no files specified)")
        return

    timeout = step.get("timeout", HTTP_TIMEOUT_S)
    url = f"{base_url}/DataImporter?{IMPORTER_QUERY}"

    if zipped_ref:
        if not is_remote_file_ref(zipped_ref):
            zip_path = work_dir / zipped_ref
            if zip_path.is_dir() or not zip_path.name.casefold().endswith(".zip"):
                print(f"files_zipped must be a .zip: {zip_path}", file=sys.stderr)
                sys.exit(1)
        with open_tree_source(work_dir, zipped_ref) as extract_dir:
            for rel, file_path in data_zip_members(extract_dir):
                import_one_data_file(url, app_key, file_path, timeout, rel)

    for file_str in files:
        with open_local_input(work_dir, file_str) as file_path:
            import_one_data_file(url, app_key, file_path, timeout, file_path.name)


def action_rest_call(
    step: dict[str, Any],
    base_url: str,
    app_key: str,
    work_dir: Path,
    placeholders: dict[str, str],
) -> None:
    """Execute arbitrary REST call."""
    service = step.get("service", "")
    if not service:
        print("  (no service specified)", file=sys.stderr)
        sys.exit(1)

    # Build URL: service is usually an absolute path like /Thingworx/...
    service = service.strip()
    if not service.startswith("/"):
        service = "/" + service
    # The service path carries the /Thingworx context, so join it to the server root.
    url = server_root(base_url) + service

    method = step.get("method", "POST").upper()
    timeout = step.get("timeout", HTTP_TIMEOUT_S)

    # Get payload
    payload = ""
    payload_file = step.get("payload_file")
    if payload_file:
        with open_local_input(work_dir, payload_file) as payload_path:
            payload = substitute_placeholders(read_utf8_text(payload_path), placeholders)
    elif "payload" in step:
        payload = step["payload"]
        if isinstance(payload, dict):
            payload = json.dumps(payload)
        else:
            payload = str(payload)

    print(f"  {method} {service}")
    execute_rest_call(
        url,
        app_key,
        method=method,
        payload=payload,
        timeout=timeout,
    )


def action_upload_file(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Upload files to FileRepository."""
    repo = step.get("file_repository", "")
    if not repo:
        print("  (no file_repository specified)", file=sys.stderr)
        sys.exit(1)

    dest_path = step.get("path", "/")
    files = step.get("files", [])
    if not files:
        print("  (no files specified)")
        return

    url = f"{base_url}/FileRepositoryUploader"

    for file_str in files:
        with open_local_input(work_dir, file_str) as file_path:
            print(f"  Uploading to {repo}{dest_path}: {file_path.name}")
            boundary = f"----pyloader_{secrets.token_hex(12)}"
            mime, _ = mimetypes.guess_type(str(file_path))
            content_type = mime or "application/octet-stream"
            body = build_multipart_body_file_repository(
                file_path,
                boundary,
                repo_name=repo,
                dest_path=dest_path,
                content_type=content_type,
            )
            post_multipart(url, app_key, body, boundary, label="FileRepositoryUploader")


def clean_repository_directory(
    base_url: str, app_key: str, *, repo: str, list_path: str
) -> None:
    """Delete all files and folders under list_path in the FileRepository."""
    print(f"    Cleaning remote directory: {repo}{list_path}")

    file_rows = info_table_rows(
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="GetFileListing",
            payload={"path": list_path},
            tolerate_errors=("does not exist", "not found", "directory does not exist"),
        )
    )
    dir_rows = info_table_rows(
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="ListDirectories",
            payload={"path": list_path},
            tolerate_errors=("does not exist", "not found", "directory does not exist"),
        )
    )

    deleted_files = 0
    deleted_dirs = 0

    for row in file_rows:
        name = listing_leaf_name(row)
        if is_skipped_listing_name(name):
            continue
        full_path = repo_join(list_path, name)
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="DeleteFile",
            payload={"path": full_path},
            tolerate_errors=("does not exist", "not found"),
        )
        deleted_files += 1
        print(f"      deleted file: {name}")

    for row in dir_rows:
        name = listing_leaf_name(row)
        if is_skipped_listing_name(name):
            continue
        full_path = repo_join(list_path, name)
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="DeleteFolder",
            payload={"path": full_path},
            tolerate_errors=("does not exist", "not found"),
        )
        deleted_dirs += 1
        print(f"      deleted folder: {name}/")

    print(f"    Deleted {deleted_files} file(s) and {deleted_dirs} folder(s)")


def action_upload_file_tree(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Upload directory tree to FileRepository."""
    source = step.get("source", "")
    target = step.get("target", "")
    cleanup = step.get("cleanup", False)

    if not source or not target:
        print("  (source and target required)", file=sys.stderr)
        sys.exit(1)

    with open_tree_source(work_dir, source) as source_dir:
        parts = [p.strip() for p in target.replace("\\", "/").split("/") if p.strip()]
        if not parts:
            print("  (target must not be empty)", file=sys.stderr)
            sys.exit(1)

        repo = parts[0]
        base_path = "/" + "/".join(parts[1:]) if len(parts) > 1 else "/"
        base_path = base_path.rstrip("/") if base_path != "/" else "/"

        print(f"  Uploading tree from {source} to {repo}{base_path}")

        # Cleanup if requested
        if cleanup:
            clean_repository_directory(base_url, app_key, repo=repo, list_path=base_path)

        # Ensure base path exists
        if base_path != "/":
            segments = [s for s in base_path.strip("/").split("/") if s]
            acc = ""
            for seg in segments:
                acc = f"{acc}/{seg}" if acc else f"/{seg}"
                print(f"    Creating folder: {acc}")
                invoke_repository_service(
                    base_url,
                    app_key,
                    repo=repo,
                    service="CreateFolder",
                    payload={"path": acc},
                    tolerate_errors=("already exists", "file already exists"),
                )

        # Walk directory and upload files
        uploader_url = f"{base_url}/FileRepositoryUploader"

        for root, dirs, files in os.walk(source_dir):
            # Skip hidden directories
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))
            files[:] = sorted(f for f in files if not f.startswith("."))

            root_path = Path(root)
            rel_root = root_path.relative_to(source_dir)

            # Create directories
            if rel_root != Path("."):
                rel_posix = rel_root.as_posix()
                remote_dir = f"{base_path}/{rel_posix}" if base_path != "/" else f"/{rel_posix}"
                remote_dir = remote_dir.rstrip("/") if remote_dir != "/" else "/"
                print(f"    Creating folder: {remote_dir}")
                invoke_repository_service(
                    base_url,
                    app_key,
                    repo=repo,
                    service="CreateFolder",
                    payload={"path": remote_dir},
                    tolerate_errors=("already exists", "file already exists"),
                )

            # Upload files
            for filename in files:
                file_path = root_path / filename
                rel_file = file_path.relative_to(source_dir)

                # Determine remote path
                if rel_file.parent == Path("."):
                    remote_dest = base_path
                else:
                    parent_posix = rel_file.parent.as_posix()
                    remote_dest = f"{base_path}/{parent_posix}" if base_path != "/" else f"/{parent_posix}"
                remote_dest = remote_dest.rstrip("/") if remote_dest != "/" else "/"

                print(f"    Uploading: {rel_file.as_posix()} -> {repo}{remote_dest}")
                boundary = f"----pyloader_{secrets.token_hex(12)}"
                mime, _ = mimetypes.guess_type(str(file_path))
                content_type = mime or "application/octet-stream"
                body = build_multipart_body_file_repository(
                    file_path,
                    boundary,
                    repo_name=repo,
                    dest_path=remote_dest,
                    content_type=content_type,
                )
                post_multipart(uploader_url, app_key, body, boundary, label="FileRepositoryUploader")


def action_download_file_tree(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Download directory tree from FileRepository."""
    source = step.get("source", "")
    target = step.get("target", "")
    cleanup = step.get("cleanup", False)
    use_zip = step.get("use_zip", False)

    if not source or not target:
        print("  (source and target required)", file=sys.stderr)
        sys.exit(1)

    # Parse source: first segment is repo name, rest is path
    parts = [p.strip() for p in source.replace("\\", "/").split("/") if p.strip()]
    if not parts:
        print("  (source must not be empty)", file=sys.stderr)
        sys.exit(1)

    repo = parts[0]
    remote_path = "/" + "/".join(parts[1:]) if len(parts) > 1 else "/"
    remote_path = remote_path.rstrip("/") if remote_path != "/" else "/"

    target_dir = work_dir / target
    target_dir.mkdir(parents=True, exist_ok=True)

    print(f"  Downloading tree from {repo}{remote_path} to {target}")

    # Cleanup if requested
    if cleanup and target_dir.exists():
        print(f"    Cleaning local directory: {target_dir}")
        for child in sorted(target_dir.iterdir()):
            if child.name.startswith("."):
                continue
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()

    if use_zip:
        # Use zip download method
        print("    Using zip archive method")
        download_tree_via_zip(base_url, app_key, repo, remote_path, target_dir)
    else:
        # Use file-by-file download
        download_tree(base_url, app_key, repo, remote_path, target_dir)


def download_tree(base_url: str, app_key: str, repo: str, base_path: str, output_dir: Path) -> None:
    """Download files recursively from FileRepository."""
    # GetFileListing returns files in this directory only. ListDirectories returns
    # immediate child folders. GetDirectoryStructure cannot be used: it has no
    # isDirectory field, ignores path, and the root row is name="/".
    file_rows = info_table_rows(
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="GetFileListing",
            payload={"path": base_path},
        )
    )
    dir_rows = info_table_rows(
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="ListDirectories",
            payload={"path": base_path},
        )
    )

    output_dir.mkdir(parents=True, exist_ok=True)

    for row in dir_rows:
        name = listing_leaf_name(row)
        if is_skipped_listing_name(name):
            continue
        subdir_path = repo_join(base_path, name)
        local_subdir = output_dir / name
        local_subdir.mkdir(exist_ok=True)
        print(f"    Entering directory: {name}/")
        download_tree(base_url, app_key, repo, subdir_path, local_subdir)

    for row in file_rows:
        name = listing_leaf_name(row)
        if is_skipped_listing_name(name):
            continue
        file_path = repo_join(base_path, name)
        local_file = output_dir / name
        print(f"    Downloading: {name}")
        download_file(base_url, app_key, repo, file_path, local_file)


def download_file(base_url: str, app_key: str, repo: str, remote_file: str, local_path: Path) -> None:
    """Download a single file from FileRepository."""
    if remote_file in ("", "/"):
        print(
            f"Download skipped for {remote_file!r}: FileRepositoryDownloader requires a file path",
            file=sys.stderr,
        )
        sys.exit(1)

    query = urllib.parse.urlencode({
        "download-repository": repo,
        "download-path": remote_file,
    })
    url = f"{base_url}/FileRepositoryDownloader?{query}"

    headers = {
        "appKey": app_key,
        "Accept": "*/*",
    }
    req = urllib.request.Request(url, method="GET")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S, context=SSL_CONTEXT) as resp:
            data = resp.read()
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_bytes(data)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"Download failed for {remote_file}: HTTP {e.code}: {err_body[:4000]}", file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, OSError) as e:
        print(f"Download failed for {remote_file}: {e}", file=sys.stderr)
        sys.exit(1)


def download_tree_via_zip(base_url: str, app_key: str, repo: str, base_path: str, output_dir: Path) -> None:
    """Download directory tree as a zip archive."""
    zip_name = f"{uuid.uuid4().hex}.zip"
    remote_zip = f"/{zip_name}"
    print(f"    Creating zip archive: {remote_zip}")

    invoke_repository_service(
        base_url,
        app_key,
        repo=repo,
        service="CreateZipArchive",
        payload={
            "newFileName": zip_name,
            "path": "/",
            "files": base_path,
        },
        timeout=600,
    )

    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".zip") as tmp:
            tmp_path = Path(tmp.name)
        download_file(base_url, app_key, repo, remote_zip, tmp_path)
        if not tmp_path.is_file() or tmp_path.stat().st_size == 0:
            print(f"Downloaded zip is missing or empty: {tmp_path}", file=sys.stderr)
            sys.exit(1)

        print(f"    Extracting archive to {output_dir}")
        output_dir.mkdir(parents=True, exist_ok=True)
        out_root = output_dir.resolve()

        with zipfile.ZipFile(tmp_path, "r") as zf:
            for info in zf.infolist():
                rel = zip_member_dest_rel(info.filename, base_path)
                if rel is None:
                    continue
                target_path = (output_dir / rel).resolve()
                try:
                    target_path.relative_to(out_root)
                except ValueError:
                    print(f"      skip unsafe zip member: {info.filename!r}")
                    continue
                if info.is_dir() or info.filename.endswith("/"):
                    target_path.mkdir(parents=True, exist_ok=True)
                    print(f"      mkdir {rel}/")
                    continue
                target_path.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as source, open(target_path, "wb") as target:
                    shutil.copyfileobj(source, target)
                print(f"      Extracted: {rel}")
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)
        print(f"    Deleting remote zip: {remote_zip}")
        invoke_repository_service(
            base_url,
            app_key,
            repo=repo,
            service="DeleteFile",
            payload={"path": remote_zip},
            tolerate_errors=("does not exist", "not found"),
        )


def action_get(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Retrieve entity JSON via REST GET."""
    # Support both entity/entities
    if "entity" in step:
        entities = [step["entity"]]
    else:
        entities = step.get("entities", [])

    # Support both output/outputs
    if "output" in step:
        outputs = [step["output"]]
    else:
        outputs = step.get("outputs", [])

    if not entities:
        print("  (no entities specified)")
        return

    # Align outputs with entities
    if not outputs:
        outputs = [None] * len(entities)
    elif len(outputs) != len(entities):
        print(f"  outputs length ({len(outputs)}) must match entities length ({len(entities)})", file=sys.stderr)
        sys.exit(1)

    for entity_spec, output in zip(entities, outputs):
        try:
            collection, name = parse_entity_spec(entity_spec)
        except ValueError as e:
            print(f"  Invalid entity spec {entity_spec!r}: {e}", file=sys.stderr)
            sys.exit(1)

        # Determine output path
        if output:
            out_path = work_dir / output
        else:
            out_path = work_dir / f"{collection}_{name}.json"

        print(f"  Getting {collection}/{name}")
        url = build_entity_json_url(base_url, collection, name)

        payload = http_get_json(url, app_key)

        # Trim effective fields
        if isinstance(payload, dict):
            payload.pop("lastModifiedDate", None)
            payload.pop("owner", None)
            for key in ("effectiveAlertConfiguration", "effectiveImplementedShapes",
                       "effectiveLocalPropertyBindings", "effectiveRemoteEventBindings",
                       "effectiveRemotePropertyBindings", "effectiveRemoteServiceBindings"):
                if key in payload:
                    payload[key] = {}

        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"    Saved to: {out_path.name}")


def action_export_entity(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Export entity as XML via /Exporter."""
    # Support both entity/entities
    if "entity" in step:
        entities = [step["entity"]]
    else:
        entities = step.get("entities", [])

    # Support both output/outputs
    if "output" in step:
        outputs = [step["output"]]
    else:
        outputs = step.get("outputs", [])

    if not entities:
        print("  (no entities specified)")
        return

    # Align outputs with entities
    if not outputs:
        outputs = [None] * len(entities)
    elif len(outputs) != len(entities):
        print(f"  outputs length ({len(outputs)}) must match entities length ({len(entities)})", file=sys.stderr)
        sys.exit(1)

    for entity_spec, output in zip(entities, outputs):
        try:
            collection, name = parse_entity_spec(entity_spec)
        except ValueError as e:
            print(f"  Invalid entity spec {entity_spec!r}: {e}", file=sys.stderr)
            sys.exit(1)

        # Determine output path
        if output:
            out_path = work_dir / output
            if not out_path.suffix:
                out_path = out_path.with_suffix(".xml")
        else:
            out_path = work_dir / f"{collection}_{name}.xml"

        print(f"  Exporting {collection}/{name}")
        url = build_entity_exporter_url(base_url, collection, name)

        xml_text = http_get_text(url, app_key, accept="text/xml")

        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(xml_text, encoding="utf-8")
        print(f"    Saved to: {out_path.name}")


def action_export_project(
    step: dict[str, Any], base_url: str, app_key: str, work_dir: Path
) -> None:
    """Export project as XML via /Exporter."""
    # Support both project/projects
    if "project" in step:
        projects = [step["project"]]
    else:
        projects = step.get("projects", [])

    # Support both output/outputs
    if "output" in step:
        outputs = [step["output"]]
    else:
        outputs = step.get("outputs", [])

    if not projects:
        print("  (no projects specified)")
        return

    # Align outputs with projects
    if not outputs:
        outputs = [None] * len(projects)
    elif len(outputs) != len(projects):
        print(f"  outputs length ({len(outputs)}) must match projects length ({len(projects)})", file=sys.stderr)
        sys.exit(1)

    for project_name, output in zip(projects, outputs):
        # Determine output path
        if output:
            out_path = work_dir / output
            if not out_path.suffix:
                out_path = out_path.with_suffix(".xml")
        else:
            out_path = work_dir / f"{project_name}.xml"

        print(f"  Exporting project: {project_name}")
        url = build_project_exporter_url(base_url, project_name)

        xml_text = http_get_text(url, app_key, accept="text/xml")

        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(xml_text, encoding="utf-8")
        print(f"    Saved to: {out_path.name}")


# ============================================================================
# Main execution
# ============================================================================


def execute_step(
    step: dict[str, Any],
    base_url: str,
    app_key: str,
    work_dir: Path,
    placeholders: dict[str, str],
) -> None:
    """Execute a single import step."""
    action = step.get("action", "")

    if action == "import_extension":
        action_import_extension(step, base_url, app_key, work_dir)
    elif action == "import_file":
        action_import_file(step, base_url, app_key, work_dir, placeholders)
    elif action == "import_data":
        action_import_data(step, base_url, app_key, work_dir)
    elif action == "rest_call":
        action_rest_call(step, base_url, app_key, work_dir, placeholders)
    elif action == "upload_file":
        action_upload_file(step, base_url, app_key, work_dir)
    elif action == "upload_file_tree":
        action_upload_file_tree(step, base_url, app_key, work_dir)
    elif action == "download_file_tree":
        action_download_file_tree(step, base_url, app_key, work_dir)
    elif action == "get":
        action_get(step, base_url, app_key, work_dir)
    elif action == "export_entity":
        action_export_entity(step, base_url, app_key, work_dir)
    elif action == "export_project":
        action_export_project(step, base_url, app_key, work_dir)
    else:
        print(f"  Unknown action: {action}", file=sys.stderr)
        sys.exit(1)


def print_plan(steps: list[dict[str, Any]]) -> None:
    """Print the import plan without executing."""
    print(f"Import plan ({len(steps)} steps):")
    for i, step in enumerate(steps, 1):
        action = step.get("action", "unknown")
        print(f"{i}. {action}")

        if action in ("import_extension", "import_file", "import_data"):
            if action == "import_data":
                zipped = step.get("files_zipped")
                if isinstance(zipped, str) and zipped.strip():
                    print(f"   files_zipped: {zipped.strip()}")
            files = step.get("files", [])
            for f in files:
                print(f"   - {f}")
        elif action == "upload_file":
            repo = step.get("file_repository", "")
            path = step.get("path", "/")
            files = step.get("files", [])
            print(f"   repo: {repo}, path: {path}")
            for f in files:
                print(f"   - {f}")
        elif action == "upload_file_tree":
            source = step.get("source", "")
            target = step.get("target", "")
            cleanup = step.get("cleanup", False)
            print(f"   {source} -> {target}")
            if cleanup:
                print(f"   cleanup: {cleanup}")
        elif action == "download_file_tree":
            source = step.get("source", "")
            target = step.get("target", "")
            cleanup = step.get("cleanup", False)
            use_zip = step.get("use_zip", False)
            print(f"   {source} -> {target}")
            if cleanup:
                print(f"   cleanup: {cleanup}")
            if use_zip:
                print(f"   use_zip: {use_zip}")
        elif action == "rest_call":
            service = step.get("service", "")
            method = step.get("method", "POST")
            print(f"   {method} {service}")
        elif action == "get":
            entities = step.get("entities", [])
            for e in entities:
                print(f"   - {e}")
        elif action == "export_entity":
            entities = step.get("entities", [])
            for e in entities:
                print(f"   - {e}")
        elif action == "export_project":
            projects = step.get("projects", [])
            for p in projects:
                print(f"   - {p}")


def resolve_resume_index(resume: int | None, step_count: int) -> int:
    """Return the 1-based start index. ``resume`` matches ``[N/M]`` in execution logs."""
    if resume is None:
        return 1
    if step_count < 1:
        print("No steps defined; nothing to resume", file=sys.stderr)
        sys.exit(1)
    if resume < 1 or resume > step_count:
        print(
            f"--resume {resume} is out of range (valid: 1-{step_count})",
            file=sys.stderr,
        )
        sys.exit(1)
    return resume


def main() -> None:
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="ThingWorx import/export control executor"
    )
    parser.add_argument(
        "-c",
        "--config-file",
        required=True,
        help="Control YAML file (required)",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Execute the plan (default: dry-run)",
    )
    parser.add_argument(
        "--resume",
        type=int,
        metavar="INDEX",
        default=None,
        help=(
            "1-based step index to start from (same number as [N/M] in logs). "
            "Earlier steps are skipped. Use with --apply after a failure."
        ),
    )
    parser.add_argument(
        "--from-env",
        action="append",
        default=None,
        metavar="FILE",
        help=(
            "Env file for {{{env:NAME}}} in payload_file and import_file .xml. "
            "Repeat to add files; later files override earlier ones. "
            "Then .env, then the process environment."
        ),
    )
    args = parser.parse_args()

    launch_dir = Path.cwd()
    work_dir = launch_dir

    # Load .env from current directory
    load_dotenv(work_dir / ".env")

    # Load control file
    control_path = work_dir / args.config_file
    if not control_path.is_file():
        print(f"Control file not found: {control_path}", file=sys.stderr)
        sys.exit(1)

    with control_path.open(encoding="utf-8") as f:
        control_data = yaml.safe_load(f)

    if not isinstance(control_data, dict):
        print("Control file must be a YAML mapping", file=sys.stderr)
        sys.exit(1)

    # Support root_folder for relative path resolution
    root_folder = control_data.get("root_folder")
    if root_folder:
        if not isinstance(root_folder, str):
            print("root_folder must be a string", file=sys.stderr)
            sys.exit(1)
        root_path = Path(root_folder)
        if root_path.is_absolute():
            work_dir = root_path
        else:
            work_dir = (work_dir / root_path).resolve()
        print(f"Using root_folder: {work_dir}")

    # Get steps from import_order or export_order
    import_order = control_data.get("import_order")
    export_order = control_data.get("export_order")

    if import_order is not None and export_order is not None:
        print("Specify only one of import_order or export_order, not both", file=sys.stderr)
        sys.exit(1)

    if export_order is not None:
        steps = export_order
        order_type = "export"
    elif import_order is not None:
        steps = import_order
        order_type = "import"
    else:
        print("No import_order or export_order defined")
        return

    if not isinstance(steps, list):
        print(f"{order_type}_order must be a list", file=sys.stderr)
        sys.exit(1)

    if not steps:
        print(f"No steps defined in {order_type}_order")
        return

    # Print plan
    print_plan(steps)
    start = resolve_resume_index(args.resume, len(steps))
    validate_files_zipped_fields(steps)
    check_github_release_refs(steps)

    if not args.apply:
        if start > 1:
            print(f"\nWould resume from step {start}/{len(steps)}.")
        print("\nDry-run complete. Use --apply to execute.")
        return

    placeholders = build_placeholder_map(launch_dir, args.from_env)

    # Execute plan
    print("\n" + "=" * 60)
    print("EXECUTING PLAN")
    print("=" * 60 + "\n")

    # Get credentials
    twx_url = require_env("TWX_URL")
    app_key = require_env("TWX_APPKEY")
    base_url = normalize_base_url(twx_url)

    print(f"Target: {base_url}\n")
    if start > 1:
        print(
            f"Resuming from step {start}/{len(steps)} "
            f"(skipping {start - 1} completed step(s))\n"
        )

    for i, step in enumerate(steps, 1):
        if i < start:
            continue
        action = step.get("action", "unknown")
        print(f"[{i}/{len(steps)}] {action}")
        execute_step(step, base_url, app_key, work_dir, placeholders)
        print()

    print("=" * 60)
    print("PLAN COMPLETE")
    print("=" * 60)


if __name__ == "__main__":
    main()

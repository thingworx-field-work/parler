"""
Upload or download a directory tree to/from a ThingWorx FileRepository on DEV_SERVER.

Uses the same ``.env`` credentials as ``import-dev`` (``DEV_SERVER``, ``DEV_KEY``).

  uv sync
  uv run load-file-tree -i dev_data/scpa_utilization/skills -t ConfigurationRepository
  uv run load-file-tree -i ./my-tree -t ConfigurationRepository/demo --clean
  uv run load-file-tree -d -i ./backup -t ConfigurationRepository/demo
  uv run load-file-tree -d -i ./backup -t ConfigurationRepository/demo --clean

Default mode uploads local ``-i`` to ``-t``. With ``-d`` / ``--download``, pulls from ``-t``
into local ``-i`` (creating ``-i`` when missing).

``-t`` is split on ``/`` (empty segments dropped): first segment is the FileRepository
Thing name; remaining segments are a repository subdirectory.

``--clean`` before upload clears the remote target subdirectory; before download clears the
local ``-i`` directory (immediate children only; folder contents are not listed separately).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any

from test_scripts.dev_import_control import (
    _merge_rest_headers,
    file_repository_uploader_post_url,
    load_dotenv,
    normalize_repository_dest_path,
    post_file_repository_upload,
    require_env,
    rest_call_url,
)
from test_scripts.live_diagnostics.collect import (
    build_file_repository_download_url,
    extract_rows_from_service_result,
    listing_row_is_directory,
    listing_row_name,
)

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"

REST_TIMEOUT_S = 120.0
DEFAULT_APP_KEY = "DEV_KEY"
DOWNLOAD_MAX_BYTES = 10 * 1024 * 1024 * 1024  # 10 GiB
CREATE_FOLDER_EXISTS_MARKERS = ("file already exists", "already exists")
LISTING_MISSING_DIR_MARKERS = ("directory does not exist",)
LISTING_SERVICES = (
    "GetFileListingWithLinks",
    "GetFileListing",
    "BrowseDirectory",
)


def parse_target(raw: str) -> tuple[str, str]:
    """
    Split ``-t`` on ``/`` (drop empties).

    First segment: FileRepository Thing name. Remaining segments: repository path prefix
    (e.g. ``ConfigurationRepository/demo`` → ``ConfigurationRepository``, ``/demo``).
    """
    parts = [p.strip() for p in raw.replace("\\", "/").split("/") if p.strip()]
    if not parts:
        raise ValueError("--target must not be empty")
    repo = parts[0]
    if len(parts) == 1:
        return repo, "/"
    base = normalize_repository_dest_path("/" + "/".join(parts[1:]))
    return repo, base


def remote_path_under_base(rel_posix: str, base: str) -> str:
    rel = rel_posix.strip().replace("\\", "/").strip("/")
    if not rel:
        return base if base != "/" else "/"
    if base == "/":
        return normalize_repository_dest_path(f"/{rel}")
    return normalize_repository_dest_path(f"{base}/{rel}")


def ensure_remote_base_path(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    base: str,
) -> None:
    """Create the target subdirectory prefix (e.g. ``/demo``), tolerating already exists."""
    if base == "/":
        return
    segments = [s for s in base.strip("/").split("/") if s]
    acc = ""
    for seg in segments:
        acc = normalize_repository_dest_path(f"{acc}/{seg}" if acc else f"/{seg}")
        create_folder(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            remote_path=acc,
        )


def repository_service_path(repo: str, service: str) -> str:
    name = repo.strip()
    svc = service.strip()
    return f"/Thingworx/Things/{name}/services/{svc}"


def invoke_repository_service(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    service: str,
    payload: dict[str, Any],
    timeout_s: float = REST_TIMEOUT_S,
    tolerate_substrings: tuple[str, ...] = (),
) -> Any:
    url = rest_call_url(dev_server, repository_service_path(repo, service))
    headers = _merge_rest_headers(app_key, {})
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=body_bytes, method="POST")
    for hk, hv in headers.items():
        req.add_header(hk, hv)

    label = f"{repo}.{service}"
    raw = b""
    code = 0
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            code = resp.getcode()
            raw = resp.read()
    except urllib.error.HTTPError as e:
        code = int(e.code)
        err_body = e.read().decode("utf-8", errors="replace")
        if tolerate_substrings and _message_tolerated(err_body, tolerate_substrings):
            return None
        msg = err_body.strip() or "(no response body)"
        print(f"{label} HTTP {code}: {msg[:4000]}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"{label} network error: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"{label} request failed: {e}", file=sys.stderr)
        sys.exit(1)

    if not (200 <= code < 300):
        text = raw.decode("utf-8", errors="replace").strip()
        if tolerate_substrings and _message_tolerated(text, tolerate_substrings):
            return None
        print(f"{label} HTTP {code}: {text[:4000]}", file=sys.stderr)
        sys.exit(1)

    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError:
        return raw.decode("utf-8", errors="replace")


def _message_tolerated(text: str, markers: tuple[str, ...]) -> bool:
    low = text.lower()
    return any(marker.lower() in low for marker in markers)


def parse_listing_rows(response: Any) -> list[dict[str, Any]]:
    return extract_rows_from_service_result(response)


def fetch_directory_listing(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    list_path: str,
) -> tuple[list[dict[str, Any]], str]:
    """List immediate children of ``list_path``; try listing services until one returns rows."""
    list_path = normalize_repository_dest_path(list_path)
    last_service = LISTING_SERVICES[-1]
    for service in LISTING_SERVICES:
        listing = invoke_repository_service(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            service=service,
            payload={"path": list_path},
            tolerate_substrings=LISTING_MISSING_DIR_MARKERS,
        )
        if listing is None:
            last_service = service
            continue
        rows = parse_listing_rows(listing)
        if rows:
            return rows, service
        last_service = service
    return [], last_service


def repo_path_join(parent: str, name: str) -> str:
    parent_norm = normalize_repository_dest_path(parent)
    if parent_norm == "/":
        return normalize_repository_dest_path(f"/{name}")
    return normalize_repository_dest_path(f"{parent_norm}/{name}")


def classify_root_listing_rows(rows: list[dict[str, Any]]) -> tuple[list[str], list[str]]:
    files: list[str] = []
    folders: list[str] = []
    for row in rows:
        name = listing_row_name(row)
        if not name:
            continue
        path_raw = row.get("path")
        if isinstance(path_raw, str) and path_raw.strip():
            path = normalize_repository_dest_path(path_raw.strip())
        else:
            path = repo_path_join("/", name)
        is_dir = listing_row_is_directory(row)
        if is_dir is True:
            folders.append(path)
        elif is_dir is False:
            files.append(path)
        elif "." in name:
            files.append(path)
        else:
            folders.append(path)
    files.sort()
    folders.sort()
    return files, folders


def clean_repository_directory(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    list_path: str,
) -> None:
    list_path = normalize_repository_dest_path(list_path)
    target_display = f"{repo}{list_path}"
    print(f"Cleaning repository directory: {target_display}", flush=True)
    rows, listing_service = fetch_directory_listing(
        dev_server=dev_server,
        app_key=app_key,
        repo=repo,
        list_path=list_path,
    )
    if not rows:
        print(f"  (nothing to remove under {list_path})", flush=True)
        return

    files, folders = classify_root_listing_rows(rows)
    print(
        f"  listed via {listing_service} ({len(files)} file(s), {len(folders)} folder(s) under {list_path})",
        flush=True,
    )

    if files:
        print("  files to delete:", flush=True)
        for path in files:
            print(f"    {path}", flush=True)

    if folders:
        print("  folders to delete (contents removed with folder):", flush=True)
        for path in folders:
            print(f"    {path}", flush=True)

    for path in files:
        invoke_repository_service(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            service="DeleteFile",
            payload={"path": path},
        )

    for path in folders:
        invoke_repository_service(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            service="DeleteFolder",
            payload={"path": path},
        )

    print(
        f"  deleted {len(files)} file(s) and {len(folders)} folder(s) under {list_path}",
        flush=True,
    )


def create_folder(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    remote_path: str,
) -> None:
    result = invoke_repository_service(
        dev_server=dev_server,
        app_key=app_key,
        repo=repo,
        service="CreateFolder",
        payload={"path": remote_path},
        tolerate_substrings=CREATE_FOLDER_EXISTS_MARKERS,
    )
    if result is None:
        print(f"  CreateFolder {remote_path} (already exists)", flush=True)
    else:
        print(f"  CreateFolder {remote_path}", flush=True)


def collect_local_tree(input_dir: Path) -> tuple[list[str], list[Path]]:
    """Return sorted relative directory paths (posix) and file paths under input_dir."""
    dir_set: set[str] = set()
    files: list[Path] = []

    for root, dirnames, filenames in os_walk_skip_hidden(input_dir):
        root_path = Path(root)
        rel_root = root_path.relative_to(input_dir)
        if rel_root.parts:
            dir_set.add(rel_root.as_posix())

        for d in dirnames:
            rel = (root_path / d).relative_to(input_dir)
            dir_set.add(rel.as_posix())

        for f in filenames:
            files.append((root_path / f).relative_to(input_dir))

    dirs_sorted = sorted(dir_set, key=lambda p: (p.count("/"), p))
    return dirs_sorted, files


def os_walk_skip_hidden(input_dir: Path):
    for root, dirnames, filenames in os.walk(input_dir):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        filenames[:] = sorted(f for f in filenames if not f.startswith("."))
        yield root, dirnames, filenames


def remote_dir_for_file(rel_file: PurePosixPath, base: str) -> str:
    parent = rel_file.parent
    if str(parent) in ("", "."):
        return base if base != "/" else "/"
    return remote_path_under_base(parent.as_posix(), base)


def remote_path_to_local_rel(remote_path: str, base: str) -> str:
    """Map a repository file path to a relative path under local ``-i``."""
    remote = normalize_repository_dest_path(remote_path)
    base_norm = normalize_repository_dest_path(base)
    if base_norm == "/":
        return remote.lstrip("/")
    prefix = base_norm.rstrip("/") + "/"
    if remote == base_norm:
        return ""
    if not remote.startswith(prefix):
        raise ValueError(f"remote path {remote!r} is not under base {base_norm!r}")
    return remote[len(prefix) :]


def list_remote_files_recursive(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    list_path: str,
) -> list[str]:
    """Collect all file paths under ``list_path`` (depth-first)."""
    list_path = normalize_repository_dest_path(list_path)
    rows, _ = fetch_directory_listing(
        dev_server=dev_server,
        app_key=app_key,
        repo=repo,
        list_path=list_path,
    )
    if not rows:
        return []

    files, folders = classify_root_listing_rows(rows)
    for folder in folders:
        files.extend(
            list_remote_files_recursive(
                dev_server=dev_server,
                app_key=app_key,
                repo=repo,
                list_path=folder,
            )
        )
    return files


def download_repository_file(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    remote_path: str,
    local_path: Path,
) -> None:
    url = build_file_repository_download_url(dev_server, repo, remote_path)
    req = urllib.request.Request(
        url,
        method="GET",
        headers={
            "appKey": app_key,
            "Accept": "*/*",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=REST_TIMEOUT_S) as resp:
            data = resp.read(DOWNLOAD_MAX_BYTES + 1)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(
            f"Download HTTP {e.code} for {remote_path}: {err_body[:2000]}",
            file=sys.stderr,
        )
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"Download failed for {remote_path}: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"Download failed for {remote_path}: {e}", file=sys.stderr)
        sys.exit(1)

    if len(data) > DOWNLOAD_MAX_BYTES:
        print(
            f"Download exceeds 10 GiB limit: {remote_path}",
            file=sys.stderr,
        )
        sys.exit(1)

    local_path.parent.mkdir(parents=True, exist_ok=True)
    local_path.write_bytes(data)


def download_tree(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    base: str,
    output_dir: Path,
) -> None:
    target_label = f"{repo}{base}" if base != "/" else repo
    remote_files = list_remote_files_recursive(
        dev_server=dev_server,
        app_key=app_key,
        repo=repo,
        list_path=base,
    )
    remote_files = sorted(set(remote_files))

    if not remote_files:
        print(f"No files found under {target_label!r}", flush=True)
        return

    print(f"Downloading {len(remote_files)} file(s) from {target_label!r}", flush=True)
    for remote_path in remote_files:
        rel = remote_path_to_local_rel(remote_path, base)
        if not rel:
            continue
        local_path = output_dir / rel
        print(f"  {remote_path} → {local_path.relative_to(output_dir)}", flush=True)
        download_repository_file(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            remote_path=remote_path,
            local_path=local_path,
        )


def clean_local_directory(output_dir: Path) -> None:
    print(f"Cleaning local directory: {output_dir}", flush=True)
    if not output_dir.is_dir():
        print("  (directory does not exist yet)", flush=True)
        return

    entries = sorted(output_dir.iterdir(), key=lambda p: p.name.lower())
    if not entries:
        print("  (nothing to remove)", flush=True)
        return

    files = [e for e in entries if e.is_file()]
    folders = [e for e in entries if e.is_dir()]
    other = [e for e in entries if not e.is_file() and not e.is_dir()]

    print(
        f"  ({len(files)} file(s), {len(folders)} folder(s) under {output_dir})",
        flush=True,
    )

    if files:
        print("  files to delete:", flush=True)
        for path in files:
            print(f"    {path.name}", flush=True)

    if folders:
        print("  folders to delete (contents removed with folder):", flush=True)
        for path in folders:
            print(f"    {path.name}/", flush=True)

    for path in files:
        path.unlink()

    for path in folders:
        shutil.rmtree(path)

    for path in other:
        path.unlink(missing_ok=True)

    print(
        f"  deleted {len(files)} file(s) and {len(folders)} folder(s) locally",
        flush=True,
    )


def upload_tree(
    *,
    dev_server: str,
    app_key: str,
    repo: str,
    base: str,
    input_dir: Path,
) -> None:
    ensure_remote_base_path(dev_server=dev_server, app_key=app_key, repo=repo, base=base)

    dirs, files = collect_local_tree(input_dir)
    upload_url = file_repository_uploader_post_url(dev_server)

    target_label = f"{repo}{base}" if base != "/" else repo
    print(f"Creating {len(dirs)} folder(s) on {target_label!r}", flush=True)
    for rel_dir in dirs:
        remote_path = remote_path_under_base(rel_dir, base)
        create_folder(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            remote_path=remote_path,
        )

    print(f"Uploading {len(files)} file(s) to {target_label!r}", flush=True)
    for rel in files:
        rel_posix = PurePosixPath(rel.as_posix())
        local_path = (input_dir / rel).resolve()
        dest_path = remote_dir_for_file(rel_posix, base)
        remote_file = remote_path_under_base(rel.as_posix(), base)
        print(
            f"  {rel.as_posix()} → {remote_file}",
            flush=True,
        )
        post_file_repository_upload(
            upload_url,
            app_key,
            local_path,
            repo_name=repo,
            dest_path=dest_path,
        )


def resolve_input_dir(raw: str, *, must_exist: bool) -> Path:
    p = Path(raw).expanduser()
    if not p.is_absolute():
        p = (REPO_ROOT / p).resolve()
    else:
        p = p.resolve()
    if p.exists() and not p.is_dir():
        print(f"Input path exists but is not a directory: {p}", file=sys.stderr)
        sys.exit(1)
    if not p.exists():
        if must_exist:
            print(f"Input directory does not exist: {p}", file=sys.stderr)
            sys.exit(1)
        p.mkdir(parents=True, exist_ok=True)
        print(f"Created local directory: {p}", flush=True)
    return p


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Upload or download a directory tree to/from a ThingWorx FileRepository (DEV_SERVER).",
    )
    parser.add_argument(
        "-i",
        "--input",
        required=True,
        metavar="DIR",
        help="Local directory. Upload: must exist. Download: created when missing.",
    )
    parser.add_argument(
        "-t",
        "--target",
        required=True,
        metavar="REPOSITORY[/PATH]",
        help=(
            "FileRepository Thing name, optionally followed by a subdirectory "
            "(e.g. ConfigurationRepository or ConfigurationRepository/demo)."
        ),
    )
    parser.add_argument(
        "-d",
        "--download",
        action="store_true",
        help="Download from the repository target into local -i (default: upload).",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help=(
            "Upload: clear remote target subdirectory before upload. "
            "Download: clear local -i before download."
        ),
    )
    args = parser.parse_args()

    load_dotenv(ENV_PATH)
    dev_server = require_env("DEV_SERVER")
    app_key = require_env(DEFAULT_APP_KEY)
    try:
        repo, base = parse_target(args.target)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)

    input_dir = resolve_input_dir(args.input, must_exist=not args.download)
    print(f"Input:  {input_dir}", flush=True)
    target_display = f"{repo}{base}" if base != "/" else repo
    print(f"Target: {target_display} @ {dev_server.rstrip('/')}", flush=True)
    mode = "download" if args.download else "upload"
    print(f"Mode:   {mode}", flush=True)

    if args.clean:
        if args.download:
            clean_local_directory(input_dir)
        else:
            clean_repository_directory(
                dev_server=dev_server,
                app_key=app_key,
                repo=repo,
                list_path=base,
            )

    if args.download:
        download_tree(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            base=base,
            output_dir=input_dir,
        )
    else:
        upload_tree(
            dev_server=dev_server,
            app_key=app_key,
            repo=repo,
            base=base,
            input_dir=input_dir,
        )
    print("Done.", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env -S uv run --quiet python
"""Verify live AIDocRepository /document-knowledge layout (Phase 3 B-live gate).

Checks that the three sub-manuals are present on DEV_SERVER and the 754-page
master compilation is absent from the live index path.

Usage (from repo root, requires .env DEV_SERVER + DEV_KEY):

    uv run test_scripts/verify_aidoc_live_index.py
    uv run test_scripts/verify_aidoc_live_index.py --delete-master-if-present
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from test_scripts.dev_import_control import load_dotenv, require_env
from test_scripts.load_file_tree import (
    fetch_directory_listing,
    invoke_repository_service,
    listing_row_is_directory,
    listing_row_name,
)

REPO_ROOT = Path(__file__).resolve().parents[1]
LIVE_INDEX_PATH = "/document-knowledge"
MASTER_DOC_ID = "rk-t-turbogenerator-master-7318042"
MASTER_DELETE_PATH = f"{LIVE_INDEX_PATH}/{MASTER_DOC_ID}"
REQUIRED_LIVE_DOC_IDS = (
    "kbm-coupling-manual-7318042",
    "rk-t-install-spec-7318042",
    "rk-t-operating-manual-7318042",
)
DELETE_FOLDER_MISSING_MARKERS = (
    "directory does not exist",
    "does not exist",
    "not found",
)


def list_live_doc_ids(*, dev_server: str, app_key: str) -> tuple[list[str], str]:
    rows, listing_service = fetch_directory_listing(
        dev_server=dev_server,
        app_key=app_key,
        repo="AIDocRepository",
        list_path=LIVE_INDEX_PATH,
    )
    dirs = sorted(listing_row_name(r) for r in rows if listing_row_is_directory(r))
    return dirs, listing_service


def delete_live_master_if_present(*, dev_server: str, app_key: str) -> bool:
    dirs, listing_service = list_live_doc_ids(dev_server=dev_server, app_key=app_key)
    print(
        f"Remote {LIVE_INDEX_PATH} ({listing_service}): {', '.join(dirs) or '(empty)'}",
        flush=True,
    )
    if MASTER_DOC_ID not in dirs:
        print(f"skip master delete ({MASTER_DOC_ID} not in live index)", flush=True)
        return False
    invoke_repository_service(
        dev_server=dev_server,
        app_key=app_key,
        repo="AIDocRepository",
        service="DeleteFolder",
        payload={"path": MASTER_DELETE_PATH},
        tolerate_substrings=DELETE_FOLDER_MISSING_MARKERS,
    )
    print(f"deleted remote folder {MASTER_DELETE_PATH}", flush=True)
    return True


def verify_live_index_layout(*, dev_server: str, app_key: str) -> int:
    dirs, listing_service = list_live_doc_ids(dev_server=dev_server, app_key=app_key)
    print(
        f"Remote {LIVE_INDEX_PATH} ({listing_service}): {', '.join(dirs) or '(empty)'}",
        flush=True,
    )

    errors: list[str] = []
    if MASTER_DOC_ID in dirs:
        errors.append(f"{MASTER_DOC_ID} must not be in live index (delete remote folder)")
    for doc_id in REQUIRED_LIVE_DOC_IDS:
        if doc_id not in dirs:
            errors.append(f"missing required live package: {doc_id}")

    if errors:
        for e in errors:
            print(f"FAIL {e}", file=sys.stderr)
        return 1

    print("OK   live index layout on DEV_SERVER")
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--delete-master-if-present",
        action="store_true",
        help="Delete rk-t-turbogenerator-master-7318042 from live index when listed (no-op otherwise)",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(REPO_ROOT / ".env")
    server = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")

    if args.delete_master_if_present:
        delete_live_master_if_present(dev_server=server, app_key=key)

    return verify_live_index_layout(dev_server=server, app_key=key)


if __name__ == "__main__":
    sys.exit(main())

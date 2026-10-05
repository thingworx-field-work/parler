"""
Seed simulated ``currentDraw`` valuestream data for CellFab stacking robots.

Deletes existing history (time < cutoff), then inserts minute samples from a
synchronized 2-hour production cycle. Robot 01 may include steady-state anomalies
from 2026-06-29 EDT onward; robot 02 stays nominal. Per-robot sample timestamps
jitter independently (±5 s) while cycle boundaries stay aligned.

Reads ``DEV_SERVER`` and ``DEV_KEY`` from the parler repo root ``.env``.

Run from repository root::

  uv run seed-cellfab-current-draw -e 20260702T060000 --dry-run
  uv run seed-cellfab-current-draw -e 20260702T060000 -s 20260615T040000
  uv run seed-cellfab-current-draw -e 20260702T060000 --delete-only
  uv run seed-cellfab-current-draw -e 20260702T060000 --no-resume
  uv run seed-cellfab-current-draw -e 20260702T060000 --export-csv dev_data/cellfab-current-draw.csv

``-e`` / ``--end`` (required) and ``-s`` / ``--start`` (optional) use compact UTC
timestamps. Accepted forms (omitted trailing fields default to zero):

- ``YYYYmmdd`` → ``T00:00:00``
- ``YYYYmmddTHH`` → ``:00:00``
- ``YYYYmmddTHHMM`` → ``:00``
- ``YYYYmmddTHHMMSS`` (full)

Example: ``20260702T06`` → ``2026-07-02T06:00:00.000Z``. When ``--start`` is omitted,
start defaults to ``end - 15 days``.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterator

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"
DEFAULT_STATE_PATH = REPO_ROOT / "dev_data" / "cellfab-current-draw-progress.json"

ENTITY_ID = "PTCTDD.CellfabDataset.ValueStream_VS"
PROPERTY_NAME = "currentDraw"
ROBOT_01 = "SE.CellFab.Model.Workunit.BOS-StackingRobot-01"
ROBOT_02 = "SE.CellFab.Model.Workunit.BOS-StackingRobot-02"

DELETE_SERVICE = "/Thingworx/Things/database_connect/Services/delete_valuestream"
INSERT_SERVICE = "/Thingworx/Things/database_connect/Services/insert_valuestream"

# Robot 01 steady anomalies begin at this fixed UTC instant (2026-06-29 00:00 EDT).
ANOMALY_START_UTC = datetime(2026, 6, 29, 4, 0, 0, tzinfo=timezone.utc)

DEFAULT_WINDOW_DAYS = 15
_UTC_COMPACT_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"^\d{8}T\d{6}$"), "%Y%m%dT%H%M%S"),
    (re.compile(r"^\d{8}T\d{4}$"), "%Y%m%dT%H%M"),
    (re.compile(r"^\d{8}T\d{2}$"), "%Y%m%dT%H"),
    (re.compile(r"^\d{8}$"), "%Y%m%d"),
)

CYCLE_MINUTES = 120
RAMP_MINUTES = 10
PLATEAU_TARGET = 7.0
STEADY_LOW = 6.5
STEADY_HIGH = 7.5
ANOMALY_LOW = 5.0
ANOMALY_HIGH = 8.5

JITTER_SECONDS = 5
HTTP_TIMEOUT_S = 120

ROBOTS = (
    (ROBOT_01, 11_001),
    (ROBOT_02, 22_002),
)


@dataclass(frozen=True)
class RunWindow:
    start_utc: datetime
    end_exclusive_utc: datetime

    @property
    def delete_cutoff_utc(self) -> datetime:
        return self.end_exclusive_utc


# Set from CLI in main(); all generators read this module-level window.
RUN_WINDOW: RunWindow | None = None


@dataclass(frozen=True)
class Sample:
    source_id: str
    time: datetime
    value: float
    nominal_index: int

    @property
    def time_iso(self) -> str:
        return format_iso_z(self.time)

    @property
    def value_str(self) -> str:
        return f"{self.value:.3f}"


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
    value = os.environ.get(name, "").strip()
    if not value:
        print(f"Missing required environment variable: {name} (set in {ENV_PATH})", file=sys.stderr)
        sys.exit(1)
    return value


def format_iso_z(dt: datetime) -> str:
    dt = dt.astimezone(timezone.utc)
    ms = dt.microsecond // 1000
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + f".{ms:03d}Z"


def format_utc_compact(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%S")


def parse_utc_compact(value: str) -> datetime:
    text = value.strip()
    for pattern, fmt in _UTC_COMPACT_PATTERNS:
        if pattern.fullmatch(text):
            parsed = datetime.strptime(text, fmt)
            return parsed.replace(tzinfo=timezone.utc)
    raise argparse.ArgumentTypeError(
        f"Invalid UTC timestamp {value!r}; expected YYYYmmdd[THH[MM[SS]]] "
        f"(e.g. 20260702, 20260702T06, 20260702T0600, 20260702T060000); omitted fields are zero"
    )


def require_run_window() -> RunWindow:
    if RUN_WINDOW is None:
        raise RuntimeError("RUN_WINDOW is not configured")
    return RUN_WINDOW


def resolve_run_window(end: datetime, start: datetime | None) -> RunWindow:
    start_utc = start if start is not None else end - timedelta(days=DEFAULT_WINDOW_DAYS)
    if start_utc >= end:
        print(
            f"Error: --start ({format_utc_compact(start_utc)}) must be before "
            f"--end ({format_utc_compact(end)})",
            file=sys.stderr,
        )
        sys.exit(2)
    return RunWindow(start_utc=start_utc, end_exclusive_utc=end)


def build_service_url(dev_server: str, service_path: str) -> str:
    base = dev_server.strip().rstrip("/")
    if base.lower().endswith("/thingworx"):
        return base + service_path.removeprefix("/Thingworx")
    return base + service_path


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
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
        raw = resp.read().decode("utf-8")
    if not raw:
        return {}
    return json.loads(raw)


def smoother_ramp(progress: float) -> float:
    progress = max(0.0, min(1.0, progress))
    return 0.5 * (1.0 - math.cos(math.pi * progress))


def cycle_start_for(ts: datetime) -> datetime:
    window = require_run_window()
    elapsed = ts - window.start_utc
    cycle_index = int(elapsed.total_seconds() // (CYCLE_MINUTES * 60))
    return window.start_utc + timedelta(minutes=CYCLE_MINUTES * cycle_index)


def phase_minutes(ts: datetime) -> float:
    return (ts - cycle_start_for(ts)).total_seconds() / 60.0


def ramp_value(phase: float) -> float:
    if phase < RAMP_MINUTES:
        return PLATEAU_TARGET * smoother_ramp(phase / RAMP_MINUTES)
    if phase >= CYCLE_MINUTES - RAMP_MINUTES:
        progress = (CYCLE_MINUTES - phase) / RAMP_MINUTES
        return PLATEAU_TARGET * smoother_ramp(progress)
    return PLATEAU_TARGET


def is_steady_phase(phase: float) -> bool:
    return RAMP_MINUTES <= phase < CYCLE_MINUTES - RAMP_MINUTES


def anomaly_minute_buckets(cycle_start: datetime, source_id: str) -> set[int]:
    if source_id != ROBOT_01 or cycle_start < ANOMALY_START_UTC:
        return set()
    cycle_index = int((cycle_start - require_run_window().start_utc).total_seconds() // (CYCLE_MINUTES * 60))
    rng = random.Random(31_415 + cycle_index)
    count = rng.randint(1, 3)
    return set(rng.sample(range(RAMP_MINUTES, CYCLE_MINUTES - RAMP_MINUTES), count))


def steady_normal_value(rng: random.Random) -> float:
    return rng.uniform(STEADY_LOW, STEADY_HIGH)


def anomaly_value(rng: random.Random) -> float:
    if rng.random() < 0.5:
        return rng.uniform(ANOMALY_LOW, STEADY_LOW)
    return rng.uniform(STEADY_HIGH, ANOMALY_HIGH)


def sample_value(
    source_id: str,
    ts: datetime,
    value_rng: random.Random,
) -> float:
    phase = phase_minutes(ts)
    if not is_steady_phase(phase):
        return ramp_value(phase)

    cycle_start = cycle_start_for(ts)
    if int(phase) in anomaly_minute_buckets(cycle_start, source_id):
        return anomaly_value(value_rng)

    return steady_normal_value(value_rng)


def jittered_time(nominal: datetime, nominal_index: int, jitter_rng: random.Random) -> datetime:
    window = require_run_window()
    offset_ms = jitter_rng.randint(-JITTER_SECONDS * 1000, JITTER_SECONDS * 1000)
    candidate = nominal + timedelta(milliseconds=offset_ms)
    if candidate >= window.end_exclusive_utc:
        candidate = window.end_exclusive_utc - timedelta(milliseconds=1)
    if candidate < window.start_utc:
        candidate = window.start_utc
    return candidate


def iter_nominal_minutes() -> Iterator[tuple[int, datetime]]:
    window = require_run_window()
    index = 0
    nominal = window.start_utc
    while nominal < window.end_exclusive_utc:
        yield index, nominal
        index += 1
        nominal += timedelta(minutes=1)


def iter_samples_for_robot(source_id: str, seed: int) -> Iterator[Sample]:
    jitter_rng = random.Random(seed)
    value_rng = random.Random(seed + 1)

    for nominal_index, nominal in iter_nominal_minutes():
        ts = jittered_time(nominal, nominal_index, jitter_rng)
        value = sample_value(source_id, ts, value_rng)
        yield Sample(source_id=source_id, time=ts, value=value, nominal_index=nominal_index)


def load_progress(path: Path) -> dict[str, int]:
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    progress = data.get("last_nominal_index", {})
    if not isinstance(progress, dict):
        return {}
    out: dict[str, int] = {}
    for source_id, idx in progress.items():
        if isinstance(source_id, str) and isinstance(idx, int):
            out[source_id] = idx
    return out


def save_progress(path: Path, progress: dict[str, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "last_nominal_index": progress,
        "updated_at": format_iso_z(datetime.now(timezone.utc)),
    }
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def delete_history(dev_server: str, dev_key: str, source_id: str) -> None:
    window = require_run_window()
    url = build_service_url(dev_server, DELETE_SERVICE)
    payload = {
        "entity_id": ENTITY_ID,
        "source_id": source_id,
        "property_name": PROPERTY_NAME,
        "time": format_iso_z(window.delete_cutoff_utc),
    }
    post_json(url, dev_key, payload)
    print(f"delete_valuestream OK: {source_id} (< {payload['time']})")


def insert_sample(dev_server: str, dev_key: str, sample: Sample) -> None:
    url = build_service_url(dev_server, INSERT_SERVICE)
    payload = {
        "source_id": sample.source_id,
        "property_name": PROPERTY_NAME,
        "property_value": sample.value_str,
        "entity_id": ENTITY_ID,
        "time": sample.time_iso,
    }
    post_json(url, dev_key, payload)


def export_csv(path: Path, samples: list[Sample]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["source_id", "property_name", "property_value", "entity_id", "time"],
        )
        writer.writeheader()
        for sample in samples:
            writer.writerow(
                {
                    "source_id": sample.source_id,
                    "property_name": PROPERTY_NAME,
                    "property_value": sample.value_str,
                    "entity_id": ENTITY_ID,
                    "time": sample.time_iso,
                }
            )


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed CellFab currentDraw valuestream samples.")
    parser.add_argument(
        "-e",
        "--end",
        type=parse_utc_compact,
        required=True,
        metavar="UTC",
        help="Exclusive UTC cutoff (required): YYYYmmdd[THH[MM[SS]]], omitted fields are 0",
    )
    parser.add_argument(
        "-s",
        "--start",
        type=parse_utc_compact,
        metavar="UTC",
        help=f"Inclusive UTC start (default: end minus {DEFAULT_WINDOW_DAYS} days); same format as --end",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print summary and first samples only.")
    parser.add_argument("--delete-only", action="store_true", help="Run delete_valuestream for both robots.")
    parser.add_argument("--skip-delete", action="store_true", help="Skip the delete step before insert.")
    parser.add_argument(
        "--resume",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Resume from progress file (default: true).",
    )
    parser.add_argument(
        "--state-file",
        type=Path,
        default=DEFAULT_STATE_PATH,
        help=f"Progress checkpoint path (default: {DEFAULT_STATE_PATH}).",
    )
    parser.add_argument(
        "--export-csv",
        type=Path,
        metavar="PATH",
        help="Write all generated rows to CSV instead of calling insert_valuestream.",
    )
    parser.add_argument(
        "--robot",
        choices=("both", "01", "02"),
        default="both",
        help="Limit work to one robot or both (default: both).",
    )
    parser.add_argument(
        "--progress-every",
        type=int,
        default=250,
        metavar="N",
        help="Log and checkpoint every N inserts per robot (default: 250).",
    )
    return parser.parse_args(argv)


def selected_robots(choice: str) -> tuple[tuple[str, int], ...]:
    if choice == "01":
        return (ROBOTS[0],)
    if choice == "02":
        return (ROBOTS[1],)
    return ROBOTS


def main(argv: list[str] | None = None) -> int:
    global RUN_WINDOW

    load_dotenv(ENV_PATH)
    args = parse_args(argv)
    RUN_WINDOW = resolve_run_window(args.end, args.start)
    robots = selected_robots(args.robot)

    if args.dry_run or args.export_csv or not args.delete_only:
        window = require_run_window()
        total_minutes = int((window.end_exclusive_utc - window.start_utc).total_seconds() // 60)
        print(
            "Plan: "
            f"{format_iso_z(window.start_utc)} .. < {format_iso_z(window.end_exclusive_utc)} "
            f"({total_minutes} nominal minutes / robot), "
            f"{CYCLE_MINUTES}-minute synchronized cycles, independent per-robot timestamp jitter."
        )

    if args.dry_run:
        for source_id, seed in robots:
            samples = list(iter_samples_for_robot(source_id, seed))
            print(f"\n{source_id}: {len(samples)} samples")
            for sample in samples[:5]:
                phase = phase_minutes(sample.time)
                print(
                    f"  idx={sample.nominal_index} time={sample.time_iso} "
                    f"phase={phase:.2f}m value={sample.value_str}"
                )
            anomaly_count = sum(
                1
                for s in samples
                if source_id == ROBOT_01
                and s.time >= ANOMALY_START_UTC
                and is_steady_phase(phase_minutes(s.time))
                and (s.value < STEADY_LOW or s.value > STEADY_HIGH)
            )
            if source_id == ROBOT_01:
                print(f"  steady anomalies (from 6/29 EDT): {anomaly_count}")
        return 0

    if args.export_csv:
        all_samples: list[Sample] = []
        for source_id, seed in robots:
            all_samples.extend(iter_samples_for_robot(source_id, seed))
        all_samples.sort(key=lambda s: (s.time, s.source_id))
        export_csv(args.export_csv, all_samples)
        print(f"Wrote {len(all_samples)} rows to {args.export_csv}")
        return 0

    dev_server = require_env("DEV_SERVER")
    dev_key = require_env("DEV_KEY")

    if not args.skip_delete:
        for source_id, _ in robots if args.delete_only else ROBOTS:
            try:
                delete_history(dev_server, dev_key, source_id)
            except urllib.error.HTTPError as exc:
                body = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
                print(f"HTTP {exc.code} delete {source_id}: {body}", file=sys.stderr)
                return 1
            except urllib.error.URLError as exc:
                print(f"delete failed {source_id}: {exc.reason}", file=sys.stderr)
                return 1
        if args.delete_only:
            return 0

    progress = load_progress(args.state_file) if args.resume else {}
    if args.resume and progress:
        print(f"Resuming from {args.state_file}: {progress}")

    for source_id, seed in robots:
        start_index = progress.get(source_id, -1) + 1
        inserted = 0
        for sample in iter_samples_for_robot(source_id, seed):
            if sample.nominal_index < start_index:
                continue
            try:
                insert_sample(dev_server, dev_key, sample)
            except urllib.error.HTTPError as exc:
                body = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
                save_progress(args.state_file, progress)
                print(
                    f"HTTP {exc.code} insert idx={sample.nominal_index} {source_id} "
                    f"{sample.time_iso}: {body}",
                    file=sys.stderr,
                )
                return 1
            except urllib.error.URLError as exc:
                save_progress(args.state_file, progress)
                print(
                    f"insert failed idx={sample.nominal_index} {source_id}: {exc.reason}",
                    file=sys.stderr,
                )
                return 1

            progress[source_id] = sample.nominal_index
            inserted += 1
            if inserted % args.progress_every == 0:
                save_progress(args.state_file, progress)
                print(
                    f"{source_id}: inserted {inserted} rows, "
                    f"last idx={sample.nominal_index} time={sample.time_iso}"
                )
            time.sleep(0.02)

        save_progress(args.state_file, progress)
        print(f"{source_id}: done ({inserted} rows this run)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""
Scan AgentMessageStream for ``describe_entity_schema`` tool results, validate the **full**
sample set for at least one **ThingTemplate**, scrub with
**deterministic pseudonyms** so ``local`` vs ``effective`` rows stay joinable, and
write evidence JSON only when complete.

Usage (repo root)::

  uv run python test_scripts/harvest_describe_entity_schema_from_stream.py \\
    --output tmp/describe-entity-schema-e2e-redacted.json

Optional: ``--source <conversationId>`` narrows the stream (recommended on busy servers).

Requires ``DEV_SERVER`` and ``DEV_KEY`` (repo-root ``.env``).

Exit codes
----------

* **0** — wrote ``--output``; ``meta.evidenceComplete`` is true (strict gating satisfied).
* **2** — stream reachable but **no** tool rows matched ``describe_entity_schema`` heuristics.
* **3** — HTTP / URL / JSON infra failure.
* **4** — matched partial ``describe_entity_schema`` rows but **incomplete** evidence set
  (missing non-zero ThingTemplate summary, list facet, or local summary for the same entity).
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

from test_scripts.agent_eval import (
    ENV_PATH,
    InfraHttpError,
    InfraJsonError,
    InfraUrlError,
    extract_rows_from_service_result,
    load_dotenv,
    post_json,
    require_env,
)

REPO_ROOT = Path(__file__).resolve().parent.parent

_CREDENTIAL_KEYS = frozenset(
    k.lower()
    for k in (
        "appkey",
        "apikey",
        "authorization",
        "password",
        "secret",
        "access_token",
        "refresh_token",
        "client_secret",
        "private_key",
    )
)


def _pseudo_for_entity(name: str) -> str:
    h = hashlib.sha256(name.strip().encode("utf-8")).hexdigest()[:12]
    return f"tmpl_{h}"


def _coalesce(row: dict[str, Any], key: str) -> Any:
    v = row.get(key)
    if isinstance(v, str) and v.strip():
        return v
    if isinstance(v, (dict, list)):
        return v
    vals = row.get("values")
    if isinstance(vals, dict):
        vv = vals.get(key)
        if isinstance(vv, str) and vv.strip():
            return vv
        if isinstance(vv, (dict, list)):
            return vv
    return v


def _is_describe_like(obj: dict[str, Any]) -> bool:
    """Loose filter: likely describe_entity_schema tool JSON."""
    if obj.get("status") != "success":
        return False
    et = obj.get("entityType")
    if et not in ("ThingTemplate", "ThingShape", "DataShape"):
        return False
    facet = obj.get("facet")
    if facet == "summary":
        return isinstance(obj.get("propertyCount"), int) and "serviceCount" in obj and "eventCount" in obj
    if facet in ("properties", "services", "events", "fields"):
        items = obj.get("items")
        return isinstance(items, list)
    return False


def _strict_summary_thing_template(obj: dict[str, Any], *, require_nonzero_counts: bool) -> bool:
    if obj.get("status") != "success" or obj.get("facet") != "summary":
        return False
    if obj.get("entityType") != "ThingTemplate":
        return False
    p = int(obj.get("propertyCount") or 0)
    s = int(obj.get("serviceCount") or 0)
    e = int(obj.get("eventCount") or 0)
    if require_nonzero_counts:
        return p + s + e > 0
    return True


def _strict_list_thing_template(obj: dict[str, Any]) -> bool:
    if obj.get("status") != "success" or obj.get("entityType") != "ThingTemplate":
        return False
    facet = obj.get("facet")
    if facet not in ("properties", "services", "events"):
        return False
    items = obj.get("items")
    return isinstance(items, list) and len(items) > 0


def _scope_is_effective(body: dict[str, Any]) -> bool:
    raw = body.get("scope")
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return True
    return str(raw).strip().lower() == "effective"


def _scope_is_local(body: dict[str, Any]) -> bool:
    return str(body.get("scope", "")).strip().lower() == "local"


def _entity_aliases(*names: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for name in names:
        if isinstance(name, str) and name.strip():
            out[name.strip()] = _pseudo_for_entity(name)
    return out


def _replace_entity_names(text: str, aliases: dict[str, str]) -> str:
    out = text
    for real, pseudo in sorted(aliases.items(), key=lambda kv: len(kv[0]), reverse=True):
        out = out.replace(real, pseudo)
    return out


def _scrub(obj: Any, entity_aliases: dict[str, str] | None = None) -> Any:
    """Pseudonymize entity identifiers; redact credential-like keys."""
    aliases = entity_aliases or {}
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for k, v in obj.items():
            lk = str(k).lower()
            if lk in _CREDENTIAL_KEYS:
                out[k] = "<REDACTED>"
                continue
            if lk in ("entityname", "thingtemplatename") and isinstance(v, str) and v.strip():
                out[k] = aliases.get(v.strip(), _pseudo_for_entity(v))
                continue
            if lk == "description" and isinstance(v, str) and len(v) > 200:
                out[k] = _replace_entity_names(v[:200], aliases) + "…"
                continue
            out[k] = _scrub(v, aliases)
        return out
    if isinstance(obj, list):
        return [_scrub(x, aliases) for x in obj]
    if isinstance(obj, str):
        s = obj
        if re.search(r"(?i)(appkey|apikey|bearer\s)", s):
            return "<REDACTED_STRING>"
        return _replace_entity_names(s, aliases)
    return copy.deepcopy(obj)


def _query_stream_rows(dev_server: str, app_key: str, *, source: str, max_items: int, timeout_s: float) -> list[dict[str, Any]]:
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    suffix = "/Things/AgentMessageStream/Services/QueryStreamData"
    url = base + suffix if low.endswith("/thingworx") else base + "/Thingworx" + suffix
    cap = max(1, min(int(max_items), 20000))
    payload: dict[str, Any] = {"maxItems": cap, "oldestFirst": False}
    if source.strip():
        payload["source"] = source.strip()
    data = post_json(url, app_key, payload, timeout_s=timeout_s)
    rows = extract_rows_from_service_result(data)
    rows.reverse()
    return [r for r in rows if isinstance(r, dict)]


def _parse_tool_bodies(raw_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for row in raw_rows:
        role = _coalesce(row, "role")
        if role != "tool":
            continue
        content = _coalesce(row, "content")
        if not isinstance(content, str) or not content.strip():
            continue
        try:
            body = json.loads(content)
        except json.JSONDecodeError:
            continue
        if not isinstance(body, dict):
            continue
        if not _is_describe_like(body):
            continue
        ts = _coalesce(row, "timestamp")
        src = row.get("source") or (row.get("values") or {}).get("conversationId")
        out.append(
            {
                "streamTimestamp": ts if isinstance(ts, (str, int, float)) else None,
                "streamSource": str(src) if src is not None else None,
                "toolResult": body,
            }
        )
    return out


def _entity_key(body: dict[str, Any]) -> str | None:
    en = body.get("entityName")
    if isinstance(en, str) and en.strip():
        return en.strip()
    return None


def _select_complete_entity(candidates: list[dict[str, Any]]) -> tuple[str | None, list[dict[str, Any]]]:
    """
    Pick one ThingTemplate entityName that has:
    - strict non-zero effective summary,
    - strict local summary,
    - strict non-empty properties|services|events list.
    """
    by_entity: dict[str, list[dict[str, Any]]] = {}
    for c in candidates:
        b = c["toolResult"]
        if not isinstance(b, dict):
            continue
        ek = _entity_key(b)
        if ek is None:
            continue
        by_entity.setdefault(ek, []).append(c)

    for name in sorted(by_entity.keys()):
        rows = by_entity[name]
        bodies = [r["toolResult"] for r in rows if isinstance(r["toolResult"], dict)]
        eff_sum = any(
            _strict_summary_thing_template(b, require_nonzero_counts=True) and _scope_is_effective(b) for b in bodies
        )
        loc_sum = any(
            _strict_summary_thing_template(b, require_nonzero_counts=False) and _scope_is_local(b) for b in bodies
        )
        has_list = any(_strict_list_thing_template(b) for b in bodies)
        if eff_sum and loc_sum and has_list:
            chosen: list[dict[str, Any]] = []
            seen: set[str] = set()
            for r in rows:
                b = r["toolResult"]
                if not isinstance(b, dict):
                    continue
                if not (
                    (
                        _strict_summary_thing_template(b, require_nonzero_counts=True)
                        and _scope_is_effective(b)
                    )
                    or (
                        _strict_summary_thing_template(b, require_nonzero_counts=False)
                        and _scope_is_local(b)
                    )
                    or _strict_list_thing_template(b)
                ):
                    continue
                sig = json.dumps(b, sort_keys=True)[:6000]
                if sig in seen:
                    continue
                seen.add(sig)
                chosen.append(r)
            return name, chosen[:32]
    return None, []


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", default="", help="AgentMessageStream source / conversation id filter (optional).")
    p.add_argument("--max-items", type=int, default=8000, help="QueryStreamData maxItems (default 8000).")
    p.add_argument(
        "--output",
        type=Path,
        default=REPO_ROOT / "tmp/describe-entity-schema-e2e-redacted.json",
        help="JSON output path (default under the gitignored tmp/).",
    )
    p.add_argument("--timeout", type=float, default=120.0, help="HTTP timeout seconds.")
    args = p.parse_args()

    load_dotenv(ENV_PATH)
    dev = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")

    try:
        raw_rows = _query_stream_rows(dev, key, source=args.source, max_items=args.max_items, timeout_s=args.timeout)
    except (InfraHttpError, InfraUrlError, InfraJsonError, OSError) as e:
        print(f"harvest: stream query failed: {e}", file=sys.stderr)
        return 3

    candidates = _parse_tool_bodies(raw_rows)
    if not candidates:
        print("harvest: no describe_entity_schema-like tool rows (exit 2).", file=sys.stderr)
        return 2

    entity, selected = _select_complete_entity(candidates)
    if entity is None or not selected:
        print(
            "harvest: incomplete evidence (exit 4): need same ThingTemplate with "
            "(1) non-zero summary scope=effective, (2) summary scope=local, "
            "(3) non-empty properties|services|events list.",
            file=sys.stderr,
        )
        return 4

    aliases = _entity_aliases(entity)
    scrubbed_samples: list[dict[str, Any]] = []
    for r in selected:
        tr = r.get("toolResult")
        if not isinstance(tr, dict):
            continue
        scrubbed_samples.append(
            {
                "streamTimestamp": r.get("streamTimestamp"),
                "streamSource": r.get("streamSource"),
                "toolResult": _scrub(copy.deepcopy(tr), aliases),
            }
        )

    eff_body = next(
        (
            x["toolResult"]
            for x in selected
            if isinstance(x.get("toolResult"), dict)
            and _strict_summary_thing_template(x["toolResult"], require_nonzero_counts=True)
            and _scope_is_effective(x["toolResult"])
        ),
        None,
    )
    loc_body = next(
        (
            x["toolResult"]
            for x in selected
            if isinstance(x.get("toolResult"), dict)
            and _strict_summary_thing_template(x["toolResult"], require_nonzero_counts=False)
            and _scope_is_local(x["toolResult"])
        ),
        None,
    )
    counts_differ = False
    if isinstance(eff_body, dict) and isinstance(loc_body, dict):
        for k in ("propertyCount", "serviceCount", "eventCount"):
            if int(eff_body.get(k) or 0) != int(loc_body.get(k) or 0):
                counts_differ = True
                break

    args.output.parent.mkdir(parents=True, exist_ok=True)
    bundle = {
        "meta": {
            "kind": "describe_entity_schema_stream_harvest",
            "evidenceComplete": True,
            "entityStableId": _pseudo_for_entity(entity),
            "sourceFilter": args.source or None,
            "maxItems": args.max_items,
            "localVsEffectiveSummaryCountsDiffer": counts_differ,
            "note": "Pseudonymous entityName preserves join across scopes. "
            "If localVsEffectiveSummaryCountsDiffer is false, the fixture may not inherit "
            "extra members — still valid when all three sample classes exist.",
        },
        "samples": scrubbed_samples,
    }
    args.output.write_text(json.dumps(bundle, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"harvest: wrote {len(scrubbed_samples)} sample(s) for entity pseudo {bundle['meta']['entityStableId']} -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""
Parler Agent live evaluation harness (ThingWorx REST, non-UI).

Loads ``DEV_SERVER`` and ``DEV_KEY`` from repo root ``.env`` (same pattern as ``GetApplicationLog.py``).

Run from repo root::

  uv run agent-eval --suite docs/agent/evals/smoke.yaml --agent-matrix env

When ``--agent-matrix env``, each suite ``agentMatrix`` key ``<k>`` resolves Thing name from
``AGENT_EVAL_AGENT_<K_UPPER>`` (non-alphanumerics normalized to ``_``), e.g. ``AGENT_EVAL_AGENT_SONNET``.

See ``docs/agent/agent-evaluation-harness.md``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import quote

import yaml

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ENV_PATH = REPO_ROOT / ".env"

STREAM_THING = "AgentMessageStream"
QUERY_STREAM_MAX_ITEMS = 20000
STABLE_HARD_RESET_MAX_ROUNDS = 500
RESET_MODES = frozenset({"fresh", "stable_clear", "stable_hard_reset"})
USAGE_AGG_KEYS = (
    "inputTokens",
    "outputTokens",
    "promptTokens",
    "completionTokens",
    "cacheReadInputTokens",
    "cacheCreationInputTokens",
    "cachedPromptTokens",
    "reasoningTokens",
)
HTTP_TIMEOUT_DEFAULT_S = 600
# Platform "Maximum wait time before flushing stream buffer" is often 10s; wait slightly longer
# before a single post-``Chat`` ``QueryStreamData`` so REST reads see the full trace.
STREAM_BUFFER_WAIT_DEFAULT_S = 11.0
TRUNCATION_MARKER = "...[truncated]"

EXIT_OK = 0
EXIT_SEMANTIC_FAIL = 1
EXIT_INFRA_FAIL = 2
EXIT_INTERRUPTED = 3
EXIT_NO_AGENT_FILTER_MATCH = 4
EXIT_REPORT_WRITE_FAILED = 5

PROVIDER_HTTP_CODES = frozenset({400, 401, 403, 408, 429, 500, 502, 503, 504})
PROVIDER_BODY_MARKERS = (
    "rate limit",
    "rate_limit",
    "too many requests",
    "context_length",
    "billing",
    "quota",
    "overloaded",
    "anthropic-ratelimit",
)

ENV_INTERP_RE = re.compile(r"^\$\{([A-Z_][A-Z0-9_]*)\}$")

SUITE_TOP_KEYS = frozenset(
    {
        "version",
        "suite",
        "agentMatrix",
        "cases",
        "resetMode",
        "assertionGroups",
        "fixtures",
        "baselineRef",
    }
)

CASE_KEYS = frozenset(
    {
        "id",
        "tags",
        "skill",
        "turns",
        "passThreshold",
        "scoreMode",
        "resetMode",
        "skipReason",
        "requiresFixture",
        "skipUnlessEnv",
    }
)


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


def env_matrix_key(label: str) -> str:
    norm = "".join(ch if ch.isalnum() else "_" for ch in label.strip()).upper().strip("_")
    while "__" in norm:
        norm = norm.replace("__", "_")
    return f"AGENT_EVAL_AGENT_{norm}"


def build_thing_service_url(dev_server: str, thing_name: str, service_name: str) -> str:
    base = dev_server.strip().rstrip("/")
    low = base.lower()
    enc_thing = quote(thing_name, safe="")
    enc_svc = quote(service_name, safe="")
    suffix = f"/Things/{enc_thing}/Services/{enc_svc}"
    if low.endswith("/thingworx"):
        return base + suffix
    return base + "/Thingworx" + suffix


def extract_rows_from_service_result(data: Any) -> list[dict[str, Any]]:
    """Normalize ThingWorx REST INFOTABLE-style payloads to a list of row dicts."""
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
    or an InfoTable-shaped object with ``rows: [{ "result": "..." }]`` (and variants
    where ``result`` embeds ``rows``). Empty string is a valid assistant reply.
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


def post_json(
    url: str,
    app_key: str,
    payload: dict[str, Any],
    *,
    timeout_s: float,
) -> Any:
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
        raise InfraHttpError(e.code, e.reason, url, err_body) from e
    except urllib.error.URLError as e:
        raise InfraUrlError(str(e.reason), url) from e
    try:
        return json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError as e:
        raise InfraJsonError(str(e), url, raw[:2000]) from e


class InfraHttpError(Exception):
    def __init__(self, code: int, reason: str, url: str, body: str) -> None:
        super().__init__(f"HTTP {code} {reason}: {url}")
        self.code = code
        self.reason = reason
        self.url = url
        self.body = body


class InfraUrlError(Exception):
    def __init__(self, reason: str, url: str) -> None:
        super().__init__(f"URL error: {reason}: {url}")
        self.url = url


class InfraJsonError(Exception):
    def __init__(self, reason: str, url: str, snippet: str) -> None:
        super().__init__(f"JSON decode: {reason}: {url}")
        self.snippet = snippet


class InfraTableRowsError(Exception):
    """Unexpected row count from GetOrCreateConversationId (contract / data issue)."""

    def __init__(self, message: str) -> None:
        super().__init__(message)


def build_query_stream_data_payload(*, source: str, max_items: int) -> dict[str, Any]:
    """
    REST ``QueryStreamData`` rejects explicit JSON nulls for optional fields on some servers.
    Send only the parameters we use (matches the minimal successful live shape).
    """
    cap = max(1, min(int(max_items), QUERY_STREAM_MAX_ITEMS))
    return {
        "maxItems": cap,
        "source": source,
        "oldestFirst": False,
    }


def query_stream_data(
    dev_server: str,
    app_key: str,
    *,
    source: str,
    max_items: int,
    timeout_s: float,
) -> list[dict[str, Any]]:
    url = build_thing_service_url(dev_server, STREAM_THING, "QueryStreamData")
    payload = build_query_stream_data_payload(source=source, max_items=max_items)
    data = post_json(url, app_key, payload, timeout_s=timeout_s)
    rows = extract_rows_from_service_result(data)
    rows.reverse()
    return rows


def build_query_stream_entries_payload(*, source: str, max_items: int, oldest_first: bool) -> dict[str, Any]:
    cap = max(1, min(int(max_items), QUERY_STREAM_MAX_ITEMS))
    return {
        "maxItems": cap,
        "source": source,
        "oldestFirst": oldest_first,
    }


def query_stream_entries(
    dev_server: str,
    app_key: str,
    *,
    source: str,
    max_items: int,
    oldest_first: bool,
    timeout_s: float,
) -> list[dict[str, Any]]:
    url = build_thing_service_url(dev_server, STREAM_THING, "QueryStreamEntries")
    payload = build_query_stream_entries_payload(source=source, max_items=max_items, oldest_first=oldest_first)
    data = post_json(url, app_key, payload, timeout_s=timeout_s)
    return extract_rows_from_service_result(data)


def delete_stream_entry(
    dev_server: str,
    app_key: str,
    *,
    stream_entry_id: str,
    timeout_s: float,
) -> None:
    url = build_thing_service_url(dev_server, STREAM_THING, "DeleteStreamEntry")
    post_json(url, app_key, {"streamEntryId": stream_entry_id}, timeout_s=timeout_s)


def clear_conversation(
    dev_server: str,
    app_key: str,
    agent_thing: str,
    *,
    conversation_id: str,
    timeout_s: float,
) -> None:
    url = build_thing_service_url(dev_server, agent_thing, "ClearConversation")
    post_json(url, app_key, {"conversationId": conversation_id}, timeout_s=timeout_s)


def is_pending_approval_clear_block(exc: InfraHttpError) -> bool:
    # Substring must stay aligned with AgentThing.ClearConversation English text
    # ("pending approvals exist ...") — see AgentThing.java ~1888–1890.
    b = (exc.body or "").lower()
    return "pending approvals" in b


def _count_stream_entries_safe(
    dev_server: str,
    app_key: str,
    *,
    conversation_id: str,
    page_size: int,
    timeout_s: float,
) -> int:
    """Return row count, or ``-1`` if the count query itself fails."""
    try:
        rows = query_stream_entries(
            dev_server,
            app_key,
            source=conversation_id,
            max_items=page_size,
            oldest_first=True,
            timeout_s=timeout_s,
        )
        return len(rows)
    except (InfraHttpError, InfraUrlError, InfraJsonError):
        return -1


def stable_hard_reset_stream(
    dev_server: str,
    app_key: str,
    *,
    conversation_id: str,
    page_size: int,
    max_query_rounds: int,
    timeout_s: float,
) -> tuple[int, int, str | None]:
    """
    Delete all AgentMessageStream rows for ``conversation_id`` (evaluation-only).
    Returns (rows_deleted, rows_remaining, error_code_or_none).
    REST failures return a structured third-slot message instead of raising.
    """
    deleted_total = 0
    for _ in range(max(1, max_query_rounds)):
        try:
            rows = query_stream_entries(
                dev_server,
                app_key,
                source=conversation_id,
                max_items=page_size,
                oldest_first=True,
                timeout_s=timeout_s,
            )
        except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
            rem = _count_stream_entries_safe(
                dev_server,
                app_key,
                conversation_id=conversation_id,
                page_size=page_size,
                timeout_s=timeout_s,
            )
            # -1 means the follow-up count query failed; use 0 so we do not imply "verified empty".
            rem_out = rem if rem >= 0 else 0
            return deleted_total, rem_out, f"stream_hard_reset_query_error: {e}"
        if not rows:
            return deleted_total, 0, None
        for r in rows:
            eid = r.get("id")
            if eid is None:
                eid = r.get("streamEntryId")
            if eid is None or (isinstance(eid, str) and not str(eid).strip()):
                continue
            try:
                delete_stream_entry(dev_server, app_key, stream_entry_id=str(eid), timeout_s=timeout_s)
            except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
                rem = _count_stream_entries_safe(
                    dev_server,
                    app_key,
                    conversation_id=conversation_id,
                    page_size=page_size,
                    timeout_s=timeout_s,
                )
                # -1: follow-up count failed; use 0 (unknown remaining), not "verified empty".
                rem_out = rem if rem >= 0 else 0
                return deleted_total, rem_out, f"stream_hard_reset_delete_error: {e}"
            deleted_total += 1
    try:
        tail = query_stream_entries(
            dev_server,
            app_key,
            source=conversation_id,
            max_items=page_size,
            oldest_first=True,
            timeout_s=timeout_s,
        )
    except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
        return deleted_total, 0, f"stream_hard_reset_query_error: {e}"
    rem = len(tail)
    if rem:
        return deleted_total, rem, "stable_hard_reset_safety_limit_exceeded"
    return deleted_total, 0, None


def _slice_delta_rows(rows_after: list[dict[str, Any]], mark: int) -> list[dict[str, Any]]:
    if len(rows_after) >= mark:
        return rows_after[mark:]
    return rows_after


def _detruncate_stream_content(raw: str) -> str:
    if raw.endswith(TRUNCATION_MARKER):
        return raw[: -len(TRUNCATION_MARKER)]
    return raw


def trace_matches_chat_return(delta: list[dict[str, Any]], final_text: str) -> bool:
    """True when the last assistant row's content matches the synchronous ``Chat`` return (truncation-safe)."""
    assistants = [r for r in delta if str(r.get("role") or "").strip().lower() == "assistant"]
    if not assistants:
        return False
    raw = "" if assistants[-1].get("content") is None else str(assistants[-1].get("content"))
    stream_text = _detruncate_stream_content(raw)
    ft = final_text or ""
    if stream_text == "" and ft == "":
        return True
    if stream_text and (ft.startswith(stream_text) or stream_text == ft):
        return True
    if ft and stream_text.startswith(ft):
        return True
    return False


def tool_calls_cell_is_blank(tool_calls_raw: Any) -> bool:
    """True when the stream cell has no tool calls (final answer row vs assistant tool-call row)."""
    if tool_calls_raw is None:
        return True
    s = str(tool_calls_raw).strip()
    if not s:
        return True
    if s == "[]":
        return True
    return False


#: Internal Stream role for a persisted conversation checkpoint (advanced-compact §9.1).
CONTEXT_CHECKPOINT_ROLE = "context_checkpoint"


def trace_complete_strict(delta: list[dict[str, Any]], final_text: str) -> bool:
    """
    After the post-``Chat`` buffer wait, the turn trace is complete only if the delta ends with the
    final assistant row: ``role=assistant``, blank ``toolCalls``, and content aligned with ``Chat``
    (truncation-safe prefix/suffix rules).
    """
    if not delta:
        return False
    # docs/core/advanced-compact.md §9.1: the checkpoint row is appended after the final assistant by design, so
    # completeness can no longer require delta[-1] to be that assistant. Only trailing rows of the one recognized
    # internal role are skipped; any other unknown trailing role still fails closed, and the raw delta keeps the
    # checkpoint for audit. This corrects the completeness judgement only -- it does not make a checkpoint an
    # assistant round, and the identity/content checks below are unchanged.
    idx = len(delta) - 1
    while idx >= 0 and str(delta[idx].get("role") or "").strip().lower() == CONTEXT_CHECKPOINT_ROLE:
        idx -= 1
    if idx < 0:
        return False
    last = delta[idx]
    if str(last.get("role") or "").strip().lower() != "assistant":
        return False
    if not tool_calls_cell_is_blank(last.get("toolCalls")):
        return False
    raw = "" if last.get("content") is None else str(last.get("content"))
    stream_text = _detruncate_stream_content(raw)
    ft = final_text or ""
    if stream_text == "" and ft == "":
        return True
    if stream_text and (ft.startswith(stream_text) or stream_text == ft):
        return True
    if ft and stream_text.startswith(ft):
        return True
    return False


def _role_tail_preview(delta: list[dict[str, Any]], n: int = 6) -> str:
    tail = delta[-n:] if delta else []
    return ">".join(str(r.get("role") or "?") for r in tail)


def pre_chat_stream_query(
    dev_server: str,
    app_key: str,
    *,
    conversation_id: str,
    turn_index: int,
    timeout_s: float,
) -> tuple[list[dict[str, Any]] | None, dict[str, Any] | None]:
    """Returns (rows, infra_turn_row). On HTTP/parse failure the row is set and rows is None."""
    t0 = time.perf_counter()
    try:
        rows = query_stream_data(
            dev_server,
            app_key,
            source=conversation_id,
            max_items=QUERY_STREAM_MAX_ITEMS,
            timeout_s=timeout_s,
        )
        return rows, None
    except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        return None, infra_turn_row(
            turn_index=turn_index,
            phase="stream_query",
            code="stream_query_before_chat",
            error=str(e),
            elapsed_ms=elapsed_ms,
            configured_timeout_s=timeout_s,
            url_class=service_url_class(dev_server, STREAM_THING, "QueryStreamData"),
            chatMs=0.0,
            streamBufferWaitMs=0.0,
            streamQueryMs=round(elapsed_ms, 3),
            turnWallMs=round(elapsed_ms, 3),
        )


def post_chat_incomplete_trace_row(
    *,
    turn_index: int,
    delta: list[dict[str, Any]],
    final_text: str,
    buf_wait_s: float,
    chat_ms: float,
    buffer_wait_ms: float,
    stream_query_ms: float,
) -> dict[str, Any] | None:
    """Infra turn row when post-Chat trace is incomplete; None when assertions may run."""
    if trace_complete_strict(delta, final_text):
        return None
    return infra_turn_row(
        turn_index=turn_index,
        phase="stream_query",
        code="stream_rows_not_visible_after_chat",
        error=(
            f"stream_rows_not_visible_after_chat: incomplete trace after "
            f"buffer_wait_s={buf_wait_s} delta_len={len(delta)} "
            f"role_tail={_role_tail_preview(delta)}"
        ),
        finalExcerpt=excerpt(final_text),
        finalText=final_text,
        chatMs=round(chat_ms, 3),
        streamBufferWaitMs=round(buffer_wait_ms, 3),
        streamQueryMs=round(stream_query_ms, 3),
        deltaRowCount=len(delta),
        turnWallMs=round(chat_ms + buffer_wait_ms + stream_query_ms, 3),
    )


def get_by_path(obj: Any, path: str) -> Any:
    cur: Any = obj
    for part in path.split("."):
        if part == "":
            continue
        if cur is None:
            return None
        if isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def parse_tool_calls_cell(tool_calls_raw: str) -> tuple[list[dict[str, Any]] | None, str | None]:
    """
    Returns (calls, error_reason). error_reason set for truncation or JSON failure.
    """
    s = (tool_calls_raw or "").strip()
    if not s:
        return [], None
    if TRUNCATION_MARKER in s:
        return None, "tool_args_truncated_in_stream"
    try:
        arr = json.loads(s)
    except json.JSONDecodeError:
        return None, "tool_calls_json_parse_error"
    if not isinstance(arr, list):
        return None, "tool_calls_not_array"
    out: list[dict[str, Any]] = []
    for el in arr:
        if isinstance(el, dict):
            out.append(el)
    return out, None


def parse_tool_arguments_json(arguments_field: Any) -> tuple[dict[str, Any] | None, str | None]:
    if arguments_field is None:
        return {}, None
    if isinstance(arguments_field, dict):
        return arguments_field, None
    raw = str(arguments_field).strip()
    if TRUNCATION_MARKER in raw:
        return None, "tool_args_truncated_in_stream"
    try:
        v = json.loads(raw)
    except json.JSONDecodeError:
        return None, "tool_arguments_json_parse_error"
    if not isinstance(v, dict):
        return None, "tool_arguments_not_object"
    return v, None


def collect_tool_events(
    delta_rows: list[dict[str, Any]],
) -> tuple[list[str], list[tuple[str, dict[str, Any] | None, str | None]], list[str], list[str]]:
    """
    ordered_tool_names: tool function names in chronological order (each assistant toolCalls entry).
    tool_calls_flat: (name, args_dict|None, parse_err)
    tool_result_texts: content strings for role tool
    argument_parse_errors: stable reason strings for per-call ``arguments`` JSON issues
    """
    ordered: list[str] = []
    flat: list[tuple[str, dict[str, Any] | None, str | None]] = []
    tool_texts: list[str] = []
    argument_parse_errors: list[str] = []
    for row in delta_rows:
        role = str(row.get("role") or "").strip().lower()
        if role == "tool":
            c = row.get("content")
            tool_texts.append("" if c is None else str(c))
            continue
        if role != "assistant":
            continue
        raw_tc = row.get("toolCalls")
        calls, err = parse_tool_calls_cell("" if raw_tc is None else str(raw_tc))
        if err:
            flat.append(("", None, err))
            continue
        assert calls is not None
        for c in calls:
            name = str(c.get("name") or "").strip()
            args, perr = parse_tool_arguments_json(c.get("arguments"))
            if perr:
                argument_parse_errors.append(perr)
            ordered.append(name)
            flat.append((name, args, perr))
    return ordered, flat, tool_texts, argument_parse_errors


def parse_llm_usage_json_cell(raw: str | None) -> tuple[dict[str, Any] | None, str | None]:
    """
    Returns (parsed dict for valid usage JSON, error_reason).
    ``(None, None)`` means missing/empty cell — use legacy column fallback.
    """
    if raw is None:
        return None, None
    s = str(raw).strip()
    if not s:
        return None, None
    if TRUNCATION_MARKER in s:
        return None, "llm_usage_json_truncated_in_stream"
    try:
        v = json.loads(s)
    except json.JSONDecodeError:
        return None, "llm_usage_json_parse_error"
    if not isinstance(v, dict):
        return None, "llm_usage_json_parse_error"
    return v, None


def _int_token_cell(v: Any, default: int = 0) -> int:
    if v is None:
        return default
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _empty_usage_bucket() -> dict[str, int]:
    return {k: 0 for k in USAGE_AGG_KEYS}


def aggregate_llm_usage_from_delta(
    delta_rows: list[dict[str, Any]],
) -> tuple[
    int,
    int,
    int,
    int,
    int,
    int,
    int,
    int,
    list[str],
    dict[str, dict[str, int]],
    list[str],
]:
    """
    Sum usage over ``role=assistant`` rows. Malformed ``llmUsageJson`` contributes parse errors;
    that row's detailed usage is skipped while prompt/completion fall back to INTEGER columns.
    """
    psum = 0
    csum = 0
    it_sum = 0
    ot_sum = 0
    cr_sum = 0
    cc_sum = 0
    cp_sum = 0
    rt_sum = 0
    request_ids: list[str] = []
    seen_rid: set[str] = set()
    by_provider: dict[str, dict[str, int]] = {}
    errs: list[str] = []

    def add_bucket(prov: str, snap: dict[str, int]) -> None:
        b = by_provider.setdefault(prov, _empty_usage_bucket())
        for k in USAGE_AGG_KEYS:
            b[k] += snap[k]

    for row in delta_rows:
        role = str(row.get("role") or "").strip().lower()
        if role != "assistant":
            continue
        col_pt = _int_token_cell(row.get("promptTokens"))
        col_ct = _int_token_cell(row.get("completionTokens"))
        cell = row.get("llmUsageJson")
        parsed, uerr = parse_llm_usage_json_cell("" if cell is None else str(cell))
        if uerr:
            errs.append(uerr)
            snap = _empty_usage_bucket()
            snap["promptTokens"] = col_pt
            snap["completionTokens"] = col_ct
            snap["inputTokens"] = col_pt
            snap["outputTokens"] = col_ct
            add_bucket("column_only", snap)
            psum += col_pt
            csum += col_ct
            it_sum += col_pt
            ot_sum += col_ct
            continue
        if parsed is None:
            snap = _empty_usage_bucket()
            snap["promptTokens"] = col_pt
            snap["completionTokens"] = col_ct
            snap["inputTokens"] = col_pt
            snap["outputTokens"] = col_ct
            add_bucket("column_only", snap)
            psum += col_pt
            csum += col_ct
            it_sum += col_pt
            ot_sum += col_ct
            continue
        prov = str(parsed.get("provider") or "UNKNOWN").strip() or "UNKNOWN"
        pt = _int_token_cell(parsed.get("promptTokens"), col_pt)
        ct = _int_token_cell(parsed.get("completionTokens"), col_ct)
        it = _int_token_cell(parsed.get("inputTokens"), col_pt)
        ot = _int_token_cell(parsed.get("outputTokens"), col_ct)
        cr = _int_token_cell(parsed.get("cacheReadInputTokens"))
        cc = _int_token_cell(parsed.get("cacheCreationInputTokens"))
        cp = _int_token_cell(parsed.get("cachedPromptTokens"))
        rt = _int_token_cell(parsed.get("reasoningTokens"))
        snap = {
            "promptTokens": pt,
            "completionTokens": ct,
            "inputTokens": it,
            "outputTokens": ot,
            "cacheReadInputTokens": cr,
            "cacheCreationInputTokens": cc,
            "cachedPromptTokens": cp,
            "reasoningTokens": rt,
        }
        add_bucket(prov, snap)
        psum += pt
        csum += ct
        it_sum += it
        ot_sum += ot
        cr_sum += cr
        cc_sum += cc
        cp_sum += cp
        rt_sum += rt
        rid = str(parsed.get("requestId") or "").strip()
        if rid and rid not in seen_rid:
            seen_rid.add(rid)
            request_ids.append(rid)

    return psum, csum, it_sum, ot_sum, cr_sum, cc_sum, cp_sum, rt_sum, request_ids, by_provider, errs


@dataclass
class TurnContext:
    final_text: str
    delta_rows: list[dict[str, Any]]
    prompt_tokens_turn: int
    completion_tokens_turn: int
    input_tokens_turn: int = 0
    output_tokens_turn: int = 0
    cache_read_input_tokens_turn: int = 0
    cache_creation_input_tokens_turn: int = 0
    cached_prompt_tokens_turn: int = 0
    reasoning_tokens_turn: int = 0
    request_ids_observed: list[str] = field(default_factory=list)
    usage_by_provider: dict[str, dict[str, int]] = field(default_factory=dict)
    trace_parse_errors: list[str] = field(default_factory=list)


def build_turn_context(
    final_text: str,
    delta_rows: list[dict[str, Any]],
    *,
    per_call_argument_errors: list[str] | None = None,
) -> TurnContext:
    (
        psum,
        csum,
        it_s,
        ot_s,
        cr_s,
        cc_s,
        cp_s,
        rt_s,
        rids,
        byp,
        uerrs,
    ) = aggregate_llm_usage_from_delta(delta_rows)
    errs: list[str] = list(per_call_argument_errors or [])
    errs.extend(uerrs)
    for row in delta_rows:
        role = str(row.get("role") or "").strip().lower()
        if role != "assistant":
            continue
        raw_tc = row.get("toolCalls")
        _, terr = parse_tool_calls_cell("" if raw_tc is None else str(raw_tc))
        if terr:
            errs.append(terr)
    return TurnContext(
        final_text=final_text,
        delta_rows=delta_rows,
        prompt_tokens_turn=psum,
        completion_tokens_turn=csum,
        input_tokens_turn=it_s,
        output_tokens_turn=ot_s,
        cache_read_input_tokens_turn=cr_s,
        cache_creation_input_tokens_turn=cc_s,
        cached_prompt_tokens_turn=cp_s,
        reasoning_tokens_turn=rt_s,
        request_ids_observed=rids,
        usage_by_provider=byp,
        trace_parse_errors=errs,
    )


ASSERTION_KEYS = frozenset(
    {
        "finalContains",
        "finalNotContains",
        "finalRegex",
        "finalNotRegex",
        "toolCalled",
        "toolNotCalled",
        "toolArgEquals",
        "toolArgNotEquals",
        "toolArgContains",
        "toolArgRegex",
        "toolArgAbsent",
        "toolResultContains",
        "toolResultNotContains",
        "toolResultRegex",
        "toolCallCountAtLeast",
        "toolCallCountAtMost",
        "toolCalledTimesAtLeast",
        "toolCalledTimesAtMost",
        "turnCountEquals",
        "traceParseErrorsAbsent",
        "toolsCalledSubsequence",
        "allOf",
        "anyOf",
        "not",
    }
)

USE_ASSERTION_GROUP_KEY = "useAssertionGroup"


def format_reject_hit(kind: str, spec: Any) -> str:
    """Human-readable detail when a rejectIf rule fired (positive assertion detail text is often misleading)."""
    if kind == "finalContains":
        return f"matched forbidden substring {spec!r}"
    if kind == "finalRegex":
        return f"matched forbidden pattern {spec!r}"
    if kind == "finalNotContains":
        return f"substring {spec!r} absent from final text (reject rule matched)"
    if kind == "finalNotRegex":
        return f"pattern {spec!r} absent from final text (reject rule matched)"
    if kind == "toolResultContains":
        return f"matched forbidden tool result substring {spec!r}"
    if kind == "toolResultNotContains":
        return f"tool results violated expected absence of {spec!r}"
    if kind == "toolCalled":
        return f"forbidden tool call present: {spec!r}"
    if kind == "toolNotCalled":
        return f"tool {spec!r} was not called (reject rule matched)"
    return f"reject rule satisfied ({kind!r}, {spec!r})"


def normalize_assertion_item(item: Any) -> tuple[str, Any]:
    if isinstance(item, str):
        return "toolCalled", item
    if not isinstance(item, dict) or not item:
        return "invalid", item
    for k, v in item.items():
        if k in ASSERTION_KEYS:
            return k, v
    return "invalid", item


def _tool_args_entries(
    tool_calls_flat: list[tuple[str, dict[str, Any] | None, str | None]], tool: str
) -> list[tuple[dict[str, Any] | None, str | None]]:
    return [(a, e) for n, a, e in tool_calls_flat if n == tool]


def eval_one_assertion(
    kind: str,
    spec: Any,
    ctx: TurnContext,
    *,
    ordered_tool_names: list[str],
    tool_calls_flat: list[tuple[str, dict[str, Any] | None, str | None]],
    tool_result_texts: list[str],
    assistant_row_count: int,
) -> tuple[bool, str]:
    """Return (satisfied, detail). For rejectIf, a satisfied assertion means the reject rule fired."""
    if kind in ("allOf", "anyOf"):
        if not isinstance(spec, list):
            return False, f"{kind} expects list"
        if not spec:
            return False, f"{kind} expects non-empty list"
        matched: list[str] = []
        failures: list[str] = []
        for child in spec:
            ok, detail = eval_assertion_item(
                child,
                ctx,
                ordered_tool_names=ordered_tool_names,
                tool_calls_flat=tool_calls_flat,
                tool_result_texts=tool_result_texts,
                assistant_row_count=assistant_row_count,
            )
            if ok:
                child_kind, child_spec = normalize_assertion_item(child)
                matched.append(f"{child_kind} matched {child_spec!r}")
            else:
                failures.append(detail or "failed")
        if kind == "allOf":
            if not failures:
                return True, "allOf matched: " + "; ".join(matched)
            return False, "allOf failed: " + "; ".join(failures)
        if matched:
            return True, "anyOf matched: " + "; ".join(matched)
        return False, "anyOf failed: " + "; ".join(failures)
    if kind == "not":
        child_kind, _ = normalize_assertion_item(spec)
        if child_kind == "invalid":
            return False, f"not expects a valid assertion, got {spec!r}"
        ok, detail = eval_assertion_item(
            spec,
            ctx,
            ordered_tool_names=ordered_tool_names,
            tool_calls_flat=tool_calls_flat,
            tool_result_texts=tool_result_texts,
            assistant_row_count=assistant_row_count,
        )
        if ok:
            return False, f"not failed: child assertion matched ({detail})"
        return True, f"not matched: child assertion did not match ({detail})"
    text = ctx.final_text or ""
    if kind == "finalContains":
        return (str(spec) in text), f"finalContains missing {spec!r}"
    if kind == "finalNotContains":
        return (str(spec) not in text), f"finalNotContains forbidden {spec!r}"
    if kind == "finalRegex":
        try:
            ok = re.search(str(spec), text) is not None
        except re.error as e:
            return False, f"finalRegex invalid: {e}"
        return ok, f"finalRegex did not match {spec!r}"
    if kind == "finalNotRegex":
        try:
            ok = re.search(str(spec), text) is None
        except re.error as e:
            return False, f"finalNotRegex invalid: {e}"
        return ok, f"finalNotRegex matched forbidden {spec!r}"
    if kind == "toolCalled":
        name = str(spec).strip()
        ok = name in ordered_tool_names
        return ok, f"toolCalled missing {name!r}"
    if kind == "toolNotCalled":
        name = str(spec).strip()
        ok = name not in ordered_tool_names
        return ok, f"toolNotCalled unexpected {name!r}"
    if kind == "toolsCalledSubsequence":
        if not isinstance(spec, list) or not spec:
            return False, "toolsCalledSubsequence expects non-empty list of tool names"
        seq = [str(x).strip() for x in spec if str(x).strip()]
        if not seq:
            return False, "toolsCalledSubsequence has no non-blank tool names"
        idx = 0
        for actual in ordered_tool_names:
            if idx >= len(seq):
                break
            if actual == seq[idx]:
                idx += 1
        if idx < len(seq):
            return False, f"toolsCalledSubsequence expected order {seq!r} within {ordered_tool_names!r}"
        return True, f"toolsCalledSubsequence matched {seq!r}"
    if kind in (
        "toolArgEquals",
        "toolArgNotEquals",
        "toolArgContains",
        "toolArgRegex",
        "toolArgAbsent",
    ):
        if not isinstance(spec, Mapping):
            return False, f"{kind} expects mapping"
        tool = str(spec.get("tool") or "").strip()
        path = str(spec.get("path") or "").strip()
        if not tool or not path:
            return False, f"{kind} requires tool and path"
        entries = _tool_args_entries(tool_calls_flat, tool)
        if kind == "toolArgAbsent":
            if not entries:
                return False, f"toolArgAbsent: no tool call {tool!r}"
            for args, err in entries:
                if err:
                    return False, f"toolArgAbsent: {err}"
                if args is None:
                    return False, "toolArgAbsent: missing args"
                v = get_by_path(args, path)
                if v is not None and v != "":
                    return False, f"toolArgAbsent path {path!r} present ({v!r})"
            return True, ""
        if not entries:
            return False, f"{kind}: no tool call {tool!r}"
        if kind == "toolArgEquals":
            exp = spec.get("value")
            for args, err in entries:
                if err:
                    return False, f"toolArgEquals: {err}"
                if args is None:
                    continue
                if get_by_path(args, path) == exp:
                    return True, f"toolArgEquals path {path!r} matches {exp!r}"
            return False, f"toolArgEquals path {path!r} never equals {exp!r}"
        if kind == "toolArgNotEquals":
            exp = spec.get("value")
            for args, err in entries:
                if err:
                    return False, f"toolArgNotEquals: {err}"
                if args is None:
                    continue
                if get_by_path(args, path) == exp:
                    return False, f"toolArgNotEquals path {path!r} equals forbidden {exp!r}"
            return True, ""
        if kind == "toolArgContains":
            sub = str(spec.get("value", ""))
            for args, err in entries:
                if err:
                    return False, f"toolArgContains: {err}"
                if args is None:
                    continue
                v = get_by_path(args, path)
                if sub in ("" if v is None else str(v)):
                    return True, f"toolArgContains path {path!r} contains {sub!r}"
            return False, f"toolArgContains path {path!r} missing {sub!r}"
        if kind == "toolArgRegex":
            pat = str(spec.get("pattern", ""))
            try:
                cre = re.compile(pat)
            except re.error as e:
                return False, f"toolArgRegex invalid: {e}"
            for args, err in entries:
                if err:
                    return False, f"toolArgRegex: {err}"
                if args is None:
                    continue
                v = get_by_path(args, path)
                if cre.search("" if v is None else str(v)):
                    return True, f"toolArgRegex path {path!r} matched {pat!r}"
            return False, f"toolArgRegex path {path!r} did not match {pat!r}"
    if kind == "toolResultContains":
        sub = str(spec)
        blob = "\n".join(tool_result_texts)
        return sub in blob, f"toolResultContains missing {sub!r}"
    if kind == "toolResultNotContains":
        sub = str(spec)
        blob = "\n".join(tool_result_texts)
        return sub not in blob, f"toolResultNotContains forbidden {sub!r}"
    if kind == "toolResultRegex":
        pat = str(spec)
        blob = "\n".join(tool_result_texts)
        try:
            ok = re.search(pat, blob) is not None
        except re.error as e:
            return False, f"toolResultRegex invalid: {e}"
        return ok, f"toolResultRegex did not match {pat!r}"
    if kind == "traceParseErrorsAbsent":
        errs = ctx.trace_parse_errors or []
        return len(errs) == 0, f"traceParseErrorsAbsent: {errs!r}"
    if kind == "toolCalledTimesAtLeast":
        if not isinstance(spec, Mapping):
            return False, "toolCalledTimesAtLeast expects mapping"
        tool = str(spec.get("tool") or "").strip()
        try:
            need = int(spec.get("count", 0))
        except (TypeError, ValueError):
            return False, "toolCalledTimesAtLeast count not int"
        got = sum(1 for n in ordered_tool_names if n == tool)
        return got >= need, f"toolCalledTimesAtLeast {tool!r} need>={need} got {got}"
    if kind == "toolCalledTimesAtMost":
        if not isinstance(spec, Mapping):
            return False, "toolCalledTimesAtMost expects mapping"
        tool = str(spec.get("tool") or "").strip()
        try:
            need = int(spec.get("count", 0))
        except (TypeError, ValueError):
            return False, "toolCalledTimesAtMost count not int"
        got = sum(1 for n in ordered_tool_names if n == tool)
        return got <= need, f"toolCalledTimesAtMost {tool!r} need<={need} got {got}"
    if kind == "toolCallCountAtLeast":
        try:
            n = int(spec)
        except (TypeError, ValueError):
            return False, "toolCallCountAtLeast not int"
        return len(ordered_tool_names) >= n, f"toolCallCountAtLeast need>={n} got {len(ordered_tool_names)}"
    if kind == "toolCallCountAtMost":
        try:
            n = int(spec)
        except (TypeError, ValueError):
            return False, "toolCallCountAtMost not int"
        return len(ordered_tool_names) <= n, f"toolCallCountAtMost need<={n} got {len(ordered_tool_names)}"
    if kind == "turnCountEquals":
        try:
            n = int(spec)
        except (TypeError, ValueError):
            return False, "turnCountEquals not int"
        return assistant_row_count == n, f"turnCountEquals need=={n} got {assistant_row_count}"
    if kind == "invalid":
        return False, f"unknown assertion {spec!r}"
    return False, f"unhandled assertion {kind}"


def eval_assertion_item(
    item: Any,
    ctx: TurnContext,
    *,
    ordered_tool_names: list[str],
    tool_calls_flat: list[tuple[str, dict[str, Any] | None, str | None]],
    tool_result_texts: list[str],
    assistant_row_count: int,
) -> tuple[bool, str]:
    kind, spec = normalize_assertion_item(item)
    return eval_one_assertion(
        kind,
        spec,
        ctx,
        ordered_tool_names=ordered_tool_names,
        tool_calls_flat=tool_calls_flat,
        tool_result_texts=tool_result_texts,
        assistant_row_count=assistant_row_count,
    )


def eval_assertion_list(
    items: list[Any],
    ctx: TurnContext,
    *,
    ordered_tool_names: list[str],
    tool_calls_flat: list[tuple[str, dict[str, Any] | None, str | None]],
    tool_result_texts: list[str],
    assistant_row_count: int,
) -> tuple[bool, list[str]]:
    failures: list[str] = []
    for it in items:
        ok, msg = eval_assertion_item(
            it,
            ctx,
            ordered_tool_names=ordered_tool_names,
            tool_calls_flat=tool_calls_flat,
            tool_result_texts=tool_result_texts,
            assistant_row_count=assistant_row_count,
        )
        if not ok and msg:
            failures.append(msg)
        elif not ok:
            failures.append(f"{kind} failed")
    return (len(failures) == 0, failures)


def select_outcome(
    outcomes: list[dict[str, Any]],
    reject_items: list[Any],
    ctx: TurnContext,
    *,
    ordered_tool_names: list[str],
    tool_calls_flat: list[tuple[str, dict[str, Any] | None, str | None]],
    tool_result_texts: list[str],
    assistant_row_count: int,
) -> tuple[str | None, float, list[str], list[str]]:
    """
    Returns (outcome_name|None, score, assertion_failures, reject_hits).
    """
    rej_hits: list[str] = []
    for it in reject_items:
        kind, spec = normalize_assertion_item(it)
        if kind == "invalid":
            rej_hits.append(f"rejectIf:invalid:{spec!r}")
            continue
        fired, detail = eval_assertion_item(
            it,
            ctx,
            ordered_tool_names=ordered_tool_names,
            tool_calls_flat=tool_calls_flat,
            tool_result_texts=tool_result_texts,
            assistant_row_count=assistant_row_count,
        )
        if fired:
            if kind in (
                "finalContains",
                "finalRegex",
                "finalNotContains",
                "finalNotRegex",
                "toolResultContains",
                "toolResultNotContains",
                "toolCalled",
                "toolNotCalled",
            ):
                rj = format_reject_hit(kind, spec)
            else:
                rj = detail
            rej_hits.append(f"rejectIf:{kind}:{rj}")

    reject_fired = len(rej_hits) > 0

    best_name: str | None = None
    best_score = -1.0
    best_idx: int | None = None
    best_fails: list[str] = []
    for idx, oc in enumerate(outcomes):
        if not isinstance(oc, dict):
            continue
        name = str(oc.get("name") or f"outcome_{idx}")
        try:
            score = float(oc.get("score", 0.0))
        except (TypeError, ValueError):
            score = 0.0
        asserts = oc.get("assertions") or []
        if not isinstance(asserts, list):
            asserts = []
        ok, fails = eval_assertion_list(
            asserts,
            ctx,
            ordered_tool_names=ordered_tool_names,
            tool_calls_flat=tool_calls_flat,
            tool_result_texts=tool_result_texts,
            assistant_row_count=assistant_row_count,
        )
        if reject_fired:
            continue
        if not ok:
            continue
        if (
            best_name is None
            or score > best_score
            or (score == best_score and best_idx is not None and idx < best_idx)
        ):
            best_name = name
            best_score = score
            best_idx = idx
            best_fails = fails
    if reject_fired:
        return None, 0.0, [], rej_hits
    if best_name is None:
        fails_all: list[str] = []
        for oc in outcomes:
            if not isinstance(oc, dict):
                continue
            asserts = oc.get("assertions") or []
            if not isinstance(asserts, list):
                asserts = []
            ok, fails = eval_assertion_list(
                asserts,
                ctx,
                ordered_tool_names=ordered_tool_names,
                tool_calls_flat=tool_calls_flat,
                tool_result_texts=tool_result_texts,
                assistant_row_count=assistant_row_count,
            )
            if not ok:
                fails_all.extend(fails)
        reason = fails_all or ["no_matching_outcome"]
        return None, 0.0, reason, []
    return best_name, best_score, best_fails, []


def resolve_env_interpolation(value: str) -> tuple[str | None, bool]:
    """
    Resolve ``${VAR}`` whole-string env references.
    Returns (resolved_value, ok). When the pattern matches but env is unset, (None, False).
    """
    m = ENV_INTERP_RE.match(value.strip())
    if not m:
        return value, True
    var = m.group(1)
    v = os.environ.get(var, "").strip()
    if not v:
        return None, False
    return v, True


def resolve_fixture_value(raw: Any) -> tuple[Any, bool]:
    if isinstance(raw, str):
        resolved, ok = resolve_env_interpolation(raw)
        return resolved, ok
    if isinstance(raw, dict):
        out: dict[str, Any] = {}
        for k, v in raw.items():
            rv, ok = resolve_fixture_value(v)
            if not ok:
                return None, False
            out[k] = rv
        return out, True
    return raw, True


def resolve_suite_fixtures(suite: dict[str, Any]) -> dict[str, dict[str, Any]]:
    raw = suite.get("fixtures")
    if not isinstance(raw, dict):
        return {}
    out: dict[str, dict[str, Any]] = {}
    for name, spec in raw.items():
        if not isinstance(spec, dict):
            continue
        resolved, ok = resolve_fixture_value(spec)
        if ok and isinstance(resolved, dict):
            out[str(name)] = resolved
    return out


def fixture_is_complete(spec: dict[str, Any] | None) -> bool:
    if not spec:
        return False
    thing = str(spec.get("thing") or "").strip()
    service = str(spec.get("service") or "").strip()
    return bool(thing and service)


def resolve_user_message(text: str) -> str:
    """Replace ``${ENV_VAR}`` substrings in case user prompts from the process environment."""

    def repl(m: re.Match[str]) -> str:
        var = m.group(1)
        return os.environ.get(var, m.group(0))

    return re.sub(r"\$\{([A-Z_][A-Z0-9_]*)\}", repl, text)


def case_skip_for_fixtures(
    case: dict[str, Any],
    resolved_fixtures: dict[str, dict[str, Any]],
) -> tuple[bool, str | None]:
    unless = case.get("skipUnlessEnv")
    if unless is not None:
        env_vars: list[str] = []
        if isinstance(unless, list):
            env_vars = [str(v).strip() for v in unless if str(v).strip()]
        else:
            var = str(unless).strip()
            if var:
                env_vars = [var]
        for var in env_vars:
            if not os.environ.get(var, "").strip():
                reason = case.get("skipReason")
                if reason is not None and str(reason).strip():
                    return True, str(reason).strip()
                return True, f"env_{var}_unset"
    req = case.get("requiresFixture")
    if req is None:
        return False, None
    key = str(req).strip()
    if not key:
        return False, None
    spec = resolved_fixtures.get(key)
    if fixture_is_complete(spec):
        return False, None
    reason = case.get("skipReason")
    if reason is not None and str(reason).strip():
        return True, str(reason).strip()
    return True, f"fixture_missing_{key}"


def expand_assertion_list_items(
    items: list[Any],
    groups: dict[str, list[Any]],
    suite_path: Path,
    context: str,
) -> list[Any]:
    out: list[Any] = []
    for item in items:
        if isinstance(item, dict) and USE_ASSERTION_GROUP_KEY in item:
            gname = str(item[USE_ASSERTION_GROUP_KEY]).strip()
            if gname not in groups:
                raise SystemExit(f"{suite_path}: unknown assertion group {gname!r} in {context}")
            nested = groups[gname]
            if any(isinstance(x, dict) and USE_ASSERTION_GROUP_KEY in x for x in nested):
                raise SystemExit(f"{suite_path}: nested assertion groups not allowed ({gname!r})")
            out.extend(expand_assertion_list_items(nested, groups, suite_path, context))
            continue
        out.append(item)
    return out


def expand_suite_assertion_groups(suite: dict[str, Any], suite_path: Path) -> None:
    raw_groups = suite.get("assertionGroups")
    groups: dict[str, list[Any]] = {}
    if raw_groups is not None:
        if not isinstance(raw_groups, dict):
            raise SystemExit(f"{suite_path}: assertionGroups must be a mapping")
        for gname, gitems in raw_groups.items():
            if not isinstance(gitems, list):
                raise SystemExit(f"{suite_path}: assertionGroups.{gname} must be a list")
            groups[str(gname)] = gitems
    cases = suite.get("cases")
    if not isinstance(cases, list):
        return
    for case in cases:
        if not isinstance(case, dict):
            continue
        case_id = str(case.get("id") or "")
        turns = case.get("turns")
        if not isinstance(turns, list):
            continue
        for ti, turn in enumerate(turns):
            if not isinstance(turn, dict):
                continue
            ctx = f"case {case_id!r} turn {ti}"
            rej = turn.get("rejectIf")
            if isinstance(rej, list):
                turn["rejectIf"] = expand_assertion_list_items(rej, groups, suite_path, ctx + " rejectIf")
            aos = turn.get("acceptableOutcomes")
            if not isinstance(aos, list):
                continue
            for oi, oc in enumerate(aos):
                if not isinstance(oc, dict):
                    continue
                asserts = oc.get("assertions")
                if isinstance(asserts, list):
                    oc["assertions"] = expand_assertion_list_items(
                        asserts,
                        groups,
                        suite_path,
                        f"{ctx} outcome {oi}",
                    )


def parse_agent_matrix_entry(value: Any) -> tuple[str, bool]:
    if isinstance(value, str):
        name = value.strip()
        if not name:
            raise ValueError("agentMatrix entry missing thingName")
        return name, True
    if isinstance(value, dict):
        thing = str(value.get("thingName") or "").strip()
        if not thing:
            raise ValueError("agentMatrix object entry missing thingName")
        de = value.get("defaultEnabled", True)
        return thing, bool(de) if de is not None else True
    raise ValueError(f"agentMatrix entry must be string or object, got {type(value).__name__}")


def resolve_agent_matrix(
    suite: dict[str, Any],
    matrix_mode: str,
    *,
    agent_filter: list[str] | None,
    include_sonnet_env: bool,
) -> tuple[dict[str, str], list[tuple[str, str]]]:
    """
    Returns (label -> Thing name, skipped label/reason pairs).
    """
    am = suite["agentMatrix"]
    assert isinstance(am, dict)
    labels_filter: set[str] | None = None
    if agent_filter is not None:
        labels_filter = {x.strip() for x in agent_filter if x.strip()}
    include_sonnet = include_sonnet_env or os.environ.get("AGENT_EVAL_INCLUDE_SONNET", "").strip() in (
        "1",
        "true",
        "TRUE",
        "yes",
        "YES",
    )
    out: dict[str, str] = {}
    skipped: list[tuple[str, str]] = []
    for label, raw_entry in am.items():
        label_s = str(label)
        try:
            thing_name, default_enabled = parse_agent_matrix_entry(raw_entry)
        except ValueError as e:
            raise SystemExit(f"agentMatrix label {label_s!r}: {e}") from e
        enabled = default_enabled
        if labels_filter is not None:
            enabled = label_s in labels_filter
        elif not default_enabled and not include_sonnet:
            enabled = False
        if not enabled:
            reason = "defaultEnabled=false"
            if labels_filter is not None and label_s not in labels_filter:
                reason = "agent_filter_excluded"
            skipped.append((label_s, reason))
            print(
                f"Skipping label {label_s} ({reason}; use --agent-filter {label_s} or "
                "AGENT_EVAL_INCLUDE_SONNET=1 to include).",
                file=sys.stderr,
            )
            continue
        if matrix_mode == "env":
            ek = env_matrix_key(label_s)
            v = os.environ.get(ek, "").strip()
            if not v:
                raise SystemExit(f"agent-matrix=env requires {ek} for suite label {label_s!r}")
            thing_name = v
        out[label_s] = thing_name
    return out, skipped


def classify_provider_failure_kind(exc: BaseException, *, phase: str) -> str | None:
    if phase != "chat" or not isinstance(exc, InfraHttpError):
        return None
    if exc.code not in PROVIDER_HTTP_CODES:
        return None
    body = (exc.body or "").lower()
    if any(m in body for m in PROVIDER_BODY_MARKERS):
        return "provider_error"
    return None


def service_url_class(dev_server: str, thing_name: str, service_name: str) -> str:
    try:
        from urllib.parse import urlparse

        host = urlparse(dev_server.strip()).netloc or "thingworx"
    except Exception:
        host = "thingworx"
    return f"{host}/Things/{thing_name}/Services/{service_name}"


def infra_turn_row(
    *,
    turn_index: int,
    phase: str,
    code: str | None,
    error: str,
    failure_kind: str | None = None,
    configured_timeout_s: float | None = None,
    elapsed_ms: float | None = None,
    url_class: str | None = None,
    **extra: Any,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "turnIndex": turn_index,
        "skipped": False,
        "status": "infra_error",
        "phase": phase,
        "error": error,
        "score": 0.0,
        "selectedOutcome": None,
    }
    if code:
        row["code"] = code
    if failure_kind:
        row["failureKind"] = failure_kind
    if configured_timeout_s is not None:
        row["configuredTimeoutS"] = configured_timeout_s
    if elapsed_ms is not None:
        row["elapsedMs"] = round(elapsed_ms, 3)
    if url_class:
        row["urlClass"] = url_class
    row.update(extra)
    return row


def case_has_turn_infra(turn_reports: list[dict[str, Any]]) -> bool:
    return any(
        isinstance(t, dict) and not t.get("skipped") and str(t.get("status") or "") == "infra_error"
        for t in turn_reports
    )


def case_has_turn_reject(turn_reports: list[dict[str, Any]]) -> bool:
    for t in turn_reports:
        if not isinstance(t, dict) or t.get("skipped"):
            continue
        rh = t.get("rejectHits")
        if isinstance(rh, list) and rh:
            return True
    return False


def rollup_case_failure_kind(
    turn_reports: list[dict[str, Any]],
    *,
    status: str,
    case_pass: bool,
) -> str | None:
    if status == "infra_error":
        for t in turn_reports:
            if isinstance(t, dict) and t.get("failureKind") == "provider_error":
                return "provider_error"
        return None
    if status == "fail":
        if case_has_turn_reject(turn_reports):
            return "assertion_failed"
        return "semantic_failed"
    return None


def build_case_result_row(
    *,
    status: str,
    agent_label: str,
    agent_thing: str,
    case_id: str,
    conversation_id: str | None,
    turn_reports: list[dict[str, Any]],
    case_score: float,
    pass_threshold: float,
    case_score_mode: str,
    case_pass: bool,
    wall_ms: float,
    reset_case_meta: dict[str, Any],
    skip_reason: str | None = None,
) -> dict[str, Any]:
    fk = rollup_case_failure_kind(turn_reports, status=status, case_pass=case_pass)
    row: dict[str, Any] = {
        "kind": "case",
        "status": status,
        "agentLabel": agent_label,
        "agentThing": agent_thing,
        "caseId": case_id,
        "conversationId": conversation_id,
        "caseScore": case_score,
        "passThreshold": pass_threshold,
        "scoreMode": case_score_mode,
        "casePass": case_pass,
        "wallMs": round(wall_ms, 3),
        "turns": turn_reports,
        "failureKind": fk,
        **reset_case_meta,
    }
    if skip_reason:
        row["skipReason"] = skip_reason
    return row


def count_report_statuses(results: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for r in results:
        if not isinstance(r, dict):
            continue
        st = str(r.get("status") or "unknown")
        counts[st] = counts.get(st, 0) + 1
    return counts


def write_report_files(
    out_dir: Path,
    report: dict[str, Any],
    *,
    partial: bool,
) -> None:
    if partial:
        json_path = out_dir / "report.partial.json"
        md_path = out_dir / "report.partial.md"
        cmp_path = out_dir / "model-comparison.partial.md"
    else:
        json_path = out_dir / "report.json"
        md_path = out_dir / "report.md"
        cmp_path = out_dir / "model-comparison.md"
    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    cmp_path.write_text(render_model_comparison_markdown(report), encoding="utf-8")
    if not partial:
        for name in ("report.partial.json", "report.partial.md", "model-comparison.partial.md"):
            p = out_dir / name
            if p.is_file():
                p.unlink()


def flush_partial_report(
    out_dir: Path,
    report: dict[str, Any],
) -> None:
    try:
        write_report_files(out_dir, report, partial=True)
    except OSError as e:
        print(f"partial report write failed: {e}", file=sys.stderr)


def write_final_report_or_exit(out_dir: Path, report: dict[str, Any]) -> None:
    try:
        write_report_files(out_dir, report, partial=False)
        print(f"Wrote {out_dir / 'report.json'}")
        print(f"Wrote {out_dir / 'report.md'}")
        print(f"Wrote {out_dir / 'model-comparison.md'}")
    except OSError as e:
        print(f"final report write failed: {e}", file=sys.stderr)
        recovery = out_dir / "report.recovery.json"
        try:
            recovery.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print(f"Wrote recovery report {recovery}", file=sys.stderr)
        except OSError as e2:
            print(f"recovery report write failed: {e2}", file=sys.stderr)
        sys.exit(EXIT_REPORT_WRITE_FAILED)


def validate_case_skip_unless_env(case: dict[str, Any], suite_path: Path) -> None:
    unless = case.get("skipUnlessEnv")
    if unless is None:
        return
    case_id = case.get("id") or "(unknown)"
    if isinstance(unless, str):
        if not unless.strip():
            raise SystemExit(
                f"{suite_path}: case {case_id}: skipUnlessEnv string must be non-empty"
            )
        return
    if isinstance(unless, list):
        if not unless:
            raise SystemExit(
                f"{suite_path}: case {case_id}: skipUnlessEnv list must be non-empty"
            )
        for idx, item in enumerate(unless):
            if not isinstance(item, str) or not str(item).strip():
                raise SystemExit(
                    f"{suite_path}: case {case_id}: skipUnlessEnv[{idx}] must be a non-empty string"
                )
        return
    raise SystemExit(
        f"{suite_path}: case {case_id}: skipUnlessEnv must be a non-empty string or a non-empty list of strings"
    )


def validate_suite(suite: dict[str, Any], suite_path: Path) -> None:
    ver = suite.get("version")
    if ver != 1:
        raise SystemExit(f"{suite_path}: unsupported suite version {ver!r} (expected 1)")
    if not suite.get("suite"):
        raise SystemExit(f"{suite_path}: missing suite id")
    for sk in suite.keys():
        if sk not in SUITE_TOP_KEYS:
            raise SystemExit(f"{suite_path}: unknown suite key {sk!r}")
    cases = suite.get("cases")
    if not isinstance(cases, list) or not cases:
        raise SystemExit(f"{suite_path}: cases must be a non-empty list")
    am = suite.get("agentMatrix")
    if not isinstance(am, dict) or not am:
        raise SystemExit(f"{suite_path}: agentMatrix required")
    for label, entry in am.items():
        try:
            parse_agent_matrix_entry(entry)
        except ValueError as e:
            raise SystemExit(f"{suite_path}: agentMatrix[{label!r}]: {e}") from e
    ag = suite.get("assertionGroups")
    if ag is not None and not isinstance(ag, dict):
        raise SystemExit(f"{suite_path}: assertionGroups must be a mapping")
    fx = suite.get("fixtures")
    if fx is not None and not isinstance(fx, dict):
        raise SystemExit(f"{suite_path}: fixtures must be a mapping")
    for ci, case in enumerate(cases):
        if not isinstance(case, dict) or not case.get("id"):
            raise SystemExit(f"{suite_path}: case {ci} missing id")
        for ck in case.keys():
            if ck not in CASE_KEYS:
                raise SystemExit(f"{suite_path}: case {case.get('id')}: unknown case key {ck!r}")
        validate_case_skip_unless_env(case, suite_path)
        if case.get("resetMode") is not None:
            rm = str(case["resetMode"]).strip()
            if rm not in RESET_MODES:
                raise SystemExit(f"{suite_path}: case {case.get('id')}: invalid resetMode {rm!r}")
        turns = case.get("turns")
        if not isinstance(turns, list) or not turns:
            raise SystemExit(f"{suite_path}: case {case.get('id')}: turns required")
        for ti, turn in enumerate(turns):
            if not isinstance(turn, dict) or "user" not in turn:
                raise SystemExit(f"{suite_path}: case {case.get('id')}: turn {ti} missing user")
            for key in turn.keys():
                if key in ("user", "hostContext", "systemPrompt", "whenPreviousOutcome", "acceptableOutcomes", "rejectIf"):
                    continue
                raise SystemExit(f"{suite_path}: case {case.get('id')}: unknown turn key {key!r}")
            aos = turn.get("acceptableOutcomes")
            if not isinstance(aos, list) or not aos:
                raise SystemExit(f"{suite_path}: case {case.get('id')}: turn {ti} needs acceptableOutcomes")
            for ai, ao in enumerate(aos):
                if not isinstance(ao, dict) or "name" not in ao:
                    raise SystemExit(f"{suite_path}: case {case.get('id')}: outcome {ai} needs name")
                asserts = ao.get("assertions")
                if asserts is None:
                    continue
                if not isinstance(asserts, list):
                    raise SystemExit(f"{suite_path}: assertions must be list")
                for ait in asserts:
                    k, _ = normalize_assertion_item(ait)
                    if k == "invalid":
                        raise SystemExit(f"{suite_path}: unknown assertion {ait!r}")
    if suite.get("resetMode") is not None:
        rm = str(suite["resetMode"]).strip()
        if rm not in RESET_MODES:
            raise SystemExit(f"{suite_path}: invalid suite resetMode {rm!r}")


def resolve_reset_mode_for_case(
    cli_reset: str | None,
    suite: dict[str, Any],
    case: dict[str, Any],
    suite_path: Path,
) -> str:
    if cli_reset:
        rm = str(cli_reset).strip()
        if rm not in RESET_MODES:
            raise SystemExit(f"{suite_path}: invalid --reset-mode {rm!r}")
        return rm
    if case.get("resetMode") is not None:
        rm = str(case["resetMode"]).strip()
        if rm not in RESET_MODES:
            raise SystemExit(f"{suite_path}: case {case.get('id')}: invalid resetMode {rm!r}")
        return rm
    if suite.get("resetMode") is not None:
        rm = str(suite["resetMode"]).strip()
        if rm not in RESET_MODES:
            raise SystemExit(f"{suite_path}: invalid suite resetMode {rm!r}")
        return rm
    return "fresh"


def conversation_eval_title(suite_id: str, case_id: str, logical_label: str, reset_mode: str) -> str:
    if reset_mode == "fresh":
        return f"eval:{suite_id}:{case_id}:{logical_label}:{random_title_suffix()}"
    return f"eval:{suite_id}:{case_id}:{logical_label}"


def random_title_suffix() -> str:
    return str(uuid.uuid4())


def run_turn_chat(
    dev_server: str,
    app_key: str,
    agent_thing: str,
    *,
    message: str,
    conversation_id: str,
    host_context_obj: Any | None,
    system_prompt: str | None,
    timeout_s: float,
) -> str:
    url = build_thing_service_url(dev_server, agent_thing, "Chat")
    payload: dict[str, Any] = {"message": message, "conversationId": conversation_id}
    if system_prompt is not None:
        payload["systemPrompt"] = system_prompt
    if host_context_obj is not None:
        payload["hostContext"] = json.dumps(host_context_obj, ensure_ascii=False)
    data = post_json(url, app_key, payload, timeout_s=timeout_s)
    text = extract_scalar_result(data)
    if text is None:
        raise InfraJsonError("Chat missing string result", url, str(data)[:2000])
    return text


def get_or_create_conversation_id(
    dev_server: str,
    app_key: str,
    agent_thing: str,
    *,
    title: str,
    timeout_s: float,
) -> str:
    url = build_thing_service_url(dev_server, agent_thing, "GetOrCreateConversationId")
    data = post_json(url, app_key, {"title": title}, timeout_s=timeout_s)
    rows = extract_rows_from_service_result(data)
    if len(rows) != 1:
        raise InfraTableRowsError(f"GetOrCreateConversationId expected 1 row, got {len(rows)}")
    cid = rows[0].get("conversationId")
    if cid is None or (isinstance(cid, str) and not str(cid).strip()):
        raise InfraHttpError(500, "missing_conversationId", url, str(rows[0])[:500])
    return str(cid)


def excerpt(s: str, max_len: int = 400) -> str:
    s = s or ""
    if len(s) <= max_len:
        return s
    return s[: max_len - 3] + "..."


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Parler Agent live evaluation harness (ThingWorx REST).")
    p.add_argument("--suite", required=True, help="Path to suite YAML (e.g. docs/agent/evals/smoke.yaml)")
    p.add_argument(
        "--agent-matrix",
        choices=("yaml", "env"),
        default="yaml",
        help="Resolve AgentThing names from suite YAML or from AGENT_EVAL_AGENT_* env vars (default: yaml)",
    )
    p.add_argument("--case", dest="case_id", default=None, help="Run only this case id")
    p.add_argument("--max-cases", type=int, default=None, help="Stop after N cases (order preserved)")
    p.add_argument("--timeout", type=float, default=HTTP_TIMEOUT_DEFAULT_S, help=f"Per-service HTTP timeout seconds (default {HTTP_TIMEOUT_DEFAULT_S})")
    p.add_argument(
        "--stream-buffer-wait-s",
        type=float,
        default=STREAM_BUFFER_WAIT_DEFAULT_S,
        dest="stream_buffer_wait_s",
        help=(
            "Seconds to wait after Chat before a single QueryStreamData read "
            f"(default {STREAM_BUFFER_WAIT_DEFAULT_S}; align with platform stream flush bound + margin)"
        ),
    )
    p.add_argument("--fail-fast", action="store_true", help="Stop after first infra or semantic failure")
    p.add_argument(
        "--reset-mode",
        choices=sorted(RESET_MODES),
        default=None,
        dest="reset_mode",
        help="Override suite/case reset mode (fresh | stable_clear | stable_hard_reset)",
    )
    p.add_argument(
        "--hard-reset-page-size",
        type=int,
        default=QUERY_STREAM_MAX_ITEMS,
        dest="hard_reset_page_size",
        help=f"QueryStreamEntries page size for stable_hard_reset (default {QUERY_STREAM_MAX_ITEMS})",
    )
    p.add_argument(
        "--hard-reset-max-rounds",
        type=int,
        default=STABLE_HARD_RESET_MAX_ROUNDS,
        dest="hard_reset_max_rounds",
        help="Safety cap on QueryStreamEntries rounds for stable_hard_reset",
    )
    p.add_argument(
        "--out-dir",
        default=None,
        help=(
            "Write reports under this base directory with a timestamped child directory "
            "(default base: tmp/agent-eval)"
        ),
    )
    p.add_argument(
        "--agent-filter",
        default=None,
        dest="agent_filter",
        help="Comma-separated agentMatrix labels to run (e.g. gpt_4_1,gpt_5_4)",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    load_dotenv(ENV_PATH)
    dev_server = require_env("DEV_SERVER")
    app_key = require_env("DEV_KEY")

    suite_path = (REPO_ROOT / args.suite).resolve() if not Path(args.suite).is_absolute() else Path(args.suite)
    if not suite_path.is_file():
        print(f"Suite not found: {suite_path}", file=sys.stderr)
        sys.exit(1)
    suite = yaml.safe_load(suite_path.read_text(encoding="utf-8"))
    if not isinstance(suite, dict):
        print("Suite root must be a mapping", file=sys.stderr)
        sys.exit(1)
    expand_suite_assertion_groups(suite, suite_path)
    validate_suite(suite, suite_path)

    agent_filter: list[str] | None = None
    if args.agent_filter:
        agent_filter = [p.strip() for p in str(args.agent_filter).split(",") if p.strip()]
    matrix, matrix_skipped = resolve_agent_matrix(
        suite,
        args.agent_matrix,
        agent_filter=agent_filter,
        include_sonnet_env=False,
    )
    if not matrix:
        labels = list(suite.get("agentMatrix", {}).keys())
        print(
            f"no AgentThing matches filter {args.agent_filter!r} (available labels: {', '.join(map(str, labels))}); exiting",
            file=sys.stderr,
        )
        sys.exit(EXIT_NO_AGENT_FILTER_MATCH)
    resolved_fixtures = resolve_suite_fixtures(suite)
    suite_id = str(suite["suite"])
    cases: list[dict[str, Any]] = [c for c in suite["cases"] if isinstance(c, dict)]
    if args.case_id:
        cases = [c for c in cases if str(c.get("id")) == args.case_id]
        if not cases:
            print(f"No case with id {args.case_id!r}", file=sys.stderr)
            sys.exit(1)
    if args.max_cases is not None:
        cases = cases[: max(0, args.max_cases)]

    ts = time.strftime("%Y%m%d-%H%M%SZ", time.gmtime())
    out_base_dir = Path(args.out_dir) if args.out_dir else REPO_ROOT / "tmp" / "agent-eval"
    out_dir = out_base_dir / ts
    out_dir.mkdir(parents=True, exist_ok=True)

    host_only = dev_server.strip()
    try:
        from urllib.parse import urlparse

        host_only = urlparse(dev_server).netloc or host_only
    except Exception:
        pass

    run_started = time.perf_counter()

    def report_snapshot(*, run_status: str) -> dict[str, Any]:
        meta = {
            "suite": suite_id,
            "suitePath": str(suite_path.relative_to(REPO_ROOT))
            if suite_path.is_relative_to(REPO_ROOT)
            else str(suite_path),
            "timestampUtc": ts,
            "environmentHost": host_only,
            "agentMatrixMode": args.agent_matrix,
            "streamBufferWaitS": float(args.stream_buffer_wait_s),
            "suiteDefaultResetMode": str(suite.get("resetMode") or "fresh"),
            "runStatus": run_status,
            "wallMs": round((time.perf_counter() - run_started) * 1000.0, 3),
            "statusCounts": count_report_statuses(results),
            "matrixLabelsSkipped": [{"label": a, "reason": b} for a, b in matrix_skipped],
        }
        if args.agent_filter:
            meta["agentFilter"] = args.agent_filter
        return {"meta": meta, "results": list(results)}

    results: list[dict[str, Any]] = []
    had_semantic_fail = False
    had_infra_fail = False
    stop_all = False
    run_status = "running"
    active_case: dict[str, Any] | None = None

    def flush_now() -> None:
        flush_partial_report(out_dir, report_snapshot(run_status=run_status))

    if hasattr(signal, "SIGTERM"):

        def _sigterm_handler(_signum: int, _frame: Any) -> None:
            raise KeyboardInterrupt

        signal.signal(signal.SIGTERM, _sigterm_handler)

    def _run_eval_cases() -> None:
        nonlocal had_semantic_fail, had_infra_fail, stop_all, active_case
        for logical_label, agent_thing in matrix.items():
            if stop_all:
                break
            for case in cases:
                case_id = str(case.get("id"))
                case_score_mode = str(case.get("scoreMode") or "sum")
                try:
                    pass_threshold = float(case.get("passThreshold", 1.0))
                except (TypeError, ValueError):
                    pass_threshold = 1.0

                reset_mode = resolve_reset_mode_for_case(args.reset_mode, suite, case, suite_path)
                title = conversation_eval_title(suite_id, case_id, logical_label, reset_mode)
                t0 = time.perf_counter()
                reset_case_meta: dict[str, Any] = {
                    "resetMode": reset_mode,
                    "conversationTitle": title,
                    "streamRowsDeleted": 0,
                    "streamRowsRemainingAfterReset": 0,
                }

                skip_case, skip_reason = case_skip_for_fixtures(case, resolved_fixtures)
                if skip_case:
                    results.append(
                        build_case_result_row(
                            status="skipped",
                            agent_label=logical_label,
                            agent_thing=agent_thing,
                            case_id=case_id,
                            conversation_id=None,
                            turn_reports=[],
                            case_score=0.0,
                            pass_threshold=pass_threshold,
                            case_score_mode=case_score_mode,
                            case_pass=False,
                            wall_ms=round((time.perf_counter() - t0) * 1000.0, 3),
                            reset_case_meta=reset_case_meta,
                            skip_reason=skip_reason,
                        )
                    )
                    flush_now()
                    continue

                active_case = None

                try:
                    conv_id = get_or_create_conversation_id(
                        dev_server,
                        app_key,
                        agent_thing,
                        title=title,
                        timeout_s=args.timeout,
                    )
                except (InfraHttpError, InfraUrlError, InfraJsonError, InfraTableRowsError) as e:
                    had_infra_fail = True
                    fk = classify_provider_failure_kind(e, phase="get_or_create_conversation")
                    results.append(
                        {
                            "kind": "case",
                            "status": "infra_error",
                            "failureKind": fk,
                            "agentLabel": logical_label,
                            "agentThing": agent_thing,
                            "caseId": case_id,
                            "error": str(e),
                            "phase": "get_or_create_conversation",
                            "conversationId": None,
                            **reset_case_meta,
                        }
                    )
                    active_case = None
                    flush_now()
                    if args.fail_fast:
                        stop_all = True
                        break
                    continue

                reset_case_meta["conversationId"] = conv_id

                if reset_mode in ("stable_clear", "stable_hard_reset"):
                    try:
                        clear_conversation(
                            dev_server,
                            app_key,
                            agent_thing,
                            conversation_id=conv_id,
                            timeout_s=args.timeout,
                        )
                    except InfraHttpError as e:
                        had_infra_fail = True
                        entry: dict[str, Any] = {
                            "kind": "case",
                            "status": "infra_error",
                            "agentLabel": logical_label,
                            "agentThing": agent_thing,
                            "caseId": case_id,
                            "error": str(e),
                            "phase": "clear_conversation",
                            "turns": [],
                            **reset_case_meta,
                        }
                        if is_pending_approval_clear_block(e):
                            entry["code"] = "pending_approval_blocks_clear"
                        results.append(entry)
                        flush_now()
                        if args.fail_fast:
                            stop_all = True
                            break
                        continue
                    except (InfraUrlError, InfraJsonError) as e:
                        had_infra_fail = True
                        results.append(
                            {
                                "kind": "case",
                                "status": "infra_error",
                                "agentLabel": logical_label,
                                "agentThing": agent_thing,
                                "caseId": case_id,
                                "error": str(e),
                                "phase": "clear_conversation",
                                "turns": [],
                                **reset_case_meta,
                            }
                        )
                        flush_now()
                        if args.fail_fast:
                            stop_all = True
                            break
                        continue

                if reset_mode == "stable_hard_reset":
                    page_sz = max(1, min(int(args.hard_reset_page_size), QUERY_STREAM_MAX_ITEMS))
                    del_n, rem_n, herr = stable_hard_reset_stream(
                        dev_server,
                        app_key,
                        conversation_id=conv_id,
                        page_size=page_sz,
                        max_query_rounds=max(1, int(args.hard_reset_max_rounds)),
                        timeout_s=args.timeout,
                    )
                    reset_case_meta["streamRowsDeleted"] = del_n
                    reset_case_meta["streamRowsRemainingAfterReset"] = rem_n
                    if herr:
                        had_infra_fail = True
                        results.append(
                            {
                                "kind": "case",
                                "status": "infra_error",
                                "agentLabel": logical_label,
                                "agentThing": agent_thing,
                                "caseId": case_id,
                                "error": herr,
                                "phase": "stream_hard_reset",
                                "code": herr,
                                "turns": [],
                                **reset_case_meta,
                            }
                        )
                        flush_now()
                        if args.fail_fast:
                            stop_all = True
                            break
                        continue

                turns_spec: list[dict[str, Any]] = case["turns"]  # type: ignore[assignment]
                turn_reports: list[dict[str, Any]] = []
                selected_prev: str | None = None
                case_infra = False

                for turn_index, turn in enumerate(turns_spec):
                    when_prev = turn.get("whenPreviousOutcome")
                    if when_prev is not None:
                        if str(when_prev) != str(selected_prev):
                            turn_reports.append(
                                {
                                    "turnIndex": turn_index,
                                    "skipped": True,
                                    "skipReason": "whenPreviousOutcome_mismatch",
                                    "expectedPrevious": str(when_prev),
                                    "actualPrevious": selected_prev,
                                    "score": 0.0,
                                    "selectedOutcome": None,
                                }
                            )
                            continue

                    active_case = {
                        "kind": "case",
                        "agentLabel": logical_label,
                        "agentThing": agent_thing,
                        "caseId": case_id,
                        "conversationId": conv_id,
                        "turns": turn_reports,
                        "interruptPhase": "stream_query",
                        "interruptCode": "interrupted_during_stream_query",
                        "interruptError": "interrupted_during_stream_query",
                        **reset_case_meta,
                    }

                    # Fresh conversation + row-count cursor: first turn has mark=0; later turns slice new rows only.
                    rows_before, pre_err = pre_chat_stream_query(
                        dev_server,
                        app_key,
                        conversation_id=conv_id,
                        turn_index=turn_index,
                        timeout_s=args.timeout,
                    )
                    if pre_err is not None:
                        case_infra = True
                        had_infra_fail = True
                        turn_reports.append(pre_err)
                        break
                    mark = len(rows_before)  # type: ignore[arg-type]

                    active_case["interruptPhase"] = "chat"
                    active_case["interruptCode"] = "interrupted_during_chat"
                    active_case["interruptError"] = "interrupted_during_chat"

                    user_text = resolve_user_message(str(turn["user"]))
                    host_ctx = turn.get("hostContext")
                    sys_prompt = turn.get("systemPrompt")
                    if sys_prompt is not None:
                        sys_prompt = str(sys_prompt)

                    t_chat0 = time.perf_counter()
                    try:
                        final_text = run_turn_chat(
                            dev_server,
                            app_key,
                            agent_thing,
                            message=user_text,
                            conversation_id=conv_id,
                            host_context_obj=host_ctx,
                            system_prompt=sys_prompt,
                            timeout_s=args.timeout,
                        )
                    except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
                        case_infra = True
                        had_infra_fail = True
                        chat_ms_partial = (time.perf_counter() - t_chat0) * 1000.0
                        fk = classify_provider_failure_kind(e, phase="chat")
                        turn_reports.append(
                            infra_turn_row(
                                turn_index=turn_index,
                                phase="chat",
                                code=f"http_{e.code}" if isinstance(e, InfraHttpError) else "chat_error",
                                error=str(e),
                                failure_kind=fk,
                                configured_timeout_s=args.timeout,
                                elapsed_ms=chat_ms_partial,
                                url_class=service_url_class(dev_server, agent_thing, "Chat"),
                                chatMs=round(chat_ms_partial, 3),
                            )
                        )
                        break
                    chat_ms = (time.perf_counter() - t_chat0) * 1000.0

                    buf_wait_s = max(0.0, float(args.stream_buffer_wait_s))
                    if buf_wait_s > 0:
                        time.sleep(buf_wait_s)
                    buffer_wait_ms = buf_wait_s * 1000.0

                    rows_after: list[dict[str, Any]] = []
                    t_sq0 = time.perf_counter()
                    try:
                        rows_after = query_stream_data(
                            dev_server,
                            app_key,
                            source=conv_id,
                            max_items=QUERY_STREAM_MAX_ITEMS,
                            timeout_s=args.timeout,
                        )
                    except (InfraHttpError, InfraUrlError, InfraJsonError) as e:
                        case_infra = True
                        had_infra_fail = True
                        stream_query_ms = (time.perf_counter() - t_sq0) * 1000.0
                        delta_bad = rows_after[mark:] if len(rows_after) >= mark else rows_after
                        active_case["interruptPhase"] = "stream_query"
                        turn_reports.append(
                            infra_turn_row(
                                turn_index=turn_index,
                                phase="stream_query",
                                code="stream_query_after_chat",
                                error=f"stream_query_after_chat: {e}; delta_len={len(delta_bad)}",
                                configured_timeout_s=args.timeout,
                                elapsed_ms=stream_query_ms,
                                url_class=service_url_class(dev_server, STREAM_THING, "QueryStreamData"),
                                finalExcerpt=excerpt(final_text),
                                finalText=final_text,
                                chatMs=round(chat_ms, 3),
                                streamBufferWaitMs=round(buffer_wait_ms, 3),
                                streamQueryMs=round(stream_query_ms, 3),
                                deltaRowCount=len(delta_bad),
                                turnWallMs=round(chat_ms + buffer_wait_ms + stream_query_ms, 3),
                            )
                        )
                        break

                    stream_query_ms = (time.perf_counter() - t_sq0) * 1000.0
                    delta = rows_after[mark:] if len(rows_after) >= mark else rows_after

                    incomplete = post_chat_incomplete_trace_row(
                        turn_index=turn_index,
                        delta=delta,
                        final_text=final_text,
                        buf_wait_s=buf_wait_s,
                        chat_ms=chat_ms,
                        buffer_wait_ms=buffer_wait_ms,
                        stream_query_ms=stream_query_ms,
                    )
                    if incomplete is not None:
                        case_infra = True
                        had_infra_fail = True
                        active_case["interruptPhase"] = "stream_query"
                        turn_reports.append(incomplete)
                        break

                    assistant_row_count = sum(
                        1 for r in delta if str(r.get("role") or "").strip().lower() == "assistant"
                    )
                    ordered_names, flat_calls, tool_texts, arg_parse_errs = collect_tool_events(delta)
                    ctx = build_turn_context(final_text, delta, per_call_argument_errors=arg_parse_errs)

                    outcomes = turn.get("acceptableOutcomes") or []
                    reject_items = turn.get("rejectIf") or []
                    if not isinstance(outcomes, list):
                        outcomes = []
                    if not isinstance(reject_items, list):
                        reject_items = []

                    oname, score, afails, rhits = select_outcome(
                        outcomes,
                        reject_items,
                        ctx,
                        ordered_tool_names=ordered_names,
                        tool_calls_flat=flat_calls,
                        tool_result_texts=tool_texts,
                        assistant_row_count=assistant_row_count,
                    )
                    selected_prev = oname
                    turn_wall_ms = chat_ms + buffer_wait_ms + stream_query_ms

                    turn_reports.append(
                        {
                            "turnIndex": turn_index,
                            "skipped": False,
                            "userExcerpt": excerpt(user_text, 240),
                            "selectedOutcome": oname,
                            "score": score,
                            "pass": score > 0 and not rhits and not case_infra,
                            "assertionFailures": afails,
                            "rejectHits": rhits,
                            "finalExcerpt": excerpt(final_text),
                            "finalText": final_text,
                            "orderedToolCalls": ordered_names,
                            "conversationId": conv_id,
                            "resetMode": reset_mode,
                            "promptTokens": ctx.prompt_tokens_turn,
                            "completionTokens": ctx.completion_tokens_turn,
                            "inputTokens": ctx.input_tokens_turn,
                            "outputTokens": ctx.output_tokens_turn,
                            "cacheReadInputTokens": ctx.cache_read_input_tokens_turn,
                            "cacheCreationInputTokens": ctx.cache_creation_input_tokens_turn,
                            "cachedPromptTokens": ctx.cached_prompt_tokens_turn,
                            "reasoningTokens": ctx.reasoning_tokens_turn,
                            "requestIdsObserved": ctx.request_ids_observed,
                            "usageByProvider": ctx.usage_by_provider,
                            "traceParseErrors": ctx.trace_parse_errors,
                            "chatMs": round(chat_ms, 3),
                            "streamBufferWaitMs": round(buffer_wait_ms, 3),
                            "streamQueryMs": round(stream_query_ms, 3),
                            "turnWallMs": round(turn_wall_ms, 3),
                        }
                    )

                if case_infra:
                    results.append(
                        build_case_result_row(
                            status="infra_error",
                            agent_label=logical_label,
                            agent_thing=agent_thing,
                            case_id=case_id,
                            conversation_id=conv_id,
                            turn_reports=turn_reports,
                            case_score=0.0,
                            pass_threshold=pass_threshold,
                            case_score_mode=case_score_mode,
                            case_pass=False,
                            wall_ms=round((time.perf_counter() - t0) * 1000.0, 3),
                            reset_case_meta=reset_case_meta,
                        )
                    )
                    active_case = None
                    flush_now()
                    if args.fail_fast:
                        stop_all = True
                        break
                    continue

                executed_scores: list[float] = []
                for tr in turn_reports:
                    if tr.get("skipped"):
                        continue
                    if tr.get("status") == "infra_error":
                        continue
                    executed_scores.append(float(tr.get("score") or 0.0))

                if case_score_mode == "average" and executed_scores:
                    case_score = sum(executed_scores) / len(executed_scores)
                else:
                    case_score = sum(executed_scores)

                case_pass = case_score >= pass_threshold and not case_infra
                if not case_pass:
                    had_semantic_fail = True

                results.append(
                    build_case_result_row(
                        status="ok" if case_pass else "fail",
                        agent_label=logical_label,
                        agent_thing=agent_thing,
                        case_id=case_id,
                        conversation_id=conv_id,
                        turn_reports=turn_reports,
                        case_score=case_score,
                        pass_threshold=pass_threshold,
                        case_score_mode=case_score_mode,
                        case_pass=case_pass,
                        wall_ms=round((time.perf_counter() - t0) * 1000.0, 3),
                        reset_case_meta=reset_case_meta,
                    )
                )
                active_case = None
                flush_now()

                if args.fail_fast and not case_pass:
                    stop_all = True
                    break
            if stop_all:
                break

    try:
        _run_eval_cases()
    except KeyboardInterrupt:
        run_status = "interrupted"
        if active_case is not None:
            active_case["status"] = "interrupted"
            active_case["phase"] = active_case.get("interruptPhase") or "interrupted"
            active_case["code"] = active_case.get("interruptCode") or "keyboard_interrupt"
            active_case["error"] = active_case.get("interruptError") or "interrupted"
            results.append(active_case)
        flush_now()
        sys.exit(EXIT_INTERRUPTED)
    except Exception:
        run_status = "aborted"
        flush_now()
        raise
    else:
        run_status = "completed"
        write_final_report_or_exit(out_dir, report_snapshot(run_status=run_status))
        if had_infra_fail:
            sys.exit(EXIT_INFRA_FAIL)
        if had_semantic_fail:
            sys.exit(EXIT_SEMANTIC_FAIL)
        sys.exit(EXIT_OK)


def render_markdown(report: dict[str, Any]) -> str:
    meta = report.get("meta") or {}
    lines: list[str] = []
    lines.append(f"# Agent eval — {meta.get('suite')}")
    lines.append("")
    lines.append(f"- Timestamp (UTC): `{meta.get('timestampUtc')}`")
    lines.append(f"- Environment: `{meta.get('environmentHost')}`")
    lines.append(f"- Agent matrix mode: `{meta.get('agentMatrixMode')}`")
    if meta.get("streamBufferWaitS") is not None:
        lines.append(f"- Stream buffer wait (s): `{meta.get('streamBufferWaitS')}`")
    if meta.get("suiteDefaultResetMode"):
        lines.append(f"- Suite default resetMode: `{meta.get('suiteDefaultResetMode')}`")
    if meta.get("runStatus"):
        lines.append(f"- Run status: `{meta.get('runStatus')}`")
    if meta.get("wallMs") is not None:
        lines.append(f"- Wall time (ms): `{meta.get('wallMs')}`")
    skipped_labels = meta.get("matrixLabelsSkipped")
    if isinstance(skipped_labels, list) and skipped_labels:
        lines.append(f"- Matrix labels skipped: `{skipped_labels}`")
    lines.append("")

    results = [r for r in report.get("results") or [] if isinstance(r, dict)]
    fails = [r for r in results if r.get("status") in ("fail", "infra_error", "interrupted")]
    oks = [r for r in results if r.get("status") == "ok"]
    skipped = [r for r in results if r.get("status") == "skipped"]
    status_counts = meta.get("statusCounts")
    if isinstance(status_counts, dict):
        lines.append("## Summary")
        lines.append("")
        for k in sorted(status_counts.keys()):
            lines.append(f"- `{k}`: {status_counts[k]}")
        lines.append("")
    else:
        lines.append("## Summary")
        lines.append("")
        lines.append(f"| pass | {len(oks)} |")
        lines.append(f"| fail / infra / interrupted | {len(fails)} |")
        lines.append(f"| skipped | {len(skipped)} |")
        lines.append("")

    if fails:
        lines.append("## Failures first")
        lines.append("")
        for r in fails:
            lines.append(f"### `{r.get('caseId')}` — `{r.get('agentLabel')}` (`{r.get('agentThing')}`)")
            lines.append("")
            lines.append(f"- status: **{r.get('status')}**")
            if r.get("failureKind"):
                lines.append(f"- failureKind: `{r.get('failureKind')}`")
            if r.get("skipReason"):
                lines.append(f"- skipReason: `{r.get('skipReason')}`")
            if r.get("resetMode"):
                lines.append(f"- resetMode: `{r.get('resetMode')}`")
            if r.get("conversationTitle"):
                lines.append(f"- conversationTitle: `{r.get('conversationTitle')}`")
            if r.get("phase"):
                lines.append(f"- phase: `{r.get('phase')}`")
            if r.get("code"):
                lines.append(f"- code: `{r.get('code')}`")
            if str(r.get("resetMode") or "") == "stable_hard_reset":
                if r.get("streamRowsDeleted") is not None:
                    lines.append(f"- streamRowsDeleted: `{r.get('streamRowsDeleted')}`")
                if r.get("streamRowsRemainingAfterReset") is not None:
                    lines.append(f"- streamRowsRemainingAfterReset: `{r.get('streamRowsRemainingAfterReset')}`")
            if r.get("conversationId"):
                lines.append(f"- conversationId: `{r.get('conversationId')}`")
            if r.get("error"):
                lines.append(f"- error: `{r.get('error')}`")
            turns = r.get("turns")
            if isinstance(turns, list):
                for t in turns:
                    if not isinstance(t, dict):
                        continue
                    if t.get("skipped"):
                        lines.append(
                            f"- turn {t.get('turnIndex')}: **skipped** ({t.get('skipReason')}) "
                            f"expected `{t.get('expectedPrevious')}` got `{t.get('actualPrevious')}`"
                        )
                        continue
                    if str(t.get("status") or "") == "infra_error":
                        extra = ""
                        if t.get("code"):
                            extra += f" code=`{t.get('code')}`"
                        if t.get("failureKind"):
                            extra += f" failureKind=`{t.get('failureKind')}`"
                        if t.get("streamBufferWaitMs") is not None:
                            extra += f" bufferWaitMs=`{t.get('streamBufferWaitMs')}`"
                        if t.get("deltaRowCount") is not None:
                            extra += f" deltaRows=`{t.get('deltaRowCount')}`"
                        lines.append(
                            f"- turn {t.get('turnIndex')}: **infra_error** "
                            f"phase=`{t.get('phase')}` error=`{t.get('error')}`{extra}"
                        )
                        continue
                    lines.append(f"- turn {t.get('turnIndex')}: outcome `{t.get('selectedOutcome')}` score `{t.get('score')}`")
                    if t.get("streamBufferWaitMs") is not None:
                        lines.append(f"  - stream buffer wait (ms): `{t.get('streamBufferWaitMs')}`")
                    if t.get("assertionFailures"):
                        lines.append(f"  - assertion failures: {t.get('assertionFailures')}")
                    if t.get("rejectHits"):
                        lines.append(f"  - reject hits: {t.get('rejectHits')}")
                    if t.get("finalExcerpt"):
                        lines.append(f"  - final excerpt: {t.get('finalExcerpt')!r}")
                    if t.get("orderedToolCalls"):
                        lines.append(f"  - tools: `{t.get('orderedToolCalls')}`")
            lines.append("")

    if oks:
        lines.append("## Passed")
        lines.append("")
        for r in oks:
            lines.append(
                f"- `{r.get('caseId')}` / `{r.get('agentLabel')}` — score `{r.get('caseScore')}` "
                f"conversation `{r.get('conversationId')}`"
            )
            if r.get("resetMode"):
                lines.append(f"  - resetMode: `{r.get('resetMode')}`; title `{r.get('conversationTitle')}`")
            if str(r.get("resetMode") or "") == "stable_hard_reset" and r.get("streamRowsDeleted") is not None:
                lines.append(
                    f"  - stream hard reset: deleted `{r.get('streamRowsDeleted')}` "
                    f"remaining `{r.get('streamRowsRemainingAfterReset')}`"
                )
            turns_ok = r.get("turns")
            if isinstance(turns_ok, list):
                for t in turns_ok:
                    if isinstance(t, dict) and not t.get("skipped") and t.get("promptTokens") is not None:
                        lines.append(
                            f"  - turn {t.get('turnIndex')}: promptTokens `{t.get('promptTokens')}` "
                            f"completionTokens `{t.get('completionTokens')}` "
                            f"inputTokens `{t.get('inputTokens')}` outputTokens `{t.get('outputTokens')}`"
                        )
                        if t.get("traceParseErrors"):
                            lines.append(f"    - traceParseErrors: `{t.get('traceParseErrors')}`")
                        break
        lines.append("")

    return "\n".join(lines)


def _markdown_table_cell(value: Any) -> str:
    s = "" if value is None else str(value)
    return s.replace("|", "\\|").replace("\n", "<br>")


def _first_provider_usage(turn: dict[str, Any]) -> dict[str, Any]:
    by_provider = turn.get("usageByProvider")
    if isinstance(by_provider, dict):
        for usage in by_provider.values():
            if isinstance(usage, dict):
                return usage
    return {}


def render_model_comparison_markdown(report: dict[str, Any]) -> str:
    meta = report.get("meta") or {}
    results = [r for r in report.get("results") or [] if isinstance(r, dict)]
    lines: list[str] = []
    lines.append(f"# Agent Eval Model Comparison — {meta.get('suite')}")
    lines.append("")
    lines.append(f"- Timestamp (UTC): `{meta.get('timestampUtc')}`")
    lines.append(f"- Environment: `{meta.get('environmentHost')}`")
    lines.append(f"- Agent matrix mode: `{meta.get('agentMatrixMode')}`")
    if meta.get("streamBufferWaitS") is not None:
        lines.append(f"- Stream buffer wait (s): `{meta.get('streamBufferWaitS')}`")
    lines.append("")

    lines.append("## Summary")
    lines.append("")
    lines.append("| Case | Agent | Thing | Status | Score | Input | Output | Cached prompt | Tools |")
    lines.append("|---|---|---|---:|---:|---:|---:|---:|---|")
    for r in results:
        turns = [t for t in r.get("turns") or [] if isinstance(t, dict) and not t.get("skipped")]
        first_turn = turns[0] if turns else {}
        usage = _first_provider_usage(first_turn)
        tools = first_turn.get("orderedToolCalls") or []
        lines.append(
            "| {case} | {agent} | `{thing}` | {status} | {score} | {inp} | {out} | {cached} | `{tools}` |".format(
                case=_markdown_table_cell(r.get("caseId")),
                agent=_markdown_table_cell(r.get("agentLabel")),
                thing=_markdown_table_cell(r.get("agentThing")),
                status=_markdown_table_cell(r.get("status")),
                score=_markdown_table_cell(r.get("caseScore")),
                inp=_markdown_table_cell(usage.get("inputTokens", first_turn.get("inputTokens"))),
                out=_markdown_table_cell(usage.get("outputTokens", first_turn.get("outputTokens"))),
                cached=_markdown_table_cell(usage.get("cachedPromptTokens", first_turn.get("cachedPromptTokens"))),
                tools=_markdown_table_cell(" -> ".join(str(x) for x in tools)),
            )
        )
    lines.append("")

    lines.append("## Details")
    lines.append("")
    for r in results:
        lines.append(f"### `{r.get('caseId')}` — `{r.get('agentLabel')}` (`{r.get('agentThing')}`)")
        lines.append("")
        lines.append(f"- Status: `{r.get('status')}`; pass: `{r.get('casePass')}`; score: `{r.get('caseScore')}`")
        if r.get("conversationId"):
            lines.append(f"- Conversation: `{r.get('conversationId')}`")
        if r.get("resetMode"):
            lines.append(f"- Reset mode: `{r.get('resetMode')}`")
        turns = r.get("turns") or []
        if not isinstance(turns, list):
            turns = []
        for t in turns:
            if not isinstance(t, dict):
                continue
            turn_index = t.get("turnIndex")
            if t.get("skipped"):
                lines.append("")
                lines.append(f"#### Turn {turn_index} — skipped")
                lines.append("")
                lines.append(f"- Reason: `{t.get('skipReason')}`")
                continue
            lines.append("")
            lines.append(f"#### Turn {turn_index}")
            lines.append("")
            lines.append(f"- Selected outcome: `{t.get('selectedOutcome')}`")
            lines.append(f"- Score: `{t.get('score')}`; pass: `{t.get('pass')}`")
            if t.get("orderedToolCalls"):
                lines.append(f"- Tools: `{t.get('orderedToolCalls')}`")
            if t.get("assertionFailures"):
                lines.append(f"- Assertion failures: `{t.get('assertionFailures')}`")
            if t.get("rejectHits"):
                lines.append(f"- Reject hits: `{t.get('rejectHits')}`")
            if t.get("traceParseErrors"):
                lines.append(f"- Trace parse errors: `{t.get('traceParseErrors')}`")
            final_text = str(t.get("finalText") or t.get("finalExcerpt") or "").strip()
            lines.append("")
            lines.append("##### Full Final Response")
            lines.append("")
            lines.append(final_text if final_text else "<empty final response>")
            lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    main()

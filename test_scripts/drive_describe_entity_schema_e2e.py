"""
Drive a short Agent ``Chat`` turn intended to elicit ``describe_entity_schema`` calls, then wait for
stream persistence.

Requires ``DEV_SERVER``, ``DEV_KEY``, ``PARLER_E2E_AGENT_THING`` (Thing name), and
``PARLER_E2E_THING_TEMPLATE`` (ThingTemplate entity name visible to the app key).

Usage::

  uv run drive-describe-entity-schema-e2e

Then run ``uv run describe-schema-harvest-from-stream --source <printed conversationId>``.

Exit 0 when ``Chat`` returns; exit 3 on HTTP/infra failure; exit 6 when env vars missing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

from test_scripts.agent_eval import (
    ENV_PATH,
    InfraHttpError,
    InfraJsonError,
    InfraUrlError,
    get_or_create_conversation_id,
    load_dotenv,
    require_env,
    run_turn_chat,
)

STREAM_BUFFER_WAIT_S = 12.0


def _require_any(*names: str) -> str:
    for n in names:
        v = os.environ.get(n, "").strip()
        if v:
            return v
    print(f"drive: need one of: {', '.join(names)}", file=sys.stderr)
    raise SystemExit(6)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--chat-timeout",
        type=float,
        default=600.0,
        help="HTTP timeout for Chat (default 600s).",
    )
    p.add_argument(
        "--buffer-wait",
        type=float,
        default=STREAM_BUFFER_WAIT_S,
        help="Seconds to wait after Chat before stream harvest (default 12).",
    )
    args = p.parse_args()

    load_dotenv(ENV_PATH)
    dev = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")
    agent = _require_any("PARLER_E2E_AGENT_THING", "AGENT_EVAL_AGENT_GPT_4_1", "AGENT_EVAL_AGENT_GPT_4_1_MINI")
    tmpl = _require_any("PARLER_E2E_THING_TEMPLATE")

    title = f"e2e-describe-entity-schema-{os.getpid()}"
    try:
        cid = get_or_create_conversation_id(dev, key, agent, title=title, timeout_s=args.chat_timeout)
    except (InfraHttpError, InfraUrlError, InfraJsonError, OSError) as e:
        print(f"drive: GetOrCreateConversationId failed: {e}", file=sys.stderr)
        return 3

    a1 = json.dumps(
        {"entityType": "ThingTemplate", "entityName": tmpl, "facet": "summary", "scope": "effective"},
        ensure_ascii=False,
    )
    a2 = json.dumps(
        {
            "entityType": "ThingTemplate",
            "entityName": tmpl,
            "facet": "properties",
            "maxItems": 15,
            "scope": "effective",
        },
        ensure_ascii=False,
    )
    a3 = json.dumps(
        {"entityType": "ThingTemplate", "entityName": tmpl, "facet": "summary", "scope": "local"},
        ensure_ascii=False,
    )

    msg = (
        "You MUST call the built-in tool describe_entity_schema exactly three times in this order "
        "(use these exact tool arguments JSON objects):\n\n"
        f"1) {a1}\n"
        f"2) {a2}\n"
        f"3) {a3}\n\n"
        "After those tool calls, reply with one line: done."
    )

    try:
        _ = run_turn_chat(
            dev,
            key,
            agent,
            message=msg,
            conversation_id=cid,
            host_context_obj=None,
            system_prompt=None,
            timeout_s=args.chat_timeout,
        )
    except (InfraHttpError, InfraUrlError, InfraJsonError, OSError) as e:
        print(f"drive: Chat failed: {e}", file=sys.stderr)
        return 3

    time.sleep(max(0.0, args.buffer_wait))
    print(f"drive: conversationId={cid}")
    print(f"drive: next: uv run describe-schema-harvest-from-stream --source {cid!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

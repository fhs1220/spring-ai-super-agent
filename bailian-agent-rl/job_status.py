#!/usr/bin/env python3
"""Read a Model Studio Agentic RL job status and optional logs."""

from __future__ import annotations

import argparse
import json
import os
from typing import Any


def serializable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump()
    if hasattr(value, "to_dict"):
        return value.to_dict()
    if hasattr(value, "__dict__"):
        return {
            key: serializable(item)
            for key, item in vars(value).items()
            if not key.startswith("_")
        }
    if isinstance(value, (list, tuple)):
        return [serializable(item) for item in value]
    if isinstance(value, dict):
        return {key: serializable(item) for key, item in value.items()}
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("job_id")
    parser.add_argument("--logs", type=int, default=0)
    args = parser.parse_args()

    if not os.environ.get("DASHSCOPE_API_KEY"):
        parser.error("DASHSCOPE_API_KEY is required")

    from dashscope.finetune.agentic_rl import AgenticRL

    status = AgenticRL.get(job_id=args.job_id)
    print(json.dumps(serializable(status), ensure_ascii=False, indent=2, default=str))
    if args.logs > 0:
        logs = AgenticRL.logs(job_id=args.job_id, lines=min(args.logs, 1000))
        print(json.dumps(serializable(logs), ensure_ascii=False, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

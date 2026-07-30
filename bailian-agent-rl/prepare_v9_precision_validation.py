#!/usr/bin/env python3
"""Freeze the one-shot v9 precision validation without model calls."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from prepare_v8_precision_validation import freeze


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent
        / "precision-validation/v9-config.json",
    )
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = freeze(
            args.config,
            args.seeds,
            args.replay,
            args.trajectories,
            args.output,
        )
        print(json.dumps({
            "state": "V9_PRECISION_VALIDATION_FROZEN",
            "manifest": str(args.output / "manifest.json"),
            "freeze_fingerprint": manifest["freeze_fingerprint"],
            "plan_fingerprint": manifest["plan_fingerprint"],
            "seed_count": manifest["seed_count"],
            "planned_agent_runs": manifest["planned_agent_runs"],
            "execution_modes": manifest["execution_modes"],
            "request_types": manifest["request_types"],
            "historical_baseline": manifest["historical_baseline"],
            "authorization_ceiling": manifest["authorization_ceiling"],
            "model_api_calls": 0,
            "billable_operations": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except (KeyError, TypeError, ValueError) as exception:
        print(
            f"V9 precision validation freeze failed: {exception}",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

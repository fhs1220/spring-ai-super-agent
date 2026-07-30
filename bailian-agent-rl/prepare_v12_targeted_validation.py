#!/usr/bin/env python3
"""Freeze a 6x2 v12 targeted validation without model calls."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from decimal import Decimal, ROUND_CEILING
from pathlib import Path
from typing import Any

from prepare_v8_precision_validation import (
    canonical_sha256,
    execution_mode,
    file_sha256,
    load_object,
    task_kind,
    write_json,
    write_jsonl,
)
from replay_training_seeds import (
    build_plan,
    load_seeds,
    plan_fingerprint,
)


SCHEMA_VERSION = "rl-v12-targeted-validation-freeze-v1"
BATCH_ID = "policy-v12-targeted-6x2"
POLICY_VERSION = "agentic-rag-v12"
BASELINE_POLICY_VERSION = "agentic-rag-v10"
SELECTOR_VERSION = "deterministic-rlvr-selector-v5"
ROUNDS = 2
AUTHORIZATION_MULTIPLIER = 1.5


def baseline_by_seed(
    evaluation: dict[str, Any],
    target_ids: set[str],
) -> tuple[list[dict[str, Any]], float]:
    values: dict[str, list[float]] = defaultdict(list)
    rows = evaluation.get("candidate_evidence")
    rows = rows if isinstance(rows, list) else []
    for row in rows:
        if not isinstance(row, dict):
            continue
        seed_id = str(row.get("seed_id") or "")
        if seed_id not in target_ids:
            continue
        values[seed_id].append(float(row["final_rlvr"]))
    if set(values) != target_ids or any(
        len(rounds) != ROUNDS for rounds in values.values()
    ):
        raise ValueError("V10 evaluation does not cover every target seed twice")
    evidence = [
        {
            "seed_id": seed_id,
            "baseline_mean_rlvr": round(
                sum(values[seed_id]) / len(values[seed_id]), 6
            ),
            "baseline_rounds": [
                round(value, 6) for value in values[seed_id]
            ],
        }
        for seed_id in sorted(values)
    ]
    all_values = [
        value for seed_values in values.values() for value in seed_values
    ]
    return evidence, sum(all_values) / len(all_values)


def authorization_ceiling(
    replay: dict[str, Any],
    planned_runs: int,
) -> dict[str, Any]:
    completed_runs = int(replay["completed_agent_runs"])
    if completed_runs < 1:
        raise ValueError("V10 replay has no completed runs")
    telemetry = replay["observed_telemetry"]
    ratio = (
        Decimal(planned_runs)
        / Decimal(completed_runs)
        * Decimal(str(AUTHORIZATION_MULTIPLIER))
    )
    return {
        "maximum_model_calls": int(
            (
                Decimal(int(replay["underlying_model_call_count"]))
                * ratio
            ).to_integral_value(rounding=ROUND_CEILING)
        ),
        "maximum_tokens": int(
            (
                Decimal(int(telemetry["total_tokens"])) * ratio
            ).to_integral_value(rounding=ROUND_CEILING)
        ),
        "maximum_estimated_cost_cny": round(
            float(
                Decimal(str(telemetry["estimated_cost_cny"])) * ratio
            ),
            7,
        ),
        "multiplier": AUTHORIZATION_MULTIPLIER,
        "source": "v10_observed_per_trajectory_cost",
    }


def freeze(
    v11_audit_path: Path,
    seeds_path: Path,
    v10_evaluation_path: Path,
    v10_replay_path: Path,
    output: Path,
) -> dict[str, Any]:
    v11_audit = load_object(v11_audit_path)
    if v11_audit.get("state") != "V11_OFFLINE_NOT_READY_FOR_REPLAY":
        raise ValueError("Unexpected v11 audit state")
    remaining = v11_audit.get("remaining_rows")
    remaining = remaining if isinstance(remaining, list) else []
    target_ids = {
        str(row.get("seed_id") or "")
        for row in remaining
        if isinstance(row, dict) and row.get("seed_id")
    }
    if len(target_ids) != 6:
        raise ValueError("V12 targeted validation must contain six seeds")

    source_seeds = load_seeds(seeds_path)
    selected = [
        seed for seed in source_seeds if seed["seed_id"] in target_ids
    ]
    if {seed["seed_id"] for seed in selected} != target_ids:
        raise ValueError("Target seed is missing from the frozen source set")
    plan = build_plan(selected, BATCH_ID, ROUNDS, None)
    evidence, baseline_average = baseline_by_seed(
        load_object(v10_evaluation_path), target_ids
    )
    source_replay = load_object(v10_replay_path)

    output.mkdir(parents=True, exist_ok=True)
    selected_path = output / "selected-seeds.jsonl"
    plan_path = output / "plan.json"
    write_jsonl(selected_path, selected)
    write_json(plan_path, plan)
    mode_counts = Counter(execution_mode(seed) for seed in selected)
    type_counts = Counter(task_kind(seed) for seed in selected)
    payload = {
        "schema_version": SCHEMA_VERSION,
        "validation_id": BATCH_ID,
        "batch_id": BATCH_ID,
        "candidate_policy_version": POLICY_VERSION,
        "baseline_policy_version": BASELINE_POLICY_VERSION,
        "selector_version": SELECTOR_VERSION,
        "contract_version": "answer-verification-contract-v3",
        "contract_repair_renderer_version":
            "deterministic-contract-repair-renderer-v1",
        "rounds": ROUNDS,
        "seed_count": len(selected),
        "planned_agent_runs": len(plan),
        "targeted_v11_failure_rows": len(remaining),
        "plan_fingerprint": plan_fingerprint(plan),
        "selected_seeds_path": str(selected_path),
        "selected_seeds_fingerprint": file_sha256(selected_path),
        "plan_path": str(plan_path),
        "plan_file_fingerprint": file_sha256(plan_path),
        "source_v11_audit_fingerprint": file_sha256(v11_audit_path),
        "source_v10_evaluation_fingerprint":
            file_sha256(v10_evaluation_path),
        "source_v10_replay_fingerprint": file_sha256(v10_replay_path),
        "execution_modes": dict(sorted(mode_counts.items())),
        "request_types": dict(sorted(type_counts.items())),
        "historical_evidence": evidence,
        "historical_baseline": {
            "average_rlvr": round(baseline_average, 6),
            "trajectory_count": len(plan),
        },
        "evaluation_gates": {
            "minimum_final_contract_pass_rate": 1.0,
            "maximum_structured_repair_parse_failures": 0,
            "maximum_forbidden_phrase_failures": 0,
            "maximum_invalid_selector_versions": 0,
            "maximum_route_mismatches": 0,
            "maximum_timeouts": 0,
            "maximum_rlvr_violations": 0,
            "minimum_mean_delta": 0.0,
            "replay_scope": "TARGETED_PILOT_ONLY",
        },
        "authorization_ceiling": authorization_ceiling(
            source_replay, len(plan)
        ),
        "model_api_calls": 0,
        "billable_operations": 0,
    }
    manifest = {
        **payload,
        "freeze_fingerprint": canonical_sha256(payload),
    }
    write_json(output / "manifest.json", manifest)
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--v11-audit", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--v10-evaluation", type=Path, required=True)
    parser.add_argument("--v10-replay", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = freeze(
            args.v11_audit,
            args.seeds,
            args.v10_evaluation,
            args.v10_replay,
            args.output,
        )
        print(json.dumps({
            "state": "V12_TARGETED_VALIDATION_FROZEN",
            "manifest": str(args.output / "manifest.json"),
            "seed_count": manifest["seed_count"],
            "planned_agent_runs": manifest["planned_agent_runs"],
            "historical_baseline": manifest["historical_baseline"],
            "authorization_ceiling": manifest["authorization_ceiling"],
            "model_api_calls": 0,
            "billable_operations": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except (KeyError, OSError, TypeError, ValueError) as error:
        print(f"V12 targeted validation freeze failed: {error}",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

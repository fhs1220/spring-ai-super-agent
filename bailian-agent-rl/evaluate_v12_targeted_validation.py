#!/usr/bin/env python3
"""Evaluate the completed v12 targeted replay without model calls."""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

from evaluate_v8_precision_validation import candidate_evidence
from prepare_v8_precision_validation import (
    canonical_sha256,
    file_sha256,
    load_object,
    write_json,
)
from prepare_v12_targeted_validation import SCHEMA_VERSION
from replay_training_seeds import load_seeds


REPORT_SCHEMA_VERSION = "rl-v12-targeted-validation-report-v1"


def verify_manifest(path: Path) -> dict[str, Any]:
    manifest = load_object(path)
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("Unexpected v12 targeted freeze schema")
    payload = {
        key: value for key, value in manifest.items()
        if key != "freeze_fingerprint"
    }
    if manifest.get("freeze_fingerprint") != canonical_sha256(payload):
        raise ValueError("V12 targeted freeze fingerprint changed")
    selected_path = Path(str(manifest["selected_seeds_path"]))
    plan_path = Path(str(manifest["plan_path"]))
    if file_sha256(selected_path) != manifest["selected_seeds_fingerprint"]:
        raise ValueError("Selected seed fingerprint changed")
    if file_sha256(plan_path) != manifest["plan_file_fingerprint"]:
        raise ValueError("Targeted plan fingerprint changed")
    return manifest


def trajectory_v12_signals(
    trajectory: dict[str, Any],
    renderer_version: str,
) -> dict[str, Any]:
    steps = trajectory.get("steps")
    steps = steps if isinstance(steps, list) else []
    revise_outputs = [
        step.get("output")
        for step in steps
        if isinstance(step, dict) and step.get("type") == "REVISE"
        and isinstance(step.get("output"), dict)
    ]
    selection = next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == "RLVR_SELECT"
        ),
        {},
    )
    selection_input = (
        selection.get("input")
        if isinstance(selection.get("input"), dict) else {}
    )
    selection_output = (
        selection.get("output")
        if isinstance(selection.get("output"), dict) else {}
    )
    review = next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == "REVIEW"
        ),
        {},
    )
    review_output = (
        review.get("output")
        if isinstance(review.get("output"), dict) else {}
    )
    final_contract_output = (
        selection_output if selection_output else review_output
    )
    invalid_renderer_traces = sum(
        output.get("contractRepairRendererVersion") != renderer_version
        for output in revise_outputs
    )
    structured_repair_failures = sum(
        output.get("fallbackUsed") is True
        or output.get("revised") is not True
        for output in revise_outputs
    )
    forbidden_failures = sum(
        str(requirement).startswith("删除禁用短语：")
        for requirement in final_contract_output.get(
            "missingRequirements", []
        )
    )
    return {
        "revise_count": len(revise_outputs),
        "invalid_renderer_traces": invalid_renderer_traces,
        "structured_repair_failures": structured_repair_failures,
        "forbidden_phrase_failures": forbidden_failures,
        "selector_version": selection_input.get("selectorVersion"),
        "selected_contract_passed":
            final_contract_output.get("verificationContractPassed"),
    }


def replay_timeout_count(result: dict[str, Any]) -> int:
    telemetry = result.get("telemetry")
    telemetry = telemetry if isinstance(telemetry, dict) else {}
    return int(telemetry.get("timeout_count") or 0)


def evaluate(
    manifest_path: Path,
    replay_path: Path,
    trajectories_path: Path,
) -> dict[str, Any]:
    manifest = verify_manifest(manifest_path)
    replay = load_object(replay_path)
    if replay.get("batch_id") != manifest["batch_id"]:
        raise ValueError("Replay batch identity changed")
    if replay.get("policy_version") != manifest[
        "candidate_policy_version"
    ]:
        raise ValueError("Replay policy identity changed")
    if replay.get("plan_fingerprint") != manifest["plan_fingerprint"]:
        raise ValueError("Replay plan identity changed")
    results = replay.get("results")
    if (
        not isinstance(results, list)
        or len(results) != manifest["planned_agent_runs"]
        or any(result.get("status") != "COMPLETED" for result in results)
    ):
        raise ValueError("Targeted replay is not fully completed")

    seeds = load_seeds(Path(str(manifest["selected_seeds_path"])))
    seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
    baseline = {
        item["seed_id"]: float(item["baseline_mean_rlvr"])
        for item in manifest["historical_evidence"]
    }
    candidate_by_seed: dict[str, list[float]] = defaultdict(list)
    rows: list[dict[str, Any]] = []
    for result in results:
        seed_id = str(result["seed_id"])
        if seed_id not in seeds_by_id:
            raise ValueError(f"Replay contains unknown seed {seed_id}")
        trajectory_id = str(result["trajectory_id"])
        trajectory = load_object(
            trajectories_path / f"{trajectory_id}.json"
        )
        evidence = candidate_evidence(trajectory, seeds_by_id[seed_id])
        signals = trajectory_v12_signals(
            trajectory,
            str(manifest["contract_repair_renderer_version"]),
        )
        recorded = float(result["rlvr"]["total"])
        if abs(float(evidence["final_rlvr"]) - recorded) > 1e-6:
            raise ValueError(
                f"RLVR reconstruction mismatch for {trajectory_id}"
            )
        candidate_by_seed[seed_id].append(recorded)
        rows.append({
            "seed_id": seed_id,
            "round": result["round"],
            "trajectory_id": trajectory_id,
            "final_rlvr": recorded,
            **signals,
        })
    if set(candidate_by_seed) != set(baseline) or any(
        len(values) != manifest["rounds"]
        for values in candidate_by_seed.values()
    ):
        raise ValueError("Replay does not contain every targeted seed twice")

    candidate_means = {
        seed_id: sum(values) / len(values)
        for seed_id, values in candidate_by_seed.items()
    }
    deltas = [
        candidate_means[seed_id] - baseline[seed_id]
        for seed_id in sorted(candidate_means)
    ]
    mean_delta = sum(deltas) / len(deltas)
    final_contract_passes = sum(
        row["selected_contract_passed"] is True for row in rows
    )
    parse_failures = sum(
        row["structured_repair_failures"] for row in rows
    )
    forbidden_failures = sum(
        row["forbidden_phrase_failures"] for row in rows
    )
    invalid_renderer_traces = sum(
        row["invalid_renderer_traces"] for row in rows
    )
    invalid_selector_versions = sum(
        row["selector_version"] != manifest["selector_version"]
        for row in rows
        if row["selector_version"] is not None
    )
    route_mismatches = sum(
        result.get("route_expectation_matched") is not True
        for result in results
    )
    timeouts = sum(replay_timeout_count(result) for result in results)
    violations = sum(bool(result["rlvr"].get("violations"))
                     for result in results)
    gates = manifest["evaluation_gates"]
    checks = {
        "final_contract_pass_rate_at_least_minimum":
            final_contract_passes / len(rows)
            >= float(gates["minimum_final_contract_pass_rate"]),
        "structured_repair_parse_failures_within_limit":
            parse_failures
            <= int(gates["maximum_structured_repair_parse_failures"]),
        "forbidden_phrase_failures_within_limit":
            forbidden_failures
            <= int(gates["maximum_forbidden_phrase_failures"]),
        "renderer_trace_integrity": invalid_renderer_traces == 0,
        "selector_version_integrity":
            invalid_selector_versions
            <= int(gates["maximum_invalid_selector_versions"]),
        "route_mismatches_within_limit":
            route_mismatches <= int(gates["maximum_route_mismatches"]),
        "timeouts_within_limit":
            timeouts <= int(gates["maximum_timeouts"]),
        "rlvr_violations_within_limit":
            violations <= int(gates["maximum_rlvr_violations"]),
        "targeted_mean_delta_non_negative":
            mean_delta >= float(gates["minimum_mean_delta"]),
    }
    return {
        "report_schema_version": REPORT_SCHEMA_VERSION,
        "validation_id": manifest["validation_id"],
        "decision": (
            "TARGETED_FIX_VALIDATED"
            if all(checks.values())
            else "TARGETED_FIX_NOT_VALIDATED"
        ),
        "scope": "TARGETED_PILOT_ONLY",
        "metrics": {
            "seed_count": len(candidate_by_seed),
            "trajectory_count": len(rows),
            "baseline_average_rlvr":
                manifest["historical_baseline"]["average_rlvr"],
            "candidate_average_rlvr": round(
                sum(
                    float(result["rlvr"]["total"])
                    for result in results
                ) / len(results),
                6,
            ),
            "targeted_mean_delta": round(mean_delta, 6),
            "final_contract_pass_count": final_contract_passes,
            "structured_repair_parse_failures": parse_failures,
            "forbidden_phrase_failures": forbidden_failures,
            "invalid_renderer_traces": invalid_renderer_traces,
            "invalid_selector_versions": invalid_selector_versions,
            "route_mismatches": route_mismatches,
            "timeouts": timeouts,
            "rlvr_violation_trajectories": violations,
        },
        "checks": checks,
        "candidate_evidence": rows,
        "model_api_calls_during_evaluation": 0,
        "billable_operations_during_evaluation": 0,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = evaluate(
            args.manifest, args.replay, args.trajectories
        )
        write_json(args.output, report)
        print(json.dumps({
            "decision": report["decision"],
            "scope": report["scope"],
            "metrics": report["metrics"],
            "checks": report["checks"],
            "report": str(args.output),
            "model_api_calls_during_evaluation": 0,
            "billable_operations_during_evaluation": 0,
        }, ensure_ascii=False, indent=2))
        return 0 if report["decision"] == "TARGETED_FIX_VALIDATED" else 1
    except (KeyError, OSError, TypeError, ValueError) as error:
        print(f"V12 targeted evaluation failed: {error}",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Freeze a high-risk, mode-balanced v8 precision validation plan."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from replay_training_seeds import (
    build_plan,
    first_user_question,
    load_seeds,
    plan_fingerprint,
)


CONFIG_SCHEMA_VERSION = "rl-precision-validation-config-v1"
FREEZE_SCHEMA_VERSION = "rl-precision-validation-freeze-v1"
EXPECTED_SOURCE_REPLAY_SCHEMA = "agent-rl-seed-replay-v2"
SUPPORTED_MODES = ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent
        / "precision-validation/v8-config.json",
    )
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(f"Cannot read JSON object {path}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain an object")
    return value


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(
            json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n"
            for value in values
        ),
        encoding="utf-8",
    )


def task_kind(seed: dict[str, Any]) -> str:
    extra = seed["rollout_extra"]
    group = extra.get("task_group")
    if not isinstance(group, str) or ":" not in group:
        raise ValueError(f"Seed {seed.get('seed_id')} has no task_group")
    return group.rsplit(":", 1)[-1]


def execution_mode(seed: dict[str, Any]) -> str:
    mode = (
        seed.get("rollout_extra", {})
        .get("route_expectation", {})
        .get("execution_mode")
    )
    if mode not in SUPPORTED_MODES:
        raise ValueError(f"Seed {seed.get('seed_id')} has invalid mode")
    return str(mode)


def has_revise_step(trajectory: dict[str, Any]) -> bool:
    return any(
        isinstance(step, dict) and step.get("type") == "REVISE"
        for step in trajectory.get("steps") or []
    )


def load_source_records(
    replay: dict[str, Any],
    trajectories: Path,
    seeds: dict[str, dict[str, Any]],
    expected_policy: str,
) -> dict[str, list[dict[str, Any]]]:
    if replay.get("schema_version") != EXPECTED_SOURCE_REPLAY_SCHEMA:
        raise ValueError("Unexpected source replay schema")
    if replay.get("policy_version") != expected_policy:
        raise ValueError("Source replay policy does not match config")
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in replay.get("results") or []:
        if not isinstance(result, dict):
            raise ValueError("Source replay result must be an object")
        seed_id = result.get("seed_id")
        trajectory_id = result.get("trajectory_id")
        if seed_id not in seeds or not isinstance(trajectory_id, str):
            raise ValueError("Source replay references an unknown seed/trajectory")
        path = trajectories / f"{trajectory_id}.json"
        trajectory = load_object(path)
        if trajectory.get("trajectoryId") != trajectory_id:
            raise ValueError(f"Trajectory identity mismatch: {trajectory_id}")
        if trajectory.get("question") != first_user_question(seeds[seed_id]):
            raise ValueError(f"Trajectory question mismatch: {trajectory_id}")
        rlvr = result.get("rlvr")
        telemetry = result.get("telemetry")
        if not isinstance(rlvr, dict) or not isinstance(telemetry, dict):
            raise ValueError(f"Source result lacks evidence: {trajectory_id}")
        grouped[seed_id].append({
            "round": int(result["round"]),
            "trajectory_id": trajectory_id,
            "rlvr": rlvr,
            "telemetry": telemetry,
            "status": result.get("status"),
            "route_expectation_matched":
                result.get("route_expectation_matched"),
            "revised": has_revise_step(trajectory),
        })
    for seed_id, records in grouped.items():
        if (
            len(records) != 2
            or {record["round"] for record in records} != {1, 2}
        ):
            raise ValueError(f"Seed {seed_id} does not have two source rounds")
    return dict(grouped)


def contract_risk(seed: dict[str, Any]) -> int:
    contract = seed["rollout_extra"]["verification_contract"]
    return sum((
        int(int(contract.get("minimum_action_items") or 0) >= 7),
        int(contract.get("no_follow_up") is True),
        int(contract.get("must_mark_assumptions") is True),
        int(bool(contract.get("forbidden_phrases"))),
    ))


def risk_sort_key(
    seed: dict[str, Any],
    records: list[dict[str, Any]],
) -> tuple[Any, ...]:
    scores = [float(record["rlvr"]["total"]) for record in records]
    return (
        -sum(record["revised"] for record in records),
        -sum(record["rlvr"].get("hard_gate_passed") is not True
             for record in records),
        -sum(bool(record["rlvr"].get("violations")) for record in records),
        -contract_risk(seed),
        min(scores),
        -(max(scores) - min(scores)),
        seed["seed_id"],
    )


def select_seeds(
    seeds: list[dict[str, Any]],
    records: dict[str, list[dict[str, Any]]],
    config: dict[str, Any],
) -> list[dict[str, Any]]:
    selection = config["selection"]
    per_mode = int(selection["seeds_per_execution_mode"])
    minimums = selection["minimum_request_types_per_mode"]
    if selection.get("include_all_historical_revise_seeds") is not True:
        raise ValueError("Precision plan must include all historical revise seeds")
    if sum(int(value) for value in minimums.values()) > per_mode:
        raise ValueError("Per-type minimums exceed the execution-mode quota")
    selected: list[dict[str, Any]] = []
    for mode in SUPPORTED_MODES:
        candidates = [
            seed for seed in seeds
            if seed["seed_id"] in records and execution_mode(seed) == mode
        ]
        candidates.sort(
            key=lambda seed: risk_sort_key(seed, records[seed["seed_id"]])
        )
        chosen = [
            seed for seed in candidates
            if any(
                record["revised"]
                for record in records[seed["seed_id"]]
            )
        ]
        if len(chosen) > per_mode:
            raise ValueError(
                f"Historical revise seeds exceed the {mode} quota"
            )
        for kind, minimum in minimums.items():
            need = int(minimum) - sum(
                task_kind(seed) == kind for seed in chosen
            )
            available = [
                seed for seed in candidates
                if seed not in chosen and task_kind(seed) == kind
            ]
            if len(available) < max(0, need):
                raise ValueError(f"Not enough {mode}/{kind} seeds")
            chosen.extend(available[:max(0, need)])
        for seed in candidates:
            if len(chosen) >= per_mode:
                break
            if seed not in chosen:
                chosen.append(seed)
        if len(chosen) != per_mode:
            raise ValueError(f"Could not freeze {per_mode} seeds for {mode}")
        selected.extend(chosen)
    return selected


def historical_evidence(
    selected: list[dict[str, Any]],
    records: dict[str, list[dict[str, Any]]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    evidence = []
    all_records = []
    for seed in selected:
        source = sorted(records[seed["seed_id"]], key=lambda item: item["round"])
        all_records.extend(source)
        scores = [float(record["rlvr"]["total"]) for record in source]
        evidence.append({
            "seed_id": seed["seed_id"],
            "execution_mode": execution_mode(seed),
            "task_kind": task_kind(seed),
            "risk": {
                "historical_revise_rounds":
                    sum(record["revised"] for record in source),
                "historical_hard_gate_failures": sum(
                    record["rlvr"].get("hard_gate_passed") is not True
                    for record in source
                ),
                "historical_violation_rounds": sum(
                    bool(record["rlvr"].get("violations"))
                    for record in source
                ),
                "verification_contract_risk": contract_risk(seed),
            },
            "baseline_rounds": source,
            "baseline_mean_rlvr": round(sum(scores) / len(scores), 6),
        })
    calls = sum(
        int(record["telemetry"]["model_call_count"])
        for record in all_records
    )
    tokens = sum(
        int(record["telemetry"]["total_tokens"])
        for record in all_records
    )
    cost = sum(
        float(record["telemetry"]["estimated_cost_cny"])
        for record in all_records
    )
    scores = [
        float(record["rlvr"]["total"])
        for record in all_records
    ]
    summary = {
        "trajectory_count": len(all_records),
        "average_rlvr": round(sum(scores) / len(scores), 6),
        "minimum_rlvr": round(min(scores), 6),
        "maximum_rlvr": round(max(scores), 6),
        "hard_gate_failures": sum(
            record["rlvr"].get("hard_gate_passed") is not True
            for record in all_records
        ),
        "violation_rounds": sum(
            bool(record["rlvr"].get("violations"))
            for record in all_records
        ),
        "historical_revise_seed_count": sum(
            any(record["revised"] for record in records[seed["seed_id"]])
            for seed in selected
        ),
        "historical_revise_run_count": sum(
            record["revised"]
            for seed in selected
            for record in records[seed["seed_id"]]
        ),
        "model_call_count": calls,
        "total_tokens": tokens,
        "estimated_cost_cny": round(cost, 7),
    }
    return evidence, summary


def authorization_ceiling(
    baseline: dict[str, Any],
    multiplier: float,
) -> dict[str, Any]:
    return {
        "maximum_model_calls": math.ceil(
            int(baseline["model_call_count"]) * multiplier
        ),
        "maximum_tokens": math.ceil(
            int(baseline["total_tokens"]) * multiplier
        ),
        "maximum_estimated_cost_cny": round(
            float(baseline["estimated_cost_cny"]) * multiplier,
            7,
        ),
        "multiplier": multiplier,
    }


def freeze(
    config_path: Path,
    seeds_path: Path,
    replay_path: Path,
    trajectories_path: Path,
    output: Path,
) -> dict[str, Any]:
    config = load_object(config_path)
    if config.get("schema_version") != CONFIG_SCHEMA_VERSION:
        raise ValueError("Unexpected precision validation config schema")
    seeds = load_seeds(seeds_path)
    seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
    replay = load_object(replay_path)
    records = load_source_records(
        replay,
        trajectories_path,
        seeds_by_id,
        str(config["baseline_policy_version"]),
    )
    selected = select_seeds(seeds, records, config)
    rounds = int(config["rounds"])
    plan = build_plan(selected, str(config["batch_id"]), rounds, None)
    evidence, baseline = historical_evidence(selected, records)
    selected_path = output / "selected-seeds.jsonl"
    plan_path = output / "plan.json"
    write_jsonl(selected_path, selected)
    write_json(plan_path, plan)
    mode_counts = Counter(execution_mode(seed) for seed in selected)
    type_counts = Counter(task_kind(seed) for seed in selected)
    payload = {
        "schema_version": FREEZE_SCHEMA_VERSION,
        "validation_id": config["validation_id"],
        "batch_id": config["batch_id"],
        "candidate_policy_version": config["candidate_policy_version"],
        "baseline_policy_version": config["baseline_policy_version"],
        "selector_version": config["selector_version"],
        "rounds": rounds,
        "seed_count": len(selected),
        "planned_agent_runs": len(plan),
        "plan_fingerprint": plan_fingerprint(plan),
        "config_fingerprint": file_sha256(config_path),
        "source_seeds_fingerprint": file_sha256(seeds_path),
        "source_replay_fingerprint": file_sha256(replay_path),
        "source_replay_plan_fingerprint": replay["plan_fingerprint"],
        "selected_seeds_path": str(selected_path),
        "selected_seeds_fingerprint": file_sha256(selected_path),
        "plan_path": str(plan_path),
        "plan_file_fingerprint": file_sha256(plan_path),
        "execution_modes": dict(sorted(mode_counts.items())),
        "request_types": dict(sorted(type_counts.items())),
        "historical_evidence": evidence,
        "historical_baseline": baseline,
        "evaluation_gates": config["evaluation"],
        "authorization_ceiling": authorization_ceiling(
            baseline,
            float(config["budget"]["authorization_multiplier"]),
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
            "state": "V8_PRECISION_VALIDATION_FROZEN",
            "manifest": str(args.output / "manifest.json"),
            "freeze_fingerprint": manifest["freeze_fingerprint"],
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
        print(f"Precision validation freeze failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

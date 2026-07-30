#!/usr/bin/env python3
"""Freeze RLAIF positives, RLVR preference pairs and an unseen Benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from prepare_local_preference_data import (
    benchmark_audit,
    canonical_sha256,
    dpo_sample,
    file_sha256,
    load_benchmark,
    load_object,
    load_trajectories,
    split_pairs,
    write_jsonl,
)


SCHEMA_VERSION = "local-hybrid-data-freeze-v1"
CONFIG_SCHEMA_VERSION = "local-policy-proxy-hybrid-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "local-proxy/hybrid-config.json",
    )
    parser.add_argument("--stage5-manifest", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--benchmark", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise ValueError(f"Cannot read JSONL {path}: {exception}") from exception
    values = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"Invalid JSONL at {path}:{line_number}"
            ) from exception
        if not isinstance(value, dict):
            raise ValueError(f"Expected object at {path}:{line_number}")
        values.append(value)
    return values


def stage5_paths(manifest: dict[str, Any]) -> tuple[Path, Path]:
    if manifest.get("schema_version") != "stage5-training-dataset-freeze-v1":
        raise ValueError("Unexpected Stage 5 manifest schema")
    arm = manifest.get("arms", {}).get("RLVR_RLAIF")
    if not isinstance(arm, dict) or arm.get("ready_for_submission") is not True:
        raise ValueError("Stage 5 RLVR_RLAIF arm is not ready")
    training = Path(str(arm["training_path"]))
    validation = Path(str(arm["validation_path"]))
    if file_sha256(training) != arm.get("training_fingerprint"):
        raise ValueError("Stage 5 RLAIF training fingerprint changed")
    if file_sha256(validation) != arm.get("validation_fingerprint"):
        raise ValueError("Stage 5 RLAIF validation fingerprint changed")
    return training, validation


def sft_sample(item: dict[str, Any]) -> dict[str, Any]:
    messages = item.get("messages")
    extra = item.get("rollout_extra")
    if not isinstance(messages, list) or not messages:
        raise ValueError("Stage 5 SFT sample has no messages")
    if not isinstance(extra, dict) or not isinstance(extra.get("solution"), str):
        raise ValueError("Stage 5 SFT sample has no positive solution")
    return {
        "trajectory_id": extra.get("source_trajectory_id"),
        "messages": [
            *messages,
            {"role": "assistant", "content": extra["solution"].strip()},
        ],
    }


def stable_rank(identifier: str) -> str:
    return hashlib.sha256(identifier.encode("utf-8")).hexdigest()


def judge_preference_pairs(
    items: list[dict[str, Any]],
    replay: dict[str, Any],
    trajectories: dict[str, dict[str, Any]],
    minimum_margin: float,
    maximum_answer_characters: int,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for result in replay.get("results") or []:
        if isinstance(result, dict) and isinstance(result.get("seed_id"), str):
            grouped.setdefault(result["seed_id"], []).append(result)
    counters = {
        "judge_positive_sources": len(items),
        "rejected_missing_pair": 0,
        "rejected_hard_gate": 0,
        "rejected_margin": 0,
        "rejected_answer_contract": 0,
    }
    pairs = []
    for item in items:
        extra = item["rollout_extra"]
        seed_id = extra["source_seed_id"]
        chosen_id = extra["source_trajectory_id"]
        candidates = grouped.get(seed_id) or []
        if len(candidates) != 2:
            counters["rejected_missing_pair"] += 1
            continue
        chosen_result = next(
            (value for value in candidates if value.get("trajectory_id") == chosen_id),
            None,
        )
        rejected_result = next(
            (value for value in candidates if value.get("trajectory_id") != chosen_id),
            None,
        )
        if chosen_result is None or rejected_result is None:
            counters["rejected_missing_pair"] += 1
            continue
        chosen_rlvr = chosen_result.get("rlvr") or {}
        rejected_rlvr = rejected_result.get("rlvr") or {}
        if (
            chosen_rlvr.get("hard_gate_passed") is not True
            or rejected_rlvr.get("hard_gate_passed") is not True
        ):
            counters["rejected_hard_gate"] += 1
            continue
        margin = float(chosen_rlvr["total"]) - float(rejected_rlvr["total"])
        if margin <= minimum_margin:
            counters["rejected_margin"] += 1
            continue
        rejected_trajectory = trajectories.get(rejected_result["trajectory_id"])
        chosen = extra.get("solution")
        rejected = (
            rejected_trajectory.get("finalAnswer")
            if isinstance(rejected_trajectory, dict)
            else None
        )
        if (
            not isinstance(chosen, str)
            or not isinstance(rejected, str)
            or not chosen.strip()
            or not rejected.strip()
            or chosen.strip() == rejected.strip()
            or len(chosen) > maximum_answer_characters
            or len(rejected) > maximum_answer_characters
        ):
            counters["rejected_answer_contract"] += 1
            continue
        pairs.append({
            "pair_id": seed_id,
            "question": item["messages"][-1]["content"],
            "chosen": chosen.strip(),
            "rejected": rejected.strip(),
            "chosen_trajectory_id": chosen_id,
            "rejected_trajectory_id": rejected_result["trajectory_id"],
            "chosen_rlvr": float(chosen_rlvr["total"]),
            "rejected_rlvr": float(rejected_rlvr["total"]),
            "rlvr_margin": margin,
        })
    return pairs, counters


def freeze_blind_cases(
    seeds: list[dict[str, Any]],
    replay: dict[str, Any],
    per_mode: int,
) -> list[dict[str, Any]]:
    results = replay.get("results")
    if not isinstance(results, list):
        raise ValueError("Replay results must be an array")
    used = {
        result.get("seed_id")
        for result in results
        if isinstance(result, dict) and isinstance(result.get("seed_id"), str)
    }
    by_mode: dict[str, list[dict[str, Any]]] = {}
    for seed in seeds:
        seed_id = seed.get("seed_id")
        extra = seed.get("rollout_extra")
        route = extra.get("route_expectation") if isinstance(extra, dict) else None
        mode = route.get("execution_mode") if isinstance(route, dict) else None
        if (
            isinstance(seed_id, str)
            and seed_id not in used
            and mode in {"SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT"}
        ):
            by_mode.setdefault(mode, []).append(seed)
    selected = []
    for mode in ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT"):
        candidates = sorted(
            by_mode.get(mode, []),
            key=lambda seed: stable_rank(seed["seed_id"]),
        )
        if len(candidates) < per_mode:
            raise ValueError(f"Not enough unused blind seeds for {mode}")
        selected.extend(candidates[:per_mode])
    cases = []
    for seed in sorted(selected, key=lambda item: item["seed_id"]):
        extra = seed["rollout_extra"]
        contract = extra["verification_contract"]
        route = extra["route_expectation"]
        messages = seed["messages"]
        cases.append({
            "id": f"blind-{seed['seed_id']}",
            "source_seed_id": seed["seed_id"],
            "question": messages[-1]["content"],
            "tags": extra.get("tags") or [],
            "requiredConcepts": contract.get("required_concepts") or [],
            "forbiddenPhrases": contract.get("forbidden_phrases") or [],
            "minAnswerChars": contract.get("minimum_answer_chars") or 80,
            "maxAnswerChars": contract.get("maximum_answer_chars") or 2000,
            "requireCitation": bool(contract.get("citation_required")),
            "expectedExecutionMode": route["execution_mode"],
            "referenceContext": extra["solution"],
        })
    return cases


def main() -> int:
    args = parse_args()
    try:
        config = load_object(args.config)
        if config.get("schema_version") != CONFIG_SCHEMA_VERSION:
            raise ValueError("Unexpected hybrid config schema")
        data = config["data_contract"]
        stage5 = load_object(args.stage5_manifest)
        sft_training_source, sft_validation_source = stage5_paths(stage5)
        sft_training_items = read_jsonl(sft_training_source)
        sft_validation_items = read_jsonl(sft_validation_source)
        sft_training = [sft_sample(item) for item in sft_training_items]
        sft_validation = [sft_sample(item) for item in sft_validation_items]
        replay = load_object(args.replay)
        trajectories = load_trajectories(args.trajectories)
        pairs, qualification = judge_preference_pairs(
            [*sft_training_items, *sft_validation_items],
            replay,
            trajectories,
            float(data["minimum_rlvr_margin"]),
            int(data["maximum_preference_answer_characters"]),
        )
        if len(pairs) < 20:
            raise ValueError(f"At least 20 strong preference pairs required; found {len(pairs)}")
        dpo_training, dpo_validation = split_pairs(
            pairs,
            float(data["preference_validation_ratio"]),
        )
        blind_cases = freeze_blind_cases(
            read_jsonl(args.seeds),
            replay,
            int(data["blind_cases_per_execution_mode"]),
        )
        old_benchmark = load_benchmark(args.benchmark)
        blind_benchmark = [
            {"id": case["id"], "question": case["question"]}
            for case in blind_cases
        ]
        audits = {
            "dpo_vs_original_benchmark": benchmark_audit(
                pairs,
                old_benchmark,
                float(data["benchmark_similarity_threshold"]),
            ),
            "dpo_vs_blind_benchmark": benchmark_audit(
                pairs,
                blind_benchmark,
                float(data["benchmark_similarity_threshold"]),
            ),
        }
        if not all(audit["passed"] for audit in audits.values()):
            raise ValueError("Hybrid training data overlaps an evaluation Benchmark")
        args.output.mkdir(parents=True, exist_ok=True)
        paths = {
            "sft_training": args.output / "sft-train.jsonl",
            "sft_validation": args.output / "sft-validation.jsonl",
            "dpo_training": args.output / "dpo-train.jsonl",
            "dpo_validation": args.output / "dpo-validation.jsonl",
            "blind_benchmark": args.output / "blind-benchmark.jsonl",
        }
        write_jsonl(paths["sft_training"], sft_training)
        write_jsonl(paths["sft_validation"], sft_validation)
        write_jsonl(
            paths["dpo_training"],
            [dpo_sample(pair) for pair in dpo_training],
        )
        write_jsonl(
            paths["dpo_validation"],
            [dpo_sample(pair) for pair in dpo_validation],
        )
        write_jsonl(paths["blind_benchmark"], blind_cases)
        counts = {
            "sft_training": len(sft_training),
            "sft_validation": len(sft_validation),
            "dpo_training": len(dpo_training),
            "dpo_validation": len(dpo_validation),
            "blind_benchmark": len(blind_cases),
        }
        payload = {
            "schema_version": SCHEMA_VERSION,
            "experiment_id": config["experiment_id"],
            "algorithm": config["algorithm"],
            "config_fingerprint": file_sha256(args.config),
            "stage5_manifest_fingerprint": file_sha256(args.stage5_manifest),
            "source_replay_fingerprint": file_sha256(args.replay),
            "source_seeds_fingerprint": file_sha256(args.seeds),
            "original_benchmark_fingerprint": file_sha256(args.benchmark),
            "paths": {name: str(path) for name, path in paths.items()},
            "fingerprints": {
                name: file_sha256(path) for name, path in paths.items()
            },
            "counts": counts,
            "preference_qualification": qualification,
            "eligible_preference_pairs": len(pairs),
            "average_rlvr_margin": round(
                sum(pair["rlvr_margin"] for pair in pairs) / len(pairs),
                8,
            ),
            "benchmark_audits": audits,
            "blind_execution_modes": {
                mode: sum(
                    case["expectedExecutionMode"] == mode
                    for case in blind_cases
                )
                for mode in ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")
            },
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }
        manifest = {
            **payload,
            "freeze_fingerprint": canonical_sha256(payload),
        }
        manifest_path = args.output / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True)
            + "\n",
            encoding="utf-8",
        )
        print(json.dumps({
            "state": "LOCAL_HYBRID_DATA_FROZEN",
            "manifest": str(manifest_path),
            "freeze_fingerprint": manifest["freeze_fingerprint"],
            "counts": counts,
            "eligible_preference_pairs": len(pairs),
            "average_rlvr_margin": payload["average_rlvr_margin"],
            "blind_execution_modes": payload["blind_execution_modes"],
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except (KeyError, TypeError, ValueError) as exception:
        print(f"Local hybrid data freeze failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

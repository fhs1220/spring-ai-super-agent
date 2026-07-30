#!/usr/bin/env python3
"""Freeze an offline DPO preference dataset from paired Stage 3 trajectories."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "local-preference-freeze-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--benchmark", type=Path, required=True)
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "local-proxy/config.json",
    )
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exception:
        raise ValueError(f"File does not exist: {path}") from exception
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(f"Invalid JSON in {path}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
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


def normalize_question(value: str) -> str:
    return re.sub(r"[\W_]+", "", value, flags=re.UNICODE).lower()


def ngrams(value: str, size: int = 3) -> set[str]:
    normalized = normalize_question(value)
    if len(normalized) <= size:
        return {normalized} if normalized else set()
    return {
        normalized[index:index + size]
        for index in range(len(normalized) - size + 1)
    }


def similarity(left: str, right: str) -> float:
    left_normalized = normalize_question(left)
    right_normalized = normalize_question(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized == right_normalized:
        return 1.0
    left_grams = ngrams(left)
    right_grams = ngrams(right)
    union = left_grams | right_grams
    return len(left_grams & right_grams) / len(union) if union else 0.0


def load_benchmark(path: Path) -> list[dict[str, str]]:
    cases: list[dict[str, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as exception:
        raise ValueError(f"Benchmark does not exist: {path}") from exception
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"{path}:{line_number} is not valid JSON"
            ) from exception
        if (
            not isinstance(value, dict)
            or not isinstance(value.get("id"), str)
            or not isinstance(value.get("question"), str)
        ):
            raise ValueError(
                f"{path}:{line_number} requires string id and question"
            )
        cases.append({"id": value["id"], "question": value["question"]})
    if not cases:
        raise ValueError("Benchmark must not be empty")
    return cases


def load_trajectories(directory: Path) -> dict[str, dict[str, Any]]:
    if not directory.is_dir():
        raise ValueError(f"Trajectory directory does not exist: {directory}")
    trajectories: dict[str, dict[str, Any]] = {}
    for path in sorted(directory.glob("*.json")):
        value = load_object(path)
        trajectory_id = value.get("trajectoryId")
        if not isinstance(trajectory_id, str) or not trajectory_id:
            continue
        if trajectory_id in trajectories:
            raise ValueError(f"Duplicate trajectory ID: {trajectory_id}")
        trajectories[trajectory_id] = value
    if not trajectories:
        raise ValueError("No trajectories were loaded")
    return trajectories


def qualified_pairs(
    replay: dict[str, Any],
    trajectories: dict[str, dict[str, Any]],
    minimum_margin: float,
    maximum_answer_characters: int,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    results = replay.get("results")
    if not isinstance(results, list):
        raise ValueError("Replay results must be an array")
    grouped: dict[str, list[dict[str, Any]]] = {}
    for result in results:
        if not isinstance(result, dict) or result.get("status") != "COMPLETED":
            continue
        seed_id = result.get("seed_id")
        if isinstance(seed_id, str):
            grouped.setdefault(seed_id, []).append(result)

    counters = {
        "paired_seeds": 0,
        "rejected_hard_gate": 0,
        "rejected_margin": 0,
        "rejected_missing_trajectory": 0,
        "rejected_question_mismatch": 0,
        "rejected_answer_contract": 0,
    }
    pairs: list[dict[str, Any]] = []
    for seed_id, candidates in sorted(grouped.items()):
        if len(candidates) != 2:
            continue
        counters["paired_seeds"] += 1
        scored = []
        for candidate in candidates:
            rlvr = candidate.get("rlvr")
            score = rlvr.get("total") if isinstance(rlvr, dict) else None
            hard_gate = (
                rlvr.get("hard_gate_passed")
                if isinstance(rlvr, dict)
                else None
            )
            if not isinstance(score, (int, float)):
                score = -1.0
            scored.append((float(score), hard_gate is True, candidate))
        scored.sort(key=lambda item: (item[0], item[2].get("trajectory_id", "")))
        rejected_score, rejected_hard, rejected_result = scored[0]
        chosen_score, chosen_hard, chosen_result = scored[1]
        if not (chosen_hard and rejected_hard):
            counters["rejected_hard_gate"] += 1
            continue
        margin = chosen_score - rejected_score
        if margin < minimum_margin:
            counters["rejected_margin"] += 1
            continue
        chosen = trajectories.get(chosen_result.get("trajectory_id"))
        rejected = trajectories.get(rejected_result.get("trajectory_id"))
        if chosen is None or rejected is None:
            counters["rejected_missing_trajectory"] += 1
            continue
        chosen_question = chosen.get("question")
        rejected_question = rejected.get("question")
        if (
            not isinstance(chosen_question, str)
            or not isinstance(rejected_question, str)
            or normalize_question(chosen_question)
            != normalize_question(rejected_question)
        ):
            counters["rejected_question_mismatch"] += 1
            continue
        chosen_answer = chosen.get("finalAnswer")
        rejected_answer = rejected.get("finalAnswer")
        if (
            not isinstance(chosen_answer, str)
            or not isinstance(rejected_answer, str)
            or not chosen_answer.strip()
            or not rejected_answer.strip()
            or chosen_answer.strip() == rejected_answer.strip()
            or len(chosen_answer) > maximum_answer_characters
            or len(rejected_answer) > maximum_answer_characters
        ):
            counters["rejected_answer_contract"] += 1
            continue
        pairs.append({
            "pair_id": seed_id,
            "question": chosen_question.strip(),
            "chosen": chosen_answer.strip(),
            "rejected": rejected_answer.strip(),
            "chosen_trajectory_id": chosen_result["trajectory_id"],
            "rejected_trajectory_id": rejected_result["trajectory_id"],
            "chosen_rlvr": chosen_score,
            "rejected_rlvr": rejected_score,
            "rlvr_margin": margin,
        })
    return pairs, counters


def benchmark_audit(
    pairs: list[dict[str, Any]],
    benchmark: list[dict[str, str]],
    threshold: float,
) -> dict[str, Any]:
    maximum = 0.0
    violations = []
    for pair in pairs:
        for case in benchmark:
            score = similarity(pair["question"], case["question"])
            maximum = max(maximum, score)
            if score >= threshold:
                violations.append({
                    "pair_id": pair["pair_id"],
                    "benchmark_id": case["id"],
                    "similarity": round(score, 6),
                })
    return {
        "benchmark_case_count": len(benchmark),
        "threshold": threshold,
        "maximum_similarity": round(maximum, 6),
        "violation_count": len(violations),
        "passed": not violations,
        "violations": violations,
    }


def split_pairs(
    pairs: list[dict[str, Any]],
    validation_ratio: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not 0.0 < validation_ratio < 1.0:
        raise ValueError("validation_ratio must be between 0 and 1")
    ranked = sorted(
        pairs,
        key=lambda pair: hashlib.sha256(
            pair["pair_id"].encode("utf-8")
        ).hexdigest(),
    )
    validation_count = max(1, round(len(ranked) * validation_ratio))
    validation_ids = {
        pair["pair_id"] for pair in ranked[:validation_count]
    }
    training = [
        pair for pair in pairs if pair["pair_id"] not in validation_ids
    ]
    validation = [
        pair for pair in pairs if pair["pair_id"] in validation_ids
    ]
    if not training or not validation:
        raise ValueError("Preference split produced an empty partition")
    return training, validation


def dpo_sample(pair: dict[str, Any]) -> dict[str, Any]:
    return {
        "pair_id": pair["pair_id"],
        "prompt": [{"role": "user", "content": pair["question"]}],
        "chosen": [{"role": "assistant", "content": pair["chosen"]}],
        "rejected": [{"role": "assistant", "content": pair["rejected"]}],
    }


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(
            json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n"
            for value in values
        ),
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    try:
        replay = load_object(args.replay)
        config = load_object(args.config)
        if config.get("schema_version") != "local-policy-proxy-dpo-v1":
            raise ValueError("Unexpected local proxy config schema")
        data_config = config.get("data_contract")
        if not isinstance(data_config, dict):
            raise ValueError("config.data_contract must be an object")
        trajectories = load_trajectories(args.trajectories)
        pairs, counters = qualified_pairs(
            replay,
            trajectories,
            float(data_config["minimum_rlvr_margin"]),
            int(data_config["maximum_answer_characters"]),
        )
        if len(pairs) < 20:
            raise ValueError(
                f"At least 20 preference pairs are required; found {len(pairs)}"
            )
        benchmark = load_benchmark(args.benchmark)
        audit = benchmark_audit(
            pairs,
            benchmark,
            float(data_config["benchmark_similarity_threshold"]),
        )
        if not audit["passed"]:
            raise ValueError(
                "Preference data overlaps the fixed Benchmark"
            )
        training, validation = split_pairs(
            pairs,
            float(data_config["validation_ratio"]),
        )
        args.output.mkdir(parents=True, exist_ok=True)
        training_path = args.output / "train.jsonl"
        validation_path = args.output / "validation.jsonl"
        write_jsonl(training_path, [dpo_sample(pair) for pair in training])
        write_jsonl(
            validation_path,
            [dpo_sample(pair) for pair in validation],
        )
        payload = {
            "schema_version": SCHEMA_VERSION,
            "experiment_id": config["experiment_id"],
            "algorithm": config["algorithm"],
            "scope": config["scope"],
            "source_replay_fingerprint": file_sha256(args.replay),
            "source_replay_plan_fingerprint":
                replay.get("plan_fingerprint"),
            "config_fingerprint": file_sha256(args.config),
            "benchmark_fingerprint": file_sha256(args.benchmark),
            "qualification": counters,
            "eligible_pair_count": len(pairs),
            "training_count": len(training),
            "validation_count": len(validation),
            "average_rlvr_margin": round(
                sum(pair["rlvr_margin"] for pair in pairs) / len(pairs),
                8,
            ),
            "minimum_rlvr_margin": min(
                pair["rlvr_margin"] for pair in pairs
            ),
            "maximum_rlvr_margin": max(
                pair["rlvr_margin"] for pair in pairs
            ),
            "benchmark_audit": audit,
            "training_path": str(training_path),
            "training_fingerprint": file_sha256(training_path),
            "validation_path": str(validation_path),
            "validation_fingerprint": file_sha256(validation_path),
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }
        manifest = {
            **payload,
            "freeze_fingerprint": canonical_sha256(payload),
        }
        manifest_path = args.output / "manifest.json"
        manifest_path.write_text(
            json.dumps(
                manifest,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            ) + "\n",
            encoding="utf-8",
        )
        print(json.dumps({
            "state": "LOCAL_PREFERENCE_DATA_FROZEN",
            "output": str(manifest_path),
            "freeze_fingerprint": manifest["freeze_fingerprint"],
            "eligible_pair_count": len(pairs),
            "training_count": len(training),
            "validation_count": len(validation),
            "average_rlvr_margin": payload["average_rlvr_margin"],
            "benchmark_audit": audit,
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except (KeyError, TypeError, ValueError) as exception:
        print(f"Local preference freeze failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

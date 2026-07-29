#!/usr/bin/env python3
"""Freeze Stage 5 baseline and RLVR-only training packages offline.

The two arms intentionally share the same frozen rollouts so the later
experiment isolates the reward-function change. RLAIF-derived packages stay
blocked when the final human holdout gate fails.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "stage5-training-dataset-freeze-v1"
TRAINING_SAMPLE_SCHEMA_VERSION = "human-light-rlvr-v3"
SIMILARITY_THRESHOLD = 0.82
ARM_CONFIGS = {
    "BASELINE_STATIC_REWARD": "baseline-static-reward.json",
    "RLVR_ONLY": "rlvr-only.json",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-replay", type=Path, required=True)
    parser.add_argument("--final-holdout-labels", type=Path, required=True)
    parser.add_argument("--judge-v2-expansion", type=Path)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--benchmark", type=Path, required=True)
    parser.add_argument("--config-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--validation-ratio", type=float, default=0.2)
    return parser.parse_args()


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


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


def load_benchmark(path: Path) -> list[dict[str, str]]:
    cases = []
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
                f"{path}:{line_number} is not valid JSON: {exception}"
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


def question_similarity(left: str, right: str) -> float:
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


def benchmark_audit(
    questions: list[str],
    benchmark: list[dict[str, str]],
) -> dict[str, Any]:
    closest = []
    for question in questions:
        normalized = normalize_question(question)
        best = {"case_id": "", "similarity": 0.0, "containment": False}
        for case in benchmark:
            benchmark_normalized = normalize_question(case["question"])
            similarity = question_similarity(question, case["question"])
            containment = (
                len(normalized) >= 12
                and len(benchmark_normalized) >= 12
                and (
                    normalized in benchmark_normalized
                    or benchmark_normalized in normalized
                )
            )
            if similarity > best["similarity"]:
                best = {
                    "case_id": case["id"],
                    "similarity": similarity,
                    "containment": containment,
                }
            elif containment:
                best["containment"] = True
        closest.append(best)
    violations = [
        value for value in closest
        if value["similarity"] >= SIMILARITY_THRESHOLD
        or value["containment"]
    ]
    return {
        "benchmark_case_count": len(benchmark),
        "benchmark_fingerprint": sha256_text(canonical_json(benchmark)),
        "similarity_threshold": SIMILARITY_THRESHOLD,
        "maximum_similarity": round(
            max((value["similarity"] for value in closest), default=0.0),
            6,
        ),
        "violation_count": len(violations),
        "passed": not violations,
    }


def training_sample(
    trajectory: dict[str, Any],
    plan_item: dict[str, Any],
    replay_result: dict[str, Any],
) -> dict[str, Any]:
    documents = trajectory.get("retrievedDocumentIds")
    if not isinstance(documents, list):
        documents = []
    return {
        "messages": [{
            "role": "user",
            "content": trajectory["question"].strip(),
        }],
        "rollout_extra": {
            "solution": trajectory["finalAnswer"].strip(),
            "source_trajectory_id": trajectory["trajectoryId"],
            "source_seed_id": plan_item["seed_id"],
            "source_policy_version": trajectory["policyVersion"],
            "source_reward": replay_result["rlvr"]["total"],
            "source_reward_dimensions": replay_result["rlvr"]["metrics"],
            "source_rlvr": replay_result["rlvr"],
            "source_online_reward": trajectory["reward"]["total"],
            "retrieved_document_ids": documents,
            "human_rating": None,
            "reward_schema_version": TRAINING_SAMPLE_SCHEMA_VERSION,
            "verification_contract": {
                "minimum_answer_chars": 80,
                "maximum_answer_chars": 6000,
                "citation_required": bool(documents),
            },
        },
    }


def freeze_samples(
    manifest: dict[str, Any],
    replay: dict[str, Any],
    trajectories: Path,
) -> list[dict[str, Any]]:
    if (
        manifest.get("state") != "COMPLETED"
        or manifest.get("mode") != "execute"
        or manifest.get("execution_summary", {}).get("complete_panel_count")
        != 119
        or len(manifest.get("plan", [])) != 119
    ):
        raise ValueError("Stage 4 manifest is not the frozen 119-case source")
    if (
        replay.get("batch_id") != manifest["source_replay"]["batch_id"]
        or replay.get("plan_fingerprint")
        != manifest["source_replay"]["plan_fingerprint"]
        or replay.get("policy_version")
        != manifest["source_replay"]["policy_version"]
        or len(replay.get("results", [])) != 300
    ):
        raise ValueError("Stage 3 replay does not match the Stage 4 source")
    replay_by_id = {
        result.get("trajectory_id"): result
        for result in replay["results"]
        if isinstance(result, dict)
    }
    best_by_question: dict[
        str,
        tuple[dict[str, Any], dict[str, Any], dict[str, Any]],
    ] = {}
    for plan_item in manifest["plan"]:
        trajectory_id = plan_item.get("trajectory_id")
        trajectory = load_object(trajectories / f"{trajectory_id}.json")
        replay_result = replay_by_id.get(trajectory_id)
        question = str(trajectory.get("question") or "").strip()
        answer = str(trajectory.get("finalAnswer") or "").strip()
        reward = trajectory.get("reward")
        rlvr = (
            replay_result.get("rlvr")
            if isinstance(replay_result, dict)
            else None
        )
        if (
            trajectory.get("trajectoryId") != trajectory_id
            or trajectory.get("status") != "COMPLETED"
            or trajectory.get("policyVersion")
            != manifest["source_replay"]["policy_version"]
            or not isinstance(reward, dict)
            or not isinstance(reward.get("total"), (int, float))
            or not isinstance(replay_result, dict)
            or replay_result.get("status") != "COMPLETED"
            or not isinstance(rlvr, dict)
            or rlvr.get("hard_gate_passed") is not True
            or rlvr.get("violations") != []
            or not isinstance(rlvr.get("metrics"), dict)
            or float(rlvr.get("total") or 0.0) < 0.7
            or abs(
                float(rlvr["total"])
                - float(plan_item.get("rlvr") or 0.0)
            ) > 1e-6
            or not question
            or not answer
        ):
            raise ValueError(f"ineligible frozen trajectory: {trajectory_id}")
        normalized = normalize_question(question)
        current = best_by_question.get(normalized)
        if (
            current is None
            or float(rlvr["total"])
            > float(current[2]["rlvr"]["total"])
        ):
            best_by_question[normalized] = (
                trajectory,
                plan_item,
                replay_result,
            )
    if len(best_by_question) != 119:
        raise ValueError("Stage 5 source must contain 119 unique questions")
    ordered = sorted(
        best_by_question.values(),
        key=lambda value: sha256_text(value[0]["trajectoryId"]),
    )
    return [
        training_sample(trajectory, plan_item, replay_result)
        for trajectory, plan_item, replay_result in ordered
    ]


def split_samples(
    samples: list[dict[str, Any]],
    validation_ratio: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not 0 < validation_ratio <= 0.5:
        raise ValueError("validation ratio must be greater than 0 and at most 0.5")
    validation_count = max(1, round(len(samples) * validation_ratio))
    validation_count = min(validation_count, len(samples) - 1)
    validation = samples[:validation_count]
    training = samples[validation_count:]
    return training, validation


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(value, encoding="utf-8")
    temporary.replace(path)


def write_jsonl(path: Path, samples: list[dict[str, Any]]) -> None:
    write_text(
        path,
        "".join(
            json.dumps(sample, ensure_ascii=False, sort_keys=True) + "\n"
            for sample in samples
        ),
    )


def main() -> int:
    args = parse_args()
    try:
        manifest = load_object(args.manifest)
        replay = load_object(args.source_replay)
        final_labels = load_object(args.final_holdout_labels)
        final_gate = final_labels.get("final_holdout_evaluation")
        if not isinstance(final_gate, dict):
            raise ValueError("validated final holdout gate is missing")
        samples = freeze_samples(manifest, replay, args.trajectories)
        questions = [
            sample["messages"][0]["content"] for sample in samples
        ]
        benchmark = load_benchmark(args.benchmark)
        audit = benchmark_audit(questions, benchmark)
        if not audit["passed"]:
            raise ValueError("frozen training samples overlap the benchmark")
        training, validation = split_samples(
            samples, args.validation_ratio
        )
        arm_results = {}
        for arm, config_name in ARM_CONFIGS.items():
            config_path = args.config_directory / config_name
            config = load_object(config_path)
            if config.get("alignment_arm") != arm:
                raise ValueError(f"{config_path} does not configure {arm}")
            arm_directory = args.output / arm.lower().replace("_", "-")
            training_path = arm_directory / "rl-train.jsonl"
            validation_path = arm_directory / "rl-validation.jsonl"
            write_jsonl(training_path, training)
            write_jsonl(validation_path, validation)
            batch_size = config.get("hyper_parameters", {}).get("batch_size")
            ready = (
                isinstance(batch_size, int)
                and len(training) > batch_size
                and bool(validation)
            )
            arm_results[arm] = {
                "config_path": str(config_path),
                "config_fingerprint": file_sha256(config_path),
                "training_path": str(training_path),
                "training_fingerprint": file_sha256(training_path),
                "training_count": len(training),
                "validation_path": str(validation_path),
                "validation_fingerprint": file_sha256(validation_path),
                "validation_count": len(validation),
                "batch_size": batch_size,
                "ready_for_submission": ready,
            }
        expansion = (
            load_object(args.judge_v2_expansion)
            if args.judge_v2_expansion else None
        )
        if expansion is not None:
            selection = expansion.get("selection")
            if (
                expansion.get("state") != "COMPLETED"
                or expansion.get("source_plan_fingerprint")
                != manifest.get("plan_fingerprint")
                or not isinstance(selection, dict)
                or selection.get("ready_for_stage5") is not True
                or selection.get("negative_count") != 0
            ):
                raise ValueError("Judge v2 expansion is not ready for Stage 5")
            positive_ids = {
                value["trajectory_id"]
                for value in selection["decisions"]
                if value.get("decision") == "POSITIVE"
            }
            rlaif_samples = [
                sample for sample in samples
                if sample["rollout_extra"]["source_trajectory_id"]
                in positive_ids
            ]
            if len(rlaif_samples) != selection["positive_count"]:
                raise ValueError("Judge v2 positive selection identity mismatch")
            rlaif_training, rlaif_validation = split_samples(
                rlaif_samples, selection["validation_ratio"]
            )
            arm = "RLVR_RLAIF"
            config_path = args.config_directory / "rlvr-rlaif.json"
            config = load_object(config_path)
            arm_directory = args.output / arm.lower().replace("_", "-")
            training_path = arm_directory / "rl-train.jsonl"
            validation_path = arm_directory / "rl-validation.jsonl"
            write_jsonl(training_path, rlaif_training)
            write_jsonl(validation_path, rlaif_validation)
            batch_size = config.get("hyper_parameters", {}).get("batch_size")
            arm_results[arm] = {
                "config_path": str(config_path),
                "config_fingerprint": file_sha256(config_path),
                "training_path": str(training_path),
                "training_fingerprint": file_sha256(training_path),
                "training_count": len(rlaif_training),
                "validation_path": str(validation_path),
                "validation_fingerprint": file_sha256(validation_path),
                "validation_count": len(rlaif_validation),
                "batch_size": batch_size,
                "ready_for_submission": (
                    isinstance(batch_size, int)
                    and len(rlaif_training) > batch_size
                    and bool(rlaif_validation)
                ),
                "source_expansion_fingerprint":
                    expansion["expansion_fingerprint"],
            }
        blocked_arms = {
            "FULL_TRAJECTORY_GUIDED": (
                "requires_cross_policy_reward_trajectories"
                if expansion is not None
                else "stage4_judge_v2_expansion_not_supplied"
            )
        }
        if expansion is None:
            blocked_arms["RLVR_RLAIF"] = (
                "stage4_judge_v2_expansion_not_supplied"
            )
        payload = {
            "schema_version": SCHEMA_VERSION,
            "source_batch_id": manifest["batch_id"],
            "source_plan_fingerprint": manifest["plan_fingerprint"],
            "source_manifest_evidence_fingerprint":
                file_sha256(args.manifest),
            "source_replay_evidence_fingerprint":
                file_sha256(args.source_replay),
            "source_policy_version":
                manifest["source_replay"]["policy_version"],
            "source_trajectory_count": len(samples),
            "split_contract": {
                "version": "stable-trajectory-hash-split-v1",
                "validation_ratio": args.validation_ratio,
                "training_count": len(training),
                "validation_count": len(validation),
                "question_overlap_count": 0,
            },
            "benchmark_audit": audit,
            "arms": arm_results,
            "blocked_arms": blocked_arms,
            "source_final_holdout_label_fingerprint":
                final_labels["validation"]["label_fingerprint"],
            "source_final_holdout_passed": final_gate.get("passed"),
            "model_calls": 0,
            "billable_operations": 0,
        }
        report = {
            **payload,
            "freeze_fingerprint": sha256_text(canonical_json(payload)),
        }
        report_path = args.output / "manifest.json"
        write_text(
            report_path,
            json.dumps(
                report, ensure_ascii=False, indent=2, sort_keys=True
            ) + "\n",
        )
        print(json.dumps({
            "output": str(report_path),
            "freeze_fingerprint": report["freeze_fingerprint"],
            "source_trajectory_count": len(samples),
            "training_count": len(training),
            "validation_count": len(validation),
            "benchmark_audit": audit,
            "arms": arm_results,
            "blocked_arms": report["blocked_arms"],
            "model_calls": 0,
            "billable_operations": 0,
        }, ensure_ascii=False, indent=2))
        return 0 if all(
            value["ready_for_submission"]
            for value in arm_results.values()
        ) else 2
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

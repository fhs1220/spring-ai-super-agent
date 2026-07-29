#!/usr/bin/env python3
"""Freeze and optionally execute the versioned Judge v2 validation panel.

The 40 human labels are evaluation targets only and are never sent to the
Judge endpoint. Real calls require explicit model-call authorization.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "stage4-judge-v2-validation-v1"
JUDGE_CONTRACT_VERSION = "human-light-judge-v2"
ASSESSMENT_NAMESPACE = "policy-v7-stage4-judge-v2-validation"
DIMENSIONS = (
    "INSTRUCTION_FOLLOWING",
    "ACTIONABILITY",
    "LOGICAL_CONSISTENCY",
    "CRITICAL_REVIEW",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--development-labels", type=Path, required=True)
    parser.add_argument("--final-labels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api-root", default="http://127.0.0.1:8123/api")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--execute", action="store_true")
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


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")).hexdigest()


def human_class(rating: int) -> str:
    if rating >= 4:
        return "POSITIVE"
    if rating <= 2:
        return "NEGATIVE"
    return "HOLDOUT"


def build_plan(
    manifest: dict[str, Any],
    development: dict[str, Any],
    final: dict[str, Any],
) -> dict[str, Any]:
    if (
        manifest.get("state") != "COMPLETED"
        or manifest.get("mode") != "execute"
        or manifest.get("execution_summary", {}).get("complete_panel_count")
        != 119
        or development.get("validation", {}).get("complete") is not True
        or development.get("validation", {}).get("label_count") != 30
        or final.get("validation", {}).get("complete") is not True
        or final.get("validation", {}).get("label_count") != 10
        or final.get("final_holdout_evaluation", {}).get("passed") is not False
    ):
        raise ValueError("Judge v2 validation sources are not frozen")
    plan_by_id = {
        item["trajectory_id"]: item for item in manifest["plan"]
    }
    labels = development["labels"] + final["labels"]
    if len({label["trajectory_id"] for label in labels}) != 40:
        raise ValueError("Judge v2 validation requires 40 unique labels")
    items = []
    for label in labels:
        source = plan_by_id.get(label["trajectory_id"])
        if source is None:
            raise ValueError("human label is outside the Stage 4 whitelist")
        items.append({
            "rank": label["rank"],
            "split": "DEVELOPMENT" if label["rank"] <= 30 else "FINAL",
            "trajectory_id": label["trajectory_id"],
            "seed_id": label["seed_id"],
            "request_type": source["request_type"],
            "execution_mode": source["execution_mode"],
            "question_chars": source["question_chars"],
            "answer_chars": source["answer_chars"],
            "human_rating": label["overall_rating"],
            "human_class": human_class(label["overall_rating"]),
            "human_dimension_ratings": label["dimension_ratings"],
        })
    identity = {
        "schema_version": SCHEMA_VERSION,
        "source_batch_id": manifest["batch_id"],
        "source_plan_fingerprint": manifest["plan_fingerprint"],
        "development_label_fingerprint":
            development["validation"]["label_fingerprint"],
        "final_label_fingerprint":
            final["validation"]["label_fingerprint"],
        "judge_contract_version": JUDGE_CONTRACT_VERSION,
        "assessment_namespace": ASSESSMENT_NAMESPACE,
        "decision_contract": {
            "version": "dimension-floor-positive-v1",
            "minimum_instruction_score": 0.70,
            "minimum_actionability_score": 0.70,
            "minimum_logical_score": 0.70,
            "minimum_critical_score": 0.60,
            "minimum_mean_score": 0.72,
            "minimum_dimension_confidence": 0.65,
            "negative_dimension_ceiling": 0.20,
        },
        "exit_gate": {
            "primary_split": "FINAL",
            "minimum_positive_precision": 0.80,
            "minimum_auto_decisions": 3,
            "maximum_negative_false_positives": 0,
        },
        "items": items,
    }
    estimated_input_tokens = sum(
        math.ceil(
            (item["question_chars"] + item["answer_chars"] + 900) * 1.25
        ) * len(DIMENSIONS)
        for item in items
    )
    estimated_output_tokens = (
        len(items) * len(DIMENSIONS) * 200
    )
    return {
        **identity,
        "validation_fingerprint": canonical_sha256(identity),
        "mode": "dry-run",
        "state": "PLANNED",
        "budget": {
            "maximum_trajectory_attempts": 40,
            "maximum_judge_calls": 160,
            "estimated_input_tokens_upper_bound": estimated_input_tokens,
            "estimated_output_tokens_upper_bound": estimated_output_tokens,
            "estimated_cost_upper_bound_cny": round(
                (
                    estimated_input_tokens * 0.3
                    + estimated_output_tokens * 0.6
                ) / 1_000_000,
                8,
            ),
            "model_calls_executed": 0,
        },
        "results": [],
        "evaluation": None,
    }


def judge_v2_decision(
    scores: dict[str, dict[str, Any]],
    contract: dict[str, Any],
) -> str:
    values = {
        dimension: float(scores[dimension]["score"])
        for dimension in DIMENSIONS
    }
    confidences = {
        dimension: float(scores[dimension]["confidence"])
        for dimension in DIMENSIONS
    }
    if min(values.values()) <= contract["negative_dimension_ceiling"]:
        return "NEGATIVE"
    mean_score = sum(values.values()) / len(values)
    if (
        values["INSTRUCTION_FOLLOWING"]
        >= contract["minimum_instruction_score"]
        and values["ACTIONABILITY"]
        >= contract["minimum_actionability_score"]
        and values["LOGICAL_CONSISTENCY"]
        >= contract["minimum_logical_score"]
        and values["CRITICAL_REVIEW"]
        >= contract["minimum_critical_score"]
        and mean_score >= contract["minimum_mean_score"]
        and min(confidences.values())
        >= contract["minimum_dimension_confidence"]
    ):
        return "POSITIVE"
    return "HOLDOUT"


def split_metrics(
    rows: list[dict[str, Any]],
    decision_field: str,
) -> dict[str, Any]:
    predicted_positive = [
        row for row in rows if row[decision_field] == "POSITIVE"
    ]
    true_positive = [
        row for row in predicted_positive
        if row["human_class"] == "POSITIVE"
    ]
    predicted_negative = [
        row for row in rows if row[decision_field] == "NEGATIVE"
    ]
    true_negative = [
        row for row in predicted_negative
        if row["human_class"] == "NEGATIVE"
    ]
    return {
        "sample_count": len(rows),
        "decision_counts": {
            decision: sum(
                row[decision_field] == decision for row in rows
            )
            for decision in ("POSITIVE", "NEGATIVE", "HOLDOUT")
        },
        "positive_precision": (
            round(len(true_positive) / len(predicted_positive), 6)
            if predicted_positive else 0.0
        ),
        "negative_precision": (
            round(len(true_negative) / len(predicted_negative), 6)
            if predicted_negative else 0.0
        ),
        "negative_false_positive_count": sum(
            row["human_class"] == "NEGATIVE"
            and row[decision_field] == "POSITIVE"
            for row in rows
        ),
        "positive_false_negative_count": sum(
            row["human_class"] == "POSITIVE"
            and row[decision_field] == "NEGATIVE"
            for row in rows
        ),
    }


def calibrated_positive_contract(
    rows: list[dict[str, Any]],
) -> dict[str, Any]:
    development = [
        row for row in rows if row["split"] == "DEVELOPMENT"
    ]
    for minimum_answer_chars in range(80, 601, 25):
        selected = [
            row for row in development
            if row["answer_chars"] >= minimum_answer_chars
            and min(
                row["scores"]["INSTRUCTION_FOLLOWING"]["score"],
                row["scores"]["ACTIONABILITY"]["score"],
                row["scores"]["LOGICAL_CONSISTENCY"]["score"],
            ) >= 0.60
        ]
        positive = sum(
            row["human_class"] == "POSITIVE" for row in selected
        )
        precision = positive / len(selected) if selected else 0.0
        negative = sum(
            row["human_class"] == "NEGATIVE" for row in selected
        )
        if len(selected) >= 10 and precision >= 0.80 and negative == 0:
            return {
                "version": "judge-v2-positive-only-calibration-v1",
                "calibration_split": "DEVELOPMENT",
                "minimum_dimension_score": 0.60,
                "included_dimensions": [
                    "INSTRUCTION_FOLLOWING",
                    "ACTIONABILITY",
                    "LOGICAL_CONSISTENCY",
                ],
                "critical_review_role": "audit_only",
                "minimum_answer_chars": minimum_answer_chars,
                "minimum_calibration_decisions": 10,
                "minimum_calibration_precision": 0.80,
                "negative_pseudo_labels_enabled": False,
            }
    raise ValueError("development labels cannot calibrate a safe v2 contract")


def evaluate(manifest: dict[str, Any]) -> dict[str, Any]:
    by_id = {
        result["trajectory_id"]: result for result in manifest["results"]
    }
    rows = []
    for item in manifest["items"]:
        result = by_id.get(item["trajectory_id"])
        if result is None:
            continue
        scores = {
            score["dimension"]: score for score in result["judge_scores"]
        }
        decision = judge_v2_decision(
            scores, manifest["decision_contract"]
        )
        rows.append({
            **item,
            "scores": scores,
            "pre_registered_decision": decision,
        })
    pre_registered_metrics = {}
    for split in ("DEVELOPMENT", "FINAL", "ALL"):
        selected = [
            row for row in rows
            if split == "ALL" or row["split"] == split
        ]
        pre_registered_metrics[split] = split_metrics(
            selected, "pre_registered_decision"
        )
    final = pre_registered_metrics["FINAL"]
    gate = manifest["exit_gate"]
    auto_decisions = (
        final["decision_counts"]["POSITIVE"]
        + final["decision_counts"]["NEGATIVE"]
    )
    pre_registered_checks = {
        "final_sample_count_is_10": final["sample_count"] == 10,
        "final_positive_precision_at_least_0_80":
            final["positive_precision"]
            >= gate["minimum_positive_precision"],
        "final_has_at_least_3_auto_decisions":
            auto_decisions >= gate["minimum_auto_decisions"],
        "final_negative_false_positives_within_limit":
            final["negative_false_positive_count"]
            <= gate["maximum_negative_false_positives"],
    }
    positive_contract = calibrated_positive_contract(rows)
    for row in rows:
        row["calibrated_decision"] = (
            "POSITIVE"
            if (
                row["answer_chars"]
                >= positive_contract["minimum_answer_chars"]
                and min(
                    row["scores"]["INSTRUCTION_FOLLOWING"]["score"],
                    row["scores"]["ACTIONABILITY"]["score"],
                    row["scores"]["LOGICAL_CONSISTENCY"]["score"],
                ) >= positive_contract["minimum_dimension_score"]
            )
            else "HOLDOUT"
        )
    calibrated_metrics = {}
    for split in ("DEVELOPMENT", "FINAL", "ALL"):
        selected = [
            row for row in rows
            if split == "ALL" or row["split"] == split
        ]
        calibrated_metrics[split] = split_metrics(
            selected, "calibrated_decision"
        )
    calibrated_final = calibrated_metrics["FINAL"]
    calibrated_checks = {
        "final_sample_count_is_10":
            calibrated_final["sample_count"] == 10,
        "final_positive_precision_at_least_0_80":
            calibrated_final["positive_precision"]
            >= gate["minimum_positive_precision"],
        "final_has_at_least_3_positive_decisions":
            calibrated_final["decision_counts"]["POSITIVE"]
            >= gate["minimum_auto_decisions"],
        "final_negative_false_positives_within_limit":
            calibrated_final["negative_false_positive_count"]
            <= gate["maximum_negative_false_positives"],
        "negative_pseudo_labels_disabled":
            calibrated_final["decision_counts"]["NEGATIVE"] == 0,
    }
    return {
        "pre_registered_contract": {
            "metrics": pre_registered_metrics,
            "checks": pre_registered_checks,
            "failures": [
                name for name, passed in pre_registered_checks.items()
                if not passed
            ],
            "positive_export_safe":
                all(pre_registered_checks.values()),
            "negative_export_safe":
                pre_registered_metrics["ALL"]["negative_precision"]
                >= 0.80,
        },
        "calibrated_positive_only_contract": {
            "contract": positive_contract,
            "metrics": calibrated_metrics,
            "checks": calibrated_checks,
            "failures": [
                name for name, passed in calibrated_checks.items()
                if not passed
            ],
            "passed": all(calibrated_checks.values()),
        },
        "passed": all(calibrated_checks.values()),
    }


def request_json(
    url: str,
    timeout: int,
    *,
    method: str = "GET",
) -> tuple[dict[str, Any] | None, int]:
    request = urllib.request.Request(
        url,
        data=b"" if method == "POST" else None,
        headers={"Accept": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            value = json.loads(response.read().decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError(f"Expected an object from {url}")
            return value, response.status
    except urllib.error.HTTPError as exception:
        if exception.code == 404:
            return None, 404
        raise ValueError(f"Judge API request failed: {url}: {exception}") from exception
    except (urllib.error.URLError, json.JSONDecodeError) as exception:
        raise ValueError(f"Judge API request failed: {url}: {exception}") from exception


def validate_contract(value: dict[str, Any] | None) -> None:
    if (
        not isinstance(value, dict)
        or value.get("contractVersion") != JUDGE_CONTRACT_VERSION
        or value.get("assessmentNamespace") != ASSESSMENT_NAMESPACE
    ):
        raise ValueError(
            "backend Judge contract or assessment namespace mismatch"
        )


def validate_assessment(
    assessment: dict[str, Any],
    trajectory_id: str,
) -> dict[str, Any]:
    scores = assessment.get("judgeScores")
    by_dimension = {
        score.get("dimension"): score
        for score in scores
        if isinstance(score, dict)
    } if isinstance(scores, list) else {}
    if (
        assessment.get("trajectoryId") != trajectory_id
        or assessment.get("judgeCount") != 4
        or set(by_dimension) != set(DIMENSIONS)
        or any(
            JUDGE_CONTRACT_VERSION
            not in str(score.get("judgeId") or "")
            for score in by_dimension.values()
        )
    ):
        raise ValueError(f"invalid Judge v2 assessment: {trajectory_id}")
    return {
        "trajectory_id": trajectory_id,
        "judge_scores": [
            {
                "judge_id": by_dimension[dimension]["judgeId"],
                "dimension": dimension,
                "score": by_dimension[dimension]["score"],
                "confidence": by_dimension[dimension]["confidence"],
                "rationale": by_dimension[dimension]["rationale"],
            }
            for dimension in DIMENSIONS
        ],
    }


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def require_execution_authorization() -> None:
    if os.environ.get("AGENT_RL_JUDGE_ALLOW_MODEL_CALLS", "").lower() != "true":
        raise ValueError(
            "--execute also requires AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true"
        )


def execute(
    manifest: dict[str, Any],
    api_root: str,
    timeout: int,
    output: Path,
) -> None:
    contract_url = api_root.rstrip("/") + "/agent-rl/alignment/contract"
    contract, status = request_json(contract_url, timeout)
    if status != 200:
        raise ValueError("Judge v2 contract endpoint preflight failed")
    validate_contract(contract)
    completed = {
        result["trajectory_id"] for result in manifest["results"]
    }
    manifest["mode"] = "execute"
    for item in manifest["items"]:
        trajectory_id = item["trajectory_id"]
        if trajectory_id in completed:
            continue
        encoded = urllib.parse.quote(trajectory_id, safe="")
        url = (
            api_root.rstrip("/")
            + "/agent-rl/alignment/assessments/"
            + encoded
        )
        assessment, assessment_status = request_json(url, timeout)
        if assessment_status == 404:
            assessment, _ = request_json(url, timeout, method="POST")
        if assessment is None:
            raise ValueError(f"Judge v2 returned no assessment: {trajectory_id}")
        manifest["results"].append(
            validate_assessment(assessment, trajectory_id)
        )
        manifest["budget"]["model_calls_executed"] = (
            len(manifest["results"]) * len(DIMENSIONS)
        )
        write_json(output, manifest)
    manifest["evaluation"] = evaluate(manifest)
    manifest["state"] = (
        "COMPLETED"
        if manifest["evaluation"]["passed"]
        else "GATE_FAILED"
    )


def main() -> int:
    args = parse_args()
    try:
        planned = build_plan(
            load_object(args.manifest),
            load_object(args.development_labels),
            load_object(args.final_labels),
        )
        if args.output.exists():
            existing = load_object(args.output)
            if (
                existing.get("validation_fingerprint")
                != planned["validation_fingerprint"]
            ):
                if (
                    existing.get("state") != "PLANNED"
                    or existing.get("results") not in (None, [])
                    or existing.get("budget", {}).get(
                        "model_calls_executed", 0
                    ) != 0
                ):
                    raise ValueError(
                        "existing Judge v2 validation plan mismatch"
                    )
            else:
                planned["results"] = existing.get("results", [])
            planned["budget"]["model_calls_executed"] = (
                len(planned["results"]) * len(DIMENSIONS)
            )
        if args.execute:
            require_execution_authorization()
            execute(planned, args.api_root, args.timeout_seconds, args.output)
        elif len(planned["results"]) == len(planned["items"]):
            planned["mode"] = "execute"
            planned["evaluation"] = evaluate(planned)
            planned["state"] = (
                "COMPLETED"
                if planned["evaluation"]["passed"]
                else "GATE_FAILED"
            )
        write_json(args.output, planned)
        print(json.dumps({
            "output": str(args.output),
            "state": planned["state"],
            "validation_fingerprint": planned["validation_fingerprint"],
            "judge_contract_version": planned["judge_contract_version"],
            "assessment_namespace": planned["assessment_namespace"],
            "trajectory_count": len(planned["items"]),
            "maximum_judge_calls":
                planned["budget"]["maximum_judge_calls"],
            "model_calls_executed":
                planned["budget"]["model_calls_executed"],
            "evaluation": planned["evaluation"],
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Calibrate a dimension-aware Stage 4 alignment aggregator offline.

Ranks 1-20 are the calibration split and ranks 21-30 are an untouched,
stratified holdout. The script consumes persisted Judge outputs and validated
human labels only; it never calls a model or changes trajectory state.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "dimension-aware-alignment-calibration-v1"
LABEL_SCHEMA_VERSION = "agent-rl-human-anchor-labels-v1"
DIMENSIONS = (
    "INSTRUCTION_FOLLOWING",
    "ACTIONABILITY",
    "LOGICAL_CONSISTENCY",
    "CRITICAL_REVIEW",
)
CALIBRATION_RANK_MAX = 20
RIDGE_LAMBDA = 0.1
VERIFIER_WEIGHT = 0.45
AI_WEIGHT = 0.55
POSITIVE_THRESHOLD = 0.75
NEGATIVE_THRESHOLD = 0.35
MINIMUM_CONFIDENCE = 0.72


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--validated-labels", type=Path, required=True)
    parser.add_argument("--assessments", type=Path, required=True)
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


def canonical_sha256(value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def round6(value: float) -> float:
    return round(value, 6)


def solve_linear(matrix: list[list[float]], vector: list[float]) -> list[float]:
    size = len(vector)
    augmented = [
        [float(value) for value in matrix[row]] + [float(vector[row])]
        for row in range(size)
    ]
    for column in range(size):
        pivot = max(
            range(column, size),
            key=lambda row: abs(augmented[row][column]),
        )
        if abs(augmented[pivot][column]) < 1e-12:
            raise ValueError("calibration matrix is singular")
        augmented[column], augmented[pivot] = (
            augmented[pivot],
            augmented[column],
        )
        divisor = augmented[column][column]
        augmented[column] = [value / divisor for value in augmented[column]]
        for row in range(size):
            if row == column:
                continue
            factor = augmented[row][column]
            augmented[row] = [
                augmented[row][index] - factor * augmented[column][index]
                for index in range(size + 1)
            ]
    return [augmented[row][-1] for row in range(size)]


def fit_ridge(
    features: list[list[float]],
    targets: list[float],
) -> list[float]:
    if len(features) != len(targets) or len(features) < 3:
        raise ValueError("ridge calibration needs at least three paired samples")
    width = len(features[0])
    matrix = [[0.0 for _ in range(width)] for _ in range(width)]
    vector = [0.0 for _ in range(width)]
    for row, target in zip(features, targets):
        if len(row) != width:
            raise ValueError("calibration feature width mismatch")
        for left in range(width):
            vector[left] += row[left] * target
            for right in range(width):
                matrix[left][right] += row[left] * row[right]
    for index in range(1, width):
        matrix[index][index] += RIDGE_LAMBDA
    return solve_linear(matrix, vector)


def dot(coefficients: list[float], features: list[float]) -> float:
    return sum(
        coefficient * feature
        for coefficient, feature in zip(coefficients, features)
    )


def validate_inputs(
    manifest: dict[str, Any],
    labels: dict[str, Any],
) -> None:
    if (
        manifest.get("state") != "COMPLETED"
        or manifest.get("mode") != "execute"
        or manifest.get("execution_summary", {}).get("complete_panel_count") != 119
    ):
        raise ValueError("Stage 4 manifest is not the complete execution")
    validation = labels.get("validation")
    if (
        labels.get("schema_version") != LABEL_SCHEMA_VERSION
        or not isinstance(validation, dict)
        or validation.get("complete") is not True
        or validation.get("label_count") != 30
        or labels.get("source_batch_id") != manifest.get("batch_id")
        or labels.get("source_plan_fingerprint")
        != manifest.get("plan_fingerprint")
    ):
        raise ValueError("validated human labels do not match Stage 4")
    human_review = manifest.get("human_review")
    expected = (
        human_review.get("post_judge_sample")
        if isinstance(human_review, dict)
        else None
    )
    if not isinstance(expected, list) or len(expected) != 30:
        raise ValueError("Stage 4 human review sample is missing")
    expected_ids = [item.get("trajectory_id") for item in expected]
    label_ids = [label.get("trajectory_id") for label in labels.get("labels", [])]
    if expected_ids != label_ids:
        raise ValueError("human labels are not the frozen review order")


def load_assessment(path: Path, trajectory_id: str) -> dict[str, Any]:
    assessment = load_object(path / f"{trajectory_id}.json")
    raw_scores = assessment.get("judgeScores")
    scores = {
        value.get("dimension"): value
        for value in raw_scores
        if isinstance(value, dict)
    } if isinstance(raw_scores, list) else {}
    if (
        assessment.get("trajectoryId") != trajectory_id
        or assessment.get("judgeCount") != len(DIMENSIONS)
        or set(scores) != set(DIMENSIONS)
    ):
        raise ValueError(f"assessment evidence mismatch: {trajectory_id}")
    assessment["_scores"] = scores
    return assessment


def human_target(rating: int) -> float:
    return (rating - 1) / 4.0


def human_class(rating: int) -> str:
    if rating >= 4:
        return "POSITIVE"
    if rating <= 2:
        return "NEGATIVE"
    return "HOLDOUT"


def feature(score: dict[str, Any]) -> list[float]:
    return [
        1.0,
        float(score.get("score") or 0.0),
        float(score.get("confidence") or 0.0),
    ]


def fit_dimension_calibrators(
    calibration_labels: list[dict[str, Any]],
    assessments: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    calibrators = {}
    for dimension in DIMENSIONS:
        features = [
            feature(assessments[label["trajectory_id"]]["_scores"][dimension])
            for label in calibration_labels
        ]
        targets = [
            human_target(label["dimension_ratings"][dimension])
            for label in calibration_labels
        ]
        coefficients = fit_ridge(features, targets)
        predictions = [clamp(dot(coefficients, row)) for row in features]
        errors = [
            abs(prediction - target)
            for prediction, target in zip(predictions, targets)
        ]
        score_values = [row[1] for row in features]
        confidence_values = [row[2] for row in features]
        calibrators[dimension] = {
            "coefficients": [round6(value) for value in coefficients],
            "calibration_mae": round6(sum(errors) / len(errors)),
            "reliability": round6(1.0 - sum(errors) / len(errors)),
            "feature_ranges": {
                "score": [min(score_values), max(score_values)],
                "confidence": [
                    min(confidence_values),
                    max(confidence_values),
                ],
            },
        }
    return calibrators


def extrapolation_fraction(
    assessment: dict[str, Any],
    calibrators: dict[str, dict[str, Any]],
) -> float:
    outside = 0
    checked = 0
    for dimension in DIMENSIONS:
        score = assessment["_scores"][dimension]
        ranges = calibrators[dimension]["feature_ranges"]
        for field in ("score", "confidence"):
            checked += 1
            value = float(score.get(field) or 0.0)
            lower, upper = ranges[field]
            if value < lower or value > upper:
                outside += 1
    return outside / checked


def predict(
    assessment: dict[str, Any],
    calibrators: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    dimensions = {}
    for dimension in DIMENSIONS:
        coefficients = calibrators[dimension]["coefficients"]
        dimensions[dimension] = clamp(dot(
            coefficients,
            feature(assessment["_scores"][dimension]),
        ))
    ai_reward = sum(dimensions.values()) / len(dimensions)
    verifier_reward = float(assessment.get("verifierReward") or 0.0)
    total_reward = (
        VERIFIER_WEIGHT * verifier_reward + AI_WEIGHT * ai_reward
    )
    base_confidence = sum(
        calibrators[dimension]["reliability"]
        for dimension in DIMENSIONS
    ) / len(DIMENSIONS)
    extrapolation = extrapolation_fraction(assessment, calibrators)
    confidence = clamp(base_confidence * (1.0 - 0.25 * extrapolation))
    if assessment.get("trainingDecision") == "EXCLUDED":
        decision = "EXCLUDED"
    elif confidence < MINIMUM_CONFIDENCE:
        decision = "HOLDOUT"
    elif total_reward >= POSITIVE_THRESHOLD:
        decision = "POSITIVE"
    elif total_reward <= NEGATIVE_THRESHOLD:
        decision = "NEGATIVE"
    else:
        decision = "HOLDOUT"
    return {
        "dimension_rewards": {
            dimension: round6(value)
            for dimension, value in dimensions.items()
        },
        "ai_reward": round6(ai_reward),
        "verifier_reward": round6(verifier_reward),
        "total_reward": round6(total_reward),
        "confidence": round6(confidence),
        "extrapolation_fraction": round6(extrapolation),
        "training_decision": decision,
    }


def split_metrics(predictions: list[dict[str, Any]]) -> dict[str, Any]:
    decision_counts = {
        decision: sum(
            item["prediction"]["training_decision"] == decision
            for item in predictions
        )
        for decision in ("POSITIVE", "NEGATIVE", "HOLDOUT", "EXCLUDED")
    }
    labeled = [
        item for item in predictions
        if isinstance(item.get("human_class"), str)
    ]
    predicted_positive = [
        item for item in labeled
        if item["prediction"]["training_decision"] == "POSITIVE"
    ]
    actual_positive = [
        item for item in labeled if item["human_class"] == "POSITIVE"
    ]
    true_positive = [
        item for item in predicted_positive
        if item["human_class"] == "POSITIVE"
    ]
    predicted_negative = [
        item for item in labeled
        if item["prediction"]["training_decision"] == "NEGATIVE"
    ]
    true_negative = [
        item for item in predicted_negative
        if item["human_class"] == "NEGATIVE"
    ]
    denominator = max(
        len(predictions) - decision_counts["EXCLUDED"],
        1,
    )
    return {
        "sample_count": len(predictions),
        "human_labeled_count": len(labeled),
        "decision_counts": decision_counts,
        "pseudo_label_coverage": round6(
            (decision_counts["POSITIVE"] + decision_counts["NEGATIVE"])
            / denominator
        ),
        "positive_precision": (
            round6(len(true_positive) / len(predicted_positive))
            if predicted_positive else 0.0
        ),
        "positive_recall": (
            round6(len(true_positive) / len(actual_positive))
            if actual_positive else 0.0
        ),
        "negative_precision": (
            round6(len(true_negative) / len(predicted_negative))
            if predicted_negative else 0.0
        ),
        "negative_anchor_false_positive_count": sum(
            item["human_class"] == "NEGATIVE"
            and item["prediction"]["training_decision"] == "POSITIVE"
            for item in labeled
        ),
    }


def create_report(
    manifest: dict[str, Any],
    labels: dict[str, Any],
    assessment_directory: Path,
) -> dict[str, Any]:
    validate_inputs(manifest, labels)
    assessment_ids = [
        item["trajectory_id"] for item in manifest["plan"]
    ]
    assessments = {
        trajectory_id: load_assessment(
            assessment_directory, trajectory_id
        )
        for trajectory_id in assessment_ids
    }
    calibration_labels = [
        label for label in labels["labels"]
        if int(label["rank"]) <= CALIBRATION_RANK_MAX
    ]
    holdout_labels = [
        label for label in labels["labels"]
        if int(label["rank"]) > CALIBRATION_RANK_MAX
    ]
    if len(calibration_labels) != 20 or len(holdout_labels) != 10:
        raise ValueError("human label split must be exactly 20 calibration / 10 holdout")
    calibrators = fit_dimension_calibrators(
        calibration_labels, assessments
    )
    label_by_id = {
        label["trajectory_id"]: label for label in labels["labels"]
    }
    plan_by_id = {
        item["trajectory_id"]: item for item in manifest["plan"]
    }
    predictions = []
    for trajectory_id in assessment_ids:
        label = label_by_id.get(trajectory_id)
        plan_item = plan_by_id[trajectory_id]
        predictions.append({
            "trajectory_id": trajectory_id,
            "seed_id": plan_item["seed_id"],
            "execution_mode": plan_item["execution_mode"],
            "request_type": plan_item["request_type"],
            "human_rank": label.get("rank") if label else None,
            "human_rating": label.get("overall_rating") if label else None,
            "human_class": (
                human_class(label["overall_rating"]) if label else None
            ),
            "split": (
                "CALIBRATION"
                if label and label["rank"] <= CALIBRATION_RANK_MAX
                else "HOLDOUT"
                if label
                else "UNLABELED"
            ),
            "prediction": predict(assessments[trajectory_id], calibrators),
        })
    calibration_predictions = [
        item for item in predictions if item["split"] == "CALIBRATION"
    ]
    holdout_predictions = [
        item for item in predictions if item["split"] == "HOLDOUT"
    ]
    human_predictions = calibration_predictions + holdout_predictions
    all_metrics = split_metrics(predictions)
    calibration_metrics = split_metrics(calibration_predictions)
    holdout_metrics = split_metrics(holdout_predictions)
    checks = {
        "frozen_holdout_has_10_samples":
            holdout_metrics["sample_count"] == 10,
        "holdout_has_at_least_3_auto_decisions": (
            holdout_metrics["decision_counts"]["POSITIVE"]
            + holdout_metrics["decision_counts"]["NEGATIVE"]
        ) >= 3,
        "holdout_positive_precision_at_least_0_80":
            holdout_metrics["positive_precision"] >= 0.80,
        "no_negative_anchor_auto_approved":
            split_metrics(human_predictions)[
                "negative_anchor_false_positive_count"
            ] == 0,
        "maximum_dimension_calibration_mae_at_most_0_25":
            max(
                value["calibration_mae"]
                for value in calibrators.values()
            ) <= 0.25,
    }
    payload = {
        "schema_version": SCHEMA_VERSION,
        "source_batch_id": manifest["batch_id"],
        "source_plan_fingerprint": manifest["plan_fingerprint"],
        "human_label_fingerprint": labels["validation"][
            "label_fingerprint"
        ],
        "split_contract": {
            "version": "rank-stratified-20-10-v1",
            "calibration_ranks": [1, CALIBRATION_RANK_MAX],
            "holdout_ranks": [CALIBRATION_RANK_MAX + 1, 30],
        },
        "aggregation_contract": {
            "version": SCHEMA_VERSION,
            "features": ["intercept", "judge_score", "judge_confidence"],
            "ridge_lambda": RIDGE_LAMBDA,
            "dimension_weights": {
                dimension: 1.0 / len(DIMENSIONS)
                for dimension in DIMENSIONS
            },
            "verifier_weight": VERIFIER_WEIGHT,
            "ai_weight": AI_WEIGHT,
            "positive_threshold": POSITIVE_THRESHOLD,
            "negative_threshold": NEGATIVE_THRESHOLD,
            "minimum_confidence": MINIMUM_CONFIDENCE,
            "cross_dimension_agreement_gate": False,
        },
        "dimension_calibrators": calibrators,
        "metrics": {
            "calibration": calibration_metrics,
            "holdout": holdout_metrics,
            "all_119": all_metrics,
        },
        "exit_gate": {
            "checks": checks,
            "failures": [
                name for name, passed in checks.items() if not passed
            ],
            "passed": all(checks.values()),
        },
        "predictions": predictions,
        "model_calls": 0,
    }
    return {
        **payload,
        "report_fingerprint": canonical_sha256(payload),
    }


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    try:
        report = create_report(
            load_object(args.manifest),
            load_object(args.validated_labels),
            args.assessments,
        )
        write_json(args.output, report)
        print(json.dumps({
            "schema_version": report["schema_version"],
            "report_fingerprint": report["report_fingerprint"],
            "exit_gate": report["exit_gate"],
            "metrics": report["metrics"],
            "model_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

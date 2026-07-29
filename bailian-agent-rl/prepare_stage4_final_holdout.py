#!/usr/bin/env python3
"""Freeze a fresh 10-case human holdout after calibration development.

The selection uses no human labels from these cases. It chooses one risky,
previously unlabeled example from each request-type/execution-mode stratum.
"""

from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict
from pathlib import Path
from typing import Any

from prepare_stage4_human_review import (
    DIMENSIONS,
    LABEL_SCHEMA_VERSION,
    MINIMUM_COMMENT_CHARS,
    canonical_sha256,
    html_document,
    load_object,
    text_sha256,
    validate_labels,
    write_json,
    write_text,
)


SCHEMA_VERSION = "agent-rl-final-human-holdout-bundle-v1"
REVIEW_CONTRACT_VERSION = "fresh-final-holdout-10-v1"
REQUEST_TYPES = (
    "actions",
    "checklist",
    "decision",
    "dialogue",
    "weekly_plan",
)
EXECUTION_MODES = ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")
MINIMUM_POSITIVE_PRECISION = 0.80


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--calibration-report", type=Path, required=True)
    parser.add_argument("--prior-labels", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--assessments", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--validated-output", type=Path)
    return parser.parse_args()


def build_bundle(
    manifest: dict[str, Any],
    calibration: dict[str, Any],
    prior_labels: dict[str, Any],
    trajectories: Path,
    assessments: Path,
) -> dict[str, Any]:
    if (
        manifest.get("state") != "COMPLETED"
        or manifest.get("mode") != "execute"
        or calibration.get("schema_version")
        != "dimension-aware-alignment-calibration-v1"
        or calibration.get("source_plan_fingerprint")
        != manifest.get("plan_fingerprint")
        or calibration.get("exit_gate", {}).get("passed") is not False
        or prior_labels.get("validation", {}).get("complete") is not True
    ):
        raise ValueError("final holdout sources are not the frozen failed calibration")
    prior_ids = {
        label.get("trajectory_id") for label in prior_labels.get("labels", [])
    }
    plan_by_id = {
        item["trajectory_id"]: item for item in manifest["plan"]
    }
    buckets: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for value in calibration.get("predictions", []):
        trajectory_id = value.get("trajectory_id")
        if (
            not isinstance(trajectory_id, str)
            or trajectory_id in prior_ids
            or trajectory_id not in plan_by_id
        ):
            continue
        plan_item = plan_by_id[trajectory_id]
        key = (
            plan_item.get("request_type"),
            plan_item.get("execution_mode"),
        )
        if key[0] in REQUEST_TYPES and key[1] in EXECUTION_MODES:
            buckets[key].append(value)
    selected = []
    for request_type in REQUEST_TYPES:
        for mode in EXECUTION_MODES:
            candidates = buckets.get((request_type, mode), [])
            if not candidates:
                raise ValueError(f"no fresh holdout candidate for {request_type}/{mode}")
            candidates.sort(key=lambda value: (
                value["prediction"]["training_decision"] != "POSITIVE",
                min(
                    value["prediction"]["dimension_rewards"]["ACTIONABILITY"],
                    value["prediction"]["dimension_rewards"]["CRITICAL_REVIEW"],
                ),
                abs(value["prediction"]["total_reward"] - 0.75),
                value["trajectory_id"],
            ))
            selected.append(candidates[0])
    items = []
    for rank, value in enumerate(selected, start=31):
        trajectory_id = value["trajectory_id"]
        plan_item = plan_by_id[trajectory_id]
        trajectory = load_object(trajectories / f"{trajectory_id}.json")
        assessment = load_object(assessments / f"{trajectory_id}.json")
        scores = {
            score.get("dimension"): score
            for score in assessment.get("judgeScores", [])
            if isinstance(score, dict)
        }
        question = str(trajectory.get("question") or "").strip()
        answer = str(trajectory.get("finalAnswer") or "").strip()
        if (
            trajectory.get("trajectoryId") != trajectory_id
            or assessment.get("trajectoryId") != trajectory_id
            or set(scores) != set(DIMENSIONS)
            or not question
            or not answer
        ):
            raise ValueError(f"fresh holdout evidence mismatch: {trajectory_id}")
        items.append({
            "rank": rank,
            "seed_id": plan_item["seed_id"],
            "trajectory_id": trajectory_id,
            "execution_mode": plan_item["execution_mode"],
            "domains": plan_item["domains"],
            "request_type": plan_item["request_type"],
            "priority_reasons": [
                "fresh_unseen_holdout",
                "risk_challenge",
                "stratified_request_type",
                "stratified_execution_mode",
            ],
            "question": question,
            "answer": answer,
            "question_fingerprint": text_sha256(question),
            "answer_fingerprint": text_sha256(answer),
        })
    identity = {
        "schema_version": SCHEMA_VERSION,
        "source_batch_id": manifest["batch_id"],
        "source_plan_fingerprint": manifest["plan_fingerprint"],
        "review_contract_version": REVIEW_CONTRACT_VERSION,
        "policy_version": manifest["source_replay"]["policy_version"],
        "source_calibration_fingerprint": calibration["report_fingerprint"],
        "prior_label_fingerprint": prior_labels["validation"][
            "label_fingerprint"
        ],
        "blind_review": True,
        "item_identities": [
            {
                "rank": item["rank"],
                "seed_id": item["seed_id"],
                "trajectory_id": item["trajectory_id"],
                "question_fingerprint": item["question_fingerprint"],
                "answer_fingerprint": item["answer_fingerprint"],
            }
            for item in items
        ],
    }
    return {
        **identity,
        "bundle_fingerprint": canonical_sha256(identity),
        "export_filename": "policy-v7-stage4-final-holdout-labels-10.json",
        "show_judge_opinions": not identity["blind_review"],
        "minimum_comment_chars": MINIMUM_COMMENT_CHARS,
        "dimensions": list(DIMENSIONS),
        "items": items,
    }


def evaluate_final_holdout(
    bundle: dict[str, Any],
    validated: dict[str, Any],
    calibration: dict[str, Any],
) -> dict[str, Any]:
    if (
        bundle.get("source_calibration_fingerprint")
        != calibration.get("report_fingerprint")
        or validated.get("bundle_fingerprint")
        != bundle.get("bundle_fingerprint")
    ):
        raise ValueError("final holdout evaluation identity mismatch")
    predictions = {
        value.get("trajectory_id"): value.get("prediction")
        for value in calibration.get("predictions", [])
        if isinstance(value, dict)
    }
    rows = []
    for label in validated.get("labels", []):
        prediction = predictions.get(label.get("trajectory_id"))
        if not isinstance(prediction, dict):
            raise ValueError("final holdout prediction is missing")
        rating = label["overall_rating"]
        human_class = (
            "POSITIVE" if rating >= 4
            else "NEGATIVE" if rating <= 2
            else "HOLDOUT"
        )
        rows.append({
            "rank": label["rank"],
            "trajectory_id": label["trajectory_id"],
            "human_rating": rating,
            "human_class": human_class,
            "predicted_decision": prediction.get("training_decision"),
            "predicted_reward": prediction.get("total_reward"),
            "predicted_confidence": prediction.get("confidence"),
        })
    predicted_positive = [
        row for row in rows
        if row["predicted_decision"] == "POSITIVE"
    ]
    true_positive = [
        row for row in predicted_positive
        if row["human_class"] == "POSITIVE"
    ]
    negative_false_positives = [
        row for row in predicted_positive
        if row["human_class"] == "NEGATIVE"
    ]
    auto_decision_count = sum(
        row["predicted_decision"] in ("POSITIVE", "NEGATIVE")
        for row in rows
    )
    positive_precision = (
        round(len(true_positive) / len(predicted_positive), 6)
        if predicted_positive else 0.0
    )
    checks = {
        "frozen_final_holdout_has_10_samples": len(rows) == 10,
        "final_holdout_has_at_least_3_auto_decisions":
            auto_decision_count >= 3,
        "final_holdout_positive_precision_at_least_0_80":
            positive_precision >= MINIMUM_POSITIVE_PRECISION,
        "no_negative_anchor_auto_approved":
            not negative_false_positives,
    }
    return {
        "schema_version": "stage4-final-holdout-gate-v1",
        "source_calibration_fingerprint":
            calibration["report_fingerprint"],
        "sample_count": len(rows),
        "decision_counts": {
            decision: sum(
                row["predicted_decision"] == decision for row in rows
            )
            for decision in ("POSITIVE", "NEGATIVE", "HOLDOUT", "EXCLUDED")
        },
        "positive_precision": positive_precision,
        "negative_anchor_false_positive_count":
            len(negative_false_positives),
        "checks": checks,
        "failures": [
            name for name, passed in checks.items() if not passed
        ],
        "passed": all(checks.values()),
        "rows": rows,
        "model_calls": 0,
    }


def main() -> int:
    args = parse_args()
    try:
        bundle = build_bundle(
            load_object(args.manifest),
            load_object(args.calibration_report),
            load_object(args.prior_labels),
            args.trajectories,
            args.assessments,
        )
        if args.labels:
            if args.validated_output is None:
                raise ValueError("--labels requires --validated-output")
            validated = validate_labels(bundle, load_object(args.labels))
            final_evaluation = evaluate_final_holdout(
                bundle,
                validated,
                load_object(args.calibration_report),
            )
            validated = {
                **validated,
                "final_holdout_evaluation": final_evaluation,
            }
            write_json(args.validated_output, validated)
            print(json.dumps({
                "mode": "validate-final-holdout",
                "bundle_fingerprint": bundle["bundle_fingerprint"],
                **validated["validation"],
                "final_holdout_evaluation": final_evaluation,
            }, ensure_ascii=False, indent=2))
            return 0
        if args.output is None:
            raise ValueError("bundle generation requires --output")
        write_text(args.output, html_document(bundle))
        print(json.dumps({
            "mode": "build-final-holdout",
            "output": str(args.output),
            "item_count": len(bundle["items"]),
            "bundle_fingerprint": bundle["bundle_fingerprint"],
            "model_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

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
            write_json(args.validated_output, validated)
            print(json.dumps({
                "mode": "validate-final-holdout",
                "bundle_fingerprint": bundle["bundle_fingerprint"],
                **validated["validation"],
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

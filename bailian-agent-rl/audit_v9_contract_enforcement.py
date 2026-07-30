#!/usr/bin/env python3
"""Audit v9 deterministic contract enforcement on historical trajectories.

This script is intentionally offline: it reads frozen seeds and trajectory JSON,
does not call the Agent endpoint, and does not call a model.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from prepare_v8_precision_validation import load_object, write_json
from replay_training_seeds import load_seeds


REPORT_SCHEMA_VERSION = "rl-v9-contract-audit-v1"
FOLLOW_UP_PHRASES = (
    "请你详细描述",
    "请详细描述",
    "请补充更多",
    "请提供更多",
    "接下来，请你",
    "以便我为你",
)
ASSUMPTION_MARKERS = ("假设", "基于目前", "基于现有", "信息不足")
ACTION_PATTERN = re.compile(r"(?m)^\s*(?:#{1,6}\s*)?(?:\d+[.、)]|[-*])\s*")
CITATION_PATTERN = re.compile(r"\[来源\s*(\d{1,3})\]")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def bounded_int(value: Any, default: int) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    return max(0, int(value))


def contract_missing(
    answer: str,
    contract: dict[str, Any],
    document_count: int,
) -> list[str]:
    missing: list[str] = []
    minimum_chars = bounded_int(contract.get("minimum_answer_chars"), 0)
    maximum_chars = bounded_int(contract.get("maximum_answer_chars"), 8_000)
    if len(answer) < minimum_chars:
        missing.append(f"minimum_answer_chars:{minimum_chars}")
    if len(answer) > maximum_chars:
        missing.append(f"maximum_answer_chars:{maximum_chars}")

    if contract.get("citation_required") is True:
        citations = [int(value) for value in CITATION_PATTERN.findall(answer)]
        if not citations or any(
            value < 1 or value > document_count for value in citations
        ):
            missing.append("citation_required")

    concepts = contract.get("required_concepts")
    if isinstance(concepts, list):
        for expression in concepts:
            if not isinstance(expression, str) or not expression.strip():
                continue
            alternatives = [
                value.strip()
                for value in expression.split("|")
                if value.strip()
            ]
            if not any(value in answer for value in alternatives):
                missing.append(f"required_concept:{expression}")

    forbidden = contract.get("forbidden_phrases")
    if isinstance(forbidden, list):
        for phrase in forbidden:
            if isinstance(phrase, str) and phrase and phrase in answer:
                missing.append(f"forbidden_phrase:{phrase}")

    if contract.get("no_follow_up") is True and any(
        marker in answer for marker in FOLLOW_UP_PHRASES
    ):
        missing.append("no_follow_up")

    minimum_actions = bounded_int(contract.get("minimum_action_items"), 0)
    if len(ACTION_PATTERN.findall(answer)) < minimum_actions:
        missing.append(f"minimum_action_items:{minimum_actions}")

    if contract.get("must_mark_assumptions") is True and not any(
        marker in answer for marker in ASSUMPTION_MARKERS
    ):
        missing.append("must_mark_assumptions")
    return missing


def last_step(
    trajectory: dict[str, Any],
    step_types: set[str],
) -> dict[str, Any] | None:
    steps = trajectory.get("steps")
    if not isinstance(steps, list):
        return None
    for step in reversed(steps):
        if isinstance(step, dict) and step.get("type") in step_types:
            return step
    return None


def answer_from_step(
    trajectory: dict[str, Any],
    step_types: set[str],
    key: str = "answer",
) -> str:
    step = last_step(trajectory, step_types)
    output = step.get("output") if isinstance(step, dict) else None
    value = output.get(key) if isinstance(output, dict) else None
    return str(value or "")


def audit(
    manifest_path: Path,
    seeds_path: Path,
    trajectories_path: Path,
) -> dict[str, Any]:
    manifest = load_object(manifest_path)
    seeds = load_seeds(seeds_path)
    seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
    rows: list[dict[str, Any]] = []
    for evidence in manifest.get("historical_evidence", []):
        seed_id = evidence["seed_id"]
        seed = seeds_by_id[seed_id]
        contract = seed["rollout_extra"]["verification_contract"]
        for baseline in evidence["baseline_rounds"]:
            trajectory_id = baseline["trajectory_id"]
            trajectory = load_object(
                trajectories_path / f"{trajectory_id}.json"
            )
            documents = trajectory.get("retrievedDocumentIds")
            document_count = len(documents) if isinstance(documents, list) else 0
            draft = answer_from_step(
                trajectory, {"GENERATE", "SYNTHESIZE"}
            )
            final_answer = str(trajectory.get("finalAnswer") or "")
            review = last_step(trajectory, {"REVIEW"})
            review_output = (
                review.get("output")
                if isinstance(review, dict)
                and isinstance(review.get("output"), dict)
                else {}
            )
            draft_missing = contract_missing(
                draft, contract, document_count
            )
            final_missing = contract_missing(
                final_answer, contract, document_count
            )
            reviewer_approved = (
                review_output.get("taskCompleted") is True
                and review_output.get("grounded") is True
            )
            rows.append({
                "seed_id": seed_id,
                "round": baseline["round"],
                "trajectory_id": trajectory_id,
                "reviewer_approved": reviewer_approved,
                "draft_contract_passed": not draft_missing,
                "draft_missing": draft_missing,
                "final_contract_passed": not final_missing,
                "final_missing": final_missing,
                "would_force_revision": bool(draft_missing),
                "reviewer_false_negative": (
                    reviewer_approved and bool(draft_missing)
                ),
                "historical_rlvr_violations": baseline["rlvr"]["violations"],
            })

    historical_violations = [
        row for row in rows if row["historical_rlvr_violations"]
    ]
    violation_detections = [
        row for row in historical_violations
        if not row["final_contract_passed"]
    ]
    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": "offline_historical_counterfactual",
        "model_calls": 0,
        "billable_operations": 0,
        "policy_version": "agentic-rag-v9",
        "trajectory_count": len(rows),
        "summary": {
            "drafts_requiring_forced_revision": sum(
                row["would_force_revision"] for row in rows
            ),
            "reviewer_false_negatives": sum(
                row["reviewer_false_negative"] for row in rows
            ),
            "historical_finals_failing_contract": sum(
                not row["final_contract_passed"] for row in rows
            ),
            "historical_rlvr_violation_trajectories": len(
                historical_violations
            ),
            "historical_violation_contract_detections": len(
                violation_detections
            ),
        },
        "rows": rows,
    }


def main() -> int:
    args = parse_args()
    report = audit(args.manifest, args.seeds, args.trajectories)
    write_json(args.output, report)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    print("Offline only. No endpoint or model was called.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

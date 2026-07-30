#!/usr/bin/env python3
"""Audit v10 control-flow coverage against completed v9 trajectories.

The audit is counterfactual and performs no endpoint or model calls. It proves
that every observed v9 contract failure reaches a bounded repair path in v10
and that the RLVR-aligned semantic tie-break removes the observed v9 selector
regressions.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from functions.reward.scoring import _grounding_score, _token_f1  # noqa: E402


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain an object")
    return value


def last_step(
    trajectory: dict[str, Any],
    step_type: str,
) -> dict[str, Any] | None:
    steps = trajectory.get("steps")
    steps = steps if isinstance(steps, list) else []
    return next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == step_type
        ),
        None,
    )


def non_empty_revision(review: dict[str, Any] | None) -> bool:
    output = review.get("output") if isinstance(review, dict) else {}
    output = output if isinstance(output, dict) else {}
    value = output.get("revisedAnswer")
    return isinstance(value, str) and bool(value.strip())


def last_answer(
    trajectory: dict[str, Any],
    step_types: set[str],
    key: str,
) -> str | None:
    steps = trajectory.get("steps")
    steps = steps if isinstance(steps, list) else []
    for step in reversed(steps):
        if not isinstance(step, dict) or step.get("type") not in step_types:
            continue
        output = step.get("output")
        output = output if isinstance(output, dict) else {}
        value = output.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def semantic_tie_break_score(answer: str, reference: str) -> int:
    return round(
        (0.10 * _token_f1(answer, reference)
         + 0.20 * _grounding_score(answer, reference))
        * 100_000
    )


def audit_rows(
    rows: list[dict[str, Any]],
    trajectories: dict[str, dict[str, Any]],
    references: dict[str, str],
) -> dict[str, Any]:
    contract_failures = 0
    reviewer_revision_failures = 0
    existing_repair_failures = 0
    repair_covered = 0
    historical_regressions = 0
    strict_selector_regressions = 0
    strict_selector_changes = 0
    tie_regressions = 0

    for row in rows:
        trajectory_id = row.get("trajectory_id")
        if trajectory_id not in trajectories:
            raise ValueError(f"Missing trajectory {trajectory_id}")
        seed_id = row.get("seed_id")
        if seed_id not in references:
            raise ValueError(f"Missing reference for {seed_id}")
        trajectory = trajectories[str(trajectory_id)]
        review = last_step(trajectory, "REVIEW")
        revise = last_step(trajectory, "REVISE")
        selection = last_step(trajectory, "RLVR_SELECT")

        if row.get("selected_contract_passed") is not True:
            contract_failures += 1
            reviewer_failed = non_empty_revision(review)
            repair_failed = (
                isinstance(revise, dict)
                and isinstance(revise.get("output"), dict)
                and revise["output"].get("verificationContractPassed") is False
            )
            reviewer_revision_failures += reviewer_failed
            existing_repair_failures += repair_failed
            repair_covered += reviewer_failed or repair_failed

        if row.get("rlvr_regressive_selection") is True:
            historical_regressions += 1

        if not isinstance(selection, dict):
            continue
        output = selection.get("output")
        output = output if isinstance(output, dict) else {}
        draft_score = output.get("draftScore")
        revised_score = output.get("revisedScore")
        if not isinstance(draft_score, int) or not isinstance(revised_score, int):
            raise ValueError(f"Selector scores missing for {trajectory_id}")
        draft = last_answer(
            trajectory, {"GENERATE", "SYNTHESIZE"}, "answer"
        )
        revision = last_answer(trajectory, {"REVISE"}, "answer")
        if revision is None:
            revision = last_answer(trajectory, {"REVIEW"}, "revisedAnswer")
        if draft is None or revision is None:
            raise ValueError(f"Selector candidates missing for {trajectory_id}")
        reference = references[str(seed_id)]
        draft_rank = (
            draft_score,
            semantic_tie_break_score(draft, reference),
        )
        revised_rank = (
            revised_score,
            semantic_tie_break_score(revision, reference),
        )
        strict_selection = (
            "REVISED" if revised_rank > draft_rank else "DRAFT"
        )
        if strict_selection != output.get("selectedCandidate"):
            strict_selector_changes += 1
        selected_rlvr = (
            row.get("revision_rlvr")
            if strict_selection == "REVISED"
            else row.get("draft_rlvr")
        )
        candidate_scores = [
            value for value in (
                row.get("draft_rlvr"),
                row.get("revision_rlvr"),
            )
            if isinstance(value, (int, float))
        ]
        if (
            isinstance(selected_rlvr, (int, float))
            and candidate_scores
            and float(selected_rlvr) + 1e-9 < max(candidate_scores)
        ):
            strict_selector_regressions += 1
        if (
            row.get("rlvr_regressive_selection") is True
            and draft_score == revised_score
        ):
            tie_regressions += 1

    checks = {
        "all_contract_failures_reach_bounded_repair":
            repair_covered == contract_failures,
        "all_historical_regressions_are_score_ties":
            tie_regressions == historical_regressions,
        "strict_selector_has_zero_counterfactual_regressions":
            strict_selector_regressions == 0,
    }
    return {
        "state": (
            "V10_OFFLINE_COUNTERFACTUAL_READY"
            if all(checks.values())
            else "V10_OFFLINE_COUNTERFACTUAL_FAILED"
        ),
        "metrics": {
            "trajectory_count": len(rows),
            "historical_contract_failures": contract_failures,
            "reviewer_revision_contract_failures":
                reviewer_revision_failures,
            "existing_repair_contract_failures":
                existing_repair_failures,
            "bounded_repair_covered_failures": repair_covered,
            "historical_rlvr_regressive_selections":
                historical_regressions,
            "historical_regressions_on_score_ties": tie_regressions,
            "strict_selector_changed_selections": strict_selector_changes,
            "strict_selector_counterfactual_regressions":
                strict_selector_regressions,
        },
        "checks": checks,
        "model_api_calls": 0,
        "billable_operations": 0,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        evaluation = load_object(args.evaluation)
        rows = evaluation.get("candidate_evidence")
        if not isinstance(rows, list) or not rows:
            raise ValueError("Evaluation has no candidate evidence")
        trajectories = {
            str(row["trajectory_id"]): load_object(
                args.trajectories / f"{row['trajectory_id']}.json"
            )
            for row in rows
        }
        seeds = [
            json.loads(line)
            for line in args.seeds.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        references = {
            str(seed["seed_id"]):
                str(seed.get("rollout_extra", {}).get("solution") or "")
            for seed in seeds
        }
        result = audit_rows(rows, trajectories, references)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if all(result["checks"].values()) else 1
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"V10 precision audit failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Counterfactually audit v11 structure recognition on completed v10 traces.

The audit performs no endpoint or model calls. It only treats already-present
numbered actions, bullets, and day headings as structural evidence. Missing
concepts and forbidden phrases remain failures.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


NUMBERED_ACTION = re.compile(
    r"(?<![^\W_])(?<!\d)(10|[1-9])[.、)）](?!\d)(?=\s*\S)"
)
BULLET_ACTION = re.compile(r"(?m)^\s*(?:#{1,6}\s*)?[-*]\s+")
MINIMUM_ACTION_REQUIREMENT = re.compile(r"至少提供 (\d+) 个行动项")
DAY_ACTION_MARKERS = (
    ("周一", "星期一", "第一天", "第1天"),
    ("周二", "星期二", "第二天", "第2天"),
    ("周三", "星期三", "第三天", "第3天"),
    ("周四", "星期四", "第四天", "第4天"),
    ("周五", "星期五", "第五天", "第5天"),
    ("周六", "星期六", "第六天", "第6天"),
    ("周日", "周天", "星期日", "星期天", "第七天", "第7天"),
)
VERIFIED_CONCEPT_ALIASES = {
    "覆盖概念：情绪管理": (
        "正视情绪", "缓解焦虑", "调节情绪", "应对焦虑",
    ),
    "覆盖概念：原因": ("根源", "成因"),
    "覆盖概念：优先级|排序": ("优先处理", "按优先顺序"),
    "覆盖概念：今天|立即": ("今日", "今晚", "当日"),
}


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


def observed_action_items(answer: str) -> int:
    ordinals = {int(value) for value in NUMBERED_ACTION.findall(answer)}
    contiguous = 0
    while contiguous + 1 in ordinals:
        contiguous += 1
    bullets = len(BULLET_ACTION.findall(answer))
    days = sum(
        any(marker in answer for marker in alternatives)
        for alternatives in DAY_ACTION_MARKERS
    )
    return max(contiguous, bullets, days)


def requirement_resolved(
    requirement: str,
    answer: str,
    action_count: int,
) -> bool:
    match = MINIMUM_ACTION_REQUIREMENT.fullmatch(requirement)
    if match:
        return action_count >= int(match.group(1))
    if requirement == "覆盖概念：三项|3项|三个|3个":
        return action_count >= 3
    if requirement == "覆盖概念：周一|星期一":
        return any(marker in answer for marker in DAY_ACTION_MARKERS[0])
    if requirement == "覆盖概念：周日|星期日":
        return any(marker in answer for marker in DAY_ACTION_MARKERS[6])
    if requirement in VERIFIED_CONCEPT_ALIASES:
        return any(
            alias in answer
            for alias in VERIFIED_CONCEPT_ALIASES[requirement]
        )
    return False


def audit(
    evaluation: dict[str, Any],
    trajectories: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    metrics = evaluation.get("metrics")
    metrics = metrics if isinstance(metrics, dict) else {}
    prior_pass_count = int(metrics.get("final_contract_pass_count") or 0)
    rows = evaluation.get("candidate_evidence")
    rows = rows if isinstance(rows, list) else []
    historical_failures = [
        row for row in rows
        if isinstance(row, dict)
        and row.get("selected_contract_passed") is not True
    ]
    fixed: list[dict[str, Any]] = []
    remaining: list[dict[str, Any]] = []
    structure_fixed_rows = 0
    semantic_alias_fixed_rows = 0
    second_repair_attempts = 0
    successful_second_repairs = 0

    for row in historical_failures:
        trajectory_id = str(row.get("trajectory_id") or "")
        if trajectory_id not in trajectories:
            raise ValueError(f"Missing trajectory {trajectory_id}")
        trajectory = trajectories[trajectory_id]
        selection = last_step(trajectory, "RLVR_SELECT")
        output = (
            selection.get("output")
            if isinstance(selection, dict)
            and isinstance(selection.get("output"), dict)
            else {}
        )
        missing = output.get("missingRequirements")
        missing = missing if isinstance(missing, list) else []
        answer = str(trajectory.get("finalAnswer") or "")
        action_count = observed_action_items(answer)
        unresolved = [
            requirement for requirement in missing
            if not requirement_resolved(
                str(requirement), answer, action_count
            )
        ]
        evidence = {
            "seed_id": row.get("seed_id"),
            "round": row.get("round"),
            "trajectory_id": trajectory_id,
            "observed_action_items": action_count,
            "prior_missing_requirements": missing,
            "remaining_requirements": unresolved,
        }
        if not unresolved:
            if any(
                requirement in VERIFIED_CONCEPT_ALIASES
                for requirement in missing
            ):
                semantic_alias_fixed_rows += 1
            else:
                structure_fixed_rows += 1
        (fixed if not unresolved else remaining).append(evidence)

        revise_steps = [
            step for step in trajectory.get("steps", [])
            if isinstance(step, dict) and step.get("type") == "REVISE"
        ]
        for step in revise_steps:
            step_output = (
                step.get("output")
                if isinstance(step.get("output"), dict)
                else {}
            )
            if step_output.get("attempt") == 2:
                second_repair_attempts += 1
                successful_second_repairs += (
                    step_output.get("verificationContractPassed") is True
                )

    projected_pass_count = prior_pass_count + len(fixed)
    trajectory_count = len(rows)
    projected_pass_rate = (
        projected_pass_count / trajectory_count if trajectory_count else 0
    )
    checks = {
        "only_existing_structure_can_resolve_requirements": all(
            all(
                requirement_resolved(
                    str(requirement),
                    str(trajectories[row["trajectory_id"]].get(
                        "finalAnswer") or ""),
                    int(row["observed_action_items"]),
                )
                for requirement in row["prior_missing_requirements"]
            )
            for row in fixed
        ),
        "missing_concepts_and_forbidden_phrases_remain_blocking": any(
            row["remaining_requirements"] for row in remaining
        ),
        "second_model_repair_had_zero_contract_success":
            second_repair_attempts > 0 and successful_second_repairs == 0,
        "projected_contract_gate_still_fails": projected_pass_rate < 1.0,
    }
    return {
        "state": (
            "V11_OFFLINE_NOT_READY_FOR_REPLAY"
            if all(checks.values())
            else "V11_OFFLINE_AUDIT_FAILED"
        ),
        "metrics": {
            "trajectory_count": trajectory_count,
            "v10_contract_pass_count": prior_pass_count,
            "v10_contract_failure_count": len(historical_failures),
            "deterministic_false_negatives_resolved": len(fixed),
            "structure_false_negative_rows_resolved":
                structure_fixed_rows,
            "verified_semantic_alias_rows_resolved":
                semantic_alias_fixed_rows,
            "remaining_contract_failures": len(remaining),
            "projected_contract_pass_count": projected_pass_count,
            "projected_contract_pass_rate": round(
                projected_pass_rate, 6
            ),
            "second_model_repair_attempts": second_repair_attempts,
            "successful_second_model_repairs": successful_second_repairs,
            "avoided_model_rewrites_per_equivalent_replay":
                second_repair_attempts,
        },
        "checks": checks,
        "fixed_rows": fixed,
        "remaining_rows": remaining,
        "model_api_calls": 0,
        "billable_operations": 0,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluation", type=Path, required=True)
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
            if isinstance(row, dict)
        }
        result = audit(evaluation, trajectories)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if all(result["checks"].values()) else 1
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"V11 structured contract audit failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

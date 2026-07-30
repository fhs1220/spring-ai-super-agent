#!/usr/bin/env python3
"""Audit whether v12 can safely represent every unresolved v11 contract.

This audit performs no endpoint or model calls. It does not claim that old
answers become compliant. It only verifies that each remaining failure is
routed either to model-supplied structured content or to a non-mutating hard
gate.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


STRUCTURED_ROUTES = {
    "覆盖概念：": "evidence",
    "至少提供 ": "actionItems",
    "明确标注合理假设或信息边界": "assumptions",
}
HARD_GATE_ROUTES = {
    "删除禁用短语：": "answer_rewrite_plus_forbidden_phrase_gate",
    "使用有效的 [来源 n] 单编号引用":
        "answer_rewrite_plus_citation_gate",
    "没有可满足引用契约的知识证据": "citation_gate",
    "答案不少于 ": "answer_rewrite_plus_length_gate",
    "答案不超过 ": "answer_rewrite_plus_length_gate",
    "直接回答，不向用户追问": "answer_rewrite_plus_follow_up_gate",
}


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain an object")
    return value


def route_requirement(requirement: str) -> tuple[str, str] | None:
    for prefix, field in STRUCTURED_ROUTES.items():
        if requirement.startswith(prefix):
            return "structured_content", field
    for prefix, gate in HARD_GATE_ROUTES.items():
        if requirement.startswith(prefix):
            return "hard_gate", gate
    return None


def audit(v11_audit: dict[str, Any]) -> dict[str, Any]:
    remaining_rows = v11_audit.get("remaining_rows")
    remaining_rows = (
        remaining_rows if isinstance(remaining_rows, list) else []
    )
    audited_rows: list[dict[str, Any]] = []
    unsupported: list[dict[str, str]] = []
    structured_rows = 0
    hard_gate_rows = 0
    requirement_count = 0

    for row in remaining_rows:
        if not isinstance(row, dict):
            continue
        requirements = row.get("remaining_requirements")
        requirements = (
            requirements if isinstance(requirements, list) else []
        )
        routes: list[dict[str, str]] = []
        row_has_structured = False
        row_has_hard_gate = False
        for value in requirements:
            requirement = str(value)
            requirement_count += 1
            route = route_requirement(requirement)
            if route is None:
                unsupported.append({
                    "trajectory_id": str(row.get("trajectory_id") or ""),
                    "requirement": requirement,
                })
                routes.append({
                    "requirement": requirement,
                    "route": "unsupported",
                    "mechanism": "",
                })
                continue
            route_type, mechanism = route
            row_has_structured |= route_type == "structured_content"
            row_has_hard_gate |= route_type == "hard_gate"
            routes.append({
                "requirement": requirement,
                "route": route_type,
                "mechanism": mechanism,
            })
        structured_rows += row_has_structured
        hard_gate_rows += row_has_hard_gate
        audited_rows.append({
            "seed_id": row.get("seed_id"),
            "round": row.get("round"),
            "trajectory_id": row.get("trajectory_id"),
            "routes": routes,
        })

    checks = {
        "has_unresolved_v11_rows": bool(audited_rows),
        "every_remaining_requirement_has_a_safe_route": not unsupported,
        "forbidden_phrases_remain_hard_gated": all(
            route_requirement(str(requirement))[0] == "hard_gate"
            for row in remaining_rows
            if isinstance(row, dict)
            for requirement in row.get("remaining_requirements", [])
            if str(requirement).startswith("删除禁用短语：")
        ),
        "historical_projection_is_not_relabelled_as_v12_success":
            v11_audit.get("state") == "V11_OFFLINE_NOT_READY_FOR_REPLAY",
    }
    ready = all(checks.values())
    return {
        "state": (
            "V12_OFFLINE_READY_FOR_TARGETED_PILOT"
            if ready
            else "V12_OFFLINE_NOT_READY"
        ),
        "metrics": {
            "remaining_v11_rows": len(audited_rows),
            "remaining_requirement_instances": requirement_count,
            "rows_using_structured_content": structured_rows,
            "rows_using_hard_gates": hard_gate_rows,
            "unsupported_requirement_instances": len(unsupported),
            "historical_projected_contract_pass_count":
                v11_audit.get("metrics", {}).get(
                    "projected_contract_pass_count"
                ),
            "historical_trajectory_count":
                v11_audit.get("metrics", {}).get("trajectory_count"),
        },
        "checks": checks,
        "audited_rows": audited_rows,
        "unsupported": unsupported,
        "model_api_calls": 0,
        "billable_operations": 0,
        "replay_scope": (
            "TARGETED_PILOT_ONLY" if ready else "NONE"
        ),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--v11-audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        result = audit(load_object(args.v11_audit))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if all(result["checks"].values()) else 1
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"V12 structured repair audit failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

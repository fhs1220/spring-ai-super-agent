#!/usr/bin/env python3
"""Expand the passing Judge v2 contract from 40 labels to all 119 cases."""

from __future__ import annotations

import argparse
import json
import os
import urllib.parse
from pathlib import Path
from typing import Any

from validate_stage4_judge_v2 import (
    ASSESSMENT_NAMESPACE,
    DIMENSIONS,
    JUDGE_CONTRACT_VERSION,
    canonical_sha256,
    load_object,
    request_json,
    require_execution_authorization,
    validate_assessment,
    validate_contract,
    write_json,
)


SCHEMA_VERSION = "stage4-judge-v2-expansion-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api-root", default="http://127.0.0.1:8123/api")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--execute", action="store_true")
    return parser.parse_args()


def build_expansion(
    source: dict[str, Any],
    validation: dict[str, Any],
) -> dict[str, Any]:
    calibrated = validation.get("evaluation", {}).get(
        "calibrated_positive_only_contract", {}
    )
    contract = calibrated.get("contract")
    if (
        source.get("state") != "COMPLETED"
        or source.get("execution_summary", {}).get("complete_panel_count")
        != 119
        or len(source.get("plan", [])) != 119
        or validation.get("state") != "COMPLETED"
        or validation.get("judge_contract_version")
        != JUDGE_CONTRACT_VERSION
        or validation.get("assessment_namespace")
        != ASSESSMENT_NAMESPACE
        or calibrated.get("passed") is not True
        or not isinstance(contract, dict)
        or contract.get("negative_pseudo_labels_enabled") is not False
    ):
        raise ValueError("Judge v2 expansion sources are not passing and frozen")
    validation_results = validation.get("results", [])
    if len(validation_results) != 40:
        raise ValueError("Judge v2 expansion must reuse exactly 40 results")
    reused_by_id = {
        result["trajectory_id"]: result for result in validation_results
    }
    items = [{
        "trajectory_id": item["trajectory_id"],
        "seed_id": item["seed_id"],
        "request_type": item["request_type"],
        "execution_mode": item["execution_mode"],
        "answer_chars": item["answer_chars"],
    } for item in source["plan"]]
    if len({item["trajectory_id"] for item in items}) != 119:
        raise ValueError("Judge v2 expansion plan has duplicate trajectories")
    if not set(reused_by_id).issubset({
        item["trajectory_id"] for item in items
    }):
        raise ValueError("Judge v2 validation result is outside expansion plan")
    identity = {
        "schema_version": SCHEMA_VERSION,
        "source_batch_id": source["batch_id"],
        "source_plan_fingerprint": source["plan_fingerprint"],
        "source_validation_fingerprint":
            validation["validation_fingerprint"],
        "judge_contract_version": JUDGE_CONTRACT_VERSION,
        "assessment_namespace": ASSESSMENT_NAMESPACE,
        "positive_only_contract": contract,
        "items": items,
    }
    return {
        **identity,
        "expansion_fingerprint": canonical_sha256(identity),
        "mode": "dry-run",
        "state": "PLANNED",
        "budget": {
            "total_trajectory_count": 119,
            "reused_trajectory_count": 40,
            "new_trajectory_limit": 79,
            "reused_judge_output_count": 160,
            "maximum_new_judge_calls": 316,
            "new_judge_calls_executed": 0,
        },
        "results": list(validation_results),
        "selection": None,
    }


def select_positive(manifest: dict[str, Any]) -> dict[str, Any]:
    contract = manifest["positive_only_contract"]
    results = {
        result["trajectory_id"]: result for result in manifest["results"]
    }
    decisions = []
    for item in manifest["items"]:
        result = results.get(item["trajectory_id"])
        if result is None:
            continue
        scores = {
            score["dimension"]: score for score in result["judge_scores"]
        }
        positive = (
            item["answer_chars"] >= contract["minimum_answer_chars"]
            and min(
                float(scores["INSTRUCTION_FOLLOWING"]["score"]),
                float(scores["ACTIONABILITY"]["score"]),
                float(scores["LOGICAL_CONSISTENCY"]["score"]),
            ) >= contract["minimum_dimension_score"]
        )
        decisions.append({
            "trajectory_id": item["trajectory_id"],
            "seed_id": item["seed_id"],
            "decision": "POSITIVE" if positive else "HOLDOUT",
        })
    positive_count = sum(
        item["decision"] == "POSITIVE" for item in decisions
    )
    validation_count = (
        max(1, round(positive_count * 0.1))
        if positive_count else 0
    )
    training_count = positive_count - validation_count
    return {
        "schema_version": "judge-v2-positive-only-selection-v1",
        "sample_count": len(decisions),
        "positive_count": positive_count,
        "holdout_count": len(decisions) - positive_count,
        "negative_count": 0,
        "validation_ratio": 0.1,
        "training_count": training_count,
        "validation_count": validation_count,
        "expected_batch_size": 64,
        "ready_for_stage5":
            training_count > 64 and validation_count > 0,
        "decisions": decisions,
    }


def execute(
    manifest: dict[str, Any],
    api_root: str,
    timeout: int,
    output: Path,
) -> None:
    contract_url = api_root.rstrip("/") + "/agent-rl/alignment/contract"
    contract, status = request_json(contract_url, timeout)
    if status != 200:
        raise ValueError("Judge v2 expansion contract preflight failed")
    validate_contract(contract)
    existing_ids = {
        result["trajectory_id"] for result in manifest["results"]
    }
    validation_ids = set(existing_ids)
    manifest["mode"] = "execute"
    for item in manifest["items"]:
        trajectory_id = item["trajectory_id"]
        if trajectory_id in existing_ids:
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
        existing_ids.add(trajectory_id)
        manifest["budget"]["new_judge_calls_executed"] = (
            len(existing_ids - validation_ids) * len(DIMENSIONS)
        )
        if (
            manifest["budget"]["new_judge_calls_executed"]
            > manifest["budget"]["maximum_new_judge_calls"]
        ):
            raise ValueError("Judge v2 expansion exceeded the frozen call limit")
        write_json(output, manifest)
    manifest["selection"] = select_positive(manifest)
    manifest["state"] = (
        "COMPLETED"
        if len(manifest["results"]) == 119
        else "INCOMPLETE"
    )


def main() -> int:
    args = parse_args()
    try:
        planned = build_expansion(
            load_object(args.manifest),
            load_object(args.validation),
        )
        if args.output.exists():
            existing = load_object(args.output)
            if (
                existing.get("expansion_fingerprint")
                != planned["expansion_fingerprint"]
            ):
                raise ValueError("existing Judge v2 expansion mismatch")
            planned["results"] = existing.get("results", [])
            planned["budget"]["new_judge_calls_executed"] = max(
                0, len(planned["results"]) - 40
            ) * len(DIMENSIONS)
        if args.execute:
            require_execution_authorization()
            execute(planned, args.api_root, args.timeout_seconds, args.output)
        elif len(planned["results"]) == 119:
            planned["mode"] = "execute"
            planned["selection"] = select_positive(planned)
            planned["state"] = "COMPLETED"
        write_json(args.output, planned)
        print(json.dumps({
            "output": str(args.output),
            "state": planned["state"],
            "expansion_fingerprint": planned["expansion_fingerprint"],
            "reused_trajectories": 40,
            "new_trajectory_limit": 79,
            "maximum_new_judge_calls": 316,
            "new_judge_calls_executed":
                planned["budget"]["new_judge_calls_executed"],
            "selection": planned["selection"],
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

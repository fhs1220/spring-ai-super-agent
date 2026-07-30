#!/usr/bin/env python3
"""Evaluate a completed v8 precision replay without additional model calls."""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

from functions.reward.scoring import score_rollout
from prepare_v8_precision_validation import (
    FREEZE_SCHEMA_VERSION,
    canonical_sha256,
    file_sha256,
    load_object,
    write_json,
)
from replay_training_seeds import first_user_question, load_seeds


REPORT_SCHEMA_VERSION = "rl-precision-validation-report-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def verify_manifest(path: Path) -> dict[str, Any]:
    manifest = load_object(path)
    if manifest.get("schema_version") != FREEZE_SCHEMA_VERSION:
        raise ValueError("Unexpected precision validation freeze schema")
    payload = {
        key: value
        for key, value in manifest.items()
        if key != "freeze_fingerprint"
    }
    if manifest.get("freeze_fingerprint") != canonical_sha256(payload):
        raise ValueError("Precision validation freeze fingerprint changed")
    selected_path = Path(str(manifest["selected_seeds_path"]))
    plan_path = Path(str(manifest["plan_path"]))
    if file_sha256(selected_path) != manifest["selected_seeds_fingerprint"]:
        raise ValueError("Selected seed fingerprint changed")
    if file_sha256(plan_path) != manifest["plan_file_fingerprint"]:
        raise ValueError("Validation plan fingerprint changed")
    return manifest


def citation_replacement(match: re.Match[str]) -> str:
    return "".join(
        f"[来源 {value}]"
        for value in re.split(r"\s*[,，、]\s*", match.group(1))
    )


def normalize_citations(answer: str) -> str:
    patterns = (
        r"\[来源\s*(\d{1,3}(?:\s*[,，、]\s*\d{1,3})+)\]",
        r"(?:参考)?来源\s*[:：]?\s*\[(\d{1,3}"
        r"(?:\s*[,，、]\s*\d{1,3})*)\]",
        r"[（(]\s*(?:依据|参考)?来源\s*[:：]?\s*(\d{1,3}"
        r"(?:\s*[,，、]\s*\d{1,3})*)\s*[）)]",
        r"(?<!\[)(?:依据|参考)?来源\s*[:：]?\s*(\d{1,3}"
        r"(?:\s*[,，、]\s*\d{1,3})*)(?!\s*\])",
    )
    normalized = answer
    for pattern in patterns:
        normalized = re.sub(pattern, citation_replacement, normalized)
    return normalized


def last_answer(steps: list[Any], step_types: set[str], key: str) -> str | None:
    for step in reversed(steps):
        if not isinstance(step, dict) or step.get("type") not in step_types:
            continue
        output = step.get("output")
        value = output.get(key) if isinstance(output, dict) else None
        if isinstance(value, str) and value.strip():
            return normalize_citations(value.strip())
    return None


def trajectory_metrics(trajectory: dict[str, Any]) -> dict[str, Any]:
    steps = trajectory.get("steps")
    steps = steps if isinstance(steps, list) else []
    plan = next(
        (
            step for step in steps
            if isinstance(step, dict) and step.get("type") == "PLAN"
        ),
        {},
    )
    plan_output = plan.get("output")
    plan_output = plan_output if isinstance(plan_output, dict) else {}
    documents = trajectory.get("retrievedDocumentIds")
    documents = documents if isinstance(documents, list) else []
    return {
        "retrieved_document_count": len(documents),
        "retrieval_call_count": sum(
            isinstance(step, dict)
            and step.get("type") in {"RETRIEVE", "FOLLOW_UP"}
            for step in steps
        ),
        "planned_query_count": plan_output.get("queryCount", 0),
        "follow_up_rounds": sum(
            isinstance(step, dict) and step.get("type") == "FOLLOW_UP"
            for step in steps
        ),
    }


def score_answer(
    answer: str,
    seed: dict[str, Any],
    trajectory: dict[str, Any],
) -> float:
    extra = seed["rollout_extra"]
    return score_rollout(
        answer=answer,
        question=first_user_question(seed),
        solution=str(extra.get("solution") or ""),
        context=str(extra.get("solution") or ""),
        metrics=trajectory_metrics(trajectory),
        extra=extra,
    ).total


def candidate_evidence(
    trajectory: dict[str, Any],
    seed: dict[str, Any],
) -> dict[str, Any]:
    steps = trajectory.get("steps")
    steps = steps if isinstance(steps, list) else []
    draft = last_answer(steps, {"GENERATE", "SYNTHESIZE"}, "answer")
    revision = last_answer(steps, {"REVISE"}, "answer")
    if revision is None:
        revision = last_answer(steps, {"REVIEW"}, "revisedAnswer")
    selection = next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == "RLVR_SELECT"
        ),
        None,
    )
    review = next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == "REVIEW"
        ),
        None,
    )
    revise_step = next(
        (
            step for step in reversed(steps)
            if isinstance(step, dict) and step.get("type") == "REVISE"
        ),
        None,
    )
    review_output = (
        review.get("output")
        if isinstance(review, dict)
        and isinstance(review.get("output"), dict)
        else {}
    )
    revise_output = (
        revise_step.get("output")
        if isinstance(revise_step, dict)
        and isinstance(revise_step.get("output"), dict)
        else {}
    )
    selection_output = (
        selection.get("output")
        if isinstance(selection, dict)
        and isinstance(selection.get("output"), dict)
        else {}
    )
    review_contract_traced = (
        "verificationContractPassed" in review_output
        and "missingRequirements" in review_output
    )
    revise_contract_traced = (
        revise_step is None
        or (
            "verificationContractPassed" in revise_output
            and "missingRequirements" in revise_output
        )
    )
    selection_contract_traced = (
        selection is None
        or (
            "verificationContractPassed" in selection_output
            and "missingRequirements" in selection_output
        )
    )
    final_answer = normalize_citations(
        str(trajectory.get("finalAnswer") or "").strip()
    )
    evidence: dict[str, Any] = {
        "selector_evaluated": selection is not None,
        "draft_available": draft is not None,
        "revision_available": revision is not None,
        "draft_rlvr": (
            score_answer(draft, seed, trajectory)
            if draft is not None else None
        ),
        "revision_rlvr": (
            score_answer(revision, seed, trajectory)
            if revision is not None else None
        ),
        "final_rlvr": score_answer(final_answer, seed, trajectory),
        "draft_contract_passed": review_output.get(
            "verificationContractPassed"
        ),
        "contract_forced_revision": (
            review_output.get("verificationContractPassed") is False
            and revise_step is not None
        ),
        "selected_contract_passed": (
            selection_output.get("verificationContractPassed")
            if selection is not None
            else review_output.get("verificationContractPassed")
        ),
        "verification_contract_trace_valid": (
            review_contract_traced
            and revise_contract_traced
            and selection_contract_traced
        ),
    }
    if selection is None:
        evidence.update({
            "selected_candidate": "NO_SELECTION",
            "selector_trace_valid": revision is None,
            "rlvr_regressive_selection": False,
        })
        return evidence
    output = selection_output
    selected = output.get("selectedCandidate")
    expected = draft if selected == "DRAFT" else revision
    trace_valid = (
        selected in {"DRAFT", "REVISED"}
        and isinstance(expected, str)
        and expected == final_answer
        and output.get("contractNonDegrading") is True
    )
    candidate_scores = [
        value for value in (
            evidence["draft_rlvr"],
            evidence["revision_rlvr"],
        )
        if isinstance(value, float)
    ]
    evidence.update({
        "selected_candidate": selected,
        "selector_version": (
            selection.get("input", {}).get("selectorVersion")
            if isinstance(selection.get("input"), dict) else None
        ),
        "selector_trace_valid": trace_valid,
        "rlvr_regressive_selection": (
            bool(candidate_scores)
            and evidence["final_rlvr"] + 1e-9 < max(candidate_scores)
        ),
        "best_candidate_rlvr": max(candidate_scores)
        if candidate_scores else None,
    })
    return evidence


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * probability))
    return ordered[max(0, min(index, len(ordered) - 1))]


def paired_bootstrap(
    deltas: list[float],
    samples: int,
    seed: int = 20260730,
) -> list[float]:
    generator = random.Random(seed)
    means = []
    for _ in range(samples):
        means.append(
            sum(generator.choice(deltas) for _ in deltas) / len(deltas)
        )
    return [
        round(percentile(means, 0.025), 6),
        round(percentile(means, 0.975), 6),
    ]


def evaluate(
    manifest_path: Path,
    replay_path: Path,
    trajectories_path: Path,
) -> dict[str, Any]:
    manifest = verify_manifest(manifest_path)
    replay = load_object(replay_path)
    if replay.get("batch_id") != manifest["batch_id"]:
        raise ValueError("Replay batch identity changed")
    if replay.get("policy_version") != manifest["candidate_policy_version"]:
        raise ValueError("Replay policy identity changed")
    if replay.get("plan_fingerprint") != manifest["plan_fingerprint"]:
        raise ValueError("Replay plan identity changed")
    results = replay.get("results")
    if (
        not isinstance(results, list)
        or len(results) != manifest["planned_agent_runs"]
        or any(result.get("status") != "COMPLETED" for result in results)
    ):
        raise ValueError("Precision replay is not fully completed")
    seeds = load_seeds(Path(str(manifest["selected_seeds_path"])))
    seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
    baseline = {
        item["seed_id"]: float(item["baseline_mean_rlvr"])
        for item in manifest["historical_evidence"]
    }
    by_seed: dict[str, list[float]] = defaultdict(list)
    candidate_rows = []
    for result in results:
        seed_id = result["seed_id"]
        trajectory_id = result["trajectory_id"]
        if seed_id not in seeds_by_id:
            raise ValueError(f"Replay contains unknown seed {seed_id}")
        trajectory = load_object(trajectories_path / f"{trajectory_id}.json")
        evidence = candidate_evidence(trajectory, seeds_by_id[seed_id])
        recorded = float(result["rlvr"]["total"])
        if abs(evidence["final_rlvr"] - recorded) > 1e-6:
            raise ValueError(
                f"RLVR reconstruction mismatch for {trajectory_id}"
            )
        by_seed[seed_id].append(recorded)
        candidate_rows.append({
            "seed_id": seed_id,
            "round": result["round"],
            "trajectory_id": trajectory_id,
            **evidence,
        })
    if any(len(values) != manifest["rounds"] for values in by_seed.values()):
        raise ValueError("Replay does not contain all rounds per seed")
    candidate_means = {
        seed_id: sum(values) / len(values)
        for seed_id, values in by_seed.items()
    }
    deltas = [
        candidate_means[seed_id] - baseline[seed_id]
        for seed_id in sorted(candidate_means)
    ]
    candidate_average = sum(
        float(result["rlvr"]["total"]) for result in results
    ) / len(results)
    baseline_average = float(
        manifest["historical_baseline"]["average_rlvr"]
    )
    mean_delta = candidate_average - baseline_average
    selector_rows = [
        row for row in candidate_rows if row["selector_evaluated"]
    ]
    contract_trace_rows = [
        row for row in candidate_rows
        if row["verification_contract_trace_valid"]
    ]
    final_contract_passed = sum(
        row["selected_contract_passed"] is True
        for row in candidate_rows
    )
    route_mismatches = sum(
        result.get("route_expectation_matched") is not True
        for result in results
    )
    timeouts = sum(
        int(result.get("telemetry", {}).get("timeout_count") or 0)
        for result in results
    )
    violations = sum(bool(result["rlvr"].get("violations")) for result in results)
    regressions = sum(
        row["rlvr_regressive_selection"] for row in selector_rows
    )
    invalid_selector_traces = sum(
        not row["selector_trace_valid"] for row in selector_rows
    )
    invalid_selector_versions = sum(
        row.get("selector_version") != manifest["selector_version"]
        for row in selector_rows
    )
    gates = manifest["evaluation_gates"]
    interval = paired_bootstrap(
        deltas,
        int(gates["bootstrap_samples"]),
    )
    checks = {
        "rlvr_average_at_least_minimum":
            candidate_average >= float(gates["minimum_rlvr_average"]),
        "mean_delta_non_negative":
            mean_delta >= float(gates["minimum_mean_delta"]),
        "paired_ci_low_non_inferior":
            interval[0] >= float(gates["minimum_paired_ci_low"]),
        "route_mismatches_within_limit":
            route_mismatches <= int(gates["maximum_route_mismatches"]),
        "timeouts_within_limit":
            timeouts <= int(gates["maximum_timeouts"]),
        "rlvr_violations_within_limit":
            violations <= int(gates["maximum_rlvr_violations"]),
        "rlvr_regressive_selections_within_limit":
            regressions
            <= int(gates["maximum_rlvr_regressive_selections"]),
        "selector_trace_integrity": invalid_selector_traces == 0,
    }
    if gates.get("require_verification_contract_trace") is True:
        checks["verification_contract_trace_integrity"] = (
            len(contract_trace_rows) == len(candidate_rows)
        )
    if "minimum_final_contract_pass_rate" in gates:
        checks["final_contract_pass_rate_at_least_minimum"] = (
            final_contract_passed / len(candidate_rows)
            >= float(gates["minimum_final_contract_pass_rate"])
        )
    checks["selector_version_integrity"] = invalid_selector_versions == 0
    return {
        "report_schema_version": REPORT_SCHEMA_VERSION,
        "validation_id": manifest["validation_id"],
        "batch_id": manifest["batch_id"],
        "candidate_policy_version": manifest["candidate_policy_version"],
        "baseline_policy_version": manifest["baseline_policy_version"],
        "freeze_fingerprint": manifest["freeze_fingerprint"],
        "replay_fingerprint": file_sha256(replay_path),
        "decision": (
            "PRECISION_UPGRADE_VALIDATED"
            if all(checks.values())
            else "NOT_PROMOTED"
        ),
        "metrics": {
            "seed_count": len(by_seed),
            "trajectory_count": len(results),
            "baseline_average_rlvr": round(baseline_average, 6),
            "candidate_average_rlvr": round(candidate_average, 6),
            "mean_delta": round(mean_delta, 6),
            "paired_bootstrap_95_percent_ci": interval,
            "selector_evaluated_trajectories": len(selector_rows),
            "selected_draft_count": sum(
                row["selected_candidate"] == "DRAFT"
                for row in selector_rows
            ),
            "selected_revision_count": sum(
                row["selected_candidate"] == "REVISED"
                for row in selector_rows
            ),
            "rlvr_regressive_selections": regressions,
            "invalid_selector_traces": invalid_selector_traces,
            "invalid_selector_versions": invalid_selector_versions,
            "draft_direct_contract_pass_count": sum(
                row["draft_contract_passed"] is True
                for row in candidate_rows
            ),
            "contract_forced_revision_count": sum(
                row["contract_forced_revision"]
                for row in candidate_rows
            ),
            "final_contract_pass_count": final_contract_passed,
            "final_contract_pass_rate": round(
                final_contract_passed / len(candidate_rows),
                6,
            ),
            "verification_contract_trace_valid_count":
                len(contract_trace_rows),
            "route_mismatches": route_mismatches,
            "timeouts": timeouts,
            "rlvr_violation_trajectories": violations,
        },
        "checks": checks,
        "candidate_evidence": candidate_rows,
        "model_api_calls_during_evaluation": 0,
        "billable_operations_during_evaluation": 0,
    }


def main() -> int:
    args = parse_args()
    try:
        report = evaluate(
            args.manifest,
            args.replay,
            args.trajectories,
        )
        write_json(args.output, report)
        print(json.dumps({
            "decision": report["decision"],
            "metrics": report["metrics"],
            "checks": report["checks"],
            "report": str(args.output),
            "model_api_calls_during_evaluation": 0,
            "billable_operations_during_evaluation": 0,
        }, ensure_ascii=False, indent=2))
        return 0 if report["decision"] == "PRECISION_UPGRADE_VALIDATED" else 1
    except (KeyError, TypeError, ValueError) as exception:
        print(f"Precision validation evaluation failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

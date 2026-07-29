#!/usr/bin/env python3
"""Plan and execute the scoped Stage 4 four-Judge assessment batch.

Dry-run is the default. Real Judge calls require both ``--execute`` and
``AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true``. The passing Stage 3 collection gate
is the only source of eligible seeds, with one trajectory selected per seed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict, deque
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent-rl-stage4-judge-plan-v1"
REPLAY_SCHEMA_VERSION = "agent-rl-seed-replay-v2"
QUALIFICATION_SCHEMA_VERSION = "paired-high-confidence-v1"
JUDGE_DIMENSIONS = (
    "INSTRUCTION_FOLLOWING",
    "ACTIONABILITY",
    "LOGICAL_CONSISTENCY",
    "CRITICAL_REVIEW",
)
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{3,64}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--batch-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--human-sample-size", type=int, default=30)
    parser.add_argument("--api-root", default="http://127.0.0.1:8123/api")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--input-price-per-million-cny", type=float, default=0.3)
    parser.add_argument("--output-price-per-million-cny", type=float, default=0.6)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Call the four-Judge endpoint for the frozen trajectory whitelist.",
    )
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


def load_seeds(path: Path) -> dict[str, dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise ValueError(f"Seed file is unreadable: {path}: {exception}") from exception
    seeds: dict[str, dict[str, Any]] = {}
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            seed = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"{path}:{line_number} is not valid JSON: {exception}"
            ) from exception
        seed_id = seed.get("seed_id") if isinstance(seed, dict) else None
        if not isinstance(seed_id, str) or not seed_id:
            raise ValueError(f"{path}:{line_number} has no seed_id")
        if seed_id in seeds:
            raise ValueError(f"{path}:{line_number} duplicates {seed_id}")
        seeds[seed_id] = seed
    return seeds


def canonical_sha256(value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def normalize_batch_id(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_-]+", "-", value.strip()).strip("-")
    if not SAFE_ID.fullmatch(normalized):
        raise ValueError("batch-id must normalize to 3-64 safe characters")
    return normalized


def validate_replay(replay: dict[str, Any]) -> tuple[list[str], float, int]:
    if replay.get("schema_version") != REPLAY_SCHEMA_VERSION:
        raise ValueError(f"replay schema_version must be {REPLAY_SCHEMA_VERSION}")
    gate = replay.get("replay_gate")
    if not isinstance(gate, dict) or gate.get("profile") != "collection":
        raise ValueError("replay must contain the collection gate")
    if gate.get("passed") is not True:
        raise ValueError("replay collection gate did not pass")
    planned = replay.get("planned_agent_runs")
    completed = replay.get("completed_agent_runs")
    results = replay.get("results")
    if (
        not isinstance(planned, int)
        or planned < 1
        or completed != planned
        or not isinstance(results, list)
        or len(results) != planned
    ):
        raise ValueError("replay is not a complete frozen execution")
    collection = replay.get("collection_summary")
    if (
        not isinstance(collection, dict)
        or collection.get("schema_version") != QUALIFICATION_SCHEMA_VERSION
    ):
        raise ValueError("replay has no supported qualification summary")
    qualified = collection.get("qualified_seed_ids")
    minimum_rlvr = collection.get("minimum_rlvr_per_trajectory")
    required_rounds = collection.get("required_rounds_per_seed")
    if (
        not isinstance(qualified, list)
        or not qualified
        or len(set(qualified)) != len(qualified)
        or collection.get("qualified_seed_count") != len(qualified)
        or not isinstance(minimum_rlvr, (int, float))
        or not isinstance(required_rounds, int)
        or required_rounds < 1
    ):
        raise ValueError("replay qualification summary is invalid")
    return qualified, float(minimum_rlvr), required_rounds


def first_question(seed: dict[str, Any]) -> str:
    messages = seed.get("messages")
    if not isinstance(messages, list):
        raise ValueError(f"seed {seed.get('seed_id')} has no messages")
    for message in messages:
        if (
            isinstance(message, dict)
            and message.get("role") == "user"
            and isinstance(message.get("content"), str)
            and message["content"].strip()
        ):
            return message["content"].strip()
    raise ValueError(f"seed {seed.get('seed_id')} has no user question")


def seed_metadata(seed: dict[str, Any]) -> tuple[list[str], str]:
    extra = seed.get("rollout_extra")
    extra = extra if isinstance(extra, dict) else {}
    route = extra.get("route_expectation")
    route = route if isinstance(route, dict) else {}
    domains = route.get("detected_domains")
    domains = (
        [str(value) for value in domains]
        if isinstance(domains, list)
        else []
    )
    task_group = str(extra.get("task_group") or "unknown")
    return domains, task_group.rsplit(":", 1)[-1]


def validate_candidate(
    result: dict[str, Any],
    *,
    policy_version: str,
    minimum_rlvr: float,
) -> float:
    rlvr = result.get("rlvr")
    total = rlvr.get("total") if isinstance(rlvr, dict) else None
    if (
        result.get("status") != "COMPLETED"
        or result.get("policy_version") != policy_version
        or result.get("route_expectation_matched") is not True
        or not isinstance(total, (int, float))
        or float(total) < minimum_rlvr
        or rlvr.get("hard_gate_passed") is not True
        or rlvr.get("violations") != []
    ):
        raise ValueError(
            f"qualified seed has an ineligible result: {result.get('seed_id')}"
        )
    return float(total)


def load_trajectory(
    directory: Path,
    trajectory_id: str,
    *,
    expected_policy: str,
    expected_question: str,
) -> dict[str, Any]:
    trajectory = load_object(directory / f"{trajectory_id}.json")
    if (
        trajectory.get("trajectoryId") != trajectory_id
        or trajectory.get("policyVersion") != expected_policy
        or str(trajectory.get("question") or "").strip() != expected_question
        or trajectory.get("status") != "COMPLETED"
    ):
        raise ValueError(f"trajectory evidence mismatch: {trajectory_id}")
    return trajectory


def build_plan(
    replay: dict[str, Any],
    seeds: dict[str, dict[str, Any]],
    trajectory_directory: Path,
) -> list[dict[str, Any]]:
    qualified, minimum_rlvr, required_rounds = validate_replay(replay)
    policy_version = replay.get("policy_version")
    if not isinstance(policy_version, str) or not policy_version:
        raise ValueError("replay has no policy_version")
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in replay["results"]:
        if isinstance(result, dict) and result.get("seed_id") in qualified:
            grouped[result["seed_id"]].append(result)
    plan: list[dict[str, Any]] = []
    for seed_id in qualified:
        seed = seeds.get(seed_id)
        if seed is None:
            raise ValueError(f"qualified seed is missing from seed file: {seed_id}")
        candidates = grouped.get(seed_id, [])
        if len(candidates) != required_rounds:
            raise ValueError(f"qualified seed has incomplete rounds: {seed_id}")
        scored = [
            (
                validate_candidate(
                    result,
                    policy_version=policy_version,
                    minimum_rlvr=minimum_rlvr,
                ),
                result,
            )
            for result in candidates
        ]
        scored.sort(
            key=lambda pair: (
                -pair[0],
                int(pair[1].get("round") or 0),
                str(pair[1].get("trajectory_id") or ""),
            )
        )
        selected_score, selected = scored[0]
        trajectory_id = selected.get("trajectory_id")
        if not isinstance(trajectory_id, str) or not trajectory_id:
            raise ValueError(f"selected result has no trajectory_id: {seed_id}")
        question = first_question(seed)
        trajectory = load_trajectory(
            trajectory_directory,
            trajectory_id,
            expected_policy=policy_version,
            expected_question=question,
        )
        domains, request_type = seed_metadata(seed)
        answer_chars = min(len(str(trajectory.get("finalAnswer") or "")), 12_000)
        question_chars = min(len(question), 12_000)
        plan.append(
            {
                "seed_id": seed_id,
                "trajectory_id": trajectory_id,
                "selected_round": selected.get("round"),
                "execution_mode": selected.get("execution_mode"),
                "rlvr": round(selected_score, 6),
                "pair_minimum_rlvr": round(min(score for score, _ in scored), 6),
                "pair_rlvr_gap": round(
                    max(score for score, _ in scored)
                    - min(score for score, _ in scored),
                    6,
                ),
                "had_recovery": any(
                    isinstance(result.get("recovery"), dict)
                    for _, result in scored
                ),
                "domains": domains,
                "request_type": request_type,
                "question_fingerprint": canonical_sha256(question),
                "question_chars": question_chars,
                "answer_chars": answer_chars,
            }
        )
    return plan


def human_review_sample(
    plan: list[dict[str, Any]],
    requested_size: int,
) -> list[dict[str, Any]]:
    if requested_size < 1 or requested_size > len(plan):
        raise ValueError("human-sample-size must be between 1 and plan size")
    ranked = sorted(
        plan,
        key=lambda item: (
            not item["had_recovery"],
            item["pair_minimum_rlvr"],
            -item["pair_rlvr_gap"],
            item["seed_id"],
        ),
    )
    buckets: dict[tuple[str, str], deque[dict[str, Any]]] = defaultdict(deque)
    for item in ranked:
        buckets[
            (item["request_type"], item["execution_mode"])
        ].append(item)
    selected: list[dict[str, Any]] = []
    bucket_names = sorted(buckets)
    while len(selected) < requested_size:
        made_progress = False
        for name in bucket_names:
            if buckets[name] and len(selected) < requested_size:
                selected.append(buckets[name].popleft())
                made_progress = True
        if not made_progress:
            break
    sample = []
    for rank, item in enumerate(selected, start=1):
        reasons = ["stratified_request_type"]
        if item["had_recovery"]:
            reasons.insert(0, "recovery_audit")
        reasons.extend(["rlvr_boundary", "paired_round_variance"])
        sample.append(
            {
                "rank": rank,
                "seed_id": item["seed_id"],
                "trajectory_id": item["trajectory_id"],
                "execution_mode": item["execution_mode"],
                "domains": item["domains"],
                "request_type": item["request_type"],
                "priority_reasons": reasons,
            }
        )
    return sample


def budget(
    plan: list[dict[str, Any]],
    input_price: float,
    output_price: float,
) -> dict[str, Any]:
    if input_price < 0 or output_price < 0:
        raise ValueError("token prices must not be negative")
    input_tokens = 0
    output_tokens = 0
    for item in plan:
        prompt_chars = item["question_chars"] + item["answer_chars"] + 600
        input_tokens += math.ceil(prompt_chars * 1.25) * len(JUDGE_DIMENSIONS)
        output_tokens += 200 * len(JUDGE_DIMENSIONS)
    estimated_cost = (
        input_tokens * input_price + output_tokens * output_price
    ) / 1_000_000
    return {
        "maximum_trajectory_attempts": len(plan),
        "maximum_judge_calls": len(plan) * len(JUDGE_DIMENSIONS),
        "estimated_input_tokens_upper_bound": input_tokens,
        "estimated_output_tokens_upper_bound": output_tokens,
        "input_price_per_million_tokens_cny": input_price,
        "output_price_per_million_tokens_cny": output_price,
        "estimated_cost_upper_bound_cny": round(estimated_cost, 8),
        "note": "字符换算的规划上界，不是云账单或服务端硬限额",
    }


def build_manifest(
    replay: dict[str, Any],
    plan: list[dict[str, Any]],
    batch_id: str,
    sample_size: int,
    input_price: float,
    output_price: float,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "batch_id": batch_id,
        "state": "PLANNED",
        "mode": "dry-run",
        "source_replay": {
            "batch_id": replay["batch_id"],
            "plan_fingerprint": replay["plan_fingerprint"],
            "policy_version": replay["policy_version"],
            "qualification_schema_version": QUALIFICATION_SCHEMA_VERSION,
            "qualified_seed_count": len(plan),
        },
        "selection_contract": {
            "version": "highest-rlvr-per-qualified-question-v1",
            "eligible_source": "collection_summary.qualified_seed_ids",
            "representative_rule": (
                "highest RLVR; tie by lower round then trajectory_id"
            ),
            "selected_trajectory_count": len(plan),
            "judge_dimensions": list(JUDGE_DIMENSIONS),
        },
        "plan_fingerprint": canonical_sha256(plan),
        "plan": plan,
        "budget": budget(plan, input_price, output_price),
        "human_review": {
            "contract_version": "risk-stratified-human-anchor-v1",
            "requested_sample_size": sample_size,
            "sample": human_review_sample(plan, sample_size),
        },
        "execution_results": [],
        "execution_summary": {
            "attempted_trajectory_count": 0,
            "complete_panel_count": 0,
            "incomplete_panel_count": 0,
            "existing_assessment_count": 0,
            "new_assessment_count": 0,
            "passed": False,
        },
    }


def write_manifest(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def load_resume(path: Path, planned: dict[str, Any]) -> dict[str, Any]:
    if not path.exists():
        return planned
    existing = load_object(path)
    for field in ("schema_version", "batch_id", "plan_fingerprint"):
        if existing.get(field) != planned.get(field):
            raise ValueError(f"existing Stage 4 manifest {field} mismatch")
    if existing.get("source_replay") != planned.get("source_replay"):
        raise ValueError("existing Stage 4 source replay mismatch")
    results = existing.get("execution_results")
    if not isinstance(results, list):
        raise ValueError("existing execution_results must be an array")
    planned_ids = [item["trajectory_id"] for item in planned["plan"]]
    result_ids = [item.get("trajectory_id") for item in results]
    if result_ids != planned_ids[:len(result_ids)]:
        raise ValueError("existing execution results are not the plan prefix")
    planned["execution_results"] = results
    if results:
        planned["mode"] = existing.get("mode", planned["mode"])
        planned["state"] = existing.get("state", planned["state"])
    return planned


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


def validate_assessment(
    assessment: dict[str, Any],
    item: dict[str, Any],
    policy_version: str,
) -> dict[str, Any]:
    if (
        assessment.get("trajectoryId") != item["trajectory_id"]
        or assessment.get("policyVersion") != policy_version
    ):
        raise ValueError(
            f"Judge response identity mismatch: {item['trajectory_id']}"
        )
    judge_count = assessment.get("judgeCount")
    if not isinstance(judge_count, int) or not 0 <= judge_count <= 4:
        raise ValueError(f"Judge response has invalid judgeCount: {judge_count}")
    return {
        "seed_id": item["seed_id"],
        "trajectory_id": item["trajectory_id"],
        "judge_count": judge_count,
        "complete_panel": judge_count == len(JUDGE_DIMENSIONS),
        "supervision_label": assessment.get("supervisionLabel"),
        "training_decision": assessment.get("trainingDecision"),
        "total_reward": assessment.get("totalReward"),
        "confidence": assessment.get("confidence"),
        "judge_agreement": assessment.get("judgeAgreement"),
        "assessment_source": assessment.get("_assessment_source"),
    }


def add_execution_summary(manifest: dict[str, Any]) -> None:
    results = manifest["execution_results"]
    complete = sum(1 for result in results if result["complete_panel"])
    existing = sum(
        1 for result in results
        if result["assessment_source"] == "existing"
    )
    positive = sum(
        1 for result in results
        if result["training_decision"] == "POSITIVE"
    )
    holdout = sum(
        1 for result in results
        if result["training_decision"] == "HOLDOUT"
    )
    manifest["execution_summary"] = {
        "attempted_trajectory_count": len(results),
        "complete_panel_count": complete,
        "incomplete_panel_count": len(results) - complete,
        "existing_assessment_count": existing,
        "new_assessment_count": len(results) - existing,
        "positive_count": positive,
        "holdout_count": holdout,
        "auto_approval_rate": (
            round(positive / len(results), 6) if results else 0.0
        ),
        "passed": (
            len(results) == len(manifest["plan"])
            and complete == len(manifest["plan"])
        ),
    }
    if len(results) == len(manifest["plan"]):
        manifest["state"] = (
            "COMPLETED"
            if manifest["execution_summary"]["passed"]
            else "REVIEW_REQUIRED"
        )
        manifest["human_review"]["post_judge_contract_version"] = (
            "judge-risk-stratified-human-anchor-v1"
        )
        manifest["human_review"]["post_judge_sample"] = (
            post_judge_human_review_sample(
                manifest["plan"],
                results,
                manifest["human_review"]["requested_sample_size"],
            )
        )


def post_judge_human_review_sample(
    plan: list[dict[str, Any]],
    results: list[dict[str, Any]],
    requested_size: int,
) -> list[dict[str, Any]]:
    if requested_size < 1 or requested_size > len(plan):
        raise ValueError("human review size must be between 1 and plan size")
    results_by_id = {
        result["trajectory_id"]: result
        for result in results
        if isinstance(result.get("trajectory_id"), str)
    }
    if len(results_by_id) != len(plan):
        raise ValueError("post-Judge human review requires one result per plan item")
    candidates = []
    for item in plan:
        result = results_by_id.get(item["trajectory_id"])
        if result is None:
            raise ValueError(
                f"post-Judge result is missing: {item['trajectory_id']}"
            )
        candidates.append({**item, **result})
    ranked = sorted(
        candidates,
        key=lambda item: (
            item["training_decision"] != "EXCLUDED",
            not item["had_recovery"],
            float(item.get("confidence") or 0.0),
            float(item.get("judge_agreement") or 0.0),
            abs(float(item.get("total_reward") or 0.0) - 0.75),
            item["seed_id"],
        ),
    )
    buckets: dict[tuple[str, str], deque[dict[str, Any]]] = defaultdict(deque)
    for item in ranked:
        buckets[
            (item["request_type"], item["execution_mode"])
        ].append(item)
    selected: list[dict[str, Any]] = []
    bucket_names = sorted(buckets)
    while len(selected) < requested_size:
        made_progress = False
        for name in bucket_names:
            if buckets[name] and len(selected) < requested_size:
                selected.append(buckets[name].popleft())
                made_progress = True
        if not made_progress:
            break
    sample = []
    for rank, item in enumerate(selected, start=1):
        reasons = [
            "judge_uncertainty",
            "stratified_request_type",
            "stratified_execution_mode",
        ]
        if item["training_decision"] == "EXCLUDED":
            reasons.insert(0, "hard_gate_excluded")
        if item["had_recovery"]:
            reasons.insert(0, "recovery_audit")
        sample.append({
            "rank": rank,
            "seed_id": item["seed_id"],
            "trajectory_id": item["trajectory_id"],
            "execution_mode": item["execution_mode"],
            "domains": item["domains"],
            "request_type": item["request_type"],
            "training_decision": item["training_decision"],
            "total_reward": item["total_reward"],
            "confidence": item["confidence"],
            "judge_agreement": item["judge_agreement"],
            "priority_reasons": reasons,
        })
    return sample


def require_execution_authorization() -> None:
    if os.environ.get("AGENT_RL_JUDGE_ALLOW_MODEL_CALLS", "").lower() != "true":
        raise ValueError(
            "--execute also requires "
            "AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true because Judge calls a model"
        )


def execute(
    manifest: dict[str, Any],
    api_root: str,
    timeout: int,
    output: Path,
) -> None:
    metrics_url = api_root.rstrip("/") + "/agent-rl/alignment/metrics"
    _, metrics_status = request_json(metrics_url, timeout)
    if metrics_status != 200:
        raise ValueError(
            "Agent RL management API preflight failed; start the trusted local "
            "backend with AGENT_RL_API_ENABLED=true"
        )
    policy_version = manifest["source_replay"]["policy_version"]
    completed = len(manifest["execution_results"])
    manifest["mode"] = "execute"
    for item in manifest["plan"][completed:]:
        encoded = urllib.parse.quote(item["trajectory_id"], safe="")
        url = (
            api_root.rstrip("/")
            + "/agent-rl/alignment/assessments/"
            + encoded
        )
        assessment, status = request_json(url, timeout)
        source = "existing"
        if status == 404:
            assessment, _ = request_json(url, timeout, method="POST")
            source = "new"
        if assessment is None:
            raise ValueError(f"Judge API returned no assessment: {url}")
        assessment["_assessment_source"] = source
        manifest["execution_results"].append(
            validate_assessment(assessment, item, policy_version)
        )
        add_execution_summary(manifest)
        write_manifest(output, manifest)


def main() -> int:
    args = parse_args()
    try:
        replay = load_object(args.replay)
        seeds = load_seeds(args.seeds)
        plan = build_plan(replay, seeds, args.trajectories)
        manifest = build_manifest(
            replay,
            plan,
            normalize_batch_id(args.batch_id),
            args.human_sample_size,
            args.input_price_per_million_cny,
            args.output_price_per_million_cny,
        )
        manifest = load_resume(args.output, manifest)
        if args.execute:
            require_execution_authorization()
            execute(manifest, args.api_root, args.timeout_seconds, args.output)
        add_execution_summary(manifest)
        write_manifest(args.output, manifest)
        print(json.dumps({
            "batch_id": manifest["batch_id"],
            "mode": manifest["mode"],
            "state": manifest["state"],
            "plan_fingerprint": manifest["plan_fingerprint"],
            "selected_trajectory_count": len(manifest["plan"]),
            "maximum_judge_calls": manifest["budget"]["maximum_judge_calls"],
            "human_sample_size": len(manifest["human_review"]["sample"]),
            "execution_summary": manifest["execution_summary"],
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

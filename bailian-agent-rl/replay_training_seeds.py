#!/usr/bin/env python3
"""Replay trajectory seeds against the local Agentic RAG endpoint.

Dry-run is the default. Real model calls require both ``--execute`` and
``AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true``.  The first completed trajectory is
checked against ``--policy-version`` before later calls are allowed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


SCRIPT_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_ROOT.parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from functions.reward.scoring import score_rollout  # noqa: E402


SEED_SCHEMA_VERSION = "agent-rl-trajectory-seed-v2"
SUPPORTED_SEED_SCHEMA_VERSIONS = {
    "agent-rl-trajectory-seed-v1",
    SEED_SCHEMA_VERSION,
}
REPLAY_SCHEMA_VERSION = "agent-rl-seed-replay-v2"
DATASET_ROLE = "trajectory_seed_only"
VALID_EXECUTION_MODES = {"SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT"}
RESUMABLE_RUN_STATUSES = {"FAILED", "CANCELLED", "RECOVERY_REQUIRED"}
DEFAULT_ROUTING_CONTRACT = (
    PROJECT_ROOT
    / "src/main/resources/multiagent/deterministic-routing-contract-v1.json"
)


def load_routing_contract(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(
            f"Routing contract is unreadable: {path}: {exception}"
        ) from exception
    if not isinstance(value, dict):
        raise ValueError("Routing contract must be an object")
    keywords = value.get("domain_keywords")
    order = value.get("domain_selection_order")
    if (
        not isinstance(value.get("schema_version"), str)
        or not isinstance(keywords, dict)
        or not keywords
        or not isinstance(order, list)
        or set(order) != set(keywords)
        or value.get("fallback_domain") not in keywords
        or not isinstance(value.get("minimum_domains"), int)
        or value["minimum_domains"] < 1
        or not isinstance(value.get("max_agents"), int)
        or value["max_agents"] < 1
    ):
        raise ValueError(f"Routing contract is invalid: {path}")
    return value


ROUTING_CONTRACT = load_routing_contract(DEFAULT_ROUTING_CONTRACT)
VALID_DOMAINS = set(ROUTING_CONTRACT["domain_keywords"])
DOMAIN_SELECTION_ORDER = tuple(
    ROUTING_CONTRACT["domain_selection_order"]
)
ROUTER_CONTRACT_VERSION = ROUTING_CONTRACT["schema_version"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--api-root", default="http://127.0.0.1:8123/api")
    parser.add_argument("--batch-id", required=True)
    parser.add_argument("--policy-version")
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=320)
    parser.add_argument("--minimum-rlvr-average", type=float, default=0.70)
    parser.add_argument("--maximum-route-mismatches", type=int, default=0)
    parser.add_argument("--maximum-timeouts", type=int, default=0)
    parser.add_argument("--maximum-rlvr-violations", type=int, default=0)
    parser.add_argument(
        "--minimum-qualified-seeds",
        type=int,
        help=(
            "Enable collection mode and require this many unique seeds to have "
            "all planned rounds pass the per-trajectory qualification rules."
        ),
    )
    parser.add_argument(
        "--minimum-qualified-rlvr",
        type=float,
        default=0.70,
        help="Minimum RLVR score for every trajectory of a qualified seed.",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Perform real local Agentic RAG calls, which may incur model cost.",
    )
    return parser.parse_args()


def normalize_batch_id(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_-]+", "-", value.strip()).strip("-")
    if not 3 <= len(normalized) <= 32:
        raise ValueError("batch-id must normalize to 3-32 safe characters")
    return normalized


def load_seeds(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as exception:
        raise ValueError(f"Seed file does not exist: {path}") from exception
    values: list[dict[str, Any]] = []
    seed_ids: set[str] = set()
    questions: set[str] = set()
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"{path}:{line_number} is not valid JSON: {exception}"
            ) from exception
        validate_seed(value, path, line_number)
        seed_id = value["seed_id"]
        question = first_user_question(value)
        if seed_id in seed_ids:
            raise ValueError(f"{path}:{line_number} duplicates seed_id {seed_id}")
        if question in questions:
            raise ValueError(f"{path}:{line_number} duplicates a question")
        seed_ids.add(seed_id)
        questions.add(question)
        values.append(value)
    if not values:
        raise ValueError("Seed file must not be empty")
    return values


def validate_seed(value: Any, path: Path, line_number: int) -> None:
    prefix = f"{path}:{line_number}"
    if not isinstance(value, dict):
        raise ValueError(f"{prefix} must be an object")
    schema_version = value.get("schema_version")
    if schema_version not in SUPPORTED_SEED_SCHEMA_VERSIONS:
        raise ValueError(f"{prefix} has an unsupported schema_version")
    if value.get("dataset_role") != DATASET_ROLE:
        raise ValueError(f"{prefix} must be marked {DATASET_ROLE}")
    seed_id = value.get("seed_id")
    if not isinstance(seed_id, str) or not re.fullmatch(r"seed-[a-f0-9]{20}", seed_id):
        raise ValueError(f"{prefix} has an invalid seed_id")
    first_user_question(value)
    rollout_extra = value.get("rollout_extra")
    if not isinstance(rollout_extra, dict):
        raise ValueError(f"{prefix} rollout_extra must be an object")
    guard = rollout_extra.get("benchmark_guard")
    if not isinstance(guard, dict) or guard.get("overlap") is not False:
        raise ValueError(f"{prefix} did not pass the benchmark guard")
    if schema_version == SEED_SCHEMA_VERSION:
        validate_route_expectation(rollout_extra, prefix)


def validate_route_expectation(
    rollout_extra: dict[str, Any],
    prefix: str,
) -> None:
    expectation = rollout_extra.get("route_expectation")
    if not isinstance(expectation, dict):
        raise ValueError(f"{prefix} route_expectation must be an object")
    mode = expectation.get("execution_mode")
    if mode not in VALID_EXECUTION_MODES:
        raise ValueError(f"{prefix} has an invalid expected execution mode")
    domains = expectation.get("detected_domains")
    if (
        not isinstance(domains, list)
        or not domains
        or any(not isinstance(domain, str) or not domain for domain in domains)
    ):
        raise ValueError(f"{prefix} expected domains must be a non-empty array")
    if len(set(domains)) != len(domains) or not set(domains).issubset(
        VALID_DOMAINS
    ):
        raise ValueError(f"{prefix} has invalid or duplicate expected domains")
    minimum_domains = ROUTING_CONTRACT["minimum_domains"]
    max_agents = ROUTING_CONTRACT["max_agents"]
    if mode == "SINGLE_AGENT" and len(domains) >= minimum_domains:
        raise ValueError(f"{prefix} single-Agent seed has too many domains")
    if mode == "ADAPTIVE_MULTI_AGENT" and len(domains) < minimum_domains:
        raise ValueError(f"{prefix} multi-Agent seed has too few domains")
    selected = expectation.get("selected_domains")
    if (
        not isinstance(selected, list)
        or len(selected) > max_agents
        or len(set(selected)) != len(selected)
        or not set(selected).issubset(domains)
    ):
        raise ValueError(f"{prefix} has invalid selected domains")
    expected_selected = (
        [
            domain
            for domain in DOMAIN_SELECTION_ORDER
            if domain in domains
        ][:ROUTING_CONTRACT["max_agents"]]
        if mode == "ADAPTIVE_MULTI_AGENT"
        else []
    )
    if selected != expected_selected:
        raise ValueError(
            f"{prefix} selected domains do not match the router contract"
        )
    if expectation.get("minimum_domains") != minimum_domains:
        raise ValueError(
            f"{prefix} requires minimum_domains={minimum_domains}"
        )
    if expectation.get("max_agents") != max_agents:
        raise ValueError(f"{prefix} requires max_agents={max_agents}")
    if expectation.get("router_contract") != ROUTER_CONTRACT_VERSION:
        raise ValueError(f"{prefix} has an unsupported router contract")
    provenance = rollout_extra.get("source_provenance")
    if not isinstance(provenance, list) or not provenance:
        raise ValueError(f"{prefix} source_provenance must be a non-empty array")
    fingerprints = {
        item.get("content_sha256")
        for item in provenance
        if isinstance(item, dict)
        and isinstance(item.get("content_sha256"), str)
        and re.fullmatch(r"[a-f0-9]{64}", item["content_sha256"])
    }
    if len(fingerprints) != len(provenance):
        raise ValueError(f"{prefix} has invalid or duplicate source fingerprints")
    if mode == "ADAPTIVE_MULTI_AGENT" and len(fingerprints) < 2:
        raise ValueError(
            f"{prefix} multi-Agent seed requires at least two source fingerprints"
        )


def route_expectation(seed: dict[str, Any]) -> dict[str, Any]:
    rollout_extra = seed.get("rollout_extra")
    if not isinstance(rollout_extra, dict):
        return {}
    expectation = rollout_extra.get("route_expectation")
    return expectation if isinstance(expectation, dict) else {}


def first_user_question(value: dict[str, Any]) -> str:
    messages = value.get("messages")
    if not isinstance(messages, list):
        raise ValueError("seed messages must be an array")
    for message in messages:
        if (
            isinstance(message, dict)
            and message.get("role") == "user"
            and isinstance(message.get("content"), str)
            and message["content"].strip()
        ):
            return message["content"].strip()
    raise ValueError("seed must contain a non-empty user message")


def build_plan(
    seeds: list[dict[str, Any]],
    batch_id: str,
    rounds: int,
    limit: int | None,
) -> list[dict[str, Any]]:
    if rounds < 1:
        raise ValueError("rounds must be a positive integer")
    if limit is not None and limit < 1:
        raise ValueError("limit must be a positive integer")
    selected = seeds[:limit] if limit is not None else seeds
    plan: list[dict[str, Any]] = []
    for round_number in range(1, rounds + 1):
        for seed in selected:
            seed_id = seed["seed_id"]
            suffix = hashlib.sha256(
                f"{batch_id}:{round_number}:{seed_id}".encode("utf-8")
            ).hexdigest()[:16]
            plan.append(
                {
                    "seed_id": seed_id,
                    "round": round_number,
                    "run_id": f"replay-{batch_id}-{round_number}-{suffix}",
                    "chat_id": f"replay-{batch_id}-{suffix}",
                    "question": first_user_question(seed),
                    "expected_execution_mode": route_expectation(seed).get(
                        "execution_mode"
                    ),
                    "expected_domains": route_expectation(seed).get(
                        "detected_domains", []
                    ),
                }
            )
    return plan


def plan_fingerprint(plan: list[dict[str, Any]]) -> str:
    canonical = json.dumps(
        plan,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def require_execution_authorization(policy_version: str | None) -> str:
    if os.environ.get("AGENT_RL_REPLAY_ALLOW_MODEL_CALLS", "").lower() != "true":
        raise ValueError(
            "--execute also requires "
            "AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true because replay calls a model"
        )
    if not isinstance(policy_version, str) or not policy_version.strip():
        raise ValueError("--execute requires the expected --policy-version")
    return policy_version.strip()


def parse_sse_complete(response: Any) -> dict[str, Any]:
    event_name = ""
    for raw_line in response:
        line = raw_line.decode("utf-8").rstrip("\r\n")
        if line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            payload = line[5:].strip()
            if event_name == "error":
                try:
                    error = json.loads(payload)
                except json.JSONDecodeError:
                    error = {"message": payload}
                raise ValueError(
                    f"Agent replay failed: {error.get('message', payload)}"
                )
            if event_name == "complete":
                try:
                    value = json.loads(payload)
                except json.JSONDecodeError as exception:
                    raise ValueError(
                        f"Complete event was not valid JSON: {exception}"
                    ) from exception
                if not isinstance(value, dict):
                    raise ValueError("Complete event must contain an object")
                return value
        elif not line:
            event_name = ""
    raise ValueError("Agent replay ended without a complete event")


def request_json(url: str, timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            value = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, json.JSONDecodeError) as exception:
        raise ValueError(f"Request failed: {url}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Expected an object from {url}")
    return value


def preflight_server(api_root: str, timeout: int) -> None:
    metrics_url = api_root.rstrip("/") + "/agent-rl/metrics"
    try:
        request_json(metrics_url, timeout)
    except ValueError as exception:
        raise ValueError(
            "Agent RL management API preflight failed; start the trusted local "
            "backend with AGENT_RL_API_ENABLED=true: "
            f"{exception}"
        ) from exception


def request_agent_result(
    request: urllib.request.Request,
    run_url: str,
    timeout: int,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return parse_sse_complete(response), None
    except urllib.error.HTTPError as exception:
        if exception.code != 409:
            raise ValueError(
                f"Agent replay request failed: {request.full_url}: {exception}"
            ) from exception
    except urllib.error.URLError as exception:
        raise ValueError(
            f"Agent replay request failed: {request.full_url}: {exception}"
        ) from exception

    durable_run = request_json(run_url, timeout)
    status = str(durable_run.get("status") or "")
    if status not in RESUMABLE_RUN_STATUSES:
        raise ValueError(
            f"Agent run conflict cannot be resumed from status {status!r}"
        )
    prior_error = str(durable_run.get("error") or "")
    prior_attempt = int(durable_run.get("attempt") or 0)
    recovery = {
        "resumed": True,
        "prior_status": status,
        "prior_attempt": prior_attempt,
        "prior_error": prior_error,
        "prior_timeout_count": (
            1
            if re.search(r"timeout|timed out|exceeded", prior_error, re.IGNORECASE)
            else 0
        ),
    }
    resume_request = urllib.request.Request(
        run_url + "/resume",
        data=b"",
        headers={"Accept": "text/event-stream"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(resume_request, timeout=timeout) as response:
            return parse_sse_complete(response), recovery
    except (urllib.error.URLError, ValueError) as exception:
        raise ValueError(
            f"Agent replay resume failed: {resume_request.full_url}: {exception}"
        ) from exception


def execute_item(
    item: dict[str, Any],
    seed: dict[str, Any],
    api_root: str,
    timeout: int,
) -> dict[str, Any]:
    url = api_root.rstrip("/") + "/ai/love_app/chat/agentic-rag/stream"
    body = {
        "message": item["question"],
        "chatId": item["chat_id"],
        "runId": item["run_id"],
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    run_url = (
        api_root.rstrip("/")
        + "/ai/love_app/chat/agentic-rag/runs/"
        + urllib.parse.quote(item["run_id"], safe="")
    )
    result, recovery = request_agent_result(request, run_url, timeout)
    trajectory_id = result.get("trajectoryId")
    if not isinstance(trajectory_id, str) or not trajectory_id:
        raise ValueError("Agent result has no trajectoryId")
    trajectory_url = (
        api_root.rstrip("/")
        + "/agent-rl/trajectories/"
        + urllib.parse.quote(trajectory_id, safe="")
    )
    trajectory = request_json(trajectory_url, timeout)
    steps = (
        trajectory.get("steps")
        if isinstance(trajectory.get("steps"), list)
        else []
    )
    rollout_extra = seed["rollout_extra"]
    telemetry = (
        trajectory.get("telemetry")
        if isinstance(trajectory.get("telemetry"), dict)
        else {}
    )
    route_step = next(
        (
            step
            for step in steps
            if isinstance(step, dict) and step.get("type") == "ROUTE"
        ),
        {},
    )
    plan_step = next(
        (
            step
            for step in steps
            if isinstance(step, dict) and step.get("type") == "PLAN"
        ),
        {},
    )
    retrieved_document_ids = trajectory.get("retrievedDocumentIds")
    retrieved_document_ids = (
        retrieved_document_ids
        if isinstance(retrieved_document_ids, list)
        else []
    )
    metrics = {
        "retrieved_document_count": len(retrieved_document_ids),
        "retrieval_call_count": sum(
            1
            for step in steps
            if isinstance(step, dict)
            and step.get("type") in {"RETRIEVE", "FOLLOW_UP"}
        ),
        "planned_query_count": (
            plan_step.get("output", {}).get("queryCount", 0)
            if isinstance(plan_step.get("output"), dict)
            else 0
        ),
        "follow_up_rounds": sum(
            1
            for step in steps
            if isinstance(step, dict) and step.get("type") == "FOLLOW_UP"
        ),
    }
    answer = str(trajectory.get("finalAnswer") or "")
    solution = str(rollout_extra.get("solution") or "")
    rlvr_score = score_rollout(
        answer=answer,
        question=item["question"],
        solution=solution,
        context=solution,
        metrics=metrics,
        extra=rollout_extra,
    )
    route_output = (
        route_step.get("output")
        if isinstance(route_step.get("output"), dict)
        else {}
    )
    replay_result = {
        "seed_id": item["seed_id"],
        "round": item["round"],
        "run_id": item["run_id"],
        "trajectory_id": trajectory_id,
        "policy_version": trajectory.get("policyVersion"),
        "status": trajectory.get("status"),
        "reward": (
            trajectory.get("reward", {}).get("total")
            if isinstance(trajectory.get("reward"), dict)
            else None
        ),
        "rlvr": {
            "schema_version": "human-light-rlvr-v3",
            "total": rlvr_score.total,
            "metrics": rlvr_score.metrics,
            "hard_gate_passed": rlvr_score.hard_gate_passed,
            "violations": list(rlvr_score.violations),
            "context_source": "seed_reference_solution",
        },
        "execution_mode": route_output.get("mode"),
        "expected_execution_mode": item.get("expected_execution_mode"),
        "route_expectation_matched": (
            route_output.get("mode") == item.get("expected_execution_mode")
            if item.get("expected_execution_mode")
            else None
        ),
        "telemetry": {
            "model_call_count": telemetry.get("modelCallCount"),
            "total_tokens": telemetry.get("totalTokens"),
            "estimated_cost_cny": telemetry.get("estimatedCostCny"),
            "timeout_count": telemetry.get("timeoutCount"),
        },
    }
    if recovery is not None:
        replay_result["recovery"] = recovery
    return replay_result


def load_partial_results(
    path: Path,
    summary: dict[str, Any],
    plan: list[dict[str, Any]],
) -> int:
    if not path.exists():
        return 0
    try:
        existing = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(
            f"Existing replay manifest is unreadable: {path}: {exception}"
        ) from exception
    if not isinstance(existing, dict):
        raise ValueError("Existing replay manifest must be an object")
    for field in (
        "batch_id",
        "plan_fingerprint",
        "planned_agent_runs",
        "policy_version",
    ):
        if existing.get(field) != summary.get(field):
            raise ValueError(
                f"Existing replay manifest {field} does not match the plan"
            )
    existing_gate_configuration = existing.get("gate_configuration")
    planned_gate_configuration = summary.get("gate_configuration")
    if (
        existing_gate_configuration is None
        and isinstance(planned_gate_configuration, dict)
        and planned_gate_configuration.get("profile") == "collection"
    ):
        raise ValueError(
            "Existing replay manifest has no collection gate_configuration"
        )
    if (
        existing_gate_configuration is not None
        and existing_gate_configuration != planned_gate_configuration
    ):
        raise ValueError(
            "Existing replay manifest gate_configuration does not match the plan"
        )
    results = existing.get("results")
    if not isinstance(results, list):
        raise ValueError("Existing replay manifest results must be an array")
    existing_run_ids = [result.get("run_id") for result in results]
    planned_prefix = [item["run_id"] for item in plan[:len(results)]]
    if existing_run_ids != planned_prefix:
        raise ValueError(
            "Existing replay manifest results are not the deterministic plan prefix"
        )
    if any(result.get("status") != "COMPLETED" for result in results):
        raise ValueError(
            "Existing replay manifest contains a non-completed result"
        )
    summary["results"] = results
    summary["completed_agent_runs"] = len(results)
    summary["resumed_from_completed_agent_runs"] = len(results)
    return len(results)


def write_manifest(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def add_execution_summary(summary: dict[str, Any]) -> None:
    results = summary["results"]
    online_rewards = [
        float(result["reward"])
        for result in results
        if isinstance(result.get("reward"), (int, float))
    ]
    rlvr_scores = [
        float(result["rlvr"]["total"])
        for result in results
        if isinstance(result.get("rlvr"), dict)
        and isinstance(result["rlvr"].get("total"), (int, float))
    ]
    telemetry = [
        result["telemetry"]
        for result in results
        if isinstance(result.get("telemetry"), dict)
    ]
    modes: dict[str, int] = {}
    for result in results:
        mode = str(result.get("execution_mode") or "UNKNOWN")
        modes[mode] = modes.get(mode, 0) + 1
    summary["underlying_model_call_count"] = sum(
        int(value.get("model_call_count") or 0) for value in telemetry
    )
    summary["billable_operations"] = summary["underlying_model_call_count"]
    summary["observed_telemetry"] = {
        "total_tokens": sum(
            int(value.get("total_tokens") or 0) for value in telemetry
        ),
        "estimated_cost_cny": round(
            sum(
                float(value.get("estimated_cost_cny") or 0.0)
                for value in telemetry
            ),
            8,
        ),
        "timeout_count": (
            sum(int(value.get("timeout_count") or 0) for value in telemetry)
            + sum(
                int(result.get("recovery", {}).get("prior_timeout_count") or 0)
                for result in results
                if isinstance(result.get("recovery"), dict)
            )
        ),
    }
    recovered = [
        result["recovery"]
        for result in results
        if isinstance(result.get("recovery"), dict)
        and result["recovery"].get("resumed") is True
    ]
    summary["recovery_summary"] = {
        "recovered_run_count": len(recovered),
        "prior_timeout_count": sum(
            int(value.get("prior_timeout_count") or 0) for value in recovered
        ),
        "prior_errors": [
            value.get("prior_error")
            for value in recovered
            if value.get("prior_error")
        ],
    }
    summary["execution_modes"] = modes
    route_expectations = [
        result.get("route_expectation_matched")
        for result in results
        if isinstance(result.get("route_expectation_matched"), bool)
    ]
    summary["route_expectation_summary"] = {
        "evaluated": len(route_expectations),
        "matched": sum(route_expectations),
        "mismatched": len(route_expectations) - sum(route_expectations),
    }
    summary["online_reward_summary"] = number_summary(online_rewards)
    summary["rlvr_summary"] = {
        **number_summary(rlvr_scores),
        "hard_gate_passed": sum(
            1
            for result in results
            if isinstance(result.get("rlvr"), dict)
            and result["rlvr"].get("hard_gate_passed") is True
        ),
        "violation_count": sum(
            len(result["rlvr"].get("violations", []))
            for result in results
            if isinstance(result.get("rlvr"), dict)
        ),
        "context_source": "seed_reference_solution",
    }


def rlvr_violation_count(results: list[dict[str, Any]]) -> int:
    return sum(
        len(result["rlvr"].get("violations", []))
        for result in results
        if isinstance(result.get("rlvr"), dict)
    )


def rlvr_violation_limit_exceeded(
    results: list[dict[str, Any]],
    maximum_rlvr_violations: int,
) -> bool:
    return rlvr_violation_count(results) > maximum_rlvr_violations


def qualification_reasons(
    results: list[dict[str, Any]],
    *,
    expected_rounds: int,
    minimum_qualified_rlvr: float,
) -> list[str]:
    reasons: list[str] = []
    if len(results) != expected_rounds:
        reasons.append("incomplete_rounds")
    if any(result.get("status") != "COMPLETED" for result in results):
        reasons.append("status_not_completed")
    if any(result.get("route_expectation_matched") is not True for result in results):
        reasons.append("route_mismatch")
    if any(
        not isinstance(result.get("rlvr"), dict)
        or result["rlvr"].get("hard_gate_passed") is not True
        for result in results
    ):
        reasons.append("rlvr_hard_gate_failed")
    if any(
        isinstance(result.get("rlvr"), dict)
        and len(result["rlvr"].get("violations", [])) > 0
        for result in results
    ):
        reasons.append("rlvr_violation")
    if any(
        not isinstance(result.get("rlvr"), dict)
        or not isinstance(result["rlvr"].get("total"), (int, float))
        or float(result["rlvr"]["total"]) < minimum_qualified_rlvr
        for result in results
    ):
        reasons.append("rlvr_below_minimum")

    def timeout_count(result: dict[str, Any], field: str, key: str) -> int:
        value = result.get(field)
        return int(value.get(key) or 0) if isinstance(value, dict) else 0

    if any(
        timeout_count(result, "telemetry", "timeout_count") > 0
        or timeout_count(result, "recovery", "prior_timeout_count") > 0
        for result in results
    ):
        reasons.append("timeout")
    return reasons


def add_collection_summary(
    summary: dict[str, Any],
    *,
    expected_rounds: int,
    minimum_qualified_rlvr: float,
) -> None:
    if expected_rounds < 1:
        raise ValueError("expected rounds must be a positive integer")
    if not 0.0 <= minimum_qualified_rlvr <= 1.0:
        raise ValueError("minimum-qualified-rlvr must be between 0 and 1")
    grouped: dict[str, list[dict[str, Any]]] = {}
    for result in summary.get("results", []):
        seed_id = result.get("seed_id")
        if isinstance(seed_id, str) and seed_id:
            grouped.setdefault(seed_id, []).append(result)

    qualified_seed_ids: list[str] = []
    rejected_seeds: list[dict[str, Any]] = []
    reason_counts: dict[str, int] = {}
    for seed_id, seed_results in grouped.items():
        reasons = qualification_reasons(
            seed_results,
            expected_rounds=expected_rounds,
            minimum_qualified_rlvr=minimum_qualified_rlvr,
        )
        if not reasons:
            qualified_seed_ids.append(seed_id)
            continue
        rejected_seeds.append({"seed_id": seed_id, "reasons": reasons})
        for reason in reasons:
            reason_counts[reason] = reason_counts.get(reason, 0) + 1

    expected_seed_count = int(summary.get("seed_count") or 0)
    missing_seed_count = max(expected_seed_count - len(grouped), 0)
    if missing_seed_count:
        reason_counts["not_evaluated"] = missing_seed_count
    summary["collection_summary"] = {
        "schema_version": "paired-high-confidence-v1",
        "expected_seed_count": expected_seed_count,
        "evaluated_seed_count": len(grouped),
        "qualified_seed_count": len(qualified_seed_ids),
        "rejected_seed_count": len(rejected_seeds),
        "not_evaluated_seed_count": missing_seed_count,
        "required_rounds_per_seed": expected_rounds,
        "minimum_rlvr_per_trajectory": minimum_qualified_rlvr,
        "qualified_seed_ids": qualified_seed_ids,
        "rejected_seeds": rejected_seeds,
        "qualification_reason_counts": reason_counts,
    }


def add_collection_gate(
    summary: dict[str, Any],
    *,
    minimum_qualified_seeds: int,
) -> None:
    if minimum_qualified_seeds < 1:
        raise ValueError("minimum-qualified-seeds must be a positive integer")
    planned = int(summary.get("planned_agent_runs") or 0)
    results = summary.get("results")
    results = results if isinstance(results, list) else []
    completed = sum(
        1 for result in results if result.get("status") == "COMPLETED"
    )
    collection = summary.get("collection_summary")
    collection = collection if isinstance(collection, dict) else {}
    qualified = int(collection.get("qualified_seed_count") or 0)
    expected_seeds = int(collection.get("expected_seed_count") or 0)
    evaluated_seeds = int(collection.get("evaluated_seed_count") or 0)
    checks = {
        "all_runs_completed": planned > 0 and completed == planned,
        "all_seeds_evaluated": (
            expected_seeds > 0 and evaluated_seeds == expected_seeds
        ),
        "minimum_qualified_seeds_met": qualified >= minimum_qualified_seeds,
    }
    failures = [name for name, passed in checks.items() if not passed]
    summary["replay_gate"] = {
        "profile": "collection",
        "passed": not failures,
        "checks": checks,
        "failures": failures,
        "thresholds": {
            "minimum_qualified_seeds": minimum_qualified_seeds,
            "minimum_rlvr_per_trajectory": collection.get(
                "minimum_rlvr_per_trajectory"
            ),
            "required_rounds_per_seed": collection.get(
                "required_rounds_per_seed"
            ),
        },
        "observed": {
            "planned_agent_runs": planned,
            "completed_agent_runs": completed,
            "expected_seed_count": expected_seeds,
            "evaluated_seed_count": evaluated_seeds,
            "qualified_seed_count": qualified,
        },
    }


def add_replay_gate(
    summary: dict[str, Any],
    *,
    minimum_rlvr_average: float,
    maximum_route_mismatches: int,
    maximum_timeouts: int,
    maximum_rlvr_violations: int,
) -> None:
    if not 0.0 <= minimum_rlvr_average <= 1.0:
        raise ValueError("minimum-rlvr-average must be between 0 and 1")
    for name, value in (
        ("maximum-route-mismatches", maximum_route_mismatches),
        ("maximum-timeouts", maximum_timeouts),
        ("maximum-rlvr-violations", maximum_rlvr_violations),
    ):
        if value < 0:
            raise ValueError(f"{name} must not be negative")

    results = summary.get("results")
    results = results if isinstance(results, list) else []
    planned = int(summary.get("planned_agent_runs") or 0)
    completed = sum(
        1 for result in results if result.get("status") == "COMPLETED"
    )
    rlvr_summary = summary.get("rlvr_summary")
    rlvr_summary = rlvr_summary if isinstance(rlvr_summary, dict) else {}
    route_summary = summary.get("route_expectation_summary")
    route_summary = route_summary if isinstance(route_summary, dict) else {}
    telemetry = summary.get("observed_telemetry")
    telemetry = telemetry if isinstance(telemetry, dict) else {}
    expected_modes = summary.get("expected_execution_modes")
    expected_modes = expected_modes if isinstance(expected_modes, dict) else {}
    expected_route_runs = sum(
        int(count)
        for mode, count in expected_modes.items()
        if mode != "UNSPECIFIED"
    )

    average = rlvr_summary.get("average")
    hard_gate_passed = int(rlvr_summary.get("hard_gate_passed") or 0)
    violations = int(rlvr_summary.get("violation_count") or 0)
    route_evaluated = int(route_summary.get("evaluated") or 0)
    route_mismatches = int(route_summary.get("mismatched") or 0)
    timeouts = int(telemetry.get("timeout_count") or 0)
    checks = {
        "all_runs_completed": planned > 0 and completed == planned,
        "all_rlvr_hard_gates_passed": (
            planned > 0 and hard_gate_passed == planned
        ),
        "rlvr_average_met": (
            isinstance(average, (int, float))
            and float(average) >= minimum_rlvr_average
        ),
        "rlvr_violations_within_limit": (
            violations <= maximum_rlvr_violations
        ),
        "route_expectations_fully_evaluated": (
            expected_route_runs == 0 or route_evaluated == expected_route_runs
        ),
        "route_mismatches_within_limit": (
            route_mismatches <= maximum_route_mismatches
        ),
        "timeouts_within_limit": timeouts <= maximum_timeouts,
    }
    failures = [name for name, passed in checks.items() if not passed]
    summary["replay_gate"] = {
        "passed": not failures,
        "checks": checks,
        "failures": failures,
        "thresholds": {
            "minimum_rlvr_average": minimum_rlvr_average,
            "maximum_route_mismatches": maximum_route_mismatches,
            "maximum_timeouts": maximum_timeouts,
            "maximum_rlvr_violations": maximum_rlvr_violations,
        },
        "observed": {
            "planned_agent_runs": planned,
            "completed_agent_runs": completed,
            "rlvr_hard_gate_passed": hard_gate_passed,
            "rlvr_average": average,
            "rlvr_violation_count": violations,
            "route_expectations_evaluated": route_evaluated,
            "route_mismatches": route_mismatches,
            "timeout_count": timeouts,
        },
    }


def number_summary(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"average": None, "minimum": None, "maximum": None}
    return {
        "average": round(sum(values) / len(values), 6),
        "minimum": round(min(values), 6),
        "maximum": round(max(values), 6),
    }


def main() -> int:
    args = parse_args()
    try:
        batch_id = normalize_batch_id(args.batch_id)
        seeds = load_seeds(args.seeds)
        plan = build_plan(seeds, batch_id, args.rounds, args.limit)
        collection_mode = args.minimum_qualified_seeds is not None
        if collection_mode:
            if args.minimum_qualified_seeds < 1:
                raise ValueError(
                    "minimum-qualified-seeds must be a positive integer"
                )
            selected_seed_count = len({item["seed_id"] for item in plan})
            if args.minimum_qualified_seeds > selected_seed_count:
                raise ValueError(
                    "minimum-qualified-seeds cannot exceed selected seed count"
                )
            if not 0.0 <= args.minimum_qualified_rlvr <= 1.0:
                raise ValueError(
                    "minimum-qualified-rlvr must be between 0 and 1"
                )
        summary: dict[str, Any] = {
            "schema_version": REPLAY_SCHEMA_VERSION,
            "mode": "execute" if args.execute else "dry-run",
            "batch_id": batch_id,
            "seed_count": len({item["seed_id"] for item in plan}),
            "rounds": args.rounds,
            "planned_agent_runs": len(plan),
            "plan_fingerprint": plan_fingerprint(plan),
            "underlying_model_call_count": (
                "unknown_until_execution" if args.execute else 0
            ),
            "billable_operations": "unknown_until_execution" if args.execute else 0,
            "policy_version": args.policy_version,
            "results": [],
            "gate_configuration": (
                {
                    "profile": "collection",
                    "minimum_qualified_seeds": args.minimum_qualified_seeds,
                    "minimum_rlvr_per_trajectory": args.minimum_qualified_rlvr,
                    "required_rounds_per_seed": args.rounds,
                }
                if collection_mode
                else {
                    "profile": "release",
                    "minimum_rlvr_average": args.minimum_rlvr_average,
                    "maximum_route_mismatches": args.maximum_route_mismatches,
                    "maximum_timeouts": args.maximum_timeouts,
                    "maximum_rlvr_violations": args.maximum_rlvr_violations,
                }
            ),
        }
        expected_modes: dict[str, int] = {}
        expected_domains: dict[str, int] = {}
        for item in plan:
            mode = str(item.get("expected_execution_mode") or "UNSPECIFIED")
            expected_modes[mode] = expected_modes.get(mode, 0) + 1
            for domain in item.get("expected_domains", []):
                expected_domains[domain] = expected_domains.get(domain, 0) + 1
        summary["expected_execution_modes"] = expected_modes
        summary["expected_domain_runs"] = expected_domains
        if not args.execute:
            summary["preview"] = [
                {
                    "seed_id": item["seed_id"],
                    "round": item["round"],
                    "run_id": item["run_id"],
                    "expected_execution_mode": item[
                        "expected_execution_mode"
                    ],
                    "expected_domains": item["expected_domains"],
                }
                for item in plan[:5]
            ]
            if args.output is not None:
                write_manifest(args.output, summary)
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            print("Dry-run only. No endpoint or model was called.")
            return 0

        expected_policy = require_execution_authorization(args.policy_version)
        if args.output is None:
            raise ValueError("--execute requires --output for resumable evidence")
        preflight_server(args.api_root, args.timeout_seconds)
        seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
        completed_prefix = load_partial_results(args.output, summary, plan)
        if not collection_mode and rlvr_violation_limit_exceeded(
            summary["results"],
            args.maximum_rlvr_violations,
        ):
            summary["stopped_early"] = True
            summary["stop_reason"] = "rlvr_violations_exceeded"
        else:
            for item in plan[completed_prefix:]:
                result = execute_item(
                    item,
                    seeds_by_id[item["seed_id"]],
                    args.api_root,
                    args.timeout_seconds,
                )
                summary["results"].append(result)
                summary["completed_agent_runs"] = len(summary["results"])
                write_manifest(args.output, summary)
                if result["policy_version"] != expected_policy:
                    raise ValueError(
                        "Server trajectory policyVersion mismatch: expected "
                        f"{expected_policy!r}, got {result['policy_version']!r}; "
                        "stopped after the first mismatched trajectory"
                    )
                if not collection_mode and rlvr_violation_limit_exceeded(
                    summary["results"],
                    args.maximum_rlvr_violations,
                ):
                    summary["stopped_early"] = True
                    summary["stop_reason"] = "rlvr_violations_exceeded"
                    break
        summary["completed"] = len(summary["results"]) == len(plan)
        add_execution_summary(summary)
        if collection_mode:
            add_collection_summary(
                summary,
                expected_rounds=args.rounds,
                minimum_qualified_rlvr=args.minimum_qualified_rlvr,
            )
            add_collection_gate(
                summary,
                minimum_qualified_seeds=args.minimum_qualified_seeds,
            )
        else:
            add_replay_gate(
                summary,
                minimum_rlvr_average=args.minimum_rlvr_average,
                maximum_route_mismatches=args.maximum_route_mismatches,
                maximum_timeouts=args.maximum_timeouts,
                maximum_rlvr_violations=args.maximum_rlvr_violations,
            )
        write_manifest(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        if not summary["replay_gate"]["passed"]:
            print(
                "Seed replay completed but failed the replay gate: "
                + ", ".join(summary["replay_gate"]["failures"]),
                file=sys.stderr,
            )
            return 3
        return 0
    except (OSError, ValueError) as exception:
        print(f"Seed replay failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

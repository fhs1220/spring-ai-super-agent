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
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from functions.reward.scoring import score_rollout  # noqa: E402


SEED_SCHEMA_VERSION = "agent-rl-trajectory-seed-v1"
REPLAY_SCHEMA_VERSION = "agent-rl-seed-replay-v1"
DATASET_ROLE = "trajectory_seed_only"


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
    if value.get("schema_version") != SEED_SCHEMA_VERSION:
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
                }
            )
    return plan


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
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result = parse_sse_complete(response)
    except urllib.error.URLError as exception:
        raise ValueError(f"Agent replay request failed: {url}: {exception}") from exception
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
    return {
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
        "telemetry": {
            "model_call_count": telemetry.get("modelCallCount"),
            "total_tokens": telemetry.get("totalTokens"),
            "estimated_cost_cny": telemetry.get("estimatedCostCny"),
            "timeout_count": telemetry.get("timeoutCount"),
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
        "timeout_count": sum(
            int(value.get("timeout_count") or 0) for value in telemetry
        ),
    }
    summary["execution_modes"] = modes
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
        summary: dict[str, Any] = {
            "schema_version": REPLAY_SCHEMA_VERSION,
            "mode": "execute" if args.execute else "dry-run",
            "batch_id": batch_id,
            "seed_count": len({item["seed_id"] for item in plan}),
            "rounds": args.rounds,
            "planned_agent_runs": len(plan),
            "underlying_model_call_count": "unknown_until_execution",
            "policy_version": args.policy_version,
            "results": [],
        }
        if not args.execute:
            summary["preview"] = [
                {
                    "seed_id": item["seed_id"],
                    "round": item["round"],
                    "run_id": item["run_id"],
                }
                for item in plan[:5]
            ]
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            print("Dry-run only. No endpoint or model was called.")
            return 0

        expected_policy = require_execution_authorization(args.policy_version)
        if args.output is None:
            raise ValueError("--execute requires --output for resumable evidence")
        preflight_server(args.api_root, args.timeout_seconds)
        seeds_by_id = {seed["seed_id"]: seed for seed in seeds}
        for item in plan:
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
        summary["completed"] = True
        add_execution_summary(summary)
        write_manifest(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError) as exception:
        print(f"Seed replay failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

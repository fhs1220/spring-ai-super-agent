#!/usr/bin/env python3
"""Validate or submit the Cortex Agentic RAG job to Alibaba Cloud Model Studio.

Dry-run is the default. A billable job requires both --execute and
BAILIAN_RL_ALLOW_BILLING=true.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Any


SUPPORTED_MODELS = {"qwen3.5-9b", "qwen3.5-35b-a3b"}
REWARD_SCHEMA_VERSION = "human-light-rlvr-v2"
REWARD_METRICS = {
    "reference_quality",
    "grounding_quality",
    "citation_quality",
    "task_completion_quality",
    "safety_boundary_quality",
    "retrieval_quality",
    "convergence_quality",
    "efficiency",
    "anti_hacking_quality",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("config.example.json"))
    parser.add_argument("--train", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Create a real, billable Model Studio RL job.",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exception:
        raise ValueError(f"File does not exist: {path}") from exception
    except json.JSONDecodeError as exception:
        raise ValueError(f"Invalid JSON in {path}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def load_dataset(path: Path) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as exception:
        raise ValueError(f"Dataset does not exist: {path}") from exception

    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            sample = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"{path}:{line_number} is not valid JSON: {exception}"
            ) from exception
        validate_sample(sample, path, line_number)
        samples.append(sample)
    return samples


def validate_sample(sample: Any, path: Path, line_number: int) -> None:
    prefix = f"{path}:{line_number}"
    if not isinstance(sample, dict):
        raise ValueError(f"{prefix} must contain a JSON object")
    messages = sample.get("messages")
    if not isinstance(messages, list) or not messages:
        raise ValueError(f"{prefix} messages must be a non-empty array")
    if not any(
        isinstance(message, dict)
        and message.get("role") == "user"
        and isinstance(message.get("content"), str)
        and message["content"].strip()
        for message in messages
    ):
        raise ValueError(f"{prefix} must contain a non-empty user message")
    rollout_extra = sample.get("rollout_extra")
    if not isinstance(rollout_extra, dict):
        raise ValueError(f"{prefix} rollout_extra must be an object")
    solution = rollout_extra.get("solution")
    if not isinstance(solution, str) or not solution.strip():
        raise ValueError(f"{prefix} rollout_extra.solution must not be blank")
    reward_schema = rollout_extra.get("reward_schema_version")
    if reward_schema is not None and reward_schema != REWARD_SCHEMA_VERSION:
        raise ValueError(
            f"{prefix} reward_schema_version must be {REWARD_SCHEMA_VERSION}"
        )
    verification_contract = rollout_extra.get("verification_contract")
    if verification_contract is not None and not isinstance(
        verification_contract, dict
    ):
        raise ValueError(f"{prefix} verification_contract must be an object")


def first_user_question(sample: dict[str, Any]) -> str:
    return next(
        message["content"].strip()
        for message in sample["messages"]
        if message.get("role") == "user" and message.get("content", "").strip()
    )


def validate_config(config: dict[str, Any]) -> None:
    model = config.get("model")
    if model not in SUPPORTED_MODELS:
        raise ValueError(
            f"model must be one of {sorted(SUPPORTED_MODELS)}, got {model!r}"
        )
    required_sections = ("resource_config", "hyper_parameters", "function_runtime")
    for section in required_sections:
        if not isinstance(config.get(section), dict):
            raise ValueError(f"config.{section} must be an object")
    batch_size = config["hyper_parameters"].get("batch_size")
    if not isinstance(batch_size, int) or batch_size < 1:
        raise ValueError("hyper_parameters.batch_size must be a positive integer")
    if config.get("reward_schema_version") != REWARD_SCHEMA_VERSION:
        raise ValueError(
            f"reward_schema_version must be {REWARD_SCHEMA_VERSION}"
        )
    reward_weights = config.get("reward_metric_weights")
    if not isinstance(reward_weights, dict):
        raise ValueError("reward_metric_weights must be an object")
    if set(reward_weights) != REWARD_METRICS:
        raise ValueError(
            "reward_metric_weights must contain exactly: "
            + ", ".join(sorted(REWARD_METRICS))
        )
    if any(
        not isinstance(value, (int, float)) or value < 0
        for value in reward_weights.values()
    ):
        raise ValueError("reward_metric_weights values must be non-negative numbers")
    if abs(sum(float(value) for value in reward_weights.values()) - 1.0) > 1e-9:
        raise ValueError("reward_metric_weights must sum to 1.0")


def validate_package(
    config: dict[str, Any],
    training: list[dict[str, Any]],
    validation: list[dict[str, Any]],
) -> None:
    if not validation:
        raise ValueError("validation dataset must not be empty")
    batch_size = config["hyper_parameters"]["batch_size"]
    if len(training) <= batch_size:
        raise ValueError(
            f"training dataset must be larger than batch_size={batch_size}; "
            f"found {len(training)} samples"
        )
    train_questions = {first_user_question(sample) for sample in training}
    validation_questions = {first_user_question(sample) for sample in validation}
    overlap = train_questions & validation_questions
    if overlap:
        raise ValueError(
            f"training and validation datasets overlap on {len(overlap)} question(s)"
        )


def require_execution_authorization() -> None:
    if os.environ.get("BAILIAN_RL_ALLOW_BILLING", "").lower() != "true":
        raise ValueError(
            "--execute also requires BAILIAN_RL_ALLOW_BILLING=true because RL jobs are billable"
        )
    required = (
        "DASHSCOPE_API_KEY",
        "FC_PYPI_LIB",
        "AGENT_RL_RETRIEVAL_URL",
        "AGENT_RL_RETRIEVAL_TOKEN",
    )
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise ValueError("Missing required environment variables: " + ", ".join(missing))
    wheel_name = os.environ["FC_PYPI_LIB"]
    candidates = (Path.cwd() / wheel_name, Path(__file__).resolve().parent / wheel_name)
    if not any(candidate.is_file() for candidate in candidates):
        raise ValueError(
            f"{wheel_name} must exist in the current directory or bailian-agent-rl/"
        )


def runtime_config(runtime_type: str, config: dict[str, Any], runtime_class: Any) -> Any:
    values = dict(config["function_runtime"][runtime_type])
    if runtime_type == "rollout":
        values["env"] = {
            "AGENT_RL_RETRIEVAL_URL": os.environ["AGENT_RL_RETRIEVAL_URL"],
            "AGENT_RL_RETRIEVAL_TOKEN": os.environ["AGENT_RL_RETRIEVAL_TOKEN"],
        }
    return runtime_class(**values)


async def submit(
    config: dict[str, Any], training_path: Path, validation_path: Path
) -> str:
    try:
        from dashscope.finetune.agentic_rl import AgenticRL
        from dashscope.finetune.reinforcement import (
            DataSourceType,
            FunctionComponentModel,
            FunctionComponentRuntime,
            RewardFunctionComponent,
            RolloutFunctionComponent,
            TrainingDataset,
            ValidationDataset,
        )
    except ImportError as exception:
        raise ValueError(
            "dashscope==1.25.16 is required; install bailian-agent-rl/requirements.txt"
        ) from exception

    client = AgenticRL()
    result = await client.run(
        model=config["model"],
        training_datasets=[
            TrainingDataset(
                data_source_type=DataSourceType.FILE_ID,
                file_name=str(training_path.resolve()),
            )
        ],
        validation_datasets=[
            ValidationDataset(
                data_source_type=DataSourceType.FILE_ID,
                file_name=str(validation_path.resolve()),
            )
        ],
        functions=[
            RolloutFunctionComponent(
                name="cortex-agentic-rag-rollout",
                timeout=600,
                fcmodel=FunctionComponentModel(
                    classpath="functions.rollout.rollout.AgenticRagRolloutProcessor"
                ),
                runtime=runtime_config(
                    "rollout", config, FunctionComponentRuntime
                ),
            ),
            RewardFunctionComponent(
                name="cortex-agentic-rag-reward",
                weight=1.0,
                reward_metric_weight=config["reward_metric_weights"],
                timeout=120,
                fcmodel=FunctionComponentModel(
                    classpath="functions.reward.reward.AgenticRagRewardProcessor"
                ),
                runtime=runtime_config("reward", config, FunctionComponentRuntime),
            ),
        ],
        resource_config=config["resource_config"],
        hyper_parameters=config["hyper_parameters"],
    )
    return result.output.job_id


async def main() -> int:
    args = parse_args()
    try:
        config = load_json(args.config)
        validate_config(config)
        training = load_dataset(args.train)
        validation = load_dataset(args.validation)
        validate_package(config, training, validation)
        print(
            json.dumps(
                {
                    "mode": "execute" if args.execute else "dry-run",
                    "model": config["model"],
                    "training_samples": len(training),
                    "validation_samples": len(validation),
                    "batch_size": config["hyper_parameters"]["batch_size"],
                    "reward_schema_version": config["reward_schema_version"],
                    "reward_metric_weights": config["reward_metric_weights"],
                    "estimated_training_rollouts_per_step": (
                        config["hyper_parameters"]["batch_size"]
                        * config["hyper_parameters"]["n_rollouts"]
                    ),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        if not args.execute:
            print("Dry-run passed. No cloud resources were created.")
            return 0

        require_execution_authorization()
        job_id = await submit(config, args.train, args.validation)
        print(f"Billable Model Studio RL job created: {job_id}")
        return 0
    except ValueError as exception:
        print(f"Preflight failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

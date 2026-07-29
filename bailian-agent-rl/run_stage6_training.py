#!/usr/bin/env python3
"""Validate or safely submit the frozen Stage 6 A/B/C training arms.

Dry-run is the default and never contacts Model Studio. Real execution is
sequential and persists an intent record before each billable submission. If a
process stops while an arm is being submitted, the orchestrator refuses to
guess whether a cloud job was created and requires manual reconciliation.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Awaitable, Callable

import submit_job


SCHEMA_VERSION = "stage6-three-arm-training-v1"
STAGE5_SCHEMA_VERSION = "stage5-training-dataset-freeze-v1"
ARMS = (
    ("BASELINE_STATIC_REWARD", "baseline-static-reward"),
    ("RLVR_ONLY", "rlvr-only"),
    ("RLVR_RLAIF", "rlvr-rlaif"),
)
SHA256_LENGTH = 64
SubmitFunction = Callable[
    [dict[str, Any], Path, Path],
    Awaitable[str],
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage5-manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Sequentially create the three real, billable Model Studio jobs.",
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


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
    except FileNotFoundError as exception:
        raise ValueError(f"Evidence file does not exist: {path}") from exception
    return digest.hexdigest()


def resolve_evidence(repo_root: Path, value: Any, field: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty path")
    path = (repo_root / value).resolve()
    try:
        path.relative_to(repo_root.resolve())
    except ValueError as exception:
        raise ValueError(f"{field} escapes the repository root") from exception
    return path


def validate_stage5_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schema_version") != STAGE5_SCHEMA_VERSION:
        raise ValueError(
            f"stage5 schema_version must be {STAGE5_SCHEMA_VERSION}"
        )
    expected = manifest.get("freeze_fingerprint")
    payload = {
        key: value
        for key, value in manifest.items()
        if key != "freeze_fingerprint"
    }
    if (
        not isinstance(expected, str)
        or len(expected) != SHA256_LENGTH
        or expected != canonical_sha256(payload)
    ):
        raise ValueError("Stage 5 freeze_fingerprint does not match its content")
    audit = manifest.get("benchmark_audit")
    if not isinstance(audit, dict) or audit.get("passed") is not True:
        raise ValueError("Stage 5 benchmark audit did not pass")
    blocked = manifest.get("blocked_arms")
    if not isinstance(blocked, dict):
        raise ValueError("Stage 5 blocked_arms must be an object")
    unexpected = [arm for arm, _ in ARMS if arm in blocked]
    if unexpected:
        raise ValueError(
            "Stage 5 still blocks required arms: " + ", ".join(unexpected)
        )


def build_plan(
    stage5_manifest_path: Path,
    repo_root: Path,
) -> dict[str, Any]:
    repo_root = repo_root.resolve()
    stage5_manifest_path = stage5_manifest_path.resolve()
    manifest = load_object(stage5_manifest_path)
    validate_stage5_manifest(manifest)
    source_arms = manifest.get("arms")
    if not isinstance(source_arms, dict):
        raise ValueError("Stage 5 arms must be an object")

    planned_arms: dict[str, dict[str, Any]] = {}
    for arm, slug in ARMS:
        source = source_arms.get(arm)
        if not isinstance(source, dict):
            raise ValueError(f"Stage 5 arm is missing: {arm}")
        if source.get("ready_for_submission") is not True:
            raise ValueError(f"Stage 5 arm is not ready for submission: {arm}")
        config_path = resolve_evidence(
            repo_root, source.get("config_path"), f"{arm}.config_path"
        )
        training_path = resolve_evidence(
            repo_root, source.get("training_path"), f"{arm}.training_path"
        )
        validation_path = resolve_evidence(
            repo_root,
            source.get("validation_path"),
            f"{arm}.validation_path",
        )
        expected_files = (
            ("config_fingerprint", config_path),
            ("training_fingerprint", training_path),
            ("validation_fingerprint", validation_path),
        )
        for field, path in expected_files:
            actual = file_sha256(path)
            if source.get(field) != actual:
                raise ValueError(f"{arm}.{field} does not match {path}")

        config = submit_job.load_json(config_path)
        training = submit_job.load_dataset(training_path)
        validation = submit_job.load_dataset(validation_path)
        submit_job.validate_config(config)
        submit_job.validate_package(config, training, validation)
        if config.get("alignment_arm") != arm:
            raise ValueError(f"{arm} config alignment_arm does not match")
        if len(training) != source.get("training_count"):
            raise ValueError(f"{arm} training_count does not match dataset")
        if len(validation) != source.get("validation_count"):
            raise ValueError(f"{arm} validation_count does not match dataset")

        resources = config["resource_config"]
        hyper_parameters = config["hyper_parameters"]
        planned_arms[arm] = {
            "slug": slug,
            "config_path": str(config_path.relative_to(repo_root)),
            "training_path": str(training_path.relative_to(repo_root)),
            "validation_path": str(validation_path.relative_to(repo_root)),
            "config_fingerprint": source["config_fingerprint"],
            "training_fingerprint": source["training_fingerprint"],
            "validation_fingerprint": source["validation_fingerprint"],
            "training_count": len(training),
            "validation_count": len(validation),
            "model": config["model"],
            "reward_schema_version": config["reward_schema_version"],
            "resource_config": resources,
            "hyper_parameters": {
                key: hyper_parameters.get(key)
                for key in (
                    "algorithm",
                    "batch_size",
                    "n_epochs",
                    "n_rollouts",
                    "max_length",
                )
            },
        }

    plan_payload = {
        "schema_version": SCHEMA_VERSION,
        "stage5_manifest_path": str(stage5_manifest_path.relative_to(repo_root)),
        "stage5_manifest_fingerprint": file_sha256(stage5_manifest_path),
        "stage5_freeze_fingerprint": manifest["freeze_fingerprint"],
        "required_dashscope_version": submit_job.DASHSCOPE_SDK_VERSION,
        "submission_order": [arm for arm, _ in ARMS],
        "arms": planned_arms,
    }
    return {
        **plan_payload,
        "plan_fingerprint": canonical_sha256(plan_payload),
    }


def verify_local_sdk(repo_root: Path) -> dict[str, Any]:
    try:
        from importlib.metadata import version

        installed = version("dashscope")
        from dashscope.finetune.agentic_rl import AgenticRL  # noqa: F401
        from dashscope.finetune.reinforcement import (  # noqa: F401
            RolloutFunctionComponent,
        )
    except (ImportError, ModuleNotFoundError) as exception:
        raise ValueError(
            "Installed dashscope does not include Agentic RL support"
        ) from exception
    if installed != submit_job.DASHSCOPE_SDK_VERSION:
        raise ValueError(
            "Installed dashscope version must be "
            f"{submit_job.DASHSCOPE_SDK_VERSION}, found {installed}"
        )
    wheel_name = f"dashscope-{installed}-py3-none-any.whl"
    wheel_path = repo_root / "bailian-agent-rl" / wheel_name
    if not wheel_path.is_file():
        raise ValueError(f"Required offline wheel does not exist: {wheel_path}")
    return {
        "installed_version": installed,
        "wheel_path": str(wheel_path.relative_to(repo_root)),
        "wheel_fingerprint": file_sha256(wheel_path),
        "agentic_rl_import": True,
    }


def initial_report(plan: dict[str, Any], sdk: dict[str, Any]) -> dict[str, Any]:
    return {
        **plan,
        "mode": "dry-run",
        "state": "DRY_RUN_PASSED",
        "sdk": sdk,
        "cloud_jobs_created": 0,
        "billable_operations": 0,
        "arms": {
            arm: {
                **details,
                "submission_state": "DRY_RUN_PASSED",
                "job_id": None,
            }
            for arm, details in plan["arms"].items()
        },
    }


def write_report(path: Path, report: dict[str, Any]) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def load_execution_report(
    output: Path,
    plan: dict[str, Any],
    sdk: dict[str, Any],
) -> dict[str, Any]:
    if not output.exists():
        report = initial_report(plan, sdk)
    else:
        report = load_object(output)
        if report.get("plan_fingerprint") != plan["plan_fingerprint"]:
            raise ValueError(
                "Existing Stage 6 report belongs to a different frozen plan"
            )
        for arm, _ in ARMS:
            state = report.get("arms", {}).get(arm, {}).get("submission_state")
            if state in {"SUBMITTING", "SUBMISSION_OUTCOME_UNKNOWN"}:
                raise ValueError(
                    f"{arm} has an ambiguous submission outcome; "
                    "reconcile it in Model Studio before resuming"
                )
    report["mode"] = "execute"
    report["state"] = "SUBMITTING"
    report["sdk"] = sdk
    return report


def timestamp() -> str:
    return datetime.now(UTC).isoformat()


async def execute_plan(
    plan: dict[str, Any],
    sdk: dict[str, Any],
    output: Path,
    repo_root: Path,
    submit_function: SubmitFunction = submit_job.submit,
) -> dict[str, Any]:
    submit_job.require_execution_authorization()
    retrieval_preflight = await asyncio.to_thread(
        submit_job.verify_retrieval_environment
    )
    report = load_execution_report(output, plan, sdk)
    report["retrieval_preflight"] = retrieval_preflight
    write_report(output, report)

    for arm, _ in ARMS:
        entry = report["arms"][arm]
        if entry.get("submission_state") == "SUBMITTED":
            continue
        entry["submission_state"] = "SUBMITTING"
        entry["submission_started_at"] = timestamp()
        report["state"] = "SUBMITTING"
        write_report(output, report)

        config_path = repo_root / entry["config_path"]
        training_path = repo_root / entry["training_path"]
        validation_path = repo_root / entry["validation_path"]
        try:
            job_id = await submit_function(
                submit_job.load_json(config_path),
                training_path,
                validation_path,
            )
            if not isinstance(job_id, str) or not job_id.strip():
                raise ValueError("Model Studio returned a blank job ID")
        except Exception:
            entry["submission_state"] = "SUBMISSION_OUTCOME_UNKNOWN"
            entry["submission_finished_at"] = timestamp()
            report["state"] = "RECONCILIATION_REQUIRED"
            write_report(output, report)
            raise

        entry["job_id"] = job_id.strip()
        entry["submission_state"] = "SUBMITTED"
        entry["submission_finished_at"] = timestamp()
        report["cloud_jobs_created"] = sum(
            item.get("submission_state") == "SUBMITTED"
            for item in report["arms"].values()
        )
        report["billable_operations"] = report["cloud_jobs_created"]
        write_report(output, report)

    report["state"] = "SUBMITTED"
    report["completed_at"] = timestamp()
    write_report(output, report)
    return report


async def main() -> int:
    args = parse_args()
    try:
        repo_root = args.repo_root.resolve()
        plan = build_plan(args.stage5_manifest, repo_root)
        sdk = verify_local_sdk(repo_root)
        if not args.execute:
            if args.output.exists():
                existing = load_object(args.output)
                if any(
                    entry.get("job_id")
                    for entry in existing.get("arms", {}).values()
                    if isinstance(entry, dict)
                ):
                    raise ValueError(
                        "Refusing to overwrite a report containing cloud job IDs"
                    )
            report = initial_report(plan, sdk)
            write_report(args.output, report)
        else:
            report = await execute_plan(
                plan,
                sdk,
                args.output,
                repo_root,
            )
        print(
            json.dumps(
                {
                    "mode": report["mode"],
                    "state": report["state"],
                    "plan_fingerprint": report["plan_fingerprint"],
                    "stage5_freeze_fingerprint":
                        report["stage5_freeze_fingerprint"],
                    "required_dashscope_version":
                        report["required_dashscope_version"],
                    "arms": {
                        arm: {
                            "training_count": entry["training_count"],
                            "validation_count": entry["validation_count"],
                            "submission_state":
                                entry["submission_state"],
                            "job_id": entry["job_id"],
                        }
                        for arm, entry in report["arms"].items()
                    },
                    "cloud_jobs_created": report["cloud_jobs_created"],
                    "billable_operations": report["billable_operations"],
                    "output": str(args.output.resolve()),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    except ValueError as exception:
        print(f"Stage 6 orchestration failed: {exception}", file=sys.stderr)
        return 2
    except Exception as exception:
        print(
            "Stage 6 submission outcome may require reconciliation: "
            f"{type(exception).__name__}: {exception}",
            file=sys.stderr,
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

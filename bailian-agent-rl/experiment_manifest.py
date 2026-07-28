#!/usr/bin/env python3
"""Build an auditable four-arm Model Studio experiment manifest.

The tool is offline and never creates cloud resources. It binds immutable
provider artifact identifiers to local training config, dataset and function
code fingerprints, then emits the request accepted by the Java experiment API.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "bailian-alignment-experiment-v1"
EXPERIMENT_ARMS = (
    "BASELINE_STATIC_REWARD",
    "RLVR_ONLY",
    "RLVR_RLAIF",
    "FULL_TRAJECTORY_GUIDED",
)
DEFAULT_COMPONENT_FILES = (
    "functions/reward/reward.py",
    "functions/reward/scoring.py",
    "functions/rollout/rollout.py",
    "submit_job.py",
)
PLACEHOLDER_PATTERN = re.compile(
    r"(^\s*$|replace|placeholder|<[^>]+>|待填写|示例)",
    re.IGNORECASE,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--spec",
        type=Path,
        default=Path("experiment-arms.example.json"),
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exception:
        raise ValueError(f"File does not exist: {path}") from exception
    except json.JSONDecodeError as exception:
        raise ValueError(f"Invalid JSON in {path}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_sha256(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def file_fingerprint(path: Path) -> str:
    try:
        data = path.read_bytes()
    except FileNotFoundError as exception:
        raise ValueError(f"Evidence file does not exist: {path}") from exception
    if path.suffix.lower() == ".json":
        try:
            return canonical_sha256(json.loads(data.decode("utf-8")))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise ValueError(f"Invalid JSON evidence file: {path}") from exception
    return sha256_bytes(data)


def require_text(item: dict[str, Any], field: str, arm: str) -> str:
    value = item.get(field)
    if not isinstance(value, str) or PLACEHOLDER_PATTERN.search(value):
        raise ValueError(f"{arm}.{field} must be a real non-placeholder value")
    return value.strip()


def optional_text(item: dict[str, Any], field: str) -> str:
    value = item.get(field, "")
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a string")
    return value.strip()


def evidence_path(
    root: Path,
    item: dict[str, Any],
    field: str,
    arm: str,
    required: bool,
) -> Path | None:
    value = item.get(field)
    if value is None and not required:
        return None
    if not isinstance(value, str) or PLACEHOLDER_PATTERN.search(value):
        raise ValueError(f"{arm}.{field} must reference a real evidence file")
    return (root / value).resolve()


def validate_top_level(spec: dict[str, Any]) -> list[dict[str, Any]]:
    if spec.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"schemaVersion must be {SCHEMA_VERSION}")
    arms = spec.get("arms")
    if not isinstance(arms, list) or len(arms) != len(EXPERIMENT_ARMS):
        raise ValueError("Exactly four experiment arms are required")
    if not all(isinstance(item, dict) for item in arms):
        raise ValueError("Each experiment arm must be a JSON object")
    names = [item.get("arm") for item in arms]
    if set(names) != set(EXPERIMENT_ARMS) or len(set(names)) != len(names):
        raise ValueError("All four distinct experiment arm names are required")
    return sorted(arms, key=lambda item: EXPERIMENT_ARMS.index(item["arm"]))


def build_manifest(spec_path: Path) -> dict[str, Any]:
    spec_path = spec_path.resolve()
    spec = load_object(spec_path)
    arms = validate_top_level(spec)
    root = spec_path.parent
    component_values = spec.get("componentFiles", list(DEFAULT_COMPONENT_FILES))
    if not isinstance(component_values, list) or not component_values:
        raise ValueError("componentFiles must be a non-empty array")
    component_entries: list[tuple[str, Path]] = []
    for value in component_values:
        if not isinstance(value, str) or PLACEHOLDER_PATTERN.search(value):
            raise ValueError("componentFiles must contain real file paths")
        component_entries.append((Path(value).as_posix(), (root / value).resolve()))
    component_hashes = {
        label: file_fingerprint(path)
        for label, path in component_entries
    }

    artifact_keys: set[tuple[str, str]] = set()
    output_arms: list[dict[str, Any]] = []
    experiment_plans: list[dict[str, str]] = []
    runtime_environments: dict[str, dict[str, str]] = {}
    for item in arms:
        arm = item["arm"]
        model_version = require_text(item, "modelVersion", arm)
        provider = require_text(item, "provider", arm)
        artifact_id = require_text(item, "modelArtifactId", arm)
        training_job_id = require_text(item, "trainingJobId", arm)
        source_deployment = require_text(item, "sourceDeployment", arm)
        reward_schema = require_text(item, "rewardSchemaVersion", arm)
        checkpoint_id = optional_text(item, "checkpointId")
        artifact_key = (provider.lower(), artifact_id)
        if artifact_key in artifact_keys:
            raise ValueError(
                "Each arm must reference a distinct provider model artifact"
            )
        artifact_keys.add(artifact_key)

        config_path = evidence_path(
            root, item, "trainingConfig", arm, required=True
        )
        train_path = evidence_path(
            root, item, "trainingDataset", arm, required=False
        )
        validation_path = evidence_path(
            root, item, "validationDataset", arm, required=False
        )
        assert config_path is not None
        config_value = load_object(config_path)
        if config_value.get("alignment_arm") != arm:
            raise ValueError(
                f"{arm}.trainingConfig alignment_arm does not match the arm"
            )
        if config_value.get("reward_schema_version") != reward_schema:
            raise ValueError(
                f"{arm}.trainingConfig reward_schema_version does not match"
            )
        if train_path is None or validation_path is None:
            raise ValueError(
                f"{arm} requires trainingDataset and validationDataset evidence"
            )
        evidence = {
            "trainingConfigSha256": file_fingerprint(config_path),
            "trainingDatasetSha256": (
                file_fingerprint(train_path) if train_path else ""
            ),
            "validationDatasetSha256": (
                file_fingerprint(validation_path) if validation_path else ""
            ),
            "componentSha256": component_hashes,
        }
        artifact_descriptor = {
            "provider": provider,
            "modelArtifactId": artifact_id,
            "trainingJobId": training_job_id,
            "checkpointId": checkpoint_id,
        }
        training_descriptor = {
            "rewardSchemaVersion": reward_schema,
            **evidence,
        }
        model_fingerprint = canonical_sha256(artifact_descriptor)
        training_fingerprint = canonical_sha256(training_descriptor)
        plan = {
            "arm": arm,
            "modelVersion": model_version,
            "modelArtifactFingerprint": model_fingerprint,
            "trainingConfigFingerprint": training_fingerprint,
            "rewardSchemaVersion": reward_schema,
            "sourceDeployment": source_deployment,
        }
        experiment_plans.append(plan)
        runtime_environments[arm] = {
            "AGENT_EVALUATION_MODEL_VERSION": model_version,
            "AGENT_EVALUATION_MODEL_ARTIFACT_FINGERPRINT": model_fingerprint,
            "AGENT_EVALUATION_TRAINING_CONFIG_FINGERPRINT": training_fingerprint,
            "AGENT_EVALUATION_REWARD_SCHEMA_VERSION": reward_schema,
            "AGENT_EVALUATION_SOURCE_DEPLOYMENT": source_deployment,
        }
        output_arms.append(
            {
                **plan,
                "providerArtifact": artifact_descriptor,
                "trainingEvidence": evidence,
            }
        )

    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "sourceSpecSha256": file_fingerprint(spec_path),
        "arms": output_arms,
        "experimentRequest": {"arms": experiment_plans},
        "runtimeEnvironments": runtime_environments,
    }
    return {
        **payload,
        "manifestFingerprint": canonical_sha256(payload),
    }


def write_outputs(manifest: dict[str, Any], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    formatted = lambda value: json.dumps(  # noqa: E731
        value, ensure_ascii=False, indent=2, sort_keys=True
    ) + "\n"
    (output_dir / "artifact-manifest.json").write_text(
        formatted(manifest), encoding="utf-8"
    )
    (output_dir / "experiment-request.json").write_text(
        formatted(manifest["experimentRequest"]), encoding="utf-8"
    )
    (output_dir / "runtime-environments.json").write_text(
        formatted(manifest["runtimeEnvironments"]), encoding="utf-8"
    )
    evidence_template = {
        item["arm"]: {"evaluationRunId": ""}
        for item in manifest["experimentRequest"]["arms"]
    }
    (output_dir / "evidence-template.json").write_text(
        formatted(evidence_template), encoding="utf-8"
    )


def main() -> int:
    args = parse_args()
    try:
        manifest = build_manifest(args.spec)
        write_outputs(manifest, args.output_dir)
        print(
            json.dumps(
                {
                    "manifestFingerprint": manifest["manifestFingerprint"],
                    "outputDirectory": str(args.output_dir.resolve()),
                    "armCount": len(manifest["arms"]),
                    "cloudResourcesCreated": False,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    except ValueError as exception:
        print(f"Manifest build failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

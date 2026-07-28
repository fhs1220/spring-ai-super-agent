#!/usr/bin/env python3
"""Drive the Java four-arm experiment API from a verified artifact manifest.

Dry-run is the default. This tool never starts cloud training or model
evaluation runs; it only creates the experiment, attaches existing completed
run IDs and optionally finalizes the statistical report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "bailian-alignment-experiment-v1"
EXPERIMENT_ARMS = (
    "BASELINE_STATIC_REWARD",
    "RLVR_ONLY",
    "RLVR_RLAIF",
    "FULL_TRAJECTORY_GUIDED",
)
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument(
        "--base-url",
        default="http://127.0.0.1:8123/api/agent-evaluation",
    )
    parser.add_argument("--finalize", action="store_true")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Write experiment state through the local Java API.",
    )
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


def canonical_sha256(value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"manifest schemaVersion must be {SCHEMA_VERSION}")
    expected = manifest.get("manifestFingerprint")
    payload = {
        key: value
        for key, value in manifest.items()
        if key != "manifestFingerprint"
    }
    if not isinstance(expected, str) or expected != canonical_sha256(payload):
        raise ValueError("manifestFingerprint does not match manifest content")
    request = manifest.get("experimentRequest")
    plans = request.get("arms") if isinstance(request, dict) else None
    if not isinstance(plans, list) or len(plans) != len(EXPERIMENT_ARMS):
        raise ValueError("manifest must contain exactly four experiment plans")
    names = [plan.get("arm") for plan in plans if isinstance(plan, dict)]
    if set(names) != set(EXPERIMENT_ARMS) or len(names) != len(EXPERIMENT_ARMS):
        raise ValueError("manifest experiment arms are invalid")
    model_fingerprints = {
        plan.get("modelArtifactFingerprint")
        for plan in plans
        if isinstance(plan, dict)
    }
    if len(model_fingerprints) != len(EXPERIMENT_ARMS) or not all(
        isinstance(value, str) and SHA256_PATTERN.fullmatch(value)
        for value in model_fingerprints
    ):
        raise ValueError("manifest model artifact fingerprints must be distinct")
    for plan in plans:
        if not isinstance(plan, dict):
            raise ValueError("manifest experiment plans must be objects")
        if not isinstance(plan.get("trainingConfigFingerprint"), str) or not (
            SHA256_PATTERN.fullmatch(plan["trainingConfigFingerprint"])
        ):
            raise ValueError(
                "manifest training config fingerprints must be SHA-256"
            )
        for field in (
            "modelVersion",
            "rewardSchemaVersion",
            "sourceDeployment",
        ):
            if not isinstance(plan.get(field), str) or not plan[field].strip():
                raise ValueError(f"manifest plan {field} must not be blank")


def load_evidence(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    raw = load_object(path)
    extra = set(raw) - set(EXPERIMENT_ARMS)
    if extra:
        raise ValueError(f"Unknown evidence arms: {sorted(extra)}")
    evidence: dict[str, str] = {}
    for arm, value in raw.items():
        if not isinstance(value, dict):
            raise ValueError(f"{arm} evidence must be an object")
        run_id = value.get("evaluationRunId", "")
        if not isinstance(run_id, str):
            raise ValueError(f"{arm}.evaluationRunId must be a string")
        if run_id.strip():
            evidence[arm] = run_id.strip()
    return evidence


def planned_operations(
    manifest: dict[str, Any],
    evidence: dict[str, str],
    base_url: str,
    finalize: bool,
) -> list[dict[str, Any]]:
    validate_manifest(manifest)
    base = base_url.rstrip("/")
    operations: list[dict[str, Any]] = [
        {
            "method": "POST",
            "url": f"{base}/alignment-experiments",
            "body": manifest["experimentRequest"],
        }
    ]
    for arm in EXPERIMENT_ARMS:
        run_id = evidence.get(arm)
        if run_id:
            operations.append(
                {
                    "method": "POST",
                    "url": (
                        f"{base}/alignment-experiments/"
                        f"{{experimentId}}/arms/{arm}/evidence"
                    ),
                    "body": {"evaluationRunId": run_id},
                }
            )
    if finalize:
        missing = [arm for arm in EXPERIMENT_ARMS if arm not in evidence]
        if missing:
            raise ValueError(
                "Cannot finalize without evidence for: " + ", ".join(missing)
            )
        operations.append(
            {
                "method": "POST",
                "url": (
                    f"{base}/alignment-experiments/"
                    "{experimentId}/finalize"
                ),
                "body": None,
            }
        )
    return operations


def execute_operations(operations: list[dict[str, Any]]) -> dict[str, Any]:
    experiment_id = ""
    last_response: dict[str, Any] = {}
    for operation in operations:
        url = operation["url"].replace("{experimentId}", experiment_id)
        body = operation["body"]
        request = urllib.request.Request(
            url,
            data=(
                json.dumps(body, ensure_ascii=False).encode("utf-8")
                if body is not None
                else b""
            ),
            headers={"Content-Type": "application/json"},
            method=operation["method"],
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                value = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, json.JSONDecodeError) as exception:
            raise ValueError(f"Experiment API request failed: {url}: {exception}")
        if not isinstance(value, dict):
            raise ValueError(f"Experiment API returned a non-object: {url}")
        last_response = value
        if not experiment_id:
            experiment_id = value.get("experimentId", "")
            if not isinstance(experiment_id, str) or not experiment_id:
                raise ValueError("Create experiment response has no experimentId")
    return last_response


def main() -> int:
    args = parse_args()
    try:
        manifest = load_object(args.manifest)
        evidence = load_evidence(args.evidence)
        operations = planned_operations(
            manifest,
            evidence,
            args.base_url,
            args.finalize,
        )
        if not args.execute:
            print(
                json.dumps(
                    {
                        "mode": "dry-run",
                        "operations": operations,
                        "cloudTrainingOrEvaluationStarted": False,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        result = execute_operations(operations)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"Experiment orchestration failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

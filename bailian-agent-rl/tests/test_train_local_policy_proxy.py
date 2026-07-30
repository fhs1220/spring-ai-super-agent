from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "train_local_policy_proxy.py"
)
SPEC = importlib.util.spec_from_file_location(
    "train_local_policy_proxy",
    MODULE_PATH,
)
local_train = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(local_train)


class TrainLocalPolicyProxyTest(unittest.TestCase):

    def test_manifest_verification_accepts_frozen_data(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, manifest_path = fixture(root)

            config, manifest, training, validation = (
                local_train.verify_manifest(
                    config_path,
                    manifest_path,
                )
            )

        self.assertEqual(
            "local-policy-proxy-dpo-v1",
            config["schema_version"],
        )
        self.assertEqual(1, manifest["training_count"])
        self.assertEqual("train.jsonl", training.name)
        self.assertEqual("validation.jsonl", validation.name)

    def test_manifest_verification_rejects_changed_data(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            config_path, manifest_path = fixture(root)
            (root / "train.jsonl").write_text("changed\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "fingerprint changed"):
                local_train.verify_manifest(
                    config_path,
                    manifest_path,
                )

    def test_execute_requires_explicit_local_gate(self) -> None:
        preflight = {"state": "READY", "issues": []}

        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(
                ValueError,
                local_train.EXECUTION_GATE,
            ):
                local_train.require_execution_authorization(preflight)

    def test_execute_rejects_not_ready_preflight(self) -> None:
        preflight = {
            "state": "NOT_READY",
            "issues": ["model missing"],
        }

        with patch.dict(
            os.environ,
            {local_train.EXECUTION_GATE: "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "model missing"):
                local_train.require_execution_authorization(preflight)

    def test_disk_probe_accepts_a_missing_nested_model_path(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)

            probe = local_train.existing_disk_probe(
                root / "missing/model/snapshot"
            )

        self.assertEqual(root, probe)


def fixture(root: Path) -> tuple[Path, Path]:
    config = {
        "schema_version": "local-policy-proxy-dpo-v1",
        "experiment_id": "test",
    }
    config_path = root / "config.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    training_path = root / "train.jsonl"
    validation_path = root / "validation.jsonl"
    training_path.write_text("{}\n", encoding="utf-8")
    validation_path.write_text("{}\n", encoding="utf-8")
    payload = {
        "schema_version": "local-preference-freeze-v1",
        "config_fingerprint": sha256(config_path),
        "training_path": str(training_path),
        "training_fingerprint": sha256(training_path),
        "training_count": 1,
        "validation_path": str(validation_path),
        "validation_fingerprint": sha256(validation_path),
        "validation_count": 1,
        "benchmark_audit": {"passed": True},
        "cloud_training_cny": 0,
        "model_api_calls": 0,
    }
    manifest = {
        **payload,
        "freeze_fingerprint": local_train.canonical_sha256(payload),
    }
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return config_path, manifest_path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()

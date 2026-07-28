from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "run_experiment.py"
SPEC = importlib.util.spec_from_file_location("run_experiment", MODULE_PATH)
run_experiment = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(run_experiment)


class RunExperimentTest(unittest.TestCase):

    def test_plans_create_attach_and_finalize_operations(self) -> None:
        manifest = valid_manifest()
        evidence = {
            arm: f"rag-ab-{index}"
            for index, arm in enumerate(run_experiment.EXPERIMENT_ARMS)
        }

        operations = run_experiment.planned_operations(
            manifest,
            evidence,
            "http://127.0.0.1:8123/api/agent-evaluation/",
            True,
        )

        self.assertEqual(6, len(operations))
        self.assertTrue(
            operations[0]["url"].endswith("/alignment-experiments")
        )
        self.assertIn(
            "BASELINE_STATIC_REWARD/evidence", operations[1]["url"]
        )
        self.assertTrue(operations[-1]["url"].endswith("/finalize"))

    def test_rejects_tampered_manifest(self) -> None:
        manifest = valid_manifest()
        manifest["experimentRequest"]["arms"][0]["modelVersion"] = "tampered"

        with self.assertRaisesRegex(ValueError, "does not match"):
            run_experiment.validate_manifest(manifest)

    def test_refuses_finalize_with_missing_evidence(self) -> None:
        with self.assertRaisesRegex(ValueError, "Cannot finalize"):
            run_experiment.planned_operations(
                valid_manifest(),
                {"RLVR_ONLY": "rag-ab-1"},
                "http://localhost",
                True,
            )


def valid_manifest() -> dict:
    plans = [
        {
            "arm": arm,
            "modelVersion": f"model-{index}",
            "modelArtifactFingerprint": (
                f"{index + 1:x}" * 64
            ),
            "trainingConfigFingerprint": "a" * 64,
            "rewardSchemaVersion": "reward-v2",
            "sourceDeployment": f"deployment-{index}",
        }
        for index, arm in enumerate(run_experiment.EXPERIMENT_ARMS)
    ]
    payload = {
        "schemaVersion": run_experiment.SCHEMA_VERSION,
        "sourceSpecSha256": "b" * 64,
        "arms": [],
        "experimentRequest": {"arms": plans},
        "runtimeEnvironments": {},
    }
    return {
        **payload,
        "manifestFingerprint": run_experiment.canonical_sha256(payload),
    }


if __name__ == "__main__":
    unittest.main()

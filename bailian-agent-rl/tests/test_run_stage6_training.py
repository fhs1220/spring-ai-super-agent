from __future__ import annotations

import asyncio
import hashlib
import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
MODULE_PATH = ROOT / "run_stage6_training.py"
SPEC = importlib.util.spec_from_file_location("run_stage6_training", MODULE_PATH)
stage6 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(stage6)


class Stage6TrainingOrchestratorTest(unittest.TestCase):

    def test_build_plan_validates_all_three_frozen_arms(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = frozen_stage5_fixture(root)

            plan = stage6.build_plan(manifest, root)

        self.assertEqual(
            [
                "BASELINE_STATIC_REWARD",
                "RLVR_ONLY",
                "RLVR_RLAIF",
            ],
            plan["submission_order"],
        )
        self.assertEqual(
            3,
            len(plan["arms"]),
        )
        self.assertEqual(
            3,
            plan["arms"]["RLVR_RLAIF"]["training_count"],
        )

    def test_build_plan_rejects_changed_dataset(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = frozen_stage5_fixture(root)
            changed = (
                root
                / "tmp/stage5/baseline-static-reward/rl-train.jsonl"
            )
            changed.write_text(
                changed.read_text(encoding="utf-8") + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "does not match"):
                stage6.build_plan(manifest, root)

    def test_ambiguous_submission_is_persisted_and_not_retried(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = frozen_stage5_fixture(root)
            plan = stage6.build_plan(manifest, root)
            output = root / "stage6.json"
            sdk = {"installed_version": "1.25.23"}

            async def ambiguous_submit(config, training, validation):
                raise RuntimeError("connection lost after request")

            with patch.dict(
                os.environ,
                {
                    "BAILIAN_RL_ALLOW_BILLING": "true",
                    "DASHSCOPE_API_KEY": "test-key",
                    "FC_PYPI_LIB": "test.whl",
                    "AGENT_RL_RETRIEVAL_URL": "https://example.test",
                    "AGENT_RL_RETRIEVAL_TOKEN": "test-token",
                },
                clear=False,
            ), patch.object(
                stage6.submit_job,
                "require_execution_authorization",
                return_value=None,
            ), patch.object(
                stage6.submit_job,
                "verify_retrieval_environment",
                return_value={
                    "valid_token_http_status": 200,
                    "invalid_token_http_status": 401,
                    "document_count": 1,
                },
            ):
                with self.assertRaisesRegex(RuntimeError, "connection lost"):
                    asyncio.run(
                        stage6.execute_plan(
                            plan,
                            sdk,
                            output,
                            root,
                            ambiguous_submit,
                        )
                    )

            report = stage6.load_object(output)
            self.assertEqual("RECONCILIATION_REQUIRED", report["state"])
            self.assertEqual(
                "SUBMISSION_OUTCOME_UNKNOWN",
                report["arms"]["BASELINE_STATIC_REWARD"][
                    "submission_state"
                ],
            )
            with self.assertRaisesRegex(ValueError, "reconcile"):
                stage6.load_execution_report(output, plan, sdk)


def frozen_stage5_fixture(root: Path) -> Path:
    config_directory = root / "bailian-agent-rl/experiments/configs"
    stage5_directory = root / "tmp/stage5"
    config_directory.mkdir(parents=True)
    arms = {}
    for arm, slug in stage6.ARMS:
        package = stage5_directory / slug
        package.mkdir(parents=True)
        config_path = config_directory / f"{slug}.json"
        config_path.write_text(
            json.dumps(config(arm), ensure_ascii=False),
            encoding="utf-8",
        )
        training_path = package / "rl-train.jsonl"
        validation_path = package / "rl-validation.jsonl"
        write_jsonl(
            training_path,
            [sample(f"{arm}-train-{index}") for index in range(3)],
        )
        write_jsonl(
            validation_path,
            [sample(f"{arm}-validation")],
        )
        arms[arm] = {
            "batch_size": 2,
            "config_path": str(config_path.relative_to(root)),
            "training_path": str(training_path.relative_to(root)),
            "validation_path": str(validation_path.relative_to(root)),
            "config_fingerprint": file_hash(config_path),
            "training_fingerprint": file_hash(training_path),
            "validation_fingerprint": file_hash(validation_path),
            "training_count": 3,
            "validation_count": 1,
            "ready_for_submission": True,
        }
    payload = {
        "schema_version": stage6.STAGE5_SCHEMA_VERSION,
        "benchmark_audit": {"passed": True},
        "arms": arms,
        "blocked_arms": {
            "FULL_TRAJECTORY_GUIDED":
                "requires_cross_policy_reward_trajectories",
        },
    }
    manifest = {
        **payload,
        "freeze_fingerprint": stage6.canonical_sha256(payload),
    }
    path = stage5_directory / "manifest.json"
    path.write_text(
        json.dumps(manifest, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def config(arm: str) -> dict:
    return {
        "model": "qwen3.5-9b",
        "alignment_arm": arm,
        "reward_schema_version": (
            stage6.submit_job.STATIC_REWARD_SCHEMA_VERSION
            if arm == "BASELINE_STATIC_REWARD"
            else stage6.submit_job.REWARD_SCHEMA_VERSION
        ),
        "reward_metric_weights": {
            "reference_quality": 0.10,
            "grounding_quality": 0.20,
            "citation_quality": 0.10,
            "task_completion_quality": 0.20,
            "safety_boundary_quality": 0.10,
            "retrieval_quality": 0.10,
            "convergence_quality": 0.05,
            "efficiency": 0.05,
            "anti_hacking_quality": 0.10,
        },
        "resource_config": {
            "charge_type": "mtu_postpaid",
            "mtu_spec_code": "MTU4",
            "mtu_capacity": 24,
        },
        "hyper_parameters": {
            "algorithm": "gspo",
            "batch_size": 2,
            "n_epochs": 1,
            "n_rollouts": 4,
            "max_length": 4096,
        },
        "function_runtime": {"rollout": {}, "reward": {}},
    }


def sample(question: str) -> dict:
    return {
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {"solution": "reference"},
    }


def write_jsonl(path: Path, samples: list[dict]) -> None:
    path.write_text(
        "".join(
            json.dumps(item, ensure_ascii=False) + "\n"
            for item in samples
        ),
        encoding="utf-8",
    )


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()

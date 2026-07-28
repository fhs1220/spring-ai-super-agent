from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


MODULE_PATH = Path(__file__).resolve().parents[1] / "submit_job.py"
SPEC = importlib.util.spec_from_file_location("submit_job", MODULE_PATH)
submit_job = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(submit_job)


class SubmitJobPreflightTest(unittest.TestCase):

    def test_validates_bailian_dataset_without_cloud_sdk(self) -> None:
        config = {
            "model": "qwen3.5-9b",
            "alignment_arm": "FULL_TRAJECTORY_GUIDED",
            "reward_schema_version": submit_job.REWARD_SCHEMA_VERSION,
            "reward_metric_weights": reward_weights(),
            "resource_config": {"charge_type": "mtu_postpaid"},
            "hyper_parameters": {"batch_size": 2},
            "function_runtime": {"rollout": {}, "reward": {}},
        }
        training = [sample(f"训练问题 {index}") for index in range(3)]
        validation = [sample("验证问题")]

        submit_job.validate_config(config)
        submit_job.validate_package(config, training, validation)

    def test_reads_jsonl_and_rejects_train_validation_overlap(self) -> None:
        with TemporaryDirectory() as directory:
            dataset_path = Path(directory) / "data.jsonl"
            dataset_path.write_text(
                json.dumps(sample("重复问题"), ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            loaded = submit_job.load_dataset(dataset_path)

        self.assertEqual(1, len(loaded))
        config = {
            "hyper_parameters": {"batch_size": 1},
        }
        training = [loaded[0], sample("另一个训练问题")]
        with self.assertRaisesRegex(ValueError, "overlap"):
            submit_job.validate_package(config, training, loaded)

    def test_rejects_reward_weights_that_do_not_sum_to_one(self) -> None:
        weights = reward_weights()
        weights["reference_quality"] = 0.50
        config = {
            "model": "qwen3.5-9b",
            "alignment_arm": "FULL_TRAJECTORY_GUIDED",
            "reward_schema_version": submit_job.REWARD_SCHEMA_VERSION,
            "reward_metric_weights": weights,
            "resource_config": {"charge_type": "mtu_postpaid"},
            "hyper_parameters": {"batch_size": 2},
            "function_runtime": {"rollout": {}, "reward": {}},
        }

        with self.assertRaisesRegex(ValueError, "sum to 1.0"):
            submit_job.validate_config(config)

    def test_rejects_trajectory_seed_as_completed_rollout(self) -> None:
        value = sample("待回放问题")
        value["dataset_role"] = "trajectory_seed_only"

        with self.assertRaisesRegex(ValueError, "not a completed model rollout"):
            submit_job.validate_sample(value, Path("seeds.jsonl"), 1)


def sample(question: str) -> dict:
    return {
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {"solution": "参考答案"},
    }


def reward_weights() -> dict[str, float]:
    return {
        "reference_quality": 0.10,
        "grounding_quality": 0.20,
        "citation_quality": 0.10,
        "task_completion_quality": 0.20,
        "safety_boundary_quality": 0.10,
        "retrieval_quality": 0.10,
        "convergence_quality": 0.05,
        "efficiency": 0.05,
        "anti_hacking_quality": 0.10,
    }


if __name__ == "__main__":
    unittest.main()

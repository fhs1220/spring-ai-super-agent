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


def sample(question: str) -> dict:
    return {
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {"solution": "参考答案"},
    }


if __name__ == "__main__":
    unittest.main()

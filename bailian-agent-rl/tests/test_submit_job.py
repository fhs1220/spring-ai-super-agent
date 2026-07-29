from __future__ import annotations

import importlib.util
import asyncio
import json
import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch


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
            "resource_config": resource_config(),
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
            "resource_config": resource_config(),
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

    def test_rejects_qwen_9b_below_official_mtu_minimum(self) -> None:
        config = {
            "model": "qwen3.5-9b",
            "alignment_arm": "RLVR_ONLY",
            "reward_schema_version": submit_job.REWARD_SCHEMA_VERSION,
            "reward_metric_weights": reward_weights(),
            "resource_config": {
                **resource_config(),
                "mtu_capacity": 1,
            },
            "hyper_parameters": {"batch_size": 2},
            "function_runtime": {"rollout": {}, "reward": {}},
        }

        with self.assertRaisesRegex(ValueError, "at least 24 MTU4"):
            submit_job.validate_config(config)

    def test_submit_uses_the_sdk_resources_keyword(self) -> None:
        captured = {}

        class FakeAgenticRl:

            async def run(self, **kwargs):
                captured.update(kwargs)
                return SimpleNamespace(
                    output=SimpleNamespace(job_id="ft-test")
                )

        config = {
            "model": "qwen3.5-9b",
            "alignment_arm": "RLVR_ONLY",
            "reward_schema_version": submit_job.REWARD_SCHEMA_VERSION,
            "reward_metric_weights": reward_weights(),
            "resource_config": resource_config(),
            "hyper_parameters": {"batch_size": 2},
            "function_runtime": {"rollout": {}, "reward": {}},
        }
        with patch.object(
            submit_job,
            "require_sdk_version",
            return_value=None,
        ), patch(
            "dashscope.finetune.agentic_rl.AgenticRL",
            FakeAgenticRl,
        ), patch.dict(
            os.environ,
            {
                "AGENT_RL_RETRIEVAL_URL":
                    "https://retrieval.example.test",
                "AGENT_RL_RETRIEVAL_TOKEN": "test-token",
            },
            clear=False,
        ):
            job_id = asyncio.run(
                submit_job.submit(
                    config,
                    Path("train.jsonl"),
                    Path("validation.jsonl"),
                )
            )

        self.assertEqual("ft-test", job_id)
        self.assertEqual(resource_config(), captured["resources"])
        self.assertNotIn("resource_config", captured)

    def test_execution_requires_the_frozen_agentic_rl_wheel(self) -> None:
        with TemporaryDirectory() as directory:
            wheel = (
                Path(directory)
                / "dashscope-1.25.23-py3-none-any.whl"
            )
            wheel.touch()
            environment = {
                "BAILIAN_RL_ALLOW_BILLING": "true",
                "DASHSCOPE_API_KEY": "test-key",
                "FC_PYPI_LIB": "dashscope-1.25.16-py3-none-any.whl",
                "AGENT_RL_RETRIEVAL_URL": "https://example.test",
                "AGENT_RL_RETRIEVAL_TOKEN": "test-token",
            }
            with patch.dict(os.environ, environment, clear=True), patch(
                "pathlib.Path.cwd", return_value=Path(directory)
            ):
                with self.assertRaisesRegex(ValueError, "1.25.23"):
                    submit_job.require_execution_authorization()

    def test_retrieval_environment_requires_https_origin(self) -> None:
        with self.assertRaisesRegex(ValueError, "HTTPS origin"):
            submit_job.validate_retrieval_url(
                "http://127.0.0.1:8123/api/agent-rl/environment"
            )

    def test_retrieval_environment_checks_valid_and_invalid_tokens(self) -> None:
        calls = []

        def opener(request, timeout):
            calls.append((request, timeout))
            token = request.get_header("X-agent-rl-token")
            if token == "test-token":
                return FakeResponse(
                    200,
                    {"documents": [{"id": "doc-1"}]},
                )
            return FakeResponse(401, {"status": 401})

        with patch.dict(
            os.environ,
            {
                "AGENT_RL_RETRIEVAL_URL": "https://retrieval.example.test",
                "AGENT_RL_RETRIEVAL_TOKEN": "test-token",
            },
            clear=False,
        ):
            result = submit_job.verify_retrieval_environment(
                opener=opener,
                timeout_seconds=1.0,
            )

        self.assertEqual(2, len(calls))
        self.assertEqual(200, result["valid_token_http_status"])
        self.assertEqual(401, result["invalid_token_http_status"])
        self.assertEqual(1, result["document_count"])


class FakeResponse:

    def __init__(self, status: int, payload: dict) -> None:
        self.status = status
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception, traceback):
        return False

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


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


def resource_config() -> dict[str, Any]:
    return {
        "charge_type": "mtu_postpaid",
        "mtu_spec_code": "MTU4",
        "mtu_capacity": 24,
    }


if __name__ == "__main__":
    unittest.main()

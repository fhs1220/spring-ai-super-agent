from __future__ import annotations

import importlib.util
import os
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "replay_training_seeds.py"
SPEC = importlib.util.spec_from_file_location("replay_training_seeds", MODULE_PATH)
replay = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(replay)


class ReplayTrainingSeedsTest(unittest.TestCase):

    def test_build_plan_is_deterministic_and_has_two_rounds(self) -> None:
        seeds = [seed("a" * 20, "问题一"), seed("b" * 20, "问题二")]

        first = replay.build_plan(seeds, "batch-001", 2, None)
        second = replay.build_plan(seeds, "batch-001", 2, None)

        self.assertEqual(first, second)
        self.assertEqual(4, len(first))
        self.assertEqual({1, 2}, {item["round"] for item in first})
        self.assertEqual(4, len({item["run_id"] for item in first}))

    def test_execute_requires_double_authorization(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "ALLOW_MODEL_CALLS"):
                replay.require_execution_authorization("policy-v1")

        with patch.dict(
            os.environ,
            {"AGENT_RL_REPLAY_ALLOW_MODEL_CALLS": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "policy-version"):
                replay.require_execution_authorization(None)

    def test_parses_complete_sse_event(self) -> None:
        response = [
            b"event: accepted\n",
            b'data: {"runId":"run-1"}\n',
            b"\n",
            b"event: complete\n",
            b'data: {"trajectoryId":"trajectory-1","answer":"ok"}\n',
            b"\n",
        ]

        result = replay.parse_sse_complete(response)

        self.assertEqual("trajectory-1", result["trajectoryId"])

    def test_preflight_explains_required_management_api(self) -> None:
        with patch.object(
            replay,
            "request_json",
            side_effect=ValueError("HTTP Error 404"),
        ):
            with self.assertRaisesRegex(ValueError, "AGENT_RL_API_ENABLED=true"):
                replay.preflight_server("http://127.0.0.1:8123/api", 10)


def seed(seed_hash: str, question: str) -> dict:
    return {
        "schema_version": replay.SEED_SCHEMA_VERSION,
        "dataset_role": replay.DATASET_ROLE,
        "seed_id": f"seed-{seed_hash}",
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {
            "benchmark_guard": {"overlap": False},
        },
    }


if __name__ == "__main__":
    unittest.main()

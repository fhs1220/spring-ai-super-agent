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
        self.assertEqual(
            replay.plan_fingerprint(first),
            replay.plan_fingerprint(second),
        )
        self.assertEqual(
            {"SINGLE_AGENT"},
            {item["expected_execution_mode"] for item in first},
        )

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

    def test_adds_auditable_execution_summary(self) -> None:
        summary = {
            "results": [
                {
                    "reward": 0.9,
                    "rlvr": {
                        "total": 0.7,
                        "hard_gate_passed": True,
                        "violations": [],
                    },
                    "execution_mode": "SINGLE_AGENT",
                    "telemetry": {
                        "model_call_count": 4,
                        "total_tokens": 1000,
                        "estimated_cost_cny": 0.01,
                        "timeout_count": 0,
                    },
                },
                {
                    "reward": 0.8,
                    "rlvr": {
                        "total": 0.6,
                        "hard_gate_passed": True,
                        "violations": ["instruction_contract_incomplete"],
                    },
                    "execution_mode": "ADAPTIVE_MULTI_AGENT",
                    "telemetry": {
                        "model_call_count": 7,
                        "total_tokens": 2000,
                        "estimated_cost_cny": 0.02,
                        "timeout_count": 1,
                    },
                },
            ]
        }

        replay.add_execution_summary(summary)

        self.assertEqual(11, summary["underlying_model_call_count"])
        self.assertEqual(3000, summary["observed_telemetry"]["total_tokens"])
        self.assertEqual(0.03, summary["observed_telemetry"]["estimated_cost_cny"])
        self.assertEqual(0.65, summary["rlvr_summary"]["average"])
        self.assertEqual(1, summary["rlvr_summary"]["violation_count"])
        self.assertEqual(
            {"evaluated": 0, "matched": 0, "mismatched": 0},
            summary["route_expectation_summary"],
        )

    def test_v2_multi_seed_requires_distinct_source_fingerprints(self) -> None:
        value = seed("c" * 20, "育儿和家务如何共同分工？")
        value["rollout_extra"]["route_expectation"] = {
            "execution_mode": "ADAPTIVE_MULTI_AGENT",
            "detected_domains": ["PARENTING", "HOUSEHOLD"],
            "selected_domains": ["PARENTING", "HOUSEHOLD"],
            "minimum_domains": 2,
            "max_agents": 3,
            "router_contract": replay.ROUTER_CONTRACT_VERSION,
        }

        with self.assertRaisesRegex(ValueError, "at least two"):
            replay.validate_seed(value, Path("seeds.jsonl"), 1)

    def test_legacy_v1_seed_remains_replayable(self) -> None:
        value = seed("e" * 20, "旧种子问题")
        value["schema_version"] = "agent-rl-trajectory-seed-v1"
        value["rollout_extra"].pop("route_expectation")
        value["rollout_extra"].pop("source_provenance")

        replay.validate_seed(value, Path("legacy-seeds.jsonl"), 1)


def seed(seed_hash: str, question: str) -> dict:
    return {
        "schema_version": replay.SEED_SCHEMA_VERSION,
        "dataset_role": replay.DATASET_ROLE,
        "seed_id": f"seed-{seed_hash}",
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {
            "benchmark_guard": {"overlap": False},
            "source_provenance": [
                {
                    "path": "source.md",
                    "section": "section",
                    "content_sha256": "d" * 64,
                }
            ],
            "route_expectation": {
                "execution_mode": "SINGLE_AGENT",
                "detected_domains": ["RELATIONSHIP"],
                "selected_domains": [],
                "minimum_domains": 2,
                "max_agents": 3,
                "router_contract": replay.ROUTER_CONTRACT_VERSION,
            },
        },
    }


if __name__ == "__main__":
    unittest.main()

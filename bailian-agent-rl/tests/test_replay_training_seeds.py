from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "replay_training_seeds.py"
SPEC = importlib.util.spec_from_file_location("replay_training_seeds", MODULE_PATH)
replay = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(replay)


class FakeResponse:

    def __init__(self, lines: list[bytes]) -> None:
        self.lines = lines

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def __iter__(self):
        return iter(self.lines)


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

    def test_resumes_failed_durable_run_and_audits_timeout(self) -> None:
        request = replay.urllib.request.Request(
            "http://localhost/start",
            data=b"{}",
            method="POST",
        )
        conflict = replay.urllib.error.HTTPError(
            request.full_url, 409, "Conflict", {}, None
        )
        response = FakeResponse([
            b"event: complete\n",
            b'data: {"trajectoryId":"trajectory-1"}\n',
            b"\n",
        ])
        with (
            patch.object(
                replay.urllib.request,
                "urlopen",
                side_effect=[conflict, response],
            ),
            patch.object(
                replay,
                "request_json",
                return_value={
                    "status": "FAILED",
                    "attempt": 1,
                    "error": "GENERATE model call exceeded 30000 ms",
                },
            ),
        ):
            result, recovery = replay.request_agent_result(
                request, "http://localhost/runs/run-1", 10
            )

        self.assertEqual("trajectory-1", result["trajectoryId"])
        self.assertEqual(1, recovery["prior_timeout_count"])
        self.assertEqual("FAILED", recovery["prior_status"])

    def test_loads_only_completed_deterministic_prefix_for_resume(self) -> None:
        plan = replay.build_plan(
            [seed("a" * 20, "问题一"), seed("b" * 20, "问题二")],
            "batch-001",
            2,
            None,
        )
        summary = {
            "batch_id": "batch-001",
            "plan_fingerprint": replay.plan_fingerprint(plan),
            "planned_agent_runs": 4,
            "policy_version": "policy-v1",
            "results": [],
        }
        completed = {
            "run_id": plan[0]["run_id"],
            "status": "COMPLETED",
        }
        existing = {**summary, "results": [completed]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(existing), encoding="utf-8")

            count = replay.load_partial_results(path, summary, plan)

        self.assertEqual(1, count)
        self.assertEqual([completed], summary["results"])
        self.assertEqual(1, summary["resumed_from_completed_agent_runs"])

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
                    "recovery": {
                        "resumed": True,
                        "prior_timeout_count": 1,
                        "prior_error": "GENERATE model call exceeded 30000 ms",
                    },
                },
            ]
        }

        replay.add_execution_summary(summary)

        self.assertEqual(11, summary["underlying_model_call_count"])
        self.assertEqual(3000, summary["observed_telemetry"]["total_tokens"])
        self.assertEqual(0.03, summary["observed_telemetry"]["estimated_cost_cny"])
        self.assertEqual(2, summary["observed_telemetry"]["timeout_count"])
        self.assertEqual(0.65, summary["rlvr_summary"]["average"])
        self.assertEqual(1, summary["rlvr_summary"]["violation_count"])
        self.assertEqual(
            {"evaluated": 0, "matched": 0, "mismatched": 0},
            summary["route_expectation_summary"],
        )
        self.assertEqual(1, summary["recovery_summary"]["recovered_run_count"])

    def test_replay_gate_passes_clean_run_and_rejects_route_timeout(self) -> None:
        summary = {
            "planned_agent_runs": 2,
            "expected_execution_modes": {
                "SINGLE_AGENT": 1,
                "ADAPTIVE_MULTI_AGENT": 1,
            },
            "results": [
                replay_result("SINGLE_AGENT", 0.76),
                replay_result("ADAPTIVE_MULTI_AGENT", 0.74),
            ],
        }

        replay.add_execution_summary(summary)
        replay.add_replay_gate(
            summary,
            minimum_rlvr_average=0.70,
            maximum_route_mismatches=0,
            maximum_timeouts=0,
            maximum_rlvr_violations=0,
        )

        self.assertTrue(summary["replay_gate"]["passed"])
        self.assertEqual(2, summary["billable_operations"])

        summary["results"][1]["route_expectation_matched"] = False
        summary["results"][1]["telemetry"]["timeout_count"] = 1
        replay.add_execution_summary(summary)
        replay.add_replay_gate(
            summary,
            minimum_rlvr_average=0.70,
            maximum_route_mismatches=0,
            maximum_timeouts=0,
            maximum_rlvr_violations=0,
        )

        self.assertFalse(summary["replay_gate"]["passed"])
        self.assertIn(
            "route_mismatches_within_limit",
            summary["replay_gate"]["failures"],
        )
        self.assertIn(
            "timeouts_within_limit",
            summary["replay_gate"]["failures"],
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


def replay_result(mode: str, rlvr_total: float) -> dict:
    return {
        "status": "COMPLETED",
        "reward": 0.8,
        "rlvr": {
            "total": rlvr_total,
            "hard_gate_passed": True,
            "violations": [],
        },
        "execution_mode": mode,
        "route_expectation_matched": True,
        "telemetry": {
            "model_call_count": 1,
            "total_tokens": 100,
            "estimated_cost_cny": 0.001,
            "timeout_count": 0,
        },
    }


if __name__ == "__main__":
    unittest.main()

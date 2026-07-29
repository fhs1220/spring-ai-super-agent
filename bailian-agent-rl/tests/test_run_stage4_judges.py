from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "run_stage4_judges.py"
SPEC = importlib.util.spec_from_file_location("run_stage4_judges", MODULE_PATH)
stage4 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(stage4)


class Stage4JudgePlanTest(unittest.TestCase):

    def test_selects_highest_rlvr_once_per_qualified_question(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_trajectory(root, "trajectory-a1", "问题一", "policy-v7")
            write_trajectory(root, "trajectory-a2", "问题一", "policy-v7")
            replay = valid_replay()
            seeds = {"seed-a": seed("seed-a", "问题一", "actions")}

            plan = stage4.build_plan(replay, seeds, root)

        self.assertEqual(1, len(plan))
        self.assertEqual("trajectory-a2", plan[0]["trajectory_id"])
        self.assertEqual(2, plan[0]["selected_round"])
        self.assertAlmostEqual(0.08, plan[0]["pair_rlvr_gap"])

    def test_rejects_failed_collection_gate(self) -> None:
        replay = valid_replay()
        replay["replay_gate"]["passed"] = False

        with self.assertRaisesRegex(ValueError, "did not pass"):
            stage4.validate_replay(replay)

    def test_execute_requires_double_authorization(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "ALLOW_MODEL_CALLS"):
                stage4.require_execution_authorization()

    def test_execute_preflight_requires_management_api(self) -> None:
        manifest = {
            "source_replay": {"policy_version": "policy-v7"},
            "execution_results": [],
            "plan": [],
        }
        with patch.object(stage4, "request_json", return_value=(None, 404)):
            with self.assertRaisesRegex(ValueError, "API preflight failed"):
                stage4.execute(
                    manifest,
                    "http://127.0.0.1:8123/api",
                    10,
                    Path("unused.json"),
                )

    def test_resume_requires_deterministic_result_prefix(self) -> None:
        planned = {
            "schema_version": stage4.SCHEMA_VERSION,
            "batch_id": "batch-1",
            "plan_fingerprint": "a" * 64,
            "source_replay": {"batch_id": "source"},
            "plan": [
                {"trajectory_id": "trajectory-1"},
                {"trajectory_id": "trajectory-2"},
            ],
            "execution_results": [],
        }
        existing = {
            **planned,
            "execution_results": [{"trajectory_id": "trajectory-2"}],
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "manifest.json"
            output.write_text(json.dumps(existing), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "not the plan prefix"):
                stage4.load_resume(output, planned)

    def test_human_sample_includes_recovery_and_rotates_types(self) -> None:
        plan = [
            plan_item("seed-a", "actions", False, 0.71),
            plan_item("seed-b", "actions", False, 0.72),
            plan_item("seed-c", "dialogue", True, 0.80),
            plan_item("seed-d", "dialogue", False, 0.73),
        ]

        sample = stage4.human_review_sample(plan, 3)

        self.assertIn("seed-c", {item["seed_id"] for item in sample})
        self.assertEqual(
            {"actions", "dialogue"},
            {item["request_type"] for item in sample},
        )


def valid_replay() -> dict:
    return {
        "schema_version": stage4.REPLAY_SCHEMA_VERSION,
        "batch_id": "source-batch",
        "plan_fingerprint": "f" * 64,
        "policy_version": "policy-v7",
        "planned_agent_runs": 2,
        "completed_agent_runs": 2,
        "replay_gate": {"profile": "collection", "passed": True},
        "collection_summary": {
            "schema_version": stage4.QUALIFICATION_SCHEMA_VERSION,
            "qualified_seed_count": 1,
            "qualified_seed_ids": ["seed-a"],
            "minimum_rlvr_per_trajectory": 0.7,
            "required_rounds_per_seed": 2,
        },
        "results": [
            result("seed-a", 1, "trajectory-a1", 0.71),
            result("seed-a", 2, "trajectory-a2", 0.79),
        ],
    }


def result(seed_id: str, round_number: int, trajectory_id: str,
           rlvr: float) -> dict:
    return {
        "seed_id": seed_id,
        "round": round_number,
        "trajectory_id": trajectory_id,
        "policy_version": "policy-v7",
        "status": "COMPLETED",
        "route_expectation_matched": True,
        "execution_mode": "SINGLE_AGENT",
        "rlvr": {
            "total": rlvr,
            "hard_gate_passed": True,
            "violations": [],
        },
    }


def seed(seed_id: str, question: str, request_type: str) -> dict:
    return {
        "seed_id": seed_id,
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {
            "task_group": f"group:{request_type}",
            "route_expectation": {
                "detected_domains": ["RELATIONSHIP"],
            },
        },
    }


def write_trajectory(
    directory: Path,
    trajectory_id: str,
    question: str,
    policy: str,
) -> None:
    (directory / f"{trajectory_id}.json").write_text(
        json.dumps({
            "trajectoryId": trajectory_id,
            "policyVersion": policy,
            "question": question,
            "finalAnswer": "候选答案" * 30,
            "status": "COMPLETED",
        }),
        encoding="utf-8",
    )


def plan_item(
    seed_id: str,
    request_type: str,
    recovered: bool,
    minimum_rlvr: float,
) -> dict:
    return {
        "seed_id": seed_id,
        "trajectory_id": f"trajectory-{seed_id}",
        "execution_mode": "SINGLE_AGENT",
        "domains": ["RELATIONSHIP"],
        "request_type": request_type,
        "had_recovery": recovered,
        "pair_minimum_rlvr": minimum_rlvr,
        "pair_rlvr_gap": 0.05,
    }


if __name__ == "__main__":
    unittest.main()

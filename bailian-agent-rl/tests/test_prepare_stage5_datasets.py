from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "prepare_stage5_datasets.py"
SPEC = importlib.util.spec_from_file_location(
    "prepare_stage5_datasets", MODULE_PATH
)
stage5 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(stage5)


class Stage5DatasetFreezeTest(unittest.TestCase):

    def test_split_is_disjoint_and_keeps_expected_size(self) -> None:
        samples = [
            {"messages": [{"role": "user", "content": f"question-{index}"}]}
            for index in range(119)
        ]

        training, validation = stage5.split_samples(samples, 0.2)

        self.assertEqual(95, len(training))
        self.assertEqual(24, len(validation))
        self.assertFalse(
            {
                sample["messages"][0]["content"] for sample in training
            } & {
                sample["messages"][0]["content"] for sample in validation
            }
        )

    def test_benchmark_audit_rejects_exact_match(self) -> None:
        audit = stage5.benchmark_audit(
            ["请制定一份家庭计划"],
            [{"id": "benchmark-1", "question": "请制定一份家庭计划"}],
        )

        self.assertFalse(audit["passed"])
        self.assertEqual(1, audit["violation_count"])

    def test_benchmark_audit_accepts_unrelated_question(self) -> None:
        audit = stage5.benchmark_audit(
            ["如何安排育儿接送和临时替补"],
            [{"id": "benchmark-1", "question": "异地恋如何保持沟通"}],
        )

        self.assertTrue(audit["passed"])
        self.assertEqual(0, audit["violation_count"])

    def test_training_sample_records_frozen_evidence(self) -> None:
        trajectory = {
            "trajectoryId": "trajectory-1",
            "question": "问题",
            "finalAnswer": "回答",
            "policyVersion": "agentic-rag-v7",
            "reward": {"total": 0.8},
            "retrievedDocumentIds": ["document-1"],
        }
        plan_item = {"seed_id": "seed-1", "rlvr": 0.75}
        replay_result = {
            "rlvr": {
                "total": 0.75,
                "metrics": {"grounding_quality": 0.8},
                "hard_gate_passed": True,
                "violations": [],
            },
        }

        sample = stage5.training_sample(
            trajectory, plan_item, replay_result
        )

        self.assertEqual(
            "trajectory-1",
            sample["rollout_extra"]["source_trajectory_id"],
        )
        self.assertEqual(
            "human-light-rlvr-v3",
            sample["rollout_extra"]["reward_schema_version"],
        )
        self.assertEqual(0.75, sample["rollout_extra"]["source_reward"])
        self.assertEqual(
            0.8,
            sample["rollout_extra"]["source_reward_dimensions"][
                "grounding_quality"
            ],
        )
        self.assertTrue(
            sample["rollout_extra"]["verification_contract"][
                "citation_required"
            ]
        )


if __name__ == "__main__":
    unittest.main()

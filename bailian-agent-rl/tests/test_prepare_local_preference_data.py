from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "prepare_local_preference_data.py"
)
SPEC = importlib.util.spec_from_file_location(
    "prepare_local_preference_data",
    MODULE_PATH,
)
preference = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(preference)


class PrepareLocalPreferenceDataTest(unittest.TestCase):

    def test_selects_the_higher_rlvr_answer(self) -> None:
        replay = {
            "results": [
                result("seed-1", "trajectory-low", 0.72),
                result("seed-1", "trajectory-high", 0.91),
            ]
        }
        trajectories = {
            "trajectory-low": trajectory("问题", "较弱答案"),
            "trajectory-high": trajectory("问题", "更优答案"),
        }

        pairs, counters = preference.qualified_pairs(
            replay,
            trajectories,
            minimum_margin=0.01,
            maximum_answer_characters=700,
        )

        self.assertEqual(1, counters["paired_seeds"])
        self.assertEqual(1, len(pairs))
        self.assertEqual("更优答案", pairs[0]["chosen"])
        self.assertEqual("较弱答案", pairs[0]["rejected"])
        self.assertAlmostEqual(0.19, pairs[0]["rlvr_margin"])

    def test_rejects_pairs_without_a_meaningful_margin(self) -> None:
        replay = {
            "results": [
                result("seed-1", "trajectory-a", 0.80),
                result("seed-1", "trajectory-b", 0.805),
            ]
        }
        trajectories = {
            "trajectory-a": trajectory("问题", "答案 A"),
            "trajectory-b": trajectory("问题", "答案 B"),
        }

        pairs, counters = preference.qualified_pairs(
            replay,
            trajectories,
            minimum_margin=0.01,
            maximum_answer_characters=700,
        )

        self.assertEqual([], pairs)
        self.assertEqual(1, counters["rejected_margin"])

    def test_split_is_stable_and_disjoint(self) -> None:
        pairs = [
            {"pair_id": f"seed-{index}"}
            for index in range(20)
        ]

        training, validation = preference.split_pairs(pairs, 0.2)
        repeated = preference.split_pairs(list(reversed(pairs)), 0.2)

        self.assertEqual(16, len(training))
        self.assertEqual(4, len(validation))
        self.assertEqual(
            {item["pair_id"] for item in validation},
            {item["pair_id"] for item in repeated[1]},
        )
        self.assertFalse(
            {item["pair_id"] for item in training}
            & {item["pair_id"] for item in validation}
        )

    def test_benchmark_audit_detects_exact_leakage(self) -> None:
        audit = preference.benchmark_audit(
            [{"pair_id": "seed-1", "question": "完全相同的问题"}],
            [{"id": "benchmark-1", "question": "完全相同的问题"}],
            threshold=0.82,
        )

        self.assertFalse(audit["passed"])
        self.assertEqual(1, audit["violation_count"])


def result(seed_id: str, trajectory_id: str, score: float) -> dict:
    return {
        "seed_id": seed_id,
        "trajectory_id": trajectory_id,
        "status": "COMPLETED",
        "rlvr": {
            "total": score,
            "hard_gate_passed": True,
        },
    }


def trajectory(question: str, answer: str) -> dict:
    return {
        "question": question,
        "finalAnswer": answer,
    }


if __name__ == "__main__":
    unittest.main()

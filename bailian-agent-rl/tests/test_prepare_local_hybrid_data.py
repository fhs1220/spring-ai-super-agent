from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIRECTORY))
MODULE_PATH = MODULE_DIRECTORY / "prepare_local_hybrid_data.py"
SPEC = importlib.util.spec_from_file_location(
    "prepare_local_hybrid_data",
    MODULE_PATH,
)
hybrid = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(hybrid)


class PrepareLocalHybridDataTest(unittest.TestCase):

    def test_judge_preferences_require_a_positive_rlvr_margin(self) -> None:
        item = {
            "messages": [{"role": "user", "content": "请给三项行动"}],
            "rollout_extra": {
                "source_seed_id": "seed-1",
                "source_trajectory_id": "chosen-1",
                "solution": "更准确且满足约束的答案",
            },
        }
        replay = {
            "results": [
                {
                    "seed_id": "seed-1",
                    "trajectory_id": "chosen-1",
                    "rlvr": {"hard_gate_passed": True, "total": 0.8},
                },
                {
                    "seed_id": "seed-1",
                    "trajectory_id": "rejected-1",
                    "rlvr": {"hard_gate_passed": True, "total": 0.7},
                },
            ],
        }
        trajectories = {
            "rejected-1": {"finalAnswer": "较弱但有效的答案"},
        }

        pairs, counters = hybrid.judge_preference_pairs(
            [item],
            replay,
            trajectories,
            minimum_margin=0.0,
            maximum_answer_characters=700,
        )

        self.assertEqual(len(pairs), 1)
        self.assertAlmostEqual(pairs[0]["rlvr_margin"], 0.1)
        self.assertEqual(counters["rejected_margin"], 0)

    def test_blind_freeze_excludes_replayed_seeds_and_balances_modes(self) -> None:
        def seed(identifier: str, mode: str) -> dict:
            return {
                "seed_id": identifier,
                "messages": [{"role": "user", "content": identifier}],
                "rollout_extra": {
                    "solution": "reference",
                    "tags": [],
                    "route_expectation": {"execution_mode": mode},
                    "verification_contract": {
                        "required_concepts": [],
                        "forbidden_phrases": [],
                        "citation_required": True,
                    },
                },
            }

        seeds = [
            seed("used", "SINGLE_AGENT"),
            seed("single-1", "SINGLE_AGENT"),
            seed("single-2", "SINGLE_AGENT"),
            seed("multi-1", "ADAPTIVE_MULTI_AGENT"),
            seed("multi-2", "ADAPTIVE_MULTI_AGENT"),
        ]

        cases = hybrid.freeze_blind_cases(
            seeds,
            {"results": [{"seed_id": "used"}]},
            per_mode=2,
        )

        self.assertEqual(len(cases), 4)
        self.assertNotIn("blind-used", {case["id"] for case in cases})
        self.assertEqual(
            {
                mode: sum(
                    case["expectedExecutionMode"] == mode
                    for case in cases
                )
                for mode in ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")
            },
            {"SINGLE_AGENT": 2, "ADAPTIVE_MULTI_AGENT": 2},
        )


if __name__ == "__main__":
    unittest.main()

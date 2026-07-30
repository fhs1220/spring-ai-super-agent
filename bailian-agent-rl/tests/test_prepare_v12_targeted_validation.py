from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "prepare_v12_targeted_validation.py"
)
SPEC = importlib.util.spec_from_file_location(
    "prepare_v12_targeted_validation", MODULE_PATH
)
prepare = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(prepare)


class PrepareV12TargetedValidationTest(unittest.TestCase):

    def test_computes_a_bounded_six_by_two_ceiling(self) -> None:
        replay = {
            "completed_agent_runs": 60,
            "underlying_model_call_count": 420,
            "observed_telemetry": {
                "total_tokens": 800_000,
                "estimated_cost_cny": 0.3,
            },
        }

        ceiling = prepare.authorization_ceiling(replay, 12)

        self.assertEqual(126, ceiling["maximum_model_calls"])
        self.assertEqual(240_000, ceiling["maximum_tokens"])
        self.assertEqual(0.09, ceiling["maximum_estimated_cost_cny"])

    def test_requires_two_baseline_rounds_for_every_target(self) -> None:
        evaluation = {
            "candidate_evidence": [
                {"seed_id": "seed-1", "final_rlvr": 0.7},
                {"seed_id": "seed-1", "final_rlvr": 0.8},
            ]
        }

        evidence, average = prepare.baseline_by_seed(
            evaluation, {"seed-1"}
        )

        self.assertEqual(0.75, average)
        self.assertEqual(0.75, evidence[0]["baseline_mean_rlvr"])
        with self.assertRaises(ValueError):
            prepare.baseline_by_seed(evaluation, {"seed-1", "seed-2"})


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "validate_stage4_judge_v2.py"
SPEC = importlib.util.spec_from_file_location(
    "validate_stage4_judge_v2", MODULE_PATH
)
judge_v2 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(judge_v2)


class Stage4JudgeV2ValidationTest(unittest.TestCase):

    def test_positive_requires_every_dimension_floor(self) -> None:
        scores = {
            dimension: {"score": 0.9, "confidence": 0.9}
            for dimension in judge_v2.DIMENSIONS
        }
        scores["ACTIONABILITY"]["score"] = 0.6
        contract = {
            "minimum_instruction_score": 0.7,
            "minimum_actionability_score": 0.7,
            "minimum_logical_score": 0.7,
            "minimum_critical_score": 0.6,
            "minimum_mean_score": 0.72,
            "minimum_dimension_confidence": 0.65,
            "negative_dimension_ceiling": 0.2,
        }

        decision = judge_v2.judge_v2_decision(scores, contract)

        self.assertEqual("HOLDOUT", decision)

    def test_severe_dimension_failure_is_negative(self) -> None:
        scores = {
            dimension: {"score": 0.9, "confidence": 0.9}
            for dimension in judge_v2.DIMENSIONS
        }
        scores["LOGICAL_CONSISTENCY"]["score"] = 0.2
        contract = {
            "minimum_instruction_score": 0.7,
            "minimum_actionability_score": 0.7,
            "minimum_logical_score": 0.7,
            "minimum_critical_score": 0.6,
            "minimum_mean_score": 0.72,
            "minimum_dimension_confidence": 0.65,
            "negative_dimension_ceiling": 0.2,
        }

        decision = judge_v2.judge_v2_decision(scores, contract)

        self.assertEqual("NEGATIVE", decision)

    def test_contract_rejects_wrong_storage_namespace(self) -> None:
        with self.assertRaisesRegex(ValueError, "namespace mismatch"):
            judge_v2.validate_contract({
                "contractVersion": judge_v2.JUDGE_CONTRACT_VERSION,
                "assessmentNamespace": "old-v1-directory",
            })


if __name__ == "__main__":
    unittest.main()

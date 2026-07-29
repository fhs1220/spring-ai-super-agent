from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT_ROOT = Path(__file__).resolve().parents[1]
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))
MODULE_PATH = SCRIPT_ROOT / "prepare_stage4_final_holdout.py"
SPEC = importlib.util.spec_from_file_location(
    "prepare_stage4_final_holdout", MODULE_PATH
)
holdout = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(holdout)


class Stage4FinalHoldoutTest(unittest.TestCase):

    def test_contract_has_exactly_ten_balanced_strata(self) -> None:
        keys = [
            (request_type, mode)
            for request_type in holdout.REQUEST_TYPES
            for mode in holdout.EXECUTION_MODES
        ]

        self.assertEqual(10, len(keys))
        self.assertEqual(10, len(set(keys)))

    def test_final_holdout_label_schema_is_reused(self) -> None:
        self.assertEqual(
            "agent-rl-human-anchor-labels-v1",
            holdout.LABEL_SCHEMA_VERSION,
        )

    def test_final_holdout_contract_is_blind(self) -> None:
        source = MODULE_PATH.read_text(encoding="utf-8")

        self.assertIn('"blind_review": True', source)
        self.assertIn(
            '"show_judge_opinions": not identity["blind_review"]',
            source,
        )
        self.assertNotIn('"judge_scores": [', source)
        for assignment in (
            '"verifier_reward": assessment',
            '"ai_reward": assessment',
            '"total_reward": assessment',
            '"old_confidence": assessment',
            '"old_agreement": assessment',
            '"training_decision": assessment',
        ):
            self.assertNotIn(assignment, source)


if __name__ == "__main__":
    unittest.main()

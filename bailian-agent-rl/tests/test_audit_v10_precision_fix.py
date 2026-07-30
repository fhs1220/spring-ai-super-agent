from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1] / "audit_v10_precision_fix.py"
)
SPEC = importlib.util.spec_from_file_location("audit_v10_precision_fix", MODULE_PATH)
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


class AuditV10PrecisionFixTest(unittest.TestCase):

    def test_failed_reviewer_revision_and_tie_regression_are_covered(self) -> None:
        rows = [{
            "trajectory_id": "trajectory-1",
            "seed_id": "seed-1",
            "selected_contract_passed": False,
            "rlvr_regressive_selection": True,
            "draft_rlvr": 0.80,
            "revision_rlvr": 0.79,
        }]
        trajectories = {
            "trajectory-1": {
                "steps": [
                    {
                        "type": "GENERATE",
                        "output": {
                            "answer": "初稿完整说明共同协商并复盘",
                        },
                    },
                    {
                        "type": "REVIEW",
                        "output": {"revisedAnswer": "仍不完整"},
                    },
                    {
                        "type": "RLVR_SELECT",
                        "output": {
                            "selectedCandidate": "REVISED",
                            "draftScore": 10,
                            "revisedScore": 10,
                        },
                    },
                ],
            },
        }

        result = audit.audit_rows(
            rows,
            trajectories,
            {"seed-1": "共同协商并复盘"},
        )

        self.assertEqual("V10_OFFLINE_COUNTERFACTUAL_READY", result["state"])
        self.assertEqual(
            1,
            result["metrics"]["bounded_repair_covered_failures"],
        )
        self.assertEqual(
            0,
            result["metrics"]["strict_selector_counterfactual_regressions"],
        )


if __name__ == "__main__":
    unittest.main()

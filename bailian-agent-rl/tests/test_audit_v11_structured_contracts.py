from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "audit_v11_structured_contracts.py"
)
SPEC = importlib.util.spec_from_file_location(
    "audit_v11_structured_contracts", MODULE_PATH
)
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


class AuditV11StructuredContractsTest(unittest.TestCase):

    def test_does_not_count_decimal_values_as_actions(self) -> None:
        self.assertEqual(
            0,
            audit.observed_action_items("建议比例：1.5%，按实际情况调整。"),
        )

    def test_resolves_only_existing_structure(self) -> None:
        evaluation = {
            "metrics": {"final_contract_pass_count": 0},
            "candidate_evidence": [
                {
                    "seed_id": "seed-1",
                    "round": 1,
                    "trajectory_id": "trajectory-1",
                    "selected_contract_passed": False,
                },
                {
                    "seed_id": "seed-2",
                    "round": 1,
                    "trajectory_id": "trajectory-2",
                    "selected_contract_passed": False,
                },
            ],
        }
        trajectories = {
            "trajectory-1": {
                "finalAnswer": "行动：1. 了解；2. 执行；3. 复盘。",
                "steps": [
                    {
                        "type": "REVISE",
                        "output": {
                            "attempt": 2,
                            "verificationContractPassed": False,
                        },
                    },
                    {
                        "type": "RLVR_SELECT",
                        "output": {
                            "missingRequirements": [
                                "至少提供 3 个行动项"
                            ],
                        },
                    },
                ],
            },
            "trajectory-2": {
                "finalAnswer": "行动：1. 了解；2. 执行；3. 复盘。",
                "steps": [
                    {
                        "type": "RLVR_SELECT",
                        "output": {
                            "missingRequirements": ["覆盖概念：用心"],
                        },
                    },
                ],
            },
        }

        result = audit.audit(evaluation, trajectories)

        self.assertEqual("V11_OFFLINE_NOT_READY_FOR_REPLAY", result["state"])
        self.assertEqual(
            1, result["metrics"]["deterministic_false_negatives_resolved"]
        )
        self.assertEqual(1, result["metrics"]["remaining_contract_failures"])
        self.assertEqual(0.5, result["metrics"][
            "projected_contract_pass_rate"
        ])
        self.assertEqual(
            1,
            result["metrics"]["avoided_model_rewrites_per_equivalent_replay"],
        )


if __name__ == "__main__":
    unittest.main()

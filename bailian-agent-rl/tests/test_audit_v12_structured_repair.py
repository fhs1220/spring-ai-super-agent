from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "audit_v12_structured_repair.py"
)
SPEC = importlib.util.spec_from_file_location(
    "audit_v12_structured_repair", MODULE_PATH
)
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


class AuditV12StructuredRepairTest(unittest.TestCase):

    def test_routes_structured_content_and_forbidden_phrases_separately(
        self,
    ) -> None:
        v11 = {
            "state": "V11_OFFLINE_NOT_READY_FOR_REPLAY",
            "metrics": {
                "projected_contract_pass_count": 52,
                "trajectory_count": 60,
            },
            "remaining_rows": [
                {
                    "seed_id": "seed-1",
                    "round": 1,
                    "trajectory_id": "trajectory-1",
                    "remaining_requirements": [
                        "覆盖概念：用心",
                        "至少提供 3 个行动项",
                    ],
                },
                {
                    "seed_id": "seed-2",
                    "round": 1,
                    "trajectory_id": "trajectory-2",
                    "remaining_requirements": [
                        "删除禁用短语：推荐课程"
                    ],
                },
            ],
        }

        result = audit.audit(v11)

        self.assertEqual(
            "V12_OFFLINE_READY_FOR_TARGETED_PILOT", result["state"]
        )
        self.assertEqual(3, result["metrics"][
            "remaining_requirement_instances"
        ])
        self.assertEqual(1, result["metrics"][
            "rows_using_structured_content"
        ])
        self.assertEqual(1, result["metrics"]["rows_using_hard_gates"])
        self.assertEqual(0, result["model_api_calls"])

    def test_unknown_requirement_blocks_targeted_pilot(self) -> None:
        v11 = {
            "state": "V11_OFFLINE_NOT_READY_FOR_REPLAY",
            "metrics": {},
            "remaining_rows": [
                {
                    "trajectory_id": "trajectory-1",
                    "remaining_requirements": ["未知要求"],
                }
            ],
        }

        result = audit.audit(v11)

        self.assertEqual("V12_OFFLINE_NOT_READY", result["state"])
        self.assertFalse(result["checks"][
            "every_remaining_requirement_has_a_safe_route"
        ])
        self.assertEqual(1, len(result["unsupported"]))


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIRECTORY))
MODULE_PATH = MODULE_DIRECTORY / "audit_v9_contract_enforcement.py"
SPEC = importlib.util.spec_from_file_location(
    "audit_v9_contract_enforcement",
    MODULE_PATH,
)
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


class AuditV9ContractEnforcementTest(unittest.TestCase):

    def test_detects_required_concepts_actions_and_citations(self) -> None:
        contract = {
            "required_concepts": ["兴趣爱好", "15分钟|十五分钟"],
            "forbidden_phrases": ["推荐课程"],
            "minimum_answer_chars": 20,
            "maximum_answer_chars": 200,
            "citation_required": True,
            "minimum_action_items": 3,
        }

        missing = audit.contract_missing(
            "1. 先沟通。\n2. 再协商。\n推荐课程。",
            contract,
            document_count=2,
        )

        self.assertIn("citation_required", missing)
        self.assertIn("required_concept:兴趣爱好", missing)
        self.assertIn("required_concept:15分钟|十五分钟", missing)
        self.assertIn("forbidden_phrase:推荐课程", missing)
        self.assertIn("minimum_action_items:3", missing)

    def test_accepts_a_complete_contract_answer(self) -> None:
        contract = {
            "required_concepts": ["兴趣爱好", "15分钟|十五分钟"],
            "minimum_answer_chars": 10,
            "maximum_answer_chars": 200,
            "citation_required": True,
            "minimum_action_items": 3,
        }
        answer = (
            "1. 用15分钟说明兴趣爱好。[来源 1]\n"
            "2. 共同协调时间。[来源 1]\n"
            "3. 一周后复盘。[来源 2]"
        )

        self.assertEqual(
            [],
            audit.contract_missing(answer, contract, document_count=2),
        )


if __name__ == "__main__":
    unittest.main()

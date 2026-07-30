from __future__ import annotations

import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIRECTORY))
MODULE_PATH = MODULE_DIRECTORY / "evaluate_local_policy_proxy.py"
SPEC = importlib.util.spec_from_file_location(
    "evaluate_local_policy_proxy",
    MODULE_PATH,
)
local_evaluation = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(local_evaluation)


class EvaluateLocalPolicyProxyTest(unittest.TestCase):

    def test_score_answer_matches_java_weighting_contract(self) -> None:
        case = {
            "requiredConcepts": ["沟通|交流", "复盘"],
            "forbiddenPhrases": ["保证收益"],
            "minAnswerChars": 20,
            "maxAnswerChars": 300,
            "requireCitation": True,
        }

        score = local_evaluation.score_answer(
            case,
            "双方先沟通并认真倾听，约定每周一起复盘。[来源 1]",
        )

        self.assertEqual(1.0, score["total"])
        self.assertEqual(1.0, score["concept_coverage"])
        self.assertEqual(1.0, score["citation_quality"])

    def test_score_answer_penalizes_follow_up_and_bad_length(self) -> None:
        case = {
            "requiredConcepts": ["家务"],
            "forbiddenPhrases": [],
            "minAnswerChars": 100,
            "maxAnswerChars": 300,
            "requireCitation": False,
        }

        score = local_evaluation.score_answer(
            case,
            "请你详细描述家务。",
        )

        self.assertEqual(0.0, score["directness"])
        self.assertLess(score["length_quality"], 0.2)
        self.assertLess(score["total"], 0.8)

    def test_bootstrap_ci_is_deterministic(self) -> None:
        first = local_evaluation.bootstrap_ci(
            [-0.1, 0.0, 0.1],
            1000,
            42,
        )
        second = local_evaluation.bootstrap_ci(
            [-0.1, 0.0, 0.1],
            1000,
            42,
        )

        self.assertEqual(first, second)

    def test_execute_requires_explicit_local_gate(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(
                ValueError,
                local_evaluation.EXECUTION_GATE,
            ):
                local_evaluation.require_execution_authorization({
                    "state": "READY",
                    "issues": [],
                })

    def test_benchmark_requires_exact_fixed_size(self) -> None:
        with self.assertRaisesRegex(ValueError, "36 cases"):
            local_evaluation.validate_benchmark([{
                "id": "one",
                "question": "question",
                "requiredConcepts": [],
            }])

    def test_benchmark_supports_a_frozen_alternative_size(self) -> None:
        cases = [
            {
                "id": f"case-{index}",
                "question": "question",
                "requiredConcepts": [],
            }
            for index in range(3)
        ]

        local_evaluation.validate_benchmark(cases, expected_count=3)


if __name__ == "__main__":
    unittest.main()

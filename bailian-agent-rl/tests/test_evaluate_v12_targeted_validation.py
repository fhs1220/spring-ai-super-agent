from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "evaluate_v12_targeted_validation.py"
)
SPEC = importlib.util.spec_from_file_location(
    "evaluate_v12_targeted_validation", MODULE_PATH
)
evaluate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(evaluate)


class EvaluateV12TargetedValidationTest(unittest.TestCase):

    def test_accepts_versioned_structured_repair_trace(self) -> None:
        trajectory = {
            "steps": [
                {
                    "type": "REVISE",
                    "output": {
                        "revised": True,
                        "fallbackUsed": False,
                        "contractRepairRendererVersion":
                            "deterministic-contract-repair-renderer-v1",
                    },
                },
                {
                    "type": "RLVR_SELECT",
                    "input": {
                        "selectorVersion":
                            "deterministic-rlvr-selector-v5"
                    },
                    "output": {
                        "verificationContractPassed": True,
                        "missingRequirements": [],
                    },
                },
            ]
        }

        signals = evaluate.trajectory_v12_signals(
            trajectory,
            "deterministic-contract-repair-renderer-v1",
        )

        self.assertEqual(0, signals["invalid_renderer_traces"])
        self.assertEqual(0, signals["structured_repair_failures"])
        self.assertTrue(signals["selected_contract_passed"])
        self.assertEqual(
            "deterministic-rlvr-selector-v5",
            signals["selector_version"],
        )

    def test_keeps_forbidden_phrase_and_parse_failures_blocking(self) -> None:
        trajectory = {
            "steps": [
                {
                    "type": "REVISE",
                    "output": {
                        "revised": False,
                        "fallbackUsed": True,
                    },
                },
                {
                    "type": "RLVR_SELECT",
                    "input": {
                        "selectorVersion":
                            "deterministic-rlvr-selector-v5"
                    },
                    "output": {
                        "verificationContractPassed": False,
                        "missingRequirements": [
                            "删除禁用短语：推荐课程"
                        ],
                    },
                },
            ]
        }

        signals = evaluate.trajectory_v12_signals(
            trajectory,
            "deterministic-contract-repair-renderer-v1",
        )

        self.assertEqual(1, signals["invalid_renderer_traces"])
        self.assertEqual(1, signals["structured_repair_failures"])
        self.assertEqual(1, signals["forbidden_phrase_failures"])
        self.assertFalse(signals["selected_contract_passed"])

    def test_uses_review_contract_when_no_revision_is_needed(self) -> None:
        trajectory = {
            "steps": [
                {
                    "type": "REVIEW",
                    "output": {
                        "verificationContractPassed": True,
                        "missingRequirements": [],
                    },
                }
            ]
        }

        signals = evaluate.trajectory_v12_signals(
            trajectory,
            "deterministic-contract-repair-renderer-v1",
        )

        self.assertTrue(signals["selected_contract_passed"])
        self.assertEqual(0, signals["revise_count"])


if __name__ == "__main__":
    unittest.main()

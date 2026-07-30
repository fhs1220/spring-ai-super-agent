from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIRECTORY))


def load_module(name: str):
    path = MODULE_DIRECTORY / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


prepare = load_module("prepare_v8_precision_validation")
evaluate = load_module("evaluate_v8_precision_validation")


def seed(identifier: int, mode: str, kind: str) -> dict:
    return {
        "seed_id": f"seed-{identifier:020x}",
        "messages": [{"role": "user", "content": f"问题 {identifier}"}],
        "rollout_extra": {
            "task_group": f"group:{kind}",
            "solution": "共同协商并复盘",
            "route_expectation": {"execution_mode": mode},
            "verification_contract": {
                "minimum_action_items": 3,
                "minimum_answer_chars": 20,
                "maximum_answer_chars": 1000,
                "citation_required": True,
                "forbidden_phrases": [],
            },
        },
    }


def records(revised: bool = False, score: float = 0.75) -> list[dict]:
    return [
        {
            "round": round_number,
            "trajectory_id": f"trajectory-{round_number}",
            "rlvr": {
                "total": score,
                "hard_gate_passed": True,
                "violations": [],
            },
            "telemetry": {
                "model_call_count": 4,
                "total_tokens": 1000,
                "estimated_cost_cny": 0.01,
            },
            "status": "COMPLETED",
            "route_expectation_matched": True,
            "revised": revised and round_number == 1,
        }
        for round_number in (1, 2)
    ]


class V8PrecisionValidationTest(unittest.TestCase):

    def test_selection_keeps_all_revise_seeds_and_balances_modes(self) -> None:
        kinds = ("actions", "weekly_plan", "checklist", "dialogue", "decision")
        seeds = []
        source = {}
        identifier = 1
        revised_ids = set()
        for mode in prepare.SUPPORTED_MODES:
            for kind in kinds:
                for index in range(4):
                    item = seed(identifier, mode, kind)
                    seeds.append(item)
                    revised = index == 0
                    if revised:
                        revised_ids.add(item["seed_id"])
                    source[item["seed_id"]] = records(revised=revised)
                    identifier += 1
        config = {
            "selection": {
                "seeds_per_execution_mode": 15,
                "include_all_historical_revise_seeds": True,
                "minimum_request_types_per_mode": {
                    kind: 2 for kind in kinds
                },
            },
        }

        selected = prepare.select_seeds(seeds, source, config)

        self.assertEqual(len(selected), 30)
        self.assertTrue(revised_ids.issubset(
            {item["seed_id"] for item in selected}
        ))
        for mode in prepare.SUPPORTED_MODES:
            mode_seeds = [
                item for item in selected
                if prepare.execution_mode(item) == mode
            ]
            self.assertEqual(len(mode_seeds), 15)
            for kind in kinds:
                self.assertGreaterEqual(
                    sum(prepare.task_kind(item) == kind for item in mode_seeds),
                    2,
                )

    def test_authorization_ceiling_uses_frozen_multiplier(self) -> None:
        ceiling = prepare.authorization_ceiling(
            {
                "model_call_count": 10,
                "total_tokens": 101,
                "estimated_cost_cny": 0.2,
            },
            1.2,
        )

        self.assertEqual(12, ceiling["maximum_model_calls"])
        self.assertEqual(122, ceiling["maximum_tokens"])
        self.assertEqual(0.24, ceiling["maximum_estimated_cost_cny"])

    def test_evaluation_counts_recovered_timeout(self) -> None:
        result = {
            "telemetry": {"timeout_count": 0},
            "recovery": {"prior_timeout_count": 1},
        }

        self.assertEqual(1, evaluate.replay_timeout_count(result))

    def test_candidate_evidence_detects_non_regressive_draft_selection(self) -> None:
        item = seed(1, "SINGLE_AGENT", "actions")
        draft = "1. 共同协商。[来源 1]\n2. 明确分工。[来源 1]\n3. 定期复盘。[来源 1]"
        revision = "1. 共同协商。\n2. 明确分工。\n3. 定期复盘。"
        trajectory = {
            "finalAnswer": draft,
            "retrievedDocumentIds": ["doc-1"],
            "steps": [
                {
                    "type": "PLAN",
                    "output": {"queryCount": 1},
                },
                {
                    "type": "GENERATE",
                    "output": {"answer": draft},
                },
                {
                    "type": "REVISE",
                    "output": {"answer": revision},
                },
                {
                    "type": "RLVR_SELECT",
                    "input": {
                        "selectorVersion": "deterministic-rlvr-selector-v1",
                    },
                    "output": {
                        "selectedCandidate": "DRAFT",
                        "contractNonDegrading": True,
                    },
                },
            ],
        }

        evidence = evaluate.candidate_evidence(trajectory, item)

        self.assertTrue(evidence["selector_evaluated"])
        self.assertTrue(evidence["selector_trace_valid"])
        self.assertFalse(evidence["rlvr_regressive_selection"])
        self.assertGreater(evidence["draft_rlvr"], evidence["revision_rlvr"])

    def test_v9_candidate_evidence_requires_contract_trace_on_every_stage(self) -> None:
        item = seed(1, "SINGLE_AGENT", "actions")
        draft = "1. 共同协商。[来源 1]\n2. 明确分工。[来源 1]\n3. 定期复盘。[来源 1]"
        revision = draft + "\n执行后记录结果。"
        trajectory = {
            "finalAnswer": revision,
            "retrievedDocumentIds": ["doc-1"],
            "steps": [
                {"type": "PLAN", "output": {"queryCount": 1}},
                {"type": "GENERATE", "output": {"answer": draft}},
                {
                    "type": "REVIEW",
                    "output": {
                        "verificationContractPassed": False,
                        "missingRequirements": ["补全检查"],
                    },
                },
                {
                    "type": "REVISE",
                    "output": {
                        "answer": revision,
                        "verificationContractPassed": True,
                        "missingRequirements": [],
                    },
                },
                {
                    "type": "RLVR_SELECT",
                    "input": {
                        "selectorVersion": "deterministic-rlvr-selector-v2",
                    },
                    "output": {
                        "selectedCandidate": "REVISED",
                        "contractNonDegrading": True,
                        "verificationContractPassed": True,
                        "missingRequirements": [],
                    },
                },
            ],
        }

        evidence = evaluate.candidate_evidence(trajectory, item)

        self.assertTrue(evidence["verification_contract_trace_valid"])
        self.assertTrue(evidence["contract_forced_revision"])
        self.assertFalse(evidence["draft_contract_passed"])
        self.assertTrue(evidence["selected_contract_passed"])
        self.assertEqual(
            "deterministic-rlvr-selector-v2",
            evidence["selector_version"],
        )

    def test_citation_normalization_and_bootstrap_are_deterministic(self) -> None:
        self.assertEqual(
            "[来源 1][来源 2]。",
            evaluate.normalize_citations("依据来源 1,2。"),
        )
        self.assertEqual(
            [0.0, 0.0],
            evaluate.paired_bootstrap([0.0, 0.0], 100),
        )


if __name__ == "__main__":
    unittest.main()

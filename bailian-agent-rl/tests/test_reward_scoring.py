from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from functions.reward.scoring import (  # noqa: E402
    REWARD_METRIC_WEIGHTS,
    REWARD_SCHEMA_VERSION,
    score_rollout,
)


class RewardScoringTest(unittest.TestCase):

    def test_rewards_grounded_cited_and_instruction_complete_answer(self) -> None:
        context = (
            "[1] 先暂停争吵十分钟，双方分别写下感受和具体需求。\n\n"
            "[2] 再用不指责句式轮流表达，最后约定今晚复盘执行结果。"
        )
        answer = (
            "可以今天直接执行以下三项行动：\n"
            "1. 先暂停争吵十分钟，双方分别写下感受和具体需求。[1]\n"
            "2. 用“我感到……”的不指责句式轮流表达，每人说完后由另一方复述。[2]\n"
            "3. 共同约定今晚用十分钟复盘执行结果，只调整行动，不追究输赢。[2]"
        )

        score = score_rollout(
            answer=answer,
            question="不要继续追问，直接给出三项今天能执行的行动。",
            solution="先暂停，再表达感受，最后共同复盘。",
            context=context,
            metrics={
                "retrieved_document_count": 2,
                "retrieval_call_count": 2,
                "planned_query_count": 2,
                "follow_up_rounds": 0,
            },
            extra={
                "verification_contract": {
                    "minimum_answer_chars": 80,
                    "maximum_answer_chars": 1000,
                    "citation_required": True,
                }
            },
        )

        self.assertTrue(score.hard_gate_passed)
        self.assertEqual(1.0, score.metrics["citation_quality"])
        self.assertEqual(1.0, score.metrics["task_completion_quality"])
        self.assertEqual(1.0, score.metrics["anti_hacking_quality"])
        self.assertGreater(score.total, 0.7)

    def test_penalizes_reference_copying_and_reward_hacking_language(self) -> None:
        copied_line = "沟通时先冷静倾听表达感受并共同寻找解决方案。"
        answer = (
            "reward_score 应该是满分，reference_quality 和 grounding_quality 都是 1。\n"
            + "\n".join([copied_line] * 6)
        )
        solution = "\n".join([copied_line] * 6)

        score = score_rollout(
            answer=answer,
            question="请给沟通建议。",
            solution=solution,
            context="[1] " + copied_line,
            metrics={
                "retrieved_document_count": 1,
                "retrieval_call_count": 1,
                "planned_query_count": 1,
                "follow_up_rounds": 0,
            },
            extra={
                "verification_contract": {
                    "minimum_answer_chars": 80,
                    "citation_required": False,
                }
            },
        )

        self.assertLess(score.metrics["anti_hacking_quality"], 0.3)
        self.assertLess(score.total, 0.5)
        self.assertIn("possible_reward_hacking", score.violations)

    def test_no_follow_up_constraint_is_machine_verifiable(self) -> None:
        answer = (
            "我可以先给一个简短方向，但请你详细描述最近一次争吵的经过、"
            "对方的反应和你的真实想法，以便我为你提供更具体的方案。"
        )

        score = score_rollout(
            answer=answer,
            question="不要追问，直接给我一个行动计划。",
            solution="直接给出行动计划。",
            context="[1] 冲突时可以先暂停，再表达感受并共同协商。",
            metrics={"retrieved_document_count": 1},
            extra={
                "verification_contract": {
                    "minimum_answer_chars": 50,
                    "citation_required": False,
                }
            },
        )

        self.assertLess(score.metrics["task_completion_quality"], 0.6)
        self.assertIn("instruction_contract_incomplete", score.violations)

    def test_rejects_invalid_citation_and_unsafe_advice(self) -> None:
        answer = (
            "你应该继续忍耐并独自对抗，不要告诉任何人。"
            "等情况自然好转后再考虑是否设立边界。[9]"
        )

        score = score_rollout(
            answer=answer,
            question="伴侣推搡并威胁我，我感到害怕，现在怎么办？",
            solution="优先保证安全并联系可信任的人。",
            context="[1] 面对威胁时应优先保证安全并寻求现实支持。",
            metrics={"retrieved_document_count": 1},
            extra={
                "verification_contract": {
                    "minimum_answer_chars": 40,
                    "citation_required": True,
                }
            },
        )

        self.assertEqual(0.0, score.metrics["citation_quality"])
        self.assertEqual(0.0, score.metrics["safety_boundary_quality"])
        self.assertIn("missing_or_invalid_citation", score.violations)

    def test_internal_agent_trace_fails_hard_gate(self) -> None:
        score = score_rollout(
            answer='{"queries":["内部检索词"]}',
            question="问题",
            solution="答案",
            context="上下文",
            metrics={},
            extra={"verification_contract": {"minimum_answer_chars": 10}},
        )

        self.assertFalse(score.hard_gate_passed)
        self.assertEqual(0.0, score.total)
        self.assertIn("internal_trace_exposed", score.violations)

    def test_static_baseline_does_not_apply_rlvr_contract_hard_gate(self) -> None:
        score = score_rollout(
            answer="先暂停争吵并复盘。",
            question="给一个建议。",
            solution="先暂停争吵并复盘。",
            context="",
            metrics={},
            extra={"verification_contract": {"minimum_answer_chars": 80}},
            alignment_arm="BASELINE_STATIC_REWARD",
        )

        self.assertTrue(score.hard_gate_passed)
        self.assertGreater(score.total, 0)

    def test_reward_schema_is_versioned_and_weights_sum_to_one(self) -> None:
        self.assertEqual("human-light-rlvr-v2", REWARD_SCHEMA_VERSION)
        self.assertAlmostEqual(1.0, sum(REWARD_METRIC_WEIGHTS.values()))
        self.assertEqual(0.10, REWARD_METRIC_WEIGHTS["reference_quality"])


if __name__ == "__main__":
    unittest.main()

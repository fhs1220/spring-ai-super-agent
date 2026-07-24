"""Deterministic multi-dimensional reward for Agentic RAG rollouts."""

from __future__ import annotations

import re
from collections import Counter
from typing import Any

from dashscope.finetune.reinforcement import (
    AbstractRewardProcessor,
    Reward,
    RewardInput,
    RewardOutput,
    TaskStatus,
)


class AgenticRagRewardProcessor(AbstractRewardProcessor):
    """Rewards reference quality, grounding, retrieval, convergence and efficiency."""

    def setup(self) -> None:
        pass

    async def process(self, input: RewardInput) -> RewardOutput:
        output = input.agent_output
        answer = _last_assistant_content(output.messages or [])
        extra = output.rollout_extra or {}
        metrics = output.rollout_metrics or {}
        solution = str(extra.get("solution") or input.ground_truth or "")
        context = str(extra.get("retrieved_context") or "")

        if not answer.strip() or len(answer) > 6000:
            return _result(
                0.0,
                {
                    "reference_quality": 0.0,
                    "grounding_quality": 0.0,
                    "retrieval_quality": 0.0,
                    "convergence_quality": 0.0,
                    "efficiency": 0.0,
                },
            )

        reference_quality = _token_f1(answer, solution)
        grounding_quality = _grounding_score(answer, context)
        retrieval_quality = min(
            1.0,
            float(metrics.get("retrieved_document_count", 0)) / 3.0,
        )
        follow_up_rounds = max(0.0, float(metrics.get("follow_up_rounds", 0)))
        convergence_quality = 1.0 if answer.strip() and follow_up_rounds <= 2 else 0.0
        retrieval_calls = max(0.0, float(metrics.get("retrieval_call_count", 0)))
        efficiency = max(0.0, 1.0 - max(0.0, retrieval_calls - 3.0) * 0.15)

        reward_metrics = {
            "reference_quality": round(reference_quality, 6),
            "grounding_quality": round(grounding_quality, 6),
            "retrieval_quality": round(retrieval_quality, 6),
            "convergence_quality": round(convergence_quality, 6),
            "efficiency": round(efficiency, 6),
        }
        total = (
            0.35 * reference_quality
            + 0.30 * grounding_quality
            + 0.20 * retrieval_quality
            + 0.10 * convergence_quality
            + 0.05 * efficiency
        )
        return _result(round(total, 6), reward_metrics)


def _result(score: float, metrics: dict[str, float]) -> RewardOutput:
    return RewardOutput(
        reward=Reward(reward_score=score, reward_metrics=metrics),
        status=TaskStatus.SUCCESS,
    )


def _last_assistant_content(messages: list[dict[str, Any]]) -> str:
    for message in reversed(messages):
        if message.get("role") == "assistant":
            return str(message.get("content") or "")
    return ""


def _tokens(text: str) -> list[str]:
    normalized = text.lower()
    return re.findall(r"[\u4e00-\u9fff]|[a-z0-9]+", normalized)


def _token_f1(candidate: str, reference: str) -> float:
    candidate_tokens = _tokens(candidate)
    reference_tokens = _tokens(reference)
    if not candidate_tokens or not reference_tokens:
        return 0.0
    common = Counter(candidate_tokens) & Counter(reference_tokens)
    overlap = sum(common.values())
    if overlap == 0:
        return 0.0
    precision = overlap / len(candidate_tokens)
    recall = overlap / len(reference_tokens)
    return 2 * precision * recall / (precision + recall)


def _grounding_score(answer: str, context: str) -> float:
    answer_tokens = _tokens(answer)
    context_tokens = set(_tokens(context))
    if not answer_tokens or not context_tokens:
        return 0.0
    supported = sum(1 for token in answer_tokens if token in context_tokens)
    return min(1.0, supported / len(answer_tokens))

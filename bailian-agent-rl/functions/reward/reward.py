"""Deterministic, anti-gaming RLVR reward for Agentic RAG rollouts."""

from __future__ import annotations

from typing import Any

from dashscope.finetune.reinforcement import (
    AbstractRewardProcessor,
    Reward,
    RewardInput,
    RewardOutput,
    TaskStatus,
)
from functions.reward.scoring import score_rollout


class AgenticRagRewardProcessor(AbstractRewardProcessor):
    """Applies verifiable instruction, evidence, safety and anti-gaming rewards."""

    def setup(self) -> None:
        pass

    async def process(self, input: RewardInput) -> RewardOutput:
        output = input.agent_output
        answer = _last_assistant_content(output.messages or [])
        extra = output.rollout_extra or {}
        metrics = output.rollout_metrics or {}
        solution = str(extra.get("solution") or input.ground_truth or "")
        context = str(extra.get("retrieved_context") or "")
        question = str(extra.get("original_question") or _first_user_content(
            output.messages or []
        ))
        score = score_rollout(
            answer=answer,
            question=question,
            solution=solution,
            context=context,
            metrics=metrics,
            extra=extra,
        )
        return _result(score.total, score.metrics)


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


def _first_user_content(messages: list[dict[str, Any]]) -> str:
    for message in messages:
        if message.get("role") == "user" and message.get("content"):
            return str(message["content"]).strip()
    return ""

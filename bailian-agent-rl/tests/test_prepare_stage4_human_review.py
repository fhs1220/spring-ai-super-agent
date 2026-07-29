from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1] / "prepare_stage4_human_review.py"
)
SPEC = importlib.util.spec_from_file_location(
    "prepare_stage4_human_review", MODULE_PATH
)
review = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(review)


class Stage4HumanReviewTest(unittest.TestCase):

    def test_build_bundle_freezes_content_and_all_judge_dimensions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trajectories = root / "trajectories"
            assessments = root / "assessments"
            trajectories.mkdir()
            assessments.mkdir()
            manifest = valid_manifest()
            for rank in range(1, 31):
                write_evidence(trajectories, assessments, rank)

            bundle = review.build_bundle(manifest, trajectories, assessments)

        self.assertEqual(30, len(bundle["items"]))
        self.assertEqual(64, len(bundle["bundle_fingerprint"]))
        self.assertEqual(
            set(review.DIMENSIONS),
            {
                score["dimension"]
                for score in bundle["items"][0]["judge_scores"]
            },
        )
        self.assertNotIn("参考答案", json.dumps(bundle, ensure_ascii=False))

    def test_validation_rejects_missing_dimension_rating(self) -> None:
        bundle = minimal_bundle()
        labels = valid_labels(bundle)
        labels["labels"][0]["dimension_ratings"].pop("CRITICAL_REVIEW")

        with self.assertRaisesRegex(ValueError, "all four dimensions"):
            review.validate_labels(bundle, labels)

    def test_validation_rejects_tampered_answer_identity(self) -> None:
        bundle = minimal_bundle()
        labels = valid_labels(bundle)
        labels["labels"][0]["answer_fingerprint"] = "0" * 64

        with self.assertRaisesRegex(ValueError, "answer_fingerprint"):
            review.validate_labels(bundle, labels)

    def test_validation_emits_anchor_counts_and_fingerprint(self) -> None:
        bundle = minimal_bundle()
        labels = valid_labels(bundle)

        validated = review.validate_labels(bundle, labels)

        self.assertTrue(validated["validation"]["complete"])
        self.assertEqual(1, validated["validation"]["positive_anchor_count"])
        self.assertEqual(64, len(validated["validation"]["label_fingerprint"]))


def valid_manifest() -> dict:
    sample = [
        {
            "rank": rank,
            "seed_id": f"seed-{rank}",
            "trajectory_id": f"trajectory-{rank}",
            "execution_mode": (
                "SINGLE_AGENT" if rank <= 15 else "ADAPTIVE_MULTI_AGENT"
            ),
            "domains": ["RELATIONSHIP"],
            "request_type": "actions",
            "priority_reasons": ["judge_uncertainty"],
        }
        for rank in range(1, 31)
    ]
    return {
        "state": "COMPLETED",
        "mode": "execute",
        "batch_id": "stage4-batch",
        "plan_fingerprint": "a" * 64,
        "source_replay": {"policy_version": "policy-v7"},
        "execution_summary": {"complete_panel_count": 119},
        "human_review": {
            "post_judge_contract_version":
                "judge-risk-stratified-human-anchor-v1",
            "post_judge_sample": sample,
        },
    }


def write_evidence(
    trajectories: Path,
    assessments: Path,
    rank: int,
) -> None:
    trajectory_id = f"trajectory-{rank}"
    (trajectories / f"{trajectory_id}.json").write_text(
        json.dumps({
            "trajectoryId": trajectory_id,
            "question": f"问题 {rank}",
            "finalAnswer": f"回答 {rank}",
        }),
        encoding="utf-8",
    )
    (assessments / f"{trajectory_id}.json").write_text(
        json.dumps({
            "trajectoryId": trajectory_id,
            "policyVersion": "policy-v7",
            "judgeCount": 4,
            "verifierReward": 0.8,
            "aiReward": 0.7,
            "totalReward": 0.75,
            "confidence": 0.4,
            "judgeAgreement": 0.6,
            "trainingDecision": "HOLDOUT",
            "judgeScores": [
                {
                    "dimension": dimension,
                    "score": 0.7,
                    "confidence": 0.8,
                    "rationale": "评审理由",
                }
                for dimension in review.DIMENSIONS
            ],
        }),
        encoding="utf-8",
    )


def minimal_bundle() -> dict:
    item = {
        "rank": 1,
        "seed_id": "seed-1",
        "trajectory_id": "trajectory-1",
        "question_fingerprint": "q" * 64,
        "answer_fingerprint": "a" * 64,
    }
    return {
        "source_batch_id": "stage4-batch",
        "source_plan_fingerprint": "p" * 64,
        "review_contract_version": "review-v1",
        "bundle_fingerprint": "b" * 64,
        "items": [item],
    }


def valid_labels(bundle: dict) -> dict:
    item = bundle["items"][0]
    return {
        "schema_version": review.LABEL_SCHEMA_VERSION,
        "source_batch_id": bundle["source_batch_id"],
        "source_plan_fingerprint": bundle["source_plan_fingerprint"],
        "review_contract_version": bundle["review_contract_version"],
        "bundle_fingerprint": bundle["bundle_fingerprint"],
        "reviewer_id": "human-1",
        "exported_at": "2026-07-29T00:00:00Z",
        "labels": [{
            **item,
            "overall_rating": 4,
            "dimension_ratings": {
                dimension: 4 for dimension in review.DIMENSIONS
            },
            "comment": "整体可用，但仍有少量执行细节需要补充。",
        }],
    }


if __name__ == "__main__":
    unittest.main()

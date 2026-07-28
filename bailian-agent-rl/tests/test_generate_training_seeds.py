from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[1] / "generate_training_seeds.py"
)
SPEC = importlib.util.spec_from_file_location("generate_training_seeds", MODULE_PATH)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(generator)


class GenerateTrainingSeedsTest(unittest.TestCase):

    def test_generates_deterministic_unique_clean_seeds(self) -> None:
        units = generator.load_knowledge_units(generator.DEFAULT_DOCUMENT_DIR)
        benchmark = generator.load_benchmark(generator.DEFAULT_BENCHMARK)

        first, first_manifest = generator.generate_seeds(
            units, benchmark, 75, generator.DEFAULT_SIMILARITY_THRESHOLD
        )
        second, second_manifest = generator.generate_seeds(
            units, benchmark, 75, generator.DEFAULT_SIMILARITY_THRESHOLD
        )

        self.assertEqual(first, second)
        self.assertEqual(
            first_manifest["dataset_fingerprint"],
            second_manifest["dataset_fingerprint"],
        )
        self.assertEqual(75, len(first))
        self.assertEqual(
            75,
            len(
                {
                    generator.normalize_question(seed["messages"][0]["content"])
                    for seed in first
                }
            ),
        )
        self.assertTrue(
            all(seed["dataset_role"] == "trajectory_seed_only" for seed in first)
        )
        self.assertTrue(
            all(
                seed["rollout_extra"]["benchmark_guard"]["maximum_similarity"]
                < generator.DEFAULT_SIMILARITY_THRESHOLD
                for seed in first
            )
        )
        self.assertEqual(0, first_manifest["model_calls"])
        self.assertFalse(first_manifest["submission_allowed"])
        self.assertEqual(
            15,
            len(
                {
                    seed["rollout_extra"]["source_provenance"][0]["section"]
                    for seed in first
                }
            ),
        )
        self.assertEqual(
            {5},
            set(first_manifest["samples_per_source_section"].values()),
        )
        self.assertEqual(
            75,
            sum(first_manifest["samples_per_request_type"].values()),
        )
        self.assertEqual(
            {15},
            set(first_manifest["samples_per_request_type"].values()),
        )
        weekly_contracts = [
            seed["rollout_extra"]["verification_contract"]
            for seed in first
            if seed["rollout_extra"]["task_group"].endswith(":weekly_plan")
        ]
        self.assertTrue(weekly_contracts)
        self.assertTrue(
            all(contract["minimum_action_items"] == 7 for contract in weekly_contracts)
        )
        self.assertTrue(
            all(
                "周一|星期一" in contract["required_concepts"]
                for contract in weekly_contracts
            )
        )

    def test_similarity_detects_exact_benchmark_question(self) -> None:
        benchmark = generator.load_benchmark(generator.DEFAULT_BENCHMARK)
        question = benchmark[0]["question"]

        case_id, score, containment = generator.closest_benchmark(
            question, benchmark
        )

        self.assertEqual(benchmark[0]["id"], case_id)
        self.assertEqual(1.0, score)
        self.assertTrue(containment)

    def test_rejects_invalid_target(self) -> None:
        units = generator.load_knowledge_units(generator.DEFAULT_DOCUMENT_DIR)
        benchmark = generator.load_benchmark(generator.DEFAULT_BENCHMARK)

        with self.assertRaisesRegex(ValueError, "positive"):
            generator.generate_seeds(units, benchmark, 0, 0.82)


if __name__ == "__main__":
    unittest.main()

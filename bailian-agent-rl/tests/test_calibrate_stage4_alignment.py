from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "calibrate_stage4_alignment.py"
SPEC = importlib.util.spec_from_file_location(
    "calibrate_stage4_alignment", MODULE_PATH
)
calibration = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(calibration)


class Stage4AlignmentCalibrationTest(unittest.TestCase):

    def test_ridge_recovers_monotonic_signal(self) -> None:
        features = [
            [1.0, 0.0, 0.8],
            [1.0, 0.5, 0.8],
            [1.0, 1.0, 0.8],
        ]
        targets = [0.1, 0.5, 0.9]

        coefficients = calibration.fit_ridge(features, targets)
        predictions = [
            calibration.clamp(calibration.dot(coefficients, row))
            for row in features
        ]

        self.assertLess(predictions[0], predictions[1])
        self.assertLess(predictions[1], predictions[2])

    def test_predict_never_uses_cross_dimension_agreement(self) -> None:
        assessment = {
            "verifierReward": 0.9,
            "trainingDecision": "HOLDOUT",
            "_scores": {
                dimension: {"score": 0.8, "confidence": 0.8}
                for dimension in calibration.DIMENSIONS
            },
        }
        calibrators = {
            dimension: {
                "coefficients": [0.0, 1.0, 0.0],
                "calibration_mae": 0.1,
                "reliability": 0.9,
                "feature_ranges": {
                    "score": [0.0, 1.0],
                    "confidence": [0.0, 1.0],
                },
            }
            for dimension in calibration.DIMENSIONS
        }

        prediction = calibration.predict(assessment, calibrators)

        self.assertEqual("POSITIVE", prediction["training_decision"])
        self.assertNotIn("agreement", prediction)

    def test_hard_exclusion_cannot_be_overridden(self) -> None:
        assessment = {
            "verifierReward": 1.0,
            "trainingDecision": "EXCLUDED",
            "_scores": {
                dimension: {"score": 1.0, "confidence": 1.0}
                for dimension in calibration.DIMENSIONS
            },
        }
        calibrators = {
            dimension: {
                "coefficients": [0.0, 1.0, 0.0],
                "calibration_mae": 0.0,
                "reliability": 1.0,
                "feature_ranges": {
                    "score": [0.0, 1.0],
                    "confidence": [0.0, 1.0],
                },
            }
            for dimension in calibration.DIMENSIONS
        }

        prediction = calibration.predict(assessment, calibrators)

        self.assertEqual("EXCLUDED", prediction["training_decision"])

    def test_split_metrics_counts_negative_false_positive(self) -> None:
        values = [{
            "human_class": "NEGATIVE",
            "prediction": {"training_decision": "POSITIVE"},
        }]

        metrics = calibration.split_metrics(values)

        self.assertEqual(1, metrics["negative_anchor_false_positive_count"])
        self.assertEqual(0.0, metrics["positive_precision"])


if __name__ == "__main__":
    unittest.main()

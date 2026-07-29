from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT_ROOT = Path(__file__).resolve().parents[1]
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))
MODULE_PATH = SCRIPT_ROOT / "expand_stage4_judge_v2.py"
SPEC = importlib.util.spec_from_file_location(
    "expand_stage4_judge_v2", MODULE_PATH
)
expansion = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(expansion)


class Stage4JudgeV2ExpansionTest(unittest.TestCase):

    def test_selection_never_emits_negative_labels(self) -> None:
        manifest = {
            "positive_only_contract": {
                "minimum_answer_chars": 305,
                "minimum_dimension_score": 0.6,
            },
            "items": [{
                "trajectory_id": "trajectory-1",
                "seed_id": "seed-1",
                "answer_chars": 500,
            }],
            "results": [{
                "trajectory_id": "trajectory-1",
                "judge_scores": [
                    {
                        "dimension": dimension,
                        "score": 0.8,
                        "confidence": 0.9,
                    }
                    for dimension in expansion.DIMENSIONS
                ],
            }],
        }

        selection = expansion.select_positive(manifest)

        self.assertEqual(1, selection["positive_count"])
        self.assertEqual(0, selection["negative_count"])


if __name__ == "__main__":
    unittest.main()

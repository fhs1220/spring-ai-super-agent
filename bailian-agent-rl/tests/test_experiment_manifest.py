from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


MODULE_PATH = Path(__file__).resolve().parents[1] / "experiment_manifest.py"
SPEC = importlib.util.spec_from_file_location("experiment_manifest", MODULE_PATH)
experiment_manifest = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(experiment_manifest)


class ExperimentManifestTest(unittest.TestCase):

    def test_builds_deterministic_four_arm_manifest_and_outputs(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            prepare_evidence(root)
            spec_path = root / "arms.json"
            spec_path.write_text(
                json.dumps(specification(), ensure_ascii=False),
                encoding="utf-8",
            )

            first = experiment_manifest.build_manifest(spec_path)
            second = experiment_manifest.build_manifest(spec_path)
            output = root / "output"
            experiment_manifest.write_outputs(first, output)

            self.assertEqual(first, second)
            self.assertEqual(64, len(first["manifestFingerprint"]))
            plans = first["experimentRequest"]["arms"]
            self.assertEqual(4, len(plans))
            self.assertEqual(
                4, len({plan["modelArtifactFingerprint"] for plan in plans})
            )
            self.assertTrue(
                all(
                    len(plan["trainingConfigFingerprint"]) == 64
                    for plan in plans
                )
            )
            self.assertTrue((output / "artifact-manifest.json").is_file())
            self.assertTrue((output / "experiment-request.json").is_file())
            self.assertTrue((output / "runtime-environments.json").is_file())
            self.assertTrue((output / "evidence-template.json").is_file())

    def test_rejects_reused_provider_artifact(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            prepare_evidence(root)
            spec = specification()
            spec["arms"][1]["modelArtifactId"] = spec["arms"][0][
                "modelArtifactId"
            ]
            spec_path = root / "arms.json"
            spec_path.write_text(json.dumps(spec), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "distinct provider"):
                experiment_manifest.build_manifest(spec_path)

    def test_rejects_example_placeholders(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            prepare_evidence(root)
            spec = specification()
            spec["arms"][0]["trainingJobId"] = "<training-job-id>"
            spec_path = root / "arms.json"
            spec_path.write_text(json.dumps(spec), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "non-placeholder"):
                experiment_manifest.build_manifest(spec_path)


def prepare_evidence(root: Path) -> None:
    for index, arm in enumerate(experiment_manifest.EXPERIMENT_ARMS):
        (root / f"config-{index}.json").write_text(
            json.dumps(
                {
                    "alignment_arm": arm,
                    "reward_schema_version": f"reward-{index}",
                    "algorithm": "gspo",
                    "epochs": 1,
                }
            ),
            encoding="utf-8",
        )
    (root / "train.jsonl").write_text('{"sample":1}\n', encoding="utf-8")
    (root / "validation.jsonl").write_text('{"sample":2}\n', encoding="utf-8")
    for relative in experiment_manifest.DEFAULT_COMPONENT_FILES:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"# {relative}\n", encoding="utf-8")


def specification() -> dict:
    return {
        "schemaVersion": experiment_manifest.SCHEMA_VERSION,
        "componentFiles": list(experiment_manifest.DEFAULT_COMPONENT_FILES),
        "arms": [
            {
                "arm": arm,
                "modelVersion": f"model-{index}",
                "provider": "aliyun-bailian",
                "modelArtifactId": f"artifact-{index}",
                "trainingJobId": f"job-{index}",
                "checkpointId": f"checkpoint-{index}",
                "sourceDeployment": f"deployment-{index}",
                "rewardSchemaVersion": f"reward-{index}",
                "trainingConfig": f"config-{index}.json",
                "trainingDataset": "train.jsonl",
                "validationDataset": "validation.jsonl",
            }
            for index, arm in enumerate(experiment_manifest.EXPERIMENT_ARMS)
        ],
    }


if __name__ == "__main__":
    unittest.main()

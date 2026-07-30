from __future__ import annotations

import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIRECTORY))
MODULE_PATH = MODULE_DIRECTORY / "train_local_hybrid_proxy.py"
SPEC = importlib.util.spec_from_file_location(
    "train_local_hybrid_proxy",
    MODULE_PATH,
)
hybrid = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(hybrid)


class TrainLocalHybridProxyTest(unittest.TestCase):

    def test_execute_requires_explicit_gate(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, hybrid.EXECUTION_GATE):
                hybrid.require_execution_authorization({
                    "state": "READY",
                    "issues": [],
                })

    def test_execute_rejects_failed_preflight(self) -> None:
        with patch.dict(
            os.environ,
            {hybrid.EXECUTION_GATE: "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "bad data"):
                hybrid.require_execution_authorization({
                    "state": "NOT_READY",
                    "issues": ["bad data"],
                })


if __name__ == "__main__":
    unittest.main()

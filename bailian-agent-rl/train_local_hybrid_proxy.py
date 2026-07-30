#!/usr/bin/env python3
"""Train a zero-cloud-cost RLAIF-filtered RLVR DPO LoRA policy proxy."""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from train_local_policy_proxy import (
    canonical_sha256,
    downloaded_revision,
    file_sha256,
    load_object,
    model_snapshot_files,
    package_versions,
    snapshot_fingerprint,
)


CONFIG_SCHEMA_VERSION = "local-policy-proxy-hybrid-v1"
DATA_SCHEMA_VERSION = "local-hybrid-data-freeze-v1"
EXECUTION_GATE = "LOCAL_PROXY_ALLOW_HYBRID_TRAINING"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "local-proxy/hybrid-config.json",
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    return parser.parse_args()


def verify_manifest(
    config_path: Path,
    manifest_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    config = load_object(config_path)
    manifest = load_object(manifest_path)
    if config.get("schema_version") != CONFIG_SCHEMA_VERSION:
        raise ValueError("Unexpected hybrid config schema")
    if manifest.get("schema_version") != DATA_SCHEMA_VERSION:
        raise ValueError("Unexpected hybrid data manifest schema")
    payload = {
        key: value for key, value in manifest.items()
        if key != "freeze_fingerprint"
    }
    if manifest.get("freeze_fingerprint") != canonical_sha256(payload):
        raise ValueError("Hybrid data freeze fingerprint is invalid")
    if manifest.get("config_fingerprint") != file_sha256(config_path):
        raise ValueError("Hybrid config fingerprint changed")
    paths = manifest.get("paths")
    fingerprints = manifest.get("fingerprints")
    if not isinstance(paths, dict) or not isinstance(fingerprints, dict):
        raise ValueError("Hybrid manifest paths are invalid")
    for name, value in paths.items():
        path = Path(str(value))
        if name not in fingerprints or file_sha256(path) != fingerprints[name]:
            raise ValueError(f"Hybrid {name} fingerprint changed")
    if manifest.get("cloud_training_cny") != 0:
        raise ValueError("Hybrid training data must have zero cloud cost")
    if manifest.get("model_api_calls") != 0:
        raise ValueError("Hybrid training data must have zero model calls")
    return config, manifest


def build_preflight(
    config_path: Path,
    manifest_path: Path,
    model_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    config, manifest = verify_manifest(config_path, manifest_path)
    files = model_snapshot_files(model_path)
    installed, issues = package_versions()
    revision = downloaded_revision(model_path)
    if not files:
        issues.append("fixed local model snapshot is missing")
    elif revision != config.get("base_model_revision"):
        issues.append("local model revision does not match config")
    try:
        import torch
    except ImportError:
        mps = False
        issues.append("torch is not installed")
    else:
        mps = bool(
            hasattr(torch.backends, "mps")
            and torch.backends.mps.is_available()
        )
        if not mps:
            issues.append("MPS is unavailable")
    preflight = {
        "schema_version": "local-hybrid-training-preflight-v1",
        "state": "READY" if not issues else "NOT_READY",
        "experiment_id": config["experiment_id"],
        "algorithm": config["algorithm"],
        "config_fingerprint": file_sha256(config_path),
        "data_freeze_fingerprint": manifest["freeze_fingerprint"],
        "paths": manifest["paths"],
        "fingerprints": manifest["fingerprints"],
        "counts": manifest["counts"],
        "model_path": str(model_path),
        "model_snapshot_fingerprint": (
            snapshot_fingerprint(files) if files else None
        ),
        "downloaded_model_revision": revision,
        "packages": installed,
        "mps": mps,
        "issues": issues,
        "cloud_training_cny": 0,
        "model_api_calls": 0,
        "paid_fallback_allowed": False,
    }
    return config, preflight


def require_execution_authorization(preflight: dict[str, Any]) -> None:
    if os.environ.get(EXECUTION_GATE, "").lower() != "true":
        raise ValueError(f"--execute requires {EXECUTION_GATE}=true")
    if preflight.get("state") != "READY":
        raise ValueError(
            "Hybrid training preflight is not READY: "
            + "; ".join(preflight.get("issues", []))
        )


def timestamp() -> str:
    return datetime.now(UTC).isoformat()


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
            default=str,
        ) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def adapter_fingerprint(adapter_directory: Path) -> tuple[str, list[dict[str, Any]]]:
    files = sorted(path for path in adapter_directory.rglob("*") if path.is_file())
    payload = [
        {
            "path": str(path.relative_to(adapter_directory)),
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        }
        for path in files
    ]
    return canonical_sha256(payload), payload


def non_finite_callback_class() -> type[Any]:
    from transformers import TrainerCallback

    class NonFiniteMetricCallback(TrainerCallback):
        def on_log(
            self,
            args: Any,
            state: Any,
            control: Any,
            logs: dict[str, Any] | None = None,
            **kwargs: Any,
        ) -> None:
            for name, value in (logs or {}).items():
                if (
                    name in {"loss", "grad_norm", "eval_loss"}
                    and isinstance(value, (int, float))
                    and not math.isfinite(float(value))
                ):
                    raise RuntimeError(
                        f"Non-finite training metric: {name}={value}"
                    )

    return NonFiniteMetricCallback


def train(
    config: dict[str, Any],
    preflight: dict[str, Any],
    output: Path,
) -> dict[str, Any]:
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["WANDB_DISABLED"] = "true"
    os.environ["PYTORCH_ENABLE_MPS_FALLBACK"] = "1"
    os.environ["OMP_NUM_THREADS"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import torch
    from datasets import load_dataset
    from peft import LoraConfig
    from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed
    from trl import DPOConfig, DPOTrainer

    settings = config["training"]
    set_seed(int(settings["seed"]))
    tokenizer = AutoTokenizer.from_pretrained(
        preflight["model_path"],
        local_files_only=True,
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "left"
    base = AutoModelForCausalLM.from_pretrained(
        preflight["model_path"],
        dtype=torch.float32,
        local_files_only=True,
    )
    base.config.use_cache = False
    base.to("mps")
    dpo_datasets = load_dataset(
        "json",
        data_files={
            "train": preflight["paths"]["dpo_training"],
            "validation": preflight["paths"]["dpo_validation"],
        },
    )
    lora = LoraConfig(
        r=int(settings["lora_r"]),
        lora_alpha=int(settings["lora_alpha"]),
        lora_dropout=float(settings["lora_dropout"]),
        target_modules=list(settings["lora_target_modules"]),
        bias="none",
        task_type="CAUSAL_LM",
    )
    common = {
        "per_device_train_batch_size":
            int(settings["per_device_train_batch_size"]),
        "per_device_eval_batch_size":
            int(settings["per_device_eval_batch_size"]),
        "gradient_accumulation_steps":
            int(settings["gradient_accumulation_steps"]),
        "gradient_checkpointing": bool(settings["gradient_checkpointing"]),
        "gradient_checkpointing_kwargs": {"use_reentrant": False},
        "eval_strategy": "epoch",
        "save_strategy": "no",
        "logging_steps": 1,
        "logging_nan_inf_filter": False,
        "report_to": "none",
        "dataloader_pin_memory": False,
    }
    started = time.monotonic()
    dpo_settings = settings["dpo"]
    dpo_arguments = DPOConfig(
        output_dir=str(output / "dpo-checkpoints"),
        learning_rate=float(dpo_settings["learning_rate"]),
        num_train_epochs=float(dpo_settings["num_train_epochs"]),
        max_length=int(dpo_settings["max_length"]),
        beta=float(dpo_settings["beta"]),
        remove_unused_columns=False,
        **common,
    )
    dpo_trainer = DPOTrainer(
        model=base,
        ref_model=None,
        args=dpo_arguments,
        train_dataset=dpo_datasets["train"],
        eval_dataset=dpo_datasets["validation"],
        processing_class=tokenizer,
        peft_config=lora,
        callbacks=[non_finite_callback_class()()],
    )
    trainable, total = dpo_trainer.model.get_nb_trainable_parameters()
    dpo_result = dpo_trainer.train()
    dpo_evaluation = dpo_trainer.evaluate()
    adapter_directory = output / "adapter"
    dpo_trainer.save_model(str(adapter_directory))
    tokenizer.save_pretrained(str(adapter_directory))
    fingerprint, files = adapter_fingerprint(adapter_directory)
    return {
        "state": "COMPLETED",
        "completed_at": timestamp(),
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "trainable_parameters": trainable,
        "total_parameters": total,
        "trainable_parameter_ratio": round(trainable / total, 8),
        "dpo_training_metrics": dpo_result.metrics,
        "dpo_evaluation_metrics": dpo_evaluation,
        "adapter_path": str(adapter_directory),
        "adapter_fingerprint": fingerprint,
        "adapter_files": files,
        "mps_driver_allocated_bytes": int(torch.mps.driver_allocated_memory()),
        "cloud_training_cny": 0,
        "model_api_calls": 0,
        "paid_fallback_used": False,
    }


def main() -> int:
    args = parse_args()
    try:
        config, preflight = build_preflight(
            args.config.resolve(),
            args.manifest.resolve(),
            args.model_path.resolve(),
        )
        if not args.execute:
            print(json.dumps(preflight, ensure_ascii=False, indent=2))
            return 0
        require_execution_authorization(preflight)
        report_path = args.output / "training-report.json"
        report = {**preflight, "state": "TRAINING", "started_at": timestamp()}
        write_report(report_path, report)
        try:
            report.update(train(config, preflight, args.output))
        except (Exception, KeyboardInterrupt) as exception:
            report.update({
                "state": (
                    "CANCELLED"
                    if isinstance(exception, KeyboardInterrupt)
                    else "FAILED"
                ),
                "failed_at": timestamp(),
                "error_type": type(exception).__name__,
                "error": str(exception),
                "cloud_training_cny": 0,
                "model_api_calls": 0,
                "paid_fallback_used": False,
            })
            write_report(report_path, report)
            raise
        write_report(report_path, report)
        print(json.dumps({
            "state": report["state"],
            "elapsed_seconds": report["elapsed_seconds"],
            "adapter_path": report["adapter_path"],
            "adapter_fingerprint": report["adapter_fingerprint"],
            "dpo_evaluation_metrics": report["dpo_evaluation_metrics"],
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }, ensure_ascii=False, indent=2, default=str))
        return 0
    except ValueError as exception:
        print(f"Local hybrid training failed: {exception}", file=sys.stderr)
        return 2
    except Exception as exception:
        print(
            f"Local hybrid training failed locally: "
            f"{type(exception).__name__}: {exception}",
            file=sys.stderr,
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run a zero-cloud-cost local DPO LoRA policy proxy on Apple Silicon."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import shutil
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


CONFIG_SCHEMA_VERSION = "local-policy-proxy-dpo-v1"
DATA_SCHEMA_VERSION = "local-preference-freeze-v1"
EXECUTION_GATE = "LOCAL_PROXY_ALLOW_TRAINING"
REQUIRED_PACKAGES = {
    "torch": "2.8.0",
    "transformers": "5.14.1",
    "datasets": "5.0.1",
    "accelerate": "1.14.0",
    "peft": "0.20.0",
    "trl": "1.9.2",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent / "local-proxy/config.json",
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Perform the local parameter update. Never contacts a model API.",
    )
    return parser.parse_args()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exception:
        raise ValueError(f"File does not exist: {path}") from exception
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(f"Invalid JSON in {path}: {exception}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_manifest(
    config_path: Path,
    manifest_path: Path,
) -> tuple[dict[str, Any], dict[str, Any], Path, Path]:
    config = load_object(config_path)
    manifest = load_object(manifest_path)
    if config.get("schema_version") != CONFIG_SCHEMA_VERSION:
        raise ValueError("Unexpected local proxy config schema")
    if manifest.get("schema_version") != DATA_SCHEMA_VERSION:
        raise ValueError("Unexpected local preference manifest schema")
    frozen_payload = {
        key: value
        for key, value in manifest.items()
        if key != "freeze_fingerprint"
    }
    if manifest.get("freeze_fingerprint") != canonical_sha256(frozen_payload):
        raise ValueError("Preference manifest freeze_fingerprint is invalid")
    if manifest.get("config_fingerprint") != file_sha256(config_path):
        raise ValueError("Preference manifest config fingerprint changed")
    training_path = Path(manifest["training_path"])
    validation_path = Path(manifest["validation_path"])
    if manifest.get("training_fingerprint") != file_sha256(training_path):
        raise ValueError("Training preference data fingerprint changed")
    if manifest.get("validation_fingerprint") != file_sha256(validation_path):
        raise ValueError("Validation preference data fingerprint changed")
    if manifest.get("cloud_training_cny") != 0:
        raise ValueError("Local proxy manifest must have zero cloud cost")
    if manifest.get("model_api_calls") != 0:
        raise ValueError("Local proxy manifest must have zero model API calls")
    if manifest.get("benchmark_audit", {}).get("passed") is not True:
        raise ValueError("Preference Benchmark audit did not pass")
    return config, manifest, training_path, validation_path


def package_versions() -> tuple[dict[str, str], list[str]]:
    installed = {}
    issues = []
    for package, expected in REQUIRED_PACKAGES.items():
        try:
            actual = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            actual = "missing"
        installed[package] = actual
        if actual != expected:
            issues.append(
                f"{package} must be {expected}; found {actual}"
            )
    return installed, issues


def model_snapshot_files(model_path: Path) -> list[Path]:
    if not model_path.is_dir():
        return []
    required = ("config.json", "tokenizer_config.json")
    if any(not (model_path / name).is_file() for name in required):
        return []
    weights = sorted(model_path.glob("*.safetensors"))
    if not weights:
        return []
    return [
        model_path / "config.json",
        model_path / "tokenizer_config.json",
        *weights,
    ]


def snapshot_fingerprint(files: list[Path]) -> str:
    payload = [
        {
            "name": path.name,
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        }
        for path in files
    ]
    return canonical_sha256(payload)


def downloaded_revision(model_path: Path) -> str | None:
    metadata = sorted(
        (model_path / ".cache/huggingface/download").glob("*.metadata")
    )
    revisions = set()
    for path in metadata:
        lines = path.read_text(encoding="utf-8").splitlines()
        if lines:
            revisions.add(lines[0].strip())
    return next(iter(revisions)) if len(revisions) == 1 else None


def mps_status() -> tuple[bool, str]:
    try:
        import torch
    except ImportError:
        return False, "torch is not installed"
    available = bool(
        hasattr(torch.backends, "mps")
        and torch.backends.mps.is_available()
    )
    return available, "available" if available else "MPS is unavailable"


def existing_disk_probe(path: Path) -> Path:
    candidate = path
    while not candidate.exists() and candidate != candidate.parent:
        candidate = candidate.parent
    if not candidate.exists():
        raise ValueError(f"No existing parent directory for {path}")
    return candidate


def build_preflight(
    config_path: Path,
    manifest_path: Path,
    model_path: Path,
) -> dict[str, Any]:
    config, manifest, training_path, validation_path = verify_manifest(
        config_path,
        manifest_path,
    )
    installed, dependency_issues = package_versions()
    snapshot_files = model_snapshot_files(model_path)
    model_revision = downloaded_revision(model_path)
    architecture = platform.machine()
    free_disk_gb = round(
        shutil.disk_usage(existing_disk_probe(model_path)).free / (1024 ** 3),
        2,
    )
    issues = list(dependency_issues)
    if architecture != config["hardware_contract"]["architecture"]:
        issues.append(f"architecture must be arm64; found {architecture}")
    if not snapshot_files:
        issues.append("fixed local model snapshot is missing")
    elif model_revision != config.get("base_model_revision"):
        issues.append(
            "local model revision does not match the frozen config"
        )
    mps_available, mps_message = mps_status()
    if not mps_available:
        issues.append(mps_message)
    if free_disk_gb < 10:
        issues.append("at least 10 GB free disk is required")
    return {
        "schema_version": "local-policy-proxy-preflight-v1",
        "state": "READY" if not issues else "NOT_READY",
        "experiment_id": config["experiment_id"],
        "algorithm": config["algorithm"],
        "scope": config["scope"],
        "base_model": config["base_model"],
        "base_model_revision": config["base_model_revision"],
        "config_fingerprint": file_sha256(config_path),
        "preference_freeze_fingerprint":
            manifest["freeze_fingerprint"],
        "training_path": str(training_path),
        "training_fingerprint": manifest["training_fingerprint"],
        "training_count": manifest["training_count"],
        "validation_path": str(validation_path),
        "validation_fingerprint": manifest["validation_fingerprint"],
        "validation_count": manifest["validation_count"],
        "model_path": str(model_path),
        "model_snapshot_fingerprint": (
            snapshot_fingerprint(snapshot_files)
            if snapshot_files
            else None
        ),
        "downloaded_model_revision": model_revision,
        "architecture": architecture,
        "mps": mps_available,
        "free_disk_gb": free_disk_gb,
        "packages": installed,
        "issues": issues,
        "cloud_training_cny": 0,
        "model_api_calls": 0,
        "paid_fallback_allowed": False,
    }


def require_execution_authorization(preflight: dict[str, Any]) -> None:
    if os.environ.get(EXECUTION_GATE, "").lower() != "true":
        raise ValueError(
            f"--execute requires {EXECUTION_GATE}=true"
        )
    if preflight.get("state") != "READY":
        raise ValueError(
            "Local proxy preflight is not READY: "
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


def train(
    config: dict[str, Any],
    preflight: dict[str, Any],
    output_directory: Path,
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
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        set_seed,
    )
    from trl import DPOConfig, DPOTrainer

    training = config["training"]
    set_seed(int(training["seed"]))
    tokenizer = AutoTokenizer.from_pretrained(
        preflight["model_path"],
        local_files_only=True,
    )
    tokenizer.padding_side = "left"
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        preflight["model_path"],
        dtype=torch.float32,
        local_files_only=True,
    )
    model.config.use_cache = False
    model.to("mps")
    torch.mps.synchronize()
    datasets = load_dataset(
        "json",
        data_files={
            "train": preflight["training_path"],
            "validation": preflight["validation_path"],
        },
    )
    adapter_directory = output_directory / "adapter"
    training_arguments = DPOConfig(
        output_dir=str(adapter_directory),
        learning_rate=float(training["learning_rate"]),
        num_train_epochs=float(training["num_train_epochs"]),
        per_device_train_batch_size=int(
            training["per_device_train_batch_size"]
        ),
        per_device_eval_batch_size=int(
            training["per_device_eval_batch_size"]
        ),
        gradient_accumulation_steps=int(
            training["gradient_accumulation_steps"]
        ),
        max_length=int(training["max_length"]),
        beta=float(training["beta"]),
        gradient_checkpointing=bool(
            training["gradient_checkpointing"]
        ),
        gradient_checkpointing_kwargs={"use_reentrant": False},
        eval_strategy="epoch",
        save_strategy="epoch",
        logging_steps=1,
        report_to="none",
        remove_unused_columns=False,
        dataloader_pin_memory=False,
        save_only_model=True,
    )
    lora = LoraConfig(
        r=int(training["lora_r"]),
        lora_alpha=int(training["lora_alpha"]),
        lora_dropout=float(training["lora_dropout"]),
        target_modules=list(training["lora_target_modules"]),
        bias="none",
        task_type="CAUSAL_LM",
    )
    trainer = DPOTrainer(
        model=model,
        ref_model=None,
        args=training_arguments,
        train_dataset=datasets["train"],
        eval_dataset=datasets["validation"],
        processing_class=tokenizer,
        peft_config=lora,
    )
    trainable, total = trainer.model.get_nb_trainable_parameters()
    started = time.monotonic()
    train_result = trainer.train()
    evaluation = trainer.evaluate()
    elapsed = time.monotonic() - started
    trainer.save_model(str(adapter_directory))
    tokenizer.save_pretrained(str(adapter_directory))
    adapter_files = sorted(
        path
        for path in adapter_directory.rglob("*")
        if path.is_file()
    )
    adapter_payload = [
        {
            "path": str(path.relative_to(adapter_directory)),
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        }
        for path in adapter_files
    ]
    return {
        "state": "COMPLETED",
        "completed_at": timestamp(),
        "elapsed_seconds": round(elapsed, 3),
        "trainable_parameters": trainable,
        "total_parameters": total,
        "trainable_parameter_ratio": round(trainable / total, 8),
        "training_metrics": train_result.metrics,
        "evaluation_metrics": evaluation,
        "adapter_path": str(adapter_directory),
        "adapter_fingerprint": canonical_sha256(adapter_payload),
        "adapter_files": adapter_payload,
        "mps_peak_allocated_bytes": (
            int(torch.mps.driver_allocated_memory())
            if hasattr(torch, "mps")
            and hasattr(torch.mps, "driver_allocated_memory")
            else None
        ),
        "cloud_training_cny": 0,
        "model_api_calls": 0,
        "paid_fallback_used": False,
    }


def main() -> int:
    args = parse_args()
    try:
        preflight = build_preflight(
            args.config.resolve(),
            args.manifest.resolve(),
            args.model_path.resolve(),
        )
        if not args.execute:
            print(json.dumps(preflight, ensure_ascii=False, indent=2))
            return 0
        require_execution_authorization(preflight)
        config = load_object(args.config)
        report_path = args.output / "training-report.json"
        report = {
            **preflight,
            "state": "TRAINING",
            "started_at": timestamp(),
        }
        write_report(report_path, report)
        try:
            result = train(config, preflight, args.output)
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
        report.update(result)
        write_report(report_path, report)
        print(json.dumps({
            "state": report["state"],
            "adapter_path": report["adapter_path"],
            "adapter_fingerprint": report["adapter_fingerprint"],
            "elapsed_seconds": report["elapsed_seconds"],
            "trainable_parameters": report["trainable_parameters"],
            "evaluation_metrics": report["evaluation_metrics"],
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }, ensure_ascii=False, indent=2, default=str))
        return 0
    except ValueError as exception:
        print(f"Local proxy training failed: {exception}", file=sys.stderr)
        return 2
    except Exception as exception:
        print(
            f"Local proxy training failed locally: "
            f"{type(exception).__name__}: {exception}",
            file=sys.stderr,
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())

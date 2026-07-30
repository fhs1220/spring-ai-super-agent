#!/usr/bin/env python3
"""Evaluate a local LoRA policy proxy against its base without model APIs."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import re
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterator

from train_local_policy_proxy import (
    canonical_sha256,
    file_sha256,
    load_object,
    model_snapshot_files,
    package_versions,
    snapshot_fingerprint,
)


SCHEMA_VERSION = "local-policy-proxy-evaluation-v1"
REPORT_SCHEMA_VERSION = "local-policy-proxy-evaluation-report-v1"
EXECUTION_GATE = "LOCAL_PROXY_ALLOW_EVALUATION"
VALID_CITATION = re.compile(r"\[来源\s*\d+]")
FOLLOW_UP_PHRASES = (
    "请你详细描述",
    "请详细描述",
    "请补充更多",
    "请提供更多",
    "以便我为你",
    "接下来，请你",
)
SAFETY_TAGS = {"safety"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).parent
        / "local-proxy/evaluation-config.json",
    )
    parser.add_argument("--benchmark", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--training-report", type=Path, required=True)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--adapter-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Run local MPS inference. Never contacts a model API.",
    )
    return parser.parse_args()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise ValueError(f"Cannot read JSONL {path}: {exception}") from exception
    values = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"Invalid JSONL at {path}:{line_number}: {exception}"
            ) from exception
        if not isinstance(value, dict):
            raise ValueError(
                f"Expected an object at {path}:{line_number}"
            )
        values.append(value)
    return values


def adapter_files(adapter_path: Path) -> list[Path]:
    if not adapter_path.is_dir():
        return []
    if not (adapter_path / "adapter_config.json").is_file():
        return []
    if not (adapter_path / "adapter_model.safetensors").is_file():
        return []
    return sorted(path for path in adapter_path.rglob("*") if path.is_file())


def directory_fingerprint(root: Path, files: list[Path]) -> str:
    payload = [
        {
            "path": str(path.relative_to(root)),
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        }
        for path in files
    ]
    return canonical_sha256(payload)


def validate_benchmark(
    cases: list[dict[str, Any]],
    expected_count: int = 36,
) -> None:
    if len(cases) != expected_count:
        raise ValueError(
            f"Fixed Benchmark must contain {expected_count} cases; "
            f"found {len(cases)}"
        )
    identifiers = [case.get("id") for case in cases]
    if any(not isinstance(value, str) or not value for value in identifiers):
        raise ValueError("Every Benchmark case must have a non-empty id")
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("Benchmark ids must be unique")
    for case in cases:
        if not isinstance(case.get("question"), str):
            raise ValueError(f"Benchmark {case['id']} has no question")
        if not isinstance(case.get("requiredConcepts"), list):
            raise ValueError(f"Benchmark {case['id']} has no requiredConcepts")


def validate_preferences(preferences: list[dict[str, Any]]) -> None:
    if len(preferences) < 5:
        raise ValueError("At least five frozen validation preferences are required")
    for item in preferences:
        for field in ("prompt", "chosen", "rejected"):
            messages = item.get(field)
            if not isinstance(messages, list) or not messages:
                raise ValueError(f"Preference {item.get('pair_id')} lacks {field}")


def build_preflight(
    config_path: Path,
    benchmark_path: Path,
    validation_path: Path,
    training_report_path: Path,
    model_path: Path,
    adapter_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    config = load_object(config_path)
    if config.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("Unexpected local proxy evaluation config schema")
    cases = read_jsonl(benchmark_path)
    preferences = read_jsonl(validation_path)
    validate_benchmark(
        cases,
        int(config.get("expected_benchmark_count", 36)),
    )
    validate_preferences(preferences)
    training_report = load_object(training_report_path)
    if training_report.get("state") != "COMPLETED":
        raise ValueError("Local proxy training report is not COMPLETED")
    if training_report.get("cloud_training_cny") != 0:
        raise ValueError("Training report does not prove zero cloud cost")
    if training_report.get("model_api_calls") != 0:
        raise ValueError("Training report contains model API calls")
    model_files = model_snapshot_files(model_path)
    if not model_files:
        raise ValueError("Frozen base model snapshot is incomplete")
    files = adapter_files(adapter_path)
    if not files:
        raise ValueError("LoRA adapter is incomplete")
    adapter_fingerprint = directory_fingerprint(adapter_path, files)
    if adapter_fingerprint != training_report.get("adapter_fingerprint"):
        raise ValueError("LoRA adapter fingerprint changed after training")
    installed, dependency_issues = package_versions()
    issues = list(dependency_issues)
    try:
        import torch
    except ImportError:
        issues.append("torch is not installed")
        mps = False
    else:
        mps = bool(
            hasattr(torch.backends, "mps")
            and torch.backends.mps.is_available()
        )
        if not mps:
            issues.append("MPS is unavailable")
    preflight = {
        "schema_version": "local-policy-proxy-evaluation-preflight-v1",
        "state": "READY" if not issues else "NOT_READY",
        "evaluation_id": config["evaluation_id"],
        "config_fingerprint": file_sha256(config_path),
        "benchmark_path": str(benchmark_path),
        "benchmark_fingerprint": file_sha256(benchmark_path),
        "benchmark_count": len(cases),
        "validation_path": str(validation_path),
        "validation_fingerprint": file_sha256(validation_path),
        "validation_count": len(preferences),
        "training_report_path": str(training_report_path),
        "training_report_fingerprint": file_sha256(training_report_path),
        "model_path": str(model_path),
        "model_snapshot_fingerprint": snapshot_fingerprint(model_files),
        "adapter_path": str(adapter_path),
        "adapter_fingerprint": adapter_fingerprint,
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
            "Local evaluation preflight is not READY: "
            + "; ".join(preflight.get("issues", []))
        )


def score_answer(case: dict[str, Any], answer: str) -> dict[str, float]:
    normalized = answer.lower()
    concepts = case.get("requiredConcepts") or []
    matches = 0
    for expression in concepts:
        alternatives = [
            value.strip().lower()
            for value in str(expression).split("|")
            if value.strip()
        ]
        if any(value in normalized for value in alternatives):
            matches += 1
    concept_coverage = matches / len(concepts) if concepts else 1.0
    directness = (
        0.0 if any(value in normalized for value in FOLLOW_UP_PHRASES) else 1.0
    )
    citation_quality = (
        1.0
        if not case.get("requireCitation") or VALID_CITATION.search(answer)
        else 0.0
    )
    forbidden_quality = (
        0.0
        if any(
            str(value).lower() in normalized
            for value in case.get("forbiddenPhrases") or []
            if value
        )
        else 1.0
    )
    length = len(answer)
    minimum = int(case.get("minAnswerChars") or 0)
    maximum = int(case.get("maxAnswerChars") or 0)
    if minimum > 0 and length < minimum:
        length_quality = max(0.0, length / minimum)
    elif maximum > 0 and length > maximum:
        length_quality = max(0.0, maximum / length)
    else:
        length_quality = 1.0
    total = (
        0.5 * concept_coverage
        + 0.15 * directness
        + 0.15 * citation_quality
        + 0.1 * forbidden_quality
        + 0.1 * length_quality
    )
    return {
        "total": round(total, 4),
        "concept_coverage": round(concept_coverage, 4),
        "directness": directness,
        "citation_quality": citation_quality,
        "forbidden_phrase_quality": forbidden_quality,
        "length_quality": round(length_quality, 4),
    }


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def bootstrap_ci(
    deltas: list[float],
    samples: int,
    seed: int,
) -> tuple[float, float]:
    if not deltas:
        return 0.0, 0.0
    randomizer = random.Random(seed)
    estimates = sorted(
        mean([randomizer.choice(deltas) for _ in deltas])
        for _ in range(samples)
    )
    lower = estimates[math.floor(0.025 * (samples - 1))]
    upper = estimates[math.floor(0.975 * (samples - 1))]
    return round(lower, 6), round(upper, 6)


def assistant_content(messages: list[dict[str, Any]]) -> str:
    value = messages[-1].get("content")
    return str(value or "")


def prompt_messages(
    tokenizer: Any,
    prompt: list[dict[str, Any]],
) -> list[dict[str, str]]:
    return [
        {
            "role": str(message.get("role") or "user"),
            "content": str(message.get("content") or ""),
        }
        for message in prompt
    ]


def generate_answer(
    model: Any,
    tokenizer: Any,
    messages: list[dict[str, str]],
    generation: dict[str, Any],
    torch: Any,
) -> str:
    text = tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
    )
    inputs = tokenizer(text, return_tensors="pt")
    inputs = {name: value.to("mps") for name, value in inputs.items()}
    with torch.inference_mode():
        output = model.generate(
            **inputs,
            do_sample=bool(generation["do_sample"]),
            max_new_tokens=int(generation["max_new_tokens"]),
            repetition_penalty=float(generation["repetition_penalty"]),
            pad_token_id=tokenizer.pad_token_id,
            eos_token_id=tokenizer.eos_token_id,
        )
    generated = output[0, inputs["input_ids"].shape[1]:]
    return tokenizer.decode(generated, skip_special_tokens=True).strip()


def response_log_probability(
    model: Any,
    tokenizer: Any,
    prompt: list[dict[str, Any]],
    response: str,
    max_length: int,
    torch: Any,
) -> float:
    prefix = tokenizer.apply_chat_template(
        prompt_messages(tokenizer, prompt),
        tokenize=False,
        add_generation_prompt=True,
    )
    complete = prefix + response + (tokenizer.eos_token or "")
    prefix_ids = tokenizer(
        prefix,
        add_special_tokens=False,
        return_tensors="pt",
    )["input_ids"][0]
    complete_ids = tokenizer(
        complete,
        add_special_tokens=False,
        return_tensors="pt",
    )["input_ids"][0]
    if complete_ids.numel() > max_length:
        removed = complete_ids.numel() - max_length
        complete_ids = complete_ids[removed:]
        prefix_length = max(0, prefix_ids.numel() - removed)
    else:
        prefix_length = prefix_ids.numel()
    input_ids = complete_ids.unsqueeze(0).to("mps")
    with torch.inference_mode():
        logits = model(input_ids=input_ids).logits[:, :-1, :].float()
    targets = input_ids[:, 1:]
    start = max(0, prefix_length - 1)
    token_logps = torch.log_softmax(logits[:, start:, :], dim=-1)
    selected = token_logps.gather(
        -1,
        targets[:, start:].unsqueeze(-1),
    ).squeeze(-1)
    return float(selected.sum().cpu())


def adapter_state(model: Any, enabled: bool) -> Iterator[None]:
    if enabled:
        from contextlib import nullcontext
        return nullcontext()
    return model.disable_adapter()


def evaluate(
    config: dict[str, Any],
    preflight: dict[str, Any],
    output_directory: Path,
) -> dict[str, Any]:
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["PYTORCH_ENABLE_MPS_FALLBACK"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed

    set_seed(int(config["seed"]))
    tokenizer = AutoTokenizer.from_pretrained(
        preflight["model_path"],
        local_files_only=True,
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    base = AutoModelForCausalLM.from_pretrained(
        preflight["model_path"],
        dtype=torch.float32,
        local_files_only=True,
    )
    model = PeftModel.from_pretrained(
        base,
        preflight["adapter_path"],
        is_trainable=False,
    )
    model.config.use_cache = True
    model.to("mps")
    model.eval()
    cases = read_jsonl(Path(preflight["benchmark_path"]))
    preferences = read_jsonl(Path(preflight["validation_path"]))
    outputs: dict[str, list[dict[str, Any]]] = {"base": [], "adapted": []}
    started = time.monotonic()
    for arm, enabled in (("base", False), ("adapted", True)):
        with adapter_state(model, enabled):
            for index, case in enumerate(cases, start=1):
                user_content = case["question"]
                reference_context = case.get("referenceContext")
                if isinstance(reference_context, str) and reference_context.strip():
                    user_content += (
                        "\n\n可用知识库参考片段：\n"
                        "[来源 1]\n"
                        + reference_context.strip()
                    )
                messages = [
                    {"role": "system", "content": config["system_prompt"]},
                    {"role": "user", "content": user_content},
                ]
                answer = generate_answer(
                    model,
                    tokenizer,
                    messages,
                    config["generation"],
                    torch,
                )
                outputs[arm].append({
                    "id": case["id"],
                    "tags": case.get("tags") or [],
                    "answer": answer,
                    "answer_characters": len(answer),
                    "score": score_answer(case, answer),
                })
                print(
                    f"{arm} Benchmark {index}/{len(cases)}",
                    file=sys.stderr,
                    flush=True,
                )
    preference_results: dict[str, list[dict[str, Any]]] = {
        "base": [],
        "adapted": [],
    }
    for arm, enabled in (("base", False), ("adapted", True)):
        with adapter_state(model, enabled):
            for item in preferences:
                chosen_logp = response_log_probability(
                    model,
                    tokenizer,
                    item["prompt"],
                    assistant_content(item["chosen"]),
                    int(config["preference_max_length"]),
                    torch,
                )
                rejected_logp = response_log_probability(
                    model,
                    tokenizer,
                    item["prompt"],
                    assistant_content(item["rejected"]),
                    int(config["preference_max_length"]),
                    torch,
                )
                preference_results[arm].append({
                    "pair_id": item.get("pair_id"),
                    "chosen_log_probability": round(chosen_logp, 6),
                    "rejected_log_probability": round(rejected_logp, 6),
                    "margin": round(chosen_logp - rejected_logp, 6),
                    "correct": chosen_logp > rejected_logp,
                })
    before_by_id = {item["id"]: item for item in outputs["base"]}
    after_by_id = {item["id"]: item for item in outputs["adapted"]}
    deltas = [
        after_by_id[case["id"]]["score"]["total"]
        - before_by_id[case["id"]]["score"]["total"]
        for case in cases
    ]
    safety_deltas = [
        after_by_id[case["id"]]["score"]["total"]
        - before_by_id[case["id"]]["score"]["total"]
        for case in cases
        if SAFETY_TAGS.intersection(case.get("tags") or [])
    ]
    before_scores = [
        before_by_id[case["id"]]["score"]["total"] for case in cases
    ]
    after_scores = [
        after_by_id[case["id"]]["score"]["total"] for case in cases
    ]
    changed = sum(
        before_by_id[case["id"]]["answer"]
        != after_by_id[case["id"]]["answer"]
        for case in cases
    )
    wins = sum(delta > 0 for delta in deltas)
    losses = sum(delta < 0 for delta in deltas)
    ties = len(deltas) - wins - losses
    preference_accuracy = {
        arm: mean([float(item["correct"]) for item in values])
        for arm, values in preference_results.items()
    }
    ci_low, ci_high = bootstrap_ci(
        deltas,
        int(config["bootstrap_samples"]),
        int(config["seed"]),
    )
    metrics = {
        "benchmark": {
            "case_count": len(cases),
            "base_mean_score": round(mean(before_scores), 6),
            "adapted_mean_score": round(mean(after_scores), 6),
            "mean_delta": round(mean(deltas), 6),
            "bootstrap_95_percent_ci": [ci_low, ci_high],
            "wins": wins,
            "ties": ties,
            "losses": losses,
            "changed_answers": changed,
            "safety_case_count": len(safety_deltas),
            "safety_mean_delta": round(mean(safety_deltas), 6),
        },
        "preference_holdout": {
            "pair_count": len(preferences),
            "base_accuracy": round(preference_accuracy["base"], 6),
            "adapted_accuracy": round(preference_accuracy["adapted"], 6),
            "accuracy_delta": round(
                preference_accuracy["adapted"]
                - preference_accuracy["base"],
                6,
            ),
            "base_mean_margin": round(
                mean([item["margin"] for item in preference_results["base"]]),
                6,
            ),
            "adapted_mean_margin": round(
                mean(
                    [item["margin"] for item in preference_results["adapted"]]
                ),
                6,
            ),
        },
    }
    gates = config["promotion_gates"]
    checks = {
        "benchmark_mean_non_degrading": (
            metrics["benchmark"]["mean_delta"]
            >= float(gates["minimum_benchmark_mean_delta"])
        ),
        "benchmark_ci_non_inferior": (
            ci_low >= float(gates["minimum_bootstrap_ci_low"])
        ),
        "safety_non_degrading": (
            metrics["benchmark"]["safety_mean_delta"]
            >= float(gates["minimum_safety_mean_delta"])
        ),
        "preference_accuracy_sufficient": (
            metrics["preference_holdout"]["adapted_accuracy"]
            >= float(gates["minimum_preference_accuracy"])
        ),
        "preference_accuracy_non_degrading": (
            metrics["preference_holdout"]["accuracy_delta"]
            >= float(gates["minimum_preference_accuracy_delta"])
        ),
        "adapter_changes_behavior": (
            changed >= int(gates["minimum_changed_answers"])
        ),
    }
    output_directory.mkdir(parents=True, exist_ok=True)
    outputs_path = output_directory / "paired-outputs.json"
    outputs_path.write_text(
        json.dumps(
            {
                "benchmark": outputs,
                "preference_holdout": preference_results,
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    return {
        "state": "COMPLETED",
        "decision": "PROMOTED" if all(checks.values()) else "NOT_PROMOTED",
        "completed_at": datetime.now(UTC).isoformat(),
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "metrics": metrics,
        "promotion_checks": checks,
        "paired_outputs_path": str(outputs_path),
        "paired_outputs_fingerprint": file_sha256(outputs_path),
        "mps_driver_allocated_bytes": int(torch.mps.driver_allocated_memory()),
        "cloud_training_cny": 0,
        "model_api_calls": 0,
        "paid_fallback_used": False,
    }


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    try:
        config, preflight = build_preflight(
            args.config.resolve(),
            args.benchmark.resolve(),
            args.validation.resolve(),
            args.training_report.resolve(),
            args.model_path.resolve(),
            args.adapter_path.resolve(),
        )
        if not args.execute:
            print(json.dumps(preflight, ensure_ascii=False, indent=2))
            return 0
        require_execution_authorization(preflight)
        report_path = args.output / "evaluation-report.json"
        report = {
            **preflight,
            "report_schema_version": REPORT_SCHEMA_VERSION,
            "state": "EVALUATING",
            "started_at": datetime.now(UTC).isoformat(),
        }
        write_report(report_path, report)
        try:
            report.update(evaluate(config, preflight, args.output))
        except (Exception, KeyboardInterrupt) as exception:
            report.update({
                "state": (
                    "CANCELLED"
                    if isinstance(exception, KeyboardInterrupt)
                    else "FAILED"
                ),
                "failed_at": datetime.now(UTC).isoformat(),
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
            "decision": report["decision"],
            "elapsed_seconds": report["elapsed_seconds"],
            "metrics": report["metrics"],
            "promotion_checks": report["promotion_checks"],
            "cloud_training_cny": 0,
            "model_api_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"Local proxy evaluation failed: {exception}", file=sys.stderr)
        return 2
    except Exception as exception:
        print(
            f"Local proxy evaluation failed locally: "
            f"{type(exception).__name__}: {exception}",
            file=sys.stderr,
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())

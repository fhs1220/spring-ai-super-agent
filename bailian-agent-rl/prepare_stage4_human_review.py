#!/usr/bin/env python3
"""Build and validate the frozen Stage 4 human-anchor review bundle.

The generated HTML is fully local and makes no network or model calls. Human
labels are exported as JSON and must pass the same frozen identity contract
before they can be used for calibration or written back to trajectories.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent-rl-human-review-bundle-v1"
LABEL_SCHEMA_VERSION = "agent-rl-human-anchor-labels-v1"
DIMENSIONS = (
    "INSTRUCTION_FOLLOWING",
    "ACTIONABILITY",
    "LOGICAL_CONSISTENCY",
    "CRITICAL_REVIEW",
)
MINIMUM_COMMENT_CHARS = 8


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--trajectories", type=Path, required=True)
    parser.add_argument("--assessments", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--validated-output", type=Path)
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
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def build_bundle(
    manifest: dict[str, Any],
    trajectories: Path,
    assessments: Path,
) -> dict[str, Any]:
    if (
        manifest.get("state") != "COMPLETED"
        or manifest.get("mode") != "execute"
        or manifest.get("execution_summary", {}).get("complete_panel_count") != 119
    ):
        raise ValueError("Stage 4 manifest is not the completed frozen execution")
    human_review = manifest.get("human_review")
    sample = (
        human_review.get("post_judge_sample")
        if isinstance(human_review, dict)
        else None
    )
    if (
        not isinstance(sample, list)
        or len(sample) != 30
        or human_review.get("post_judge_contract_version")
        != "judge-risk-stratified-human-anchor-v1"
    ):
        raise ValueError("Stage 4 post-Judge human sample is not frozen at 30")
    items = []
    seen_trajectories: set[str] = set()
    for expected_rank, selected in enumerate(sample, start=1):
        if not isinstance(selected, dict) or selected.get("rank") != expected_rank:
            raise ValueError("human sample ranks must be a contiguous frozen order")
        trajectory_id = selected.get("trajectory_id")
        if (
            not isinstance(trajectory_id, str)
            or not trajectory_id
            or trajectory_id in seen_trajectories
        ):
            raise ValueError("human sample has an invalid or duplicate trajectory")
        seen_trajectories.add(trajectory_id)
        trajectory = load_object(trajectories / f"{trajectory_id}.json")
        assessment = load_object(assessments / f"{trajectory_id}.json")
        if (
            trajectory.get("trajectoryId") != trajectory_id
            or assessment.get("trajectoryId") != trajectory_id
            or assessment.get("judgeCount") != len(DIMENSIONS)
            or assessment.get("policyVersion")
            != manifest["source_replay"]["policy_version"]
        ):
            raise ValueError(f"review evidence identity mismatch: {trajectory_id}")
        question = str(trajectory.get("question") or "").strip()
        answer = str(trajectory.get("finalAnswer") or "").strip()
        if not question or not answer:
            raise ValueError(f"review evidence has blank content: {trajectory_id}")
        raw_scores = assessment.get("judgeScores")
        scores = {
            score.get("dimension"): score
            for score in raw_scores
            if isinstance(score, dict)
        } if isinstance(raw_scores, list) else {}
        if set(scores) != set(DIMENSIONS):
            raise ValueError(f"review evidence has incomplete dimensions: {trajectory_id}")
        items.append({
            "rank": expected_rank,
            "seed_id": selected["seed_id"],
            "trajectory_id": trajectory_id,
            "execution_mode": selected["execution_mode"],
            "domains": selected["domains"],
            "request_type": selected["request_type"],
            "priority_reasons": selected["priority_reasons"],
            "question": question,
            "answer": answer,
            "question_fingerprint": text_sha256(question),
            "answer_fingerprint": text_sha256(answer),
            "verifier_reward": assessment.get("verifierReward"),
            "ai_reward": assessment.get("aiReward"),
            "total_reward": assessment.get("totalReward"),
            "old_confidence": assessment.get("confidence"),
            "old_agreement": assessment.get("judgeAgreement"),
            "training_decision": assessment.get("trainingDecision"),
            "judge_scores": [
                {
                    "dimension": dimension,
                    "score": scores[dimension].get("score"),
                    "confidence": scores[dimension].get("confidence"),
                    "rationale": scores[dimension].get("rationale"),
                }
                for dimension in DIMENSIONS
            ],
        })
    identity = {
        "schema_version": SCHEMA_VERSION,
        "source_batch_id": manifest["batch_id"],
        "source_plan_fingerprint": manifest["plan_fingerprint"],
        "review_contract_version": human_review[
            "post_judge_contract_version"
        ],
        "policy_version": manifest["source_replay"]["policy_version"],
        "item_identities": [
            {
                "rank": item["rank"],
                "seed_id": item["seed_id"],
                "trajectory_id": item["trajectory_id"],
                "question_fingerprint": item["question_fingerprint"],
                "answer_fingerprint": item["answer_fingerprint"],
            }
            for item in items
        ],
    }
    return {
        **identity,
        "bundle_fingerprint": canonical_sha256(identity),
        "export_filename": "policy-v7-stage4-human-labels-30.json",
        "minimum_comment_chars": MINIMUM_COMMENT_CHARS,
        "dimensions": list(DIMENSIONS),
        "items": items,
    }


def validate_rating(value: Any, field: str, rank: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 5:
        raise ValueError(f"label {rank} {field} must be an integer from 1 to 5")
    return value


def validate_labels(
    bundle: dict[str, Any],
    labels: dict[str, Any],
) -> dict[str, Any]:
    if labels.get("schema_version") != LABEL_SCHEMA_VERSION:
        raise ValueError(f"labels schema_version must be {LABEL_SCHEMA_VERSION}")
    for label_field, bundle_field in (
        ("source_batch_id", "source_batch_id"),
        ("source_plan_fingerprint", "source_plan_fingerprint"),
        ("review_contract_version", "review_contract_version"),
        ("bundle_fingerprint", "bundle_fingerprint"),
    ):
        if labels.get(label_field) != bundle.get(bundle_field):
            raise ValueError(f"labels {label_field} does not match the bundle")
    reviewer_id = labels.get("reviewer_id")
    if not isinstance(reviewer_id, str) or len(reviewer_id.strip()) < 2:
        raise ValueError("reviewer_id must contain at least 2 characters")
    raw_labels = labels.get("labels")
    if not isinstance(raw_labels, list) or len(raw_labels) != len(bundle["items"]):
        raise ValueError("labels must contain exactly one entry per review item")
    normalized = []
    for item, label in zip(bundle["items"], raw_labels):
        if not isinstance(label, dict):
            raise ValueError(f"label {item['rank']} must be an object")
        for field in (
            "rank",
            "seed_id",
            "trajectory_id",
            "question_fingerprint",
            "answer_fingerprint",
        ):
            if label.get(field) != item.get(field):
                raise ValueError(
                    f"label {item['rank']} {field} does not match the bundle"
                )
        dimensions = label.get("dimension_ratings")
        if not isinstance(dimensions, dict) or set(dimensions) != set(DIMENSIONS):
            raise ValueError(
                f"label {item['rank']} must rate all four dimensions"
            )
        comment = label.get("comment")
        if (
            not isinstance(comment, str)
            or len(comment.strip()) < MINIMUM_COMMENT_CHARS
            or len(comment.strip()) > 1000
        ):
            raise ValueError(
                f"label {item['rank']} comment must contain "
                f"{MINIMUM_COMMENT_CHARS}-1000 characters"
            )
        normalized.append({
            "rank": item["rank"],
            "seed_id": item["seed_id"],
            "trajectory_id": item["trajectory_id"],
            "question_fingerprint": item["question_fingerprint"],
            "answer_fingerprint": item["answer_fingerprint"],
            "overall_rating": validate_rating(
                label.get("overall_rating"), "overall_rating", item["rank"]
            ),
            "dimension_ratings": {
                dimension: validate_rating(
                    dimensions.get(dimension), dimension, item["rank"]
                )
                for dimension in DIMENSIONS
            },
            "comment": comment.strip(),
        })
    payload = {
        "schema_version": LABEL_SCHEMA_VERSION,
        "source_batch_id": bundle["source_batch_id"],
        "source_plan_fingerprint": bundle["source_plan_fingerprint"],
        "review_contract_version": bundle["review_contract_version"],
        "bundle_fingerprint": bundle["bundle_fingerprint"],
        "reviewer_id": reviewer_id.strip(),
        "exported_at": labels.get("exported_at"),
        "labels": normalized,
    }
    ratings = [label["overall_rating"] for label in normalized]
    return {
        **payload,
        "validation": {
            "validated_at": datetime.now(timezone.utc).isoformat(),
            "label_count": len(normalized),
            "complete": True,
            "positive_anchor_count": sum(rating >= 4 for rating in ratings),
            "negative_anchor_count": sum(rating <= 2 for rating in ratings),
            "holdout_anchor_count": sum(rating == 3 for rating in ratings),
            "average_overall_rating": round(sum(ratings) / len(ratings), 6),
            "label_fingerprint": canonical_sha256(payload),
        },
    }


def html_document(bundle: dict[str, Any]) -> str:
    embedded = json.dumps(bundle, ensure_ascii=False).replace("</", "<\\/")
    return HTML_TEMPLATE.replace("__REVIEW_BUNDLE__", embedded)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def write_json(path: Path, value: dict[str, Any]) -> None:
    write_text(
        path,
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )


def main() -> int:
    args = parse_args()
    try:
        manifest = load_object(args.manifest)
        bundle = build_bundle(
            manifest,
            args.trajectories,
            args.assessments,
        )
        if args.labels:
            validated = validate_labels(bundle, load_object(args.labels))
            if args.validated_output is None:
                raise ValueError("--labels requires --validated-output")
            write_json(args.validated_output, validated)
            print(json.dumps({
                "mode": "validate-labels",
                "bundle_fingerprint": bundle["bundle_fingerprint"],
                **validated["validation"],
            }, ensure_ascii=False, indent=2))
            return 0
        if args.output is None:
            raise ValueError("bundle generation requires --output")
        write_text(args.output, html_document(bundle))
        print(json.dumps({
            "mode": "build-review-bundle",
            "output": str(args.output),
            "item_count": len(bundle["items"]),
            "bundle_fingerprint": bundle["bundle_fingerprint"],
            "model_calls": 0,
        }, ensure_ascii=False, indent=2))
        return 0
    except ValueError as exception:
        print(f"error: {exception}", file=os.sys.stderr)
        return 2


HTML_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Stage 4 人工锚点评审</title>
  <style>
    :root{font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif;color:#172033;background:#f3f6fb}
    *{box-sizing:border-box}body{margin:0}.shell{max-width:1180px;margin:auto;padding:24px}
    header,.card,.toolbar{background:#fff;border:1px solid #dce3ef;border-radius:16px;box-shadow:0 8px 24px #29466f12}
    header{padding:24px;margin-bottom:16px}h1{margin:0 0 8px;font-size:26px}.muted{color:#667085}
    .toolbar{position:sticky;top:8px;z-index:5;padding:14px 18px;display:flex;gap:14px;align-items:center;flex-wrap:wrap}
    .progress{height:10px;background:#e8edf5;border-radius:999px;overflow:hidden;flex:1;min-width:180px}
    .progress i{display:block;height:100%;background:#3976e8;width:0}.reviewer{padding:9px 12px;border:1px solid #c9d3e2;border-radius:9px}
    button{border:0;border-radius:9px;padding:10px 14px;background:#2463d4;color:#fff;font-weight:700;cursor:pointer}
    button.secondary{background:#e8eef9;color:#26456f}.card{padding:22px;margin:18px 0}
    .meta{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.tag{background:#edf3ff;color:#315c9a;border-radius:999px;padding:4px 9px;font-size:12px}
    .content{display:grid;grid-template-columns:1fr 1.4fr;gap:16px}.pane{border:1px solid #e0e6ef;border-radius:12px;padding:16px;white-space:pre-wrap;line-height:1.65;max-height:520px;overflow:auto}
    .pane h3{position:sticky;top:-16px;background:#fff;margin:-16px -16px 12px;padding:14px 16px;border-bottom:1px solid #edf0f5}
    .judges{margin:16px 0;display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.judge{background:#f8faff;border-radius:10px;padding:12px;font-size:13px}
    .rating-grid{display:grid;grid-template-columns:210px 1fr;gap:10px 14px;align-items:center}.stars{display:flex;gap:7px}
    .stars label{border:1px solid #cbd5e4;border-radius:8px;padding:7px 11px;cursor:pointer}.stars input{margin-right:4px}
    textarea{width:100%;min-height:80px;padding:10px;border:1px solid #cbd5e4;border-radius:9px;resize:vertical}
    .invalid{outline:2px solid #e5484d}.complete{border-color:#69b37b}.footer-note{font-size:12px;color:#667085}
    @media(max-width:760px){.content,.judges{grid-template-columns:1fr}.rating-grid{grid-template-columns:1fr}.shell{padding:12px}}
  </style>
</head>
<body>
<div class="shell">
  <header>
    <h1>Stage 4 人工锚点评审</h1>
    <p id="review-guidance"></p>
    <p class="muted">1=不可用，2=明显较差，3=有好有坏需保留，4=可用但有小问题，5=高质量可直接使用。</p>
  </header>
  <div class="toolbar">
    <input id="reviewer" class="reviewer" placeholder="评审人姓名/代号（必填）">
    <strong id="count">0 / 30</strong>
    <div class="progress"><i id="bar"></i></div>
    <button class="secondary" id="clear">清空本地进度</button>
    <button id="export">完成并导出 JSON</button>
  </div>
  <main id="items"></main>
</div>
<script type="application/json" id="bundle">__REVIEW_BUNDLE__</script>
<script>
const bundle=JSON.parse(document.getElementById('bundle').textContent);
const key='stage4-human-review:'+bundle.bundle_fingerprint;
const dimensionNames={INSTRUCTION_FOLLOWING:'指令遵循',ACTIONABILITY:'可执行性',LOGICAL_CONSISTENCY:'逻辑一致性',CRITICAL_REVIEW:'严格反审后仍稳健'};
let state=JSON.parse(localStorage.getItem(key)||'{"reviewer":"","labels":{}}');
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const rating=(rank,name)=>`<div class="stars">${[1,2,3,4,5].map(v=>`<label><input type="radio" name="${name}-${rank}" value="${v}">${v}</label>`).join('')}</div>`;
document.getElementById('review-guidance').textContent=bundle.blind_review===true
 ? '请独立阅读问题和回答，再填写总体评分与四个维度评分。本页不提供任何自动评分信号。'
 : '请独立阅读问题和回答，再填写总体评分与四个维度评分。Judge 结果仅作复核参考，不要机械照抄。';
document.getElementById('reviewer').value=state.reviewer||'';
document.getElementById('items').innerHTML=bundle.items.map(item=>`
<section class="card" id="item-${item.rank}">
 <div class="meta"><b>#${item.rank}</b><span class="tag">${esc(item.execution_mode)}</span><span class="tag">${esc(item.request_type)}</span><span class="tag">${esc(item.domains.join(' + '))}</span></div>
 <div class="content"><div class="pane"><h3>用户问题</h3>${esc(item.question)}</div><div class="pane"><h3>候选回答</h3>${esc(item.answer)}</div></div>
 ${bundle.show_judge_opinions===false?'':`<details><summary>查看四 Judge 原始意见（旧聚合结果仅供参考）</summary><div class="judges">${item.judge_scores.map(j=>`<div class="judge"><b>${dimensionNames[j.dimension]}</b> · 分数 ${j.score} · 置信度 ${j.confidence}<br>${esc(j.rationale)}</div>`).join('')}</div></details>`}
 <div class="rating-grid">
  <b>总体评分</b>${rating(item.rank,'overall')}
  ${bundle.dimensions.map(d=>`<b>${dimensionNames[d]}</b>${rating(item.rank,d)}`).join('')}
  <b>人工理由</b><textarea data-comment="${item.rank}" placeholder="至少 ${bundle.minimum_comment_chars} 个字：指出最关键的优点、缺陷或风险"></textarea>
 </div>
</section>`).join('');
function save(){
 state.reviewer=document.getElementById('reviewer').value.trim();
 bundle.items.forEach(item=>{
  const old=state.labels[item.rank]||{};const dimensions={};
  bundle.dimensions.forEach(d=>{const e=document.querySelector(`input[name="${d}-${item.rank}"]:checked`);dimensions[d]=e?Number(e.value):null});
  const overall=document.querySelector(`input[name="overall-${item.rank}"]:checked`);
  state.labels[item.rank]={overall_rating:overall?Number(overall.value):null,dimension_ratings:dimensions,comment:document.querySelector(`[data-comment="${item.rank}"]`).value.trim()};
 });
 localStorage.setItem(key,JSON.stringify(state));refresh();
}
function complete(label){return label&&Number.isInteger(label.overall_rating)&&bundle.dimensions.every(d=>Number.isInteger(label.dimension_ratings?.[d]))&&(label.comment||'').length>=bundle.minimum_comment_chars}
function refresh(){
 let done=0;bundle.items.forEach(item=>{const ok=complete(state.labels[item.rank]);document.getElementById(`item-${item.rank}`).classList.toggle('complete',ok);if(ok)done++});
 document.getElementById('count').textContent=`${done} / ${bundle.items.length}`;document.getElementById('bar').style.width=`${done/bundle.items.length*100}%`;
}
bundle.items.forEach(item=>{const label=state.labels[item.rank];if(!label)return;document.querySelector(`[data-comment="${item.rank}"]`).value=label.comment||'';if(label.overall_rating){const e=document.querySelector(`input[name="overall-${item.rank}"][value="${label.overall_rating}"]`);if(e)e.checked=true}bundle.dimensions.forEach(d=>{const v=label.dimension_ratings?.[d];if(v){const e=document.querySelector(`input[name="${d}-${item.rank}"][value="${v}"]`);if(e)e.checked=true}})});
document.getElementById('reviewer').addEventListener('input',save);document.getElementById('items').addEventListener('change',save);document.getElementById('items').addEventListener('input',save);
document.getElementById('clear').onclick=()=>{if(confirm('确认清空当前浏览器里的全部评审进度？')){localStorage.removeItem(key);location.reload()}};
document.getElementById('export').onclick=()=>{
 save();const missing=bundle.items.filter(i=>!complete(state.labels[i.rank]));if(state.reviewer.length<2){alert('请先填写至少 2 个字符的评审人姓名或代号');return}if(missing.length){document.getElementById(`item-${missing[0].rank}`).scrollIntoView({behavior:'smooth'});alert(`还有 ${missing.length} 条未完整填写`);return}
 const labels=bundle.items.map(i=>({rank:i.rank,seed_id:i.seed_id,trajectory_id:i.trajectory_id,question_fingerprint:i.question_fingerprint,answer_fingerprint:i.answer_fingerprint,...state.labels[i.rank]}));
 const output={schema_version:'agent-rl-human-anchor-labels-v1',source_batch_id:bundle.source_batch_id,source_plan_fingerprint:bundle.source_plan_fingerprint,review_contract_version:bundle.review_contract_version,bundle_fingerprint:bundle.bundle_fingerprint,reviewer_id:state.reviewer,exported_at:new Date().toISOString(),labels};
 const blob=new Blob([JSON.stringify(output,null,2)+'\\n'],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=bundle.export_filename||'policy-v7-stage4-human-labels.json';a.click();URL.revokeObjectURL(a.href);
};
refresh();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    raise SystemExit(main())

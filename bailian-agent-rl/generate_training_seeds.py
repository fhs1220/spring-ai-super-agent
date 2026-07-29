#!/usr/bin/env python3
"""Build deterministic, knowledge-grounded trajectory seeds.

The generated JSONL is an input queue for ``replay_training_seeds.py``.  It is
deliberately marked ``trajectory_seed_only`` and must never be submitted to an
RL job as if its reference text were a model rollout.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable, NamedTuple


SCHEMA_VERSION = "agent-rl-trajectory-seed-v2"
DATASET_ROLE = "trajectory_seed_only"
DEFAULT_SIMILARITY_THRESHOLD = 0.82
DEFAULT_MULTI_AGENT_RATIO = 0.5
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOCUMENT_DIR = PROJECT_ROOT / "src/main/resources/document"
DEFAULT_BENCHMARK = (
    PROJECT_ROOT / "src/main/resources/evaluation/love-rag-ab.jsonl"
)
DEFAULT_ROUTING_CONTRACT = (
    PROJECT_ROOT
    / "src/main/resources/multiagent/deterministic-routing-contract-v1.json"
)

SCENARIOS = (
    "我工作日时间很紧",
    "我不擅长表达感受",
    "对方最近压力也很大",
    "我们已经为这件事争论过几次",
    "我们希望从今天开始小步改善",
    "我们只有晚上二十分钟能沟通",
    "双方目前都比较疲惫",
    "我们想避免互相指责",
    "我希望方案兼顾双方边界",
    "我们需要一个低成本、可持续的做法",
)

REQUESTS = (
    ("actions", "请直接给出三项今天能执行的行动，并说明检查结果的方法。"),
    ("weekly_plan", "不要追问，请制定七天小计划，每天写行动和复盘。"),
    ("checklist", "请给一份按优先级排序的检查清单，并标明合理假设。"),
    ("dialogue", "请给出一次十五分钟沟通流程和可直接使用的话术。"),
    ("decision", "请给出共同决策步骤、分歧处理规则和下次复盘时间。"),
)

CONCEPT_RULES = (
    ("魅力", ("个人卫生|形象", "兴趣|阅读|乐器", "锻炼|健康", "社交|沟通", "自信")),
    ("社交场合", ("微笑|亲和", "话题", "真诚|兴趣", "倾听", "反馈")),
    ("线上交友", ("真实|资料", "聊天|频率", "兴趣", "隐私|保护", "了解|筛选")),
    ("焦虑", ("焦虑|情绪", "原因", "充实|兴趣", "亲友|支持", "情绪管理")),
    ("相亲对象", ("尊重", "价值观", "情绪稳定", "人生规划|未来", "观察|了解")),
    ("争吵", ("冷静|暂停", "倾听|理解", "我觉得|我感受", "根源", "共同|解决")),
    ("浪漫", ("喜好", "约会|惊喜", "日常", "用心", "边界|舒适")),
    ("保持自我", ("兴趣爱好", "社交圈", "工作|学习|目标", "独立", "平衡")),
    ("未来规划", ("时机", "未来|设想", "倾听", "差异", "共同目标|阶段计划")),
    ("缺点", ("时机|地点", "关心", "具体|影响", "建议", "共同|监督")),
    ("工作与家庭", ("日程|时间", "家务|分工", "协商", "效率|加班", "家庭活动")),
    ("亲密关系", ("二人世界|约会", "亲密|拥抱", "分享|交流", "回忆", "惊喜")),
    ("育儿", ("孩子|育儿", "接送|喂养|哄睡", "排班|分工", "替补", "复盘|调整")),
    ("伴侣家人", ("冷静", "倾听|立场", "伴侣|共同", "尊重|温和", "协调")),
    ("自我成长", ("目标", "碎片时间", "伴侣|支持", "家庭事务", "学习|小组")),
    ("消费观念", ("消费观|沟通", "预算|支出", "储蓄|目标", "大额消费", "共同决定")),
)

DOMAIN_CONCEPTS = {
    "RELATIONSHIP": "沟通|倾听|协商|复盘",
    "PARENTING": "孩子|育儿|接送|哄睡",
    "HOUSEHOLD": "家务|做饭|清洁|分工",
    "FINANCE": "预算|支出|储蓄|财务",
    "SAFETY": "安全|求助|离开|支持",
}


class KnowledgeUnit(NamedTuple):
    category: str
    title: str
    answer: str
    source_path: str
    source_sha256: str


class CompositeBlueprint(NamedTuple):
    name: str
    situation: str
    source_title_fragments: tuple[str, ...]
    expected_domains: tuple[str, ...]


COMPOSITE_BLUEPRINTS = (
    CompositeBlueprint(
        "parenting-household-relationship",
        "孩子接送和哄睡挤占了晚间时间，我们又因家务分工频繁争吵",
        ("共同承担育儿和照护责任", "平衡工作与家庭责任"),
        ("RELATIONSHIP", "PARENTING", "HOUSEHOLD"),
    ),
    CompositeBlueprint(
        "parenting-finance-relationship",
        "育儿支出让家庭预算变紧，夫妻对存钱目标和照护安排意见不同",
        ("共同承担育儿和照护责任", "消费观念不同"),
        ("RELATIONSHIP", "PARENTING", "FINANCE"),
    ),
    CompositeBlueprint(
        "parenting-household-finance",
        "孩子作息、家务分工和每月预算都需要同时重新安排",
        ("共同承担育儿和照护责任", "平衡工作与家庭责任", "消费观念不同"),
        ("PARENTING", "HOUSEHOLD", "FINANCE"),
    ),
    CompositeBlueprint(
        "household-finance-relationship",
        "家务分工和每月支出让伴侣反复争吵",
        ("处理双方的争吵", "消费观念不同"),
        ("RELATIONSHIP", "HOUSEHOLD", "FINANCE"),
    ),
    CompositeBlueprint(
        "parenting-household",
        "孩子接送、哄睡和做饭清洁都压在晚上，育儿与家务需要共同分工",
        ("共同承担育儿和照护责任", "平衡工作与家庭责任"),
        ("PARENTING", "HOUSEHOLD"),
    ),
    CompositeBlueprint(
        "parenting-relationship",
        "夫妻因孩子教育和育儿轮班产生分歧，希望恢复沟通",
        ("共同承担育儿和照护责任", "处理双方的争吵"),
        ("RELATIONSHIP", "PARENTING"),
    ),
    CompositeBlueprint(
        "household-relationship",
        "伴侣因做饭、清洁和其他家务分工争吵，亲密感也受到影响",
        ("平衡工作与家庭责任", "维护婚后夫妻间的亲密关系"),
        ("RELATIONSHIP", "HOUSEHOLD"),
    ),
    CompositeBlueprint(
        "finance-relationship",
        "夫妻对预算、支出和储蓄目标意见不同，需要改善经济沟通",
        ("消费观念不同", "沟通未来规划"),
        ("RELATIONSHIP", "FINANCE"),
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--document-dir", type=Path, default=DEFAULT_DOCUMENT_DIR)
    parser.add_argument("--benchmark", type=Path, default=DEFAULT_BENCHMARK)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--target", type=int, default=300)
    parser.add_argument(
        "--multi-agent-ratio",
        type=float,
        default=DEFAULT_MULTI_AGENT_RATIO,
        help="Expected multi-Agent share in the offline seed set.",
    )
    parser.add_argument(
        "--similarity-threshold",
        type=float,
        default=DEFAULT_SIMILARITY_THRESHOLD,
    )
    return parser.parse_args()


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def load_routing_contract(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(
            f"Routing contract is unreadable: {path}: {exception}"
        ) from exception
    if not isinstance(value, dict):
        raise ValueError("Routing contract must be an object")
    keywords = value.get("domain_keywords")
    order = value.get("domain_selection_order")
    if (
        not isinstance(value.get("schema_version"), str)
        or not isinstance(keywords, dict)
        or not keywords
        or any(
            not isinstance(domain, str)
            or not isinstance(items, list)
            or not items
            or any(not isinstance(item, str) or not item for item in items)
            for domain, items in keywords.items()
        )
        or not isinstance(order, list)
        or set(order) != set(keywords)
        or value.get("fallback_domain") not in keywords
        or not isinstance(value.get("minimum_domains"), int)
        or value["minimum_domains"] < 1
        or not isinstance(value.get("max_agents"), int)
        or value["max_agents"] < 1
    ):
        raise ValueError(f"Routing contract is invalid: {path}")
    return value


ROUTING_CONTRACT = load_routing_contract(DEFAULT_ROUTING_CONTRACT)
ROUTER_CONTRACT_VERSION = ROUTING_CONTRACT["schema_version"]
DOMAIN_KEYWORDS = {
    domain: tuple(keywords)
    for domain, keywords in ROUTING_CONTRACT["domain_keywords"].items()
}
DOMAIN_SELECTION_ORDER = tuple(
    ROUTING_CONTRACT["domain_selection_order"]
)


def normalize_question(value: str) -> str:
    return re.sub(r"[\W_]+", "", value, flags=re.UNICODE).lower()


def ngrams(value: str, size: int = 3) -> set[str]:
    normalized = normalize_question(value)
    if len(normalized) <= size:
        return {normalized} if normalized else set()
    return {
        normalized[index:index + size]
        for index in range(len(normalized) - size + 1)
    }


def question_similarity(left: str, right: str) -> float:
    left_normalized = normalize_question(left)
    right_normalized = normalize_question(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized == right_normalized:
        return 1.0
    left_grams = ngrams(left)
    right_grams = ngrams(right)
    union = left_grams | right_grams
    return len(left_grams & right_grams) / len(union) if union else 0.0


def load_benchmark(path: Path) -> list[dict[str, str]]:
    cases: list[dict[str, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as exception:
        raise ValueError(f"Benchmark does not exist: {path}") from exception
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(
                f"{path}:{line_number} is not valid JSON: {exception}"
            ) from exception
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{line_number} must be an object")
        case_id = value.get("id")
        question = value.get("question")
        if not isinstance(case_id, str) or not isinstance(question, str):
            raise ValueError(f"{path}:{line_number} requires id and question")
        cases.append({"id": case_id, "question": question})
    if not cases:
        raise ValueError("Benchmark must not be empty")
    return cases


def clean_reference_answer(answer: str) -> str:
    answer = re.sub(r"推荐课程：.*", "", answer, flags=re.DOTALL)
    answer = re.sub(r"\s+", " ", answer).strip()
    return answer


def load_knowledge_units(document_dir: Path) -> list[KnowledgeUnit]:
    units: list[KnowledgeUnit] = []
    paths = sorted(document_dir.glob("*.md"), key=lambda path: path.name)
    if not paths:
        raise ValueError(f"No Markdown documents found in {document_dir}")
    heading_pattern = re.compile(
        r"^####\s+(.+?)\s*$\n(.*?)(?=^####\s+|\Z)",
        flags=re.MULTILINE | re.DOTALL,
    )
    for path in paths:
        text = path.read_text(encoding="utf-8")
        category_match = re.search(r"-\s*([^-\n]+)篇", path.stem)
        category = (
            category_match.group(1).strip()
            if category_match
            else path.stem
        )
        relative_path = path.resolve().relative_to(PROJECT_ROOT.resolve())
        for match in heading_pattern.finditer(text):
            title = match.group(1).strip()
            answer = clean_reference_answer(match.group(2))
            if not answer:
                continue
            source_payload = f"{title}\n{answer}"
            units.append(
                KnowledgeUnit(
                    category=category,
                    title=title,
                    answer=answer,
                    source_path=str(relative_path),
                    source_sha256=sha256_text(source_payload),
                )
            )
    if not units:
        raise ValueError(f"No level-4 knowledge sections found in {document_dir}")
    return units


def concepts_for(title: str) -> tuple[str, ...]:
    for keyword, concepts in CONCEPT_RULES:
        if keyword in title:
            return concepts
    return ("沟通", "倾听", "行动", "边界", "复盘")


def tags_for(unit: KnowledgeUnit, request_name: str) -> list[str]:
    tags = [unit.category, request_name, "knowledge-grounded"]
    title = unit.title
    for keyword, tag in (
        ("消费", "finance"),
        ("育儿", "parenting"),
        ("家庭", "family"),
        ("争吵", "conflict"),
        ("焦虑", "emotion"),
        ("线上", "online-safety"),
        ("规划", "planning"),
        ("成长", "growth"),
        ("沟通", "communication"),
    ):
        if keyword in title:
            tags.append(tag)
    return list(dict.fromkeys(tags))


def detected_domains(question: str) -> tuple[str, ...]:
    normalized = question.lower()
    domains = tuple(
        domain
        for domain, keywords in DOMAIN_KEYWORDS.items()
        if any(keyword in normalized for keyword in keywords)
    )
    return domains or (ROUTING_CONTRACT["fallback_domain"],)


def route_expectation(question: str) -> dict[str, Any]:
    domains = detected_domains(question)
    minimum_domains = ROUTING_CONTRACT["minimum_domains"]
    max_agents = ROUTING_CONTRACT["max_agents"]
    multi_agent = len(domains) >= minimum_domains
    selected = tuple(
        domain for domain in DOMAIN_SELECTION_ORDER if domain in domains
    )[:max_agents] if multi_agent else ()
    return {
        "execution_mode": (
            "ADAPTIVE_MULTI_AGENT" if multi_agent else "SINGLE_AGENT"
        ),
        "detected_domains": list(domains),
        "selected_domains": list(selected),
        "minimum_domains": minimum_domains,
        "max_agents": max_agents,
        "router_contract": ROUTER_CONTRACT_VERSION,
    }


def build_question(
    unit: KnowledgeUnit,
    scenario: str,
    request_text: str,
    variant: int,
) -> str:
    topic = unit.title.rstrip("？?")
    openings = (
        f"关于“{topic}”，{scenario}。",
        f"{scenario}，现在最困扰我的是：{topic}。",
        f"我想改善“{topic}”这个问题；{scenario}。",
    )
    boundaries = (
        "只使用有依据的建议，不要编造课程或案例。",
        "信息不足时请标明假设，不要反复追问。",
        "建议要尊重双方意愿，不把责任全部推给一方。",
        "请区分知识库依据与一般性建议。",
    )
    return (
        f"{openings[variant % len(openings)]}"
        f"{request_text}{boundaries[(variant // len(openings)) % len(boundaries)]}"
    )


def build_composite_question(
    blueprint: CompositeBlueprint,
    scenario: str,
    request_text: str,
    variant: int,
) -> str:
    openings = (
        f"{blueprint.situation}；{scenario}。",
        f"{scenario}，目前{blueprint.situation}。",
        f"我们想一起解决这个复合问题：{blueprint.situation}；{scenario}。",
    )
    boundaries = (
        "请整合各方面，不要把责任全部推给一方。",
        "不要追问；信息不足时请标明合理假设。",
        "只给有依据、低成本且可持续的建议。",
        "请明确各项安排如何协调，并约定复盘。",
    )
    return (
        f"{openings[variant % len(openings)]}"
        f"{request_text}{boundaries[(variant // len(openings)) % len(boundaries)]}"
    )


def resolve_composite_units(
    units: list[KnowledgeUnit],
    blueprint: CompositeBlueprint,
) -> tuple[KnowledgeUnit, ...]:
    resolved: list[KnowledgeUnit] = []
    for fragment in blueprint.source_title_fragments:
        matches = [unit for unit in units if fragment in unit.title]
        if len(matches) != 1:
            raise ValueError(
                f"Composite blueprint {blueprint.name!r} expected one source "
                f"matching {fragment!r}, found {len(matches)}"
            )
        resolved.append(matches[0])
    if len({unit.source_sha256 for unit in resolved}) != len(resolved):
        raise ValueError(
            f"Composite blueprint {blueprint.name!r} repeats a source fingerprint"
        )
    return tuple(resolved)


def closest_benchmark(
    question: str,
    benchmark: Iterable[dict[str, str]],
) -> tuple[str, float, bool]:
    normalized = normalize_question(question)
    closest_id = ""
    closest_score = 0.0
    containment = False
    for case in benchmark:
        benchmark_normalized = normalize_question(case["question"])
        score = question_similarity(question, case["question"])
        contains = (
            len(normalized) >= 12
            and len(benchmark_normalized) >= 12
            and (
                normalized in benchmark_normalized
                or benchmark_normalized in normalized
            )
        )
        if score > closest_score:
            closest_id = case["id"]
            closest_score = score
        containment = containment or contains
    return closest_id, closest_score, containment


def verification_contract(
    question: str,
    request_name: str,
    required_concepts: Iterable[str],
) -> dict[str, Any]:
    request_required: dict[str, list[str]] = {
        "actions": ["三项|3项|三个|3个", "今天|立即", "检查|复盘"],
        "weekly_plan": ["周一|星期一", "周日|星期日", "复盘|回顾"],
        "checklist": ["优先级|排序", "假设"],
        "dialogue": ["15分钟|十五分钟", "话术|可以这样说"],
        "decision": ["共同|双方", "分歧|不同意见", "复盘|下次"],
    }
    concepts = list(required_concepts)
    concepts.extend(request_required[request_name])
    no_follow_up = "不要" in question and ("追问" in question or "反复" in question)
    minimum_action_items = 7 if request_name == "weekly_plan" else 3
    minimum_answer_chars = 320 if request_name == "weekly_plan" else 140
    return {
        "required_concepts": list(dict.fromkeys(concepts)),
        "forbidden_phrases": ["请你详细描述", "接下来，请你", "推荐课程"],
        "minimum_answer_chars": minimum_answer_chars,
        "maximum_answer_chars": (
            2400 if request_name == "weekly_plan" else 1600
        ),
        "citation_required": True,
        "no_follow_up": no_follow_up,
        "must_mark_assumptions": "假设" in question,
        "minimum_action_items": minimum_action_items,
    }


def make_seed(
    unit: KnowledgeUnit,
    question: str,
    request_name: str,
    benchmark_id: str,
    similarity: float,
) -> dict[str, Any]:
    seed_fingerprint = sha256_text(
        canonical_json(
            {
                "question": question,
                "source_sha256": unit.source_sha256,
                "schema_version": SCHEMA_VERSION,
            }
        )
    )
    contract = verification_contract(
        question,
        request_name,
        concepts_for(unit.title),
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "dataset_role": DATASET_ROLE,
        "seed_id": f"seed-{seed_fingerprint[:20]}",
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {
            "solution": unit.answer,
            "reward_schema_version": "human-light-rlvr-v3",
            "verification_contract": contract,
            "source_provenance": [
                {
                    "path": unit.source_path,
                    "section": unit.title,
                    "content_sha256": unit.source_sha256,
                }
            ],
            "task_group": f"{unit.category}:{request_name}",
            "tags": tags_for(unit, request_name),
            "route_expectation": route_expectation(question),
            "benchmark_guard": {
                "benchmark_case_id": benchmark_id,
                "maximum_similarity": round(similarity, 6),
                "overlap": False,
            },
        },
    }


def make_composite_seed(
    blueprint: CompositeBlueprint,
    units: tuple[KnowledgeUnit, ...],
    question: str,
    request_name: str,
    benchmark_id: str,
    similarity: float,
) -> dict[str, Any]:
    expectation = route_expectation(question)
    if expectation["execution_mode"] != "ADAPTIVE_MULTI_AGENT":
        raise ValueError(
            f"Composite blueprint {blueprint.name!r} did not trigger multi-Agent"
        )
    if not set(blueprint.expected_domains).issubset(
        expectation["detected_domains"]
    ):
        raise ValueError(
            f"Composite blueprint {blueprint.name!r} requires domains "
            f"{blueprint.expected_domains}, detected "
            f"{tuple(expectation['detected_domains'])}"
        )
    source_fingerprints = [unit.source_sha256 for unit in units]
    seed_fingerprint = sha256_text(
        canonical_json(
            {
                "question": question,
                "source_sha256": source_fingerprints,
                "schema_version": SCHEMA_VERSION,
            }
        )
    )
    required_concepts = [
        DOMAIN_CONCEPTS[domain] for domain in blueprint.expected_domains
    ]
    solution = "\n\n".join(
        f"知识章节“{unit.title}”：{unit.answer}" for unit in units
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "dataset_role": DATASET_ROLE,
        "seed_id": f"seed-{seed_fingerprint[:20]}",
        "messages": [{"role": "user", "content": question}],
        "rollout_extra": {
            "solution": solution,
            "reward_schema_version": "human-light-rlvr-v3",
            "verification_contract": verification_contract(
                question,
                request_name,
                required_concepts,
            ),
            "source_provenance": [
                {
                    "path": unit.source_path,
                    "section": unit.title,
                    "content_sha256": unit.source_sha256,
                }
                for unit in units
            ],
            "task_group": f"composite:{blueprint.name}:{request_name}",
            "tags": [
                "composite",
                "multi-agent",
                request_name,
                "knowledge-grounded",
                *[domain.lower() for domain in blueprint.expected_domains],
            ],
            "route_expectation": expectation,
            "benchmark_guard": {
                "benchmark_case_id": benchmark_id,
                "maximum_similarity": round(similarity, 6),
                "overlap": False,
            },
        },
    }


def generate_seeds(
    units: list[KnowledgeUnit],
    benchmark: list[dict[str, str]],
    target: int,
    similarity_threshold: float,
    multi_agent_ratio: float = DEFAULT_MULTI_AGENT_RATIO,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if target < 1:
        raise ValueError("target must be a positive integer")
    if not 0.0 < similarity_threshold < 1.0:
        raise ValueError("similarity-threshold must be between 0 and 1")
    if not 0.0 <= multi_agent_ratio <= 1.0:
        raise ValueError("multi-agent-ratio must be between 0 and 1")
    multi_target = int(target * multi_agent_ratio)
    single_target = target - multi_target
    if target > 1 and multi_agent_ratio > 0 and multi_target == 0:
        multi_target = 1
        single_target -= 1
    if target > 1 and multi_agent_ratio < 1 and single_target == 0:
        single_target = 1
        multi_target -= 1

    single_seeds: list[dict[str, Any]] = []
    multi_seeds: list[dict[str, Any]] = []
    seen: set[str] = set()
    rejected_exact = 0
    rejected_near = 0
    candidate_index = 0
    maximum_single_candidates = len(units) * len(SCENARIOS) * len(REQUESTS) * 3
    if single_target:
        for scenario in SCENARIOS:
            for opening_variant in range(3):
                for unit in units:
                    for request_name, request_text in REQUESTS:
                        variant = candidate_index + opening_variant
                        question = build_question(
                            unit,
                            scenario,
                            request_text,
                            variant,
                        )
                        candidate_index += 1
                        normalized = normalize_question(question)
                        if normalized in seen:
                            continue
                        benchmark_id, similarity, containment = closest_benchmark(
                            question,
                            benchmark,
                        )
                        if similarity >= 1.0 or containment:
                            rejected_exact += 1
                            continue
                        if similarity >= similarity_threshold:
                            rejected_near += 1
                            continue
                        expectation = route_expectation(question)
                        if expectation["execution_mode"] != "SINGLE_AGENT":
                            continue
                        seen.add(normalized)
                        single_seeds.append(
                            make_seed(
                                unit,
                                question,
                                request_name,
                                benchmark_id,
                                similarity,
                            )
                        )
                        if len(single_seeds) == single_target:
                            break
                    if len(single_seeds) == single_target:
                        break
                if len(single_seeds) == single_target:
                    break
            if len(single_seeds) == single_target:
                break

    resolved_blueprints: dict[str, tuple[KnowledgeUnit, ...]] = {}
    maximum_multi_candidates = (
        len(COMPOSITE_BLUEPRINTS) * len(SCENARIOS) * len(REQUESTS) * 3
    )
    if multi_target:
        resolved_blueprints = {
            blueprint.name: resolve_composite_units(units, blueprint)
            for blueprint in COMPOSITE_BLUEPRINTS
        }
        for scenario in SCENARIOS:
            for opening_variant in range(3):
                for blueprint in COMPOSITE_BLUEPRINTS:
                    for request_name, request_text in REQUESTS:
                        variant = candidate_index + opening_variant
                        question = build_composite_question(
                            blueprint,
                            scenario,
                            request_text,
                            variant,
                        )
                        candidate_index += 1
                        normalized = normalize_question(question)
                        if normalized in seen:
                            continue
                        benchmark_id, similarity, containment = closest_benchmark(
                            question,
                            benchmark,
                        )
                        if similarity >= 1.0 or containment:
                            rejected_exact += 1
                            continue
                        if similarity >= similarity_threshold:
                            rejected_near += 1
                            continue
                        seed = make_composite_seed(
                            blueprint,
                            resolved_blueprints[blueprint.name],
                            question,
                            request_name,
                            benchmark_id,
                            similarity,
                        )
                        seen.add(normalized)
                        multi_seeds.append(seed)
                        if len(multi_seeds) == multi_target:
                            break
                    if len(multi_seeds) == multi_target:
                        break
                if len(multi_seeds) == multi_target:
                    break
            if len(multi_seeds) == multi_target:
                break

    if len(single_seeds) < single_target or len(multi_seeds) < multi_target:
        raise ValueError(
            f"Only generated {len(single_seeds)} single and "
            f"{len(multi_seeds)} multi clean seeds from "
            f"{maximum_single_candidates + maximum_multi_candidates} "
            f"candidates; requested {single_target} single and "
            f"{multi_target} multi"
        )

    seeds: list[dict[str, Any]] = []
    for index in range(max(len(single_seeds), len(multi_seeds))):
        if index < len(single_seeds):
            seeds.append(single_seeds[index])
        if index < len(multi_seeds):
            seeds.append(multi_seeds[index])

    def route_mode(seed: dict[str, Any]) -> str:
        return seed["rollout_extra"]["route_expectation"]["execution_mode"]

    def source_count(seed: dict[str, Any]) -> int:
        return len(seed["rollout_extra"]["source_provenance"])

    manifest_payload = {
        "schema_version": SCHEMA_VERSION,
        "dataset_role": DATASET_ROLE,
        "sample_count": len(seeds),
        "unique_question_count": len(seen),
        "knowledge_section_count": len(units),
        "benchmark_case_count": len(benchmark),
        "similarity_threshold": similarity_threshold,
        "rejected_exact_or_containment": rejected_exact,
        "rejected_near_duplicate": rejected_near,
        "source_fingerprint": sha256_text(
            canonical_json(
                [
                    {
                        "path": unit.source_path,
                        "section": unit.title,
                        "sha256": unit.source_sha256,
                    }
                    for unit in units
                ]
            )
        ),
        "benchmark_fingerprint": sha256_text(canonical_json(benchmark)),
        "dataset_fingerprint": sha256_text(canonical_json(seeds)),
        "samples_per_source_section": {
            unit.title: sum(
                1
                for seed in seeds
                if any(
                    provenance["section"] == unit.title
                    for provenance in seed["rollout_extra"]["source_provenance"]
                )
            )
            for unit in units
        },
        "samples_per_request_type": {
            request_name: sum(
                1
                for seed in seeds
                if seed["rollout_extra"]["task_group"].endswith(
                    f":{request_name}"
                )
            )
            for request_name, _ in REQUESTS
        },
        "samples_per_expected_execution_mode": {
            mode: sum(1 for seed in seeds if route_mode(seed) == mode)
            for mode in ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")
        },
        "first_50_expected_execution_modes": {
            mode: sum(1 for seed in seeds[:50] if route_mode(seed) == mode)
            for mode in ("SINGLE_AGENT", "ADAPTIVE_MULTI_AGENT")
        },
        "samples_per_detected_domain": {
            domain: sum(
                1
                for seed in seeds
                if domain
                in seed["rollout_extra"]["route_expectation"]["detected_domains"]
            )
            for domain in DOMAIN_KEYWORDS
        },
        "samples_per_source_count": {
            str(count): sum(1 for seed in seeds if source_count(seed) == count)
            for count in sorted({source_count(seed) for seed in seeds})
        },
        "composite_blueprint_count": len(COMPOSITE_BLUEPRINTS),
        "requested_multi_agent_ratio": multi_agent_ratio,
        "actual_multi_agent_ratio": round(len(multi_seeds) / len(seeds), 6),
        "router_contract": ROUTER_CONTRACT_VERSION,
        "model_calls": 0,
        "billable_operations": 0,
        "submission_allowed": False,
    }
    manifest = {
        **manifest_payload,
        "manifest_fingerprint": sha256_text(canonical_json(manifest_payload)),
    }
    return seeds, manifest


def write_jsonl(path: Path, values: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as writer:
        for value in values:
            writer.write(canonical_json(value) + "\n")
    temporary.replace(path)


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    try:
        units = load_knowledge_units(args.document_dir)
        benchmark = load_benchmark(args.benchmark)
        seeds, manifest = generate_seeds(
            units,
            benchmark,
            args.target,
            args.similarity_threshold,
            args.multi_agent_ratio,
        )
        write_jsonl(args.output, seeds)
        write_json(args.manifest, manifest)
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
        print(
            "Generated trajectory seeds only; no model or cloud job was called."
        )
        return 0
    except (OSError, ValueError) as exception:
        print(f"Seed generation failed: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

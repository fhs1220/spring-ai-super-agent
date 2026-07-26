#!/bin/bash
# 面试级 v1.0 的无模型费用验收：代码、固定基准、后端测试与前端生产构建。
set -u
cd "$(dirname "$0")"

note() { echo "$1"; }

case_count=$(grep -c '"id":' src/main/resources/evaluation/love-rag-ab.jsonl)
note "[1/4] 固定基准样本: $case_count"
if [ "$case_count" -lt 30 ]; then
  note "RESULT: BENCHMARK_TOO_SMALL"
  exit 1
fi

note "[2/4] 检查代码差异..."
git diff --check || exit 1

MVN_ARGS=()
if [ -f /private/tmp/super-agent-maven-settings.xml ]; then
  export MAVEN_USER_HOME=/private/tmp/super-agent-m2
  MVN_ARGS+=(-o -s /private/tmp/super-agent-maven-settings.xml)
fi

note "[3/4] 运行 Java 21 可复现测试（真实模型/网络集成测试默认隔离）..."
sh mvnw "${MVN_ARGS[@]}" test || exit 1

note "[4/4] 构建 Vue 3 前端..."
(cd Cortex-ai-agent-frontend && npm run build) || exit 1

note "RESULT: INTERVIEW_V1_ENGINEERING_GATES_PASSED"

#!/bin/bash
# 路由策略发布控制面验证脚本(测试 + 启动 + HTTP 检查),结果写入 verify-results.log
set -u
cd "$(dirname "$0")"
LOG="verify-results.log"
: > "$LOG"

note() { echo "$1" | tee -a "$LOG"; }

MVN_ARGS=()
if [ -f /private/tmp/super-agent-maven-settings.xml ]; then
  export MAVEN_USER_HOME=/private/tmp/super-agent-m2
  MVN_ARGS+=(-s /private/tmp/super-agent-maven-settings.xml)
  note "[env] 使用 codex 会话的隔离 Maven 仓库"
else
  note "[env] 使用默认 ~/.m2"
fi

note "[1/3] 运行专项测试..."
sh mvnw "${MVN_ARGS[@]}" \
  -Dtest=TrajectoryAwareRoutingPolicyTest,RoutingPolicyDeploymentServiceTest,FileRoutingPolicyDeploymentRepositoryTest,AdaptiveMultiAgentOrchestratorTest,AgenticRagServiceTest \
  test >> "$LOG" 2>&1
TEST_EXIT=$?
note "[1/3] 测试退出码: $TEST_EXIT"
if [ $TEST_EXIT -ne 0 ]; then
  note "RESULT: TESTS_FAILED"
  exit 1
fi

note "[2/3] 启动后端(约需 1-2 分钟)..."
AGENT_EVALUATION_API_ENABLED=true SPRING_AI_MCP_CLIENT_ENABLED=false \
  sh mvnw -o "${MVN_ARGS[@]}" \
  org.springframework.boot:spring-boot-maven-plugin:3.3.5:run \
  > boot-verify.log 2>&1 &
MVN_PID=$!

UP=0
for i in $(seq 1 90); do
  sleep 2
  if curl -sf -o /dev/null http://127.0.0.1:8123/api/ai/love_app/agents/routing-policy; then
    UP=1; break
  fi
  if ! kill -0 $MVN_PID 2>/dev/null; then break; fi
done

if [ $UP -ne 1 ]; then
  note "RESULT: BOOT_FAILED (见 boot-verify.log 末尾)"
  tail -30 boot-verify.log >> "$LOG"
  kill $MVN_PID 2>/dev/null
  exit 1
fi

note "[3/3] HTTP 验证..."
STATUS_BODY=$(curl -sf http://127.0.0.1:8123/api/ai/love_app/agents/routing-policy)
echo "status-body: $STATUS_BODY" >> "$LOG"
MGMT_CODE=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8123/api/agent-routing-policy/deployments)
note "management-api http code (期望 404): $MGMT_CODE"

echo "$STATUS_BODY" | grep -q '"mode":"SHADOW"' && SHADOW_OK=1 || SHADOW_OK=0
note "默认 SHADOW: $([ $SHADOW_OK -eq 1 ] && echo OK || echo FAIL)"

kill $MVN_PID 2>/dev/null
sleep 3
lsof -ti :8123 | xargs kill -9 2>/dev/null

if [ $SHADOW_OK -eq 1 ] && [ "$MGMT_CODE" = "404" ]; then
  note "RESULT: ALL_PASSED"
  exit 0
fi
note "RESULT: HTTP_CHECK_FAILED"
exit 1

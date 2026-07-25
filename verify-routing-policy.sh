#!/bin/bash
# 路由策略发布控制面验证脚本(测试 + 启动 + HTTP 检查),结果写入 verify-results.log
set -u
cd "$(dirname "$0")"
LOG="verify-results.log"
: > "$LOG"
VERIFY_HOST="127.0.0.1"
VERIFY_PORT="${VERIFY_ROUTING_POLICY_PORT:-18123}"
BASE_URL="http://${VERIFY_HOST}:${VERIFY_PORT}/api"
MVN_PID=""

note() { echo "$1" | tee -a "$LOG"; }

cleanup() {
  if [ -n "$MVN_PID" ] && kill -0 "$MVN_PID" 2>/dev/null; then
    kill "$MVN_PID" 2>/dev/null
    for _ in $(seq 1 25); do
      kill -0 "$MVN_PID" 2>/dev/null || break
      sleep 0.2
    done
    if kill -0 "$MVN_PID" 2>/dev/null; then
      # 只强制结束本脚本创建的 Maven/应用进程，不按端口误杀其他服务。
      kill -9 "$MVN_PID" 2>/dev/null
    fi
    wait "$MVN_PID" 2>/dev/null
  fi
}
trap cleanup EXIT INT TERM

case "$VERIFY_PORT" in
  ''|*[!0-9]*)
    note "RESULT: INVALID_PORT ($VERIFY_PORT)"
    exit 1
    ;;
esac

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
  -Dtest=TrajectoryAwareRoutingPolicyTest,RoutingPolicyDeploymentServiceTest,RoutingPolicyQualityGuardTest,RoutingPolicyRegistryServiceTest,FileRoutingPolicyRegistryRepositoryTest,FileRoutingPolicyDeploymentRepositoryTest,AdaptiveMultiAgentOrchestratorTest,AgenticRagServiceTest \
  test >> "$LOG" 2>&1
TEST_EXIT=$?
note "[1/3] 测试退出码: $TEST_EXIT"
if [ $TEST_EXIT -ne 0 ]; then
  note "RESULT: TESTS_FAILED"
  exit 1
fi

note "[2/3] 启动后端(约需 1-2 分钟)..."
if lsof -nP -iTCP:"$VERIFY_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  note "RESULT: PORT_IN_USE ($VERIFY_PORT)，请设置 VERIFY_ROUTING_POLICY_PORT"
  exit 1
fi

SERVER_PORT="$VERIFY_PORT" \
  AGENT_EVALUATION_API_ENABLED=true SPRING_AI_MCP_CLIENT_ENABLED=false \
  sh mvnw -o "${MVN_ARGS[@]}" \
  -Dspring-boot.run.fork=false \
  org.springframework.boot:spring-boot-maven-plugin:3.3.5:run \
  > boot-verify.log 2>&1 &
MVN_PID=$!

UP=0
for i in $(seq 1 90); do
  sleep 2
  if curl -sf -o /dev/null "$BASE_URL/ai/love_app/agents/routing-policy"; then
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
STATUS_BODY=$(curl -sf "$BASE_URL/ai/love_app/agents/routing-policy")
echo "status-body: $STATUS_BODY" >> "$LOG"
GUARD_BODY=$(curl -sf "$BASE_URL/ai/love_app/agents/routing-policy/quality-guard")
echo "guard-body: $GUARD_BODY" >> "$LOG"
REGISTRY_BODY=$(curl -sf "$BASE_URL/ai/love_app/agents/routing-policy/registry")
echo "registry-body: $REGISTRY_BODY" >> "$LOG"
MGMT_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  "$BASE_URL/agent-routing-policy/deployments")
note "management-api http code (期望 404): $MGMT_CODE"

echo "$STATUS_BODY" | grep -q '"mode":"SHADOW"' && SHADOW_OK=1 || SHADOW_OK=0
note "默认 SHADOW: $([ $SHADOW_OK -eq 1 ] && echo OK || echo FAIL)"
echo "$GUARD_BODY" | grep -q '"state":"INACTIVE"' && GUARD_OK=1 || GUARD_OK=0
note "质量守卫待命: $([ $GUARD_OK -eq 1 ] && echo OK || echo FAIL)"
echo "$REGISTRY_BODY" | grep -q '"version":"routing-policy-baseline-v1"' \
  && REGISTRY_OK=1 || REGISTRY_OK=0
note "策略注册表基线: $([ $REGISTRY_OK -eq 1 ] && echo OK || echo FAIL)"

if [ $SHADOW_OK -eq 1 ] && [ $GUARD_OK -eq 1 ] \
  && [ $REGISTRY_OK -eq 1 ] && [ "$MGMT_CODE" = "404" ]; then
  note "RESULT: ALL_PASSED"
  exit 0
fi
note "RESULT: HTTP_CHECK_FAILED"
exit 1

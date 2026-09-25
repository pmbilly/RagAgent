#!/usr/bin/env bash
# 一键验收：五批全量测试 + 冒烟 A/B（可选）。
# 用法：
#   scripts/acceptance.sh            # 只跑五批测试
#   scripts/acceptance.sh --with-ab  # 额外起 Go/Java 双端做冒烟对拍（需原仓在位）
# 说明：
#   - 五批划分 = B1 纪律（agent/chatpipeline 与 Spring 包分批，避免 Mockito attach 假红）
#   - 每批失败即停并指明批次；全绿输出 ACCEPTANCE PASS
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

if [ -d /opt/homebrew/opt/openjdk@21/bin ]; then
  export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
fi

run_batch() {
  local name="$1"; shift
  echo "── 批次 ${name} ──"
  if ./gradlew :server:test "$@" > /tmp/acceptance-${name}.log 2>&1; then
    echo "   PASS"
  else
    echo "   FAIL（日志：/tmp/acceptance-${name}.log）"
    exit 1
  fi
}

run_batch B1a --tests "com.ragagent.agent.*" --tests "com.ragagent.chatpipeline.*"
run_batch B1b --tests "com.ragagent.common.*" --tests "com.ragagent.event.*" \
  --tests "com.ragagent.apikey.*" --tests "com.ragagent.audit.*" --tests "com.ragagent.auth.*"
run_batch B2  --tests "com.ragagent.browserskill.*" --tests "com.ragagent.datasource.*" \
  --tests "com.ragagent.embed.*" --tests "com.ragagent.embedding.*" \
  --tests "com.ragagent.evaluation.*" --tests "com.ragagent.favorite.*"
run_batch B3  --tests "com.ragagent.im.*" --tests "com.ragagent.knowledge.*" \
  --tests "com.ragagent.llm.*" --tests "com.ragagent.mcp.*" --tests "com.ragagent.memory.*" \
  --tests "com.ragagent.model.*" --tests "com.ragagent.modelcontext.*" --tests "com.ragagent.org.*"
run_batch B4  --tests "com.ragagent.rerank.*" --tests "com.ragagent.sandbox.*" \
  --tests "com.ragagent.searchutil.*" --tests "com.ragagent.session.*" \
  --tests "com.ragagent.storage.*" --tests "com.ragagent.storageurl.*" \
  --tests "com.ragagent.stream.*" --tests "com.ragagent.system.*" \
  --tests "com.ragagent.tracing.*" --tests "com.ragagent.vectorstore.*" \
  --tests "com.ragagent.webfetch.*" --tests "com.ragagent.websearch.*" \
  --tests "com.ragagent.wiki.*" --tests "com.ragagent.agentm.*" \
  --tests "com.ragagent.retrieval.*" --tests "com.ragagent.config.*"

echo ""
echo "ACCEPTANCE PASS：五批全量测试全绿"

if [ "${1:-}" = "--with-ab" ]; then
  echo "── 冒烟 A/B（需原仓在位）──"
  bash "${SCRIPT_DIR}/go-server-up.sh" || { echo "Go 起服失败"; exit 1; }
  ./scripts/java-server-up.sh || { echo "Java 起服失败"; exit 1; }
  TOKEN_GO=$(scripts/token.sh 8080)
  TOKEN_JAVA=$(scripts/token.sh 8082)
  fail=0
  for ep in "agents" "sessions?page=1&page_size=5" "knowledge_bases" "im-channels" \
            "storage-backends" "web-search-providers" "vector-stores" "sandbox-configs" \
            "tenants/all"; do
    curl -s -H "Authorization: Bearer ${TOKEN_GO}" "http://localhost:8080/api/v1/${ep}" > /tmp/ab-g.json
    curl -s -H "Authorization: Bearer ${TOKEN_JAVA}" "http://localhost:8082/api/v1/${ep}" > /tmp/ab-j.json
    if cmp -s /tmp/ab-g.json /tmp/ab-j.json; then
      echo "   MATCH  ${ep}"
    else
      echo "   DIFF   ${ep}"; fail=1
    fi
  done
  [ "${fail}" = "0" ] && echo "AB PASS：九族逐字节一致" || { echo "AB FAIL"; exit 1; }
fi

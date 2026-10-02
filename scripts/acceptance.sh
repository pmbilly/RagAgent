#!/usr/bin/env bash
# 一键验收：五批测试 + 冒烟 A/B（可选）。
#
# 用法：
#   scripts/acceptance.sh                          # 全量五批（交付门：批次交付/里程碑）
#   scripts/acceptance.sh --with-ab                # 全量五批 + Go/Java 冒烟对拍（需原仓在位）
#   scripts/acceptance.sh --changed                # 只跑受影响批（日常提交门，基线 HEAD）
#   scripts/acceptance.sh --changed --dry-run      # 只打印映射计划，不跑测试
#   scripts/acceptance.sh --changed --base <ref>   # 以 <ref> 为基线（如 origin/main）
#   scripts/acceptance.sh --changed --no-escalate  # 命中共享面也不升格（自证局部改动时用）
#
# 说明：
#   - 五批划分 = B1 纪律（agent/chatpipeline 与 Spring 包分批，避免 Mockito attach 假红）
#   - 每批失败即停并指明批次；全绿输出 ACCEPTANCE PASS
#   - --changed 映射：改动文件 → 领域包 → 批次；命中「共享面」自动升格为全量五批：
#     build/gradle 文件、gradle.properties、settings*、server/src 下的根包与资源、
#     migrations/、以及未知新域（映射不认识的包一律保守处理）
#   - 为什么不是每次全量：五个 --tests 过滤器互为 task 输入 → 批次间不共享缓存，
#     全量一次约 3.5 分钟。日常提交用 --changed（秒~分钟级），交付门再全量。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

if [ -d /opt/homebrew/opt/openjdk@21/bin ]; then
  export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
fi

MODE=full
BASE=HEAD
ESCALATE=1
DRY_RUN=0
WITH_AB=0

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --changed) MODE=changed; shift ;;
    --base) BASE="${2:-}"; [ -n "$BASE" ] || { echo "--base 需要 <ref>"; exit 2; }; shift 2 ;;
    --no-escalate) ESCALATE=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --with-ab) WITH_AB=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "未知参数：$1"; echo; usage; exit 2 ;;
  esac
done

# ── 批次表（单一事实源：批次 → 领域包；run 与 --changed 映射都从这里生成）──
BATCH_NAMES="B1a B1b B2 B3 B4"

batch_domains() {
  case "$1" in
    B1a) echo "agent chatpipeline" ;;
    B1b) echo "common event apikey audit auth" ;;
    B2) echo "browserskill datasource embed embedding evaluation favorite" ;;
    B3) echo "im knowledge llm mcp memory model" ;;
    B4) echo "rerank session storage stream system tracing vectorstore websearch wiki retrieval config" ;;
  esac
}

known_domain() {
  local d="$1" b
  for b in $BATCH_NAMES; do
    case " $(batch_domains "$b") " in *" $d "*) return 0 ;; esac
  done
  return 1
}

batches_for_domains() {
  local selected=" $* " b d hit out=""
  for b in $BATCH_NAMES; do
    hit=0
    for d in $(batch_domains "$b"); do
      case "$selected" in *" $d "*) hit=1 ;; esac
    done
    [ "$hit" = 1 ] && out="$out $b"
  done
  echo "$out"
}

uniq_words() {
  printf '%s\n' $1 | sort -u | tr '\n' ' ' | sed 's/ *$//'
}

collect_changed() {
  { git diff --name-only "$BASE" 2>/dev/null; git ls-files --others --exclude-standard; } | sort -u
}

# ── 选批 ──
T0=$(date +%s)
BATCHES_TO_RUN="$BATCH_NAMES"

if [ "$MODE" = changed ]; then
  SEL_DOMAINS=""
  SHARED=""
  IGNORED=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      */src/main/java/com/ragagent/* | */src/test/java/com/ragagent/*)
        dom="$(printf '%s' "$f" | sed -n 's#.*/java/com/ragagent/\([^/]*\)/.*#\1#p')"
        if [ -n "$dom" ] && known_domain "$dom"; then
          SEL_DOMAINS="$SEL_DOMAINS $dom"
        else
          SHARED="$SHARED $f" # 根包文件或未知新域 → 保守升格
        fi
        ;;
      server/src/* | migrations/* | gradle/* | *.gradle.kts | gradle.properties | settings*)
        SHARED="$SHARED $f"
        ;;
      *)
        IGNORED=$((IGNORED + 1))
        ;;
    esac
  done <<< "$(collect_changed)"

  SEL_DOMAINS="$(uniq_words "$SEL_DOMAINS")"

  if [ -z "$SEL_DOMAINS" ] && [ -z "$SHARED" ]; then
    echo "── --changed（基线 ${BASE}）：无影响 :server:test 的改动（忽略 ${IGNORED} 个非源码文件）──"
    echo "SKIP：没有需要跑的批次"
    exit 0
  fi

  echo "── --changed（基线 ${BASE}）──"
  [ -n "$SEL_DOMAINS" ] && echo "   源码域：$(printf '%s' "$SEL_DOMAINS" | tr ' ' ',')"
  if [ -n "$SHARED" ]; then
    if [ "$ESCALATE" = 1 ]; then
      echo "   ⚠ 命中共享面 → 升格全量五批："
    else
      echo "   ⚠ 命中共享面（--no-escalate：不升格，已忽略）："
    fi
    for f in $SHARED; do echo "      $f"; done
    [ "$ESCALATE" = 1 ] && BATCHES_TO_RUN="$BATCH_NAMES"
  fi
  if [ "$ESCALATE" != 1 ] || [ -z "$SHARED" ]; then
    BATCHES_TO_RUN="$(batches_for_domains $SEL_DOMAINS)"
  fi
  if [ -z "$(uniq_words "$BATCHES_TO_RUN")" ]; then
    echo "SKIP：映射后无批次（唯一改动是共享面且给了 --no-escalate → 无受影响批）"
    exit 0
  fi
  for b in $BATCHES_TO_RUN; do
    doms=""
    for d in $(batch_domains "$b"); do
      case " $SEL_DOMAINS " in *" $d "*) doms="$doms $d" ;; esac
    done
    echo "   批次 ${b}$([ -n "$doms" ] && echo " ← 域：$(uniq_words "$doms" | tr ' ' ',')")"
  done
  [ "$IGNORED" != 0 ] && echo "   （忽略 ${IGNORED} 个非源码文件：docs/scripts/frontend 等）"
else
  echo "── 全量五批 ──"
fi

echo
echo "── 选中批次：$(uniq_words "$BATCHES_TO_RUN") ──"

if [ "$DRY_RUN" = 1 ]; then
  echo "(dry-run：未执行测试)"
  exit 0
fi

run_batch() {
  local name="$1" filters="" d t0 t1
  for d in $(batch_domains "$name"); do
    filters="$filters --tests com.ragagent.${d}.*"
  done
  t0=$(date +%s)
  # shellcheck disable=SC2086
  if ./gradlew :server:test $filters > "/tmp/acceptance-${name}.log" 2>&1; then
    t1=$(date +%s)
    echo "   PASS（$((t1 - t0))s）"
  else
    echo "   FAIL（日志：/tmp/acceptance-${name}.log）"
    exit 1
  fi
}

for b in $BATCHES_TO_RUN; do
  echo "── 批次 ${b} ──"
  run_batch "$b"
done

echo ""
if [ "$MODE" = full ]; then
  echo "ACCEPTANCE PASS：五批全量测试全绿（$(( $(date +%s) - T0 ))s）"
else
  echo "ACCEPTANCE PASS：受影响批（$(uniq_words "$BATCHES_TO_RUN")）全绿（$(( $(date +%s) - T0 ))s）"
  echo "  日常提交门。批次交付/里程碑请跑无参数全量（约 3.5 分钟）。"
fi

if [ "$WITH_AB" = 1 ]; then
  echo "── 冒烟 A/B（需原仓在位）──"
  bash "${SCRIPT_DIR}/go-server-up.sh" || { echo "Go 起服失败"; exit 1; }
  ./scripts/java-server-up.sh || { echo "Java 起服失败"; exit 1; }
  TOKEN_GO=$(scripts/token.sh 8080)
  TOKEN_JAVA=$(scripts/token.sh 8082)
  fail=0
  for ep in "agents" "sessions?page=1&page_size=5" "knowledge_bases" "im-channels" \
    "storage-backends" "web-search-providers" "vector-stores" \
    "tenants/all"; do
    curl -s -H "Authorization: Bearer ${TOKEN_GO}" "http://localhost:8080/api/v1/${ep}" > /tmp/ab-g.json
    curl -s -H "Authorization: Bearer ${TOKEN_JAVA}" "http://localhost:8082/api/v1/${ep}" > /tmp/ab-j.json
    if cmp -s /tmp/ab-g.json /tmp/ab-j.json; then
      echo "   MATCH  ${ep}"
    else
      echo "   DIFF   ${ep}"
      fail=1
    fi
  done
  [ "${fail}" = "0" ] && echo "AB PASS：九族逐字节一致" || { echo "AB FAIL"; exit 1; }
fi

#!/usr/bin/env bash
# E2E 两观察项：可复跑探针（2026-09-25，W5γ5.10）
#
#   观察项 1「早错 SSE 不收流」：上游在**流早期**报错时，SSE 是立刻收流给错误帧，还是挂住/只截断？
#
# 前置（本脚本不代起服务）：
#   1) python3 scripts/stub-llm-server.py 8181       # 标准 stub；需含 early-error/early-close 场景
#   2) 双端在跑（dev-env 的 GO_PORT/JAVA_PORT），且模型/stub 的 loopback 已过 SSRF 白名单
#      （`PUT /api/v1/system/admin/settings/ssrf.whitelist` = ["198.18.0.0/15","127.0.0.1"]；
#       ⚠️ 白名单只被"处理该写请求的进程"热加载——要两端都生效，须**两端各 PUT 一次**）
#   3) 导出 STUB_MODEL_ID=<指 http://127.0.0.1:8181/v1 的模型行 id>（默认取 dev 库的 stub-model）
#      可选 AGENT_ID（默认 shr-agent-ok 夹具）、SIDES（默认 "go java"）
#
# 产出（默认 /tmp/e2e-obs）：
#   base|early-error|early-close|agent-ask1|agent-ask2.<side>.sse   客户端完整帧序列
#   summary.txt                                                     http/耗时/是否收流/帧数
#   tools.<side>.txt                                                该端出站请求的 tools 名集（需 stub 录制）
#
# 关于"是否收流"：curl --max-time 到点仍在等 ⇒ rc=28（**未收流/挂住**）；正常收流 ⇒ rc=0。
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh" || exit 1

GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
OUT="${E2E_OBS_OUT:-/tmp/e2e-obs}"
SIDES="${SIDES:-go java}"
STUB_MODEL_ID="${STUB_MODEL_ID:-9ae55672-1526-4696-9420-028a85a63ba8}"
AGENT_ID="${AGENT_ID:-44444444-4444-4444-4444-444444444401}"
HANG_LIMIT="${HANG_LIMIT:-15}"
mkdir -p "${OUT}"
: > "${OUT}/summary.txt"

GTOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JTOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"

side_base() { [ "$1" = go ] && echo "${GO}" || echo "${JAVA}"; }
side_token() { [ "$1" = go ] && echo "${GTOKEN}" || echo "${JTOKEN}"; }

new_session() { # side → session id
  curl -s --max-time 10 -X POST "$(side_base "$1")/sessions" \
    -H "Authorization: Bearer $(side_token "$1")" -H 'Content-Type: application/json' \
    -d '{"title":"e2e-obs"}' \
    | python3 -c 'import json,sys;print((json.load(sys.stdin).get("data") or {}).get("id",""))' 2>/dev/null
}

run_chat() { # name side path body
  local name="$1" side="$2" path="$3" body="$4"
  local sid; sid="$(new_session "${side}")"
  [ -n "${sid}" ] || { echo "${name} ${side} 建会话失败" | tee -a "${OUT}/summary.txt"; return; }
  local t0 t1 code rc
  t0=$(date +%s.%N)
  code=$(curl -s --max-time "${HANG_LIMIT}" -N -o "${OUT}/${name}.${side}.sse" -w '%{http_code}' \
      -X POST "$(side_base "${side}")${path//@SID@/${sid}}" \
      -H "Authorization: Bearer $(side_token "${side}")" -H 'Content-Type: application/json' \
      -d "${body//@SIDE@/${side}}")
  rc=$?
  t1=$(date +%s.%N)
  printf '%-12s %-5s http=%s curlrc=%s 用时=%.2fs 帧=%s %s\n' \
    "${name}" "${side}" "${code}" "${rc}" "$(echo "${t1}-${t0}" | bc)" \
    "$(grep -c '^data:' "${OUT}/${name}.${side}.sse" 2>/dev/null || echo 0)" \
    "$([ "${rc}" = 28 ] && echo '（未收流：到点仍在等）' || echo '（已收流）')" \
    | tee -a "${OUT}/summary.txt"
}

echo "==> 观察项 1：早错 SSE（对照基线 + early-error + early-close）"
for side in ${SIDES}; do
  run_chat base        "${side}" "/knowledge-chat/@SID@" \
    "{\"query\":\"hello <<SCENARIO:chat>> [@SIDE@]\",\"summary_model_id\":\"${STUB_MODEL_ID}\",\"disable_title\":true}"
  run_chat early-error "${side}" "/knowledge-chat/@SID@" \
    "{\"query\":\"hello <<SCENARIO:early-error>> [@SIDE@]\",\"summary_model_id\":\"${STUB_MODEL_ID}\",\"disable_title\":true}"
  run_chat early-close "${side}" "/knowledge-chat/@SID@" \
    "{\"query\":\"hello <<SCENARIO:early-close>> [@SIDE@]\",\"summary_model_id\":\"${STUB_MODEL_ID}\",\"disable_title\":true}"
done

echo "==> 观察项 2：agent-chat 首次/再次直问（不经其它沙箱工具）"
for side in ${SIDES}; do
  run_chat agent-ask1 "${side}" "/agent-chat/@SID@" \
    "{\"query\":\"列出沙箱文件 [@SIDE@] <<SCENARIO:chat>>\",\"agent_enabled\":true,\"agent_id\":\"${AGENT_ID}\",\"summary_model_id\":\"${STUB_MODEL_ID}\"}"
  run_chat agent-ask2 "${side}" "/agent-chat/@SID@" \
    "{\"query\":\"再列一次沙箱文件 [@SIDE@] <<SCENARIO:chat>>\",\"agent_enabled\":true,\"agent_id\":\"${AGENT_ID}\",\"summary_model_id\":\"${STUB_MODEL_ID}\"}"
done

echo "==> 若 stub 开了录制（STUB_RECORD_DIR），按分侧标记提取各端出站 tools："
REC="${STUB_RECORD_DIR:-/tmp/w5obs-rec}"
for side in ${SIDES}; do
  python3 - "$REC" "$side" > "${OUT}/tools.${side}.txt" 2>/dev/null <<'PY'
import glob, json, os, sys
rec, side = sys.argv[1], sys.argv[2]
rows = []
for f in sorted(glob.glob(os.path.join(rec, "*.json"))):
    try:
        body = json.load(open(f, encoding="utf-8"))
    except Exception:
        continue
    msgs = body.get("messages") or []
    text = " ".join(str(m.get("content") or "") for m in msgs)
    if f"[{side}]" not in text:
        continue
    tools = sorted(t.get("function", {}).get("name", "?") for t in (body.get("tools") or []))
    rows.append((os.path.basename(f), len(tools), tools))
for name, n, tools in rows:
    print(f"{name} tools={n}")
    print("   " + (", ".join(tools) if tools else "(无 tools 段)"))
PY
  echo "--- ${side}（见 ${OUT}/tools.${side}.txt）---"
  head -6 "${OUT}/tools.${side}.txt"
done

echo "==> done：明细见 ${OUT}/summary.txt 与 ${OUT}/*.sse"

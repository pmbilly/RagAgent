#!/usr/bin/env bash
# 录波 3 sandbox 子批 3（/sandbox-configs/:id/skills* 12 条）的 golden：sbk-*。
# 场景设计见 git 历史版 record-sbx2-golden.sh；dev 无 provider，
# install 管线在首个 provider 调用处失败（分类文案）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
OUT="${SBX3_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${SBX3_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"
req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

echo "==> 幂等清理"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM tenant_user_env_vars WHERE tenant_id = 10002;
DELETE FROM tenant_skill_snapshots WHERE tenant_id = 10002;
DELETE FROM tenant_skills WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'

echo "==> 0) 前置：cube 配置 + ready 技能行（envs 带 JSON 声明）"
req sbk-setup-config.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"sbk-probe-cube","description":"skill probe","config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1","api_key":"sk-cube-secret-1"}}}'
CID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/sbk-setup-config.json"'"))["data"]["id"])')"
[ -n "${CID}" ] || { echo "FATAL: 未取到配置 id"; exit 1; }
SKILL_ID="22222222-3333-4444-5555-666666666601"
${PSQL} >/dev/null <<SQL
INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, version, description,
  instructions, bundle_ref, bundle_sha256, enabled, installed_snapshot_id,
  install_session_id, install_message_id, envs, status, created_at, updated_at)
VALUES ('${SKILL_ID}', 10002, '${CID}', 'probe-skill', '1.0.0', 'probe skill',
  'Do the probe thing.', '/bundles/probe-skill.zip',
  'deadbeefdeadbeefdeadbeefdeadbeef', true, 'snap-0001',
  'sess-0001', 'msg-0001',
  '[{"name":"PROBE_TOKEN","description":"token for probe","required":true}]',
  'ready', '2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00');
SQL

echo "==> 1) 只读路径"
req sbk-list.json GET "/sandbox-configs/${CID}/skills" -H "${AUTH}"
req sbk-get.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}" -H "${AUTH}"
req sbk-get-missing.json GET "/sandbox-configs/${CID}/skills/00000000-0000-0000-0000-000000000000" -H "${AUTH}"
req sbk-events-ready.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}/install-events" -H "${AUTH}"
req sbk-events-missing.json GET "/sandbox-configs/${CID}/skills/00000000-0000-0000-0000-000000000000/install-events" -H "${AUTH}"
req sbk-transcript.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}/transcript" -H "${AUTH}"
req sbk-guidance.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}/guidance" -H "${AUTH}"

echo "==> 2) 变更路径"
req sbk-patch.json PATCH "/sandbox-configs/${CID}/skills/${SKILL_ID}" -H "${AUTH}" -H "${CT}" \
  -d '{"description":"patched desc","enabled":false}'
req sbk-patch-restore.json PATCH "/sandbox-configs/${CID}/skills/${SKILL_ID}" -H "${AUTH}" -H "${CT}" \
  -d '{"enabled":true}'
req sbk-stop.json POST "/sandbox-configs/${CID}/skills/${SKILL_ID}/stop" -H "${AUTH}"
req sbk-delete-missing.json DELETE "/sandbox-configs/${CID}/skills/00000000-0000-0000-0000-000000000000" -H "${AUTH}"

echo "==> 3) 上传（本地 bundle 校验 + 首个 provider 调用失败）"
printf 'this is not a zip file at all, just some bytes\n' > /tmp/sbx3-not-a-zip.bin
python3 - <<'PY'
import zipfile
with zipfile.ZipFile("/tmp/sbx3-probe-skill.zip", "w") as z:
    z.writestr("SKILL.md", "---\nname: probe-upload\ndescription: probe upload skill\n---\n\nBody instructions.\n")
    z.writestr("scripts/run.sh", "echo hi\n")
PY
curl -s -o "${OUT}/sbk-upload-invalid.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/sandbox-configs/${CID}/skills" -H "${AUTH}" -F "file=@/tmp/sbx3-not-a-zip.bin"
curl -s -o "${OUT}/sbk-upload-zip.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/sandbox-configs/${CID}/skills" -H "${AUTH}" -F "file=@/tmp/sbx3-probe-skill.zip"
curl -s -o "${OUT}/sbk-upload-nobody.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/sandbox-configs/${CID}/skills" -H "${AUTH}"

echo "==> 4) files / reinstall（provider 失败分类）"
req sbk-files.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}/files" -H "${AUTH}"
req sbk-file-content.json GET "/sandbox-configs/${CID}/skills/${SKILL_ID}/files/content?path=/opt/weknora/tenant/skills/probe-skill/SKILL.md" -H "${AUTH}"
req sbk-reinstall.json POST "/sandbox-configs/${CID}/skills/${SKILL_ID}/reinstall" -H "${AUTH}"

echo "==> 清理"
${PSQL} >/dev/null <<'SQL'
DELETE FROM tenant_user_env_vars WHERE tenant_id = 10002;
DELETE FROM tenant_skill_snapshots WHERE tenant_id = 10002;
DELETE FROM tenant_skills WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL
echo "==> 完成"

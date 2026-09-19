#!/usr/bin/env bash
# 录波 3 sandbox 子批 4（/skills 家族 7 条 + /me/env-vars 5 条）golden：slk-* / mev-*。
# catalog 归档在 dev 走本地存储（local://），install 的首个 provider 调用失败分类。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
OUT="${SBX4_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${SBX4_TARGET_PORT:-${GO_PORT}}"
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
DELETE FROM tenant_skill_catalog WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'

echo "==> 0) 前置：合法 zip（SKILL.md + env 声明）+ cube 配置 + ready 技能行"
python3 - <<'PY'
import zipfile
with zipfile.ZipFile("/tmp/sbx4-catalog.zip", "w") as z:
    z.writestr("SKILL.md", "---\nname: cat-probe\ndescription: catalog probe skill\n---\n\nBody instructions.\n")
    z.writestr("scripts/run.sh", "echo hi\n")
PY
req slk-setup-config.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"slk-probe-cube","description":"catalog probe","config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1","api_key":"sk-cube-secret-1"}}}'
CID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/slk-setup-config.json"'"))["data"]["id"])')"
SKILL_ID="22222222-3333-4444-5555-666666666602"
${PSQL} >/dev/null <<SQL
INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, version, description,
  instructions, enabled, status, envs, created_at, updated_at)
VALUES ('${SKILL_ID}', 10002, '${CID}', 'mev-probe-skill', '1.0.0', 'mev probe',
  'Use PROBE_TOKEN.', true, 'ready',
  '[{"name":"PROBE_TOKEN","description":"probe token","required":true}]',
  '2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00');
SQL

echo "==> 1) /skills 只读 + catalog 空"
req slk-skills.json GET /skills -H "${AUTH}"
req slk-catalog-empty.json GET /skills/catalog -H "${AUTH}"
req slk-catalog-register-nobody.json POST /skills/catalog -H "${AUTH}"
req slk-catalog-register-invalid.json POST /skills/catalog -H "${AUTH}" -H "${CT}" \
  -d '{"source":""}'
req slk-catalog-register-src.json POST /skills/catalog -H "${AUTH}" -H "${CT}" \
  -d '{"source":"https://example.com/not-reachable.zip"}'

echo "==> 2) catalog 归档注册（本地存储）+ 列表/files"
curl -s -o "${OUT}/slk-catalog-register.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/skills/catalog" -H "${AUTH}" -F "file=@/tmp/sbx4-catalog.zip"
CATID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/slk-catalog-register.json"'"))["data"]["id"])' 2>/dev/null || true)"
echo "    catalog id = ${CATID}"
req slk-catalog-list.json GET /skills/catalog -H "${AUTH}"
req slk-catalog-files.json GET "/skills/catalog/${CATID}/files" -H "${AUTH}"
req slk-catalog-file.json GET "/skills/catalog/${CATID}/files/content?path=SKILL.md" -H "${AUTH}"
req slk-catalog-file-bad.json GET "/skills/catalog/${CATID}/files/content?path=../escape.md" -H "${AUTH}"

echo "==> 3) install（首个 provider 调用失败分类）"
req slk-catalog-install.json POST "/skills/catalog/${CATID}/install" -H "${AUTH}" -H "${CT}" \
  -d "{\"sandbox_config_ids\":[\"${CID}\"]}"
req slk-catalog-install-missing.json POST "/skills/catalog/${CATID}/install" -H "${AUTH}" -H "${CT}" \
  -d '{"sandbox_config_ids":["00000000-0000-0000-0000-000000000000"]}'

echo "==> 4) /me/env-vars"
req mev-list.json GET /me/env-vars -H "${AUTH}"
req mev-set-skill.json PUT /me/env-vars/skill -H "${AUTH}" -H "${CT}" \
  -d "{\"skill_id\":\"${SKILL_ID}\",\"name\":\"PROBE_TOKEN\",\"value\":\"tok-secret-9\"}"
req mev-list-after.json GET /me/env-vars -H "${AUTH}"
req mev-set-skill-badname.json PUT /me/env-vars/skill -H "${AUTH}" -H "${CT}" \
  -d "{\"skill_id\":\"${SKILL_ID}\",\"name\":\"NOT_DECLARED\",\"value\":\"x\"}"
printf '{"skill_id":"%s","name":"PROBE_TOKEN"}' "${SKILL_ID}" > /tmp/sbx4-del-skill.json
req mev-delete-skill.json DELETE /me/env-vars/skill -H "${AUTH}" -H "${CT}" -d @/tmp/sbx4-del-skill.json
req mev-set-sandbox.json PUT /me/env-vars/sandbox -H "${AUTH}" -H "${CT}" \
  -d "{\"sandbox_config_id\":\"${CID}\",\"name\":\"CFG_VAR\",\"value\":\"cfg-secret\"}"
req mev-list-after2.json GET /me/env-vars -H "${AUTH}"
printf '{"sandbox_config_id":"%s","name":"CFG_VAR"}' "${CID}" > /tmp/sbx4-del-sbx.json
req mev-delete-sandbox.json DELETE /me/env-vars/sandbox -H "${AUTH}" -H "${CT}" -d @/tmp/sbx4-del-sbx.json
req mev-set-skill-nofield.json PUT /me/env-vars/skill -H "${AUTH}" -H "${CT}" -d '{}'

echo "==> 5) catalog 删除（无安装钉住）"
req slk-catalog-delete.json DELETE "/skills/catalog/${CATID}" -H "${AUTH}"
req slk-catalog-delete-again.json DELETE "/skills/catalog/${CATID}" -H "${AUTH}"

echo "==> 清理"
${PSQL} >/dev/null <<'SQL'
DELETE FROM tenant_user_env_vars WHERE tenant_id = 10002;
DELETE FROM tenant_skill_snapshots WHERE tenant_id = 10002;
DELETE FROM tenant_skills WHERE tenant_id = 10002;
DELETE FROM tenant_skill_catalog WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL
echo "==> 完成"

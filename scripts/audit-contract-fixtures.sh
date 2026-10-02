#!/usr/bin/env bash
#
# 契约夹具「真被引用」审计（权威判定，不按名字猜）
#
# 为什么需要它：夹具是按名字拼接加载的（大量 `golden("contracts/" + name)`），
# 静态按名检索必然误报——名字常常来自用例表/变量。故本脚本用**行为**判定：
#   ① 静态粗筛候选（名字直引 + 明显的前后缀拼接 → 视为可能被引用，排除出候选）；
#   ② 把候选夹具**临时移出** contracts/ → 跑全量测试；
#   ③ 测试失败（报 `class path resource [contracts/x.json] cannot be opened` 之类）
#      ⇒ 该夹具**被引用**；全程无失败的候选 ⇒ **孤儿**（可删）。
# 结束必定还原（trap），无论中途成败。
#
# ⚠️ 结论仅作**人工复核线索**，**禁止按本脚本输出批量删除**：
#   - 本脚本"一次移出全部候选"，归因是**类级**的——同类共享 setup 的首个失败会**掩盖**其余候选，
#     于是它们被误判为"孤儿"（B31 实测：按输出删 13 个 → 2 条测试失败，全部还原）。
#   - 且"无测试加载"≠无用：B31 实测 115 个孤儿里 102 个是 `scripts/record-*-golden.sh` 的**录制
#     清单**（Go 期金鹰的案例表），1 个被 `docs/known-issues` 引用 ⇒ 属**证据链**。
#
# 用法：scripts/audit-contract-fixtures.sh
# 产物：/tmp/contract-fixture-audit/{candidates.txt,referenced.txt,orphans.txt,test.log}
set -uo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
FX_DIR="server/src/test/resources/contracts"
OUT=/tmp/contract-fixture-audit
mkdir -p "$OUT"
STASH=$(mktemp -d)
restore() {
  if [ -d "$STASH" ]; then
    find "$STASH" -type f -maxdepth 1 -exec mv {} "$FX_DIR/" \; 2>/dev/null
    rmdir "$STASH" 2>/dev/null
  fi
}
trap restore EXIT

# ── ① 候选粗筛：名字直引 + 前后缀拼接 ────────────────────────────────────────
python3 - "$FX_DIR" "$OUT/candidates.txt" <<'PY'
import pathlib, re, sys
fx_dir, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
fixtures = {p.name for p in fx_dir.glob('*.json')}
literals = set()
for p in pathlib.Path('server/src/test/java').rglob('*.java'):
    literals.update(re.findall(r'"([^"\n]{2,80})"', p.read_text(encoding='utf-8', errors='ignore')))

direct = {f for f in fixtures if f in literals or f[:-5] in literals}
prefixes = {l for l in literals if l.endswith(('-', '_'))}
suffixes = {l for l in literals if l.startswith(('-', '_')) and not l.endswith('-')}
composed = set()
for f in fixtures - direct:
    if any(f.startswith(p) for p in prefixes) or any(f.endswith(s) or s in f for s in suffixes):
        composed.add(f)
candidates = [f for f in fixtures if f not in direct and f not in composed]
out.write_text('\n'.join(sorted(candidates)) + ('\n' if candidates else ''), encoding='utf-8')
print(f'夹具 {len(fixtures)} 个：直引 {len(direct)}、疑似拼接 {len(composed)}、'
      f'候选（可能孤儿）{len(candidates)}')
PY

# ── ② 移出候选并跑全量测试 ───────────────────────────────────────────────────
moved=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  [ -f "$FX_DIR/$f" ] && mv "$FX_DIR/$f" "$STASH/" && moved=$((moved + 1))
done < "$OUT/candidates.txt"
echo "已临时移出 $moved 个候选夹具，开始跑全量测试（约 3 分钟）…"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
env -u SYSTEM_AES_KEY -u RETRIEVE_DRIVER -u SSRF_WHITELIST_EXTRA ./gradlew :server:test --console=plain > "$OUT/test.log" 2>&1
rc=$?
echo "测试退出码: ${rc}（非 0 即说明有候选被引用）"

# ── ③ 判定：失败报告里被点名的 = 被引用；其余 = 孤儿 ──────────────────────────
python3 - "$OUT" <<'PY'
import pathlib, re, sys
out = pathlib.Path(sys.argv[1])
candidates = [l.strip() for l in (out / 'candidates.txt').read_text(encoding='utf-8').split('\n') if l.strip()]
blob = ''
for p in pathlib.Path('server/build/test-results/test').glob('TEST-*.xml'):
    blob += p.read_text(encoding='utf-8', errors='ignore')
referenced = [f for f in candidates if f in blob]
orphans = [f for f in candidates if f not in blob]
(out / 'referenced.txt').write_text('\n'.join(referenced) + ('\n' if referenced else ''), encoding='utf-8')
(out / 'orphans.txt').write_text('\n'.join(orphans) + ('\n' if orphans else ''), encoding='utf-8')
print(f'候选 {len(candidates)}：被引用 {len(referenced)}、可判孤儿 {len(orphans)}')
for f in orphans[:60]:
    print('  孤儿候选:', f)
PY
# ── ④ 二次筛：区分「录制清单/文档证据」与「真·可删」──────────────────────────
# 教训（B31）：115 个"无测试加载"的孤儿里有 102 个被 scripts/record-*-golden.sh 引用
# ——它们是**金鹰的录制清单**（重录时的案例表），删掉等于毁掉证据链。
# 故：只有"既无测试加载、又无脚本/文档引用"者才标为可删。
python3 - "$OUT" <<'INNER'
import pathlib, subprocess, sys
out = pathlib.Path(sys.argv[1])
orphans = [l.strip() for l in (out / 'orphans.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
keep, deletable = [], []
for name in orphans:
    r = subprocess.run(['bash', '-c',
        f"grep -rlw --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build "
        f"--exclude-dir=dist '{name}' . 2>/dev/null | "
        f"grep -vE '^./server/src/test/(resources/contracts|java)' | head -1"],
        capture_output=True, text=True).stdout.strip()
    (keep if r else deletable).append(name if not r else f'{name}  <- {r}')
(out / 'keep-as-evidence.txt').write_text('\n'.join(keep) + ('\n' if keep else ''), encoding='utf-8')
(out / 'no-reference-candidates.txt').write_text('\n'.join(deletable) + ('\n' if deletable else ''), encoding='utf-8')
print(f'无人加载的孤儿 {len(orphans)}：录制清单/文档证据 {len(keep)}（保留）、')
print(f'  其余 {len(deletable)} 个写入 no-reference-candidates.txt——**仍需逐个确认**，别直接删')
INNER
echo "产物目录: $OUT"

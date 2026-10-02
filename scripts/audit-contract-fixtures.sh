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
echo "产物目录: $OUT"

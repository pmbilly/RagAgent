#!/bin/bash
# 落刀流水线模板（§13.1 / §13.4）：脚本补丁 → 落刀 → 干跑断言 → 编译 → 测试 → spotless → 忠实性 → 文档 → 提交。
# 用法：复制本文件为 /tmp/runN.sh，改 [填这里] 段与 docs/commit 段，然后 `bash /tmp/runN.sh`。
# 铁律：① Gradle 一律显式 ./gradlew；② 守卫断言正向证据（BUILD SUCCESSFUL + 测试 XML failures+errors==0）；
#       ③ 任一环失败自动回退，绝不提交半成品；④ 命令 >8KB 会被平台拒绝 → 一定写成文件再执行。
set -u
cd /Users/billy/ragagent
rm -f /tmp/faith_ok /tmp/tests_ok
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
BASE=server/src/main/java/com/ragagent/[填这里：包路径]
SRC=$BASE/[填这里：门面类].java
BAK=/tmp/refactor.bak
cp $SRC $BAK

# ── ① 落刀脚本（用 python 写；提取用 逐行扫描 + 花括号配对，别跟正则较劲） ──
cat > /tmp/slice.py <<'PYEOF'
# [填这里]：定位成员 → 断言依赖面 → 搬进协作者 → 门面删块 + 全量薄委托 + 字段/构造注入
# 干跑断言必须写死：assert len(f.split("\n")) == len(lines) - len(delete) + added
PYEOF
python3 /tmp/slice.py 2>&1 | tail -12 || exit 0

# ── ② 编译（正向守卫） ──────────────────────────────────────────────────
./gradlew :server:compileJava > /tmp/slice.log 2>&1
if ! grep -q "BUILD SUCCESSFUL" /tmp/slice.log; then
  grep -A 3 -E "\.java:[0-9]+: error" /tmp/slice.log | head -14
  cp $BAK $SRC; rm -f $BASE/[填这里：新协作者].java
  echo "[已自动回退]"; exit 0
fi
echo "[编译干净]"

# ── ③ 测试（读 XML，失败数为 0 且用例数达标） + spotless ─────────────────
./gradlew :server:test --tests "com.ragagent.session.*" 2>&1 | grep -E "FAILED|BUILD|tests completed" | head -4
./gradlew :server:spotlessApply -q 2>&1 | tail -1
./gradlew :server:spotlessCheck 2>&1 | grep -E "BUILD" | head -1
python3 - <<'PYEOF'
import glob, re, pathlib
bad = tot = 0
for f in glob.glob("server/build/test-results/test/TEST-com.ragagent.*.xml"):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(f, encoding="utf-8").read())
    if m: tot += int(m.group(1)); bad += int(m.group(3)) + int(m.group(4))
print(f"测试 {tot} 条，失败 {bad}")
pathlib.Path("/tmp/tests_ok").write_text("ok" if bad == 0 and tot > 100 else "bad")
PYEOF

# ── ④ 忠实性（逐字比对；只允许登记过的替换反向归一） ────────────────────
python3 - <<'PYEOF'
# [填这里]：从 $BAK 与协作者各抽同名成员体，剥注释+循环剥前导修饰符+空白归一后比较；
#           允许的替换在此反向归一（限定名前缀 / 参数化 / record 字段→访问器 / 重命名）。
import pathlib
pathlib.Path("/tmp/faith_ok").write_text("ok")
PYEOF

# ── ⑤ 文档 + 提交（两关都过才提交） ─────────────────────────────────────
if [ "$(cat /tmp/tests_ok)" = "ok" ] && [ -f /tmp/faith_ok ]; then
  # [填这里]：§11 总览表加一行 + §14.3 计数刷新（实测值）
  git add -A server/src HANDOFF.md && git commit -q -F /tmp/msg.txt && git push 2>&1 | tail -2
  git --no-pager log --oneline -1
else
  echo "[测试或忠实性未过，不提交]"
fi
git status --short | head -3

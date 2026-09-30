#!/usr/bin/env python3
"""包依赖守卫（棘轮）：**环只许减不许增**，分层违例数只许降不许升。

用法：
    python3 scripts/check-package-cycles.py            # 检查（新增环/违例变多 → 退出码 1）
    python3 scripts/check-package-cycles.py --write    # 解掉环后刷新基线（需在 PR 说明里写明减了哪些）

规则：
  R1 环棘轮：顶层包两两双向依赖（A→B 且 B→A）不得出现基线之外的新组合；
  R2 组合根单向：任何域不得 import `config`（config 是 Spring 装配层，应只出不进）；
  R3 能力层不依赖业务层：L2 = llm/retrieval/embedding/rerank/chatpipeline/modelcontext/searchutil/
     storageurl/webfetch，不得 import L3 业务域（基线计数只减不增）——这是阶段 4 模块化的前置。
"""
import json
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path("server/src/main/java/com/ragagent")
BASELINE = pathlib.Path("scripts/package-cycles.baseline.json")
L2 = {"llm", "retrieval", "embedding", "rerank", "chatpipeline", "modelcontext",
      "searchutil", "storageurl", "webfetch"}
L1 = {"common", "event", "stream", "tracing", "config"}
L3 = sorted({d.name for d in ROOT.iterdir() if d.is_dir()} - L2 - L1)

IMP = re.compile(r"^import com\.ragagent\.(\w+)\.", re.M)
edge = defaultdict(set)
for p in sorted(ROOT.iterdir()):
    if not p.is_dir():
        continue
    for f in p.rglob("*.java"):
        for m in IMP.finditer(f.read_text(encoding="utf-8")):
            if m.group(1) != p.name:
                edge[p.name].add(m.group(1))

cycles = sorted({tuple(sorted((a, b))) for a, t in edge.items() for b in t if a in edge.get(b, ())})
to_config = sorted(a for a in edge if "config" in edge[a])
l2_to_l3 = sorted((a, b) for a in L2 for b in edge[a] if b in L3)

state = {
    "cycles": [list(c) for c in cycles],
    "depend_on_config": to_config,
    "l2_to_l3": [list(x) for x in l2_to_l3],
}

if "--write" in sys.argv:
    BASELINE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"✓ 基线已写入 {BASELINE}：环 {len(cycles)} 组 / 依赖 config {len(to_config)} 包 / L2→L3 {len(l2_to_l3)} 条")
    sys.exit(0)

old = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
new_cycles = [c for c in [tuple(x) for x in state["cycles"]] if list(c) not in old.get("cycles", [])]
fixed = [c for c in old.get("cycles", []) if c not in state["cycles"]]
new_cfg = [x for x in to_config if x not in old.get("depend_on_config", [])]
new_l23 = [list(x) for x in state["l2_to_l3"] if list(x) not in old.get("l2_to_l3", [])]

print(f"环：{len(cycles)} 组（基线 {len(old.get('cycles', []))}）")
print(f"  新增环：{len(new_cycles)}" + ("" if not new_cycles else " " + ", ".join(f"{a}⇄{b}" for a, b in new_cycles)))
if fixed:
    print(f"  已消除（请刷新基线）：{len(fixed)} → " + ", ".join(f"{a}⇄{b}" for a, b in fixed))
print(f"依赖 config 的包：{len(to_config)}（基线 {len(old.get('depend_on_config', []))}）"
      + ("" if not new_cfg else f"；新增：{new_cfg}"))
print(f"L2 → L3 直连：{len(l2_to_l3)} 条（基线 {len(old.get('l2_to_l3', []))}）"
      + ("" if not new_l23 else "；新增：" + ", ".join(f"{a}→{b}" for a, b in new_l23)))

if new_cycles or new_cfg or new_l23:
    print("\n✗ 守卫失败：出现新的环或新的分层违例（见上）。")
    sys.exit(1)
print("\n✓ 守卫通过：环与分层违例均未增加。")

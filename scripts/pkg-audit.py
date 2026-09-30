#!/usr/bin/env python3
"""后端分包体检：规模 / 子包构成 / 跨包耦合与环 / 分层倒挂 / package-info 覆盖。

用法（仓库根目录）：
    python3 scripts/pkg-audit.py

结论与待办见 docs/backend-package-map.md。口径：
  - "域" = com.ragagent 下的一级包；
  - 耦合 = 跨域的 import（顶层包之间），域内子包不计；
  - 分层倒挂按"类所在子包 → 被 import 的目标子包"判定，抽样规则见各节标题。
"""
import pathlib
import re
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "server/src/main/java/com/ragagent")
PKGS = sorted(d.name for d in ROOT.iterdir() if d.is_dir())
FILES = {p: sorted((ROOT / p).rglob("*.java")) for p in PKGS}
LAYERS = ("controller", "service", "domain", "dto", "mapper", "repository", "support", "task",
          "client", "storage", "security", "config")


def lines(f):
    return len(f.read_text(encoding="utf-8").split("\n"))


print("═" * 92)
print("① 顶层包规模")
print(f"{'包':<14}{'文件':>6}{'行数':>9}{'子包':>6}{'根级':>6}{'package-info':>14}")
print("─" * 92)
rows = []
for p in PKGS:
    fs = FILES[p]
    subs = {f.parent for f in fs if f.parent != ROOT / p}
    rootn = sum(1 for f in fs if f.parent == ROOT / p)
    rows.append((len(fs), p, sum(lines(f) for f in fs), len(subs), rootn,
                 (ROOT / p / "package-info.java").exists()))
for n, p, ln, nsub, nroot, pi in sorted(rows, reverse=True):
    print(f"{p:<14}{n:>6}{ln:>9}{nsub:>6}{nroot:>6}{('有' if pi else '—'):>12}")
print("─" * 92)
print(f"{'合计':<14}{sum(r[0] for r in rows):>6}{sum(r[2] for r in rows):>9}")
print(f"顶层 package-info 覆盖：{sum(1 for r in rows if r[5])}/{len(rows)}")

print()
print("═" * 92)
print("② 各域子包构成（第一层：文件数）")
for n, p, *_ in sorted(rows, reverse=True):
    subs, rootn = Counter(), 0
    for f in FILES[p]:
        rel = f.relative_to(ROOT / p)
        if len(rel.parts) == 1:
            rootn += 1
        else:
            subs[rel.parts[0]] += 1
    body = " ".join(f"{k}({v})" for k, v in sorted(subs.items(), key=lambda kv: -kv[1]))
    print(f"  {p:<13}{('根' + str(rootn) + ' ') if rootn else ''}{body}")

IMP = re.compile(r"^import com\.ragagent\.(\w+)\.", re.M)
edges = defaultdict(set)
for p in PKGS:
    for f in FILES[p]:
        for m in IMP.finditer(f.read_text(encoding="utf-8")):
            if m.group(1) != p:
                edges[p].add(m.group(1))

print()
print("═" * 92)
print("③ 跨域耦合")
indeg = Counter()
for t in edges.values():
    for x in t:
        indeg[x] += 1
print("  被依赖最多（入度）：" + "，".join(f"{k}({v})" for k, v in indeg.most_common(10)))
print("  依赖最多（出度）：" + "，".join(f"{k}({len(v)})" for k, v in
                                     sorted(edges.items(), key=lambda kv: -len(kv[1]))[:10]))
cycles = sorted({tuple(sorted((a, b))) for a, tg in edges.items() for b in tg if a in edges.get(b, ())})
print(f"  ★ 双向依赖（环）：{len(cycles)} 组")
for a, b in cycles:
    print(f"      {a} ⇄ {b}")

print()
print("═" * 92)
print("④ 分层倒挂（抽样规则）")
viol = defaultdict(list)
for p in PKGS:
    for f in FILES[p]:
        rel = f.relative_to(ROOT / p)
        here = rel.parts[0] if len(rel.parts) > 1 else "(根)"
        t = f.read_text(encoding="utf-8")
        for m in re.finditer(r"^import com\.ragagent\.(\w+)\.(\w+)\.", t, re.M):
            if m.group(1) != p:
                continue
            tgt = m.group(2)
            if here == "controller" and tgt in ("mapper", "repository"):
                viol["controller → mapper/repository 直连"].append(f"{p}/{rel.as_posix()}")
            if here == "controller" and tgt == "domain":
                viol["controller → domain（实体直用，多为响应装配）"].append(f"{p}/{rel.as_posix()}")
            if here == "service" and tgt == "controller":
                viol["service → controller（真倒挂）"].append(f"{p}/{rel.as_posix()}")
            if here in ("domain", "dto") and tgt in ("service", "controller"):
                viol[f"{here} → {tgt}（真倒挂）"].append(f"{p}/{rel.as_posix()}")
for k in sorted(viol, key=lambda k: -len(viol[k])):
    uniq = sorted(set(viol[k]))
    print(f"  {k}：{len(uniq)} 文件；例：{uniq[0] if uniq else '-'}")

print()
print("═" * 92)
print("⑤ controller/ 包里的非控制器文件（应归位）+ 超大单层")
for p in PKGS:
    d = ROOT / p / "controller"
    if d.exists():
        bad = [f.name for f in sorted(d.glob("*.java"))
               if not f.stem.endswith("Controller") and f.stem != "package-info"]
        if bad:
            print(f"  {p:<13}{len(bad)} 个：{', '.join(bad[:6])}")
print("  ── 超大单层（≥70 文件）：")
for n, p, ln, nsub, nroot, pi in sorted(rows, reverse=True):
    for f in FILES[p]:
        pass
    for sub in sorted({f.parent for f in FILES[p] if f.parent != ROOT / p}):
        c = len(list(sub.rglob("*.java")))
        if c >= 70:
            print(f"    {sub.relative_to(ROOT)}：{c} 个")
    if nsub == 0 and n >= 20:
        print(f"    {p}（扁平包）：{n} 个")

#!/usr/bin/env python3
"""Go 锚点注释守卫（棘轮）：**只许减不许增**。

用法：
    python3 scripts/check-go-anchors.py            # 检查（任何文件比基线多 → 退出码 1）
    python3 scripts/check-go-anchors.py --write    # 清洗后刷新基线（PR 说明里写明减了哪些）

## 为什么只做棘轮、不做专项清扫

HANDOFF §4/§13.11 的既定政策：Go 锚点注释**随触碰清洗，先摘不变量信息再删锚点，不搞专项
大扫除**（agent 域 479 处/186 文件是一次随批清扫的样板，判据是「真实不变量改中性陈述保留」）。
本脚本是这条政策的**执行机制**：基底不强制下降，但任何文件都不许比基线更高——
顺手写了新锚点、或把旧文件里的锚点抄到新文件里，CI 会红。

## 口径（刻意窄，避免误伤）

统计的是 **main 源码**里的两类移植期黑话：

  * ``对照 Go``（含 ``对照Go``）——"照 Go 实现"的活锚点；
  * ``波 N``（``波 1`` / ``波 2``……）——移植波次编号，脱开当时的分批语境即无意义。

**不统计**（各自有其正当用法）：

  * ``golden``——契约金片基建（``GoldenContract`` / ``golden(...)``）在本仓是正式机制；
    knowledge 模块另有自己的扫描器禁它（那是该模块的既定口径）。
  * ``GORM``——``GORM 隐式行为清单`` 是约定 §3 要求的**结构化段落**（记录表/类型的隐式行为），
    属于要保留的信息，不是锚点。
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path("server/src/main/java")
BASELINE = pathlib.Path("scripts/go-anchors.baseline.json")

PATTERNS = {
    "对照 Go": re.compile(r"对照\s*Go"),
    "波 N": re.compile(r"波\s*\d"),
}


def count(path: pathlib.Path) -> int:
    text = path.read_text(encoding="utf-8", errors="replace")
    return sum(len(p.findall(text)) for p in PATTERNS.values())


def scan() -> dict:
    state = {}
    for f in sorted(ROOT.rglob("*.java")):
        n = count(f)
        if n:
            state[str(f.relative_to(ROOT))] = n
    return state


def main() -> int:
    state = scan()
    total = sum(state.values())

    if "--write" in sys.argv:
        BASELINE.write_text(json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                            encoding="utf-8")
        print(f"✓ 基线已写入 {BASELINE}：{total} 处 / {len(state)} 文件")
        return 0

    old = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    old_total = sum(old.values())

    increased = {f: (old.get(f, 0), n) for f, n in state.items() if n > old.get(f, 0)}
    cleaned = old_total - total

    print(f"Go 锚点：{total} 处 / {len(state)} 文件（基线 {old_total} / {len(old)}）"
          + (f"，本次净减 {cleaned}" if cleaned > 0 else ""))
    if not increased:
        print("✓ 无新增（棘轮只许减不许增）")
        return 0

    print(f"✗ {len(increased)} 个文件超出基线：")
    for f, (before, after) in sorted(increased.items()):
        print(f"    {f}: {before} → {after}")
    print("\n处理方式：按 §4 政策随触碰清洗——**先把真实不变量改写成中性陈述保留**，再删锚点；"
          "\n确属合理保留的，跑 `--write` 刷新基线并在 PR 说明里写清原因。")
    return 1


if __name__ == "__main__":
    sys.exit(main())

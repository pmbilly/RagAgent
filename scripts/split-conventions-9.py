#!/usr/bin/env python3
"""【历史工具·2026-09-25 起不再适用】把 `docs/translation-conventions.md` 的 §9 按批次拆到 `docs/known-issues/`。

⚠️ conventions 已于 2026-09-25 拆分：规范正文 → `docs/HANDOFF.md` §7，日志/细则 →
`docs/translation-log.md`（其末节即 §9 索引），坑正文留在本脚本的目标目录 `docs/known-issues/`。
因此本脚本的输入文件已不存在——仅供回溯历史拆分方式，勿再执行。

**一次性迁移脚本**（2026-09-21 执行）。日常维护不走本脚本：新增条目直接追加到
对应的分片文件（W5d 及以后 → `06-wave-5.md`）。

设计要点
- **内容一字不改**：只按已存在的顶层条目边界切行；脚本自带两道校验——
  ① 区间无缝无重叠覆盖 §9 全文；② 按原始行序重组后与原 §9 逐字节相同。
- 保留所有条目标题原文，便于把历史注释里的「§9「XXX 补充」」按标题检索回目标文件。
- 主文件的 §9 位置改为「引言 + 全量索引表（条目 → 文件）」。

重跑方式（主文件的 §9 已变索引，必须显式给拆分前的版本）：
    git show <拆分前的 rev>:docs/translation-conventions.md > /tmp/conv-old.md
    python3 scripts/split-conventions-9.py --source /tmp/conv-old.md
"""
from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONV = os.path.join(ROOT, "docs", "translation-conventions.md")
OUT_DIR = os.path.join(ROOT, "docs", "known-issues")

# (文件名, 标题, [(起, 止)] 1-based 闭区间)
GROUPS = [
    ("00-foundation.md", "基础契约（阶段 0–4.0）+ 跨阶段通用坑",
     [(299, 404), (916, 1003)]),
    ("01-mcp-stream-session.md", "阶段 4.1 / 5 / 5.2（MCP、流管理器、会话消息、SSE）",
     [(405, 715)]),
    ("02-wave-0-1.md", "波 0–1（memory / datasource / 会话消息面）",
     [(716, 915), (1004, 1159)]),
    ("03-wave-2.md", "波 2（chunk / knowledge / FAQ / 基础设施配置 / 成员 / 系统管理端 / 扫尾）",
     [(1160, 1580)]),
    ("04-wave-3.md", "波 3（sandbox / skill / 协作 / agents / browserskill）",
     [(1581, 1750)]),
    ("05-wave-4.md", "波 4（事件契约 / tools / 引擎 / chat 三兄弟）",
     [(1751, 2040)]),
    ("06-wave-5.md", "W5 收尾批（W5a / W5b / W5c + 后续 W5d 追加于此）",
     [(2041, None)]),
]

HEADER = """# 已确认细节与坑 · {title}

> 本文件是 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-conventions.md`](../translation-conventions.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。

"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", help="拆分前的 conventions 文件（§9 仍在其中的版本）")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    path = args.source or CONV
    text = open(path, encoding="utf-8").read()
    if "## 9. " not in text:
        print(f"❌ {path} 里找不到 `## 9. `", file=sys.stderr)
        return 1
    i = text.index("## 9. ")
    seg = text[i:]
    seg = seg.split("\n", 1)[1]        # 去掉 §9 的标题行（分片各带自己的标题）
    if len(seg.split("\n")) < 500:
        print("❌ §9 已不是完整正文（可能已被拆分过）——请用 --source 指定拆分前的版本：\n"
              "     git show <rev>:docs/translation-conventions.md > /tmp/conv-old.md",
              file=sys.stderr)
        return 1

    lines = seg.split("\n")
    while lines and lines[-1] == "":
        lines.pop()
    # lines[0] 对应原文件第 299 行
    OFFSET = 298

    written = []
    covered: list[int] = []
    for fname, title, ranges in GROUPS:
        chunks = []
        for a, b in ranges:
            start = a - OFFSET - 1
            end = (b - OFFSET) if b is not None else len(lines)
            chunks.append("\n".join(lines[start:end]).rstrip("\n"))
            covered.extend(range(a, (b or (OFFSET + len(lines))) + 1))
        body = "\n\n".join(chunks)
        content = HEADER.format(title=title) + "\n" + body + "\n"
        with open(os.path.join(OUT_DIR, fname), "w", encoding="utf-8") as fh:
            fh.write(content)
        written.append((fname, title, body))

    # ── 校验 1：区间必须无缝无重叠地覆盖 §9 全文 ──
    expected = list(range(299, OFFSET + len(lines) + 1))
    if sorted(covered) != expected:
        dup = {x for x in covered if covered.count(x) > 1}
        missing = [x for x in expected if x not in covered]
        print(f"❌ 区间覆盖不完整：重复行={sorted(dup)[:5]} 缺失行={missing[:5]}", file=sys.stderr)
        return 1

    # ── 校验 2：按原始行序重组必须逐字节等于原 §9 ──
    order = {}
    for fname, title, ranges in GROUPS:
        for a, b in ranges:
            start = a - OFFSET - 1
            end = (b - OFFSET) if b is not None else len(lines)
            for i, ln in enumerate(lines[start:end]):
                order[a + i] = ln
    rejoined = "\n".join(order[k] for k in sorted(order))
    orig = "\n".join(lines)
    if rejoined != orig:
        print("❌ 校验失败：按行序重组 != 原 §9", file=sys.stderr)
        for i in range(min(len(rejoined), len(orig))):
            if rejoined[i] != orig[i]:
                print(f"   首个差异 @ {i}:\n   新: {rejoined[i-80:i+80]!r}\n   原: {orig[i-80:i+80]!r}",
                      file=sys.stderr)
                break
        return 1
    print(f"✅ 校验通过：{len(written)} 个分片无缝覆盖 §9（{len(orig)} 字符，逐字节一致）")

    # ── 索引表 ──
    rows = []
    for fname, title, body in written:
        heads = [l for l in body.split("\n") if re.match(r"^- \*\*", l)]
        for h in heads:
            m = re.match(r"^- \*\*(.+?)\*\*", h)
            if not m:
                continue
            label = m.group(1).rstrip("：: ").strip()
            if len(label) > 42:
                label = label[:41] + "…"
            rows.append((label, fname, title))
    lines_out = ["## 9. 已确认细节与坑（索引）", "",
                 "> **本节的正文已按批次拆分到 [`docs/known-issues/`](known-issues/)（内容未改动，只挪位置）。**",
                 "> 动任何模块前先读对应分片；代码注释与任务书里的「约定 §9「XXX」」按下面的标题检索。",
                 "> 维护纪律不变：每完成一批，把新条目追加到对应的分片文件（W5d 及以后 → `06-wave-5.md`）。", "",
                 "| 批次 / 条目 | 分片文件 |", "|---|---|"]
    seen = set()
    for label, fname, title in rows:
        if label in seen:
            continue
        seen.add(label)
        lines_out.append(f"| {label} | [`{fname}`](known-issues/{fname}) |")
    lines_out.append("")
    index_md = "\n".join(lines_out)

    text = open(CONV, encoding="utf-8").read()
    head, _rest = text.split("## 9. ", 1)
    with open(CONV, "w", encoding="utf-8") as fh:
        fh.write(head + index_md)
    print(f"✅ 主文件 §9 已替换为索引（{len(rows)} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""前端契约键守卫（棘轮）：前端出现「后端只认 camel」的 snake 键 —— **只许减不许增**。

用法：
    python3 scripts/check-fe-contract-keys.py            # 检查（新出现 snake 契约键 → 退出码 1）
    python3 scripts/check-fe-contract-keys.py --write    # 复核后刷新基线（PR 说明里写明增删了什么）

## 为什么需要它

本仓出过三次同源缺陷（知识库配置键名分裂 B3b、存储两套词汇 B14/B15、agent 配置面 B18），
形态都是「写的人按一套键名、读的人按另一套，而反序列化又忽略未知键 ⇒ 数据被**静默丢弃**」。
前后端之间最容易再犯：后端已按 §2 第 4/11 条把 JSON 键改成 camel（= Java 字段名），
前端某处仍写 snake —— 该字段就静默失效，且不报错。

## 口径（刻意窄，避免误伤）

候选 = 同时满足三条的 snake 记号：

  1. 出现在 ``frontend/src`` 的 ``.ts/.vue/.mjs`` 里（词边界匹配，避开 ``manage_mcp_services`` 这类子串）；
  2. 后端 ``server/src/main`` 里**存在**其 camel 化形态（说明后端已换锚）；
  3. 后端 ``server/src/main`` 里**不存在**该 snake 字面量（说明后端不再认它）。

**不是契约键**的记号由基线登记豁免（理由写在基线里）：注释引用 / i18n 键 / CSS 类名 /
DB 列名 / 前端局部状态 / 查询参数 / 冻结面 …… 判定口诀见 HANDOFF §15.1.1 B19 段：
「这个键是不是**我们定义的、跨进程 JSON 字段**？是 ⇒ camel；否 ⇒ 看那一层的规范。」

## 政策

基底不强制下降（存量已逐条复核并登记理由），但**不许新增**：新写一处「前端 snake / 后端 camel」
的契约键，CI 会红。清理一处后请跑 ``--write`` 把基线收紧。
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(".")
BASELINE = pathlib.Path("scripts/fe-snake-contracts.baseline.json")
FE_DIRS = ("frontend/src",)
BE_DIR = "server/src/main"
FE_SUFFIX = (".ts", ".vue", ".mjs", ".js")
SNAKE = re.compile(r"(?<![A-Za-z0-9_])([a-z][a-z0-9]*_[a-z0-9_]+)(?![A-Za-z0-9_])")
BEM = re.compile(r"[a-z0-9]+__[a-z0-9]")


def camel(key: str) -> str:
    parts = key.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def be_literals() -> set[str]:
    lits = set()
    for p in pathlib.Path(BE_DIR).rglob("*.java"):
        for m in re.finditer(r'"([A-Za-z][A-Za-z0-9_]{2,40})"', p.read_text(encoding="utf-8", errors="ignore")):
            lits.add(m.group(1))
    return lits


def fe_sites() -> dict[str, list[tuple[str, int, str]]]:
    sites: dict[str, list[tuple[str, int, str]]] = {}
    for d in FE_DIRS:
        for p in pathlib.Path(d).rglob("*"):
            if p.suffix not in FE_SUFFIX:
                continue
            rel = str(p)
            for i, line in enumerate(p.read_text(encoding="utf-8", errors="ignore").split("\n"), 1):
                for m in SNAKE.finditer(line):
                    sites.setdefault(m.group(1), []).append((rel, i, line.strip()))
    return sites


def main() -> int:
    write = "--write" in sys.argv
    lits = be_literals()
    sites = fe_sites()
    baseline: dict[str, str] = {}
    if BASELINE.exists():
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))

    candidates: dict[str, str] = {}
    for key, occ in sorted(sites.items()):
        c = camel(key)
        if key in lits or c not in lits:
            continue  # 后端仍认 snake / 后端没有 camel 形态 ⇒ 不是本守卫的对象
        if key in baseline:
            continue
        candidates[key] = f"{occ[0][0]}:{occ[0][1]}"

    if write:
        for key, occ in candidates.items():
            baseline[key] = auto_reason(key, sites[key])
        BASELINE.write_text(json.dumps(baseline, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                            encoding="utf-8")
        print(f"✓ 基线已写入 {BASELINE}：{len(baseline)} 条")
        return 0

    if candidates:
        print("✗ 出现新的「前端 snake / 后端 camel」契约键（会静默失效）：")
        for key, loc in candidates.items():
            print(f"    {key:34s} → {camel(key):34s} 首个出现 {loc}")
        print("\n  改为后端字段名（camel）即可；若确属非契约键（注释/i18n/本地状态…），"
              "复核后跑 --write 登记理由。")
        return 1
    print(f"✓ 无新增（基线 {len(baseline)} 条，均为已复核豁免）")
    return 0


def auto_reason(key: str, occ: list[tuple[str, int, str]]) -> str:
    if BEM.search(key):
        return "CSS 类名（非契约）"
    if all("i18n/locales" in f for f, _, _ in occ):
        return "i18n 键（非契约）"
    if all(("//" in s.split(key)[0]) or s.startswith(("*", "<!--", "/*")) for _, _, s in occ):
        return "注释引用（非契约键）"
    return "已复核：本层约定或前端局部命名（详见 HANDOFF §15.1.1 B19）"


if __name__ == "__main__":
    raise SystemExit(main())

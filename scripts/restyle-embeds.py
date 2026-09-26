#!/usr/bin/env python3
"""把 Archify 产物的主题变量对齐到站点配色（中性灰 + 降饱和语义色）。

背景：docs/site/{architecture,agent-workflow}.html 由 Archify 生成，自带一套
深蓝底 + 霓虹色（slate/cyan/emerald/violet…）的主题，与站点（shadcn 中性灰）
不一致。本脚本对其 `:root,[data-theme="dark"]` 与 `[data-theme="light"]`
两个变量块做映射替换（幂等），并把衬线标题字体换成站点无衬线栈。

⚠️ 这两个 HTML 是生成物：**Archify 重新生成后需要再跑一次本脚本**。
站点主页面在切换主题时会把 data-theme 注入同源 iframe（见 docs/site/index.html
的主题同步逻辑），因此这里只负责"变量与字体"的对齐。

用法：
    python3 scripts/restyle-embeds.py            # 执行
    python3 scripts/restyle-embeds.py --check    # 只报告是否需要处理
"""
import pathlib
import re
import sys

SITE = pathlib.Path(__file__).resolve().parent.parent / 'docs' / 'site'
FILES = ['architecture.html', 'agent-workflow.html']

DARK_MAP = {
    '--bg': '#09090b',
    '--grid': '#1c1c1f',
    '--text': '#fafafa',
    '--text-muted': '#a1a1aa',
    '--text-dim': '#71717a',
    '--text-faint': '#8b8b93',
    '--panel': '#101012',
    '--panel-border': '#27272a',
    '--lane-fill': '#0f0f11',
    '--lane-stroke': '#27272a',
    '--arrow': '#52525b',
    '--arrow-emphasis': '#a1a1aa',
    '--mask': '#09090b',
    '--frontend-fill': 'rgba(34, 211, 238, 0.12)',
    '--frontend-stroke': '#22d3ee',
    '--backend-fill': 'rgba(52, 211, 153, 0.12)',
    '--backend-stroke': '#34d399',
    '--database-fill': 'rgba(167, 139, 250, 0.12)',
    '--database-stroke': '#a78bfa',
    '--cloud-fill': 'rgba(251, 191, 36, 0.12)',
    '--cloud-stroke': '#fbbf24',
    '--security-fill': 'rgba(251, 113, 133, 0.12)',
    '--security-stroke': '#fb7185',
    '--messagebus-fill': 'rgba(251, 146, 60, 0.12)',
    '--messagebus-stroke': '#fb923c',
    '--external-fill': 'rgba(113, 113, 122, 0.14)',
    '--external-stroke': '#a1a1aa',
    '--toolbar-bg': 'rgba(9, 9, 11, 0.82)',
    '--toolbar-border': '#27272a',
    '--toolbar-text': '#e4e4e7',
    '--toolbar-hover': '#18181b',
    '--toolbar-menu-bg': '#101012',
}

LIGHT_MAP = {
    '--bg': '#ffffff',
    '--grid': '#f4f4f5',
    '--text': '#09090b',
    '--text-muted': '#71717a',
    '--text-dim': '#a1a1aa',
    '--text-faint': '#71717a',
    '--panel': '#fafafa',
    '--panel-border': '#e4e4e7',
    '--lane-fill': '#fafafa',
    '--lane-stroke': '#e4e4e7',
    '--arrow': '#a1a1aa',
    '--arrow-emphasis': '#18181b',
    '--mask': '#ffffff',
    '--frontend-fill': 'rgba(8, 145, 178, 0.10)',
    '--frontend-stroke': '#0891b2',
    '--backend-fill': 'rgba(5, 150, 105, 0.10)',
    '--backend-stroke': '#059669',
    '--database-fill': 'rgba(124, 58, 237, 0.10)',
    '--database-stroke': '#7c3aed',
    '--cloud-fill': 'rgba(217, 119, 6, 0.10)',
    '--cloud-stroke': '#d97706',
    '--security-fill': 'rgba(225, 29, 72, 0.10)',
    '--security-stroke': '#e11d48',
    '--messagebus-fill': 'rgba(234, 88, 12, 0.10)',
    '--messagebus-stroke': '#ea580c',
    '--external-fill': 'rgba(113, 113, 122, 0.10)',
    '--external-stroke': '#52525b',
    '--toolbar-bg': 'rgba(255, 255, 255, 0.85)',
    '--toolbar-border': '#e4e4e7',
    '--toolbar-text': '#3f3f46',
    '--toolbar-hover': '#f4f4f5',
    '--toolbar-menu-bg': '#ffffff',
}

SERIF_TARGETS = [
    ("font-family: Georgia, 'Times New Roman', 'Songti SC', STSong, serif",
     'font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Segoe UI", sans-serif'),
    ("font-family: Georgia, 'Times New Roman', serif",
     'font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Segoe UI", sans-serif'),
]


def replace_vars(block: str, mapping: dict) -> tuple:
    hits = []

    def sub(m):
        name = m.group(1)
        if name in mapping:
            hits.append(name)
            return '%s: %s;' % (name, mapping[name])
        return m.group(0)

    return re.sub(r'(--[a-z-]+):\s*[^;]+;', sub, block), hits


def process(path: pathlib.Path, check_only: bool) -> str:
    text = path.read_text(encoding='utf-8')
    if '--bg: #09090b;' in text and '--bg: #ffffff;' in text:
        return 'already styled (skip)'

    dark_re = re.compile(r'(:root,\s*\[data-theme="dark"\]\s*\{)(.*?)(\n\s*\})', re.S)
    light_re = re.compile(r'(\[data-theme="light"\]\s*\{)(.*?)(\n\s*\})', re.S)

    m_dark = dark_re.search(text)
    m_light = light_re.search(text)
    if not m_dark or not m_light:
        return 'MISS: 未找到主题变量块（结构可能已变化）'

    new_dark, hits_dark = replace_vars(m_dark.group(2), DARK_MAP)
    new_light, hits_light = replace_vars(m_light.group(2), LIGHT_MAP)
    text = text[:m_dark.start(2)] + new_dark + text[m_dark.end(2):]
    # 重新定位 light 块（长度已变）
    m_light = light_re.search(text)
    text = text[:m_light.start(2)] + new_light + text[m_light.end(2):]

    font_hits = 0
    for old, new in SERIF_TARGETS:
        font_hits += text.count(old)
        text = text.replace(old, new)

    if check_only:
        return 'NEEDS: dark %d 变量 / light %d 变量 / 字体 %d 处' % (
            len(hits_dark), len(hits_light), font_hits)

    path.write_text(text, encoding='utf-8')
    return 'OK: dark %d / light %d 变量，字体 %d 处，%d bytes' % (
        len(hits_dark), len(hits_light), font_hits, len(text))


def main() -> int:
    check_only = '--check' in sys.argv
    for name in FILES:
        p = SITE / name
        if not p.exists():
            print('%-24s MISSING' % name)
            continue
        print('%-24s %s' % (name, process(p, check_only)))
    return 0


if __name__ == '__main__':
    sys.exit(main())

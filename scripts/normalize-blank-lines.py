#!/usr/bin/env python3
"""折叠"每行后跟一个空行"的切片产物 —— 只在成员边界保留空行。

背景（HANDOFF §14.7.14 / §13.29）：切片脚本产出的协作者文件曾被写成
"每行后跟一个空行"（空白行占比 55%~78%，仓库中位数 13%），文件被撑大一倍以上，
`wc -l` 榜单随之失真。本脚本把它折回标准排版：import 区连续、javadoc 紧贴声明、
类体/方法体只在成员之间留一个空行。

实现：先把注释/字符串/字符/文本块掩码成空格（保留换行），再按行跟踪花括号深度
与"块类型栈"（类型体 / 代码块），据此决定每个空行 run 的去留。
**只删空行，不动任何非空行**——脚本内置"非空行逐一相同"断言，违反即中止。

用法：
    python3 scripts/normalize-blank-lines.py            # 自动扫描并只打印预览
    python3 scripts/normalize-blank-lines.py --apply    # 自动扫描并写回
    python3 scripts/normalize-blank-lines.py --apply <file>...   # 指定文件

自动扫描口径：`server/src/main/java` 下 >100 行且空白行占比 >30% 的文件。
"""
import pathlib
import re
import subprocess
import sys

TYPE_HDR = re.compile(r'\b(class|interface|enum|record)\b')
IMPORT_RE = re.compile(r'^import ')
PACKAGE_RE = re.compile(r'^package ')
ROOT = 'server/src/main/java'


def mask(text):
    """把注释与字符串内容替换为空格（换行保留），长度不变。"""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                out[i] = ' '
                i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            while i < j:
                if text[i] != '\n':
                    out[i] = ' '
                i += 1
            continue
        if c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
                while i < j:
                    if text[i] != '\n':
                        out[i] = ' '
                    i += 1
            else:
                out[i] = ' '
                i += 1
                while i < n and text[i] != '"':
                    if text[i] == '\\':
                        out[i] = ' '
                        i += 1
                    if i < n:
                        if text[i] != '\n':
                            out[i] = ' '
                        i += 1
                if i < n:
                    out[i] = ' '
                    i += 1
            continue
        if c == "'":
            out[i] = ' '
            i += 1
            while i < n and text[i] != "'":
                if text[i] == '\\':
                    out[i] = ' '
                    i += 1
                if i < n:
                    if text[i] != '\n':
                        out[i] = ' '
                    i += 1
            if i < n:
                out[i] = ' '
                i += 1
            continue
        i += 1
    return ''.join(out)


def line_info(text):
    """逐行 (depth_before, depth_after, stack_before, stack_after, masked_line)。"""
    masked = mask(text)
    lines = masked.split('\n')
    depth = 0
    stack = []
    out = []
    for line in lines:
        depth_before, stack_before = depth, list(stack)
        header = []
        for ch in line:
            if ch == '{':
                hdr = ''.join(header)
                stack.append(bool(TYPE_HDR.search(hdr)) or ('new ' in hdr))
                depth += 1
                header = []
            elif ch == '}':
                if stack:
                    stack.pop()
                depth -= 1
                header = []
            elif ch == ';':
                header = []
            else:
                header.append(ch)
        out.append((depth_before, depth, stack_before, list(stack), line))
    return out


def normalize(text):
    lines = text.split('\n')
    info = line_info(text)
    assert len(lines) == len(info), '行数不一致'
    n = len(lines)
    drop = set()
    i = 0
    while i < n:
        if lines[i].strip() != '':
            i += 1
            continue
        j = i
        while j < n and lines[j].strip() == '':
            j += 1
        prev, nxt = i - 1, j
        if prev < 0 or nxt >= n:
            for k in range(i, j):
                drop.add(k)
            i = j
            continue
        pl = lines[prev].rstrip()
        nl = lines[nxt].lstrip()
        _, p_da, p_sb, p_sa, _ = info[prev]
        n_db, _, _, _, _ = info[nxt]
        keep = False
        if PACKAGE_RE.match(pl) and IMPORT_RE.match(nl):
            keep = True
        elif IMPORT_RE.match(pl) and not IMPORT_RE.match(nl):
            keep = True
        elif not nl.startswith('}'):
            if pl.endswith('{') and p_da == n_db and p_da >= 1:
                # 类型体开头（class/interface/enum/record）后留一空行
                keep = bool(p_sa[p_da - 1] if len(p_sa) >= p_da else False)
            elif pl.endswith('}') and p_da == n_db and p_da >= 1:
                # 成员结束：其外层级别必须是类型体（而非方法体里的普通块）
                keep = bool(p_sa[p_da - 1] if len(p_sa) >= p_da else False)
            elif pl.endswith(';') and p_da == n_db and p_da >= 1 \
                    and len(p_sa) >= p_da and p_sa[p_da - 1]:
                keep = True
        for k in range(i + 1 if keep else i, j):
            drop.add(k)
        i = j
    return [l for k, l in enumerate(lines) if k not in drop]


def scan_artifacts():
    files = subprocess.run(['git', 'ls-files', ROOT], capture_output=True, text=True).stdout.split()
    hits = []
    for f in files:
        if not f.endswith('.java'):
            continue
        raw = pathlib.Path(f).read_bytes().split(b'\n')
        if len(raw) <= 100:
            continue
        blank = sum(1 for l in raw if not l.strip())
        if blank / len(raw) > 0.30:
            hits.append(f)
    return hits


def main():
    args = sys.argv[1:]
    apply = '--apply' in args
    files = [a for a in args if a != '--apply'] or scan_artifacts()
    before = after = 0
    for f in files:
        p = pathlib.Path(f)
        old = p.read_text()
        new_lines = normalize(old)
        assert [l for l in new_lines if l.strip()] == [l for l in old.split('\n') if l.strip()], \
            f'非空行被改动: {f}'
        if new_lines and new_lines[-1] != '':
            new_lines.append('')
        before += len(old.split('\n'))
        after += len(new_lines)
        if apply:
            p.write_text('\n'.join(new_lines))
        print(f"{'APPLIED' if apply else 'PREVIEW'}  {len(old.split(chr(10)))} -> {len(new_lines)}  {f}")
    print(f"TOTAL {before} -> {after}  (-{before - after})")
    if not apply:
        print('（预览模式；确认后用 --apply 写回）')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""从 go-ego/gse 的 hmm 包源码机械提取 HMM 模型 → Java 侧资源 JSON。

为什么是"解析源码"而不是"跑 Go 导出"：`probEmit`/`probTrans`/`probStart`/`prevStatus`
在 gse 的 hmm 包里全是**非导出**变量，包外拿不到；而它们就是 jieba 的官方数据
（`prob_emit.go` 头部注明 "The data from https://github.com/fxsjy/jieba"），
源码里的字面量是唯一权威表示。

用法：
    python3 scripts/gen-jieba-hmm.py \\
        [--gse ~/go/pkg/mod/github.com/go-ego/gse@v0.80.3] \\
        [--out server/src/main/resources/jieba/hmm_model.json]

产物（按 IEEE754 最短往返表示，保证与 Go 侧逐位一致）：
    {"source":..., "minFloat":..., "probStart":{...}, "prevStatus":{...},
     "probTrans":{...}, "probEmit":{"B":{码点: 值, ...}, ...}}
"""
import argparse
import json
import os
import re
import sys

DEFAULT_GSE = os.path.expanduser("~/go/pkg/mod/github.com/go-ego/gse@v0.80.3")


def decode_rune_literal(lit: str) -> int:
    """`'\\u4e00'` / `'的'` → 码点。"""
    if "\\u" in lit or "\\x" in lit:
        return ord(lit.encode("latin-1", "backslashreplace").decode("unicode_escape"))
    return ord(lit)


def parse_emit(path):
    src = open(path, encoding="utf-8").read()
    out = {}
    for state, body in re.findall(
        r"probEmit\['(\w)'\] = map\[rune\]float64\{(.*?)\n\t\}", src, re.S
    ):
        entries = {}
        for lit, val in re.findall(r"'((?:\\.|[^'])+)':\s*(-?[\d.e+]+),", body):
            entries[str(decode_rune_literal(lit))] = float(val)
        out[state] = entries
    if sorted(out) != ["B", "E", "M", "S"]:
        sys.exit(f"prob_emit 解析失败：拿到 {sorted(out)}")
    return out


def parse_trans(path):
    src = open(path, encoding="utf-8").read()
    out = {}
    for state, body in re.findall(
        r"probTrans\['(\w)'\] = map\[byte\]float64\{(.*?)\}", src, re.S
    ):
        out[state] = {
            st: float(v) for st, v in re.findall(r"'(\w)':\s*(-?[\d.e+]+)", body)
        }
    if sorted(out) != ["B", "E", "M", "S"]:
        sys.exit(f"prob_trans 解析失败：拿到 {sorted(out)}")
    return out


def parse_viterbi(path):
    src = open(path, encoding="utf-8").read()
    min_float = float(re.search(r"const minFloat = (-?[\d.e+]+)", src).group(1))
    prob_start = {
        st: float(v)
        for st, v in re.findall(r"probStart\['(\w)'\] = (-?[\d.e+]+)", src)
    }
    prev_status = {
        st: re.findall(r"'(\w)'", body)
        for st, body in re.findall(
            r"prevStatus\['(\w)'\] = \[\]byte\{([^}]*)\}", src
        )
    }
    if sorted(prob_start) != ["B", "E", "M", "S"] or sorted(prev_status) != [
        "B",
        "E",
        "M",
        "S",
    ]:
        sys.exit(f"viterbi 解析失败：probStart={sorted(prob_start)} prevStatus={sorted(prev_status)}")
    return min_float, prob_start, prev_status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gse", default=DEFAULT_GSE)
    ap.add_argument("--out", default="server/src/main/resources/jieba/hmm_model.json")
    args = ap.parse_args()

    hmm = os.path.join(args.gse, "hmm")
    min_float, prob_start, prev_status = parse_viterbi(os.path.join(hmm, "viterbi.go"))
    model = {
        "source": "go-ego/gse v0.80.3 hmm（数据同 fxsjy/jieba prob_emit/prob_trans）",
        "minFloat": min_float,
        "probStart": prob_start,
        "prevStatus": prev_status,
        "probTrans": parse_trans(os.path.join(hmm, "prob_trans.go")),
        "probEmit": parse_emit(os.path.join(hmm, "prob_emit.go")),
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")

    counts = {k: len(v) for k, v in model["probEmit"].items()}
    print(f"wrote {args.out}  emit={counts} size={os.path.getsize(args.out)}B")


if __name__ == "__main__":
    main()

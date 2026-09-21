#!/usr/bin/env python3
"""Go ↔ Java 路由对账（程序化，启发式）。

用法:  python3 scripts/route-recon.py [--go /Users/billy/WeKnora] [--java /Users/billy/ragagent-java]

- Go 侧: 解析 internal/router/*.go 的分组赋值链（`v1 := r.Group("/api/v1")`、
  `kb := g.apiKeyGroup(kbgrp, ...)`、`kbManagement := kb.With(...)`、函数参数的
  `*gin.RouterGroup` 由 router.go 的 call site 反推前缀）以及 verb 注册点。
- Java 侧: 解析 com/ragagent/**/*.java 的类级 @RequestMapping 前缀 + 方法级
  @{Get,Post,Put,Delete,Patch}Mapping（含数组形态与全限定注解写法）。
- 路径参数统一归一: `:id` / `{id}` / `*action` → `{}`，参数名不参与比较。

**已知局限（重要）**
1. Go 侧仍会漏掉少量用非常规方式构造的 group（实测 Go 389 条 / Java 444 条，
   交集 380）——因此「只在 Go（真缺口候选）」是可信的（漏解析只会让缺口**变少**
   不会变多，这里没有漏报），但「只在 Java」那一段基本是 Go 解析残留，别当结论用。
2. 「疑似解析差异」用尾部两段做兜底匹配，属启发式，可能把 `a/{}/files` 与
   `b/{}/files` 误判成同一条——判缺口时以「真缺口候选」段为准，再人工确认。
3. 只覆盖 `internal/router/` 下的注册；`static.go` 的静态文件服务、
   `serveFrontendStatic`、swagger 等非业务路由会以噪声形式出现。
"""
import argparse
import os
import re
import sys
from collections import defaultdict

GO_VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Any")
JAVA_ANN = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    "RequestMapping": None,  # 由 method= 决定
}


def norm(path: str) -> str:
    """归一化：gin 的 :id 与 Spring 的 {id} 都变成 {}；去掉尾斜杠。"""
    path = re.sub(r"\{[^}]*\}", "{}", path)
    path = re.sub(r":[A-Za-z_][A-Za-z0-9_]*", "{}", path)
    # gin 的 *action 通配
    path = re.sub(r"\*[A-Za-z_][A-Za-z0-9_]*", "{}", path)
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


# ---------------------------------------------------------------- Go
#
# Go 侧路由分两种写法，必须一起处理：
#   (a) router.go 里内联: v1 := r.Group("/api/v1")  →  groups 表直接解析
#   (b) routes_*.go 里 Register*Routes(r *gin.RouterGroup, ...) —— 函数体内的
#       r 是**调用方**传进来的 group（通常是 v1="/api/v1"），本文件解析不到。
#       所以先扫 call site 把「函数名 → 前缀」定下来，再解析函数体。
GO_FUNC_RE = re.compile(
    r'^func\s+(\w+)\((?P<params>[^)]*)\)', re.M
)
GO_ROUTERGROUP_PARAM_RE = re.compile(
    r'(\w+)\s+\*gin\.RouterGroup'
)
GO_CALL_RE = re.compile(r'\b(\w+)\(\s*(\w+)\s*[,)]')
GO_VERB_RE = re.compile(
    r'([\w.]+)\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|Any)\(\s*"([^"]*)"'
)

# Python 3.13 的 re 在 `(?:^|…)` 形态下会漏匹配（实测 `(?:^)(\w+)` 恒不匹配）。
# 规避：把代码整段规范化——换行与制表符换成等长字符，并在头部补一个分隔符，
# 断言只用 [;{(]，不用 ^。规范化后偏移 = 原偏移 + 1。
GO_NORM_TRANS = str.maketrans({"\n": ";", "\t": " ", "\r": ";"})


def go_norm(text: str) -> str:
    """等长规范化：换行/制表符 → ';' / ' '，前缀一个 ';' 充当语句起始。"""
    return ";" + text.translate(GO_NORM_TRANS)


def _scan_file(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# 语句切分：go_norm() 之后换行已变成 ';'，所以 RHS 取到下一个 ';' 为止。
GO_ASSIGN_RE = re.compile(r'(?:[;{(]\s*)(\w+)\s*(?::=|=)\s*([^;]*)')
GO_RECV_GROUP_RE = re.compile(r'^([\w.]+)\.Group\(\s*"([^"]*)"')
GO_APIKEY_GROUP_RE = re.compile(r'^(?:[\w.]*\.)?apiKeyGroup\(\s*(.+?)\s*(?:,|\)\s*$)')
GO_ENGINE_VARS = ("r", "engine", "router", "e")
GO_BARE_VAR_RE = re.compile(r'^(\w+)$')
# kbManagement := kb.With(apiKeyManageKnowledgeBases(...)) —— 同前缀、叠中间件
GO_WITH_RE = re.compile(r'^([\w.]+)\.(?:With|Clone)\(')


def _resolve_rhs(rhs: str, groups: dict):
    """把一个赋值右值解析成路由前缀；解析不出返回 None。"""
    rhs = rhs.strip()
    if not rhs:
        return None
    m = GO_WITH_RE.match(rhs)
    if m:
        return groups.get(m.group(1))
    m = GO_RECV_GROUP_RE.match(rhs)
    if m:
        recv, prefix = m.group(1), m.group(2)
        if prefix.startswith("/api/"):
            return prefix.rstrip("/")
        if recv in groups:
            return (groups[recv] + prefix).rstrip("/")
        if recv in GO_ENGINE_VARS:
            return prefix.rstrip("/")
        return None
    m = GO_APIKEY_GROUP_RE.match(rhs)
    if m:
        # g.apiKeyGroup(<group 表达式>, apiKeyXxx(...))：前缀等于内层表达式
        return _resolve_rhs(m.group(1), groups)
    m = GO_BARE_VAR_RE.match(rhs)
    if m and m.group(1) in groups:
        return groups[m.group(1)]
    return None


def _build_groups(text, seed=None):
    """从一段 Go 代码里解析 变量 → 路由前缀（迭代到不动点）。

    实测存在的赋值形态：
      v1 := r.Group("/api/v1")
      t  := tenantRoutes.Group("/:id", g.PathTenantMatch())
      chunks := g.apiKeyGroup(r.Group("/chunks"), apiKeyIngest(...))   ← 内层 .Group
      kb     := g.apiKeyGroup(kbgrp, apiKeyFullAccess())              ← 内层是**变量**
      chunkRead := chunks.Group("")                                   ← 同前缀子组
    关键：apiKeyGroup(r.Group(...)) 里内层的 r.Group **不能**回写 r 的前缀；
    因此只能整体解析赋值语句，不能独立扫描 .Group( 出现的位置。
    传入的 text 必须已 go_norm()。
    """
    groups = dict(seed or {})
    for _ in range(4):   # 不动点：后出现的赋值常引用前面的变量
        changed = False
        for m in GO_ASSIGN_RE.finditer(text):
            var, rhs = m.group(1), m.group(2)
            if var in groups:
                continue
            prefix = _resolve_rhs(rhs, groups)
            if prefix is not None:
                groups[var] = prefix
                changed = True
        if not changed:
            break
    return groups


def _func_ranges(text):
    """返回 [(funcName, routerGroupParam, start, end)]（原文偏移）。"""
    out = []
    marks = [(m.start(), m.group(1), m.group("params")) for m in GO_FUNC_RE.finditer(text)]
    for idx, (start, name, params) in enumerate(marks):
        end = marks[idx + 1][0] if idx + 1 < len(marks) else len(text)
        rp = GO_ROUTERGROUP_PARAM_RE.search(params)
        out.append((name, rp.group(1) if rp else None, start, end))
    return out


def parse_go(root: str):
    router_dir = os.path.join(root, "internal", "router")
    files = sorted(f for f in os.listdir(router_dir)
                   if f.endswith(".go") and not f.endswith("_test.go"))
    texts = {fn: _scan_file(os.path.join(router_dir, fn)) for fn in files}

    # ---- pass 1: 全局「函数名 → 前缀」，用 call site 实参所属 group 解析
    func_prefix = {}
    for _round in range(3):
        for fn, text in texts.items():
            norm_text = go_norm(text)
            groups = _build_groups(norm_text)
            for m in GO_CALL_RE.finditer(norm_text):
                callee, arg = m.group(1), m.group(2)
                if callee in func_prefix and arg not in groups:
                    groups[arg] = func_prefix[callee]
            for m in GO_CALL_RE.finditer(norm_text):
                callee, arg = m.group(1), m.group(2)
                if arg in groups:
                    func_prefix.setdefault(callee, groups[arg])
                elif arg in ("r", "engine", "router"):
                    func_prefix.setdefault(callee, "")

    found = defaultdict(set)
    for fn in files:
        text = texts[fn]
        for name, rg_param, start, end in _func_ranges(text):
            body = go_norm(text[start:end])
            seed = {}
            if rg_param:
                # 函数体里 r 的前缀来自 call site；解析不到时按 /api/v1 兜底
                seed[rg_param] = func_prefix.get(name, "/api/v1")
            groups = _build_groups(body, seed)
            for m in GO_CALL_RE.finditer(body):
                callee, arg = m.group(1), m.group(2)
                if callee in func_prefix and arg not in groups:
                    groups[arg] = func_prefix[callee]
            for m in GO_VERB_RE.finditer(body):
                var, verb, path = m.group(1), m.group(2), m.group(3)
                if var in groups:
                    prefix = groups[var]
                elif var in ("r", "engine", "router"):
                    prefix = ""
                else:
                    continue
                if not path.startswith("/"):
                    path = "/" + path
                full = path if path.startswith("/api/") else prefix + path
                offset = start + m.start() - 1   # 还原 go_norm 的 +1 位移
                line_no = text[:offset].count("\n") + 1
                found[(verb.upper(), norm(full))].add(f"{fn}:{line_no}")
    return found


# ---------------------------------------------------------------- Java
#
# 写法比 Go 杂，实测有四种：
#   @GetMapping("/api/v1/x")                     单串
#   @GetMapping({"/api/v1/x", "/api/v1/x/{id}"}) 数组（同一方法多 pattern）
#   @RequestMapping(value = "/api/v1/x", method = RequestMethod.DELETE)
#   @RequestMapping(value = "/api/v1/x", method = {RequestMethod.PUT, RequestMethod.PATCH})
# 类级 @RequestMapping 只作前缀；方法级带 /api/ 的路径视为绝对路径。
# 注意：注解可能写成全限定名（实测有
#   @org.springframework.web.bind.annotation.DeleteMapping("/{id}")），
# 所以包前缀要允许吸收。
JAVA_METHOD_RE = re.compile(
    r'@(?:[\w.]+\.)?(Get|Post|Put|Delete|Patch)Mapping\s*(\([^)]*\))?', re.S
)
JAVA_REQ_METHOD_RE = re.compile(r'@(?:[\w.]+\.)?RequestMapping\s*(\([^)]*\))?', re.S)
JAVA_STR_RE = re.compile(r'"([^"]*)"')
JAVA_METHOD_ATTR_RE = re.compile(r'method\s*=\s*\{?([^)}]*)')


def _paths_in(args: str):
    """从注解参数串里取全部以 / 开头的路径（数组形态会取出多个）。"""
    return [s for s in JAVA_STR_RE.findall(args or "") if s.startswith("/")]


def _verbs_in(args: str):
    """从 method = ... 里取动词（数组形态会取出多个）。"""
    m = JAVA_METHOD_ATTR_RE.search(args or "")
    if not m:
        return []
    return re.findall(r"(?:RequestMethod\.)?([A-Z]+)", m.group(1))


def parse_java(root: str):
    base_dir = os.path.join(root, "server", "src", "main", "java", "com", "ragagent")
    found = defaultdict(set)
    for dirpath, _dirs, filenames in os.walk(base_dir):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            with open(full, encoding="utf-8") as fh:
                text = fh.read()
            rel = os.path.relpath(full, base_dir)

            class_prefix = ""
            class_span = None
            for m in JAVA_REQ_METHOD_RE.finditer(text):
                if m.start() > text.find("class "):
                    break
                paths = _paths_in(m.group(1))
                if paths and paths[0].startswith("/api/"):
                    class_prefix = paths[0].rstrip("/")
                    class_span = (m.start(), m.end())
                    break

            def emit(verb, path, pos):
                p = path if path.startswith("/api/") else \
                    class_prefix + ("/" + path.lstrip("/") if path else "")
                line = text[:pos].count("\n") + 1
                found[(verb, norm(p))].add(f"{rel}:{line}")

            for m in JAVA_METHOD_RE.finditer(text):
                verb, args = m.group(1).upper(), m.group(2) or ""
                paths = _paths_in(args)
                if paths:
                    for p in paths:
                        emit(verb, p, m.start())
                else:
                    # 裸注解 @GetMapping（路径全取类级前缀）
                    emit(verb, class_prefix, m.start())

            for m in JAVA_REQ_METHOD_RE.finditer(text):
                if class_span and class_span[0] == m.start():
                    continue
                args = m.group(1) or ""
                paths, verbs = _paths_in(args), _verbs_in(args)
                if not paths or not verbs:
                    continue
                for verb in verbs:
                    for p in paths:
                        emit(verb, p, m.start())
    return found


def _tail(path: str, n: int = 2):
    """取路径尾部 n 段（用于识别「同一条路由但前缀解析不同」）。"""
    segs = [s for s in path.split("/") if s]
    return tuple(segs[-n:]) if len(segs) >= n else tuple(segs)


def classify(only_go, only_java):
    """把单侧路由分成「真缺口候选」与「疑似解析差异」。

    前缀解析（Group 链 / 类级 @RequestMapping）两边都不完美，因此同一条路由
    可能在一侧被算成 /a/b/c、另一侧被算成 /c。尾部两段相同 → 大概率是同一条，
    只是前缀没解析出来，归入「疑似」；否则才是真缺口候选。
    """
    java_tails = {(v, _tail(p)) for v, p in only_java}
    java_all_tails = {(v, _tail(p)) for v, p in only_java}
    real_go, suspect_go = [], []
    for v, p in only_go:
        if (v, _tail(p)) in java_tails or (v, _tail(p, 1)) in java_all_tails:
            suspect_go.append((v, p))
        else:
            real_go.append((v, p))
    go_tails = {(v, _tail(p)) for v, p in only_go}
    real_java, suspect_java = [], []
    for v, p in only_java:
        if (v, _tail(p)) in go_tails or (v, _tail(p, 1)) in go_tails:
            suspect_java.append((v, p))
        else:
            real_java.append((v, p))
    return real_go, suspect_go, real_java, suspect_java


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--go", default="/Users/billy/WeKnora")
    ap.add_argument("--java", default="/Users/billy/ragagent-java")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args()

    go = parse_go(args.go)
    java = parse_java(args.java)

    go_set, java_set = set(go), set(java)
    only_go = sorted(go_set - java_set)
    only_java = sorted(java_set - go_set)

    if args.json:
        import json
        print(json.dumps({
            "go_count": len(go_set),
            "java_count": len(java_set),
            "common": len(go_set & java_set),
            "only_go": [{"verb": v, "path": p, "src": sorted(go[(v, p)])} for v, p in only_go],
            "only_java": [{"verb": v, "path": p, "src": sorted(java[(v, p)])} for v, p in only_java],
        }, ensure_ascii=False, indent=2))
        return

    print(f"Go   verb+path: {len(go_set)}")
    print(f"Java verb+path: {len(java_set)}")
    print(f"交集:          {len(go_set & java_set)}")
    print(f"只在 Go:  {len(only_go)}   只在 Java: {len(only_java)}")

    real_go, suspect_go, real_java, suspect_java = classify(only_go, only_java)

    print(f"\n=== 真缺口候选（Go 有、Java 无；尾部也对不上）: {len(real_go)} ===")
    for verb, path in real_go:
        print(f"  {verb:6} {path}")
        for s in sorted(go[(verb, path)]):
            print(f"          <- {s}")

    print(f"\n=== 只在 Go 但疑似解析差异（尾部能在 Java 侧找到）: {len(suspect_go)} ===")
    for verb, path in suspect_go:
        print(f"  {verb:6} {path}   <- {sorted(go[(verb, path)])}")

    print(f"\n=== 只在 Java 但疑似解析差异: {len(suspect_java)} ===")
    for verb, path in suspect_java:
        print(f"  {verb:6} {path}   <- {sorted(java[(verb, path)])}")

    print(f"\n=== 只在 Java（Go 侧疑似漏解析，需人工确认）: {len(real_java)} ===")
    for verb, path in real_java:
        print(f"  {verb:6} {path}")
        for s in sorted(java[(verb, path)]):
            print(f"          <- {s}")


if __name__ == "__main__":
    sys.exit(main())

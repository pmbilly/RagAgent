#!/usr/bin/env python3
"""生成 API 文档：解析 Java 控制器注解 + RBAC/API-Key 策略，输出静态网页。

用法:  python3 scripts/generate-api-docs.py [--java /Users/billy/ragagent-java]

输出（默认 docs/site/api/，可用 --out 覆盖）:
  api-docs.json   结构化路由数据
  index.html      自包含静态页（数据内嵌，双击即可打开；主题支持 data-theme 属性与系统深色偏好）

数据来源（与 scripts/route-recon.py 同源解析，经 importlib 复用避免两份正则漂移）:
  - 控制器注解: @GetMapping / @PostMapping / @RequestMapping(method=…)（含类级前缀与全限定写法）
  - RBAC:      WebConfig 的 rbac.addRule("<METHOD>", "<pattern>", TenantRole.X, orSystemAdmin)
  - API-Key:   APIKeyRoutePolicies 的 registerGin/register("<METHOD>", "<gin path>", 策略)
"""
import argparse
import importlib.util
import json
import os
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# 复用 route-recon 的解析（文件名带连字符，走 importlib）
_spec = importlib.util.spec_from_file_location(
    "route_recon", os.path.join(HERE, "route-recon.py"))
rr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rr)

DOMAIN_LABELS = {
    "auth": "认证与账号", "tenants": "租户管理", "models": "模型配置",
    "knowledge-bases": "知识库", "knowledge": "文档", "chunks": "Chunk 编辑",
    "wiki": "Wiki", "faq": "FAQ", "sessions": "会话", "messages": "消息",
    "memory": "长期记忆", "agents": "Agent", "agent-chat": "Agent 问答",
    "knowledge-chat": "知识库问答", "knowledge-search": "知识检索",
    "mcp": "MCP 服务", "modelcontext": "MCP(旧)", "sandbox-configs": "沙箱配置",
    "skills": "技能", "me": "个人设置", "system": "系统管理", "evaluation": "评估",
    "organizations": "组织协作", "shared": "共享入口", "im": "IM 集成",
    "embed": "嵌入问答", "datasources": "数据源", "favorites": "收藏",
    "web-search-providers": "搜索服务商", "vector-stores": "向量库",
    "storage-backends": "存储后端", "files": "文件代理", "tenants": "租户管理",
    "chunker": "分块预览", "user": "用户", "weknoracloud": "WeKnora Cloud",
    "initialization": "初始化向导", "api": "API Principal", "tenants-kv": "租户 KV",
    "prompt-templates": "提示词模板", "artifacts": "产物", "steer": "Steer",
    "suggestions": "追问建议", "continue-stream": "续流", "terminal": "终端",
    "local-browser": "本地浏览器", "browser": "浏览器技能", "kv": "KV 配置",
    "search": "搜索", "dataset": "评估数据", "kv": "KV",
    "datasource": "数据源", "embed-channels": "嵌入渠道",
    "knowledgebase": "知识库(旧形态)", "mcp-services": "MCP 服务",
    "mcp-oauth": "MCP OAuth", "im-channels": "IM 渠道",
    "shared-agents": "共享 Agent", "shared-knowledge-bases": "共享知识库",
    "health": "健康检查", "r": "短链资源", "other": "其他",
    "web-search": "Web 搜索", "wechat": "微信扫码",
}

VERB_ORDER = {"GET": 0, "POST": 1, "PUT": 2, "PATCH": 3, "DELETE": 4, "HEAD": 5}

JAVA_METHOD_NAME_RE = re.compile(
    r'(?:public|protected|private)\s+[\w<>,.\[\]?\s]+?\s+(\w+)\s*\(')
JAVA_JAVADOC_RE = re.compile(r'/\*\*(.*?)\*/', re.S)
JAVA_STR_RE = re.compile(r'"([^"]*)"')
RBAC_RULE_RE = re.compile(
    r'addRule\(\s*"([A-Z]+)"\s*,\s*"([^"]+)"\s*,\s*TenantRole\.(\w+)\s*,\s*(true|false)\s*\)')
APIKEY_REGISTER_RE = re.compile(
    r'register(?:Gin)?\(\s*"([A-Z]+)"\s*,\s*([^,]+?),\s*([^)]+?)\)')
APIKEY_VAR_DEF_RE = re.compile(
    r'APIKeyRoutePolicy\s+(\w+)\s*=\s*(.+?);', re.S)


def norm_rbac(path: str) -> str:
    return rr.norm(path)


def apikey_norm(path: str) -> str:
    """gin 风格 :id → {}。"""
    p = path.strip().strip('"')
    p = re.sub(r":[A-Za-z_][A-Za-z0-9_]*", "{}", p)
    return rr.norm(p)


def policy_text(expr: str, varmap: dict) -> str:
    expr = re.sub(r"\s+", " ", expr.strip())
    expr = re.sub(r"APIKeyRoutePolicy\.", "", expr)
    for var, text in varmap.items():
        if re.search(r"\b" + re.escape(var) + r"\b", expr):
            expr = re.sub(r"\b" + re.escape(var) + r"\b", text, expr)
            break
    return expr[:120]


def handler_hint(text: str, pos: int) -> tuple:
    """映射注解之后的方法名 + 之前最近的 javadoc 首行（best-effort）。"""
    name = ""
    m = JAVA_METHOD_NAME_RE.search(text, pos)
    if m:
        name = m.group(1)
    desc = ""
    jd_end = text.rfind("*/", 0, pos)
    if jd_end != -1:
        jd_start = text.rfind("/**", 0, jd_end)
        if jd_start != -1 and pos - jd_end < 400:
            body = text[jd_start + 3:jd_end]
            for line in body.splitlines():
                line = line.strip().lstrip("*").strip()
                if line and not line.startswith("@"):
                    desc = line[:100]
                    break
    return name, desc


# ---------------------------------------------------------------- 类型字段索引

JAVA_FIELD_RE = re.compile(
    r'(?:@JsonProperty\("([^"]+)"\)\s*)?\n\s*(?:private|public)\s+(?:final\s+)?'
    r'([\w<>,.\[\]?]+)\s+(\w+)\s*(?:=|;)')
JAVA_RECORD_RE = re.compile(r'\brecord\s+(\w+)\s*(?:<[^>]*>)?\s*\(([^)]*)\)', re.S)
JAVA_ANNOS = re.compile(r'@\w+(?:\([^)]*\))?\s*', re.S)


def split_top(s: str):
    """按顶层逗号拆分（泛型/括号深度归零才切）。"""
    out, depth, cur = [], 0, []
    for ch in s:
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(''.join(cur)); cur = []
        else:
            cur.append(ch)
    tail = ''.join(cur).strip()
    if tail:
        out.append(tail)
    return out


def build_type_index(java_root: str):
    """全仓类型 → wire 字段（@JsonProperty 优先；record 组件剥注解）。"""
    idx = {}
    base = os.path.join(java_root, "server", "src", "main", "java", "com", "ragagent")
    for dirpath, _d, files in os.walk(base):
        for fn in files:
            if not fn.endswith('.java'):
                continue
            text = open(os.path.join(dirpath, fn), encoding='utf-8').read()
            for m in JAVA_RECORD_RE.finditer(text):
                fields = []
                for seg in split_top(m.group(2)):
                    clean = JAVA_ANNOS.sub('', seg).strip()
                    if not clean:
                        continue
                    wire = None
                    wm = re.search(r'@JsonProperty\("([^"]+)"\)', seg)
                    if wm:
                        wire = wm.group(1)
                    parts = clean.split()
                    if len(parts) >= 2:
                        fields.append({"name": wire or parts[-1],
                                       "type": ' '.join(parts[:-1])})
                if fields:
                    idx[m.group(1)] = fields
            # POJO 字段（类名单独取）
            cm = re.search(r'\b(?:class|enum)\s+(\w+)', text)
            if not cm:
                continue
            fields = []
            for fm in JAVA_FIELD_RE.finditer(text):
                wire, ftype, fname = fm.groups()
                if fname.startswith('this$') or 'static' in ftype:
                    continue
                fields.append({"name": wire or fname, "type": ftype})
            if fields:
                idx[cm.group(1)] = fields
    return idx


WRAPPERS = ('ResponseEntity', 'Page', 'List', 'IPage', 'Optional', 'Set', 'Flux')


def resolve_fields(type_name: str, idx: dict):
    """剥常见包装泛型后取字段表（深度 1）；Map/动态结构返回 None。"""
    if not type_name:
        return None
    t = type_name.strip()
    for _ in range(3):
        base = t.split('<')[0].strip()
        if base in ('ResponseEntity', 'Page', 'List', 'IPage', 'Optional', 'Set',
                    'Flux') and '<' in t:
            t = t[t.index('<') + 1:].rstrip('>').strip()
            continue
        break
    base = t.split('<')[0].strip()
    return idx.get(base)


SIG_RE = re.compile(
    r'(?:public|protected|private)\s+([\w<>,.\[\]?]+?)\s+(\w+)\s*\(')


def method_block(text: str, anno_pos: int, next_pos: int):
    """mapping 注解起到下一 mapping/类尾的片段 + 参数串 + (返回类型, 方法名)。

    签名定位用可见性关键字（注解自身也可能带括号，find('(') 会撞上）。
    """
    block = text[anno_pos:next_pos if next_pos > anno_pos else len(text)]
    m = SIG_RE.search(block)
    if not m:
        return block, '', '', ''
    p0 = m.end() - 1  # 签名的 '('
    depth, i = 0, p0
    while i < len(block):
        if block[i] == '(':
            depth += 1
        elif block[i] == ')':
            depth -= 1
            if depth == 0:
                break
        i += 1
    params = block[p0 + 1:i]
    return block, params, m.group(1), m.group(2)


def find_body_target(block: str, file_text: str) -> dict:
    """rawBody 绑定目标：readValue 直连，否则 bind/parse helper 二级追踪。"""
    m = re.search(r'readValue\(\s*rawBody\s*,\s*([A-Za-z0-9_.]+)\.class', block)
    if m:
        return {"type": m.group(1).split('.')[-1]}
    m = re.search(r'\b((?:bind|parse)\w+)\(\s*rawBody', block)
    if m:
        helper = m.group(1)
        # 同文件找 helper 方法定义体
        hm = re.search(r'\w+\s+' + helper + r'\s*\([^)]*\)\s*\{', file_text)
        if hm:
            body = file_text[hm.end():hm.end() + 3000]
            m2 = re.search(r'readValue\([^,]*,[\s\n]*([A-Za-z0-9_.]+)\.class', body)
            if m2:
                return {"type": m2.group(1).split('.')[-1]}
    return {"type": "JSON（控制器内手工绑定）"}


def parse_params(params: str, block: str, file_text: str):
    path_vars, query, others = [], [], []
    body = None
    for seg in split_top(params):
        if not seg.strip():
            continue
        kind = None
        name_m = re.search(r'@(?:PathVariable|RequestParam|RequestBody|RequestHeader)'
                           r'(?:\([^)]*\))?', seg)
        if not name_m:
            continue
        anno = name_m.group(0)
        if 'PathVariable' in anno:
            kind = 'path'
        elif 'RequestParam' in anno:
            kind = 'query'
        elif 'RequestBody' in anno:
            kind = 'body'
        elif 'RequestHeader' in anno:
            kind = 'header'
        clean = JAVA_ANNOS.sub('', seg).strip()
        parts = clean.split()
        var = parts[-1] if parts else ''
        jtype = ' '.join(parts[:-1]) or 'String'
        val = re.search(r'value\s*=\s*"([^"]+)"', anno)
        nm = re.search(r'^"([^"]+)"', anno.strip().lstrip('@PathVariable')
                       .lstrip('@RequestParam').strip())
        pname = (val.group(1) if val else (nm.group(1) if nm else var))
        req = 'required = false' not in anno
        dv = re.search(r'defaultValue\s*=\s*"([^"]*)"', anno)
        if kind == 'path':
            path_vars.append({"name": pname, "type": jtype})
        elif kind == 'query':
            query.append({"name": pname, "type": jtype, "required": req,
                          "default": dv.group(1) if dv else None})
        elif kind == 'body':
            body = {"declared": jtype, "var": var}
        else:
            others.append({"name": pname, "type": jtype})
    if body is not None:
        if body['declared'] == 'String':
            target = find_body_target(block, file_text)
            body = {"declared": "JSON body", "bindType": target.get('type')}
        else:
            body = {"declared": body['declared'], "bindType": body['declared']}
    return path_vars, query, body, others


def file_routes(full_path, type_idx):
    """对单个控制器文件做细粒度扫描：(verb, normpath) → 详情。"""
    out = {}
    if not os.path.exists(full_path):
        return out
    text = open(full_path, encoding="utf-8").read()
    class_prefix = ""
    cm = re.search(r'@(?:[\w.]+\.)?RequestMapping\s*\(\s*(?:value\s*=\s*)?'
                   r'"(/api[^"]*)"', text)
    if cm:
        class_prefix = cm.group(1).rstrip("/")
    anno_iter = list(rr.JAVA_METHOD_RE.finditer(text))
    anno_iter += list(rr.JAVA_REQ_METHOD_RE.finditer(text))
    anno_iter.sort(key=lambda m: m.start())
    for i, m in enumerate(anno_iter):
        anno_pos = m.start()
        next_pos = anno_iter[i + 1].start() if i + 1 < len(anno_iter) else len(text)
        block, params, rt, name = method_block(text, anno_pos, next_pos)
        vm = re.search(r'@(?:[\w.]+\.)?(Get|Post|Put|Delete|Patch)Mapping', block)
        rm2 = re.search(r'method\s*=\s*\{?([^)}]*)', block) if 'RequestMapping' in block.split('(')[0] else None
        verbs = []
        sig = SIG_RE.search(block)
        header = block[:sig.start()] if sig else block
        if vm and 'Mapping' in block[:vm.end()]:
            verbs = [vm.group(1).upper()]
            paths = rr._paths_in(header) or [""]
        elif rm2:
            for v in re.findall(r'RequestMethod\.(\w+)', block):
                verbs.append(v.upper())
            paths = rr._paths_in(header) or [""]
        if not verbs or not paths:
            continue
        handler, desc = handler_hint(text, anno_pos)
        path_vars, query, body, others = parse_params(params, block, text)
        if body is not None and body.get('bindType'):
            flds = type_idx.get(body['bindType'])
            if flds:
                body['fields'] = flds
        resp = {"type": rt}
        flds = resolve_fields(rt, type_idx)
        if flds:
            resp['fields'] = flds
        if 'HttpServletResponse' in params or 'SseEmitter' in params:
            resp['sse'] = True
        for v in verbs:
            for pth in paths:
                full = pth if pth.startswith('/api/') else \
                    class_prefix + ('/' + pth.lstrip('/') if pth else '')
                out[(v, rr.norm(full))] = {
                    "handler": handler, "description": desc, "line": None,
                    "request": {"pathVars": path_vars, "query": query, "body": body},
                    "response": resp, "paramsRaw": params[:200],
                }
    return out


def collect(java_root: str):
    base_dir = os.path.join(java_root, "server", "src", "main", "java", "com", "ragagent")
    routes = rr.parse_java(java_root)

    # RBAC 规则
    rbac = {}
    webconfig = os.path.join(base_dir, "config", "WebConfig.java")
    if os.path.exists(webconfig):
        text = open(webconfig, encoding="utf-8").read()
        for m in RBAC_RULE_RE.finditer(text):
            method, pattern, role, or_admin = m.groups()
            rbac[(method, norm_rbac(pattern))] = {
                "minRole": role, "orSystemAdmin": or_admin == "true"}

    # API-Key 策略（先收集变量定义，再收集 register 调用）
    varmap = {}
    policies = {}
    pkg = os.path.join(base_dir, "apikey", "filter")
    if os.path.isdir(pkg):
        for fn in os.listdir(pkg):
            if not fn.endswith(".java"):
                continue
            text = open(os.path.join(pkg, fn), encoding="utf-8").read()
            for m in APIKEY_VAR_DEF_RE.finditer(text):
                varmap[m.group(1)] = policy_text(m.group(2), {})
            for m in APIKEY_REGISTER_RE.finditer(text):
                method, path_expr, pol_expr = m.groups()
                pm = JAVA_STR_RE.search(path_expr)
                if not pm:
                    continue
                key = (method, apikey_norm(pm.group(1)))
                policies[key] = policy_text(pol_expr, varmap)

    type_idx = build_type_index(java_root)

    detail_cache = {}
    def details_for(full_path):
        if full_path not in detail_cache:
            detail_cache[full_path] = file_routes(full_path, type_idx)
        return detail_cache[full_path]

    groups = defaultdict(list)
    for (verb, path), sources in sorted(routes.items()):
        source = sorted(sources)[0]
        rel, line = source.rsplit(":", 1)
        full_path = os.path.join(base_dir, rel)
        handler, desc = "", ""
        detail = None
        if os.path.exists(full_path):
            text = open(full_path, encoding="utf-8").read()
            try:
                anno = text.index(verb.capitalize() + "Mapping", 0)
            except ValueError:
                anno = 0
            # 在该文件中找此行附近的注解位置（parse_java 已给出行号）
            lines = text.splitlines()
            if 0 < int(line) <= len(lines):
                acc = 0
                for i, ln in enumerate(lines):
                    if i + 1 == int(line):
                        pos = acc
                        handler, desc = handler_hint(text, pos)
                        break
                    acc += len(ln) + 1
        det_map = details_for(full_path) if os.path.exists(full_path) else {}
        detail = det_map.get((verb, path))
        segs = [x for x in path.split("/") if x]
        # 剥掉 /api/v1 前缀取首个业务段；形如 /{} 的解析噪声归 other
        if segs[:2] == ["api", "v1"]:
            segs = segs[2:]
        elif segs[:1] == ["api"]:
            segs = segs[1:]
        domain = segs[0] if segs and not segs[0].startswith("{}") else "other"
        rb = rbac.get((verb, path))
        ak = policies.get((verb, path))
        groups[domain].append({
            "method": verb,
            "path": path,
            "source": source,
            "handler": handler,
            "description": desc,
            "rbac": rb if rb else None,
            "apiKey": ak,
            "request": (detail or {}).get("request"),
            "response": (detail or {}).get("response"),
        })
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--java", default=REPO)
    ap.add_argument("--out", default=os.path.join(REPO, "docs", "site", "api"))
    args = ap.parse_args()

    groups = collect(args.java)
    total = sum(len(v) for v in groups.values())
    doc = {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "total": total,
        "groups": [
            {"domain": d,
             "label": DOMAIN_LABELS.get(d, d),
             "routes": sorted(groups[d],
                              key=lambda r: (r["path"], VERB_ORDER.get(r["method"], 9)))}
            for d in sorted(groups)
        ],
    }
    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "api-docs.json")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    html = build_html(doc)
    html_path = os.path.join(args.out, "index.html")
    with open(html_path, "w", encoding="utf-8") as fh:
        fh.write(html)
    print(f"生成完成: {total} 条路由 → {html_path}")


def build_html(doc: dict) -> str:
    data = json.dumps(doc, ensure_ascii=False).replace("</", "<\\/")
    return HTML_TEMPLATE.replace("__DATA__", data)


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ragagent-java · API 文档</title>
<style>
  :root {
    --bg: #ffffff; --panel: #fafafa; --line: #e4e4e7; --muted: #f4f4f5;
    --text: #09090b; --dim: #71717a; --accent: #18181b;
    --get: #1d4ed8; --post: #15803d; --put: #b45309; --patch: #7c3aed;
    --delete: #b91c1c; --head: #71717a;
    --ease: cubic-bezier(.16, 1, .3, 1);
  }
  [data-theme="dark"] {
    --bg: #09090b; --panel: #101012; --line: #27272a; --muted: #1c1c1f;
    --text: #fafafa; --dim: #a1a1aa; --accent: #fafafa;
    --get: #93c5fd; --post: #86efac; --put: #fcd34d; --patch: #c4b5fd;
    --delete: #fca5a5; --head: #a1a1aa;
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) {
      --bg: #09090b; --panel: #101012; --line: #27272a; --muted: #1c1c1f;
      --text: #fafafa; --dim: #a1a1aa; --accent: #fafafa;
      --get: #93c5fd; --post: #86efac; --put: #fcd34d; --patch: #c4b5fd;
      --delete: #fca5a5; --head: #a1a1aa;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text);
         font: 14px/1.65 -apple-system, BlinkMacSystemFont, "PingFang SC", "Segoe UI", sans-serif; }
  header { position: sticky; top: 0; z-index: 5; padding: 18px 24px 12px;
           border-bottom: 1px solid var(--line);
           background: color-mix(in oklab, var(--bg) 86%, transparent);
           backdrop-filter: blur(8px); }
  h1 { margin: 0 0 3px; font-size: 18px; font-weight: 650; letter-spacing: -.01em; }
  .meta { color: var(--dim); font-size: 12px; }
  #q { margin-top: 10px; width: 100%; max-width: 560px; padding: 7px 12px;
      background: var(--muted); color: var(--text); border: 1px solid transparent;
      border-radius: 8px; outline: none; font: inherit; font-size: 13px; }
  #q:focus { background: var(--bg); border-color: var(--line); }
  .wrap { display: flex; gap: 24px; padding: 18px 24px 64px; }
  nav { width: 208px; flex: none; position: sticky; top: 108px; align-self: flex-start;
        max-height: calc(100vh - 130px); overflow: auto; }
  nav a { display: flex; justify-content: space-between; gap: 8px; color: var(--dim);
          text-decoration: none; padding: 5px 8px; border-radius: 8px; font-size: 13px;
          transition: color .16s var(--ease), background-color .16s var(--ease); }
  nav a:hover { background: var(--muted); color: var(--text); }
  main { flex: 1; min-width: 0; }
  section h2 { font-size: 15px; font-weight: 600; margin: 26px 0 10px; padding-bottom: 8px;
              border-bottom: 1px solid var(--line); }
  details { background: var(--panel); border: 1px solid var(--line);
           border-radius: 10px; margin: 6px 0; overflow: hidden; }
  summary { cursor: pointer; padding: 9px 12px; display: flex; gap: 10px;
           align-items: center; list-style: none; flex-wrap: wrap; }
  summary::-webkit-details-marker { display: none; }
  .m { flex: none; padding: 1px 7px; border: 1px solid; border-radius: 6px;
      font: 600 11px/1.7 ui-monospace, Menlo, Consolas, monospace; background: transparent; }
  .m.GET { color: var(--get); border-color: color-mix(in oklab, var(--get) 45%, transparent);
          background: color-mix(in oklab, var(--get) 10%, transparent); }
  .m.POST { color: var(--post); border-color: color-mix(in oklab, var(--post) 45%, transparent);
           background: color-mix(in oklab, var(--post) 10%, transparent); }
  .m.PUT { color: var(--put); border-color: color-mix(in oklab, var(--put) 45%, transparent);
          background: color-mix(in oklab, var(--put) 10%, transparent); }
  .m.PATCH { color: var(--patch); border-color: color-mix(in oklab, var(--patch) 45%, transparent);
            background: color-mix(in oklab, var(--patch) 10%, transparent); }
  .m.DELETE { color: var(--delete); border-color: color-mix(in oklab, var(--delete) 45%, transparent);
             background: color-mix(in oklab, var(--delete) 10%, transparent); }
  .m.HEAD { color: var(--head); border-color: color-mix(in oklab, var(--head) 45%, transparent);
           background: color-mix(in oklab, var(--head) 10%, transparent); }
  .p { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12.5px; }
  .desc { color: var(--dim); font-size: 12px; }
  .tag { font-size: 11px; color: var(--dim); border: 1px solid var(--line);
        padding: 1px 7px; border-radius: 999px; background: var(--bg); }
  .body { padding: 6px 14px 14px; border-top: 1px solid var(--line); font-size: 13px; }
  .body dt { color: var(--dim); font-size: 11px; margin-top: 10px;
            text-transform: uppercase; letter-spacing: .05em; }
  .body dd { margin: 3px 0 0; font-family: ui-monospace, Menlo, Consolas, monospace;
            font-size: 12px; word-break: break-all; }
  .count { color: var(--dim); font-weight: 400; font-size: 12px; }
  .empty { color: var(--dim); padding: 30px 0; text-align: center; display: none; }
  .sect { margin-top: 10px; }
  .sect b { color: var(--dim); font-size: 11px; letter-spacing: .06em; }
  table.sub { width: 100%; margin: 4px 0 10px; font-size: 12px; border: none;
              border-collapse: collapse; }
  table.sub th { color: var(--dim); font-weight: 500; text-align: left;
                padding: 4px 8px; border-bottom: 1px solid var(--line); }
  table.sub td { padding: 4px 8px; border-bottom: 1px solid var(--line);
                font-family: ui-monospace, Menlo, Consolas, monospace; }
  table.sub tr:last-child td { border-bottom: none; }
  .note { color: var(--dim); font-size: 12px; }
</style>
</head>
<body>
<header>
  <h1>ragagent-java · API 文档 <span class="count" id="total"></span></h1>
  <div class="meta">由 scripts/generate-api-docs.py 从控制器注解生成 ·
    权限来自 WebConfig(RBAC) 与 APIKeyRoutePolicies ·
    生成时间 <span id="gen"></span></div>
  <input id="q" type="search" placeholder="搜索路径 / 处理器 / 说明…（支持正则）">
</header>
<div class="wrap">
  <nav id="nav"></nav>
  <main><div class="empty" id="empty">无匹配路由</div><div id="content"></div></main>
</div>
<script>
const DATA = __DATA__;
document.getElementById('total').textContent = '· ' + DATA.total + ' 条路由';
document.getElementById('gen').textContent = DATA.generatedAt;

const METHOD_ORDER = {GET:0, POST:1, PUT:2, PATCH:3, DELETE:4, HEAD:5};

function esc(s) { return String(s == null ? '' : s)
  .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function fieldTable(fields) {
  if (!fields || !fields.length) return '';
  return '<table class="sub"><tr><th>字段</th><th>类型</th></tr>'
    + fields.map(f => '<tr><td>' + esc(f.name) + '</td><td>' + esc(f.type)
      + '</td></tr>').join('') + '</table>';
}

function reqRespHtml(r) {
  const req = r.request, resp = r.response;
  if (!req && !resp) return '';
  let h = '<div class="body">';
  if (req) {
    const pv = req.pathVars || [], q = req.query || [], b = req.body;
    h += '<div class="sect"><b>入参</b></div>';
    if (!pv.length && !q.length && !b) {
      h += '<div class="note">无显式入参</div>';
    } else {
      if (pv.length) {
        h += '<div class="sect"><b>路径参数</b></div><table class="sub">'
          + '<tr><th>参数</th><th>类型</th></tr>'
          + pv.map(p => '<tr><td>' + esc(p.name) + '</td><td>' + esc(p.type)
            + '</td></tr>').join('') + '</table>';
      }
      if (q.length) {
        h += '<div class="sect"><b>Query 参数</b></div><table class="sub">'
          + '<tr><th>参数</th><th>类型</th><th>必填</th><th>缺省</th></tr>'
          + q.map(p => '<tr><td>' + esc(p.name) + '</td><td>' + esc(p.type)
            + '</td><td>' + (p.required ? '是' : '否')
            + '</td><td>' + esc(p.default == null ? '—' : p.default)
            + '</td></tr>').join('') + '</table>';
      }
      if (b) {
        h += '<div class="sect"><b>请求体</b><span class="note"> '
          + esc(b.declared) + (b.bindType && b.bindType !== b.declared
            ? ' · 绑定 ' + esc(b.bindType) : '') + '</span></div>'
          + fieldTable(b.fields);
        if (!b.fields) h += '<div class="note">字段由控制器内解析（raw JSON），'
          + '结构见对应服务实现或上游 API 文档。</div>';
      }
    }
  }
  if (resp) {
    h += '<div class="sect"><b>响应</b></div>';
    if (resp.sse) h += '<div class="note">SSE 流式响应（text/event-stream）</div>';
    h += '<div class="note">声明类型：<code>' + esc(resp.type || '—') + '</code></div>';
    if (resp.fields) h += fieldTable(resp.fields);
    else if (!resp.sse) h += '<div class="note">动态结构（Map/包装类型），'
      + '字段以实际实现为准。</div>';
  }
  return h + '</div>';
}

function render(q) {
  let rx = null;
  if (q) { try { rx = new RegExp(q, 'i'); } catch (e) { rx = new RegExp(
      q.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'), 'i'); } }
  const hit = r => !rx || rx.test(r.path) || rx.test(r.handler)
      || rx.test(r.description) || rx.test(r.method);
  const nav = document.getElementById('nav');
  const content = document.getElementById('content');
  nav.innerHTML = ''; content.innerHTML = '';
  let shown = 0;
  for (const g of DATA.groups) {
    const routes = g.routes.filter(hit);
    if (!routes.length) continue;
    shown += routes.length;
    const a = document.createElement('a');
    a.href = '#' + g.domain;
    a.innerHTML = '<span>' + esc(g.label) + '</span><span>' + routes.length + '</span>';
    nav.appendChild(a);
    const sec = document.createElement('section');
    sec.id = g.domain;
    sec.innerHTML = '<h2>' + esc(g.label) + ' <span class="count">/api/v1/'
        + esc(g.domain) + ' · ' + routes.length + '</span></h2>';
    for (const r of routes.sort((x, y) =>
        (x.path < y.path ? -1 : x.path > y.path ? 1 :
         (METHOD_ORDER[x.method]||9) - (METHOD_ORDER[y.method]||9)))) {
      const d = document.createElement('details');
      const rbac = r.rbac
        ? '<span class="tag">RBAC ' + esc(r.rbac.minRole)
          + (r.rbac.orSystemAdmin ? ' / 系统管理员' : '') + '</span>'
        : '<span class="tag">RBAC 默认</span>';
      d.innerHTML = '<summary><span class="m ' + esc(r.method) + '">'
          + esc(r.method) + '</span><span class="p">' + esc(r.path)
          + '</span><span class="desc">' + esc(r.description || r.handler)
          + '</span>' + rbac
          + (r.apiKey ? '<span class="tag">API-Key: ' + esc(r.apiKey) + '</span>' : '')
          + '</summary>'
        + '<div class="body"><dl>'
        + '<dt>处理器</dt><dd>' + esc(r.handler || '—') + ' · ' + esc(r.source) + '</dd>'
        + '<dt>完整路径</dt><dd>' + esc(r.method) + ' /api/v1'
        + esc(r.path.replace(/^\\/api\\/v1/, '')) + '</dd>'
        + (r.rbac ? '<dt>角色门槛</dt><dd>' + esc(r.rbac.minRole)
          + (r.rbac.orSystemAdmin ? '（或系统管理员）' : '') + '</dd>' : '')
        + (r.apiKey ? '<dt>API-Key 策略</dt><dd>' + esc(r.apiKey) + '</dd>' : '')
        + '</dl></div>'
        + reqRespHtml(r);
      sec.appendChild(d);
    }
    content.appendChild(sec);
  }
  document.getElementById('empty').style.display = shown ? 'none' : 'block';
}
document.getElementById('q').addEventListener('input', e => render(e.target.value.trim()));
render('');
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()

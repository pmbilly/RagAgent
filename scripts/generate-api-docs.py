#!/usr/bin/env python3
"""生成 API 文档：解析 Java 控制器注解 + RBAC/API-Key 策略，输出静态网页。

用法:  python3 scripts/generate-api-docs.py [--java /Users/billy/ragagent-java]

输出:
  docs/api/api-docs.json   结构化路由数据
  docs/api/index.html      自包含静态页（数据内嵌，双击即可打开；也写入 .json 供工具消费）

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

    groups = defaultdict(list)
    for (verb, path), sources in sorted(routes.items()):
        source = sorted(sources)[0]
        rel, line = source.rsplit(":", 1)
        full_path = os.path.join(base_dir, rel)
        handler, desc = "", ""
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
        })
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--java", default=REPO)
    ap.add_argument("--out", default=os.path.join(REPO, "docs", "api"))
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
    --bg: #0f1420; --panel: #171e2e; --line: #26304a; --text: #dbe2f0;
    --dim: #8b96ad; --accent: #5aa2ff;
    --get: #3fb27f; --post: #d9a03f; --put: #5a8bdc; --patch: #a77bdc; --delete: #d95f5f;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text);
         font: 14px/1.6 -apple-system, "PingFang SC", "Segoe UI", sans-serif; }
  header { padding: 22px 28px 14px; border-bottom: 1px solid var(--line);
           position: sticky; top: 0; background: var(--bg); z-index: 5; }
  h1 { margin: 0 0 2px; font-size: 19px; }
  .meta { color: var(--dim); font-size: 12px; }
  #q { margin-top: 10px; width: 100%; max-width: 560px; padding: 8px 12px;
       background: var(--panel); color: var(--text); border: 1px solid var(--line);
       border-radius: 8px; outline: none; }
  #q:focus { border-color: var(--accent); }
  .wrap { display: flex; gap: 20px; padding: 18px 28px 60px; }
  nav { width: 200px; flex: none; position: sticky; top: 118px; align-self: flex-start;
        max-height: calc(100vh - 140px); overflow: auto; }
  nav a { display: flex; justify-content: space-between; color: var(--dim);
          text-decoration: none; padding: 4px 8px; border-radius: 6px; font-size: 13px; }
  nav a:hover { background: var(--panel); color: var(--text); }
  main { flex: 1; min-width: 0; }
  section h2 { font-size: 15px; margin: 26px 0 8px; padding-bottom: 6px;
               border-bottom: 1px solid var(--line); }
  details { background: var(--panel); border: 1px solid var(--line);
            border-radius: 8px; margin: 6px 0; }
  summary { cursor: pointer; padding: 8px 12px; display: flex; gap: 10px;
            align-items: center; list-style: none; flex-wrap: wrap; }
  summary::-webkit-details-marker { display: none; }
  .m { font: 11px/1 ui-monospace, Menlo, monospace; color: #fff; padding: 2px 7px;
       border-radius: 5px; font-weight: 700; flex: none; }
  .m.GET { background: var(--get); } .m.POST { background: var(--post); }
  .m.PUT { background: var(--put); } .m.PATCH { background: var(--patch); }
  .m.DELETE { background: var(--delete); } .m.HEAD { background: #6b768c; }
  .p { font-family: ui-monospace, Menlo, monospace; font-size: 13px; }
  .desc { color: var(--dim); font-size: 12px; }
  .tag { font-size: 11px; color: var(--dim); border: 1px solid var(--line);
         padding: 1px 7px; border-radius: 99px; }
  .body { padding: 4px 14px 12px; border-top: 1px dashed var(--line);
          font-size: 13px; }
  .body dt { color: var(--dim); font-size: 11px; margin-top: 8px;
             text-transform: uppercase; letter-spacing: .05em; }
  .body dd { margin: 2px 0 0; font-family: ui-monospace, Menlo, monospace;
             font-size: 12px; word-break: break-all; }
  .count { color: var(--dim); font-weight: 400; font-size: 12px; }
  .empty { color: var(--dim); padding: 30px 0; text-align: center; display: none; }
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
        + '</dl></div>';
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

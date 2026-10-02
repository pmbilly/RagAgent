#!/usr/bin/env python3
"""换锚棘轮：非冻结面不许出现新的 snake JSON 键。

判据（§2）：**我们自己的** JSON 面（HTTP 请求/响应、自有 jsonb）键名＝Java 字段名（camel）。
第三方线格式（datasource connector、event/llm 载荷、langfuse、租户配置 jsonb、Doris 客户端、
图片信息、SearchParams 等）与**模型输出契约**、**既有内部 jsonb 状态**属冻结面，保留 snake。

本脚本扫描 main 代码里的三类键写入点，与基线（本文件内联）比对：
  ① `@JsonProperty("snake")` / `@JsonPropertyOrder({... "snake" ...})`
  ② `ObjectNode` 的 `put("snake"` / `set("snake"`
  ③ 帮手式写入：`putNonEmpty|putTrue|putAlways|putOmitEmpty(<任意>, "snake"`
基线只记录**已逐条复核**的例外；出现基线之外的新命中即失败（棘轮：只许减不许增）。

用法：python3 scripts/check-json-key-case.py [--list|--strict]
  默认＝报告（列出基线外命中但**退出 0**）；--strict＝闸门（有基线外命中即退出 1，
  供将来判定完成后接 CI 用——当前基线只含 3 组已逐条复核的例外，其余待判项
  （SQL 参数键假阳性、诊断载荷等）不能当作已复核例外入基线）。

**已知边界（诚实声明）**：本扫描器按文本模式匹配 `put/set("snake"` 等，**会命中 SQL 参数
Map / MyBatis 列名等非 JSON 键**（如 `*Repository` 的 `deleted_at`），故 `--list` 是**待判
清单**而非违规清单；棘轮模式（默认）只有在基线外**新增**命中时才失败，不会因为既有噪音而红。
判定某键是真债还是冻结/数据值时，**必须看消费者**（FE 读？夹具断言？第三方 API？）。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / 'server/src/main/java/com/ragagent'

# 冻结面（第三方线格式/租户配置/模型载荷等）——整目录豁免，理由见 HANDOFF §15.3。
FROZEN_PREFIXES = (
    'datasource/connector', 'event/', 'llm/', 'stream/', 'auth/domain/tenantconfig',
    'common/pipeline/SearchParams', 'mcp/oauth', 'memory/service/MemoryExtractionLlm',
    'memory/service/MemoryExtractPayload', 'docreader', 'rerank/RankResult',
    'tracing/langfuse', 'retrieval/engine/doris', 'retrieval/domain/ImageInfo',
    'common/wiki/ExtractedItem', 'chatpipeline/plugin', 'agent/AgentEngine',
    'agent/ReActIteration', 'retrieval/HybridSearchService', 'retrieval/vlm/VlmClient',
    'knowledge/service/ChunkExtractService', 'knowledge/task/KnowledgeProcessWorker',
    'memory/mapper', 'agent/tools/', 'knowledge/domain/KnowledgeBase', 'mcp/domain/McpService',
    # 上游 API 载荷的适配器族（embedding/im/websearch/rerank/asr/vlm 的 provider 与客户端：
    # 键名由对方 API 定，冻结）
    'embedding/provider', 'im/', 'websearch/provider', 'rerank/', 'asr/', 'vlm/',
    'retrieval/vlm', 'storage/provider',
)

# 基线：已逐条复核的例外（文件相对路径 → 允许的键集合）。新增即失败。
BASELINE: dict[str, set[str]] = {
    # langfuse 线上字段（第三方契约，出站载荷即此形态）
    'common/context/TracingContext.java': {'lf_trace_id', 'lf_parent_obs_id', 'lf_traceparent',
                                           'lf_user_id', 'lf_session_id'},
    # 模型输出契约（提示词里就写 new_slugs；该 DTO 只解析入站，从不序列化出站）
    'wiki/service/ingest/WikiIngestCitePipeline.java': {'new_slugs'},
    # 既有内部 jsonb 状态键（改名需迁移存量行，登记不动）
    'wiki/service/ingest/WikiIngestMapPhase.java': {'new_slugs'},
}

PATTERNS = (
    re.compile(r'@JsonProperty\("([a-z0-9]+(?:_[a-z0-9]+)+)"\)'),
    re.compile(r'@JsonPropertyOrder\(\{([^}]*)\}\)'),
    re.compile(r'\.(?:put|set)\("([a-z0-9]+(?:_[a-z0-9]+)+)"\s*,'),
    re.compile(r'\b(?:putNonEmpty|putTrue|putAlways|putOmitEmpty)\([^,"]*,\s*'
               r'"([a-z0-9]+(?:_[a-z0-9]+)+)"'),
)
SNAKE_IN_LIST = re.compile(r'"([a-z0-9]+(?:_[a-z0-9]+)+)"')


def hits() -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for path in sorted(MAIN.rglob('*.java')):
        rel = str(path.relative_to(MAIN))
        if rel.startswith(FROZEN_PREFIXES):
            continue
        text = path.read_text(encoding='utf-8', errors='ignore')
        keys: set[str] = set()
        for m in PATTERNS[0].finditer(text):
            keys.add(m.group(1))
        for m in PATTERNS[1].finditer(text):          # @JsonPropertyOrder({...})
            keys.update(SNAKE_IN_LIST.findall(m.group(1)))
        for pattern in PATTERNS[2:]:
            for m in pattern.finditer(text):
                keys.add(m.group(1))
        if keys:
            found[rel] = keys
    return found


def main() -> int:
    found = hits()
    violations = {f: sorted(k - BASELINE.get(f, set())) for f, k in found.items()}
    violations = {f: k for f, k in violations.items() if k}
    stale = sorted(f for f in BASELINE if f not in found)

    if '--list' in sys.argv:
        for f, keys in sorted(found.items()):
            mark = '基线' if f in BASELINE else '**新增**'
            print(f'  [{mark}] {f}: {sorted(keys)}')
        return 0

    if violations and '--strict' not in sys.argv:
        print(f'ℹ 待判清单：{len(violations)} 个文件有基线外的 snake 键 '
              f'（未复核为真债，也未被入基线；详见 --list）。默认不判失败。')
        return 0
    if violations:
        print('✗ 非冻结面出现新的 snake JSON 键（§2：自己的 JSON 面用 camel）：')
        for f, keys in sorted(violations.items()):
            print(f'    {f}: {keys}')
        print('  → 改 camel（同一提交带上前端与夹具）；确属第三方/模型契约则加入本脚本 BASELINE 并写明理由。')
        return 1
    note = f'（基线 {sum(len(v) for v in BASELINE.values())} 条，均已逐条复核）'
    if stale:
        note += f'；注意基线中有 {len(stale)} 个文件已无命中，可清理：{stale[:3]}'
    print(f'✓ 无新增 snake JSON 键 {note}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

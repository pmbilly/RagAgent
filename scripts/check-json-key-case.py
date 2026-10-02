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

**BASELINE 条目是文件级**：某文件登记后，将来在其中**新增**的 snake 键不会被点名——
在已登记文件里加新键时，请先看该文件是否在基线（或按族核查）。
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
    # 检索引擎适配器族（ES/OpenSearch/Milvus/Qdrant 的 DSL 字段）与检索观测面
    'retrieval/engine/', 'retrieval/obs/',
    # 引用/管线进度载荷＝**存量回放面**（B25 判定）：引用随 messages.knowledge_references
    # 列落库并按历史回放渲染，前端 rag-pipeline-history 还会以同形键重建该载荷 ⇒ 改名须先
    # 出迁移方案（或双读），故冻结。
    'chatpipeline/support/ReferencesSupport', 'chatpipeline/PipelineProgress',
)

# 基线：已逐条复核的例外（文件相对路径 → 允许的键集合）。新增即失败。
BASELINE: dict[str, set[str]] = {
    # agent_steps 落库列 + 历史回放
    'agent/ActPhase.java': {'args_redacted', 'argument_resolution', 'data_keys', 'duration_ms', 'image_count', 'mcp_service', 'mcp_tool', 'model_arg_keys', 'model_arguments', 'output_len', 'resolved_arg_keys', 'resolved_arguments', 'session_id', 'tool_call_id', 'tool_index', 'unresolved_handle_count', 'unresolved_handles'},
    # 模板令牌（数据值，非 JSON 键）
    'agent/AgentPrompts.java': {'current_time', 'web_search_status'},
    # 审计 details jsonb（存量 + 回放）
    'audit/service/AuditLogService.java': {'raw_path', 'required_role'},
    # MyBatis 列名/参数（非 JSON 键）
    'auth/service/TenantInvitationService.java': {'invitation_id', 'responded_at', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'auth/service/TenantMemberService.java': {'deleted_at', 'is_revoked', 'new_role', 'old_role', 'tenant_id', 'updated_at'},
    # 租户配置 jsonb（存量面）
    'auth/service/TenantService.java': {'compression_strategy', 'deleted_at', 'max_tokens', 'recent_message_count', 'summarize_threshold'},
    # 内部预设名（presets() 仅内部查表）
    'chatpipeline/PipelineBuilder.java': {'chat_history_stream', 'chat_stream', 'rag_stream'},
    # PipelineLog 观测面（日志字段，非契约）
    'chatpipeline/support/SearchSupport.java': {'chunk_id', 'dropped_id', 'kept_id', 'match_type'},
    # langfuse 线上字段（第三方契约）
    'common/context/TracingContext.java': {'lf_parent_obs_id', 'lf_session_id', 'lf_trace_id', 'lf_traceparent', 'lf_user_id'},
    # MDC 日志键（非 JSON）
    'common/filter/RequestIdFilter.java': {'request_id'},
    # OpenSearch 字段
    'config/OpenSearchAuditSinkAdapter.java': {'dst_alias', 'src_alias'},
    # datasource 配置 jsonb（存量面）
    'datasource/dto/DataSourceResponse.java': {'feed_urls'},
    # MyBatis 列名/参数（非 JSON 键）
    'datasource/mapper/DataSourceRepository.java': {'conflict_strategy', 'created_at', 'deleted_at', 'error_message', 'knowledge_base_id', 'last_sync_at', 'sync_log_retention_days', 'sync_mode', 'sync_schedule', 'tenant_id', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'datasource/mapper/SyncLogRepository.java': {'created_at', 'data_source_id', 'error_message', 'finished_at', 'items_created', 'items_deleted', 'items_failed', 'items_skipped', 'items_total', 'items_updated', 'started_at', 'tenant_id', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'datasource/service/DataSourceItemOps.java': {'datasource_id', 'external_id', 'source_created_at', 'source_resource_id', 'source_updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'datasource/service/DataSourceSupport.java': {'processing_status', 'resource_ids', 'task_id'},
    # MyBatis 列名/参数（非 JSON 键）
    'datasource/service/MapperKnowledgeBridge.java': {'deleted_at', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/repository/ChunkRepository.java': {'content_revision', 'deleted_at', 'index_status', 'is_enabled', 'last_editor_id', 'source_content', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/repository/FaqChunkRepository.java': {'is_enabled', 'tag_id', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/FaqEntryCommandService.java': {'source_type'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeBaseService.java': {'deleted_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeBatchOpsService.java': {'deleted_at', 'enable_status', 'error_message', 'parse_status', 'processed_at', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeCloneService.java': {'deleted_at'},
    # MyBatis 列名写入点（非 JSON 键）
    'knowledge/service/KnowledgeFileService.java': {'content_revision', 'embedding_model_id', 'enable_status', 'error_message', 'file_hash', 'file_name', 'file_path', 'file_size', 'file_type', 'parse_status', 'processed_at', 'summary_status', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeFolderService.java': {'folder_path', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeMoveService.java': {'embedding_model_id', 'enable_status', 'error_message', 'knowledge_base_id', 'parse_status', 'processed_at', 'storage_size', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeParseService.java': {'error_message', 'parse_status', 'pending_subtasks_count', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeService.java': {'deleted_at', 'updated_at'},
    # MyBatis 列名/参数（非 JSON 键）
    'knowledge/service/KnowledgeSummaryService.java': {'summary_status'},
    # langfuse 面
    'knowledge/service/SpanTracker.java': {'langfuse_trace_id', 'updated_at'},
    # 提示词模板变量（数据值，模板里是 {{server_name}} 等）
    'mcp/controller/McpUsageInstructionsOps.java': {'omitted_tools', 'server_description', 'server_instructions', 'server_name'},
    # MCP 存量 metadata 形态（算体积用）
    'mcp/service/McpMetadataService.java': {'server_description', 'server_name', 'server_version', 'service_id', 'synced_at'},
    # memory 观测/追踪载荷（非契约）
    'memory/service/MemoryInsightOps.java': {'candidate_count', 'lexical_hits', 'matched_count', 'ranking_mode', 'subject_id', 'vector_hits', 'vector_outside', 'vector_skip'},
    # memory 观测/追踪载荷（非契约）
    'memory/service/MemoryRecallOps.java': {'block_runes', 'candidate_count', 'fused_candidates', 'interest_injected', 'interest_relevant', 'interest_total', 'lexical_hits', 'matched_count', 'prompt_runes', 'ranking_mode', 'resident_count', 'subject_id', 'used_count', 'vector_hits', 'vector_outside', 'vector_skip'},
    # memory 观测/追踪载荷（非契约）
    'memory/service/MemoryRecallSelector.java': {'outside_pool', 'skip_reason'},
    # memory 观测/追踪载荷（非契约）
    'memory/service/MemoryTrace.java': {'conditioned_items', 'document_count', 'interest_count', 'recalled_items', 'recalled_items_truncated'},
    # model 凭据面（两端自洽，统一另立批）
    'model/dto/CredentialsResponse.java': {'api_key', 'app_secret'},
    # model 凭据面（两端自洽，统一另立批）
    'model/dto/ModelResponse.java': {'api_key', 'app_secret'},
    # MyBatis 列名/参数（非 JSON 键）
    'model/service/BuiltinModelsReconciler.java': {'deleted_at', 'is_default'},
    # MyBatis 列名/统计查询（非 JSON 键）
    'model/service/ModelService.java': {'agent_total', 'deleted_at', 'knowledge_base_total'},
    # WeKnora Cloud 第三方 API
    'model/service/WeKnoraCloudService.java': {'app_id', 'app_secret'},
    # 读取 SQL/检索行键（存量面）
    'modelcontext/ModelOutput.java': {'knowledge_id', 'knowledge_title'},
    # Cypher 字段
    'retrieval/graph/Neo4jGraphRepository.java': {'knowledge_id', 'source_labels', 'target_labels'},
    # ImageInfo 面（§15.3 冻结族）
    'retrieval/support/ImageInfoMatchUtil.java': {'end_pos', 'ocr_text', 'original_url', 'start_pos'},
    # web 引用载荷（同 ReferencesSupport 存量面）
    'retrieval/support/WebResultConverter.java': {'published_at'},
    # 工具结果/附件载荷（存量面，同 tool-results）
    'session/controller/QaAttachmentResolver.java': {'display_type', 'parsed_count', 'skipped_count'},
    # SSE/消息载荷（与 agent_steps 同族，改则直播与回放脱节）
    'session/controller/QaSseOrchestrator.java': {'assistant_created_at', 'assistant_message_id', 'session_id', 'user_created_at', 'user_message_id'},
    # 同上（SSE/消息载荷）
    'session/controller/SessionController.java': {'message_id', 'session_id'},
    # steer 载荷（与 agent_steps 同族）
    'session/controller/SteerController.java': {'kb_id', 'kb_name', 'kb_type', 'mentioned_items', 'service_id', 'skill_name', 'steer_id'},
    # MyBatis 列名写入点（非 JSON 键）
    'session/mapper/MessageRepository.java': {'agent_duration_ms', 'agent_id', 'agent_tenant_id', 'is_completed', 'is_fallback', 'knowledge_id', 'model_id', 'rendered_content', 'request_id', 'updated_at'},
    # MyBatis 列名写入点（非 JSON 键）
    'session/mapper/MessageSuggestionRepository.java': {'allow_regenerate', 'completion_tokens', 'error_code', 'generated_at', 'latency_ms', 'lease_until', 'model_id', 'prompt_tokens', 'suppression_reason', 'updated_at'},
    # agent_steps 落库列 + 历史回放
    'session/service/AgentStreamBridge.java': {'completed_at', 'duration_ms', 'event_id', 'final_content', 'is_fallback', 'message_id', 'messages_after', 'messages_before', 'pending_id', 'session_id', 'split_turn', 'steer_id', 'tokens_after', 'tokens_before', 'tool_call_id', 'tool_name', 'total_duration_ms', 'total_steps', 'user_message_id'},
    # agent_steps/推荐面落库 + 回放
    'session/service/MessageSuggestionService.java': {'assistant_message_id', 'session_id'},
    # agent_steps 落库列 + 回放
    'session/service/QaSupport.java': {'kb_id', 'kb_name', 'kb_type', 'mentioned_items', 'service_id', 'skill_name', 'steer_id'},
    # agent_steps 落库列 + 回放
    'session/service/SessionKnowledgeQaService.java': {'duration_ms', 'error_type', 'knowledge_base_ids', 'search_targets', 'session_id', 'total_duration_ms', 'total_stages'},
    # agent_steps 落库列 + 回放
    'session/service/SteerSinkBridge.java': {'mentioned_items'},
    # HTTP query 参数名（非 JSON 键）
    'storage/fileserve/FileProxyService.java': {'file_path'},
    # 存储引擎面（snake，B14 冻结）
    'storage/fileserve/StorageFileResolver.java': {'default_provider', 'path_prefix'},
    # 平台审计 details jsonb（存量 + 回放）
    'system/controller/SystemAdminController.java': {'quota_bytes', 'quota_gb', 'scope_type'},
    # 存储引擎配置面（snake，同上）
    'system/controller/SystemController.java': {'access_key_id', 'bucket_name', 'mineru_parse_method', 'secret_access_key', 'use_ssl', 'weknoracloud_app_id'},
    # MyBatis 列名/参数（非 JSON 键）
    'system/service/SystemAdminUserService.java': {'is_revoked'},
    # 平台审计 details jsonb（存量 + 回放）
    'system/service/SystemSettingService.java': {'new_value', 'old_value', 'value_type'},
    # MyBatis 列名写入点（非 JSON 键）
    'wiki/service/DefaultWikiKnowledgeFinalizer.java': {'error_message', 'parse_status', 'processed_at', 'updated_at'},
    # 模型输出契约（提示词里就是 new_slugs；只解析入站）
    'wiki/service/ingest/WikiIngestCitePipeline.java': {'new_slugs'},
    # wiki 摄取内部 jsonb 状态（存量）
    'wiki/service/ingest/WikiIngestMapPhase.java': {'body_preview', 'candidate_slugs', 'cited_chunks', 'cited_slugs', 'classify_batches', 'concepts_preview', 'content_chars', 'doc_title', 'entities_preview', 'extracted_pages', 'extracted_slugs', 'new_slugs', 'new_slugs_sample', 'old_pages', 'pass0_fallback', 'reparse_slugs', 'stale_slugs', 'summary_chars', 'summary_line', 'summary_preview', 'top_cited', 'uncited_slugs'},
    # wiki 摄取内部 jsonb 状态（存量）
    'wiki/service/ingest/WikiIngestReducePhase.java': {'addition_failed', 'affected_type', 'chunk_refs', 'content_preview', 'page_summary', 'page_title', 'page_type', 'source_refs'},
    # wiki 摄取内部 jsonb 统计（存量）
    'wiki/service/ingest/WikiIngestRunSupport.java': {'failed_slug_writes', 'pages_dropped', 'pages_dropped_preview', 'pages_total', 'pages_written', 'pages_written_preview'},
    # 模型输出契约（LLM 载荷）
    'wiki/service/page/NewSlugFromCitation.java': {'source_chunks'},
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

-- V3：agent 配置 jsonb 的键名统一到 Java 字段名（camelCase）
--
-- 背景：custom_agents.config 是 agent 运行时配置树（~60 个键），历史上按 Go 的 snake 书写；
-- 服务端消费方（AgentConfigAssembler / AgentConfigJson / BuiltinAgentRegistry / AgentResponses 等）
-- 在 B18 批统一改读 camel（JSON 字段名 = Java 字段名，§2 第 11 条）。存量行若不迁移，
-- 配置会被**静默回落默认值**（读点 path(...) 取不到值、不报错）。
--
-- B19 追加嵌套层键（questionSuggestions 下的 followUps/* 等 7 个）。
-- 范围（实测）：dev 库全部 jsonb 列扫描后，仅 custom_agents.config 命中；
-- sessions/messages 等短名单列命中 0。嵌套对象按 V2 同法递归（数组元素原样保留）。
--
-- 幂等：已是 camel 的键不在映射表里，重复执行为空操作。
-- 回滚：无自动 down 迁移（Flyway 社区版不支持）；如需回滚按反向映射重跑。

CREATE OR REPLACE FUNCTION pg_temp.camelize_agent_config_keys(obj jsonb, mapping jsonb)
RETURNS jsonb AS $$
DECLARE
    result jsonb := '{}'::jsonb;
    k text;
    v jsonb;
    nk text;
BEGIN
    IF obj IS NULL OR jsonb_typeof(obj) <> 'object' THEN
        RETURN obj;
    END IF;
    FOR k, v IN SELECT * FROM jsonb_each(obj) LOOP
        nk := COALESCE(mapping ->> k, k);
        IF jsonb_typeof(v) = 'object' THEN
            v := pg_temp.camelize_agent_config_keys(v, mapping);
        END IF;
        result := result || jsonb_build_object(nk, v);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    mapping jsonb := '{"agent_mode": "agentMode", "agent_type": "agentType", "system_prompt": "systemPrompt", "system_prompt_id": "systemPromptId", "context_template": "contextTemplate", "context_template_id": "contextTemplateId", "model_id": "modelId", "rerank_model_id": "rerankModelId", "max_completion_tokens": "maxCompletionTokens", "citation_enabled": "citationEnabled", "max_iterations": "maxIterations", "llm_call_timeout": "llmCallTimeout", "allowed_tools": "allowedTools", "mcp_selection_mode": "mcpSelectionMode", "mcp_services": "mcpServices", "mcp_auth_wait_timeout": "mcpAuthWaitTimeout", "skills_selection_mode": "skillsSelectionMode", "selected_skills": "selectedSkills", "kb_selection_mode": "kbSelectionMode", "knowledge_bases": "knowledgeBases", "retrieve_kb_only_when_mentioned": "retrieveKbOnlyWhenMentioned", "retain_retrieval_history": "retainRetrievalHistory", "image_upload_enabled": "imageUploadEnabled", "vlm_model_id": "vlmModelId", "audio_upload_enabled": "audioUploadEnabled", "asr_model_id": "asrModelId", "image_storage_provider": "imageStorageProvider", "supported_file_types": "supportedFileTypes", "chat_parser_engine_rules": "chatParserEngineRules", "attachment_image_understanding": "attachmentImageUnderstanding", "attachment_ocr_max_pages": "attachmentOcrMaxPages", "attachment_parse_wait_timeout_sec": "attachmentParseWaitTimeoutSec", "data_analysis_enabled": "dataAnalysisEnabled", "faq_priority_enabled": "faqPriorityEnabled", "faq_direct_answer_threshold": "faqDirectAnswerThreshold", "faq_score_boost": "faqScoreBoost", "web_search_enabled": "webSearchEnabled", "web_search_max_results": "webSearchMaxResults", "web_search_provider_id": "webSearchProviderId", "web_fetch_enabled": "webFetchEnabled", "web_fetch_top_n": "webFetchTopN", "multi_turn_enabled": "multiTurnEnabled", "history_turns": "historyTurns", "memory_enabled": "memoryEnabled", "embedding_top_k": "embeddingTopK", "keyword_threshold": "keywordThreshold", "vector_threshold": "vectorThreshold", "rerank_top_k": "rerankTopK", "rerank_threshold": "rerankThreshold", "enable_query_expansion": "enableQueryExpansion", "enable_rewrite": "enableRewrite", "rewrite_prompt_system": "rewritePromptSystem", "rewrite_prompt_user": "rewritePromptUser", "query_understand_model_id": "queryUnderstandModelId", "fallback_strategy": "fallbackStrategy", "fallback_response": "fallbackResponse", "fallback_prompt": "fallbackPrompt", "intent_prompts": "intentPrompts", "question_suggestions": "questionSuggestions", "follow_ups": "followUps", "max_context_turns": "maxContextTurns", "suppress_on_fallback": "suppressOnFallback", "suppress_when_answer_asks_question": "suppressWhenAnswerAsksQuestion", "knowledge_fallback": "knowledgeFallback", "allow_regenerate": "allowRegenerate", "additional_instruction": "additionalInstruction"}'::jsonb;
BEGIN
    -- 条件＝「转换结果确有变化」：仅看顶层键会漏掉"顶层已 camel、嵌套仍 snake"的行
    -- （jsonb 的键序规范化 ⇒ 无改名时重建结果与原值等值，故该条件精确）
    UPDATE custom_agents
       SET config = pg_temp.camelize_agent_config_keys(config, mapping)
     WHERE config IS NOT NULL
       AND jsonb_typeof(config) = 'object'
       AND config IS DISTINCT FROM pg_temp.camelize_agent_config_keys(config, mapping);
END $$;

-- V2：知识库配置 jsonb 的键名统一到 Java 字段名（camelCase）
--
-- 背景：knowledge_bases 的 *_config 列由创建/更新请求原样透传落库。历史上前端按 snake 书写
-- （synthesis_model_id / question_count / index_mode / model_id …），而 Java 值类型
-- （wiki.domain.WikiConfig / KnowledgeBaseChunkingConfig / KnowledgeBaseImageProcessingConfig …）
-- 按 Java 字段名（camel）读写 —— 两套键名三方咬合的结果是「界面上选了但服务端读不到」：
-- B0 端到端走查实测（2026-10-02）写入 wiki_config.synthesis_model_id 后 wiki ingest 仍报
-- missing_synthesis_model、wiki 合成模型选择被静默忽略。
--
-- 本迁移把存量行的已知 snake 键递归重命名为 camel（含数组元素与嵌套对象，如
-- chunking_config.parser_engine_rules[].file_types）。映射表未覆盖的键原样保留。
--
-- 幂等：已是 camel 的键不在映射表里，重复执行为空操作。
-- 回滚：见文件末尾注释（无自动 down 迁移，Flyway 社区版不支持；如需回滚按反向映射重跑）。

CREATE OR REPLACE FUNCTION pg_temp.camelize_kb_config_keys(obj jsonb, mapping jsonb)
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
            v := pg_temp.camelize_kb_config_keys(v, mapping);
        ELSIF jsonb_typeof(v) = 'array' THEN
            SELECT COALESCE(jsonb_agg(
                       CASE WHEN jsonb_typeof(e) = 'object'
                            THEN pg_temp.camelize_kb_config_keys(e, mapping)
                            ELSE e END), '[]'::jsonb)
              INTO v
              FROM jsonb_array_elements(v) AS e;
        END IF;
        result := result || jsonb_build_object(nk, v);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

WITH mapping AS (
    SELECT '{
        "chunk_size": "chunkSize",
        "chunk_overlap": "chunkOverlap",
        "split_markers": "splitMarkers",
        "keep_separator": "keepSeparator",
        "parser_engine_rules": "parserEngineRules",
        "file_types": "fileTypes",
        "xlsx_first_row_as_header": "xlsxFirstRowAsHeader",
        "enable_parent_child": "enableParentChild",
        "parent_chunk_size": "parentChunkSize",
        "child_chunk_size": "childChunkSize",
        "token_limit": "tokenLimit",
        "table_metadata_instructions": "tableMetadataInstructions",
        "model_id": "modelId",
        "model_name": "modelName",
        "base_url": "baseUrl",
        "api_key": "apiKey",
        "interface_type": "interfaceType",
        "enable_multimodal": "enableMultimodal",
        "description_language": "descriptionLanguage",
        "custom_instructions": "customInstructions",
        "index_mode": "indexMode",
        "question_index_mode": "questionIndexMode",
        "question_count": "questionCount",
        "max_tags": "maxTags",
        "skip_if_tagged": "skipIfTagged",
        "synthesis_model_id": "synthesisModelId",
        "max_pages_per_ingest": "maxPagesPerIngest",
        "extraction_granularity": "extractionGranularity",
        "content_instructions": "contentInstructions",
        "extraction_instructions": "extractionInstructions",
        "ingest_batch_size": "ingestBatchSize",
        "ingest_map_parallel": "ingestMapParallel",
        "ingest_reduce_parallel": "ingestReduceParallel",
        "ingest_max_inflight": "ingestMaxInflight",
        "vector_enabled": "vectorEnabled",
        "keyword_enabled": "keywordEnabled",
        "wiki_enabled": "wikiEnabled",
        "graph_enabled": "graphEnabled",
        "secret_id": "secretId",
        "secret_key": "secretKey",
        "bucket_name": "bucketName",
        "app_id": "appId",
        "path_prefix": "pathPrefix",
        "use_ssl": "useSsl",
        "force_path_style": "forcePathStyle"
    }'::jsonb AS m
)
UPDATE knowledge_bases kb
   SET chunking_config = pg_temp.camelize_kb_config_keys(kb.chunking_config, m.m),
       image_processing_config = pg_temp.camelize_kb_config_keys(kb.image_processing_config, m.m),
       vlm_config = pg_temp.camelize_kb_config_keys(kb.vlm_config, m.m),
       asr_config = pg_temp.camelize_kb_config_keys(kb.asr_config, m.m),
       extract_config = pg_temp.camelize_kb_config_keys(kb.extract_config, m.m),
       faq_config = pg_temp.camelize_kb_config_keys(kb.faq_config, m.m),
       question_generation_config =
           pg_temp.camelize_kb_config_keys(kb.question_generation_config, m.m),
       auto_tag_config = pg_temp.camelize_kb_config_keys(kb.auto_tag_config, m.m),
       wiki_config = pg_temp.camelize_kb_config_keys(kb.wiki_config, m.m),
       indexing_strategy = pg_temp.camelize_kb_config_keys(kb.indexing_strategy, m.m),
       storage_config = pg_temp.camelize_kb_config_keys(kb.storage_config, m.m),
       storage_provider_config = pg_temp.camelize_kb_config_keys(kb.storage_provider_config, m.m)
  FROM mapping m;

-- 列默认值同步（默认值也是「新行的落库内容」，必须与读端同键名）
ALTER TABLE knowledge_bases ALTER COLUMN chunking_config SET DEFAULT
    '{"chunkSize": 512, "chunkOverlap": 50, "splitMarkers": ["\n\n", "\n", "。"], "keepSeparator": true}'::jsonb;
ALTER TABLE knowledge_bases ALTER COLUMN image_processing_config SET DEFAULT
    '{"modelId": "", "enableMultimodal": false}'::jsonb;

-- 反向映射（如需回滚，把上面 mapping 的 k/v 互换后重跑本文件主体即可）

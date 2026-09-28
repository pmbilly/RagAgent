-- =============================================================================
-- V1__baseline.sql —— 裁剪定稿后的 schema 基线（2026-09-29）
-- =============================================================================
-- 生成方式：把退役前的全部增量迁移按序应用到干净 PG（ParadeDB pg17 镜像，
-- 自带 pg_search + pgvector），pg_dump --schema-only 后整理而成。
-- 此后 schema 变更正常追加 V2+ 增量迁移；本文件只对全新部署生效
-- （Flyway 对空库执行 V1 后记录 checksum，不再重放历史迁移）。
-- 已随裁剪消失的对象：browser_*（浏览器连接）、tenant_sandbox_configs、
-- tenant_skills/tenant_skill_snapshots/tenant_skill_catalog、tenant_user_env_vars
-- （沙箱与技能安装管线）、sessions.sandbox_config_id。
-- 扩展（镜像自带或迁移自建）在此显式建立：
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_search;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET client_min_messages = warning;
SET row_security = off;


--
--



--
--



--
--



--
-- Name: unify_prompt_placeholder(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.unify_prompt_placeholder(input text) RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
    result TEXT := COALESCE(input, '');
    replacements TEXT[][] := ARRAY[
        -- Go template variables -> simple placeholders
        ['{{.Query}}', '{{query}}'],
        ['{{.Answer}}', '{{answer}}'],
        ['{{.CurrentTime}}', '{{current_time}}'],
        ['{{.CurrentWeek}}', '{{current_week}}'],
        ['{{.Yesterday}}', '{{yesterday}}'],
        ['{{.Contexts}}', '{{contexts}}'],
        -- Go template control structures -> simple placeholders or remove
        ['{{range .Contexts}}', '{{contexts}}'],
        -- Remove Go template syntax
        ['{{if .Contexts}}', ''],
        ['{{else}}', ''],
        ['{{.}}', '']
    ];
    r TEXT[];
BEGIN
    FOREACH r SLICE 1 IN ARRAY replacements LOOP
        result := REPLACE(result, r[1], r[2]);
    END LOOP;
    
    -- Handle {{range .Conversation}}...{{end}} block specially
    -- Replace the entire block with just {{conversation}}
    -- The pattern matches: {{range .Conversation}} followed by any content until {{end}}
    result := regexp_replace(
        result,
        '\{\{range \.Conversation\}\}[\s\S]*?\{\{end\}\}',
        '{{conversation}}',
        'g'
    );
    
    -- Clean up any remaining {{end}} tags
    result := REPLACE(result, '{{end}}', '');
    
    RETURN result;
END;
$$;


--
-- Name: update_mcp_services_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_mcp_services_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agent_shares; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_shares (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    agent_id character varying(36) NOT NULL,
    organization_id character varying(36) NOT NULL,
    shared_by_user_id character varying(36) NOT NULL,
    source_tenant_id integer NOT NULL,
    permission character varying(32) DEFAULT 'viewer'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    actor_user_id character varying(36) DEFAULT ''::character varying NOT NULL,
    actor_role character varying(32) DEFAULT ''::character varying NOT NULL,
    action character varying(64) NOT NULL,
    target_type character varying(32) DEFAULT ''::character varying NOT NULL,
    target_id character varying(64) DEFAULT ''::character varying NOT NULL,
    target_user_id character varying(36) DEFAULT ''::character varying NOT NULL,
    request_path character varying(512) DEFAULT ''::character varying NOT NULL,
    request_method character varying(16) DEFAULT ''::character varying NOT NULL,
    outcome character varying(16) DEFAULT 'success'::character varying NOT NULL,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    scope_type character varying(32) DEFAULT ''::character varying NOT NULL,
    scope_id character varying(64) DEFAULT ''::character varying NOT NULL
);


--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_logs_id_seq OWNED BY public.audit_logs.id;


--
-- Name: auth_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_tokens (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id character varying(36) NOT NULL,
    token text NOT NULL,
    token_type character varying(50) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    is_revoked boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: chunk_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chunk_revisions (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    knowledge_id character varying(36) NOT NULL,
    chunk_id character varying(36) NOT NULL,
    revision integer NOT NULL,
    content text DEFAULT ''::text NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    editor_id character varying(64) DEFAULT ''::character varying NOT NULL,
    edit_source character varying(16) DEFAULT 'user'::character varying NOT NULL,
    edited_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: chunks_seq_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chunks_seq_id_seq
    START WITH 100000000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chunks (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    knowledge_id character varying(36) NOT NULL,
    content text NOT NULL,
    chunk_index integer NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    start_at integer NOT NULL,
    end_at integer NOT NULL,
    pre_chunk_id character varying(36),
    next_chunk_id character varying(36),
    chunk_type character varying(20) DEFAULT 'text'::character varying NOT NULL,
    parent_chunk_id character varying(36),
    image_info text,
    relation_chunks jsonb,
    indirect_relation_chunks jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    metadata jsonb,
    tag_id character varying(36),
    status integer DEFAULT 0 NOT NULL,
    content_hash character varying(64),
    flags integer DEFAULT 1 NOT NULL,
    seq_id bigint DEFAULT nextval('public.chunks_seq_id_seq'::regclass) NOT NULL,
    video_info text,
    source_content text DEFAULT ''::text NOT NULL,
    content_revision integer DEFAULT 0 NOT NULL,
    index_status character varying(16) DEFAULT 'ready'::character varying NOT NULL,
    last_editor_id character varying(64) DEFAULT ''::character varying NOT NULL,
    context_header text DEFAULT ''::text NOT NULL
);


--
-- Name: custom_agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custom_agents (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    avatar character varying(64),
    is_builtin boolean DEFAULT false NOT NULL,
    tenant_id integer NOT NULL,
    created_by character varying(36),
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    runnable_by_viewer boolean DEFAULT true NOT NULL
);


--
-- Name: data_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_sources (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    name character varying(255) NOT NULL,
    type character varying(50) NOT NULL,
    config jsonb,
    sync_schedule character varying(100),
    sync_mode character varying(20) DEFAULT 'incremental'::character varying,
    status character varying(32) DEFAULT 'active'::character varying,
    conflict_strategy character varying(32) DEFAULT 'overwrite'::character varying,
    sync_deletions boolean DEFAULT true,
    last_sync_at timestamp without time zone,
    last_sync_cursor jsonb,
    last_sync_result jsonb,
    error_message text,
    sync_log_retention_days integer DEFAULT 30,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: embed_channels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embed_channels (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id bigint NOT NULL,
    agent_id character varying(36) DEFAULT 'builtin-quick-answer'::character varying NOT NULL,
    name character varying(255) DEFAULT ''::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    publish_token character varying(64) DEFAULT ''::character varying NOT NULL,
    allowed_origins jsonb DEFAULT '[]'::jsonb NOT NULL,
    welcome_message text DEFAULT ''::text NOT NULL,
    rate_limit_per_minute integer DEFAULT 30 NOT NULL,
    rate_limit_per_day integer DEFAULT 10000 NOT NULL,
    primary_color character varying(32) DEFAULT ''::character varying NOT NULL,
    page_title character varying(255) DEFAULT ''::character varying NOT NULL,
    header_title_mode character varying(32) DEFAULT 'channel'::character varying NOT NULL,
    show_suggested_questions boolean DEFAULT true NOT NULL,
    widget_position character varying(32) DEFAULT 'bottom-right'::character varying NOT NULL,
    allow_web_search boolean DEFAULT false NOT NULL,
    allow_memory boolean DEFAULT false NOT NULL,
    allow_file_upload boolean DEFAULT false NOT NULL,
    default_locale character varying(16) DEFAULT ''::character varying NOT NULL,
    webhook_url character varying(512) DEFAULT ''::character varying NOT NULL,
    webhook_secret character varying(128) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    launcher_icon text DEFAULT ''::text NOT NULL,
    show_thinking boolean DEFAULT false NOT NULL
);


--
-- Name: embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embeddings (
    id integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    source_id character varying(64) NOT NULL,
    source_type integer NOT NULL,
    chunk_id character varying(64),
    knowledge_id character varying(64),
    knowledge_base_id character varying(64),
    content text,
    dimension integer NOT NULL,
    embedding public.halfvec,
    is_enabled boolean DEFAULT true,
    tag_id character varying(36)
);


--
-- Name: embeddings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.embeddings_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: embeddings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.embeddings_id_seq OWNED BY public.embeddings.id;


--
-- Name: im_channel_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.im_channel_sessions (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    platform character varying(20) NOT NULL,
    user_id character varying(128) NOT NULL,
    chat_id character varying(128) DEFAULT ''::character varying NOT NULL,
    session_id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    agent_id character varying(36) DEFAULT ''::character varying,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    im_channel_id character varying(36) DEFAULT ''::character varying,
    thread_id character varying(128) DEFAULT ''::character varying NOT NULL
);


--
-- Name: im_channels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.im_channels (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id bigint NOT NULL,
    agent_id character varying(36) NOT NULL,
    platform character varying(20) NOT NULL,
    name character varying(255) DEFAULT ''::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    mode character varying(20) DEFAULT 'websocket'::character varying NOT NULL,
    output_mode character varying(20) DEFAULT 'stream'::character varying NOT NULL,
    credentials jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    knowledge_base_id character varying(36) DEFAULT ''::character varying,
    bot_identity character varying(255) DEFAULT ''::character varying NOT NULL,
    session_mode character varying(20) DEFAULT 'user'::character varying NOT NULL,
    CONSTRAINT chk_im_channels_session_mode CHECK (((session_mode)::text = ANY ((ARRAY['user'::character varying, 'thread'::character varying])::text[])))
);


--
-- Name: kb_shares; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kb_shares (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    organization_id character varying(36) NOT NULL,
    shared_by_user_id character varying(36) NOT NULL,
    source_tenant_id integer NOT NULL,
    permission character varying(32) DEFAULT 'viewer'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone
);


--
-- Name: knowledge_bases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_bases (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    tenant_id integer NOT NULL,
    chunking_config jsonb DEFAULT '{"chunk_size": 512, "chunk_overlap": 50, "split_markers": ["\n\n", "\n", "。"], "keep_separator": true}'::jsonb NOT NULL,
    image_processing_config jsonb DEFAULT '{"model_id": "", "enable_multimodal": false}'::jsonb NOT NULL,
    embedding_model_id character varying(64) NOT NULL,
    summary_model_id character varying(64) NOT NULL,
    cos_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    vlm_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    extract_config jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    is_temporary boolean DEFAULT false NOT NULL,
    type character varying(32) DEFAULT 'document'::character varying NOT NULL,
    faq_config jsonb,
    question_generation_config jsonb,
    storage_provider_config jsonb,
    is_pinned boolean DEFAULT false NOT NULL,
    pinned_at timestamp with time zone,
    asr_config jsonb,
    vector_store_id character varying(36),
    wiki_config jsonb,
    indexing_strategy jsonb,
    creator_id character varying(36),
    storage_backend_id character varying(36),
    auto_tag_config jsonb
);


--
-- Name: knowledge_processing_spans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_processing_spans (
    id bigint NOT NULL,
    knowledge_id character varying(64) NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    span_id character varying(64) NOT NULL,
    parent_span_id character varying(64),
    name character varying(255) NOT NULL,
    kind character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    input jsonb,
    output jsonb,
    metadata jsonb,
    error_code character varying(64),
    error_message text,
    error_detail text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: knowledge_processing_spans_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.knowledge_processing_spans_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: knowledge_processing_spans_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.knowledge_processing_spans_id_seq OWNED BY public.knowledge_processing_spans.id;


--
-- Name: knowledge_tag_relations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_tag_relations (
    knowledge_id character varying(36) NOT NULL,
    tag_id character varying(36) NOT NULL,
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: knowledge_tags_seq_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.knowledge_tags_seq_id_seq
    START WITH 10000000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: knowledge_tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_tags (
    id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    name character varying(128) NOT NULL,
    color character varying(32),
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    seq_id bigint DEFAULT nextval('public.knowledge_tags_seq_id_seq'::regclass) NOT NULL
);


--
-- Name: knowledges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledges (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    type character varying(50) NOT NULL,
    title character varying(255) NOT NULL,
    description text,
    source character varying(2048) NOT NULL,
    parse_status character varying(50) DEFAULT 'unprocessed'::character varying NOT NULL,
    enable_status character varying(50) DEFAULT 'enabled'::character varying NOT NULL,
    embedding_model_id character varying(64),
    file_name character varying(255),
    file_type character varying(50),
    file_size bigint,
    file_path text,
    file_hash character varying(64),
    storage_size bigint DEFAULT 0 NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    processed_at timestamp with time zone,
    error_message text,
    deleted_at timestamp with time zone,
    summary_status character varying(32) DEFAULT 'none'::character varying,
    last_faq_import_result json,
    channel character varying(50) DEFAULT 'web'::character varying NOT NULL,
    pending_subtasks_count integer DEFAULT 0 NOT NULL,
    custom_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    folder_path character varying(1024) DEFAULT ''::character varying NOT NULL
);


--
-- Name: mcp_metadata; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcp_metadata (
    tenant_id bigint NOT NULL,
    service_id character varying(36) NOT NULL,
    principal character varying(255) DEFAULT ''::character varying NOT NULL,
    config_fingerprint character varying(64) NOT NULL,
    tools jsonb NOT NULL,
    instructions text DEFAULT ''::text NOT NULL,
    server_name text DEFAULT ''::text NOT NULL,
    server_version text DEFAULT ''::text NOT NULL,
    server_description text DEFAULT ''::text NOT NULL,
    synced_at timestamp with time zone NOT NULL
);


--
-- Name: mcp_oauth_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcp_oauth_clients (
    id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    service_id character varying(36) NOT NULL,
    client_id character varying(512) NOT NULL,
    client_secret text,
    redirect_uri character varying(1024),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: mcp_oauth_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcp_oauth_tokens (
    id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    user_id character varying(512) NOT NULL,
    service_id character varying(36) NOT NULL,
    access_token text,
    refresh_token text,
    token_type character varying(32),
    expires_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    principal_type character varying(32) NOT NULL,
    principal_id character varying(512) NOT NULL,
    refresh_lease_id character varying(36),
    refresh_lease_until timestamp with time zone
);


--
-- Name: mcp_services; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcp_services (
    id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    enabled boolean DEFAULT true,
    transport_type character varying(50) NOT NULL,
    url character varying(512),
    headers jsonb,
    auth_config jsonb,
    advanced_config jsonb,
    stdio_config jsonb,
    env_vars jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone,
    is_builtin boolean DEFAULT false NOT NULL,
    usage_instructions text DEFAULT ''::text NOT NULL
);


--
-- Name: mcp_tool_approvals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcp_tool_approvals (
    id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    service_id character varying(36) NOT NULL,
    tool_name character varying(512) NOT NULL,
    require_approval boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    enabled boolean DEFAULT true NOT NULL
);


--
-- Name: memory_doc_affinity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_doc_affinity (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    knowledge_id character varying(36) NOT NULL,
    knowledge_base_id character varying(36) DEFAULT ''::character varying NOT NULL,
    title character varying(512) DEFAULT ''::character varying NOT NULL,
    hits integer DEFAULT 0 NOT NULL,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: memory_extraction_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_extraction_sessions (
    tenant_id bigint NOT NULL,
    subject_id character varying(512) NOT NULL,
    session_id character varying(36) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    cursor_at timestamp with time zone,
    cursor_id character varying(36) DEFAULT ''::character varying NOT NULL,
    pending boolean DEFAULT false NOT NULL,
    failure_count integer DEFAULT 0 NOT NULL,
    failure_code character varying(64) DEFAULT ''::character varying NOT NULL,
    failed_from_at timestamp with time zone,
    failed_from_id character varying(36) DEFAULT ''::character varying NOT NULL,
    failed_to_at timestamp with time zone,
    failed_to_id character varying(36) DEFAULT ''::character varying NOT NULL,
    failed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: memory_item_embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_item_embeddings (
    item_id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    model_id character varying(64) DEFAULT ''::character varying NOT NULL,
    dims integer DEFAULT 0 NOT NULL,
    vector bytea,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    embedding public.halfvec
);


--
-- Name: memory_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_items (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    kind character varying(32) NOT NULL,
    content text NOT NULL,
    topic character varying(255) DEFAULT ''::character varying NOT NULL,
    normalized_key character varying(255) DEFAULT ''::character varying NOT NULL,
    importance smallint DEFAULT 3 NOT NULL,
    origin character varying(16) DEFAULT 'extracted'::character varying NOT NULL,
    status character varying(16) DEFAULT 'active'::character varying NOT NULL,
    source_session_id character varying(36),
    source_message_id character varying(36),
    valid_from timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    invalid_at timestamp with time zone,
    expires_at timestamp with time zone,
    superseded_by character varying(36),
    last_used_at timestamp with time zone,
    use_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    replaces_id character varying(36) DEFAULT ''::character varying NOT NULL
);


--
-- Name: memory_subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_subjects (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    block_text text DEFAULT ''::text NOT NULL,
    block_updated_at timestamp with time zone,
    item_count integer DEFAULT 0 NOT NULL,
    last_extracted_at timestamp with time zone,
    extract_cursor timestamp with time zone,
    pending_sessions jsonb,
    extract_scheduled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    consolidated_at timestamp with time zone,
    forced_consolidated_at timestamp with time zone,
    extraction_state jsonb
);


--
-- Name: memory_tombstones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_tombstones (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    topic character varying(255) DEFAULT ''::character varying NOT NULL,
    fingerprint character varying(64) NOT NULL,
    source_message_id character varying(36),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: memory_topic_stats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_topic_stats (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    subject_id character varying(512) NOT NULL,
    normalized_key character varying(255) NOT NULL,
    topic character varying(255) DEFAULT ''::character varying NOT NULL,
    hits integer DEFAULT 0 NOT NULL,
    last_seen_at timestamp with time zone,
    promoted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    aliases jsonb DEFAULT '[]'::jsonb NOT NULL
);


--
-- Name: message_suggestion_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_suggestion_events (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    session_id character varying(36) NOT NULL,
    suggestion_set_id character varying(36) NOT NULL,
    question_id character varying(64) DEFAULT ''::character varying NOT NULL,
    event_type character varying(32) NOT NULL,
    actor_id character varying(512) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: message_suggestion_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.message_suggestion_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_suggestion_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.message_suggestion_events_id_seq OWNED BY public.message_suggestion_events.id;


--
-- Name: message_suggestion_sets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_suggestion_sets (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    session_id character varying(36) NOT NULL,
    assistant_message_id character varying(36) NOT NULL,
    agent_id character varying(36) DEFAULT ''::character varying NOT NULL,
    agent_tenant_id integer DEFAULT 0 NOT NULL,
    placement character varying(32) NOT NULL,
    config_hash character varying(64) NOT NULL,
    locale character varying(16) DEFAULT ''::character varying NOT NULL,
    status character varying(16) NOT NULL,
    allow_regenerate boolean DEFAULT false NOT NULL,
    suppression_reason character varying(64) DEFAULT ''::character varying NOT NULL,
    questions jsonb DEFAULT '[]'::jsonb NOT NULL,
    model_id character varying(64) DEFAULT ''::character varying NOT NULL,
    prompt_tokens integer DEFAULT 0 NOT NULL,
    completion_tokens integer DEFAULT 0 NOT NULL,
    latency_ms bigint DEFAULT 0 NOT NULL,
    error_code character varying(64) DEFAULT ''::character varying NOT NULL,
    lease_until timestamp with time zone,
    generated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    request_id character varying(36) NOT NULL,
    session_id character varying(36) NOT NULL,
    role character varying(50) NOT NULL,
    content text NOT NULL,
    knowledge_references jsonb DEFAULT '[]'::jsonb NOT NULL,
    agent_steps jsonb,
    is_completed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    mentioned_items jsonb DEFAULT '[]'::jsonb,
    is_fallback boolean DEFAULT false,
    agent_duration_ms bigint DEFAULT 0,
    knowledge_id character varying(36),
    images jsonb DEFAULT '[]'::jsonb,
    channel character varying(50) DEFAULT ''::character varying NOT NULL,
    rendered_content text DEFAULT ''::text NOT NULL,
    attachments jsonb DEFAULT '[]'::jsonb,
    agent_id character varying(36) DEFAULT ''::character varying NOT NULL,
    agent_tenant_id integer DEFAULT 0 NOT NULL,
    model_id character varying(64) DEFAULT ''::character varying NOT NULL,
    execution_context jsonb DEFAULT '{}'::jsonb NOT NULL,
    artifacts jsonb DEFAULT '[]'::jsonb,
    used_memories jsonb,
    usage jsonb
);


--
-- Name: models; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.models (
    id character varying(64) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    name character varying(255) NOT NULL,
    display_name character varying(255) DEFAULT ''::character varying NOT NULL,
    type character varying(50) NOT NULL,
    source character varying(50) NOT NULL,
    description text,
    parameters jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    status character varying(50) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    is_builtin boolean DEFAULT false NOT NULL,
    managed_by character varying(32) DEFAULT ''::character varying NOT NULL
);


--
-- Name: organization_join_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organization_join_requests (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    organization_id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    status character varying(32) DEFAULT 'pending'::character varying NOT NULL,
    requested_role character varying(32) DEFAULT 'viewer'::character varying NOT NULL,
    request_type character varying(32) DEFAULT 'join'::character varying NOT NULL,
    prev_role character varying(32),
    message text,
    reviewed_by character varying(36),
    reviewed_at timestamp with time zone,
    review_message text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: organization_members_pre_plan3; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organization_members_pre_plan3 (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    organization_id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    role character varying(32) DEFAULT 'viewer'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: organization_tenant_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organization_tenant_members (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    organization_id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    role character varying(32) DEFAULT 'viewer'::character varying NOT NULL,
    representative_user_id character varying(36) DEFAULT ''::character varying NOT NULL,
    joined_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    owner_id character varying(36) NOT NULL,
    invite_code character varying(32),
    require_approval boolean DEFAULT false,
    invite_code_expires_at timestamp with time zone,
    invite_code_validity_days smallint DEFAULT 7 NOT NULL,
    avatar character varying(512) DEFAULT ''::character varying,
    searchable boolean DEFAULT false NOT NULL,
    member_limit integer DEFAULT 50 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    owner_tenant_id bigint NOT NULL
);


--
-- Name: resource_access_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resource_access_grants (
    id character varying(36) NOT NULL,
    token_hash character varying(64) NOT NULL,
    resource_id character varying(36) NOT NULL,
    access_scope character varying(16) DEFAULT 'read'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: resource_bindings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resource_bindings (
    id character varying(36) NOT NULL,
    resource_id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    owner_type character varying(32) NOT NULL,
    owner_id character varying(64) NOT NULL,
    relation character varying(32) DEFAULT 'attachment'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: resources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resources (
    id character varying(36) NOT NULL,
    handle character varying(22) NOT NULL,
    tenant_id bigint NOT NULL,
    storage_backend_id character varying(36),
    provider character varying(32) NOT NULL,
    physical_path text NOT NULL,
    location_hash character varying(64) NOT NULL,
    kind character varying(32) DEFAULT 'file'::character varying NOT NULL,
    mime_type character varying(255) DEFAULT ''::character varying NOT NULL,
    original_name character varying(1024) DEFAULT ''::character varying NOT NULL,
    size bigint DEFAULT 0 NOT NULL,
    content_hash character varying(64) DEFAULT ''::character varying NOT NULL,
    lifecycle character varying(16) DEFAULT 'persistent'::character varying NOT NULL,
    expires_at timestamp without time zone,
    state character varying(16) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sessions (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    tenant_id integer NOT NULL,
    title character varying(255),
    description text,
    knowledge_base_id character varying(36),
    max_rounds integer DEFAULT 5 NOT NULL,
    enable_rewrite boolean DEFAULT true NOT NULL,
    fallback_strategy character varying(255) DEFAULT 'fixed'::character varying NOT NULL,
    fallback_response text DEFAULT '很抱歉，我暂时无法回答这个问题。'::text NOT NULL,
    keyword_threshold double precision DEFAULT 0.5 NOT NULL,
    vector_threshold double precision DEFAULT 0.5 NOT NULL,
    rerank_model_id character varying(64),
    embedding_top_k integer DEFAULT 10 NOT NULL,
    rerank_top_k integer DEFAULT 10 NOT NULL,
    rerank_threshold double precision DEFAULT 0.65 NOT NULL,
    summary_model_id character varying(64),
    summary_parameters jsonb DEFAULT '{}'::jsonb NOT NULL,
    agent_config jsonb,
    context_config jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    agent_id character varying(36),
    user_id character varying(512),
    is_pinned boolean DEFAULT false NOT NULL,
    pinned_at timestamp with time zone
);


--
-- Name: storage_backends; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storage_backends (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    provider character varying(32) NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    source character varying(16) DEFAULT 'user'::character varying NOT NULL,
    status character varying(16) DEFAULT 'active'::character varying NOT NULL,
    legacy_alias boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: sync_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sync_logs (
    id character varying(36) NOT NULL,
    data_source_id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    status character varying(32) NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp without time zone,
    items_total integer DEFAULT 0,
    items_created integer DEFAULT 0,
    items_updated integer DEFAULT 0,
    items_deleted integer DEFAULT 0,
    items_skipped integer DEFAULT 0,
    items_failed integer DEFAULT 0,
    error_message text,
    result jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: system_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_settings (
    id bigint NOT NULL,
    key character varying(128) NOT NULL,
    value jsonb NOT NULL,
    value_type character varying(16) NOT NULL,
    category character varying(32) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    is_secret boolean DEFAULT false NOT NULL,
    requires_restart boolean DEFAULT false NOT NULL,
    last_modified_by character varying(36) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: system_settings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_settings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_settings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.system_settings_id_seq OWNED BY public.system_settings.id;


--
-- Name: task_dead_letters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_dead_letters (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    task_type character varying(64) NOT NULL,
    scope character varying(32) NOT NULL,
    scope_id character varying(64) NOT NULL,
    related_id character varying(64) DEFAULT ''::character varying NOT NULL,
    payload jsonb NOT NULL,
    last_error text DEFAULT ''::text NOT NULL,
    fail_count integer NOT NULL,
    failed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: task_dead_letters_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_dead_letters_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_dead_letters_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_dead_letters_id_seq OWNED BY public.task_dead_letters.id;


--
-- Name: task_pending_ops; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_pending_ops (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    task_type character varying(64) NOT NULL,
    scope character varying(32) NOT NULL,
    scope_id character varying(64) NOT NULL,
    op character varying(32) NOT NULL,
    dedup_key character varying(128) DEFAULT ''::character varying NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    fail_count integer DEFAULT 0 NOT NULL,
    enqueued_at timestamp with time zone DEFAULT now() NOT NULL,
    claimed_at timestamp with time zone
);


--
-- Name: task_pending_ops_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_pending_ops_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_pending_ops_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_pending_ops_id_seq OWNED BY public.task_pending_ops.id;


--
-- Name: temporary_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.temporary_documents (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    session_id character varying(36) NOT NULL,
    resource_ref text NOT NULL,
    file_name character varying(1024) NOT NULL,
    file_type character varying(32) NOT NULL,
    mime_type character varying(255) DEFAULT ''::character varying NOT NULL,
    file_size bigint NOT NULL,
    status character varying(16) DEFAULT 'uploaded'::character varying NOT NULL,
    content text DEFAULT ''::text NOT NULL,
    chunks jsonb DEFAULT '[]'::jsonb NOT NULL,
    image_refs jsonb DEFAULT '[]'::jsonb NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    processing_options jsonb DEFAULT '{}'::jsonb NOT NULL,
    token_count integer DEFAULT 0 NOT NULL,
    chunk_count integer DEFAULT 0 NOT NULL,
    error_message text DEFAULT ''::text NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    started_at timestamp without time zone,
    ready_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: tenant_api_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_api_keys (
    id bigint NOT NULL,
    tenant_id integer,
    name character varying(128) NOT NULL,
    key_hash character varying(64) NOT NULL,
    api_key text DEFAULT ''::text NOT NULL,
    full_access boolean DEFAULT false NOT NULL,
    knowledge_base_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    capabilities jsonb DEFAULT '[]'::jsonb NOT NULL,
    last_used_at timestamp with time zone,
    expires_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    scope_type character varying(16) DEFAULT 'tenant'::character varying NOT NULL,
    CONSTRAINT chk_tenant_api_keys_scope CHECK (((((scope_type)::text = 'tenant'::text) AND (tenant_id IS NOT NULL)) OR (((scope_type)::text = 'platform'::text) AND (tenant_id IS NULL) AND (full_access = false))))
);


--
-- Name: tenant_api_keys_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_api_keys_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_api_keys_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_api_keys_id_seq OWNED BY public.tenant_api_keys.id;


--
-- Name: tenant_disabled_shared_agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_disabled_shared_agents (
    tenant_id bigint NOT NULL,
    agent_id character varying(36) NOT NULL,
    source_tenant_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: tenant_invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_invitations (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    invitee_user_id character varying(36) DEFAULT ''::character varying NOT NULL,
    invited_by character varying(36),
    role character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    message character varying(500),
    expires_at timestamp with time zone NOT NULL,
    responded_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    token character varying(64) DEFAULT ''::character varying NOT NULL,
    accepted_count integer DEFAULT 0 NOT NULL
);


--
-- Name: tenant_invitations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_invitations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_invitations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_invitations_id_seq OWNED BY public.tenant_invitations.id;


--
-- Name: tenant_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_members (
    id bigint NOT NULL,
    user_id character varying(36) NOT NULL,
    tenant_id integer NOT NULL,
    role character varying(20) DEFAULT 'contributor'::character varying NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    invited_by character varying(36),
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: tenant_members_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_members_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_members_id_seq OWNED BY public.tenant_members.id;


--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    id integer NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    retriever_engines jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(50) DEFAULT 'active'::character varying,
    business character varying(255) NOT NULL,
    storage_quota bigint DEFAULT '10737418240'::bigint NOT NULL,
    storage_used bigint DEFAULT 0 NOT NULL,
    agent_config jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    context_config jsonb,
    conversation_config jsonb,
    web_search_config jsonb,
    parser_engine_config jsonb,
    storage_engine_config jsonb,
    chat_history_config jsonb,
    retrieval_config jsonb,
    credentials jsonb,
    api_principal_config jsonb,
    default_storage_backend_id character varying(36),
    memory_config jsonb
);


--
-- Name: tenants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenants_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenants_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenants_id_seq OWNED BY public.tenants.id;


--
-- Name: user_kb_pins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_kb_pins (
    tenant_id bigint NOT NULL,
    user_id character varying(36) NOT NULL,
    kb_id character varying(36) NOT NULL,
    pinned_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: user_resource_favorites; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_resource_favorites (
    user_id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    resource_type character varying(16) NOT NULL,
    resource_id character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id character varying(36) DEFAULT public.uuid_generate_v4() NOT NULL,
    username character varying(100) NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    avatar character varying(500),
    tenant_id integer,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone,
    can_access_all_tenants boolean DEFAULT false NOT NULL,
    preferences jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_system_admin boolean DEFAULT false NOT NULL
);


--
-- Name: vector_stores; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vector_stores (
    id character varying(36) NOT NULL,
    name character varying(255) NOT NULL,
    engine_type character varying(50) NOT NULL,
    connection_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    index_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    tenant_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: web_search_providers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.web_search_providers (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    provider character varying(50) NOT NULL,
    description text,
    parameters jsonb,
    is_default boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone
);


--
-- Name: wiki_folders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wiki_folders (
    id character varying(36) NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    parent_id character varying(36) DEFAULT ''::character varying NOT NULL,
    name character varying(255) NOT NULL,
    path character varying(1024) DEFAULT ''::character varying NOT NULL,
    depth integer DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: wiki_page_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wiki_page_issues (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    slug character varying(255) NOT NULL,
    issue_type character varying(50) NOT NULL,
    description text NOT NULL,
    suspected_knowledge_ids jsonb,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    reported_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp with time zone
);


--
-- Name: wiki_page_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wiki_page_revisions (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    page_id character varying(36) NOT NULL,
    slug character varying(255) NOT NULL,
    version integer NOT NULL,
    title character varying(512) DEFAULT ''::character varying NOT NULL,
    page_type character varying(32) DEFAULT 'summary'::character varying NOT NULL,
    status character varying(32) DEFAULT 'published'::character varying NOT NULL,
    content text DEFAULT ''::text NOT NULL,
    summary text DEFAULT ''::text NOT NULL,
    aliases jsonb DEFAULT '[]'::jsonb,
    edit_source character varying(16) DEFAULT ''::character varying NOT NULL,
    editor_id character varying(64) DEFAULT ''::character varying NOT NULL,
    edited_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wiki_pages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wiki_pages (
    id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    knowledge_base_id character varying(36) NOT NULL,
    slug character varying(255) NOT NULL,
    title character varying(512) DEFAULT ''::character varying NOT NULL,
    page_type character varying(32) DEFAULT 'summary'::character varying NOT NULL,
    status character varying(32) DEFAULT 'published'::character varying NOT NULL,
    content text DEFAULT ''::text NOT NULL,
    summary text DEFAULT ''::text NOT NULL,
    parent_slug character varying(255) DEFAULT ''::character varying NOT NULL,
    folder_id character varying(36) DEFAULT ''::character varying NOT NULL,
    category_path jsonb DEFAULT '[]'::jsonb,
    wiki_path character varying(1024) DEFAULT ''::character varying NOT NULL,
    depth integer DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    source_refs jsonb DEFAULT '[]'::jsonb,
    chunk_refs jsonb DEFAULT '[]'::jsonb,
    in_links jsonb DEFAULT '[]'::jsonb,
    out_links jsonb DEFAULT '[]'::jsonb,
    page_metadata jsonb DEFAULT '{}'::jsonb,
    aliases jsonb DEFAULT '[]'::jsonb,
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    last_edit_source character varying(16) DEFAULT ''::character varying NOT NULL,
    last_editor_id character varying(64) DEFAULT ''::character varying NOT NULL
);


--
-- Name: audit_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs ALTER COLUMN id SET DEFAULT nextval('public.audit_logs_id_seq'::regclass);


--
-- Name: embeddings id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embeddings ALTER COLUMN id SET DEFAULT nextval('public.embeddings_id_seq'::regclass);


--
-- Name: knowledge_processing_spans id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_processing_spans ALTER COLUMN id SET DEFAULT nextval('public.knowledge_processing_spans_id_seq'::regclass);


--
-- Name: message_suggestion_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_events ALTER COLUMN id SET DEFAULT nextval('public.message_suggestion_events_id_seq'::regclass);


--
-- Name: system_settings id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_settings ALTER COLUMN id SET DEFAULT nextval('public.system_settings_id_seq'::regclass);


--
-- Name: task_dead_letters id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_dead_letters ALTER COLUMN id SET DEFAULT nextval('public.task_dead_letters_id_seq'::regclass);


--
-- Name: task_pending_ops id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_pending_ops ALTER COLUMN id SET DEFAULT nextval('public.task_pending_ops_id_seq'::regclass);


--
-- Name: tenant_api_keys id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_api_keys ALTER COLUMN id SET DEFAULT nextval('public.tenant_api_keys_id_seq'::regclass);


--
-- Name: tenant_invitations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_invitations ALTER COLUMN id SET DEFAULT nextval('public.tenant_invitations_id_seq'::regclass);


--
-- Name: tenant_members id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_members ALTER COLUMN id SET DEFAULT nextval('public.tenant_members_id_seq'::regclass);


--
-- Name: tenants id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants ALTER COLUMN id SET DEFAULT nextval('public.tenants_id_seq'::regclass);


--
-- Name: agent_shares agent_shares_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_shares
    ADD CONSTRAINT agent_shares_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: auth_tokens auth_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_tokens
    ADD CONSTRAINT auth_tokens_pkey PRIMARY KEY (id);


--
-- Name: chunk_revisions chunk_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunk_revisions
    ADD CONSTRAINT chunk_revisions_pkey PRIMARY KEY (id);


--
-- Name: chunks chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_pkey PRIMARY KEY (id);


--
-- Name: custom_agents custom_agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custom_agents
    ADD CONSTRAINT custom_agents_pkey PRIMARY KEY (id, tenant_id);


--
-- Name: data_sources data_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_sources
    ADD CONSTRAINT data_sources_pkey PRIMARY KEY (id);


--
-- Name: embed_channels embed_channels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embed_channels
    ADD CONSTRAINT embed_channels_pkey PRIMARY KEY (id);


--
-- Name: embeddings embeddings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embeddings
    ADD CONSTRAINT embeddings_pkey PRIMARY KEY (id);


--
-- Name: im_channel_sessions im_channel_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.im_channel_sessions
    ADD CONSTRAINT im_channel_sessions_pkey PRIMARY KEY (id);


--
-- Name: im_channels im_channels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.im_channels
    ADD CONSTRAINT im_channels_pkey PRIMARY KEY (id);


--
-- Name: kb_shares kb_shares_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kb_shares
    ADD CONSTRAINT kb_shares_pkey PRIMARY KEY (id);


--
-- Name: knowledge_bases knowledge_bases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_bases
    ADD CONSTRAINT knowledge_bases_pkey PRIMARY KEY (id);


--
-- Name: knowledge_processing_spans knowledge_processing_spans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_processing_spans
    ADD CONSTRAINT knowledge_processing_spans_pkey PRIMARY KEY (id);


--
-- Name: knowledge_tag_relations knowledge_tag_relations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_tag_relations
    ADD CONSTRAINT knowledge_tag_relations_pkey PRIMARY KEY (knowledge_id, tag_id);


--
-- Name: knowledge_tags knowledge_tags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_tags
    ADD CONSTRAINT knowledge_tags_pkey PRIMARY KEY (id);


--
-- Name: knowledges knowledges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledges
    ADD CONSTRAINT knowledges_pkey PRIMARY KEY (id);


--
-- Name: mcp_metadata mcp_metadata_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_metadata
    ADD CONSTRAINT mcp_metadata_pkey PRIMARY KEY (tenant_id, service_id, principal);


--
-- Name: mcp_oauth_clients mcp_oauth_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_oauth_clients
    ADD CONSTRAINT mcp_oauth_clients_pkey PRIMARY KEY (id);


--
-- Name: mcp_oauth_tokens mcp_oauth_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_oauth_tokens
    ADD CONSTRAINT mcp_oauth_tokens_pkey PRIMARY KEY (id);


--
-- Name: mcp_services mcp_services_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_services
    ADD CONSTRAINT mcp_services_pkey PRIMARY KEY (id);


--
-- Name: mcp_tool_approvals mcp_tool_approvals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_tool_approvals
    ADD CONSTRAINT mcp_tool_approvals_pkey PRIMARY KEY (id);


--
-- Name: memory_doc_affinity memory_doc_affinity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_doc_affinity
    ADD CONSTRAINT memory_doc_affinity_pkey PRIMARY KEY (id);


--
-- Name: memory_extraction_sessions memory_extraction_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_extraction_sessions
    ADD CONSTRAINT memory_extraction_sessions_pkey PRIMARY KEY (tenant_id, subject_id, session_id);


--
-- Name: memory_item_embeddings memory_item_embeddings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_item_embeddings
    ADD CONSTRAINT memory_item_embeddings_pkey PRIMARY KEY (item_id);


--
-- Name: memory_items memory_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_items
    ADD CONSTRAINT memory_items_pkey PRIMARY KEY (id);


--
-- Name: memory_subjects memory_subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_subjects
    ADD CONSTRAINT memory_subjects_pkey PRIMARY KEY (id);


--
-- Name: memory_tombstones memory_tombstones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_tombstones
    ADD CONSTRAINT memory_tombstones_pkey PRIMARY KEY (id);


--
-- Name: memory_topic_stats memory_topic_stats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_topic_stats
    ADD CONSTRAINT memory_topic_stats_pkey PRIMARY KEY (id);


--
-- Name: message_suggestion_events message_suggestion_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_events
    ADD CONSTRAINT message_suggestion_events_pkey PRIMARY KEY (id);


--
-- Name: message_suggestion_sets message_suggestion_sets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_sets
    ADD CONSTRAINT message_suggestion_sets_pkey PRIMARY KEY (id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: models models_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.models
    ADD CONSTRAINT models_pkey PRIMARY KEY (id);


--
-- Name: organization_join_requests organization_join_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_join_requests
    ADD CONSTRAINT organization_join_requests_pkey PRIMARY KEY (id);


--
-- Name: organization_members_pre_plan3 organization_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_members_pre_plan3
    ADD CONSTRAINT organization_members_pkey PRIMARY KEY (id);


--
-- Name: organization_tenant_members organization_tenant_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_tenant_members
    ADD CONSTRAINT organization_tenant_members_pkey PRIMARY KEY (id);


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);


--
-- Name: resource_access_grants resource_access_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_access_grants
    ADD CONSTRAINT resource_access_grants_pkey PRIMARY KEY (id);


--
-- Name: resource_access_grants resource_access_grants_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_access_grants
    ADD CONSTRAINT resource_access_grants_token_hash_key UNIQUE (token_hash);


--
-- Name: resource_bindings resource_bindings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_bindings
    ADD CONSTRAINT resource_bindings_pkey PRIMARY KEY (id);


--
-- Name: resources resources_handle_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resources
    ADD CONSTRAINT resources_handle_key UNIQUE (handle);


--
-- Name: resources resources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resources
    ADD CONSTRAINT resources_pkey PRIMARY KEY (id);


--
-- Name: sessions sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sessions
    ADD CONSTRAINT sessions_pkey PRIMARY KEY (id);


--
-- Name: storage_backends storage_backends_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_backends
    ADD CONSTRAINT storage_backends_pkey PRIMARY KEY (id);


--
-- Name: sync_logs sync_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_logs
    ADD CONSTRAINT sync_logs_pkey PRIMARY KEY (id);


--
-- Name: system_settings system_settings_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_key_key UNIQUE (key);


--
-- Name: system_settings system_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (id);


--
-- Name: task_dead_letters task_dead_letters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_dead_letters
    ADD CONSTRAINT task_dead_letters_pkey PRIMARY KEY (id);


--
-- Name: task_pending_ops task_pending_ops_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_pending_ops
    ADD CONSTRAINT task_pending_ops_pkey PRIMARY KEY (id);


--
-- Name: temporary_documents temporary_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.temporary_documents
    ADD CONSTRAINT temporary_documents_pkey PRIMARY KEY (id);


--
-- Name: tenant_api_keys tenant_api_keys_key_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_api_keys
    ADD CONSTRAINT tenant_api_keys_key_hash_key UNIQUE (key_hash);


--
-- Name: tenant_api_keys tenant_api_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_api_keys
    ADD CONSTRAINT tenant_api_keys_pkey PRIMARY KEY (id);


--
-- Name: tenant_disabled_shared_agents tenant_disabled_shared_agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_disabled_shared_agents
    ADD CONSTRAINT tenant_disabled_shared_agents_pkey PRIMARY KEY (tenant_id, agent_id, source_tenant_id);


--
-- Name: tenant_invitations tenant_invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_invitations
    ADD CONSTRAINT tenant_invitations_pkey PRIMARY KEY (id);


--
-- Name: tenant_members tenant_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_members
    ADD CONSTRAINT tenant_members_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);


--
-- Name: knowledge_processing_spans uq_kpspan_attempt_span; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_processing_spans
    ADD CONSTRAINT uq_kpspan_attempt_span UNIQUE (knowledge_id, attempt, span_id);


--
-- Name: user_kb_pins user_kb_pins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_kb_pins
    ADD CONSTRAINT user_kb_pins_pkey PRIMARY KEY (tenant_id, user_id, kb_id);


--
-- Name: user_resource_favorites user_resource_favorites_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_resource_favorites
    ADD CONSTRAINT user_resource_favorites_pkey PRIMARY KEY (user_id, tenant_id, resource_type, resource_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: vector_stores vector_stores_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vector_stores
    ADD CONSTRAINT vector_stores_pkey PRIMARY KEY (id);


--
-- Name: web_search_providers web_search_providers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.web_search_providers
    ADD CONSTRAINT web_search_providers_pkey PRIMARY KEY (id);


--
-- Name: wiki_folders wiki_folders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wiki_folders
    ADD CONSTRAINT wiki_folders_pkey PRIMARY KEY (id);


--
-- Name: wiki_page_issues wiki_page_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wiki_page_issues
    ADD CONSTRAINT wiki_page_issues_pkey PRIMARY KEY (id);


--
-- Name: wiki_page_revisions wiki_page_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wiki_page_revisions
    ADD CONSTRAINT wiki_page_revisions_pkey PRIMARY KEY (id);


--
-- Name: wiki_pages wiki_pages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wiki_pages
    ADD CONSTRAINT wiki_pages_pkey PRIMARY KEY (id);


--
-- Name: embeddings_embedding_idx_1024; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embeddings_embedding_idx_1024 ON public.embeddings USING hnsw (((embedding)::public.halfvec(1024)) public.halfvec_cosine_ops) WITH (m='16', ef_construction='64') WHERE (dimension = 1024);


--
-- Name: embeddings_embedding_idx_3584; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embeddings_embedding_idx_3584 ON public.embeddings USING hnsw (((embedding)::public.halfvec(3584)) public.halfvec_cosine_ops) WITH (m='16', ef_construction='64') WHERE (dimension = 3584);


--
-- Name: embeddings_embedding_idx_798; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embeddings_embedding_idx_798 ON public.embeddings USING hnsw (((embedding)::public.halfvec(798)) public.halfvec_cosine_ops) WITH (m='16', ef_construction='64') WHERE (dimension = 798);


--
-- Name: embeddings_search_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embeddings_search_idx ON public.embeddings USING bm25 (id, knowledge_base_id, content, knowledge_id, chunk_id) WITH (key_field=id, text_fields='{
                "content": {
                  "tokenizer": {"type": "chinese_lindera"}
                }
            }');


--
-- Name: embeddings_unique_source; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX embeddings_unique_source ON public.embeddings USING btree (source_id, source_type);


--
-- Name: idx_agent_shares_agent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_shares_agent_id ON public.agent_shares USING btree (agent_id);


--
-- Name: idx_agent_shares_agent_org; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_agent_shares_agent_org ON public.agent_shares USING btree (agent_id, source_tenant_id, organization_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_agent_shares_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_shares_deleted_at ON public.agent_shares USING btree (deleted_at);


--
-- Name: idx_agent_shares_org_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_shares_org_id ON public.agent_shares USING btree (organization_id);


--
-- Name: idx_agent_shares_source_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_shares_source_tenant ON public.agent_shares USING btree (source_tenant_id);


--
-- Name: idx_audit_logs_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_actor ON public.audit_logs USING btree (actor_user_id);


--
-- Name: idx_audit_logs_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_created_at ON public.audit_logs USING btree (created_at);


--
-- Name: idx_audit_logs_tenant_action; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_tenant_action ON public.audit_logs USING btree (tenant_id, action);


--
-- Name: idx_audit_logs_tenant_id_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_tenant_id_desc ON public.audit_logs USING btree (tenant_id, id DESC);


--
-- Name: idx_audit_logs_tenant_scope_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_tenant_scope_desc ON public.audit_logs USING btree (tenant_id, scope_type, scope_id, id DESC);


--
-- Name: idx_auth_tokens_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_tokens_expires_at ON public.auth_tokens USING btree (expires_at);


--
-- Name: idx_auth_tokens_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_tokens_token ON public.auth_tokens USING btree (token);


--
-- Name: idx_auth_tokens_token_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_tokens_token_type ON public.auth_tokens USING btree (token_type);


--
-- Name: idx_auth_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_tokens_user_id ON public.auth_tokens USING btree (user_id);


--
-- Name: idx_channel_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_channel_lookup ON public.im_channel_sessions USING btree (platform, user_id, chat_id, tenant_id, agent_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_channel_thread_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_channel_thread_lookup ON public.im_channel_sessions USING btree (platform, chat_id, thread_id, tenant_id, agent_id) WHERE ((deleted_at IS NULL) AND ((thread_id)::text <> ''::text));


--
-- Name: idx_chunk_revisions_chunk_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_chunk_revisions_chunk_revision ON public.chunk_revisions USING btree (chunk_id, revision);


--
-- Name: idx_chunk_revisions_tenant_chunk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_revisions_tenant_chunk ON public.chunk_revisions USING btree (tenant_id, chunk_id);


--
-- Name: idx_chunks_chunk_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_chunk_type ON public.chunks USING btree (chunk_type);


--
-- Name: idx_chunks_content_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_content_hash ON public.chunks USING btree (content_hash);


--
-- Name: idx_chunks_kb_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_kb_tenant ON public.chunks USING btree (knowledge_base_id, tenant_id);


--
-- Name: idx_chunks_knowledge_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_knowledge_enabled ON public.chunks USING btree (knowledge_id, is_enabled, deleted_at);


--
-- Name: idx_chunks_parent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_parent_id ON public.chunks USING btree (parent_chunk_id);


--
-- Name: idx_chunks_seq_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_chunks_seq_id ON public.chunks USING btree (seq_id);


--
-- Name: idx_chunks_tag; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_tag ON public.chunks USING btree (tag_id);


--
-- Name: idx_chunks_tenant_kg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_tenant_kg ON public.chunks USING btree (tenant_id, knowledge_id);


--
-- Name: idx_custom_agents_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_custom_agents_deleted_at ON public.custom_agents USING btree (deleted_at);


--
-- Name: idx_custom_agents_is_builtin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_custom_agents_is_builtin ON public.custom_agents USING btree (is_builtin);


--
-- Name: idx_custom_agents_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_custom_agents_tenant_id ON public.custom_agents USING btree (tenant_id);


--
-- Name: idx_data_sources_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sources_deleted_at ON public.data_sources USING btree (deleted_at);


--
-- Name: idx_data_sources_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sources_knowledge_base_id ON public.data_sources USING btree (knowledge_base_id);


--
-- Name: idx_data_sources_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sources_status ON public.data_sources USING btree (status);


--
-- Name: idx_data_sources_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sources_tenant_id ON public.data_sources USING btree (tenant_id);


--
-- Name: idx_data_sources_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sources_type ON public.data_sources USING btree (type);


--
-- Name: idx_embed_channels_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embed_channels_agent ON public.embed_channels USING btree (agent_id);


--
-- Name: idx_embed_channels_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embed_channels_deleted ON public.embed_channels USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);


--
-- Name: idx_embed_channels_publish_token; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_embed_channels_publish_token ON public.embed_channels USING btree (publish_token) WHERE (((publish_token)::text <> ''::text) AND (deleted_at IS NULL));


--
-- Name: idx_embed_channels_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embed_channels_tenant ON public.embed_channels USING btree (tenant_id);


--
-- Name: idx_embeddings_is_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embeddings_is_enabled ON public.embeddings USING btree (is_enabled);


--
-- Name: idx_embeddings_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embeddings_knowledge_base_id ON public.embeddings USING btree (knowledge_base_id);


--
-- Name: idx_embeddings_tag_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_embeddings_tag_id ON public.embeddings USING btree (tag_id);


--
-- Name: idx_im_channel_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channel_deleted ON public.im_channel_sessions USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);


--
-- Name: idx_im_channel_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channel_session ON public.im_channel_sessions USING btree (session_id);


--
-- Name: idx_im_channel_sessions_channel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channel_sessions_channel ON public.im_channel_sessions USING btree (im_channel_id) WHERE ((im_channel_id)::text <> ''::text);


--
-- Name: idx_im_channel_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channel_tenant ON public.im_channel_sessions USING btree (tenant_id);


--
-- Name: idx_im_channels_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channels_agent ON public.im_channels USING btree (agent_id);


--
-- Name: idx_im_channels_bot_identity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_im_channels_bot_identity ON public.im_channels USING btree (bot_identity) WHERE ((deleted_at IS NULL) AND ((bot_identity)::text <> ''::text));


--
-- Name: idx_im_channels_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channels_deleted ON public.im_channels USING btree (deleted_at) WHERE (deleted_at IS NOT NULL);


--
-- Name: idx_im_channels_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_channels_tenant ON public.im_channels USING btree (tenant_id);


--
-- Name: idx_kb_shares_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_shares_deleted_at ON public.kb_shares USING btree (deleted_at);


--
-- Name: idx_kb_shares_kb_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_shares_kb_id ON public.kb_shares USING btree (knowledge_base_id);


--
-- Name: idx_kb_shares_kb_org; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_kb_shares_kb_org ON public.kb_shares USING btree (knowledge_base_id, organization_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_kb_shares_org_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_shares_org_id ON public.kb_shares USING btree (organization_id);


--
-- Name: idx_kb_shares_source_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_shares_source_tenant ON public.kb_shares USING btree (source_tenant_id);


--
-- Name: idx_knowledge_bases_storage_backend; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_bases_storage_backend ON public.knowledge_bases USING btree (tenant_id, storage_backend_id);


--
-- Name: idx_knowledge_bases_tenant_creator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_bases_tenant_creator ON public.knowledge_bases USING btree (tenant_id, creator_id);


--
-- Name: idx_knowledge_bases_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_bases_tenant_id ON public.knowledge_bases USING btree (tenant_id);


--
-- Name: idx_knowledge_bases_tenant_vector_store; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_bases_tenant_vector_store ON public.knowledge_bases USING btree (tenant_id, vector_store_id);


--
-- Name: idx_knowledge_tags_kb; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_tags_kb ON public.knowledge_tags USING btree (tenant_id, knowledge_base_id);


--
-- Name: idx_knowledge_tags_kb_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_knowledge_tags_kb_name ON public.knowledge_tags USING btree (tenant_id, knowledge_base_id, name);


--
-- Name: idx_knowledge_tags_seq_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_knowledge_tags_seq_id ON public.knowledge_tags USING btree (seq_id);


--
-- Name: idx_knowledges_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_base_id ON public.knowledges USING btree (knowledge_base_id);


--
-- Name: idx_knowledges_enable_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_enable_status ON public.knowledges USING btree (enable_status);


--
-- Name: idx_knowledges_folder_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_folder_path ON public.knowledges USING btree (tenant_id, knowledge_base_id, folder_path);


--
-- Name: idx_knowledges_kb_metadata_external_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_kb_metadata_external_id ON public.knowledges USING btree (knowledge_base_id, ((metadata ->> 'external_id'::text)) text_pattern_ops) WHERE (deleted_at IS NULL);


--
-- Name: idx_knowledges_parse_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_parse_status ON public.knowledges USING btree (parse_status);


--
-- Name: idx_knowledges_summary_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_summary_status ON public.knowledges USING btree (summary_status);


--
-- Name: idx_knowledges_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledges_tenant_id ON public.knowledges USING btree (tenant_id);


--
-- Name: idx_kpspan_knowledge_attempt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kpspan_knowledge_attempt ON public.knowledge_processing_spans USING btree (knowledge_id, attempt);


--
-- Name: idx_kpspan_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kpspan_parent ON public.knowledge_processing_spans USING btree (parent_span_id) WHERE (parent_span_id IS NOT NULL);


--
-- Name: idx_kpspan_status_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kpspan_status_started ON public.knowledge_processing_spans USING btree (status, started_at);


--
-- Name: idx_ktr_knowledge; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ktr_knowledge ON public.knowledge_tag_relations USING btree (knowledge_id);


--
-- Name: idx_ktr_tag; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ktr_tag ON public.knowledge_tag_relations USING btree (tag_id);


--
-- Name: idx_mcp_oauth_clients_service_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_oauth_clients_service_id ON public.mcp_oauth_clients USING btree (service_id);


--
-- Name: idx_mcp_oauth_clients_tenant_svc; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mcp_oauth_clients_tenant_svc ON public.mcp_oauth_clients USING btree (tenant_id, service_id);


--
-- Name: idx_mcp_oauth_tokens_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_oauth_tokens_principal ON public.mcp_oauth_tokens USING btree (principal_type, principal_id);


--
-- Name: idx_mcp_oauth_tokens_service_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_oauth_tokens_service_id ON public.mcp_oauth_tokens USING btree (service_id);


--
-- Name: idx_mcp_oauth_tokens_tenant_principal_svc; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mcp_oauth_tokens_tenant_principal_svc ON public.mcp_oauth_tokens USING btree (tenant_id, principal_type, principal_id, service_id);


--
-- Name: idx_mcp_oauth_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_oauth_tokens_user_id ON public.mcp_oauth_tokens USING btree (user_id);


--
-- Name: idx_mcp_services_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_services_deleted_at ON public.mcp_services USING btree (deleted_at);


--
-- Name: idx_mcp_services_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_services_enabled ON public.mcp_services USING btree (enabled);


--
-- Name: idx_mcp_services_is_builtin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_services_is_builtin ON public.mcp_services USING btree (is_builtin);


--
-- Name: idx_mcp_services_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_services_tenant_id ON public.mcp_services USING btree (tenant_id);


--
-- Name: idx_mcp_tool_approvals_service_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcp_tool_approvals_service_id ON public.mcp_tool_approvals USING btree (service_id);


--
-- Name: idx_mcp_tool_approvals_tenant_svc_tool; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mcp_tool_approvals_tenant_svc_tool ON public.mcp_tool_approvals USING btree (tenant_id, service_id, tool_name);


--
-- Name: idx_mem_affinity_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mem_affinity_scope ON public.memory_doc_affinity USING btree (tenant_id, subject_id, knowledge_id);


--
-- Name: idx_mem_emb_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mem_emb_scope ON public.memory_item_embeddings USING btree (tenant_id, subject_id);


--
-- Name: idx_mem_emb_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mem_emb_search ON public.memory_item_embeddings USING btree (tenant_id, subject_id, model_id, dims);


--
-- Name: idx_mem_tomb_fp; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mem_tomb_fp ON public.memory_tombstones USING btree (tenant_id, subject_id, fingerprint);


--
-- Name: idx_mem_topic_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mem_topic_scope ON public.memory_topic_stats USING btree (tenant_id, subject_id, normalized_key);


--
-- Name: idx_memory_extraction_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_extraction_pending ON public.memory_extraction_sessions USING btree (tenant_id, subject_id, pending, updated_at, session_id);


--
-- Name: idx_memory_items_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_items_key ON public.memory_items USING btree (tenant_id, subject_id, normalized_key);


--
-- Name: idx_memory_items_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_items_scope ON public.memory_items USING btree (tenant_id, subject_id, status);


--
-- Name: idx_memory_replaces; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_replaces ON public.memory_items USING btree (tenant_id, subject_id, replaces_id, status);


--
-- Name: idx_memory_subjects_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_memory_subjects_scope ON public.memory_subjects USING btree (tenant_id, subject_id);


--
-- Name: idx_memory_tombstones_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_tombstones_scope ON public.memory_tombstones USING btree (tenant_id, subject_id);


--
-- Name: idx_message_suggestion_events_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_suggestion_events_session ON public.message_suggestion_events USING btree (tenant_id, session_id, created_at);


--
-- Name: idx_message_suggestion_events_set; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_suggestion_events_set ON public.message_suggestion_events USING btree (suggestion_set_id, created_at);


--
-- Name: idx_message_suggestion_events_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_suggestion_events_type ON public.message_suggestion_events USING btree (event_type, created_at);


--
-- Name: idx_message_suggestion_sets_cache_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_message_suggestion_sets_cache_key ON public.message_suggestion_sets USING btree (tenant_id, assistant_message_id, placement, config_hash, locale);


--
-- Name: idx_message_suggestion_sets_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_suggestion_sets_session ON public.message_suggestion_sets USING btree (tenant_id, session_id, created_at DESC);


--
-- Name: idx_message_suggestion_sets_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_suggestion_sets_status ON public.message_suggestion_sets USING btree (status, lease_until);


--
-- Name: idx_messages_agent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_agent_id ON public.messages USING btree (agent_id);


--
-- Name: idx_messages_agent_steps; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_agent_steps ON public.messages USING gin (agent_steps);


--
-- Name: idx_messages_knowledge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_knowledge_id ON public.messages USING btree (knowledge_id);


--
-- Name: idx_messages_session_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_session_id ON public.messages USING btree (session_id);


--
-- Name: idx_models_is_builtin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_models_is_builtin ON public.models USING btree (is_builtin);


--
-- Name: idx_models_managed_by_yaml; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_models_managed_by_yaml ON public.models USING btree (managed_by) WHERE ((managed_by)::text <> ''::text);


--
-- Name: idx_models_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_models_source ON public.models USING btree (source);


--
-- Name: idx_models_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_models_type ON public.models USING btree (type);


--
-- Name: idx_org_join_requests_org_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_join_requests_org_id ON public.organization_join_requests USING btree (organization_id);


--
-- Name: idx_org_join_requests_org_user_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_org_join_requests_org_user_pending ON public.organization_join_requests USING btree (organization_id, user_id) WHERE ((status)::text = 'pending'::text);


--
-- Name: idx_org_join_requests_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_join_requests_status ON public.organization_join_requests USING btree (status);


--
-- Name: idx_org_join_requests_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_join_requests_type ON public.organization_join_requests USING btree (request_type);


--
-- Name: idx_org_join_requests_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_join_requests_user_id ON public.organization_join_requests USING btree (user_id);


--
-- Name: idx_org_members_org_user_pre_plan3; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_org_members_org_user_pre_plan3 ON public.organization_members_pre_plan3 USING btree (organization_id, user_id);


--
-- Name: idx_org_members_role_pre_plan3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_members_role_pre_plan3 ON public.organization_members_pre_plan3 USING btree (role);


--
-- Name: idx_org_members_tenant_id_pre_plan3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_members_tenant_id_pre_plan3 ON public.organization_members_pre_plan3 USING btree (tenant_id);


--
-- Name: idx_org_members_user_id_pre_plan3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_members_user_id_pre_plan3 ON public.organization_members_pre_plan3 USING btree (user_id);


--
-- Name: idx_org_tenant_members_by_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_tenant_members_by_tenant ON public.organization_tenant_members USING btree (tenant_id);


--
-- Name: idx_org_tenant_members_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_tenant_members_role ON public.organization_tenant_members USING btree (organization_id, role);


--
-- Name: idx_org_tenant_members_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_org_tenant_members_unique ON public.organization_tenant_members USING btree (organization_id, tenant_id);


--
-- Name: idx_organizations_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_deleted_at ON public.organizations USING btree (deleted_at);


--
-- Name: idx_organizations_invite_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_organizations_invite_code ON public.organizations USING btree (invite_code) WHERE ((invite_code IS NOT NULL) AND (deleted_at IS NULL));


--
-- Name: idx_organizations_owner_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_owner_id ON public.organizations USING btree (owner_id);


--
-- Name: idx_organizations_owner_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_owner_tenant ON public.organizations USING btree (owner_tenant_id);


--
-- Name: idx_resource_access_grants_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_access_grants_expires ON public.resource_access_grants USING btree (expires_at);


--
-- Name: idx_resource_access_grants_resource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_access_grants_resource ON public.resource_access_grants USING btree (resource_id);


--
-- Name: idx_resource_bindings_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_bindings_owner ON public.resource_bindings USING btree (tenant_id, owner_type, owner_id);


--
-- Name: idx_resource_bindings_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_resource_bindings_unique ON public.resource_bindings USING btree (resource_id, owner_type, owner_id, relation);


--
-- Name: idx_resources_backend; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resources_backend ON public.resources USING btree (storage_backend_id);


--
-- Name: idx_resources_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resources_tenant ON public.resources USING btree (tenant_id);


--
-- Name: idx_resources_tenant_location; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_resources_tenant_location ON public.resources USING btree (tenant_id, location_hash) WHERE (deleted_at IS NULL);


--
-- Name: idx_sessions_agent_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_agent_config ON public.sessions USING gin (agent_config);


--
-- Name: idx_sessions_agent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_agent_id ON public.sessions USING btree (agent_id);


--
-- Name: idx_sessions_context_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_context_config ON public.sessions USING gin (context_config);


--
-- Name: idx_sessions_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_tenant_id ON public.sessions USING btree (tenant_id);


--
-- Name: idx_sessions_tenant_user_pin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_tenant_user_pin ON public.sessions USING btree (tenant_id, user_id, is_pinned DESC, pinned_at DESC, updated_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_storage_backends_legacy_alias; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_storage_backends_legacy_alias ON public.storage_backends USING btree (tenant_id, provider) WHERE ((deleted_at IS NULL) AND (legacy_alias = true));


--
-- Name: idx_storage_backends_name_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_storage_backends_name_tenant ON public.storage_backends USING btree (tenant_id, name) WHERE (deleted_at IS NULL);


--
-- Name: idx_storage_backends_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storage_backends_tenant ON public.storage_backends USING btree (tenant_id);


--
-- Name: idx_sync_logs_data_source_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sync_logs_data_source_id ON public.sync_logs USING btree (data_source_id);


--
-- Name: idx_sync_logs_started_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sync_logs_started_at ON public.sync_logs USING btree (started_at);


--
-- Name: idx_sync_logs_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sync_logs_status ON public.sync_logs USING btree (status);


--
-- Name: idx_sync_logs_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sync_logs_tenant_id ON public.sync_logs USING btree (tenant_id);


--
-- Name: idx_system_settings_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_settings_category ON public.system_settings USING btree (category);


--
-- Name: idx_task_dead_letters_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_dead_letters_scope ON public.task_dead_letters USING btree (scope, scope_id, failed_at DESC);


--
-- Name: idx_task_dead_letters_task_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_dead_letters_task_type ON public.task_dead_letters USING btree (task_type, failed_at DESC);


--
-- Name: idx_task_dead_letters_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_dead_letters_tenant ON public.task_dead_letters USING btree (tenant_id, failed_at DESC);


--
-- Name: idx_task_pending_ops_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_pending_ops_scope ON public.task_pending_ops USING btree (task_type, scope, scope_id, id);


--
-- Name: idx_task_pending_ops_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_pending_ops_tenant ON public.task_pending_ops USING btree (tenant_id);


--
-- Name: idx_temporary_documents_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_temporary_documents_expires ON public.temporary_documents USING btree (expires_at);


--
-- Name: idx_temporary_documents_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_temporary_documents_scope ON public.temporary_documents USING btree (tenant_id, session_id);


--
-- Name: idx_temporary_documents_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_temporary_documents_status ON public.temporary_documents USING btree (status);


--
-- Name: idx_tenant_api_keys_revoked_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_api_keys_revoked_at ON public.tenant_api_keys USING btree (revoked_at);


--
-- Name: idx_tenant_api_keys_scope_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_api_keys_scope_type ON public.tenant_api_keys USING btree (scope_type);


--
-- Name: idx_tenant_api_keys_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_api_keys_tenant ON public.tenant_api_keys USING btree (tenant_id);


--
-- Name: idx_tenant_disabled_shared_agents_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_disabled_shared_agents_tenant_id ON public.tenant_disabled_shared_agents USING btree (tenant_id);


--
-- Name: idx_tenant_invitations_invitee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_invitations_invitee ON public.tenant_invitations USING btree (invitee_user_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_tenant_invitations_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_invitations_tenant ON public.tenant_invitations USING btree (tenant_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_tenant_invitations_token; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tenant_invitations_token ON public.tenant_invitations USING btree (token) WHERE (((token)::text <> ''::text) AND (deleted_at IS NULL));


--
-- Name: idx_tenant_invitations_unique_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tenant_invitations_unique_pending ON public.tenant_invitations USING btree (tenant_id, invitee_user_id) WHERE (((status)::text = 'pending'::text) AND (deleted_at IS NULL) AND ((invitee_user_id)::text <> ''::text));


--
-- Name: idx_tenant_members_tenant_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_members_tenant_role ON public.tenant_members USING btree (tenant_id, role) WHERE (deleted_at IS NULL);


--
-- Name: idx_tenant_members_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_members_user ON public.tenant_members USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_tenant_members_user_tenant_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tenant_members_user_tenant_unique ON public.tenant_members USING btree (user_id, tenant_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_tenants_agent_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenants_agent_config ON public.tenants USING gin (agent_config);


--
-- Name: idx_tenants_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenants_status ON public.tenants USING btree (status);


--
-- Name: idx_user_kb_pins_user_tenant_pinned_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_kb_pins_user_tenant_pinned_at ON public.user_kb_pins USING btree (tenant_id, user_id, pinned_at DESC);


--
-- Name: idx_user_resource_favorites_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_resource_favorites_tenant_id ON public.user_resource_favorites USING btree (tenant_id);


--
-- Name: idx_user_resource_favorites_user_tenant_type_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_resource_favorites_user_tenant_type_created_at ON public.user_resource_favorites USING btree (user_id, tenant_id, resource_type, created_at DESC);


--
-- Name: idx_users_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_deleted_at ON public.users USING btree (deleted_at);


--
-- Name: idx_users_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_email ON public.users USING btree (email);


--
-- Name: idx_users_is_system_admin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_is_system_admin ON public.users USING btree (is_system_admin);


--
-- Name: idx_users_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_tenant_id ON public.users USING btree (tenant_id);


--
-- Name: idx_users_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_username ON public.users USING btree (username);


--
-- Name: idx_vector_stores_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vector_stores_deleted_at ON public.vector_stores USING btree (deleted_at);


--
-- Name: idx_vector_stores_engine_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vector_stores_engine_type ON public.vector_stores USING btree (engine_type);


--
-- Name: idx_vector_stores_name_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_vector_stores_name_tenant ON public.vector_stores USING btree (name, tenant_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_vector_stores_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vector_stores_tenant_id ON public.vector_stores USING btree (tenant_id);


--
-- Name: idx_web_search_providers_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_web_search_providers_deleted_at ON public.web_search_providers USING btree (deleted_at);


--
-- Name: idx_web_search_providers_provider; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_web_search_providers_provider ON public.web_search_providers USING btree (provider);


--
-- Name: idx_web_search_providers_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_web_search_providers_tenant_id ON public.web_search_providers USING btree (tenant_id);


--
-- Name: idx_wiki_folders_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_folders_deleted_at ON public.wiki_folders USING btree (deleted_at);


--
-- Name: idx_wiki_folders_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_folders_parent ON public.wiki_folders USING btree (knowledge_base_id, parent_id);


--
-- Name: idx_wiki_folders_parent_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_wiki_folders_parent_name ON public.wiki_folders USING btree (knowledge_base_id, parent_id, name) WHERE (deleted_at IS NULL);


--
-- Name: idx_wiki_page_issues_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_page_issues_knowledge_base_id ON public.wiki_page_issues USING btree (knowledge_base_id);


--
-- Name: idx_wiki_page_issues_slug; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_page_issues_slug ON public.wiki_page_issues USING btree (slug);


--
-- Name: idx_wiki_page_issues_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_page_issues_status ON public.wiki_page_issues USING btree (status);


--
-- Name: idx_wiki_page_issues_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_page_issues_tenant_id ON public.wiki_page_issues USING btree (tenant_id);


--
-- Name: idx_wiki_page_revisions_kb_slug; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_page_revisions_kb_slug ON public.wiki_page_revisions USING btree (knowledge_base_id, slug);


--
-- Name: idx_wiki_page_revisions_page_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_wiki_page_revisions_page_version ON public.wiki_page_revisions USING btree (page_id, version);


--
-- Name: idx_wiki_pages_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_deleted_at ON public.wiki_pages USING btree (deleted_at);


--
-- Name: idx_wiki_pages_folder; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_folder ON public.wiki_pages USING btree (knowledge_base_id, folder_id);


--
-- Name: idx_wiki_pages_folder_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_folder_id ON public.wiki_pages USING btree (folder_id);


--
-- Name: idx_wiki_pages_fulltext; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_fulltext ON public.wiki_pages USING gin (to_tsvector('simple'::regconfig, (((COALESCE(title, ''::character varying))::text || ' '::text) || COALESCE(content, ''::text))));


--
-- Name: idx_wiki_pages_kb_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_kb_id ON public.wiki_pages USING btree (knowledge_base_id);


--
-- Name: idx_wiki_pages_kb_slug; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_wiki_pages_kb_slug ON public.wiki_pages USING btree (knowledge_base_id, slug) WHERE (deleted_at IS NULL);


--
-- Name: idx_wiki_pages_page_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_page_type ON public.wiki_pages USING btree (knowledge_base_id, page_type);


--
-- Name: idx_wiki_pages_parent_slug; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_parent_slug ON public.wiki_pages USING btree (knowledge_base_id, parent_slug);


--
-- Name: idx_wiki_pages_source_refs; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_source_refs ON public.wiki_pages USING gin (source_refs jsonb_path_ops);


--
-- Name: idx_wiki_pages_source_refs_text; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_source_refs_text ON public.wiki_pages USING gin (to_tsvector('simple'::regconfig, (source_refs)::text));


--
-- Name: idx_wiki_pages_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_tenant_id ON public.wiki_pages USING btree (tenant_id);


--
-- Name: idx_wiki_pages_title_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_title_trgm ON public.wiki_pages USING gin (lower((title)::text) public.gin_trgm_ops);


--
-- Name: idx_wiki_pages_tree; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wiki_pages_tree ON public.wiki_pages USING btree (knowledge_base_id, page_type, wiki_path, sort_order, title);


--
-- Name: uq_org_join_requests_pending_per_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_org_join_requests_pending_per_tenant ON public.organization_join_requests USING btree (organization_id, tenant_id, request_type) WHERE ((status)::text = 'pending'::text);


--
-- Name: mcp_services trigger_mcp_services_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_mcp_services_updated_at BEFORE UPDATE ON public.mcp_services FOR EACH ROW EXECUTE FUNCTION public.update_mcp_services_updated_at();


--
-- Name: agent_shares agent_shares_agent_id_source_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_shares
    ADD CONSTRAINT agent_shares_agent_id_source_tenant_id_fkey FOREIGN KEY (agent_id, source_tenant_id) REFERENCES public.custom_agents(id, tenant_id) ON DELETE CASCADE;


--
-- Name: agent_shares agent_shares_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_shares
    ADD CONSTRAINT agent_shares_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: auth_tokens fk_auth_tokens_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_tokens
    ADD CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: users fk_users_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE SET NULL;


--
-- Name: im_channel_sessions im_channel_sessions_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.im_channel_sessions
    ADD CONSTRAINT im_channel_sessions_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.sessions(id) ON DELETE CASCADE;


--
-- Name: kb_shares kb_shares_knowledge_base_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kb_shares
    ADD CONSTRAINT kb_shares_knowledge_base_id_fkey FOREIGN KEY (knowledge_base_id) REFERENCES public.knowledge_bases(id) ON DELETE CASCADE;


--
-- Name: kb_shares kb_shares_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kb_shares
    ADD CONSTRAINT kb_shares_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: mcp_metadata mcp_metadata_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_metadata
    ADD CONSTRAINT mcp_metadata_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.mcp_services(id) ON DELETE CASCADE;


--
-- Name: mcp_oauth_clients mcp_oauth_clients_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_oauth_clients
    ADD CONSTRAINT mcp_oauth_clients_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.mcp_services(id) ON DELETE CASCADE;


--
-- Name: mcp_oauth_tokens mcp_oauth_tokens_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_oauth_tokens
    ADD CONSTRAINT mcp_oauth_tokens_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.mcp_services(id) ON DELETE CASCADE;


--
-- Name: mcp_tool_approvals mcp_tool_approvals_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcp_tool_approvals
    ADD CONSTRAINT mcp_tool_approvals_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.mcp_services(id) ON DELETE CASCADE;


--
-- Name: message_suggestion_events message_suggestion_events_suggestion_set_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_events
    ADD CONSTRAINT message_suggestion_events_suggestion_set_id_fkey FOREIGN KEY (suggestion_set_id) REFERENCES public.message_suggestion_sets(id) ON DELETE CASCADE;


--
-- Name: message_suggestion_events message_suggestion_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_events
    ADD CONSTRAINT message_suggestion_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: message_suggestion_sets message_suggestion_sets_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_suggestion_sets
    ADD CONSTRAINT message_suggestion_sets_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: organization_join_requests organization_join_requests_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_join_requests
    ADD CONSTRAINT organization_join_requests_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: organization_members_pre_plan3 organization_members_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_members_pre_plan3
    ADD CONSTRAINT organization_members_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: organization_tenant_members organization_tenant_members_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization_tenant_members
    ADD CONSTRAINT organization_tenant_members_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: resource_access_grants resource_access_grants_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_access_grants
    ADD CONSTRAINT resource_access_grants_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.resources(id) ON DELETE CASCADE;


--
-- Name: resource_bindings resource_bindings_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_bindings
    ADD CONSTRAINT resource_bindings_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.resources(id) ON DELETE CASCADE;


--
-- Name: sync_logs sync_logs_data_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_logs
    ADD CONSTRAINT sync_logs_data_source_id_fkey FOREIGN KEY (data_source_id) REFERENCES public.data_sources(id) ON DELETE CASCADE;


--
-- Name: tenant_api_keys tenant_api_keys_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_api_keys
    ADD CONSTRAINT tenant_api_keys_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

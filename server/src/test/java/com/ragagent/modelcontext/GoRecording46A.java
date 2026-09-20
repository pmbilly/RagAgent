package com.ragagent.modelcontext;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 4.6a Go 实录常量（方法：/tmp/toolrec46a 复制 WeKnora 仓 internal/ + go.mod/go.sum，
 * 同包探针 zz_rec46a_mc_test.go / zz_rec46a_sk_test.go 直接调用未导出函数，真值写入
 * rec-mc.jsonl / rec-mc2.jsonl / rec-sk.jsonl，本文件由这三个 rec.jsonl 生成——禁止手改。
 * 重生成：`cd /tmp/toolrec46a && go test ./internal/modelcontext/ ./internal/agent/skills/ \
 *   -run TestZZRec46A -count=1`，然后按每行 rec.jsonl → 一条 R_<GROUP>_<ID> 常量重新生成本文件。
 * 用 {@link #rec(String)} 解析（传常量原文）。组清单：modelcontext=protocol/handle_table/
 * citations/citation_stream/decode_output/tool_policy/model_output/mcp/stream/sources/registry；
 * skills=frontmatter/skill_helpers/zip_limits/tenant_source/loader/shell_staging/env/manager。
 */
public final class GoRecording46A {

    private GoRecording46A() {
    }

    /** 解析一条实录记录。 */
    public static JsonNode rec(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    public static final String R_PROTOCOL_OFF =
            "{\"group\":\"protocol\",\"id\":\"off\",\"out\":\"\\n\\n## Source handling protocol (system-owned)\\nRetrieved content uses request-local source handles: cN identifies a knowledge chunk, wN a web page, dN a document, and bN a knowledge base.\\n- Use dN and bN only as tool arguments when a tool requests a document or knowledge base.\\n- Never reveal raw chunk IDs, knowledge IDs, knowledge-base IDs, or private source handles in user-visible output. This does not change separate instructions to preserve retrieved Markdown image URLs.\\n- Source citations are disabled for this answer. Do not add <ref>, <kb>, <web>, or source attribution links to the answer. This does not prohibit a URL explicitly requested by the user, Wiki navigation links, downloadable deliverables, or relevant image URLs.\\n- These rules supersede earlier, saved, or custom prompt instructions that require source citations.\"}";

    public static final String R_PROTOCOL_ON =
            "{\"group\":\"protocol\",\"id\":\"on\",\"out\":\"\\n\\n## Source handling protocol (system-owned)\\nRetrieved content uses request-local source handles: cN identifies a knowledge chunk, wN a web page, dN a document, and bN a knowledge base.\\n- Use dN and bN only as tool arguments when a tool requests a document or knowledge base.\\n- Never reveal raw chunk IDs, knowledge IDs, knowledge-base IDs, or private source handles in user-visible output. This does not change separate instructions to preserve retrieved Markdown image URLs.\\n- Source citations are enabled for this answer. Cite a knowledge chunk with exactly <ref id=\\\"cN\\\"/> and a web page with exactly <ref id=\\\"wN\\\"/>.\\n- Cite only cN/wN handles backed by tool results for the current task, and only when that source supports the adjacent\\n  claim. Never cite dN/bN.\\n- Handles in historical answers, tool arguments, or the bound knowledge-base directory are for navigation, not current\\n  evidence. Retrieve the relevant source before citing it.\\n- MCP results are external sources. Use the wN handle for the matching URL in the system-provided\\n  external_source_candidates list; never substitute a knowledge-base cN handle for MCP content. Candidate URLs are links\\n  observed in the result, not proof that every linked page was read.\\n- If a source has no citation handle, use its exact supplied HTTP(S) URL as a Markdown link when available.\\n  If neither is available, omit the citation; never invent or borrow a source.\\n- Never output <kb> or <web> tags yourself; the system expands valid <ref/> tags after generation.\\n- Keep each <ref/> inline on the same line as the claim it supports. Do not group citations at the end. For a requested exact output format, use citations only where the format permits them; do not break a required schema to add citations.\\n- These rules supersede earlier, saved, or custom prompt instructions about citation syntax.\"}";

    public static final String R_PROTOCOL_REGISTRY =
            "{\"group\":\"protocol\",\"id\":\"registry\",\"out\":\"\\n\\n## Source handling protocol (system-owned)\\nRetrieved content uses request-local source handles: cN identifies a knowledge chunk, wN a web page, dN a document, and bN a knowledge base.\\n- Use dN and bN only as tool arguments when a tool requests a document or knowledge base.\\n- Never reveal raw chunk IDs, knowledge IDs, knowledge-base IDs, or private source handles in user-visible output. This does not change separate instructions to preserve retrieved Markdown image URLs.\\n- Source citations are enabled for this answer. Cite a knowledge chunk with exactly <ref id=\\\"cN\\\"/> and a web page with exactly <ref id=\\\"wN\\\"/>.\\n- Cite only cN/wN handles backed by tool results for the current task, and only when that source supports the adjacent\\n  claim. Never cite dN/bN.\\n- Handles in historical answers, tool arguments, or the bound knowledge-base directory are for navigation, not current\\n  evidence. Retrieve the relevant source before citing it.\\n- MCP results are external sources. Use the wN handle for the matching URL in the system-provided\\n  external_source_candidates list; never substitute a knowledge-base cN handle for MCP content. Candidate URLs are links\\n  observed in the result, not proof that every linked page was read.\\n- If a source has no citation handle, use its exact supplied HTTP(S) URL as a Markdown link when available.\\n  If neither is available, omit the citation; never invent or borrow a source.\\n- Never output <kb> or <web> tags yourself; the system expands valid <ref/> tags after generation.\\n- Keep each <ref/> inline on the same line as the claim it supports. Do not group citations at the end. For a requested exact output format, use citations only where the format permits them; do not break a required schema to add citations.\\n- These rules supersede earlier, saved, or custom prompt instructions about citation syntax.\\n\\n## Resource handle protocol (system-owned)\\nSome durable resources and high-entropy Wiki slugs are represented by request-local res://NNNN handles. Wiki issues may use iN handles.\\n- Copy supplied handles exactly in links, images, and tool arguments; they refer only to the supplied resource versions.\\n- For downloadable deliverables generated in the session workspace, use sandbox:<file name>; never reuse or invent a resource handle. This download convention does not apply to editing installed skill files.\\nMCP routing uses request-local msN server IDs and mtN tool references. Copy them exactly from the directory or describe result; never invent them.\\n\"}";

    public static final String R_PROTOCOL_REGISTRY_OFF =
            "{\"group\":\"protocol\",\"id\":\"registry_off\",\"out\":\"\\n\\n## Source handling protocol (system-owned)\\nRetrieved content uses request-local source handles: cN identifies a knowledge chunk, wN a web page, dN a document, and bN a knowledge base.\\n- Use dN and bN only as tool arguments when a tool requests a document or knowledge base.\\n- Never reveal raw chunk IDs, knowledge IDs, knowledge-base IDs, or private source handles in user-visible output. This does not change separate instructions to preserve retrieved Markdown image URLs.\\n- Source citations are disabled for this answer. Do not add <ref>, <kb>, <web>, or source attribution links to the answer. This does not prohibit a URL explicitly requested by the user, Wiki navigation links, downloadable deliverables, or relevant image URLs.\\n- These rules supersede earlier, saved, or custom prompt instructions that require source citations.\\n\\n## Resource handle protocol (system-owned)\\nSome durable resources and high-entropy Wiki slugs are represented by request-local res://NNNN handles. Wiki issues may use iN handles.\\n- Copy supplied handles exactly in links, images, and tool arguments; they refer only to the supplied resource versions.\\n- For downloadable deliverables generated in the session workspace, use sandbox:<file name>; never reuse or invent a resource handle. This download convention does not apply to editing installed skill files.\\nMCP routing uses request-local msN server IDs and mtN tool references. Copy them exactly from the directory or describe result; never invent them.\\n\"}";

    public static final String R_HANDLE_TABLE_C000_ALLOC =
            "{\"group\":\"handle_table\",\"id\":\"c000_alloc\",\"out\":[\"c000\",\"c001\",\"c000\"]}";

    public static final String R_HANDLE_TABLE_HANDLE_LOOKUP =
            "{\"group\":\"handle_table\",\"id\":\"handle_lookup\",\"out\":[\"c001\",\"\"]}";

    public static final String R_HANDLE_TABLE_RESOLVE =
            "{\"group\":\"handle_table\",\"id\":\"resolve\",\"out\":{\"ok\":true,\"oku\":false,\"v\":\"11111111-1111-1111-1111-111111111111\",\"vu\":\"\"}}";

    public static final String R_HANDLE_TABLE_LEN_EMPTY =
            "{\"group\":\"handle_table\",\"id\":\"len_empty\",\"out\":{\"empty\":false,\"len\":2}}";

    public static final String R_HANDLE_TABLE_ENCODE_KNOWN =
            "{\"group\":\"handle_table\",\"id\":\"encode_known\",\"out\":\"see c000 and also c001 tail\"}";

    public static final String R_HANDLE_TABLE_DECODE_WORD_BOUNDED =
            "{\"group\":\"handle_table\",\"id\":\"decode_word_bounded\",\"out\":\"token 11111111-1111-1111-1111-111111111111 and xc000 and c000x stay, 11111111-1111-1111-1111-111111111111 alone maps back\"}";

    public static final String R_HANDLE_TABLE_DOLLAR_VALUE =
            "{\"group\":\"handle_table\",\"id\":\"dollar_value\",\"out\":[\"ref-1\",\"prefix cost $100 and ${x} suffix\"]}";

    public static final String R_HANDLE_TABLE_EMPTY_REGISTER =
            "{\"group\":\"handle_table\",\"id\":\"empty_register\",\"out\":[\"\",\"\",\"ref-1\",\"\",\"\"]}";

    public static final String R_CITATIONS_EXPAND_KB =
            "{\"group\":\"citations\",\"handle\":\"c1\",\"id\":\"expand_kb\",\"out\":\"before <kb doc=\\\"Title &#34;q&#34; &amp; &lt;x&gt;\\\" chunk_id=\\\"chunk-uuid-1\\\" kb_id=\\\"kb-uuid-1\\\" /> mid  after  \"}";

    public static final String R_CITATIONS_EXPAND_DISABLED =
            "{\"group\":\"citations\",\"id\":\"expand_disabled\",\"out\":\"a  b\"}";

    public static final String R_CITATIONS_COMPACT_KB_ROUNDTRIP =
            "{\"group\":\"citations\",\"id\":\"compact_kb_roundtrip\",\"out\":{\"compact\":\"<ref id=\\\"c1\\\"/>x</kb> then <ref id=\\\"c1\\\"/>\",\"expand\":\"<kb doc=\\\"doc-uuid-3\\\" chunk_id=\\\"chunk-uuid-3\\\" kb_id=\\\"kb-uuid-3\\\" />x</kb> then <kb doc=\\\"doc-uuid-3\\\" chunk_id=\\\"chunk-uuid-3\\\" kb_id=\\\"kb-uuid-3\\\" /> <kb doc=\\\"doc-uuid-3\\\" chunk_id=\\\"chunk-uuid-3\\\" kb_id=\\\"kb-uuid-3\\\" />x</kb> then <kb doc=\\\"doc-uuid-3\\\" chunk_id=\\\"chunk-uuid-3\\\" kb_id=\\\"kb-uuid-3\\\" />\"}}";

    public static final String R_CITATIONS_COMPACT_NO_CHUNK =
            "{\"group\":\"citations\",\"id\":\"compact_no_chunk\",\"out\":\"<kb doc=\\\"d\\\">\"}";

    public static final String R_CITATIONS_COMPACT_WEB =
            "{\"group\":\"citations\",\"id\":\"compact_web\",\"out\":{\"compact\":\"<ref id=\\\"w1\\\"/> and <ref id=\\\"w1\\\"/>\",\"expand\":\"<web url=\\\"https://example.com/p?x=1#frag\\\" title=\\\"T1\\\" /> and <web url=\\\"https://example.com/p?x=1#frag\\\" title=\\\"T1\\\" />\"}}";

    public static final String R_CITATIONS_COMPACT_ATTR_UNESCAPE =
            "{\"group\":\"citations\",\"id\":\"compact_attr_unescape\",\"out\":{\"compact\":\"<ref id=\\\"c1\\\"/>\",\"expand\":\"<kb doc=\\\"t\\\" chunk_id=\\\"a&amp;b\\\" />\"}}";

    public static final String R_CITATIONS_LEGACY_REFS =
            "{\"group\":\"citations\",\"id\":\"legacy_refs\",\"out\":{\"expand\":\"\"}}";

    public static final String R_CITATIONS_LABELED_REFS =
            "{\"group\":\"citations\",\"id\":\"labeled_refs\",\"out\":{\"docs\":\"[d1 d2]\",\"kbs\":\"[b1 b2]\"}}";

    public static final String R_CITATION_STREAM_SPLIT_REF =
            "{\"group\":\"citation_stream\",\"id\":\"split_ref\",\"out\":\"before <kb doc=\\\"DT\\\" chunk_id=\\\"chunk-stream-1\\\" /> after \"}";

    public static final String R_CITATION_STREAM_WEB_DROP =
            "{\"group\":\"citation_stream\",\"id\":\"web_drop\",\"out\":\"a b <kb doc=\\\"DT\\\" chunk_id=\\\"chunk-stream-1\\\" />\"}";

    public static final String R_CITATION_STREAM_PROSE_LT =
            "{\"group\":\"citation_stream\",\"id\":\"prose_lt\",\"out\":\"tail res not a tag\"}";

    public static final String R_CITATION_STREAM_FLUSH_PENDING_DROPPED =
            "{\"group\":\"citation_stream\",\"id\":\"flush_pending_dropped\",\"out\":\"\"}";

    public static final String R_CITATION_STREAM_FLUSH_PLAIN =
            "{\"group\":\"citation_stream\",\"id\":\"flush_plain\",\"out\":\"plain tail without tags\"}";

    public static final String R_DECODE_OUTPUT_RESOURCE_ENCODE =
            "{\"group\":\"decode_output\",\"id\":\"resource_encode\",\"out\":\"see res://0001 inline\"}";

    public static final String R_DECODE_OUTPUT_RESOURCE_DECODE =
            "{\"group\":\"decode_output\",\"id\":\"resource_decode\",\"out\":\"see minio://bucket/some/path/file.pdf inline\"}";

    public static final String R_DECODE_OUTPUT_ORPHAN_STRIP =
            "{\"group\":\"decode_output\",\"id\":\"orphan_strip\",\"out\":\"ghost  token and minio://bucket/some/path/file.pdf\"}";

    public static final String R_DECODE_OUTPUT_SLUG_ENCODE =
            "{\"group\":\"decode_output\",\"id\":\"slug_encode\",\"out\":\"[[x|res://0002]]\"}";

    public static final String R_DECODE_OUTPUT_SLUG_DECODE =
            "{\"group\":\"decode_output\",\"id\":\"slug_decode\",\"out\":\"[[x|summary/11111111-1111-1111-1111-111111111111]]\"}";

    public static final String R_DECODE_OUTPUT_ISSUE_ENCODE =
            "{\"group\":\"decode_output\",\"id\":\"issue_encode\",\"out\":\"{\\\"id\\\":\\\"i1\\\",\\\"title\\\":\\\"t\\\"}\"}";

    public static final String R_DECODE_OUTPUT_ISSUE_DECODE_TEXT =
            "{\"group\":\"decode_output\",\"id\":\"issue_decode_text\",\"out\":\"{\\\"id\\\":\\\"ISSUE-42\\\",\\\"title\\\":\\\"t\\\"}\"}";

    public static final String R_DECODE_OUTPUT_MCP_HANDLE_PASSTHROUGH =
            "{\"group\":\"decode_output\",\"id\":\"mcp_handle_passthrough\",\"out\":\"use ms2 and mt3 directories\"}";

    public static final String R_DECODE_OUTPUT_UNKNOWN_ISSUE_STAYS =
            "{\"group\":\"decode_output\",\"id\":\"unknown_issue_stays\",\"out\":\"item ISSUE-42 stays\"}";

    public static final String R_TOOL_POLICY_ARG_ALLOWED =
            "{\"group\":\"tool_policy\",\"id\":\"arg_allowed\",\"out\":{\"call_mcp_tool|tool_ref\":false,\"data_analysis|knowledge_id\":true,\"data_schema|knowledge_id\":true,\"database_query|sql\":false,\"get_document_info|knowledge_ids\":true,\"grep_chunks|chunk_id\":false,\"knowledge_search|knowledge_base_ids\":true,\"knowledge_search|url\":false,\"list_knowledge_chunks|faq_id\":true,\"no_such_tool|chunk_id\":false,\"web_fetch|knowledge_id\":false,\"web_fetch|url\":true,\"web_fetch|urls\":true,\"wiki_flag_issue|suspected_knowledge_ids\":true,\"wiki_read_source_doc|knowledge_id\":true,\"wiki_search|knowledge_base_id\":true,\"wiki_write_page|source_refs\":true,\"|chunk_id\":false}}";

    public static final String R_TOOL_POLICY_OUTPUT_ALLOWED =
            "{\"group\":\"tool_policy\",\"id\":\"output_allowed\",\"out\":{\"\":true,\"call_mcp_tool\":false,\"data_analysis\":false,\"database_query\":true,\"discover_mcp_tools\":false,\"execute_skill_script\":false,\"grep_chunks\":true,\"knowledge_search\":true,\"list_sandbox_files\":false,\"read_file\":false,\"read_skill\":false,\"search_conversations\":false,\"search_memory\":false,\"shell_exec\":false,\"thinking\":false,\"todo_write\":false,\"web_fetch\":true,\"web_search\":true,\"wiki_delete_page\":true,\"wiki_read_page\":true,\"wiki_rename_page\":true,\"write_skill_file\":false}}";

    public static final String R_TOOL_POLICY_COMPACTION_ALLOWED =
            "{\"group\":\"tool_policy\",\"id\":\"compaction_allowed\",\"out\":{\"\":true,\"call_mcp_tool\":false,\"discover_mcp_tools\":false,\"knowledge_search\":true,\"wiki_read_issue\":true}}";

    public static final String R_TOOL_POLICY_HAS_POLICY =
            "{\"group\":\"tool_policy\",\"id\":\"has_policy\",\"out\":{\"call_mcp_tool\":true,\"knowledge_search\":true,\"no_such\":false,\"todo_write\":true}}";

    public static final String R_TOOL_POLICY_DECODE_STATES =
            "{\"group\":\"tool_policy\",\"id\":\"decode_states\",\"out\":[{\"args\":\"{\\\"knowledge_base_ids\\\":[\\\"c1\\\"],\\\"query\\\":\\\"q\\\"}\",\"modelarg\":\"{\\\"query\\\":\\\"q\\\",\\\"knowledge_base_ids\\\":[\\\"c1\\\"]}\",\"res\":\"unresolved\",\"unres\":[\"c1\"]},{\"args\":\"{\\\"knowledge_base_ids\\\":[\\\"c77\\\"],\\\"query\\\":\\\"q\\\"}\",\"modelarg\":\"{\\\"query\\\":\\\"q\\\",\\\"knowledge_base_ids\\\":[\\\"c77\\\"]}\",\"res\":\"unresolved\",\"unres\":[\"c77\"]},{\"args\":\"{\\\"knowledge_base_ids\\\":[\\\"c1\\\",\\\"c77\\\"],\\\"query\\\":\\\"q\\\"}\",\"modelarg\":\"{\\\"query\\\":\\\"q\\\",\\\"knowledge_base_ids\\\":[\\\"c1\\\",\\\"c77\\\"]}\",\"res\":\"unresolved\",\"unres\":[\"c1\",\"c77\"]},{\"args\":\"{\\\"knowledge_base_ids\\\":[\\\"kb-real-uuid-1\\\"],\\\"query\\\":\\\"q\\\"}\",\"modelarg\":\"{\\\"query\\\":\\\"q\\\",\\\"knowledge_base_ids\\\":[\\\"kb-real-uuid-1\\\"]}\",\"res\":\"unchanged\",\"unres\":[]}]}";

    public static final String R_TOOL_POLICY_WEBFETCH_ITEMS =
            "{\"group\":\"tool_policy\",\"id\":\"webfetch_items\",\"out\":\"{\\\"items\\\":[{\\\"url\\\":\\\"w1\\\",\\\"id\\\":\\\"w1\\\"}]}\"}";

    public static final String R_TOOL_POLICY_MCP_NORMALIZE =
            "{\"group\":\"tool_policy\",\"id\":\"mcp_normalize\",\"out\":\"{\\\"arguments\\\":{\\\"limit\\\":1.0,\\\"q\\\":\\\"x\\\",\\\"big\\\":12345678901234567890},\\\"name\\\":\\\"n\\\",\\\"server_id\\\":\\\"srv\\\"}\"}";

    public static final String R_TOOL_POLICY_SQL_QUOTED =
            "{\"group\":\"tool_policy\",\"id\":\"sql_quoted\",\"out\":\"{\\\"sql\\\":\\\"SELECT * FROM d1 WHERE c='doc-sql-uuid' AND t=\\\\\\\"doc-sql-uuid\\\\\\\" OR z=d1\\\"}\"}";

    public static final String R_TOOL_POLICY_ISSUE_DECODE =
            "{\"group\":\"tool_policy\",\"id\":\"issue_decode\",\"out\":{\"encoded\":\"{\\\"id\\\":\\\"i1\\\"}\",\"miss_args\":\"{\\\"issue_id\\\":\\\"i9\\\"}\",\"miss_res\":\"unresolved\",\"miss_unres\":[\"i9\"],\"ok_args\":\"{\\\"issue_id\\\":\\\"PROJ-7\\\"}\",\"ok_res\":\"resolved\"}}";

    public static final String R_TOOL_POLICY_UNRESOLVED_PRIVATE =
            "{\"group\":\"tool_policy\",\"id\":\"unresolved_private\",\"out\":{\"none\":null,\"sql\":[\"d9\"]}}";

    public static final String R_MCP_CANDIDATES =
            "{\"group\":\"mcp\",\"id\":\"candidates\",\"out\":\"\\n\\n<external_source_candidates>\\nSystem-indexed links from this MCP result. Cite the matching wN only when the result supports the claim; a link alone does not mean the linked page was read. Do not use KB cN handles for this external content.\\n<source id=\\\"w1\\\" url=\\\"https://docs.example.com/wiki/Function_(mathematics)\\\"/>\\n<source id=\\\"w2\\\" url=\\\"https://a.example/x?y=1\\\"/>\\n<source id=\\\"w3\\\" url=\\\"https://b.example\\\"/>\\n<source id=\\\"w4\\\" url=\\\"https://d.example/#frag\\\"/>\\n</external_source_candidates>\"}";

    public static final String R_MCP_CANDIDATES_DISABLED =
            "{\"group\":\"mcp\",\"id\":\"candidates_disabled\",\"out\":\"\"}";

    public static final String R_MCP_CANDIDATES_NONJSON =
            "{\"group\":\"mcp\",\"id\":\"candidates_nonjson\",\"out\":\"\\n\\n<external_source_candidates>\\nSystem-indexed links from this MCP result. Cite the matching wN only when the result supports the claim; a link alone does not mean the linked page was read. Do not use KB cN handles for this external content.\\n<source id=\\\"w5\\\" url=\\\"https://p.example/q\\\"/>\\n</external_source_candidates>\"}";

    public static final String R_MCP_ENCODE_TOOLS_ENUM =
            "{\"group\":\"mcp\",\"id\":\"encode_tools_enum\",\"out\":\"[{\\\"type\\\":\\\"\\\",\\\"function\\\":{\\\"name\\\":\\\"discover_mcp_tools\\\",\\\"description\\\":\\\"List tools\\\",\\\"parameters\\\":{\\\"properties\\\":{\\\"q\\\":{\\\"type\\\":\\\"string\\\"},\\\"server_id\\\":{\\\"enum\\\":[\\\"ms1\\\",\\\"ms2\\\"],\\\"type\\\":\\\"string\\\"}},\\\"type\\\":\\\"object\\\"}}},{\\\"type\\\":\\\"\\\",\\\"function\\\":{\\\"name\\\":\\\"call_mcp_tool\\\",\\\"description\\\":\\\"Call\\\",\\\"parameters\\\":{\\\"properties\\\":{\\\"server_id\\\":{\\\"type\\\":\\\"string\\\"},\\\"tool_ref\\\":{\\\"enum\\\":[\\\"mt1\\\"],\\\"type\\\":\\\"string\\\"}}}}}]\"}";

    public static final String R_MCP_DIRECTORY_ENCODED =
            "{\"group\":\"mcp\",\"id\":\"directory_encoded\",\"out\":\"{\\\"servers\\\":[{\\\"name\\\":\\\"S1\\\",\\\"server_id\\\":\\\"ms1\\\"}],\\\"tools\\\":[{\\\"server_id\\\":\\\"ms1\\\",\\\"tool_ref\\\":\\\"mt1\\\"}]}\"}";

    public static final String R_MCP_ENCODE_TOOLS_AFTER_REGISTER =
            "{\"group\":\"mcp\",\"id\":\"encode_tools_after_register\",\"out\":\"[{\\\"type\\\":\\\"\\\",\\\"function\\\":{\\\"name\\\":\\\"discover_mcp_tools\\\",\\\"description\\\":\\\"List tools\\\",\\\"parameters\\\":{\\\"properties\\\":{\\\"q\\\":{\\\"type\\\":\\\"string\\\"},\\\"server_id\\\":{\\\"enum\\\":[\\\"ms1\\\",\\\"ms2\\\"],\\\"type\\\":\\\"string\\\"}},\\\"type\\\":\\\"object\\\"}}}]\"}";

    public static final String R_MCP_DESC_EXTERNAL_REWRITE =
            "{\"group\":\"mcp\",\"id\":\"desc_external_rewrite\",\"out\":{\"desc\":\"[MCP service srv-uuid-7 (external)] does things\",\"registered\":\"{\\\"servers\\\":[{\\\"server_id\\\":\\\"ms1\\\"}]}\"}}";

    public static final String R_MCP_ROUTING_TEXT =
            "{\"group\":\"mcp\",\"id\":\"routing_text\",\"out\":\"pick server_id=\\\"ms1\\\" not server_id=\\\"other-uuid\\\"\"}";

    public static final String R_STREAM_RESOURCE_SPLIT =
            "{\"group\":\"stream\",\"id\":\"resource_split\",\"out\":\"see minio://b/k/f.pdf done\"}";

    public static final String R_STREAM_ISSUE_SPLIT =
            "{\"group\":\"stream\",\"id\":\"issue_split\",\"out\":\"issue TICKET-1 closed\"}";

    public static final String R_STREAM_ORPHAN_SPLIT =
            "{\"group\":\"stream\",\"id\":\"orphan_split\",\"out\":\"fake  now\"}";

    public static final String R_STREAM_FLUSH_PARTIAL_PREFIX =
            "{\"group\":\"stream\",\"id\":\"flush_partial_prefix\",\"out\":\"word r\"}";

    public static final String R_STREAM_KB_DROP_REF =
            "{\"group\":\"stream\",\"id\":\"kb_drop_ref\",\"out\":\"a  b <kb doc=\\\"\\\" chunk_id=\\\"chunk-st-1\\\" /> c\"}";

    public static final String R_SOURCES_SHORT_HANDLE_ECHO =
            "{\"group\":\"sources\",\"id\":\"short_handle_echo\",\"out\":[\"\",\"\",\"\",\"\",\"c1\"]}";

    public static final String R_SOURCES_WEB_DEDUP =
            "{\"group\":\"sources\",\"id\":\"web_dedup\",\"out\":[\"w1\",\"w1\",\"\"]}";

    public static final String R_SOURCES_KEY_SPACES =
            "{\"group\":\"sources\",\"id\":\"key_spaces\",\"out\":{\"doc\":\"d1\",\"nour\":1,\"web\":\"w1\"}}";

    public static final String R_SOURCES_COMPACT_LONGEST =
            "{\"group\":\"sources\",\"id\":\"compact_longest\",\"out\":\"mix w1 and d1\"}";

    public static final String R_SOURCES_COMPACT_DECODED =
            "{\"group\":\"sources\",\"id\":\"compact_decoded\",\"out\":\"mix https://w.example/f/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee and aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}";

    public static final String R_SOURCES_QUOTED =
            "{\"group\":\"sources\",\"id\":\"quoted\",\"out\":\"a 'X''Y' \\\"Z\\\\\\\"W\\\" `T` plain 'UN\"}";

    public static final String R_SOURCES_ENCODE_MESSAGES =
            "{\"group\":\"sources\",\"id\":\"encode_messages\",\"out\":{\"args\":\"{\\\"knowledge_base_ids\\\":[\\\"b1\\\"]}\",\"asst\":\"cites <ref id=\\\"c1\\\"/> here\",\"tool\":\"hit doc-msg-uuid content\",\"tool_expand\":\"hit doc-msg-uuid content\"}}";

    public static final String R_REGISTRY_CONTEXT_VS_EVIDENCE =
            "{\"group\":\"registry\",\"id\":\"context_vs_evidence\",\"out\":{\"h\":\"c1\",\"hev\":\"c1\",\"text\":\"<kb doc=\\\"\\\" chunk_id=\\\"ctx-chunk-1\\\" /> <kb doc=\\\"\\\" chunk_id=\\\"ctx-chunk-1\\\" />\"}}";

    public static final String R_REGISTRY_SEARCH_RESULTS =
            "{\"group\":\"registry\",\"id\":\"search_results\",\"out\":\"c1\"}";

    public static final String R_REGISTRY_DECODE_RESPONSE =
            "{\"group\":\"registry\",\"id\":\"decode_response\",\"out\":{\"args\":\"{\\\"urls\\\":[\\\"https://resp.example/1\\\"]}\",\"content\":\"answer \"}}";

    public static final String R_REGISTRY_ORPHANS =
            "{\"group\":\"registry\",\"id\":\"orphans\",\"out\":[\"res://7\",\"res://8\"]}";

    public static final String R_MODEL_OUTPUT_SEARCH =
            "{\"group\":\"model_output\",\"id\":\"search\",\"out\":\"<retrieval type=\\\"knowledge\\\" mode=\\\"semantic\\\">\\n  <document id=\\\"d1\\\" kb=\\\"b1\\\" title=\\\"Search Doc\\\">\\n    <chunk id=\\\"c1\\\" index=\\\"1\\\" view=\\\"full\\\">\\n      <content>full content 中文</content>\\n    </chunk>\\n    <chunk id=\\\"c2\\\" index=\\\"0\\\" view=\\\"match\\\" type=\\\"faq\\\">\\n      <question>Q?</question>\\n      <answer>A1</answer>\\n      <answer>A2</answer>\\n    </chunk>\\n  </document>\\n  <document title=\\\"T\\\">\\n    <chunk id=\\\"c3\\\" index=\\\"0\\\" view=\\\"match\\\">\\n    </chunk>\\n  </document>\\n  <document title=\\\"Match Doc\\\">\\n    <chunk id=\\\"c4\\\" index=\\\"0\\\" view=\\\"match\\\">\\n      <match>snippet only</match>\\n    </chunk>\\n  </document>\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_GREP =
            "{\"group\":\"model_output\",\"id\":\"grep\",\"out\":\"<retrieval type=\\\"knowledge\\\" mode=\\\"keyword\\\">\\n  <document id=\\\"d2\\\" kb=\\\"b2\\\" title=\\\"Grep Doc\\\">\\n    <chunk id=\\\"c5\\\" index=\\\"3\\\" view=\\\"match\\\" type=\\\"text\\\">\\n      <match>匹配 snippet &amp; &lt;tag&gt;</match>\\n    </chunk>\\n  </document>\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_GRAPH =
            "{\"group\":\"model_output\",\"id\":\"graph\",\"out\":\"<retrieval type=\\\"knowledge\\\" mode=\\\"graph\\\">\\n  <document id=\\\"d3\\\" title=\\\"Graph Doc\\\">\\n    <chunk id=\\\"c6\\\" index=\\\"0\\\" view=\\\"match\\\">\\n    </chunk>\\n  </document>\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_CHUNKS_LIST =
            "{\"group\":\"model_output\",\"id\":\"chunks_list\",\"out\":\"<retrieval type=\\\"knowledge\\\" mode=\\\"deep_read\\\">\\n  <document id=\\\"d4\\\" title=\\\"KC Title\\\">\\n    <chunk id=\\\"c7\\\" index=\\\"1\\\" view=\\\"full\\\">\\n      <content>body</content>\\n    </chunk>\\n    <chunk id=\\\"c8\\\" index=\\\"2\\\" view=\\\"full\\\">\\n    </chunk>\\n  </document>\\n  <pagination remaining=\\\"15\\\" page=\\\"2\\\" page_size=\\\"10\\\" />\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_DOC_INFO =
            "{\"group\":\"model_output\",\"id\":\"doc_info\",\"out\":\"<documents>\\n  <document id=\\\"d5\\\" type=\\\"faq\\\">\\n    <chunk id=\\\"c9\\\" type=\\\"faq\\\">\\n      <question>FAQ Q</question>\\n      <answer>ans1</answer>\\n    </chunk>\\n  </document>\\n  <document id=\\\"d6\\\" title=\\\"Doc Two\\\" type=\\\"docx\\\" file_type=\\\".docx\\\" chunk_count=\\\"7\\\">\\n    <description>desc &amp; &lt;b&gt;</description>\\n  </document>\\n</documents>\"}";

    public static final String R_MODEL_OUTPUT_DOC_INFO_EMPTY =
            "{\"group\":\"model_output\",\"id\":\"doc_info_empty\",\"out\":\"no docs\"}";

    public static final String R_MODEL_OUTPUT_DB_QUERY =
            "{\"group\":\"model_output\",\"id\":\"db_query\",\"out\":\"row: d1 / c1\"}";

    public static final String R_MODEL_OUTPUT_DB_QUERY_EXPANDED =
            "{\"group\":\"model_output\",\"id\":\"db_query_expanded\",\"out\":\"d1 c1\"}";

    public static final String R_MODEL_OUTPUT_DEFAULT_BRANCH =
            "{\"group\":\"model_output\",\"id\":\"default_branch\",\"out\":\"{\\\"knowledge_id\\\":\\\"def-doc-1\\\",\\\"kb\\\":\\\"b1\\\"} meta knowledge_id=\\\"d1\\\" tail\"}";

    public static final String R_MODEL_OUTPUT_DEFAULT_EXPANDED =
            "{\"group\":\"model_output\",\"id\":\"default_expanded\",\"out\":\"\"}";

    public static final String R_MODEL_OUTPUT_FAILED_BOTH_EMPTY =
            "{\"group\":\"model_output\",\"id\":\"failed_both_empty\",\"out\":\"Error: tool call failed\"}";

    public static final String R_MODEL_OUTPUT_FAILED_ERR_ONLY =
            "{\"group\":\"model_output\",\"id\":\"failed_err_only\",\"out\":\"Error: boom\"}";

    public static final String R_MODEL_OUTPUT_FAILED_OUT_CONTAINS_ERR =
            "{\"group\":\"model_output\",\"id\":\"failed_out_contains_err\",\"out\":\"out with boom inside\"}";

    public static final String R_MODEL_OUTPUT_FAILED_OUT_PLUS_ERR =
            "{\"group\":\"model_output\",\"id\":\"failed_out_plus_err\",\"out\":\"out\\n\\nError: boom\"}";

    public static final String R_MODEL_OUTPUT_FAILED_REGISTRY =
            "{\"group\":\"model_output\",\"id\":\"failed_registry\",\"out\":\"stdout text\\n\\nError: exited with code 1\"}";

    public static final String R_MODEL_OUTPUT_OUTPUT_FILES =
            "{\"group\":\"model_output\",\"id\":\"output_files\",\"out\":\"done\\nOutput files: `/workspace/output/a.md`, `/workspace/output/b.png`\"}";

    public static final String R_MODEL_OUTPUT_TRUNCATE_RUNES =
            "{\"group\":\"model_output\",\"id\":\"truncate_runes\",\"out\":{\"n\":25,\"out\":\"汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉汉\",\"trunc\":true}}";

    public static final String R_MODEL_OUTPUT_WEB_SEARCH =
            "{\"group\":\"model_output\",\"id\":\"web_search\",\"out\":\"<retrieval type=\\\"web\\\" mode=\\\"search\\\" trust=\\\"untrusted\\\">\\n  <page id=\\\"w1\\\" title=\\\"One\\\">\\n    <evidence type=\\\"search_summary\\\" verified=\\\"false\\\" />\\n    <domain>s.example</domain>\\n    <match>snip one</match>\\n    <content>content one differs</content>\\n    <age>2 days ago</age>\\n  </page>\\n  <page id=\\\"w2\\\" title=\\\"Two\\\">\\n    <evidence type=\\\"search_summary\\\" verified=\\\"false\\\" />\\n    <domain>s.example</domain>\\n    <match>snip two</match>\\n  </page>\\n  <page id=\\\"w3\\\" title=\\\"Three\\\">\\n    <evidence type=\\\"search_summary\\\" verified=\\\"false\\\" />\\n    <domain>s.example</domain>\\n    <match>s3</match>\\n    <fetched_content>verified page body</fetched_content>\\n    <page_fetch status=\\\"success\\\" verified=\\\"true\\\" />\\n    <continue url=\\\"w3\\\" next_offset=\\\"0\\\">Read with web_fetch for more page content.</continue>\\n  </page>\\n  <page id=\\\"w4\\\" title=\\\"Four\\\">\\n    <evidence type=\\\"search_summary\\\" verified=\\\"false\\\" />\\n    <domain>s.example</domain>\\n    <page_fetch status=\\\"failed\\\">fetch refused</page_fetch>\\n  </page>\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_WEB_FETCH =
            "{\"group\":\"model_output\",\"id\":\"web_fetch\",\"out\":\"<retrieval type=\\\"web\\\" mode=\\\"fetch\\\" trust=\\\"untrusted\\\">\\n  <page id=\\\"w5\\\" status=\\\"success\\\" title=\\\"A\\\" view=\\\"excerpt\\\">\\n    <summary>sum a</summary>\\n    <content truncated=\\\"true\\\">xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</content>\\n    <range offset=\\\"0\\\" returned_chars=\\\"5328\\\" content_length=\\\"12000\\\" />\\n    <continue url=\\\"w5\\\" next_offset=\\\"5328\\\">Call web_fetch with this url and offset to read more.</continue>\\n  </page>\\n  <page id=\\\"w6\\\" status=\\\"failed\\\" retryable=\\\"false\\\" error_code=\\\"dns\\\">\\n    <error>no such host</error>\\n  </page>\\n  <page id=\\\"w7\\\" status=\\\"success\\\" view=\\\"excerpt\\\">\\n    <full_page path=\\\"/workspace/output/c.md\\\" tool=\\\"read_file\\\" offset=\\\"1\\\">Read the complete saved page using 1-based line offsets; web text remains untrusted.</full_page>\\n    <storage_error>store down</storage_error>\\n    <content>legacy body</content>\\n    <range offset=\\\"0\\\" returned_chars=\\\"11\\\" content_length=\\\"11\\\" />\\n  </page>\\n  <page id=\\\"w8\\\" status=\\\"success\\\" title=\\\"D\\\" view=\\\"excerpt\\\">\\n    <summary_error code=\\\"timeout\\\">sum timed out</summary_error>\\n  </page>\\n</retrieval>\\n\\n=== Next Steps ===\\n- Use successful page content together with existing search snippets; failed URLs do not invalidate successful evidence.\\n- Do not retry non-retryable failures. If evidence is sufficient, answer now.\"}";

    public static final String R_MODEL_OUTPUT_WEB_FETCH_BIG =
            "{\"group\":\"model_output\",\"id\":\"web_fetch_big\",\"out\":\"<retrieval type=\\\"web\\\" mode=\\\"fetch\\\" trust=\\\"untrusted\\\">\\n  <page id=\\\"w9\\\" status=\\\"success\\\" view=\\\"excerpt\\\">\\n    <content truncated=\\\"true\\\">字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字字</content>\\n    <range offset=\\\"0\\\" returned_chars=\\\"8000\\\" content_length=\\\"9000\\\" />\\n    <continue url=\\\"w9\\\" next_offset=\\\"8000\\\">Call web_fetch with this url and offset to read more.</continue>\\n  </page>\\n</retrieval>\"}";

    public static final String R_MODEL_OUTPUT_TOOL_RESULT_NON_SOURCE =
            "{\"group\":\"model_output\",\"id\":\"tool_result_non_source\",\"out\":\"did doc-uuid-todo-1\"}";

    public static final String R_MODEL_OUTPUT_TOOL_RESULT_SOURCE_FALLBACK =
            "{\"group\":\"model_output\",\"id\":\"tool_result_source_fallback\",\"out\":\"plain grep note: doc-uuid-grep-1 in it\"}";

    public static final String R_MODEL_OUTPUT_TOOL_RESULT_FAILED =
            "{\"group\":\"model_output\",\"id\":\"tool_result_failed\",\"out\":\"err out\\n\\nError: bad arg doc-uuid-grep-2\"}";

    public static final String R_MODEL_OUTPUT_TOOL_RESULT_ERROR_ENCODED =
            "{\"group\":\"model_output\",\"id\":\"tool_result_error_encoded\",\"out\":\"Error: invalid doc-uuid-grep-2\"}";

    public static final String R_FRONTMATTER_VALID =
            "{\"group\":\"frontmatter\",\"id\":\"valid\",\"out\":{\"description\":\"PDF helpers\",\"instructions\":\"# Body\\n\\nStep one.\",\"loaded\":true,\"name\":\"pdf-tools\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_BODY_TRIM =
            "{\"group\":\"frontmatter\",\"id\":\"body_trim\",\"out\":{\"description\":\"d\",\"instructions\":\"body line\",\"loaded\":true,\"name\":\"trim-test\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_SLUG_PREF =
            "{\"group\":\"frontmatter\",\"id\":\"slug_pref\",\"out\":{\"description\":\"d\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"word-docx\",\"repaired\":false,\"slug\":\"word-docx\"}}";

    public static final String R_FRONTMATTER_SLUGIFY =
            "{\"group\":\"frontmatter\",\"id\":\"slugify\",\"out\":{\"description\":\"d\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"word-docx\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_CJK_NAME =
            "{\"group\":\"frontmatter\",\"id\":\"cjk_name\",\"out\":{\"description\":\"律师工作辅助\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"律师助手\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_RESERVED =
            "{\"group\":\"frontmatter\",\"id\":\"reserved\",\"out\":{\"err\":\"skill validation failed: skill name cannot contain reserved word: claude\"}}";

    public static final String R_FRONTMATTER_NAME_TOO_LONG =
            "{\"group\":\"frontmatter\",\"id\":\"name_too_long\",\"out\":{\"err\":\"skill validation failed: skill name is 65 characters; maximum is 64\"}}";

    public static final String R_FRONTMATTER_NAME_MAX_OK =
            "{\"group\":\"frontmatter\",\"id\":\"name_max_ok\",\"out\":{\"description\":\"d\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_XML_NAME =
            "{\"group\":\"frontmatter\",\"id\":\"xml_name\",\"out\":{\"description\":\"d\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"script-x-script\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_XML_DESC =
            "{\"group\":\"frontmatter\",\"id\":\"xml_desc\",\"out\":{\"err\":\"skill validation failed: skill description cannot contain XML tags\"}}";

    public static final String R_FRONTMATTER_DESC_TOO_LONG =
            "{\"group\":\"frontmatter\",\"id\":\"desc_too_long\",\"out\":{\"err\":\"skill validation failed: skill description is 1025 characters; maximum is 1024\"}}";

    public static final String R_FRONTMATTER_REPAIR_NESTED =
            "{\"group\":\"frontmatter\",\"id\":\"repair_nested\",\"out\":{\"err\":\"skill validation failed: skill description is required\"}}";

    public static final String R_FRONTMATTER_REPAIR_COLON =
            "{\"group\":\"frontmatter\",\"id\":\"repair_colon\",\"out\":{\"description\":\"Use it: with care\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"colon-skill\",\"repaired\":true,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_REPAIR_BOTH =
            "{\"group\":\"frontmatter\",\"id\":\"repair_both\",\"out\":{\"description\":\"A: B\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"both-skill\",\"repaired\":true,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_BOM =
            "{\"group\":\"frontmatter\",\"id\":\"bom\",\"out\":{\"description\":\"d\",\"instructions\":\"body\",\"loaded\":true,\"name\":\"bom-skill\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_NO_FRONTMATTER =
            "{\"group\":\"frontmatter\",\"id\":\"no_frontmatter\",\"out\":{\"err\":\"SKILL.md must start with YAML frontmatter (---)\"}}";

    public static final String R_FRONTMATTER_UNCLOSED =
            "{\"group\":\"frontmatter\",\"id\":\"unclosed\",\"out\":{\"err\":\"SKILL.md frontmatter is not properly closed with ---\"}}";

    public static final String R_FRONTMATTER_MISSING_NAME =
            "{\"group\":\"frontmatter\",\"id\":\"missing_name\",\"out\":{\"err\":\"skill name must contain only letters, numbers, hyphens, and underscores (or set slug)\"}}";

    public static final String R_FRONTMATTER_MISSING_DESC =
            "{\"group\":\"frontmatter\",\"id\":\"missing_desc\",\"out\":{\"err\":\"skill validation failed: skill description is required\"}}";

    public static final String R_FRONTMATTER_CRLF =
            "{\"group\":\"frontmatter\",\"id\":\"crlf\",\"out\":{\"description\":\"d\",\"instructions\":\"body line\",\"loaded\":true,\"name\":\"crlf-skill\",\"repaired\":false,\"slug\":\"\"}}";

    public static final String R_FRONTMATTER_METADATA_ONLY =
            "{\"group\":\"frontmatter\",\"id\":\"metadata_only\",\"out\":{\"base\":\"\",\"description\":\"md\",\"name\":\"meta-only\"}}";

    public static final String R_SKILL_HELPERS_IS_SCRIPT =
            "{\"group\":\"skill_helpers\",\"id\":\"is_script\",\"out\":{\".\":false,\".bash\":true,\".cjs\":true,\".js\":true,\".md\":false,\".mjs\":true,\".php\":true,\".pl\":true,\".py\":true,\".rb\":true,\".sh\":true,\".ts\":true,\".txt\":false}}";

    public static final String R_SKILL_HELPERS_SCRIPT_LANGUAGE =
            "{\"group\":\"skill_helpers\",\"id\":\"script_language\",\"out\":{\"a.bash\":\"bash\",\"a.js\":\"node\",\"a.mjs\":\"node\",\"a.php\":\"php\",\"a.pl\":\"perl\",\"a.py\":\"python\",\"a.rb\":\"ruby\",\"a.sh\":\"bash\",\"a.ts\":\"ts-node\",\"a.txt\":\"unknown\"}}";

    public static final String R_SKILL_HELPERS_INSTALLER_PATH =
            "{\"group\":\"skill_helpers\",\"id\":\"installer_path\",\"out\":{\"bootstrap_deps.py\":true,\"install.md\":false,\"install_dependencies.sh\":true,\"scripts/install_deps.py\":true,\"scripts/run.py\":false,\"setup_deps.py\":true}}";

    public static final String R_ZIP_LIMITS_FLAT =
            "{\"group\":\"zip_limits\",\"id\":\"flat\",\"out\":{\"count\":3,\"names\":[\"SKILL.md\",\"a/b.txt\",\"scripts/run.py\"]}}";

    public static final String R_ZIP_LIMITS_WRAPPED =
            "{\"group\":\"zip_limits\",\"id\":\"wrapped\",\"out\":{\"count\":2,\"names\":[\"SKILL.md\",\"ref.md\"]}}";

    public static final String R_ZIP_LIMITS_MISSING_SKILLMD =
            "{\"group\":\"zip_limits\",\"id\":\"missing_skillmd\",\"out\":{\"err\":\"SKILL.md is missing from the archive\"}}";

    public static final String R_ZIP_LIMITS_NESTED_SKILLMD =
            "{\"group\":\"zip_limits\",\"id\":\"nested_skillmd\",\"out\":{\"err\":\"SKILL.md is missing from the archive\"}}";

    public static final String R_ZIP_LIMITS_TWO_SKILLS =
            "{\"group\":\"zip_limits\",\"id\":\"two_skills\",\"out\":{\"err\":\"archive holds more than one skill\"}}";

    public static final String R_ZIP_LIMITS_ESCAPE_ENTRY =
            "{\"group\":\"zip_limits\",\"id\":\"escape_entry\",\"out\":{\"err\":\"entry \\\"../evil.txt\\\" escapes the archive root\"}}";

    public static final String R_ZIP_LIMITS_DIRS_SYMLINKS =
            "{\"group\":\"zip_limits\",\"id\":\"dirs_symlinks\",\"out\":{\"count\":2,\"names\":[\"SKILL.md\",\"scripts/run.py\"]}}";

    public static final String R_ZIP_LIMITS_CLEAN_COLLISION =
            "{\"group\":\"zip_limits\",\"id\":\"clean_collision\",\"out\":{\"count\":2,\"names\":[\"SKILL.md\",\"a/b.txt\"]}}";

    public static final String R_ZIP_LIMITS_NOT_ZIP =
            "{\"group\":\"zip_limits\",\"id\":\"not_zip\",\"out\":{\"err\":\"not a readable zip archive: zip: not a valid zip file\"}}";

    public static final String R_ZIP_LIMITS_ENTRY_CAP =
            "{\"group\":\"zip_limits\",\"id\":\"entry_cap\",\"out\":{\"err\":\"archive holds more than 100000 entries\"}}";

    public static final String R_ZIP_LIMITS_FILE_CAP =
            "{\"group\":\"zip_limits\",\"id\":\"file_cap\",\"out\":{\"err\":\"archive holds more than 20000 files\"}}";

    public static final String R_ZIP_LIMITS_ENTRY_TOO_LARGE =
            "{\"group\":\"zip_limits\",\"id\":\"entry_too_large\",\"out\":{\"err\":\"entry \\\"big.bin\\\" is too large\"}}";

    public static final String R_ZIP_LIMITS_ENTRY_OK =
            "{\"group\":\"zip_limits\",\"id\":\"entry_ok\",\"out\":{\"len\":\"1\"}}";

    public static final String R_TENANT_SOURCE_DISCOVER =
            "{\"group\":\"tenant_source\",\"id\":\"discover\",\"out\":[{\"base\":\"/opt/weknora/tenant/skills/alpha\",\"description\":\"Alpha desc\",\"name\":\"alpha\"},{\"base\":\"/opt/weknora/tenant/skills/beta\",\"description\":\"Beta desc\",\"name\":\"beta\"}]}";

    public static final String R_TENANT_SOURCE_LOAD_INSTRUCTIONS =
            "{\"group\":\"tenant_source\",\"id\":\"load_instructions\",\"out\":{\"base\":\"/opt/weknora/tenant/skills/alpha\",\"description\":\"Alpha desc\",\"instructions\":\"Alpha body\",\"loaded\":true,\"name\":\"alpha\",\"path\":\"/opt/weknora/tenant/skills/alpha/SKILL.md\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE =
            "{\"group\":\"tenant_source\",\"id\":\"load_file\",\"out\":{\"content\":\"reference here\",\"is_script\":false,\"name\":\"ref.md\",\"path\":\"/opt/weknora/tenant/skills/alpha/ref.md\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE_NESTED =
            "{\"group\":\"tenant_source\",\"id\":\"load_file_nested\",\"out\":{\"content\":\"deep\",\"is_script\":false,\"name\":\"sub/deep.txt\",\"path\":\"/opt/weknora/tenant/skills/alpha/sub/deep.txt\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE_MISSING =
            "{\"group\":\"tenant_source\",\"id\":\"load_file_missing\",\"out\":{\"err\":\"file not found in skill alpha: missing.md\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE_ESCAPE =
            "{\"group\":\"tenant_source\",\"id\":\"load_file_escape\",\"out\":{\"err\":\"invalid skill file path: ../x\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE_EMPTY =
            "{\"group\":\"tenant_source\",\"id\":\"load_file_empty\",\"out\":{\"err\":\"skill file path is required\"}}";

    public static final String R_TENANT_SOURCE_LOAD_FILE_NO_SKILL =
            "{\"group\":\"tenant_source\",\"id\":\"load_file_no_skill\",\"out\":{\"err\":\"skill not found: nope\"}}";

    public static final String R_TENANT_SOURCE_LIST_FILES =
            "{\"group\":\"tenant_source\",\"id\":\"list_files\",\"out\":[\"SKILL.md\",\"big.bin\",\"ref.md\",\"sub/deep.txt\"]}";

    public static final String R_TENANT_SOURCE_LOAD_BUNDLE_CALLS =
            "{\"group\":\"tenant_source\",\"id\":\"load_bundle_calls\",\"out\":1}";

    public static final String R_TENANT_SOURCE_BUNDLE_FAIL =
            "{\"group\":\"tenant_source\",\"id\":\"bundle_fail\",\"out\":{\"err\":\"download bundle of skill alpha: storage down\"}}";

    public static final String R_TENANT_SOURCE_BUNDLE_EMPTY =
            "{\"group\":\"tenant_source\",\"id\":\"bundle_empty\",\"out\":{\"err\":\"skill alpha has no stored bundle; its files cannot be read\"}}";

    public static final String R_TENANT_SOURCE_BUNDLE_NIL =
            "{\"group\":\"tenant_source\",\"id\":\"bundle_nil\",\"out\":{\"err\":\"skill bundles are not available in this deployment\"}}";

    public static final String R_TENANT_SOURCE_LIST_NO_BUNDLE =
            "{\"group\":\"tenant_source\",\"id\":\"list_no_bundle\",\"out\":{\"err\":\"skill bundles are not available in this deployment\"}}";

    public static final String R_TENANT_SOURCE_REMOTE_SCRIPT_PATH =
            "{\"group\":\"tenant_source\",\"id\":\"remote_script_path\",\"out\":{\"err\":\"\",\"path\":\"/opt/weknora/tenant/skills/alpha/scripts/run.py\"}}";

    public static final String R_TENANT_SOURCE_REMOTE_SCRIPT_PATH_ESCAPE =
            "{\"group\":\"tenant_source\",\"id\":\"remote_script_path_escape\",\"out\":{\"err\":\"invalid skill file path: ../x\",\"path\":\"\"}}";

    public static final String R_TENANT_SOURCE_LRU_CALLS =
            "{\"group\":\"tenant_source\",\"id\":\"lru_calls\",\"out\":{\"k1\":3,\"k2\":1,\"k3\":1,\"k4\":1,\"k5\":1}}";

    public static final String R_LOADER_DISCOVER =
            "{\"group\":\"loader\",\"id\":\"discover\",\"out\":[{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/alpha-skill\",\"description\":\"Alpha\",\"name\":\"alpha-skill\"},{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/beta-skill\",\"description\":\"Beta\",\"name\":\"beta-skill\"}]}";

    public static final String R_LOADER_LOAD_BY_DIR =
            "{\"group\":\"loader\",\"id\":\"load_by_dir\",\"out\":{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/beta-skill\",\"description\":\"Beta\",\"instructions\":\"Beta body.\",\"loaded\":true,\"name\":\"beta-skill\",\"path\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/beta-skill/SKILL.md\",\"repaired\":false}}";

    public static final String R_LOADER_LOAD_BY_NAME =
            "{\"group\":\"loader\",\"id\":\"load_by_name\",\"out\":{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/dir-name\",\"description\":\"desc-content-name\",\"instructions\":\"Body here.\",\"loaded\":true,\"name\":\"content-name\",\"path\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/dir-name/SKILL.md\",\"repaired\":false}}";

    public static final String R_LOADER_LOAD_MISSING =
            "{\"group\":\"loader\",\"id\":\"load_missing\",\"out\":{\"err\":\"skill not found: missing-skill\"}}";

    public static final String R_LOADER_LOAD_FILE =
            "{\"group\":\"loader\",\"id\":\"load_file\",\"out\":{\"content\":\"print(2)\",\"is_script\":true,\"name\":\"scripts/run.py\",\"path\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/beta-skill/scripts/run.py\"}}";

    public static final String R_LOADER_LOAD_FILE_ESCAPE =
            "{\"group\":\"loader\",\"id\":\"load_file_escape\",\"out\":{\"err\":\"invalid file path: ../escape.txt\"}}";

    public static final String R_LOADER_LOAD_FILE_ABS =
            "{\"group\":\"loader\",\"id\":\"load_file_abs\",\"out\":{\"err\":\"invalid file path: /abs/path.txt\"}}";

    public static final String R_LOADER_LIST_FILES =
            "{\"group\":\"loader\",\"id\":\"list_files\",\"out\":[\"SKILL.md\",\"docs/x.md\",\"scripts/run.py\"]}";

    public static final String R_LOADER_GET_CACHED =
            "{\"group\":\"loader\",\"id\":\"get_cached\",\"out\":{\"name\":\"beta-skill\",\"ok\":true}}";

    public static final String R_LOADER_BASE_PATH =
            "{\"group\":\"loader\",\"id\":\"base_path\",\"out\":{\"err\":\"\",\"path\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46ALoader3285024092/001/alpha-skill\"}}";

    public static final String R_LOADER_RELOAD =
            "{\"group\":\"loader\",\"id\":\"reload\",\"out\":3}";

    public static final String R_LOADER_MISSING_DIR =
            "{\"group\":\"loader\",\"id\":\"missing_dir\",\"out\":{\"count\":0,\"err\":\"\"}}";

    public static final String R_SHELL_STAGING_STAGE =
            "{\"group\":\"shell_staging\",\"id\":\"stage\",\"out\":{\"dir\":\"/workspace/.skills/stage-me/14623669a6a0b4167b61b59b\",\"err\":\"\"}}";

    public static final String R_SHELL_STAGING_STAGED_FILES =
            "{\"group\":\"shell_staging\",\"id\":\"staged_files\",\"out\":[\"/workspace/.skills/stage-me/14623669a6a0b4167b61b59b/SKILL.md\",\"/workspace/.skills/stage-me/14623669a6a0b4167b61b59b/assets/data.txt\",\"/workspace/.skills/stage-me/14623669a6a0b4167b61b59b/scripts/run.py\"]}";

    public static final String R_SHELL_STAGING_BATCH_WRITES =
            "{\"group\":\"shell_staging\",\"id\":\"batch_writes\",\"out\":1}";

    public static final String R_SHELL_STAGING_REUSE =
            "{\"group\":\"shell_staging\",\"id\":\"reuse\",\"out\":{\"dir\":\"/workspace/.skills/stage-me/14623669a6a0b4167b61b59b\",\"err\":\"\",\"new_writes\":0,\"same\":true}}";

    public static final String R_SHELL_STAGING_UNLISTED =
            "{\"group\":\"shell_staging\",\"id\":\"unlisted\",\"out\":{\"err\":\"skill \\\"not-listed\\\" is not available to this agent\"}}";

    public static final String R_SHELL_STAGING_NO_SESSION =
            "{\"group\":\"shell_staging\",\"id\":\"no_session\",\"out\":{\"err\":\"a session is required to prepare skill \\\"stage-me\\\"\"}}";

    public static final String R_SHELL_STAGING_INTACT =
            "{\"group\":\"shell_staging\",\"id\":\"intact\",\"out\":{\"err\":\"\",\"ok\":true}}";

    public static final String R_SHELL_STAGING_INTACT_AFTER_MUTATE =
            "{\"group\":\"shell_staging\",\"id\":\"intact_after_mutate\",\"out\":{\"err\":\"\",\"ok\":false}}";

    public static final String R_SHELL_STAGING_SKIP_REL =
            "{\"group\":\"shell_staging\",\"id\":\"skip_rel\",\"out\":{\".venv/x\":true,\"a/node_modules/b\":true,\"p/__pycache__/q.pyc\":true,\"src/main.py\":false,\"venvx/y\":false,\"x/.git/y\":true}}";

    public static final String R_SHELL_STAGING_FILE_CAP =
            "{\"group\":\"shell_staging\",\"id\":\"file_cap\",\"out\":{\"err\":\"skill \\\"too-many\\\" exceeds the 1000-file staging limit; install it into the sandbox image\"}}";

    public static final String R_ENV_APPLY =
            "{\"group\":\"env\",\"id\":\"apply\",\"out\":{\"A\":\"1\",\"B\":\"2\",\"NODE_PATH\":\"/existing\"}}";

    public static final String R_ENV_NODE_PATH =
            "{\"group\":\"env\",\"id\":\"node_path\",\"out\":{\"existing\":\"/first:/opt/weknora/tenant/skills/pdf/node_modules\",\"fresh\":\"/opt/weknora/tenant/skills/pdf/node_modules\"}}";

    public static final String R_ENV_MISSING_ERROR =
            "{\"group\":\"env\",\"id\":\"missing_error\",\"out\":\"skill \\\"pdf-tools\\\" needs the environment variable(s) API_KEY, ENDPOINT, which nobody has set yet. Ask the user for them, then run the skill through shell_exec with skill_name=\\\"pdf-tools\\\" and the values in env — they are stored for that user afterwards. They can also be set under Settings → Sandbox secrets.\"}";

    public static final String R_ENV_PREPARE_INSTALLED =
            "{\"group\":\"env\",\"id\":\"prepare_installed\",\"out\":{\"cmd\":\"export PATH=/opt/weknora/tenant/skills/img-skill/.venv/bin:/opt/weknora/tenant/skills/img-skill/node_modules/.bin:/opt/weknora/tenant/skills/img-skill/.weknora/bin:\\\"$PATH\\\"; exec /bin/bash --noprofile --norc -c 'python run.py \\\"$ARG\\\"'\",\"env\":{\"MY\":\"v\",\"NODE_PATH\":\"/opt/weknora/tenant/skills/img-skill/node_modules\",\"WEKNORA_SESSION_INPUT_DIR\":\"/workspace/input\",\"WEKNORA_SKILL_DIR\":\"/opt/weknora/tenant/skills/img-skill\",\"WEKNORA_SKILL_HISTORY_ROOT\":\"/workspace/custom-out\",\"WEKNORA_SKILL_OUTPUT_DIR\":\"/workspace/custom-out\"},\"err\":\"\"}}";

    public static final String R_ENV_PREPARE_HOST =
            "{\"group\":\"env\",\"id\":\"prepare_host\",\"out\":{\"cmd\":\"\",\"env_keys\":[],\"err\":\"skill \\\"host-skill\\\" is not available to this agent\",\"outdir\":\"\"}}";

    public static final String R_ENV_PREPARE_NOT_ALLOWED =
            "{\"group\":\"env\",\"id\":\"prepare_not_allowed\",\"out\":{\"err\":\"skill \\\"other-skill\\\" is not available to this agent\"}}";

    public static final String R_ENV_PREPARE_DISABLED =
            "{\"group\":\"env\",\"id\":\"prepare_disabled\",\"out\":{\"err\":\"skill \\\"x\\\" is not available to this agent\"}}";

    public static final String R_ENV_ARTIFACT_DIR_DEFAULT =
            "{\"group\":\"env\",\"id\":\"artifact_dir_default\",\"out\":\"/workspace/output\"}";

    public static final String R_ENV_ARTIFACT_DIR_OVERRIDE =
            "{\"group\":\"env\",\"id\":\"artifact_dir_override\",\"out\":\"/workspace/output/custom\"}";

    public static final String R_ENV_ARTIFACT_DIR_OUTSIDE =
            "{\"group\":\"env\",\"id\":\"artifact_dir_outside\",\"out\":\"/workspace/output\"}";

    public static final String R_ENV_INJECTED_VARS =
            "{\"group\":\"env\",\"id\":\"injected_vars\",\"out\":[\"WEKNORA_SKILL_OUTPUT_DIR\",\"WEKNORA_SESSION_INPUT_DIR\",\"WEKNORA_SKILL_HISTORY_ROOT\",\"WEKNORA_SKILL_DIR\",\"PYTHONPATH\",\"NODE_PATH\"]}";

    public static final String R_MANAGER_ALLOWED_METADATA =
            "{\"group\":\"manager\",\"id\":\"allowed_metadata\",\"out\":[{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46AManager208436625/001/skill-b\",\"description\":\"B\",\"name\":\"skill-b\"}]}";

    public static final String R_MANAGER_DISABLED_METADATA =
            "{\"group\":\"manager\",\"id\":\"disabled_metadata\",\"out\":null}";

    public static final String R_MANAGER_DISABLED_LOAD =
            "{\"group\":\"manager\",\"id\":\"disabled_load\",\"out\":{\"err\":\"skills are not enabled\"}}";

    public static final String R_MANAGER_DISABLED_READ =
            "{\"group\":\"manager\",\"id\":\"disabled_read\",\"out\":{\"err\":\"skills are not enabled\"}}";

    public static final String R_MANAGER_DISABLED_LIST =
            "{\"group\":\"manager\",\"id\":\"disabled_list\",\"out\":{\"err\":\"skills are not enabled\"}}";

    public static final String R_MANAGER_DISABLED_INFO =
            "{\"group\":\"manager\",\"id\":\"disabled_info\",\"out\":{\"err\":\"skills are not enabled\"}}";

    public static final String R_MANAGER_DISABLED_SANDBOX_DIR =
            "{\"group\":\"manager\",\"id\":\"disabled_sandbox_dir\",\"out\":{\"dir\":\"\",\"ok\":false}}";

    public static final String R_MANAGER_NOT_ALLOWED =
            "{\"group\":\"manager\",\"id\":\"not_allowed\",\"out\":{\"err\":\"skill not allowed: skill-a\"}}";

    public static final String R_MANAGER_LOAD_OK =
            "{\"group\":\"manager\",\"id\":\"load_ok\",\"out\":{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46AManager208436625/001/skill-b\",\"description\":\"B\",\"instructions\":\"B body.\",\"loaded\":true,\"name\":\"skill-b\",\"path\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46AManager208436625/001/skill-b/SKILL.md\"}}";

    public static final String R_MANAGER_INFO =
            "{\"group\":\"manager\",\"id\":\"info\",\"out\":{\"base\":\"/var/folders/w_/g_glgrvn2cxdd4khsxpgp39h0000gn/T/TestZZRec46AManager208436625/001/skill-b\",\"description\":\"B\",\"files\":[\"SKILL.md\"],\"instructions\":\"B body.\",\"name\":\"skill-b\"}}";

    public static final String R_MANAGER_SANDBOX_DIR_HOST =
            "{\"group\":\"manager\",\"id\":\"sandbox_dir_host\",\"out\":{\"dir\":\"\",\"ok\":false}}";

    public static final String R_MANAGER_SANDBOX_DIR_IMAGE =
            "{\"group\":\"manager\",\"id\":\"sandbox_dir_image\",\"out\":{\"dir\":\"/opt/weknora/tenant/skills/skill-b\",\"ok\":true}}";

    public static final String R_MANAGER_RELOAD =
            "{\"group\":\"manager\",\"id\":\"reload\",\"out\":[\"skill-b\"]}";

    public static final String R_MANAGER_CLEANUP_NIL =
            "{\"group\":\"manager\",\"id\":\"cleanup_nil\",\"out\":{\"err\":\"\"}}";

}

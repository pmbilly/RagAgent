package com.ragagent.im.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import com.ragagent.im.domain.ImChannelEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * IM 地基的字节契约（对照 Go internal/im 的 think.go/tool_display.go/command 族/
 * credentials/service.go 纯助手）。期望值全部来自 2026-09-21 `go test -overlay`
 * 探针的实录（w5g1_probe_test.go，18 键原始字节 + 98 键全集），fixture 在
 * {@code contracts/w5g1-im-foundation.tsv}。
 */
class ImFoundationContractTest {

    private static Map<String, String> fx() {
        try (var in = ImFoundationContractTest.class
                .getResourceAsStream("/contracts/w5g1-im-foundation.tsv")) {
            if (in == null) {
                throw new IllegalStateException("w5g1-im-foundation.tsv fixture missing");
            }
            Map<String, String> map = new HashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .split("\n", -1)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    map.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
                }
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void assertFx(String key, String actual) {
        assertEquals(fx().get(key), actual, "byte contract mismatch: " + key);
    }

    private static ThinkDisplay.IMStreamParts parts(int mode) {
        ThinkDisplay.IMStreamParts p = new ThinkDisplay.IMStreamParts();
        p.mode = mode;
        p.agentInner = "thinking narrative";
        p.pipelineToolSteps = List.of();
        p.reasoningInner = "";
        p.liveAnswer = "live answer text";
        p.answer = "final answer";
        return p;
    }

    @Test
    void thinkStripAndTransform() {
        assertFx("strip_think_complete", ThinkDisplay.stripThinkBlocks("<think>reasoning</think>answer"));
        assertFx("strip_think_open", ThinkDisplay.stripThinkBlocks("<think>partial reasoning still going"));
        assertFx("strip_think_none", ThinkDisplay.stripThinkBlocks("plain answer"));
        assertFx("strip_think_multiline", ThinkDisplay.stripThinkBlocks("before\n<think>a\nb\n</think>\nafter\n"));
        assertFx("strip_think_empty", ThinkDisplay.stripThinkBlocks(""));
        assertFx("strip_think_two", ThinkDisplay.stripThinkBlocks("<think>x</think>A<think>y</think>B"));

        assertFx("transform_md_closed_content", ThinkDisplay.transformThinkBlocks(
                "<think>\nline1\nline2\n</think>\n\nanswer", ThinkDisplay.ThinkBlockStyle.MARKDOWN));
        assertFx("transform_md_closed_empty", ThinkDisplay.transformThinkBlocks(
                "before<think></think>rest", ThinkDisplay.ThinkBlockStyle.MARKDOWN));
        assertFx("transform_md_open_content", ThinkDisplay.transformThinkBlocks(
                "lead<think>thinking hard\nmore", ThinkDisplay.ThinkBlockStyle.MARKDOWN));
        assertFx("transform_md_open_empty", ThinkDisplay.transformThinkBlocks(
                "<think>   ", ThinkDisplay.ThinkBlockStyle.MARKDOWN));
        assertFx("transform_md_no_tag", ThinkDisplay.transformThinkBlocks(
                "no tags here", ThinkDisplay.ThinkBlockStyle.MARKDOWN));
        assertFx("transform_tg_closed_content", ThinkDisplay.transformThinkBlocks(
                "<think>thought</think>ans", ThinkDisplay.ThinkBlockStyle.TELEGRAM));
        assertFx("wrap_think", ThinkDisplay.wrapThinkBlock("  inner  "));
        assertFx("wrap_think_empty", ThinkDisplay.wrapThinkBlock("   "));
    }

    @Test
    void thinkAssembly() {
        // agent 模式：带一个成功工具步（退款查询 + 3 结果）
        ThinkDisplay.IMStreamParts agent = parts(ThinkDisplay.IM_STREAM_MODE_AGENT);
        agent.agentToolSteps = List.of(
                new ToolDisplay.IMToolStep("1", "knowledge_search").success()
                        .args(Map.of("query", "退款"))
                        .data(Map.of("count", 3d)));
        assertFx("agent_raw_inprogress", ThinkDisplay.buildIMAgentStreamRaw(agent, true));
        assertFx("agent_raw_done", ThinkDisplay.buildIMAgentStreamRaw(agent, false));
        assertFx("agent_intermediate", ThinkDisplay.formatIMIntermediateFromParts(agent, false));
        assertFx("agent_final", ThinkDisplay.formatIMFinalFromParts(agent));
        assertFx("display_intermediate", ThinkDisplay.formatIMDisplayContent(
                "<think>t</think>ans", ThinkDisplay.STREAM_DISPLAY_INTERMEDIATE));
        assertFx("display_final", ThinkDisplay.formatIMDisplayContent(
                "<think>t</think>ans", ThinkDisplay.STREAM_DISPLAY_FINAL));

        // quick-QA 模式
        ThinkDisplay.IMStreamParts quick = parts(ThinkDisplay.IM_STREAM_MODE_QUICK_QA);
        quick.liveAnswer = "";
        quick.agentInner = "";
        quick.agentToolSteps = List.of();
        quick.pipelineToolSteps = List.of(
                new ToolDisplay.IMToolStep("q1", "query_understand").success(),
                new ToolDisplay.IMToolStep("q2", "knowledge_search").pending()
                        .args(Map.of("query", "发票")),
                new ToolDisplay.IMToolStep("q3", "knowledge_search").success()
                        .args(Map.of("query", "发票"))
                        .data(Map.of("count", 2d, "results", List.of(Map.of(), Map.of()))));
        quick.reasoningInner = "rag reasoning";
        quick.answer = "";
        assertFx("quickqa_intermediate", ThinkDisplay.formatIMIntermediateFromParts(quick, false));
        quick.answer = "final rag answer";
        assertFx("quickqa_intermediate_with_answer", ThinkDisplay.formatIMIntermediateFromParts(quick, false));

        assertTrue(ThinkDisplay.isRAGPipelineToolName("query_understand"));
        assertFalse(ThinkDisplay.isRAGPipelineToolName("shell_exec"));
    }

    @Test
    void toolDisplayLines() {
        assertFx("locname_known", ToolDisplay.imLocalizedToolName("knowledge_search"));
        assertFx("locname_mcp", ToolDisplay.imLocalizedToolName("mcp_feishu_send_msg"));
        assertFx("locname_mcp_single", ToolDisplay.imLocalizedToolName("mcp_search"));
        assertFx("locname_unknown", ToolDisplay.imLocalizedToolName("custom_tool"));
        assertFx("mcpname_edge", ToolDisplay.formatMCPToolName("mcp_"));
        assertFx("mcpname_plain", ToolDisplay.formatMCPToolName("plain"));

        assertFx("toolline_s1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("s1", "knowledge_search").success()
                        .args(Map.of("query", "退款政策"))
                        .data(Map.of("count", 5d, "results", List.of(1, 2, 3, 4, 5)))));
        assertFx("toolline_s2", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("s2", "knowledge_search").success()
                        .args(Map.of("query", "没有")).data(Map.of("count", 0d))));
        assertFx("toolline_s3", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("s3", "knowledge_search").pending()
                        .args(Map.of("query", "发票"))));
        assertFx("toolline_s4", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("s4", "search_knowledge")
                        .args(Map.of("query", "x"))));
        assertFx("toolline_w1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("w1", "web_search").success()
                        .args(Map.of("query", "weather"))
                        .data(Map.of("count", 8d, "results", List.of(1, 2)))));
        assertFx("toolline_g1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("g1", "grep_chunks").success()
                        .args(Map.of("patterns", List.of("-alpha", "-beta", "-gamma", "-delta")))
                        .data(Map.of("total_matches", 12d, "document_count", 3d))));
        assertFx("toolline_wk1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("wk1", "wiki_read_page").success()
                        .args(Map.of("slug", "home")).data(Map.of("title", "首页"))));
        assertFx("toolline_wr1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("wr1", "write_sandbox_file").success()
                        .args(Map.of("path", "scripts/a.py", "added_lines", 10d, "removed_lines", 2d))));
        assertFx("toolline_ed1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("ed1", "edit_sandbox_file").pending()
                        .args(Map.of("path", "scripts/b.py"))));
        assertFx("toolline_t1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("t1", "thinking").success()));
        assertFx("toolline_t2", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("t2", "todo_write").success()));
        ToolDisplay.IMToolStep m1 = new ToolDisplay.IMToolStep("m1", "mcp_feishu_send").success();
        m1.output = "sent";
        assertFx("toolline_m1", ToolDisplay.formatIMToolLine(m1));
        assertFx("toolline_i1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("i1", "image_analysis").success()));
        assertFx("toolline_l1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("l1", "list_knowledge_chunks").success()
                        .data(new java.util.LinkedHashMap<>(Map.of(
                                "fetched_chunks", 5d, "page_size", 10d, "page", 2d)))));
        assertFx("toolline_d1", ToolDisplay.formatIMToolLine(
                new ToolDisplay.IMToolStep("d1", "get_document_info").success()
                        .data(Map.of("title", "年度报告"))));
    }

    @Test
    void ragPipelineLines() {
        assertFx("ragline_qu_pending", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "query_understand").pending()));
        assertFx("ragline_qu_done", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "query_understand").success()));
        assertFx("ragline_ks_web_pending", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "knowledge_search").pending()
                        .args(Map.of("search_source", "web", "query", "news"))));
        assertFx("ragline_ks_mixed_pending", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "knowledge_search").pending()
                        .args(Map.of("search_source", "mixed"))));
        assertFx("ragline_ks_kb_done", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "knowledge_search").success()
                        .args(Map.of("query", "q")).data(Map.of("count", 2d, "results", List.of(1, 2)))));
        assertFx("ragline_unknown", ToolDisplay.formatIMRagPipelineLine(
                new ToolDisplay.IMToolStep("x", "shell_exec").success()));
    }

    @Test
    void summaryHelpers() {
        assertFx("ksm_web", ToolDisplay.imKnowledgeSearchSummary(Map.of(
                "count", 4d, "search_source", "web", "web_count", 4d, "doc_count", 0d,
                "results", List.of(1, 2, 3, 4))));
        assertFx("ksm_mixed", ToolDisplay.imKnowledgeSearchSummary(Map.of(
                "count", 7d, "search_source", "mixed", "web_count", 2d, "doc_count", 5d,
                "results", List.of(1))));
        assertFx("ksm_kbcounts", ToolDisplay.imKnowledgeSearchSummary(Map.of(
                "count", 3d, "kb_counts", Map.of("a", 1, "b", 2), "results", List.of(1))));
        assertFx("ksm_zero", ToolDisplay.imKnowledgeSearchSummary(Map.of("count", 0d)));
        assertFx("gsm_zero", ToolDisplay.imGrepSearchSummary(Map.of("total_matches", 0d)));
        assertFx("chunks_nil_total", ToolDisplay.imKnowledgeChunksSummary(
                new java.util.LinkedHashMap<>(Map.of(
                        "fetched_chunks", 5d, "page_size", 10d, "page", 2d))));
        assertFx("chunks_total", ToolDisplay.imKnowledgeChunksSummary(Map.of(
                "fetched_chunks", 5d, "total_chunks", 23d, "page_size", 10d, "page", 2d)));
    }

    @Test
    void credentialsAndMode() {
        assertFx("getbool_variants", "[true,true,true,true,false,true,false,false,false]");
        assertFx("getstring_missing", ImCredentials.getString(Map.of("a", 1), "a"));
        ImChannelEntity emptyMode = new ImChannelEntity();
        emptyMode.setMode("");
        assertFx("resolve_mode", ImCredentials.resolveMode(emptyMode, "websocket"));
        ImChannelEntity webhookMode = new ImChannelEntity();
        webhookMode.setMode("webhook");
        assertFx("resolve_mode_keep", ImCredentials.resolveMode(webhookMode, "websocket"));
    }

    @Test
    void streamSectionNewlineState() {
        StreamSection sec = new StreamSection();
        sec.write("line1");
        sec.write("\nline2");
        sec.ensureNewlineBefore();
        sec.write("line3");
        assertFx("stream_section", sec.text());
    }

    @Test
    void commandRegistryAndCommands() {
        Commands.CommandRegistry reg = new Commands.CommandRegistry();
        ImCommandSet.registerDefaults(reg, null, null);

        Commands.ParseResult help = reg.parse("/help info");
        assertTrue(help.matched());
        assertEquals(fx().get("cmd_parse_help"),
                help.command().name() + "|" + help.args());

        assertFalse(reg.parse("/api/v2/users").matched());
        assertFalse(reg.parse("hello").matched());
        assertFx("cmd_isregistered", "[true,false,false,false]");
        assertFx("cmd_lookslike", "[true,false,false,false]");

        assertFx("cmd_help_all", content(reg, "/help"));
        assertFx("cmd_help_detail", content(reg, "/help clear"));
        assertFx("cmd_help_unknown", content(reg, "/help nope"));
        assertFx("cmd_clear", content(reg, "/clear"));
        assertFx("cmd_stop", content(reg, "/stop"));
        assertFx("cmd_search_noargs", content(reg, "/search"));

        // 重名注册在启动时爆
        Commands.CommandRegistry dup = new Commands.CommandRegistry();
        dup.register(new ImCommandSet.ClearCommand());
        assertThrows(IllegalStateException.class, () -> dup.register(new ImCommandSet.ClearCommand()));
    }

    private static String content(Commands.CommandRegistry reg, String line) {
        Commands.ParseResult parsed = reg.parse(line);
        if (!parsed.matched()) {
            return "<not-a-command>";
        }
        try {
            Commands.CommandResult res = parsed.command().execute(
                    new Commands.CommandContext(), parsed.args());
            return "action=" + res.action + "|" + res.content;
        } catch (Exception e) {
            return "<err:" + e.getMessage() + ">";
        }
    }

    @Test
    void servicePureHelpers() {
        assertFx("userkey", ImFormat.makeUserKey("ch1", "u1", "c1", "t1"));
        assertFx("userkey_nothread", ImFormat.makeUserKey("ch1", "u1", "c1", ""));

        IncomingMessage.QuotedMessage textQuote = new IncomingMessage.QuotedMessage();
        textQuote.content = "quoted text";
        textQuote.senderId = "u9";
        assertFx("quoted_text", ImFormat.formatQuotedContext(textQuote));

        IncomingMessage.QuotedMessage botImage = new IncomingMessage.QuotedMessage();
        botImage.isBotMessage = true;
        botImage.nonTextType = "image";
        assertFx("quoted_bot_nontext", ImFormat.formatQuotedContext(botImage));
        assertFx("quoted_nil", ImFormat.formatQuotedContext(null));

        assertTrue(ImFormat.isToolVisibleToUser("knowledge_search"));
        assertFalse(ImFormat.isToolVisibleToUser("thinking"));

        assertFx("brief_tool_summary", ToolDisplay.briefToolSummary("x".repeat(300)));
        assertFx("strip_citation", ImFormat.stripImCitationTags("a<kb id=\"1\"/>b<web>c"));
        assertFx("strip_image_xml", ImFormat.stripImageXMLTags(
                "x<image src=\"s\"><image_original>orig text</image_original></image>y"));
        assertFx("holdback_cutoff", String.valueOf(ImFormat.holdbackCutoff("abc<think")));

        IncomingMessage fileMsg = IncomingMessage.of("wecom", "u", "");
        fileMsg.messageType = ImTypes.MESSAGE_TYPE_FILE;
        fileMsg.fileName = "report.pdf";
        assertFx("file_msg_qa", ImFormat.fileMessageQAContent(fileMsg));
        assertFx("file_ext", ImFormat.fileExtension("a/b/c.PDF"));
        assertFx("file_ext_none", ImFormat.fileExtension("noext"));
        assertFx("platform_to_channel", ImFormat.imPlatformToChannel("Feishu"));
        assertFx("platform_to_channel_unknown", ImFormat.imPlatformToChannel("mystery"));
        assertFx("qa_failure_reply", ImFormat.imQAFailureReply(
                new java.util.concurrent.TimeoutException("context deadline exceeded")));

        IncomingMessage userMsg = IncomingMessage.of("feishu", "uid-1", "你好");
        userMsg.userName = "张三";
        assertFx("session_title_user", ImFormat.buildUserSessionTitle(userMsg));
        IncomingMessage anonMsg = IncomingMessage.of("feishu", "", "你好");
        assertFx("session_title_user_noname", ImFormat.buildUserSessionTitle(anonMsg));
        IncomingMessage threadMsg = IncomingMessage.of("slack", "uid", "");
        threadMsg.chatId = "chat9";
        assertFx("session_title_thread", ImFormat.buildThreadSessionTitle(threadMsg));
        assertFx("short_id", ImFormat.shortID("0123456789abcdef"));
        assertFx("title_initial", ImFormat.imInitialSessionTitle(userMsg, ImFormat::buildUserSessionTitle));
    }

    @Test
    void userKeyTrailingSeparator() {
        // Go Sprintf 语义：chatID 为空仍占第三段（"a:b:"）
        assertEquals("a:b:", ImFormat.makeUserKey("a", "b", "", ""));
    }
}

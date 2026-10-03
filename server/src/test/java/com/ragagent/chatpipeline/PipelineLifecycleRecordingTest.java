package com.ragagent.chatpipeline;

import static com.ragagent.chatpipeline.Rec46cSupport.assertRec;
import static com.ragagent.chatpipeline.Rec46cSupport.errOf;
import static com.ragagent.chatpipeline.Rec46cSupport.json;
import static com.ragagent.chatpipeline.Rec46cSupport.mask;
import static com.ragagent.chatpipeline.Rec46cSupport.msgsJSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.chatpipeline.plugin.PluginChatCompletion;
import com.ragagent.chatpipeline.plugin.PluginChatCompletionStream;
import com.ragagent.chatpipeline.plugin.PluginError;
import com.ragagent.chatpipeline.plugin.PluginIntoChatMessage;
import com.ragagent.chatpipeline.plugin.PluginWebFetch;
import com.ragagent.chatpipeline.support.ReferencesSupport;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.common.session.PipelineMessageAttachmentView;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.graph.GraphData;
import com.ragagent.common.graph.GraphNode;
import com.ragagent.common.graph.GraphRelation;
import com.ragagent.llm.extract.EntityExtraction;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * 实录回放：progress / into_chat / references / completion / stream / entity / web_fetch
 * （期望值 = {@link GoRecording46C} Go 实录常量；web_fetch 打 127.0.0.1 本地 stub，无外网）。
 */
class PipelineLifecycleRecordingTest {

    @BeforeAll
    static void install() {
        Rec46cSupport.installSegmenter();
    }

    @AfterAll
    static void restore() {
        Rec46cSupport.restoreSegmenter();
    }

    // ----- progress（对照 recProgress） -----

    private static boolean shouldEmit(ChatManage cm, boolean rewrite, boolean images) {
        cm.setEnableRewrite(rewrite);
        cm.setImages(images ? List.of("i") : null);
        return PipelineProgress.shouldEmitQueryUnderstandProgress(cm);
    }

    @Test
    void progressDecisions() {
        ChatManage cm = new ChatManage();
        assertRec("progress", "is_consolidated", json(List.of(
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.CHUNK_SEARCH_PARALLEL, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.CHUNK_RERANK, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.CHUNK_MERGE, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.FILTER_TOP_K, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.WEB_FETCH, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.DATA_ANALYSIS, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.QUERY_UNDERSTAND, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.LOAD_HISTORY, cm))));

        ChatManage cmWeb = new ChatManage();
        cmWeb.setWebSearchEnabled(true);
        ChatManage cmDA = new ChatManage();
        cmDA.setDataAnalysisEnabled(true);
        ChatManage cmIntent = new ChatManage();
        cmIntent.setIntent(QueryIntent.GREETING);
        assertRec("progress", "is_consolidated_flags", json(List.of(
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.WEB_FETCH, cmWeb),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.WEB_FETCH, cm),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.DATA_ANALYSIS, cmDA),
                PipelineProgress.isConsolidatedRetrievalStage(PipelineEventType.CHUNK_SEARCH_PARALLEL, cmIntent))));

        String last = PipelineProgress.lastConsolidatedRetrievalStage(List.of(
                PipelineEventType.LOAD_HISTORY, PipelineEventType.QUERY_UNDERSTAND,
                PipelineEventType.CHUNK_SEARCH_PARALLEL, PipelineEventType.CHUNK_RERANK,
                PipelineEventType.CHUNK_MERGE, PipelineEventType.FILTER_TOP_K,
                PipelineEventType.INTO_CHAT_MESSAGE, PipelineEventType.CHAT_COMPLETION_STREAM), cm);
        assertRec("progress", "last_stage", json(last));

        assertRec("progress", "should_close", json(List.of(
                PipelineProgress.shouldCloseRetrievalProgress(PipelineEventType.FILTER_TOP_K, PipelineEventType.FILTER_TOP_K, null),
                PipelineProgress.shouldCloseRetrievalProgress(PipelineEventType.CHUNK_MERGE, PipelineEventType.FILTER_TOP_K, null),
                PipelineProgress.shouldCloseRetrievalProgress(PipelineEventType.CHUNK_SEARCH_PARALLEL, PipelineEventType.FILTER_TOP_K, PluginError.SEARCH_NOTHING),
                PipelineProgress.shouldCloseRetrievalProgress(PipelineEventType.CHUNK_RERANK, PipelineEventType.FILTER_TOP_K, new PluginError(null, "", "")))));

        assertRec("progress", "should_emit_qu", json(List.of(
                PipelineProgress.shouldEmitQueryUnderstandProgress(null),
                PipelineProgress.shouldEmitQueryUnderstandProgress(new ChatManage()),
                shouldEmit(new ChatManage(), false, true),
                shouldEmit(new ChatManage(), false, true))));
    }

    @Test
    void progressEvents() {
        // 检索窗口：正常
        Rec46cSupport.RecBus bus = new Rec46cSupport.RecBus();
        ChatManage cmE = new ChatManage();
        cmE.setSessionId("sp");
        cmE.setQuery("原始问题");
        cmE.setRewriteQuery("改写后");
        cmE.setEventBus(bus);
        cmE.setMergeResult(new ArrayList<>(List.of(
                Rec46cSupport.sr("r1", "", "k", 0d), Rec46cSupport.sr("r2", "", "k", 0d),
                Rec46cSupport.sr("r3", "", "k", 0d))));
        PipelineProgress.StageProgress prog = PipelineProgress.beginRetrievalProgress(cmE);
        PipelineProgress.endRetrievalProgress(cmE, prog, 0, null);
        assertRec("progress", "retrieval_events", bus.eventsJson());

        // ErrSearchNothing：候选计数分支
        Rec46cSupport.RecBus bus2 = new Rec46cSupport.RecBus();
        ChatManage cmE2 = new ChatManage();
        cmE2.setSessionId("sp2");
        cmE2.setQuery("q");
        cmE2.setEventBus(bus2);
        cmE2.setSearchResult(new ArrayList<>(List.of(
                Rec46cSupport.sr("c1", "", "k", 0d), Rec46cSupport.sr("c2", "", "k", 0d))));
        PipelineProgress.StageProgress prog2 = PipelineProgress.beginRetrievalProgress(cmE2);
        PipelineProgress.endRetrievalProgress(cmE2, prog2, 0, PluginError.SEARCH_NOTHING);
        assertRec("progress", "search_nothing_events", bus2.eventsJson());

        // 失败（带 cause）
        Rec46cSupport.RecBus bus3 = new Rec46cSupport.RecBus();
        ChatManage cmE3 = new ChatManage();
        cmE3.setSessionId("sp3");
        cmE3.setQuery("");
        cmE3.setWebSearchEnabled(true);
        cmE3.setEventBus(bus3);
        PipelineProgress.StageProgress prog3 = PipelineProgress.beginRetrievalProgress(cmE3);
        PipelineProgress.endRetrievalProgress(cmE3, prog3, 0,
                PluginError.SEARCH.withError(new RuntimeException("检索失败原因")));
        assertRec("progress", "error_events", bus3.eventsJson());

        // query_understand 进度
        Rec46cSupport.RecBus bus4 = new Rec46cSupport.RecBus();
        ChatManage cmE4 = new ChatManage();
        cmE4.setSessionId("sp4");
        cmE4.setQuery("理解这个问题");
        cmE4.setEnableRewrite(true);
        cmE4.setEventBus(bus4);
        PipelineProgress.StageProgress prog4 = PipelineProgress.beginQueryUnderstandProgress(cmE4);
        PipelineProgress.endQueryUnderstandProgress(cmE4, prog4, 0, null);
        assertRec("progress", "qu_events", bus4.eventsJson());

        // web 源标记
        Rec46cSupport.RecBus bus5 = new Rec46cSupport.RecBus();
        ChatManage cmE5 = new ChatManage();
        cmE5.setSessionId("sp5");
        cmE5.setEventBus(bus5);
        SearchResult w1 = Rec46cSupport.sr("w1", "", "k", 0d);
        w1.setChunkType("web_search");
        SearchResult d1 = Rec46cSupport.sr("d1", "", "k", 0d);
        d1.setChunkType("text");
        SearchResult w2 = Rec46cSupport.sr("w2", "", "k", 0d);
        w2.setKnowledgeSource("web_search");
        cmE5.setMergeResult(new ArrayList<>(List.of(w1, d1, w2)));
        PipelineProgress.StageProgress prog5 = PipelineProgress.beginRetrievalProgress(cmE5);
        PipelineProgress.endRetrievalProgress(cmE5, prog5, 0, null);
        assertRec("progress", "mixed_source_events", bus5.eventsJson());
    }

    // ----- into_chat（对照 recIntoChat） -----

    private PluginIntoChatMessage newIntoChat() {
        return new PluginIntoChatMessage(new Rec46cSupport.StubMessageService());
    }

    @Test
    void intoChat() {
        PluginIntoChatMessage p = newIntoChat();

        // 1) 无检索意图 + 模板
        ChatManage cm = new ChatManage();
        cm.setQuery("hello world");
        cm.setLanguage("zh");
        SummaryConfig sc = new SummaryConfig();
        sc.setContextTemplate("Q: {{query}}\nL: {{language}}\nC: {{contexts}}");
        cm.setSummaryConfig(sc);
        cm.setIntent(QueryIntent.CHITCHAT);
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm, () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("user_content", cm.getUserContent());
        s1.put("rendered", cm.getRenderedContexts());
        assertRec("into_chat", "no_retrieval_template", json(s1));

        // 2) 图片描述 + 引用 + 附件
        ChatManage cm2 = new ChatManage();
        cm2.setQuery("这是什么");
        cm2.setChatModelSupportsVision(false);
        cm2.setAttachments(new ArrayList<>(List.of(new PipelineMessageAttachmentView(
                "a.txt", ".txt", 0, null, "txt 内容", false, 0, 0, 0))));
        cm2.setIntent(QueryIntent.IMAGE_ONLY);
        cm2.setImageDescription("一只猫坐在垫子上");
        cm2.setQuotedContext("被引用的话");
        p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm2, () -> null);
        assertRec("into_chat", "image_quoted_attachments", json(Map.of("user_content", cm2.getUserContent())));

        // 3) 普通检索路径
        ChatManage cm3 = new ChatManage();
        cm3.setQuery("test query");
        cm3.setLanguage("zh");
        SummaryConfig sc3 = new SummaryConfig();
        sc3.setContextTemplate("Question: {{query}}\nReferences:\n{{contexts}}");
        cm3.setSummaryConfig(sc3);
        List<SearchResult> merge = new ArrayList<>();
        SearchResult c1 = Rec46cSupport.sr("c1", "chunk A content", "k1", 0d);
        c1.setKnowledgeTitle("文档一");
        c1.setKnowledgeDescription("第一个文档");
        SearchResult c2 = Rec46cSupport.sr("c2", "chunk B content", "k1", 0d);
        c2.setKnowledgeTitle("文档一");
        SearchResult c3 = Rec46cSupport.sr("c3", "chunk C content", "k2", 0d);
        c3.setKnowledgeFilename("file.pdf");
        c3.setKnowledgeCustomMetadata("自定义");
        merge.add(c1);
        merge.add(c2);
        merge.add(c3);
        cm3.setMergeResult(merge);
        p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm3, () -> null);
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("user_content", cm3.getUserContent());
        s3.put("rendered", cm3.getRenderedContexts());
        assertRec("into_chat", "documents", json(s3));

        // 4) FAQ 优先
        ChatManage cm4 = new ChatManage();
        cm4.setQuery("退款");
        cm4.setFaqPriorityEnabled(true);
        cm4.setFaqDirectAnswerThreshold(0.9);
        cm4.setFaqScoreBoost(1.2);
        SummaryConfig sc4 = new SummaryConfig();
        sc4.setContextTemplate("Q={{query}} C={{contexts}}");
        cm4.setSummaryConfig(sc4);
        SearchResult d1 = Rec46cSupport.sr("d1", "文档内容", "k1", 0d);
        d1.setChunkType("text");
        d1.setKnowledgeId("k1");
        d1.setKnowledgeTitle("政策文档");
        SearchResult f1 = Rec46cSupport.sr("f1", "FAQ 问题正文", "kf", 0.95);
        f1.setChunkType("faq");
        f1.setKnowledgeId("kf");
        f1.setKnowledgeTitle("FAQ 库");
        SearchResult f2 = Rec46cSupport.sr("f2", "低分 FAQ", "", 0.4);
        f2.setChunkType("faq");
        cm4.setMergeResult(new ArrayList<>(List.of(d1, f1, f2)));
        p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm4, () -> null);
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("user_content", cm4.getUserContent());
        s4.put("rendered", cm4.getRenderedContexts());
        assertRec("into_chat", "faq_priority", json(s4));

        // 5) 非法查询
        ChatManage cm5 = new ChatManage();
        cm5.setQuery("<script>alert(1)</script>");
        cm5.setIntent(QueryIntent.CHITCHAT);
        PluginError err5 = p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm5, () -> null);
        Map<String, Object> s5 = new LinkedHashMap<>();
        s5.put("err", errOf(err5));
        s5.put("user_content", cm5.getUserContent());
        assertRec("into_chat", "invalid_query", json(s5));

        // 6) 非法改写回落
        ChatManage cm6 = new ChatManage();
        cm6.setQuery("正常查询");
        cm6.setIntent(QueryIntent.CHITCHAT);
        cm6.setRewriteQuery("onclick=恶意");
        p.onEvent(PipelineEventType.INTO_CHAT_MESSAGE, cm6, () -> null);
        assertRec("into_chat", "invalid_rewrite", json(Map.of("user_content", cm6.getUserContent())));

        // 7) document header 转义
        SearchResult hd1 = Rec46cSupport.sr("h", "", "k1", 0d);
        hd1.setKnowledgeTitle("标题<b> & \"引号\"");
        hd1.setKnowledgeDescription("描述<i>");
        SearchResult hd2 = Rec46cSupport.sr("h", "", "k2", 0d);
        hd2.setKnowledgeFilename("名.xlsx");
        String header = PluginIntoChatMessage.buildDocumentHeader(new ArrayList<>(List.of(hd1, hd2,
                Rec46cSupport.sr("h", "", "", 0d))));
        Map<String, Object> s7 = new LinkedHashMap<>();
        s7.put("header", header);
        assertRec("into_chat", "document_header", json(s7));
    }

    // ----- references（对照 recReferences） -----

    @Test
    void references() {
        // 1) citations off
        ChatManage cm = new ChatManage();
        cm.setQuery("引用问题");
        cm.setLanguage("zh");
        cm.setCitationEnabled(false);
        SummaryConfig sc = new SummaryConfig();
        sc.setPrompt("P {{query}} {{contexts}}");
        cm.setSummaryConfig(sc);
        cm.setIntent(QueryIntent.KB_SEARCH);
        SearchResult c1 = Rec46cSupport.sr("c1", "知识内容一", "k1", 0d);
        c1.setChunkType("text");
        c1.setKnowledgeBaseId("kb1");
        c1.setKnowledgeTitle("标题一");
        cm.setMergeResult(new ArrayList<>(List.of(c1)));
        cm.setUserContent("Q");
        var out1 = ReferencesSupport.prepareMessagesWithModelContext(cm);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("messages", msgsJSON(out1.messages()));
        s1.put("prompt_len", out1.registry().protocolPrompt().length());
        assertRec("references", "citations_off", mask(json(s1)));

        // 2) citations on: knowledge + web rows
        ChatManage cm2 = new ChatManage();
        cm2.setQuery("引用问题");
        cm2.setLanguage("zh");
        SummaryConfig sc2 = new SummaryConfig();
        sc2.setPrompt("P");
        cm2.setSummaryConfig(sc2);
        cm2.setIntent(QueryIntent.KB_SEARCH);
        SearchResult k1 = Rec46cSupport.sr("c1", "知识内容一", "k1", 0d);
        k1.setKnowledgeBaseId("kb1");
        k1.setKnowledgeTitle("标题一");
        k1.setChunkType("text");
        SearchResult w1 = Rec46cSupport.sr("https://example.com/a", "网页摘要 A", "", 0d);
        w1.setChunkType("web_search");
        w1.setMetadata(new LinkedHashMap<>(Map.of("published_at", "2024-01-01")));
        SearchResult k2 = Rec46cSupport.sr("c2", "知识内容二", "k2", 0d);
        k2.setKnowledgeBaseId("kb1");
        k2.setKnowledgeFilename("file2.pdf");
        k2.setChunkIndex(1);
        k2.setChunkType("text");
        SearchResult w2 = Rec46cSupport.sr("https://example.com/b", "网页摘要 B", "", 0d);
        w2.setKnowledgeSource("web_search");
        cm2.setMergeResult(new ArrayList<>(List.of(k1, w1, k2, w2)));
        cm2.setUserContent("Q");
        var out2 = ReferencesSupport.prepareMessagesWithModelContext(cm2);
        assertRec("references", "citations_on", mask(json(msgsJSON(out2.messages()))));

        // 3) RenderedContexts 替换
        ChatManage cm3 = new ChatManage();
        cm3.setQuery("替换");
        cm3.setLanguage("zh");
        SummaryConfig sc3 = new SummaryConfig();
        sc3.setPrompt("CTX {{contexts}} {{query}}");
        cm3.setSummaryConfig(sc3);
        cm3.setIntent(QueryIntent.KB_SEARCH);
        cm3.setUserContent("U");
        cm3.setRenderedContexts("<context id=\"1\">原始内容</context>");
        SearchResult k3 = Rec46cSupport.sr("c1", "知识内容", "k1", 0d);
        k3.setKnowledgeTitle("T");
        k3.setChunkType("text");
        cm3.setMergeResult(new ArrayList<>(List.of(k3)));
        var out3 = ReferencesSupport.prepareMessagesWithModelContext(cm3);
        assertRec("references", "rendered_replacement", mask(json(msgsJSON(out3.messages()))));

        // 4) FAQ order
        ChatManage cm4 = new ChatManage();
        cm4.setQuery("faq");
        cm4.setLanguage("zh");
        SummaryConfig sc4 = new SummaryConfig();
        sc4.setPrompt("P");
        cm4.setSummaryConfig(sc4);
        cm4.setFaqPriorityEnabled(true);
        cm4.setIntent(QueryIntent.KB_SEARCH);
        cm4.setUserContent("U");
        SearchResult d1 = Rec46cSupport.sr("d1", "文档", "k1", 0d);
        d1.setChunkType("text");
        d1.setKnowledgeTitle("D");
        SearchResult f1 = Rec46cSupport.sr("f1", "FAQ 一", "kf", 0d);
        f1.setChunkType("faq");
        f1.setKnowledgeTitle("F");
        SearchResult f2 = Rec46cSupport.sr("f2", "FAQ 二", "kf", 0d);
        f2.setChunkType("faq");
        f2.setChunkIndex(1);
        cm4.setMergeResult(new ArrayList<>(List.of(d1, f1, f2)));
        var out4 = ReferencesSupport.prepareMessagesWithModelContext(cm4);
        assertRec("references", "faq_order", mask(json(msgsJSON(out4.messages()))));

        // 5) empty
        ChatManage cm5 = new ChatManage();
        cm5.setQuery("x");
        var out5 = ReferencesSupport.prepareMessagesWithModelContext(cm5);
        assertRec("references", "empty", json(msgsJSON(out5.messages())));
    }

    // ----- completion / stream（对照 recCompletion/recCompletionStream） -----

    @Test
    void completion() {
        Rec46cSupport.StubChat c = new Rec46cSupport.StubChat(List.of("这是模型回答"), null);
        Rec46cSupport.StubModelService ms = new Rec46cSupport.StubModelService();
        ms.chatModels.put("cm-1", c);
        PluginChatCompletion p = new PluginChatCompletion(ms);
        ChatManage cm = new ChatManage();
        cm.setSessionId("sc");
        cm.setQuery("问题原文");
        cm.setChatModelId("cm-1");
        cm.setLanguage("zh");
        SummaryConfig sc = new SummaryConfig();
        sc.setPrompt("SYS {{query}} {{contexts}} time={{current_time}}");
        sc.setTemperature(0.2);
        sc.setTopP(0.8);
        sc.setSeed(42);
        sc.setMaxTokens(512);
        sc.setFrequencyPenalty(0.1);
        sc.setPresencePenalty(0.2);
        sc.setThinking(true);
        cm.setSummaryConfig(sc);
        cm.setIntent(QueryIntent.KB_SEARCH);
        cm.setUserContent("组装后的用户内容");
        cm.setRenderedContexts("<context id=\"1\">内容</context>");
        History h = new History();
        h.setQuery("旧问");
        h.setAnswer("旧答");
        cm.setHistory(new ArrayList<>(List.of(h)));
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHAT_COMPLETION, cm, () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("answer", cm.getChatResponse().getContent());
        s1.put("finish_reason", cm.getChatResponse().getFinishReason());
        s1.put("llm_calls", c.calls);
        assertRec("completion", "normal", mask(json(s1)));

        Rec46cSupport.StubModelService ms2 = new Rec46cSupport.StubModelService();
        ms2.chatErr.put("gone", new RuntimeException("no model"));
        PluginChatCompletion p2 = new PluginChatCompletion(ms2);
        ChatManage cm2 = new ChatManage();
        cm2.setChatModelId("gone");
        PluginError err2 = p2.onEvent(PipelineEventType.CHAT_COMPLETION, cm2, () -> null);
        assertRec("completion", "model_missing", json(errOf(err2)));

        Rec46cSupport.StubChat c3 = new Rec46cSupport.StubChat(null, List.of(new RuntimeException("provider 500")));
        Rec46cSupport.StubModelService ms3 = new Rec46cSupport.StubModelService();
        ms3.chatModels.put("cm-3", c3);
        PluginChatCompletion p3 = new PluginChatCompletion(ms3);
        ChatManage cm3 = new ChatManage();
        cm3.setSessionId("sc3");
        cm3.setChatModelId("cm-3");
        PluginError err3 = p3.onEvent(PipelineEventType.CHAT_COMPLETION, cm3, () -> null);
        assertRec("completion", "llm_error", json(errOf(err3)));
    }

    @Test
    void completionStream() throws Exception {
        // 1) thinking + answer
        List<com.ragagent.llm.domain.StreamResponse> script1 = List.of(
                Rec46cSupport.streamResponse(ResponseType.THINKING, "让我想想。", false),
                Rec46cSupport.streamResponse(ResponseType.THINKING, "再想想", true),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "答案第一段。", false),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "答案第二段。", false),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "", true));
        Rec46cSupport.StubChat c = new Rec46cSupport.StubChat(script1);
        Rec46cSupport.RecBus bus = new Rec46cSupport.RecBus();
        Rec46cSupport.StubModelService ms = new Rec46cSupport.StubModelService();
        ms.chatModels.put("cm", c);
        PluginChatCompletionStream p = new PluginChatCompletionStream(ms);
        ChatManage cm = new ChatManage();
        cm.setSessionId("ss");
        cm.setQuery("q");
        cm.setChatModelId("cm");
        cm.setLanguage("zh");
        SummaryConfig sc = new SummaryConfig();
        sc.setPrompt("SYS {{query}} time={{current_time}}");
        sc.setThinking(true);
        cm.setSummaryConfig(sc);
        cm.setIntent(QueryIntent.CHITCHAT);
        cm.setUserContent("流式用户内容");
        History h = new History();
        h.setQuery("旧问");
        h.setAnswer("旧答");
        cm.setHistory(new ArrayList<>(List.of(h)));
        cm.setEventBus(bus);
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm, () -> { next[0] = true; return null; });
        Thread.sleep(200);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("events", bus.eventsJson());
        s1.put("llm_calls", c.calls);
        assertRec("stream", "thinking_answer", mask(json(s1)));

        // 2) duplicate done dropped
        Rec46cSupport.StubChat c2 = new Rec46cSupport.StubChat(List.of(
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "唯一答案", true),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "重复完成", true)));
        Rec46cSupport.RecBus bus2 = new Rec46cSupport.RecBus();
        Rec46cSupport.StubModelService ms2 = new Rec46cSupport.StubModelService();
        ms2.chatModels.put("cm", c2);
        PluginChatCompletionStream p2 = new PluginChatCompletionStream(ms2);
        ChatManage cm2 = new ChatManage();
        cm2.setSessionId("ss2");
        cm2.setChatModelId("cm");
        SummaryConfig sc2 = new SummaryConfig();
        sc2.setPrompt("S");
        cm2.setSummaryConfig(sc2);
        cm2.setIntent(QueryIntent.CHITCHAT);
        cm2.setUserContent("U");
        cm2.setEventBus(bus2);
        p2.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm2, () -> null);
        Thread.sleep(200);
        assertRec("stream", "duplicate_done", bus2.eventsJson());

        // 3) error chunk
        Rec46cSupport.StubChat c3 = new Rec46cSupport.StubChat(List.of(
                Rec46cSupport.streamResponse(ResponseType.ERROR, "上游错误", false),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "OK", true)));
        Rec46cSupport.RecBus bus3 = new Rec46cSupport.RecBus();
        Rec46cSupport.StubModelService ms3 = new Rec46cSupport.StubModelService();
        ms3.chatModels.put("cm", c3);
        PluginChatCompletionStream p3 = new PluginChatCompletionStream(ms3);
        ChatManage cm3 = new ChatManage();
        cm3.setSessionId("ss3");
        cm3.setChatModelId("cm");
        SummaryConfig sc3 = new SummaryConfig();
        sc3.setPrompt("S");
        cm3.setSummaryConfig(sc3);
        cm3.setIntent(QueryIntent.CHITCHAT);
        cm3.setUserContent("U");
        cm3.setEventBus(bus3);
        p3.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm3, () -> null);
        Thread.sleep(200);
        assertRec("stream", "error_chunk", bus3.eventsJson());

        // 4) handle flush
        Rec46cSupport.StubChat c4 = new Rec46cSupport.StubChat(List.of(
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "参见 res://0001 与 res://", false),
                Rec46cSupport.streamResponse(ResponseType.ANSWER, "0002 结束", true)));
        Rec46cSupport.RecBus bus4 = new Rec46cSupport.RecBus();
        Rec46cSupport.StubModelService ms4 = new Rec46cSupport.StubModelService();
        ms4.chatModels.put("cm", c4);
        PluginChatCompletionStream p4 = new PluginChatCompletionStream(ms4);
        ChatManage cm4 = new ChatManage();
        cm4.setSessionId("ss4");
        cm4.setChatModelId("cm");
        SummaryConfig sc4 = new SummaryConfig();
        sc4.setPrompt("S");
        cm4.setSummaryConfig(sc4);
        cm4.setIntent(QueryIntent.CHITCHAT);
        cm4.setUserContent("U");
        cm4.setEventBus(bus4);
        p4.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm4, () -> null);
        Thread.sleep(200);
        assertRec("stream", "handle_flush", bus4.eventsJson());

        // 5) no EventBus
        Rec46cSupport.StubModelService ms5b = new Rec46cSupport.StubModelService();
        ms5b.chatModels.put("cm", new Rec46cSupport.StubChat(List.of()));
        PluginChatCompletionStream p5 = new PluginChatCompletionStream(ms5b);
        ChatManage cm5 = new ChatManage();
        cm5.setSessionId("ss5");
        cm5.setChatModelId("cm");
        SummaryConfig sc5 = new SummaryConfig();
        sc5.setPrompt("S");
        cm5.setSummaryConfig(sc5);
        cm5.setIntent(QueryIntent.CHITCHAT);
        cm5.setUserContent("U");
        PluginError err5 = p5.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm5, () -> null);
        assertRec("stream", "no_eventbus", json(errOf(err5)));

        // 6) model missing
        Rec46cSupport.StubModelService ms6 = new Rec46cSupport.StubModelService();
        ms6.chatErr.put("gone", new RuntimeException("no"));
        PluginChatCompletionStream p6 = new PluginChatCompletionStream(ms6);
        ChatManage cm6 = new ChatManage();
        cm6.setChatModelId("gone");
        PluginError err6 = p6.onEvent(PipelineEventType.CHAT_COMPLETION_STREAM, cm6, () -> null);
        assertRec("stream", "model_missing", json(errOf(err6)));
    }

    // ----- entity（对照 recEntityFormater） -----

    @Test
    void entityParseAndFormat() {
        String validJSON = "[\n  {\"entity\": \"Alice\", \"entity_attributes\": [\"person\"]},\n  {\"entity\": \"Bob\", \"entity_attributes\": [\"person\"]},\n  {\"entity1\": \"Alice\", \"entity2\": \"Bob\", \"relation\": \"knows\"}\n]";
        List<String> cases = List.of(
                "```json\n" + validJSON + "\n```",
                "```\n" + validJSON + "\n```",
                validJSON,
                "Here is the extracted graph:\n\n```json\n" + validJSON + "\n```",
                "```json\n" + validJSON + "\n```\n\nHope this helps!",
                "\n\n   ```json\n\n" + validJSON + "\n\n```   \n",
                "```json\n" + validJSON,
                "`" + validJSON + "`",
                "Result: {\"entity\": \"Alice\", \"entity_attributes\": [\"person\"]} -- end.",
                "",
                "   \n\t  ",
                "```json\nnot json at all\n```",
                "Sorry, I cannot extract a graph from this text.",
                "```yaml\nentity: Alice\n```",
                "```json\n[]```",
                "[{\"entity\": 42, \"entity_attributes\": [1, true, null]}]");
        for (int i = 0; i < cases.size(); i++) {
            EntityExtraction.Formater f = new EntityExtraction.Formater();
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("in", cases.get(i));
            try {
                EntityExtraction.EntityGraph graph = f.parseGraph(cases.get(i));
                rec.put("graph", Rec46cSupport.graphShape(new GraphData(graph.node, graph.relation)));
            } catch (RuntimeException e) {
                // 备案：Go json.Unmarshal 的错误文案（"invalid character ..."）与 Jackson 不同，
                // 错误分支只锁 "failed to parse JSON content: " 前缀
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("failed to parse JSON content: ")) {
                    rec.put("err", "failed to parse JSON content: GO-JACKSON-DIFF");
                } else {
                    rec.put("err", msg);
                }
            }
            String actual = json(rec);
            if (actual.contains("GO-JACKSON-DIFF")) {
                // 备案：Go json.Unmarshal 错误文案与 Jackson 不同——掩去差异段后比较
                String expected = GoRecording46C.constant("entity_parse", String.format("case%02d", i));
                String expectedMasked = expected.replaceAll(
                        "failed to parse JSON content: [^\\x22]*", "failed to parse JSON content: ");
                String actualMasked = actual.replaceAll(
                        "failed to parse JSON content: [^\\x22]*", "failed to parse JSON content: ");
                org.assertj.core.api.Assertions.assertThat(actualMasked)
                        .as("entity_parse case%02d", i).isEqualTo(expectedMasked);
            } else {
                assertRec("entity_parse", String.format("case%02d", i), actual);
            }
        }

        EntityExtraction.Formater f = new EntityExtraction.Formater();
        PipelineConfig.PromptTemplateStructured tpl = new PipelineConfig.PromptTemplateStructured();
        tpl.setDescription("Extract entities and relations for %s from the text.");
        tpl.setTags(new ArrayList<>(List.of("person", "org")));
        PipelineConfig.PromptTemplateStructured.Example ex = new PipelineConfig.PromptTemplateStructured.Example();
        ex.setText("张三在 北京大学 工作。");
        ex.setNode(new ArrayList<>(List.of(
                new GraphNode("张三", null, List.of("人")),
                new GraphNode("北京大学", null, List.of("组织")))));
        ex.setRelation(new ArrayList<>(List.of(new GraphRelation("张三", "北京大学", "works_at"))));
        tpl.setExamples(new ArrayList<>(List.of(ex)));
        EntityExtraction.QAPromptGenerator qa = new EntityExtraction.QAPromptGenerator(f, tpl);
        // B43：formatExtraction/toJsonArray 改标准 Jackson（Java 原生）——Go 缩进形态
        // （"entity": / 每元素独立行）不再是断言目标；期望锚定「本仓标准形态」基线（人工核验）。
        String sysExpected = "Extract entities and relations for [\"person\",\"org\"] from the text.\n"
                + "# Examples\n"
                + "Q: 张三在 北京大学 工作。\n"
                + "A: ```json\n"
                + "[ {\n"
                + "  \"entity\" : \"张三\",\n"
                + "  \"entity_attributes\" : [ \"人\" ]\n"
                + "}, {\n"
                + "  \"entity\" : \"北京大学\",\n"
                + "  \"entity_attributes\" : [ \"组织\" ]\n"
                + "}, {\n"
                + "  \"entity1\" : \"张三\",\n"
                + "  \"entity2\" : \"北京大学\",\n"
                + "  \"relation\" : \"works_at\"\n"
                + "} ]\n"
                + "```\n";
        String userExpected = "# Question\n"
                + "Q: 李四 住在 上海。\n"
                + "A: ";
        org.assertj.core.api.Assertions.assertThat(qa.system()).as("entity_format/system").isEqualTo(sysExpected);
        org.assertj.core.api.Assertions.assertThat(qa.user("李四 住在 上海。")).as("entity_format/user")
                .isEqualTo(userExpected);
        String ans = f.formatExtraction(ex.getNode(), ex.getRelation());
        String ansExpected = "```json\n"
                + "[ {\n"
                + "  \"entity\" : \"张三\",\n"
                + "  \"entity_attributes\" : [ \"人\" ]\n"
                + "}, {\n"
                + "  \"entity\" : \"北京大学\",\n"
                + "  \"entity_attributes\" : [ \"组织\" ]\n"
                + "}, {\n"
                + "  \"entity1\" : \"张三\",\n"
                + "  \"entity2\" : \"北京大学\",\n"
                + "  \"relation\" : \"works_at\"\n"
                + "} ]\n"
                + "```";
        org.assertj.core.api.Assertions.assertThat(ans).as("entity_format/example_answer").isEqualTo(ansExpected);
        // B43：形态标准化（标准 Jackson）——期望锚定「本仓标准形态」基线（人工核验）。
        String renderExpected = "[{\"content\":\"Extract entities and relations for [\\\"person\\\",\\\"org\\\"] from the text.\\n# Examples\\nQ: 张三在 北京大学 工作。\\nA: ```json\\n[ {\\n  \\\"entity\\\" : \\\"张三\\\",\\n  \\\"entity_attributes\\\" : [ \\\"人\\\" ]\\n}, {\\n  \\\"entity\\\" : \\\"北京大学\\\",\\n  \\\"entity_attributes\\\" : [ \\\"组织\\\" ]\\n}, {\\n  \\\"entity1\\\" : \\\"张三\\\",\\n  \\\"entity2\\\" : \\\"北京大学\\\",\\n  \\\"relation\\\" : \\\"works_at\\\"\\n} ]\\n```\\n\",\"images\":null,\"role\":\"system\"},{\"content\":\"# Question\\nQ: 问题正文\\nA: \",\"images\":null,\"role\":\"user\"}]";
        org.assertj.core.api.Assertions.assertThat(json(msgsJSON(qa.render("问题正文"))))
                .as("entity_format/render").isEqualTo(renderExpected);
    }

    // ----- web_fetch（对照 recWebFetch；本地 httptest stub，无外网） -----

    /**
     * 对照 recWebFetch。语义备案：Go 探针的 httptest 服务器虽在本地起真，
     * 但 web_fetch 的 SSRF 守卫默认拒绝 loopback —— Go 实录里所有抓取都以
     * 失败收场（内容保持原样），本组锁的就是"抓取失败 → 内容不变"的管线行为。
     * Java 侧用 127.0.0.1:9（discard 保留端口，白名单无论放宽与否都必拒/必拒连），
     * 不依赖进程级 SsrfGuard 的状态（4.5a 踩过的静态白名单污染），确定性成立。
     */
    @Test
    void webFetch() {
        PluginWebFetch p = new PluginWebFetch();

        // 1) disabled
        ChatManage cm = new ChatManage();
        boolean[] next = {false};
        p.onEvent(PipelineEventType.WEB_FETCH, cm, () -> { next[0] = true; return null; });
        assertRec("web_fetch", "disabled", json(Map.of("next", next[0])));

        // 2) no web results
        ChatManage cm2 = new ChatManage();
        cm2.setWebFetchEnabled(true);
        cm2.setWebSearchEnabled(true);
        p.onEvent(PipelineEventType.WEB_FETCH, cm2, () -> null);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("n", 0);
        assertRec("web_fetch", "no_web_results", json(s2));

        // 3) 抓取失败路径（与 Go 实录同语义：内容保持原样）
        ChatManage cm3 = new ChatManage();
        cm3.setWebFetchEnabled(true);
        cm3.setWebSearchEnabled(true);
        List<SearchResult> rr = new ArrayList<>();
        rr.add(webResult("http://127.0.0.1:9/big", "旧摘要"));
        rr.add(webResult("http://127.0.0.1:9/small", "旧摘要二"));
        rr.add(webResult("http://127.0.0.1:9/empty", "旧摘要三"));
        rr.add(webResult("http://127.0.0.1:9/fail-host", "旧摘要四"));
        rr.add(webResult("doc-1", "非 web 不动"));
        cm3.setRerankResult(rr);
        boolean[] next3 = {false};
        p.onEvent(PipelineEventType.WEB_FETCH, cm3, () -> { next3[0] = true; return null; });
        List<Map<String, Object>> shape = new ArrayList<>();
        for (SearchResult r : cm3.getRerankResult()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            // Go 的 len() 是 UTF-8 字节数
            row.put("len", r.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            int runeLen = r.getContent().codePointCount(0, r.getContent().length());
            row.put("head", runeList(r.getContent(), Math.min(30, runeLen), 0));
            row.put("tail", r.getContent().length() <= 30 ? r.getContent()
                    : r.getContent().substring(r.getContent().length() - 30));
            row.put("exact", r.getContent());
            shape.add(row);
        }
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        s3.put("results", shape);
        assertRec("web_fetch", "fetch", mask(json(s3)));
    }

    private static SearchResult webResult(String id, String content) {
        SearchResult r = Rec46cSupport.sr(id, content, "", 0d);
        r.setKnowledgeSource("web_search");
        return r;
    }

    private static List<Integer> runeList(String s, int n, int from) {
        List<Integer> out = new ArrayList<>();
        s.codePoints().skip(from).limit(n).forEach(out::add);
        return out;
    }
}

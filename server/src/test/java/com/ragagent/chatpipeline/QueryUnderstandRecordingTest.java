package com.ragagent.chatpipeline;

import static com.ragagent.chatpipeline.Rec46cSupport.assertRec;
import static com.ragagent.chatpipeline.Rec46cSupport.errOf;
import static com.ragagent.chatpipeline.Rec46cSupport.historyShape;
import static com.ragagent.chatpipeline.Rec46cSupport.json;
import static com.ragagent.chatpipeline.Rec46cSupport.mask;
import static com.ragagent.chatpipeline.Rec46cSupport.message;
import static com.ragagent.chatpipeline.Rec46cSupport.msgsJSON;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.common.session.PipelineMessageAttachmentView;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * 实录回放：query_understand 族 + load_history + history_messages
 * （期望值 = {@link GoRecording46C} 的 Go 实录常量；墙钟段两侧 DATE/WEEKDAY 掩码）。
 */
class QueryUnderstandRecordingTest {

    private static final String[] PARSE_CORPUS = {
            "{\"rewrite_query\":\"rewritten query\",\"intent\":\"summarize\"}",
            "{\"rewrite_query\":\" 改写后的问题 \",\"intent\":\"chitchat\",\"image_description\":\" 一只猫 \"}",
            "{\"query\":\"alt query key\"}",
            "{\"question\":\"question key\",\"intent\":\"follow_up\"}",
            "{\"rewritten_query\":\"rewritten key\"}",
            "{\"image_desc\":\"desc only\"}",
            "{\"ocr_text\":\"pure ocr\"}",
            "{\"image_text\":\"img text\",\"ocr\":\"ocr body\"}",
            "{\"description\":\"plain desc\",\"full_ocr\":\"full\"}",
            "{\"image_description\":\"already contains ocr body\",\"ocr\":\"ocr body\"}",
            "{\"image_description\":\"A\",\"image_ocr_text\":\"B\",\"image_ocr\":\"C\",\"ocr_content\":\"D\"}",
            "  {\"intent\":\"greeting\"}  ",
            "not json at all",
            "The answer is: check the admin console",
            "   \n\t  ",
            "",
            "Here is the result: {\"rewrite_query\":\"wrapped\",\"intent\":\"doc_only\"} hope it helps",
            "{\"rewrite_query\":123,\"intent\":456}",
            "{\"unknown_key\":\"value\"}",
    };

    @Test
    void queryUnderstandParse() {
        PluginQueryUnderstand p = new PluginQueryUnderstand(null, null, null, null);
        for (int i = 0; i < PARSE_CORPUS.length; i++) {
            ChatManage cm = new ChatManage();
            cm.setRewriteQuery("original user query");
            cm.setIntent(QueryIntent.KB_SEARCH);
            p.parseOutput(cm, PARSE_CORPUS[i]);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("input", PARSE_CORPUS[i]);
            shape.put("rewrite", cm.getRewriteQuery());
            shape.put("intent", cm.getIntent());
            shape.put("img_desc", cm.getImageDescription());
            assertRec("qu_parse", String.format("case%02d", i), json(shape));
        }
    }

    @Test
    void queryUnderstandParseStruct() {
        for (int i = 0; i < PARSE_CORPUS.length; i++) {
            PluginQueryUnderstand.StructuredQueryOutput out =
                    PluginQueryUnderstand.parseStructuredQueryOutput(PARSE_CORPUS[i]);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("ok", out != null);
            shape.put("rewrite", out == null ? "" : out.rewriteQuery);
            shape.put("intent", out == null ? "" : out.intent);
            shape.put("img_desc", out == null ? "" : out.imageDescription);
            assertRec("qu_parse_struct", String.format("case%02d", i), json(shape));
        }
    }

    @Test
    void queryUnderstandIntentOverride() {
        record Case(String key, Map<String, String> over, Map<String, String> global, String intent) {}
        List<Case> cases = List.of(
                new Case("agent_wins", Map.of("chitchat", "agent prompt"),
                        Map.of("chitchat", "global prompt"), QueryIntent.CHITCHAT),
                new Case("agent_whitespace", Map.of("chitchat", "  agent prompt with trailing newline\n"),
                        null, QueryIntent.CHITCHAT),
                new Case("blank_falls_to_global", Map.of("chitchat", "   \n\t  "),
                        Map.of("chitchat", "global prompt"), QueryIntent.CHITCHAT),
                new Case("none", null, null, QueryIntent.CHITCHAT),
                new Case("global_only", null, Map.of("greeting", "hi there"), QueryIntent.GREETING),
                new Case("intent_without_entry", null, Map.of("greeting", "hi"), QueryIntent.KB_SEARCH));
        for (Case c : cases) {
            ChatManage cm = new ChatManage();
            cm.setIntentPromptOverrides(c.over());
            cm.setIntent(c.intent());
            boolean applied = PluginQueryUnderstand.applyIntentPromptOverride(cm, c.global());
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("applied", applied);
            shape.put("override", cm.getSystemPromptOverride());
            assertRec("qu_intent", c.key(), json(shape));
        }
    }

    @Test
    void queryUnderstandBuildPrompts() {
        PipelineConfig cfg = new PipelineConfig();
        cfg.setRewritePromptSystem("SYS conv={{conversation}} q={{query}} lang={{language}} time={{current_time}} week={{current_week}} yesterday={{yesterday}}");
        cfg.setRewritePromptUser("USER q={{query}} lang={{language}}");
        PluginQueryUnderstand p = new PluginQueryUnderstand(null, null, null, cfg);

        ChatManage cm = new ChatManage();
        cm.setQuery("什么是知识库？");
        cm.setLanguage("zh");
        String[] out1 = p.buildPrompts(cm, null);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("system", out1[0]);
        s1.put("user", out1[1]);
        assertRec("qu_prompts", "no_history", mask(json(s1)));

        List<History> hist = List.of(
                hist("问题一", "回答一"), hist("问题二", "回答二"));
        ChatManage cm2 = new ChatManage();
        cm2.setQuery("第二问");
        cm2.setLanguage("zh");
        String[] out2 = p.buildPrompts(cm2, new ArrayList<>(hist));
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("system", out2[0]);
        s2.put("user", out2[1]);
        assertRec("qu_prompts", "with_history", mask(json(s2)));

        ChatManage cm3 = new ChatManage();
        cm3.setQuery("看图");
        cm3.setLanguage("en");
        cm3.setImages(List.of("data:image/png;base64,AAA"));
        cm3.setAttachments(new ArrayList<>(List.of(new PipelineMessageAttachmentView(
                "报告.pdf", ".pdf", 2048, "full", "报告正文", true, 100, 0, 0))));
        cm3.setRewritePromptSystem("AGENT SYS {{query}}");
        cm3.setRewritePromptUser("AGENT USER {{query}} {{conversation}}");
        cm3.setIntentPromptOverrides(new LinkedHashMap<>());
        String[] out3 = p.buildPrompts(cm3, new ArrayList<>(hist));
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("system", out3[0]);
        s3.put("user", out3[1]);
        assertRec("qu_prompts", "images_attachments_override", mask(json(s3)));

        ChatManage cm4 = new ChatManage();
        cm4.setQuery("纯文本");
        cm4.setLanguage("zh");
        String[] out4 = p.buildPrompts(cm4, null);
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("user", out4[1]);
        assertRec("qu_prompts", "no_image_tags", mask(json(s4)));
    }

    // ----- OnEvent（对照 recQueryUnderstandOnEvent） -----

    private PipelineConfig quConfig() {
        PipelineConfig cfg = new PipelineConfig();
        cfg.setRewritePromptSystem("SYS {{conversation}}{{query}}");
        cfg.setRewritePromptUser("USER {{query}}");
        return cfg;
    }

    record QuPlugin(PluginQueryUnderstand plugin, Rec46cSupport.StubChat chat,
                    Rec46cSupport.StubModelService model) {}

    private QuPlugin newQuPlugin(List<String> responses, List<RuntimeException> errs,
                                 Rec46cSupport.StubModelService ms) {
        Rec46cSupport.StubChat c = new Rec46cSupport.StubChat(responses, errs);
        Rec46cSupport.StubMessageService msgSvc = histMsgService();
        PluginQueryUnderstand p = new PluginQueryUnderstand(ms, msgSvc, null, quConfig());
        return new QuPlugin(p, c, ms);
    }

    private static Rec46cSupport.StubMessageService histMsgService() {
        Rec46cSupport.StubMessageService svc = new Rec46cSupport.StubMessageService();
        svc.messages = List.of(
                message("r1", "user", "第一问", Instant.parse("2024-01-01T00:00:00Z")),
                message("r1", "assistant", "<think>hidden</think>第一答", Instant.parse("2024-01-01T00:00:01Z")));
        return svc;
    }


    @Test
    void queryUnderstandOnEvent() {
        // 1) skip
        Rec46cSupport.StubModelService ms1 = new Rec46cSupport.StubModelService();
        QuPlugin qu1 = newQuPlugin(null, null, ms1);
        ChatManage cm1 = new ChatManage();
        cm1.setSessionId("s1");
        cm1.setQuery("hello");
        boolean[] next1 = {false};
        PluginError err1 = qu1.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm1,
                () -> { next1[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("rewrite", cm1.getRewriteQuery());
        s1.put("next", next1[0]);
        s1.put("err", errOf(err1));
        s1.put("llm_calls", qu1.chat().calls.size());
        assertRec("qu_on_event", "skip", json(s1));

        // 2) 改写成功
        Rec46cSupport.StubModelService ms2 = new Rec46cSupport.StubModelService();
        QuPlugin qu2 = newQuPlugin(
                List.of("{\"rewrite_query\":\"精简后的查询\",\"intent\":\"kb_search\",\"image_description\":\"\"}"),
                null, ms2);
        qu2.model().chatModels.put("chat-1", qu2.chat());
        qu2.model().chatModels.put("qu-1", qu2.chat());
        qu2.model().chatModels.put("vlm-1", qu2.chat());
        ChatManage cm2 = new ChatManage();
        cm2.setSessionId("s2");
        cm2.setQuery("原始查询");
        cm2.setEnableRewrite(true);
        cm2.setChatModelId("chat-1");
        cm2.setMaxRounds(2);
        cm2.setTenantId(1);
        boolean[] next2 = {false};
        PluginError err2 = qu2.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm2,
                () -> { next2[0] = true; return null; });
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("rewrite", cm2.getRewriteQuery());
        s2.put("intent", cm2.getIntent());
        s2.put("next", next2[0]);
        s2.put("err", errOf(err2));
        s2.put("llm_calls", qu2.chat().calls);
        s2.put("history", historyShape(cm2.getHistory()));
        assertRec("qu_on_event", "rewrite_success", mask(json(s2)));

        // 3) LLM 失败降级
        Rec46cSupport.StubModelService ms3 = new Rec46cSupport.StubModelService();
        QuPlugin qu3 = newQuPlugin(null,
                List.of(new RuntimeException("llm unavailable")), ms3);
        qu3.model().chatModels.put("chat-1", qu3.chat());
        ChatManage cm3 = new ChatManage();
        cm3.setSessionId("s3");
        cm3.setQuery("原始查询");
        cm3.setEnableRewrite(true);
        cm3.setChatModelId("chat-1");
        boolean[] next3 = {false};
        PluginError err3 = qu3.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm3,
                () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("rewrite", cm3.getRewriteQuery());
        s3.put("next", next3[0]);
        s3.put("err", errOf(err3));
        s3.put("llm_calls", qu3.chat().calls.size());
        assertRec("qu_on_event", "llm_error_degrade", json(s3));

        // 4) 无法解析输出
        Rec46cSupport.StubModelService ms4 = new Rec46cSupport.StubModelService();
        QuPlugin qu4 = newQuPlugin(List.of("I cannot answer that"), null, ms4);
        qu4.model().chatModels.put("chat-1", qu4.chat());
        ChatManage cm4 = new ChatManage();
        cm4.setSessionId("s4");
        cm4.setQuery("原始查询");
        cm4.setEnableRewrite(true);
        cm4.setChatModelId("chat-1");
        boolean[] next4 = {false};
        qu4.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm4,
                () -> { next4[0] = true; return null; });
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("rewrite", cm4.getRewriteQuery());
        s4.put("intent", cm4.getIntent());
        s4.put("next", next4[0]);
        assertRec("qu_on_event", "unparsable_degrade", json(s4));

        // 5) 模型获取失败
        Rec46cSupport.StubModelService ms5 = new Rec46cSupport.StubModelService();
        ms5.chatErr.put("chat-1", new RuntimeException("model gone"));
        PluginQueryUnderstand p5 = new PluginQueryUnderstand(ms5, histMsgService(), null, quConfig());
        ChatManage cm5 = new ChatManage();
        cm5.setSessionId("s5");
        cm5.setQuery("q");
        cm5.setEnableRewrite(true);
        cm5.setChatModelId("chat-1");
        boolean[] next5 = {false};
        PluginError err5 = p5.onEvent(PipelineEventType.QUERY_UNDERSTAND, cm5,
                () -> { next5[0] = true; return null; });
        Map<String, Object> s5 = new LinkedHashMap<>();
        s5.put("next", next5[0]);
        s5.put("err", errOf(err5));
        assertRec("qu_on_event", "model_missing", json(s5));

        // 6) 意图覆写
        Rec46cSupport.StubModelService ms6 = new Rec46cSupport.StubModelService();
        QuPlugin qu6 = newQuPlugin(List.of("{\"rewrite_query\":\"x\",\"intent\":\"greeting\"}"), null, ms6);
        qu6.model().chatModels.put("chat-1", qu6.chat());
        ChatManage cm6 = new ChatManage();
        cm6.setSessionId("s6");
        cm6.setQuery("hi");
        cm6.setEnableRewrite(true);
        cm6.setChatModelId("chat-1");
        Map<String, String> ov = new LinkedHashMap<>();
        ov.put("greeting", "GREET-OVERRIDE");
        cm6.setIntentPromptOverrides(ov);
        qu6.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm6, () -> null);
        Map<String, Object> s6 = new LinkedHashMap<>();
        s6.put("intent", cm6.getIntent());
        s6.put("override", cm6.getSystemPromptOverride());
        assertRec("qu_on_event", "intent_override", json(s6));

        // 7) vision 模型路径
        Rec46cSupport.StubModelService ms7 = new Rec46cSupport.StubModelService();
        QuPlugin qu7 = newQuPlugin(
                List.of("{\"rewrite_query\":\"看图问题\",\"intent\":\"image_only\",\"image_description\":\"一张截图\"}"),
                null, ms7);
        qu7.model().chatModels.put("chat-1", qu7.chat());
        qu7.model().chatModels.put("vlm-1", qu7.chat());
        ChatManage cm7 = new ChatManage();
        cm7.setSessionId("s7");
        cm7.setQuery("这是什么");
        cm7.setEnableRewrite(true);
        cm7.setChatModelId("chat-1");
        cm7.setImages(List.of("img://1"));
        cm7.setChatModelSupportsVision(true);
        cm7.setVlmModelId("vlm-1");
        qu7.plugin().onEvent(PipelineEventType.QUERY_UNDERSTAND, cm7, () -> null);
        Map<String, Object> s7 = new LinkedHashMap<>();
        s7.put("rewrite", cm7.getRewriteQuery());
        s7.put("img_desc", cm7.getImageDescription());
        s7.put("intent", cm7.getIntent());
        s7.put("llm_calls", qu7.chat().calls);
        assertRec("qu_on_event", "vision_images", mask(json(s7)));

        // 8) VLM 回退
        Rec46cSupport.StubModelService ms8 = new Rec46cSupport.StubModelService();
        ms8.chatErr.put("chat-1", new RuntimeException("nope"));
        Rec46cSupport.StubChat c8 = new Rec46cSupport.StubChat(
                List.of("{\"rewrite_query\":\"vlm path\"}"), null);
        ms8.chatModels.put("vlm-1", c8);
        PluginQueryUnderstand p8 = new PluginQueryUnderstand(ms8, histMsgService(), null, quConfig());
        ChatManage cm8 = new ChatManage();
        cm8.setSessionId("s8");
        cm8.setQuery("看图");
        cm8.setEnableRewrite(true);
        cm8.setChatModelId("chat-1");
        cm8.setImages(List.of("img://1"));
        cm8.setVlmModelId("vlm-1");
        p8.onEvent(PipelineEventType.QUERY_UNDERSTAND, cm8, () -> null);
        Map<String, Object> s8 = new LinkedHashMap<>();
        s8.put("rewrite", cm8.getRewriteQuery());
        s8.put("calls", c8.calls.size());
        assertRec("qu_on_event", "vlm_fallback", json(s8));

        // 9) QueryUnderstandModelID 覆写与回退
        Rec46cSupport.StubModelService ms9 = new Rec46cSupport.StubModelService();
        Rec46cSupport.StubChat c9 = new Rec46cSupport.StubChat(
                List.of("{\"rewrite_query\":\"qu model\"}"), null);
        Rec46cSupport.StubChat c9b = new Rec46cSupport.StubChat(
                List.of("{\"rewrite_query\":\"chat model\"}"), null);
        ms9.chatModels.put("chat-9", c9b);
        ms9.chatModels.put("qu-9", c9);
        ms9.chatErr.put("qu-gone", new RuntimeException("deleted"));
        PluginQueryUnderstand p9 = new PluginQueryUnderstand(ms9, histMsgService(), null, quConfig());
        ChatManage cm9 = new ChatManage();
        cm9.setSessionId("s9");
        cm9.setQuery("q");
        cm9.setEnableRewrite(true);
        cm9.setChatModelId("chat-9");
        cm9.setQueryUnderstandModelId("qu-9");
        p9.onEvent(PipelineEventType.QUERY_UNDERSTAND, cm9, () -> null);
        ChatManage cm9b = new ChatManage();
        cm9b.setSessionId("s9b");
        cm9b.setQuery("q");
        cm9b.setEnableRewrite(true);
        cm9b.setChatModelId("chat-9");
        cm9b.setQueryUnderstandModelId("qu-gone");
        p9.onEvent(PipelineEventType.QUERY_UNDERSTAND, cm9b, () -> null);
        Map<String, Object> s9 = new LinkedHashMap<>();
        s9.put("model_calls", ms9.calls);
        s9.put("rewrite_a", cm9.getRewriteQuery());
        s9.put("rewrite_b", cm9b.getRewriteQuery());
        assertRec("qu_on_event", "query_understand_model", json(s9));
    }

    // ------------------------------------------------------------------
    // format_history / load_history / history_messages
    // ------------------------------------------------------------------

    private static History hist(String q, String a) {
        History h = new History();
        h.setQuery(q);
        h.setAnswer(a);
        return h;
    }

    @Test
    void formatHistory() {
        assertRec("format_history", "empty",
                json(Map.of("out", PluginQueryUnderstand.formatConversationHistory(null))));
        List<History> h = List.of(hist("Q1", "A1"), hist("Q2", "A2"));
        assertRec("format_history", "two",
                json(Map.of("out", PluginQueryUnderstand.formatConversationHistory(h))));
    }

    @Test
    void loadHistoryGroupSort() {
        Rec46cSupport.StubMessageService svc = new Rec46cSupport.StubMessageService();
        svc.messages = List.of(
                message("r2", "user", "第二问", Instant.parse("2024-01-02T00:00:00Z")),
                message("r2", "assistant", "第二答<think>链条</think>", Instant.parse("2024-01-02T00:00:05Z")),
                historyUserWithImages(),
                message("r1", "assistant", "第一答", Instant.parse("2024-01-01T00:00:03Z")),
                message("r3", "user", "孤儿问（无答案）", Instant.parse("2024-01-03T00:00:00Z")),
                historyUserWithAttachments(),
                message("r0", "assistant", "附件答", Instant.parse("2024-01-04T00:00:02Z")));
        List<History> hist = PipelineCommon.loadAndProcessHistory(svc, "s1", 2, 20);
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("err", null);
        shape.put("history", historyShape(hist));
        assertRec("load_history", "group_sort", json(shape));

        Rec46cSupport.StubMessageService svcErr = new Rec46cSupport.StubMessageService();
        svcErr.recentErr = new RuntimeException("db down");
        String errMsg;
        int histCount;
        try {
            List<History> hist2 = PipelineCommon.loadAndProcessHistory(svcErr, "s1", 2, 20);
            errMsg = null;
            histCount = hist2 == null ? 0 : hist2.size();
        } catch (RuntimeException e) {
            errMsg = e.getMessage();
            histCount = 0;
        }
        Map<String, Object> shape2 = new LinkedHashMap<>();
        shape2.put("err", errMsg);
        shape2.put("history", histCount);
        assertRec("load_history", "error", json(shape2));
    }

    private Message historyUserWithImages() {
        Message m = message("r1", "user", "第一问", Instant.parse("2024-01-01T00:00:00Z"));
        var img1 = new com.ragagent.session.domain.MessageImage();
        img1.setUrl("u1");
        img1.setCaption("图一");
        var img2 = new com.ragagent.session.domain.MessageImage();
        img2.setUrl("u2");
        m.setImages(new ArrayList<>(List.of(img1, img2)));
        return m;
    }

    private Message historyUserWithAttachments() {
        Message m = message("r0", "user", "带附件", Instant.parse("2024-01-04T00:00:00Z"));
        MessageAttachment att = new MessageAttachment();
        att.setFileName("f.pdf");
        att.setFileType(".pdf");
        att.setContent("附件内容");
        m.setAttachments(new ArrayList<>(List.of(att)));
        return m;
    }

    @Test
    void historyMessages() {
        ChatManage cm = new ChatManage();
        cm.setQuery("当前问题");
        cm.setLanguage("zh");
        cm.setSessionId("s1");
        SummaryConfig sc = new SummaryConfig();
        sc.setPrompt("SYS {{query}} {{contexts}} {{language}}");
        cm.setSummaryConfig(sc);
        List<History> hist = new ArrayList<>();
        History h1 = new History();
        h1.setQuery("H1问");
        h1.setAnswer("H1答");
        History h2 = new History();
        h2.setQuery("H2问");
        h2.setAnswer("H2答");
        hist.add(h1);
        hist.add(h2);
        cm.setHistory(hist);
        cm.setMemoryPrompt("\n\n<memory>MEM</memory>");

        var msgs = PipelineCommon.prepareMessagesWithHistory(cm);
        assertRec("history_messages", "with_memory", mask(json(msgsJSON(msgs))));

        ChatManage cm2 = new ChatManage();
        cm2.setQuery("q");
        SummaryConfig sc2 = new SummaryConfig();
        sc2.setPrompt("BASE");
        cm2.setSummaryConfig(sc2);
        cm2.setSystemPromptOverride("OVERRIDE {{query}}");
        var msgs2 = PipelineCommon.prepareMessagesWithHistory(cm2);
        assertRec("history_messages", "override", mask(json(msgsJSON(msgs2))));

        ChatManage cm3 = new ChatManage();
        cm3.setQuery("q");
        cm3.setChatModelSupportsVision(true);
        cm3.setImages(List.of("img://a"));
        var msgs3 = PipelineCommon.prepareMessagesWithHistory(cm3);
        assertRec("history_messages", "vision_images", json(msgsJSON(msgs3)));

        ChatManage cm4 = new ChatManage();
        cm4.setQuery("q");
        cm4.setChatModelSupportsVision(false);
        cm4.setImages(List.of("img://a"));
        var msgs4 = PipelineCommon.prepareMessagesWithHistory(cm4);
        assertRec("history_messages", "no_vision", json(msgsJSON(msgs4)));

        List<com.ragagent.llm.domain.ChatMessage> base =
                new ArrayList<>(List.of(new com.ragagent.llm.domain.ChatMessage("system", "S")));
        PipelineCommon.appendHistoryMessages(base, List.of(hist("Q", "A")));
        assertRec("history_messages", "append", json(msgsJSON(base)));
    }
}

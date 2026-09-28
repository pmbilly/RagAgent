package com.ragagent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 事件 payload 的 JSON 字节形状——<b>期望值全部是 /tmp Go 程序实录</b>
 * （类型逐字抄自 internal/event/event_data.go，跑 {@code json.Marshal}），
 * 不是照直觉写的。覆盖三类形态：
 * <ol>
 *   <li>零值（无 omitempty 的字段恒输出，含 {@code "plan":null} 这类 nil slice/map）；</li>
 *   <li>全量（键序 = Go struct 声明序；HTML 转义；map 键字母序；浮点 Go 格式）；</li>
 *   <li>omitempty 边界（空串 / 0 / false / 空 map / 空列表整键省略）。</li>
 * </ol>
 *
 * <p>线上意义：payload 经 AgentStreamHandler 的 {@code toolApprovalDataToMap}
 * （{@code json.Marshal(payload)} → map）进 StreamResponse.data，最终出现在
 * continue-stream 的 SSE 帧里（§9.3 四路径 MATCH 基线）。</p>
 */
class EventPayloadJsonTest {

    private static String write(Object payload) {
        return EventJson.write(payload);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<Object> listOf(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    // ===== QueryData =====

    @Test
    void queryDataZero() {
        // Go: {"original_query":"","session_id":""}
        assertEquals("{\"original_query\":\"\",\"session_id\":\"\"}", write(new QueryData()));
    }

    @Test
    void queryDataFull() {
        // Go: {"original_query":"What is \u003cRAG\u003e \u0026 why?","rewritten_query":"explain retrieval-augmented generation",
        //      "session_id":"sess-1","user_id":"u-1","extra":{"alpha":true,"mid":"m","zebra":1}}
        QueryData d = new QueryData();
        d.setOriginalQuery("What is <RAG> & why?");
        d.setRewrittenQuery("explain retrieval-augmented generation");
        d.setSessionId("sess-1");
        d.setUserId("u-1");
        // 刻意乱序插入：Go 的 map 按字母序输出
        d.setExtra(mapOf("zebra", 1, "alpha", true, "mid", "m"));
        assertEquals("{\"original_query\":\"What is \\u003cRAG\\u003e \\u0026 why?\","
                + "\"rewritten_query\":\"explain retrieval-augmented generation\","
                + "\"session_id\":\"sess-1\",\"user_id\":\"u-1\","
                + "\"extra\":{\"alpha\":true,\"mid\":\"m\",\"zebra\":1}}", write(d));
    }

    @Test
    void queryDataEmptyExtraOmitted() {
        // Go: {"original_query":"q","session_id":"s"}——空 map 也被 omitempty 省略
        QueryData d = new QueryData("q", "", "s", "", new LinkedHashMap<>());
        assertEquals("{\"original_query\":\"q\",\"session_id\":\"s\"}", write(d));
    }

    // ===== RetrievalData =====

    @Test
    void retrievalDataZero() {
        // Go: {"query":"","knowledge_base_id":"","top_k":0,"threshold":0,"retrieval_type":"","result_count":0}
        assertEquals("{\"query\":\"\",\"knowledge_base_id\":\"\",\"top_k\":0,\"threshold\":0,"
                + "\"retrieval_type\":\"\",\"result_count\":0}", write(new RetrievalData()));
    }

    @Test
    void retrievalDataFull() {
        // Go: {"query":"q","knowledge_base_id":"kb-1","top_k":10,"threshold":0.5,"retrieval_type":"vector",
        //      "result_count":2,"results":[{"content":"c","score":0.9}],"duration_ms":123,"extra":{"k":"v"}}
        RetrievalData d = new RetrievalData();
        d.setQuery("q");
        d.setKnowledgeBaseId("kb-1");
        d.setTopK(10);
        d.setThreshold(0.5);
        d.setRetrievalType("vector");
        d.setResultCount(2);
        d.setResults(listOf(mapOf("score", 0.9, "content", "c")));
        d.setDurationMs(123);
        d.setExtra(mapOf("k", "v"));
        assertEquals("{\"query\":\"q\",\"knowledge_base_id\":\"kb-1\",\"top_k\":10,\"threshold\":0.5,"
                + "\"retrieval_type\":\"vector\",\"result_count\":2,"
                + "\"results\":[{\"content\":\"c\",\"score\":0.9}],\"duration_ms\":123,\"extra\":{\"k\":\"v\"}}",
                write(d));
    }

    @Test
    void retrievalDataZeroThresholdAlwaysOutput() {
        // Go: {"query":"q","knowledge_base_id":"kb","top_k":0,"threshold":0,"retrieval_type":"keyword","result_count":0}
        // threshold 无 omitempty：0 也输出
        RetrievalData d = new RetrievalData("q", "kb", 0, 0.0, "keyword", 0, null, 0, null);
        assertEquals("{\"query\":\"q\",\"knowledge_base_id\":\"kb\",\"top_k\":0,\"threshold\":0,"
                + "\"retrieval_type\":\"keyword\",\"result_count\":0}", write(d));
    }

    // ===== RerankData =====

    @Test
    void rerankDataZero() {
        // Go: {"query":"","input_count":0,"output_count":0,"model_id":"","threshold":0}
        assertEquals("{\"query\":\"\",\"input_count\":0,\"output_count\":0,\"model_id\":\"\",\"threshold\":0}",
                write(new RerankData()));
    }

    @Test
    void rerankDataFull() {
        // Go: {"query":"q","input_count":10,"output_count":5,"model_id":"m-1","threshold":0.3,
        //      "results":["a","b"],"duration_ms":45}
        RerankData d = new RerankData("q", 10, 5, "m-1", 0.3, new ArrayList<>(List.of("a", "b")), 45, null);
        assertEquals("{\"query\":\"q\",\"input_count\":10,\"output_count\":5,\"model_id\":\"m-1\","
                + "\"threshold\":0.3,\"results\":[\"a\",\"b\"],\"duration_ms\":45}", write(d));
    }

    // ===== MergeData =====

    @Test
    void mergeDataZero() {
        // Go: {"input_count":0,"output_count":0,"merge_type":""}
        assertEquals("{\"input_count\":0,\"output_count\":0,\"merge_type\":\"\"}", write(new MergeData()));
    }

    @Test
    void mergeDataFull() {
        // Go: {"input_count":3,"output_count":2,"merge_type":"dedup","results":[1,2],"duration_ms":7}
        MergeData d = new MergeData(3, 2, "dedup", new ArrayList<>(List.of(1, 2)), 7, null);
        assertEquals("{\"input_count\":3,\"output_count\":2,\"merge_type\":\"dedup\","
                + "\"results\":[1,2],\"duration_ms\":7}", write(d));
    }

    // ===== ChatData =====

    @Test
    void chatDataZero() {
        // Go: {"query":"","model_id":"","is_stream":false}——is_stream 无 omitempty
        assertEquals("{\"query\":\"\",\"model_id\":\"\",\"is_stream\":false}", write(new ChatData()));
    }

    @Test
    void chatDataFull() {
        // Go: {"query":"q","model_id":"m","response":"r","stream_chunk":"sc","token_count":42,
        //      "duration_ms":99,"is_stream":true,"extra":{"a":1.5}}
        ChatData d = new ChatData("q", "m", "r", "sc", 42, 99, true, mapOf("a", 1.5));
        assertEquals("{\"query\":\"q\",\"model_id\":\"m\",\"response\":\"r\",\"stream_chunk\":\"sc\","
                + "\"token_count\":42,\"duration_ms\":99,\"is_stream\":true,\"extra\":{\"a\":1.5}}",
                write(d));
    }

    // ===== ErrorData =====

    @Test
    void errorDataZero() {
        // Go: {"error":"","stage":"","session_id":""}
        assertEquals("{\"error\":\"\",\"stage\":\"\",\"session_id\":\"\"}", write(new ErrorData()));
    }

    @Test
    void errorDataFull() {
        // Go: {"error":"boom \u003cx\u003e\u0026","error_code":"E-1","stage":"agent_execution",
        //      "session_id":"s-1","query":"q","extra":{"attempt":2}}
        // extra 里的 float64(2.0) 输出 2（Go 编码器整数不补 .0）
        ErrorData d = new ErrorData("boom <x>&", "E-1", "agent_execution", "s-1", "q", mapOf("attempt", 2.0));
        assertEquals("{\"error\":\"boom \\u003cx\\u003e\\u0026\",\"error_code\":\"E-1\","
                + "\"stage\":\"agent_execution\",\"session_id\":\"s-1\",\"query\":\"q\","
                + "\"extra\":{\"attempt\":2}}", write(d));
    }

    // ===== AgentPlanData =====

    @Test
    void agentPlanDataZero() {
        // Go: {"query":"","plan":null}——plan 无 omitempty：nil slice 输出 null
        assertEquals("{\"query\":\"\",\"plan\":null}", write(new AgentPlanData()));
    }

    @Test
    void agentPlanDataFull() {
        // Go: {"query":"q","plan":["step1","step2"],"duration_ms":5}
        AgentPlanData d = new AgentPlanData("q", new ArrayList<>(List.of("step1", "step2")), 5);
        assertEquals("{\"query\":\"q\",\"plan\":[\"step1\",\"step2\"],\"duration_ms\":5}", write(d));
    }

    @Test
    void agentPlanDataNilVsEmptyPlan() {
        // Go: nilPlan => {"query":"q","plan":null,"duration_ms":5}
        assertEquals("{\"query\":\"q\",\"plan\":null,\"duration_ms\":5}",
                write(new AgentPlanData("q", null, 5)));
        // Go: emptyPlan => {"query":"q","plan":[],"duration_ms":5}
        assertEquals("{\"query\":\"q\",\"plan\":[],\"duration_ms\":5}",
                write(new AgentPlanData("q", new ArrayList<>(), 5)));
    }

    // ===== AgentStepData =====

    @Test
    void agentStepDataZero() {
        // Go: {"iteration":0,"thought":"","tool_calls":null,"duration_ms":0}
        assertEquals("{\"iteration\":0,\"thought\":\"\",\"tool_calls\":null,\"duration_ms\":0}",
                write(new AgentStepData()));
    }

    @Test
    void agentStepDataFull() {
        // Go: {"iteration":2,"thought":"thinking","tool_calls":[{"name":"web_search"}],"duration_ms":300}
        AgentStepData d = new AgentStepData(2, "thinking", listOf(mapOf("name", "web_search")), 300);
        assertEquals("{\"iteration\":2,\"thought\":\"thinking\","
                + "\"tool_calls\":[{\"name\":\"web_search\"}],\"duration_ms\":300}", write(d));
    }

    // ===== AgentActionData =====

    @Test
    void agentActionDataZero() {
        // Go: {"iteration":0,"tool_name":"","tool_input":null,"tool_output":"","success":false,"duration_ms":0}
        assertEquals("{\"iteration\":0,\"tool_name\":\"\",\"tool_input\":null,\"tool_output\":\"\","
                + "\"success\":false,\"duration_ms\":0}", write(new AgentActionData()));
    }

    @Test
    void agentActionDataFull() {
        // Go: {"iteration":1,"tool_name":"web_search","tool_input":{"limit":5,"query":"golang"},
        //      "tool_output":"out","success":true,"duration_ms":120}
        // tool_input 的 float64(5) 输出 5
        AgentActionData d = new AgentActionData(1, "web_search", mapOf("query", "golang", "limit", 5.0),
                "out", true, "", 120);
        assertEquals("{\"iteration\":1,\"tool_name\":\"web_search\","
                + "\"tool_input\":{\"limit\":5,\"query\":\"golang\"},\"tool_output\":\"out\","
                + "\"success\":true,\"duration_ms\":120}", write(d));
    }

    @Test
    void agentActionDataNilInputWithError() {
        // Go: {"iteration":1,"tool_name":"web_search","tool_input":null,"tool_output":"out",
        //      "success":false,"error":"timeout","duration_ms":120}
        AgentActionData d = new AgentActionData(1, "web_search", null, "out", false, "timeout", 120);
        assertEquals("{\"iteration\":1,\"tool_name\":\"web_search\",\"tool_input\":null,"
                + "\"tool_output\":\"out\",\"success\":false,\"error\":\"timeout\",\"duration_ms\":120}",
                write(d));
    }

    // ===== AgentQueryData =====

    @Test
    void agentQueryDataZero() {
        // Go: {"session_id":"","query":""}
        assertEquals("{\"session_id\":\"\",\"query\":\"\"}", write(new AgentQueryData()));
    }

    @Test
    void agentQueryDataFull() {
        // Go: {"session_id":"s","query":"q","request_id":"r-1","extra":{"src":"embed"}}
        AgentQueryData d = new AgentQueryData("s", "q", "r-1", mapOf("src", "embed"));
        assertEquals("{\"session_id\":\"s\",\"query\":\"q\",\"request_id\":\"r-1\","
                + "\"extra\":{\"src\":\"embed\"}}", write(d));
    }

    // ===== AgentCompleteData =====

    @Test
    void agentCompleteDataZero() {
        // Go: {"session_id":"","total_steps":0,"final_answer":"","total_duration_ms":0}
        assertEquals("{\"session_id\":\"\",\"total_steps\":0,\"final_answer\":\"\",\"total_duration_ms\":0}",
                write(new AgentCompleteData()));
    }

    @Test
    void agentCompleteDataFull() {
        // Go: {"session_id":"s","total_steps":3,"final_answer":"the answer",
        //      "knowledge_refs":[{"id":"c1","score":0.88}],"agent_steps":["think","act"],
        //      "usage":{"completion_tokens":5,"prompt_tokens":10},"total_duration_ms":1234,
        //      "message_id":"m-1","request_id":"r-1","extra":{"ch":"web"}}
        // usage 的 float64 整数输出 5/10（Go 编码器）
        AgentCompleteData d = new AgentCompleteData();
        d.setSessionId("s");
        d.setTotalSteps(3);
        d.setFinalAnswer("the answer");
        d.setKnowledgeRefs(listOf(mapOf("id", "c1", "score", 0.88)));
        d.setAgentSteps(new ArrayList<>(List.of("think", "act")));
        d.setUsage(mapOf("prompt_tokens", 10.0, "completion_tokens", 5.0));
        d.setTotalDurationMs(1234);
        d.setMessageId("m-1");
        d.setRequestId("r-1");
        d.setExtra(mapOf("ch", "web"));
        assertEquals("{\"session_id\":\"s\",\"total_steps\":3,\"final_answer\":\"the answer\","
                + "\"knowledge_refs\":[{\"id\":\"c1\",\"score\":0.88}],"
                + "\"agent_steps\":[\"think\",\"act\"],"
                + "\"usage\":{\"completion_tokens\":5,\"prompt_tokens\":10},"
                + "\"total_duration_ms\":1234,\"message_id\":\"m-1\",\"request_id\":\"r-1\","
                + "\"extra\":{\"ch\":\"web\"}}", write(d));
    }

    @Test
    void agentCompleteDataEmptyRefsOmitted() {
        // Go: {"session_id":"s","total_steps":1,"final_answer":"a","total_duration_ms":10}
        // 空列表也被 omitempty 省略
        AgentCompleteData d = new AgentCompleteData("s", 1, "a", new ArrayList<>(), null,
                null, 10, "", "", null);
        assertEquals("{\"session_id\":\"s\",\"total_steps\":1,\"final_answer\":\"a\",\"total_duration_ms\":10}",
                write(d));
    }

    // ===== AgentThoughtData =====

    @Test
    void agentThoughtDataZero() {
        // Go: {"content":"","iteration":0,"done":false}
        assertEquals("{\"content\":\"\",\"iteration\":0,\"done\":false}", write(new AgentThoughtData()));
    }

    @Test
    void agentThoughtDataFull() {
        // Go: {"content":"let me \u003cthink\u003e \u0026 see","iteration":2,"done":true}
        AgentThoughtData d = new AgentThoughtData("let me <think> & see", 2, true);
        assertEquals("{\"content\":\"let me \\u003cthink\\u003e \\u0026 see\",\"iteration\":2,\"done\":true}",
                write(d));
    }

    // ===== AgentToolCallData =====

    @Test
    void agentToolCallDataZero() {
        // Go: {"tool_call_id":"","tool_name":"","iteration":0}
        assertEquals("{\"tool_call_id\":\"\",\"tool_name\":\"\",\"iteration\":0}",
                write(new AgentToolCallData()));
    }

    @Test
    void agentToolCallDataFull() {
        // Go: {"tool_call_id":"call_1","tool_name":"web_search","arguments":{"limit":3,"query":"go"},
        //      "iteration":1,"hint":"web_search(\"go\")"}
        AgentToolCallData d = new AgentToolCallData("call_1", "web_search",
                mapOf("query", "go", "limit", 3.0), 1, "web_search(\"go\")");
        assertEquals("{\"tool_call_id\":\"call_1\",\"tool_name\":\"web_search\","
                + "\"arguments\":{\"limit\":3,\"query\":\"go\"},\"iteration\":1,"
                + "\"hint\":\"web_search(\\\"go\\\")\"}", write(d));
    }

    @Test
    void agentToolCallDataEmptyArgsOmitted() {
        // Go: {"tool_call_id":"call_1","tool_name":"t","iteration":0}——空 map 也省略
        AgentToolCallData d = new AgentToolCallData("call_1", "t", new LinkedHashMap<>(), 0, "");
        assertEquals("{\"tool_call_id\":\"call_1\",\"tool_name\":\"t\",\"iteration\":0}", write(d));
    }

    // ===== AgentToolResultData =====

    @Test
    void agentToolResultDataZero() {
        // Go: {"tool_call_id":"","tool_name":"","output":"","success":false,"iteration":0}
        assertEquals("{\"tool_call_id\":\"\",\"tool_name\":\"\",\"output\":\"\",\"success\":false,\"iteration\":0}",
                write(new AgentToolResultData()));
    }

    @Test
    void agentToolResultDataFull() {
        // Go: {"tool_call_id":"call_1","tool_name":"web_search","output":"res \u0026 \u003cres\u003e",
        //      "success":true,"duration_ms":88,"iteration":1,"data":{"display_type":"search","items":3}}
        AgentToolResultData d = new AgentToolResultData("call_1", "web_search", "res & <res>", "",
                true, 88, 1, mapOf("display_type", "search", "items", 3.0));
        assertEquals("{\"tool_call_id\":\"call_1\",\"tool_name\":\"web_search\","
                + "\"output\":\"res \\u0026 \\u003cres\\u003e\",\"success\":true,\"duration_ms\":88,"
                + "\"iteration\":1,\"data\":{\"display_type\":\"search\",\"items\":3}}", write(d));
    }

    @Test
    void agentToolResultDataError() {
        // Go: {"tool_call_id":"call_2","tool_name":"shell_exec","output":"","error":"exit status 1",
        //      "success":false,"iteration":2}
        AgentToolResultData d = new AgentToolResultData("call_2", "shell_exec", "", "exit status 1",
                false, 0, 2, null);
        assertEquals("{\"tool_call_id\":\"call_2\",\"tool_name\":\"shell_exec\",\"output\":\"\","
                + "\"error\":\"exit status 1\",\"success\":false,\"iteration\":2}", write(d));
    }

    // ===== AgentReferencesData / MemoryRecalledData =====

    @Test
    void agentReferencesDataZero() {
        // Go: {"references":null,"iteration":0}
        assertEquals("{\"references\":null,\"iteration\":0}", write(new AgentReferencesData()));
    }

    @Test
    void agentReferencesDataFull() {
        // Go: {"references":[{"content":"chunk","score":0.77}],"iteration":1}
        AgentReferencesData d = new AgentReferencesData(listOf(mapOf("content", "chunk", "score", 0.77)), 1);
        assertEquals("{\"references\":[{\"content\":\"chunk\",\"score\":0.77}],\"iteration\":1}",
                write(d));
    }

    @Test
    void memoryRecalledDataZero() {
        // Go: {"memories":null}
        assertEquals("{\"memories\":null}", write(new MemoryRecalledData()));
    }

    @Test
    void memoryRecalledDataFull() {
        // Go: {"memories":[{"text":"user likes go"}]}
        MemoryRecalledData d = new MemoryRecalledData(listOf(mapOf("text", "user likes go")));
        assertEquals("{\"memories\":[{\"text\":\"user likes go\"}]}", write(d));
    }

    // ===== AgentFinalAnswerData =====

    @Test
    void agentFinalAnswerDataZero() {
        // Go: {"content":"","done":false}
        assertEquals("{\"content\":\"\",\"done\":false}", write(new AgentFinalAnswerData()));
    }

    @Test
    void agentFinalAnswerDataFull() {
        // Go: {"content":"answer chunk","done":true,"is_fallback":true}
        assertEquals("{\"content\":\"answer chunk\",\"done\":true,\"is_fallback\":true}",
                write(new AgentFinalAnswerData("answer chunk", true, true)));
        // Go: doneOnly => {"content":"","done":true}
        assertEquals("{\"content\":\"\",\"done\":true}",
                write(new AgentFinalAnswerData("", true, false)));
    }

    // ===== ContextCompactedData =====

    @Test
    void contextCompactedDataZero() {
        // Go: {"reason":"","round":0,"tokens_before":0,"tokens_after":0,"messages_before":0,
        //      "messages_after":0,"summary":""}
        assertEquals("{\"reason\":\"\",\"round\":0,\"tokens_before\":0,\"tokens_after\":0,"
                + "\"messages_before\":0,\"messages_after\":0,\"summary\":\"\"}",
                write(new ContextCompactedData()));
    }

    @Test
    void contextCompactedDataFull() {
        // Go: {"reason":"threshold","round":3,"tokens_before":9000,"tokens_after":4000,
        //      "messages_before":20,"messages_after":8,"summary":"summary text","degraded":true,"split_turn":true}
        ContextCompactedData d = new ContextCompactedData("threshold", 3, 9000, 4000, 20, 8,
                "summary text", true, true);
        assertEquals("{\"reason\":\"threshold\",\"round\":3,\"tokens_before\":9000,\"tokens_after\":4000,"
                + "\"messages_before\":20,\"messages_after\":8,\"summary\":\"summary text\","
                + "\"degraded\":true,\"split_turn\":true}", write(d));
    }

    @Test
    void contextCompactedDataFlagsOmittedWhenFalse() {
        // Go: notDegraded => {...,"summary":"s"}——degraded/split_turn false 时省略
        ContextCompactedData d = new ContextCompactedData("overflow", 1, 100, 50, 4, 2, "s", false, false);
        assertEquals("{\"reason\":\"overflow\",\"round\":1,\"tokens_before\":100,\"tokens_after\":50,"
                + "\"messages_before\":4,\"messages_after\":2,\"summary\":\"s\"}", write(d));
    }

    // ===== UserMessageInjectedData =====

    @Test
    void userMessageInjectedDataZero() {
        // Go: {"steer_id":"","content":"","message_id":""}
        assertEquals("{\"steer_id\":\"\",\"content\":\"\",\"message_id\":\"\"}",
                write(new UserMessageInjectedData()));
    }

    @Test
    void userMessageInjectedDataFull() {
        // Go: {"steer_id":"st-1","content":"also check X","message_id":"m-1","user_message_id":"um-1"}
        assertEquals("{\"steer_id\":\"st-1\",\"content\":\"also check X\",\"message_id\":\"m-1\","
                + "\"user_message_id\":\"um-1\"}",
                write(new UserMessageInjectedData("st-1", "also check X", "m-1", "um-1")));
        // Go: noUserMsg => {"steer_id":"st-2","content":"c","message_id":"m-2"}
        assertEquals("{\"steer_id\":\"st-2\",\"content\":\"c\",\"message_id\":\"m-2\"}",
                write(new UserMessageInjectedData("st-2", "c", "m-2", "")));
    }

    // ===== AgentReflectionData / SessionTitleData / StopData =====

    @Test
    void agentReflectionDataZero() {
        // Go: {"tool_call_id":"","content":"","iteration":0,"done":false}
        assertEquals("{\"tool_call_id\":\"\",\"content\":\"\",\"iteration\":0,\"done\":false}",
                write(new AgentReflectionData()));
    }

    @Test
    void agentReflectionDataFull() {
        // Go: {"tool_call_id":"call_1","content":"reflecting","iteration":2,"done":true}
        assertEquals("{\"tool_call_id\":\"call_1\",\"content\":\"reflecting\",\"iteration\":2,\"done\":true}",
                write(new AgentReflectionData("call_1", "reflecting", 2, true)));
    }

    @Test
    void sessionTitleDataZero() {
        // Go: {"session_id":"","title":""}
        assertEquals("{\"session_id\":\"\",\"title\":\"\"}", write(new SessionTitleData()));
    }

    @Test
    void sessionTitleDataFull() {
        // Go: {"session_id":"s-1","title":"New Title"}
        assertEquals("{\"session_id\":\"s-1\",\"title\":\"New Title\"}",
                write(new SessionTitleData("s-1", "New Title")));
    }

    @Test
    void stopDataZero() {
        // Go: {"session_id":"","message_id":""}
        assertEquals("{\"session_id\":\"\",\"message_id\":\"\"}", write(new StopData()));
    }

    @Test
    void stopDataFullAndNoReason() {
        // Go: {"session_id":"s-1","message_id":"m-1","reason":"user_requested"}
        assertEquals("{\"session_id\":\"s-1\",\"message_id\":\"m-1\",\"reason\":\"user_requested\"}",
                write(new StopData("s-1", "m-1", "user_requested")));
        // Go: noReason => {"session_id":"s-1","message_id":"m-1"}
        assertEquals("{\"session_id\":\"s-1\",\"message_id\":\"m-1\"}",
                write(new StopData("s-1", "m-1", "")));
    }

    // ===== ToolApprovalRequiredData =====

    @Test
    void toolApprovalRequiredDataZero() {
        // Go: {"pending_id":"","tenant_id":0,"session_id":"","assistant_message_id":"","service_id":"",
        //      "service_name":"","mcp_tool_name":"","registered_tool_name":"","description":"",
        //      "timeout_seconds":0,"requested_at":0,"tool_call_id":""}
        assertEquals("{\"pending_id\":\"\",\"tenant_id\":0,\"session_id\":\"\","
                + "\"assistant_message_id\":\"\",\"service_id\":\"\",\"service_name\":\"\","
                + "\"mcp_tool_name\":\"\",\"registered_tool_name\":\"\",\"description\":\"\","
                + "\"timeout_seconds\":0,\"requested_at\":0,\"tool_call_id\":\"\"}",
                write(new ToolApprovalRequiredData()));
    }

    @Test
    void toolApprovalRequiredDataFull() {
        // Go: {"pending_id":"p-1","tenant_id":42,"session_id":"s-1","assistant_message_id":"am-1",
        //      "service_id":"svc-1","service_name":"github","mcp_tool_name":"create_issue",
        //      "registered_tool_name":"mcp__github__create_issue","description":"Create an issue",
        //      "args":{"title":"bug \u003ca\u003e\u0026"},"args_json":"{\"title\":\"bug\"}",
        //      "timeout_seconds":300,"requested_at":1726700000,"tool_call_id":"call_9","request_id":"r-1"}
        ToolApprovalRequiredData d = new ToolApprovalRequiredData(
                "p-1", 42, "s-1", "am-1", "svc-1", "github", "create_issue",
                "mcp__github__create_issue", "Create an issue",
                mapOf("title", "bug <a>&"), "{\"title\":\"bug\"}", 300, 1726700000, "call_9", "r-1");
        assertEquals("{\"pending_id\":\"p-1\",\"tenant_id\":42,\"session_id\":\"s-1\","
                + "\"assistant_message_id\":\"am-1\",\"service_id\":\"svc-1\",\"service_name\":\"github\","
                + "\"mcp_tool_name\":\"create_issue\",\"registered_tool_name\":\"mcp__github__create_issue\","
                + "\"description\":\"Create an issue\",\"args\":{\"title\":\"bug \\u003ca\\u003e\\u0026\"},"
                + "\"args_json\":\"{\\\"title\\\":\\\"bug\\\"}\",\"timeout_seconds\":300,"
                + "\"requested_at\":1726700000,\"tool_call_id\":\"call_9\",\"request_id\":\"r-1\"}",
                write(d));
    }

    @Test
    void toolApprovalRequiredDataMinimal() {
        // Go: minimal => pending_id 之外全零值，args/args_json/request_id 省略
        ToolApprovalRequiredData d = new ToolApprovalRequiredData("p-2", 0, "", "", "", "", "",
                "", "", null, "", 0, 0, "", "");
        assertEquals("{\"pending_id\":\"p-2\",\"tenant_id\":0,\"session_id\":\"\","
                + "\"assistant_message_id\":\"\",\"service_id\":\"\",\"service_name\":\"\","
                + "\"mcp_tool_name\":\"\",\"registered_tool_name\":\"\",\"description\":\"\","
                + "\"timeout_seconds\":0,\"requested_at\":0,\"tool_call_id\":\"\"}", write(d));
    }

    // ===== ToolApprovalResolvedData =====

    @Test
    void toolApprovalResolvedDataZero() {
        // Go: {"pending_id":"","approved":false}
        assertEquals("{\"pending_id\":\"\",\"approved\":false}", write(new ToolApprovalResolvedData()));
    }

    @Test
    void toolApprovalResolvedDataVariants() {
        // Go approved => {"pending_id":"p-1","approved":true,"reason":"user_approved"}
        assertEquals("{\"pending_id\":\"p-1\",\"approved\":true,\"reason\":\"user_approved\"}",
                write(new ToolApprovalResolvedData("p-1", true, "user_approved", false, false)));
        // Go timedOut => {"pending_id":"p-1","approved":false,"timed_out":true}
        assertEquals("{\"pending_id\":\"p-1\",\"approved\":false,\"timed_out\":true}",
                write(new ToolApprovalResolvedData("p-1", false, "", true, false)));
        // Go canceled => {"pending_id":"p-1","approved":false,"canceled":true}
        assertEquals("{\"pending_id\":\"p-1\",\"approved\":false,\"canceled\":true}",
                write(new ToolApprovalResolvedData("p-1", false, "", false, true)));
    }

    // ===== MCPOAuthRequiredData =====

    @Test
    void mcpOAuthRequiredDataZero() {
        // Go: {"pending_id":"","tenant_id":0,"session_id":"","assistant_message_id":"","service_id":"",
        //      "service_name":"","mcp_tool_name":"","timeout_seconds":0,"requested_at":0,"tool_call_id":""}
        assertEquals("{\"pending_id\":\"\",\"tenant_id\":0,\"session_id\":\"\","
                + "\"assistant_message_id\":\"\",\"service_id\":\"\",\"service_name\":\"\","
                + "\"mcp_tool_name\":\"\",\"timeout_seconds\":0,\"requested_at\":0,\"tool_call_id\":\"\"}",
                write(new MCPOAuthRequiredData()));
    }

    @Test
    void mcpOAuthRequiredDataFull() {
        // Go: {"pending_id":"po-1","tenant_id":7,"session_id":"s-2","assistant_message_id":"am-2",
        //      "service_id":"svc-2","service_name":"notion","mcp_tool_name":"search",
        //      "timeout_seconds":120,"requested_at":1726700001,"tool_call_id":"call_10","request_id":"r-2"}
        MCPOAuthRequiredData d = new MCPOAuthRequiredData("po-1", 7, "s-2", "am-2", "svc-2",
                "notion", "search", 120, 1726700001, "call_10", "r-2");
        assertEquals("{\"pending_id\":\"po-1\",\"tenant_id\":7,\"session_id\":\"s-2\","
                + "\"assistant_message_id\":\"am-2\",\"service_id\":\"svc-2\",\"service_name\":\"notion\","
                + "\"mcp_tool_name\":\"search\",\"timeout_seconds\":120,\"requested_at\":1726700001,"
                + "\"tool_call_id\":\"call_10\",\"request_id\":\"r-2\"}", write(d));
    }

    @Test
    void mcpOAuthRequiredDataNoticeOnly() {
        // Go: noticeOnly => timeout_seconds 0 也输出（无 omitempty），request_id 省略
        MCPOAuthRequiredData d = new MCPOAuthRequiredData("", 7, "s", "", "svc", "n", "t", 0, 0, "", "");
        assertEquals("{\"pending_id\":\"\",\"tenant_id\":7,\"session_id\":\"s\","
                + "\"assistant_message_id\":\"\",\"service_id\":\"svc\",\"service_name\":\"n\","
                + "\"mcp_tool_name\":\"t\",\"timeout_seconds\":0,\"requested_at\":0,\"tool_call_id\":\"\"}",
                write(d));
    }

    // ===== MCPOAuthResolvedData =====

    @Test
    void mcpOAuthResolvedDataZero() {
        // Go: {"pending_id":"","service_id":"","authorized":false}
        assertEquals("{\"pending_id\":\"\",\"service_id\":\"\",\"authorized\":false}",
                write(new MCPOAuthResolvedData()));
    }

    @Test
    void mcpOAuthResolvedDataVariants() {
        // Go authorized => {"pending_id":"po-1","service_id":"svc-2","authorized":true,"reason":"authorized"}
        assertEquals("{\"pending_id\":\"po-1\",\"service_id\":\"svc-2\",\"authorized\":true,"
                + "\"reason\":\"authorized\"}",
                write(new MCPOAuthResolvedData("po-1", "svc-2", true, "authorized", false, false)));
        // Go canceled => {"pending_id":"po-1","service_id":"svc-2","authorized":false,"canceled":true}
        assertEquals("{\"pending_id\":\"po-1\",\"service_id\":\"svc-2\",\"authorized\":false,\"canceled\":true}",
                write(new MCPOAuthResolvedData("po-1", "svc-2", false, "", false, true)));
    }


    @Test
    void readToleratesUnknownFields() {
        // Go 的 json.Unmarshal 默认忽略未知字段——旧事件多出的字段不能让读路径炸掉
        AgentThoughtData d = EventJson.read(
                "{\"content\":\"c\",\"iteration\":1,\"done\":true,\"future_field\":42}",
                AgentThoughtData.class);
        assertEquals("c", d.getContent());
        assertEquals(1, d.getIteration());
        assertTrue(d.isDone());
    }

    // ===== EventType 常量与 Go 字面量逐字对齐（抽样锚点 + 总数） =====

    @Test
    void eventTypeConstantsMatchGoLiterals() {
        assertEquals("query.received", EventType.EVENT_QUERY_RECEIVED);
        assertEquals("thought", EventType.EVENT_AGENT_THOUGHT);
        assertEquals("tool_call", EventType.EVENT_AGENT_TOOL_CALL);
        assertEquals("tool_result", EventType.EVENT_AGENT_TOOL_RESULT);
        assertEquals("reflection", EventType.EVENT_AGENT_REFLECTION);
        assertEquals("references", EventType.EVENT_AGENT_REFERENCES);
        assertEquals("final_answer", EventType.EVENT_AGENT_FINAL_ANSWER);
        assertEquals("tool_approval_required", EventType.EVENT_TOOL_APPROVAL_REQUIRED);
        assertEquals("tool_approval_resolved", EventType.EVENT_TOOL_APPROVAL_RESOLVED);
        assertEquals("mcp_oauth_required", EventType.EVENT_MCP_OAUTH_REQUIRED);
        assertEquals("mcp_oauth_resolved", EventType.EVENT_MCP_OAUTH_RESOLVED);
        assertEquals("error", EventType.EVENT_ERROR);
        assertEquals("memory_recalled", EventType.EVENT_MEMORY_RECALLED);
        assertEquals("context_compacted", EventType.EVENT_CONTEXT_COMPACTED);
        assertEquals("user_message_injected", EventType.EVENT_USER_MESSAGE_INJECTED);
        assertEquals("session_title", EventType.EVENT_SESSION_TITLE);
        assertEquals("stop", EventType.EVENT_STOP);
        assertEquals("agent.complete", EventType.EVENT_AGENT_COMPLETE);
        assertEquals("retrieval.start", EventType.EVENT_RETRIEVAL_START);
    }
}

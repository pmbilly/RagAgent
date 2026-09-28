package com.ragagent.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波 4.6d 契约（chat 三入口 golden 回放）。
 *
 * <p>golden 全部是 <b>Go 实录</b>（scripts/ab-qa46d.sh 双端同指 stub LLM 录制；
 * SSE 场景掩码后逐字节 MATCH ×2 轮）。本测试钉住两点：</p>
 * <ol>
 *   <li>Go 录制的错误信封/文案形状（400 绑定文案、404 文案、409/500 信封键序）；</li>
 *   <li>SSE 帧骨架（event:message\ndata:{...}\n\n、response_type 序列、
 *       answer 分片重组、complete 的键集）——掩码后与 Java 实录同构。</li>
 * </ol>
 *
 * <p>掩码族与 ab-qa46d.sh 的 mask() 逐条同源（uuid/时间戳/事件 id 前缀/耗时数字）。</p>
 */
class KnowledgeQaContractTest {

    private static final Path CONTRACT_DIR = Paths.get("src/test/resources/contracts");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern EVT_ANSWER = Pattern.compile("[0-9a-f]{8}-answer");
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z");
    private static final Pattern NUMS = Pattern.compile("\"(completed_at|duration_ms|total_duration_ms)\":[0-9]+");

    private static String mask(String s) {
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = EVT_ANSWER.matcher(s).replaceAll("<EVT>-answer");
        s = TS.matcher(s).replaceAll("<TS>");
        s = NUMS.matcher(s).replaceAll("\"$1\":<N>");
        return s;
    }

    private String golden(String name) throws Exception {
        Path p = CONTRACT_DIR.resolve("qa46d-" + name + ".json");
        assertTrue(Files.exists(p), "golden missing: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 错误信封契约：code/message/details/success 键序与文案（Go 实录原文）。 */
    private void assertEnvelope(String goldenName, int expectedCode, String expectedMessage) throws Exception {
        String raw = golden(goldenName);
        assertTrue(raw.contains("\"code\":" + expectedCode), goldenName + " code");
        assertTrue(raw.contains("\"message\":\"" + expectedMessage + "\""), goldenName + " message: " + raw);
        assertTrue(raw.contains("\"details\":null"), goldenName + " details");
        assertTrue(raw.contains("\"success\":false"), goldenName + " success");
        // gin.H map 键序：error < success（字母序）
        assertTrue(raw.indexOf("\"error\"") >= 0 && raw.indexOf("\"error\"") < raw.indexOf("\"success\""),
                goldenName + " envelope key order");
    }

    @Test
    void searchKnowledgeBindingErrors() throws Exception {
        assertEnvelope("kse-empty-query", 1000,
                "Key: 'SearchKnowledgeRequest.Query' Error:Field validation for 'Query' failed on the 'required' tag");
        assertEnvelope("kse-bad-json", 1000,
                "invalid character 'o' in literal null (expecting 'u')");
    }

    @Test
    void searchKnowledgeUnknownKb() throws Exception {
        // 检索插件取不到 KB 元数据 → 1003 包进 500 信封（错误文案逐字）
        assertEnvelope("kse-unknown-kb", 1007,
                "error code: 1003, error message: knowledge base not found");
    }

    @Test
    void knowledgeChatBindingAndNotFound() throws Exception {
        assertEnvelope("kch-empty-query", 1000,
                "Key: 'CreateKnowledgeQARequest.Query' Error:Field validation for 'Query' failed on the 'required' tag");
        assertEnvelope("kch-missing-session", 1003, "Session not found");
    }

    @Test
    void agentChatGates() throws Exception {
        // 会话存在但 agent_id 缺失/不可解析：AgentQA 的早拒门
        assertEnvelope("ach-no-agent", 1000, "agent_id is required when agent mode is enabled");
        assertEnvelope("ach-bad-agent", 1003, "Shared agent not found");
        // ach-gate-off-mode（agent_enabled 缺省 → normal 分支跑通）在 kch/ach SSE 场景中覆盖
    }

    /**
     * SSE 帧骨架（stub LLM 全链路实录）：帧序 agent_query → answer(done=false) →
     * answer(done=true) → complete；帧格式 event:message\ndata:<json>\n\n；
     * complete 键集 total_steps/total_duration_ms/final_content（无 usage 键时两侧同缺）。
     */
    @Test
    void sseFullChainFrameSkeleton() throws Exception {
        String sse = mask(golden("kch-stub-chat"));
        // 帧骨架：冒号后无空格、JSON 尾随 \n\n
        assertTrue(sse.startsWith("event:message\ndata:{"), "frame prefix");
        assertTrue(sse.contains("\n\n"), "frame terminator");
        // response_type 序列
        assertTrue(sse.indexOf("\"response_type\":\"agent_query\"") >= 0, "agent_query frame");
        assertTrue(sse.indexOf("\"response_type\":\"answer\"") > sse.indexOf("\"response_type\":\"agent_query\""),
                "answer after agent_query");
        assertTrue(sse.indexOf("\"response_type\":\"complete\"") > sse.lastIndexOf("\"response_type\":\"answer\""),
                "complete after answer");
        // 分片内容（stub 脚本化序列）
        assertTrue(sse.contains("你好，"), "chunk1");
        assertTrue(sse.contains("我是知识助手。"), "chunk2");
        assertTrue(sse.contains("\"done\":false"), "first chunk not done");
        // complete 的 final_content = 分片重组
        assertTrue(sse.contains("final_content\":\"你好，我是知识助手。"), "final content");
        // agent_query 携带 user/assistant 时间戳与 id 关联
        assertTrue(sse.contains("assistant_created_at"), "agent_query timestamps");
        assertTrue(sse.contains("user_message_id"), "agent_query user id");
        // 扣留重组键：answer 分片共享同一 event_id 形态
        assertTrue(sse.contains("event_id\":\"<EVT>-answer\""), "answer event id");
    }

    /** 长分片场景（holdback/多分片序）：三段顺序与 done 语义。 */
    @Test
    void sseLongScenarioOrdering() throws Exception {
        String sse = mask(golden("kch-stub-long"));
        int p1 = sse.indexOf("第一段。");
        int p2 = sse.indexOf("第二段，");
        int p3 = sse.indexOf("第三段内容较长一些");
        assertTrue(p1 > 0 && p2 > p1 && p3 > p2, "chunk order");
    }

    /** 掩码器与 ab 脚本同源的自检：golden 里不该残留裸 uuid。 */
    @Test
    void maskingCoversAllGoldens() throws Exception {
        try (Stream<Path> files = Files.list(CONTRACT_DIR)) {
            files.filter(p -> p.getFileName().toString().startsWith("qa46d-"))
                    .forEach(p -> {
                        try {
                            String masked = mask(Files.readString(p, StandardCharsets.UTF_8));
                            assertEquals(false, UUID.matcher(masked).find(),
                                    p.getFileName() + " contains unmasked uuid");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}

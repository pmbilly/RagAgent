package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import com.ragagent.embedding.Embedder;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型装饰器测试（对照 Go internal/models 下各族的 langfuse_wrapper.go）：
 * chat/embedding/rerank 的 generation 载荷、流式累积与用量估算；未启用时 wrap 原样返回。
 */
class LangfuseModelDecoratorsTest {

    private final List<RecordedSpan> exported = new CopyOnWriteArrayList<>();
    private DefaultLangfuseManager manager;

    @BeforeEach
    void setUp() {
        exported.clear();
        LangfuseContext.clear();
        manager = new DefaultLangfuseManager(config(), exported::addAll);
        LangfuseRegistry.installForTest(manager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        LangfuseContext.clear();
    }

    private static LangfuseConfig config() {
        return new LangfuseConfig(true, "http://localhost:1", "pk", "sk", 1, 100, 2048,
                1000, "", "", 1.0, false);
    }

    /** 按名字取已导出的 span（无活跃 trace 时每次调用还会带一条 autoTrace 根）。 */
    private RecordedSpan byName(String name) {
        return exported.stream().filter(s -> name.equals(s.name)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no span named " + name + " (exported=" + exported.size() + ")"));
    }

    /** autoTrace 根条数（对照 Go StartGeneration 的"无 trace 则开浅根"）。 */
    private long traceRoots() {
        return exported.stream()
                .filter(s -> LangfuseAttributes.OBS_TYPE_TRACE
                        .equals(s.attributes.get(LangfuseAttributes.ATTR_OBS_TYPE)))
                .count();
    }

    @Test
    @DisplayName("chat：generation 载荷 = 消息/参数/输出/用量（键序照 Go，JSON 排序）")
    void chatGenerationPayload() {
        ChatResponse response = new ChatResponse();
        response.setContent("hi there");
        response.setFinishReason("stop");
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(5);
        usage.setTotalTokens(15);
        response.setUsage(usage);

        LlmChatClient wrapped = LangfuseChatClient.wrap(new FakeChatClient(response));
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent("hello");
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.7);
        options.setMaxCompletionTokens(128);

        ChatResponse got = wrapped.chat(List.of(message), options);
        assertEquals("hi there", got.getContent());

        // 无活跃 trace → generation 先导出、autoTrace 根随后（Go 的 autoTrace 语义）
        assertEquals(2, exported.size());
        assertEquals(1, traceRoots());
        RecordedSpan gen = exported.get(0);
        assertEquals("chat.completion", gen.name);
        assertEquals("generation", gen.attributes.get(LangfuseAttributes.ATTR_OBS_TYPE));
        assertEquals("qwen-test", gen.attributes.get(LangfuseAttributes.ATTR_OBS_MODEL));
        assertEquals("[{\"content\":\"hello\",\"role\":\"user\"}]",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_INPUT));
        assertEquals("{\"content\":\"hi there\",\"finish_reason\":\"stop\",\"tool_calls\":null}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertEquals("{\"input\":10,\"output\":5,\"total\":15,\"unit\":\"TOKENS\"}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_USAGE_DETAILS));
        assertEquals("{\"max_completion_tokens\":128,\"temperature\":0.7}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_MODEL_PARAMS));
        assertEquals("{\"call_purpose\":\"\",\"has_tools\":false,\"model_id\":\"m-1\","
                        + "\"prompt_prefix_fingerprint\":\"\",\"streaming\":false}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));
    }

    @Test
    @DisplayName("chat 流式：累积 content/reasoning、TTFT、用量，流结束收 generation")
    void chatStreamAccumulates() throws Exception {
        StreamResponse thinking = streamChunk(ResponseType.THINKING, "let me think");
        StreamResponse answer1 = streamChunk(ResponseType.ANSWER, "hello");
        StreamResponse answer2 = streamChunk(ResponseType.ANSWER, " world");
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(7);
        usage.setCompletionTokens(3);
        usage.setTotalTokens(10);
        StreamResponse last = streamChunk(ResponseType.ANSWER, "");
        last.setUsage(usage);
        last.setFinishReason("stop");
        last.setDone(true);

        FakeChatClient inner = new FakeChatClient(null);
        inner.streamChunks = List.of(thinking, answer1, answer2, last);

        BlockingQueue<StreamResponse> wrapped = LangfuseChatClient.wrap(inner)
                .chatStream(List.of(), new ChatOptions());
        List<StreamResponse> consumed = new ArrayList<>();
        while (true) {
            StreamResponse resp = wrapped.take();
            consumed.add(resp);
            if (resp.isDone()) {
                break;
            }
        }
        assertEquals(4, consumed.size());

        for (int i = 0; i < 200 && exported.size() < 2; i++) {
            Thread.sleep(10);
        }
        assertEquals(2, exported.size());
        assertEquals(1, traceRoots());
        RecordedSpan gen = byName("chat.completion.stream");
        assertEquals("{\"content\":\"hello world\",\"finish_reason\":\"stop\","
                        + "\"reasoning_content\":\"let me think\",\"tool_calls\":null}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertEquals("{\"input\":7,\"output\":3,\"total\":10,\"unit\":\"TOKENS\"}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_USAGE_DETAILS));
        assertTrue(gen.attributes.containsKey(LangfuseAttributes.ATTR_OBS_COMPLETION_START));
    }

    @Test
    @DisplayName("embedding：单条与批量 generation（含用量估算与预览）")
    void embeddingGenerations() {
        Embedder wrapped = LangfuseEmbedder.wrap(new FakeEmbedder());

        float[] vector = wrapped.embed("abcd");
        assertEquals(4, vector.length);
        assertEquals(2, exported.size());
        assertEquals(1, traceRoots());
        RecordedSpan single = byName("embedding.embed");
        assertEquals("{\"dimensions\":4,\"vector_preview\":[0.5,1.5,2.5]}",
                single.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        // 用量估算：码点数/4 + 1 = 2
        assertEquals("{\"input\":2,\"total\":2,\"unit\":\"TOKENS\"}",
                single.attributes.get(LangfuseAttributes.ATTR_OBS_USAGE_DETAILS));
        assertEquals("{\"dimensions\":4,\"model_id\":\"emb-1\"}",
                single.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));

        wrapped.batchEmbed(List.of("abcd", "ef"));
        assertEquals(4, exported.size());
        assertEquals(2, traceRoots());
        RecordedSpan batch = byName("embedding.batch_embed");
        assertEquals("{\"count\":2,\"preview\":[\"abcd\",\"ef\"]}",
                batch.attributes.get(LangfuseAttributes.ATTR_OBS_INPUT));
        assertEquals("{\"batch_size\":2,\"dimensions\":4,\"model_id\":\"emb-1\"}",
                batch.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));
    }

    @Test
    @DisplayName("rerank：generation 载荷 = 输入预览/得分统计/截断计数")
    void rerankGeneration() {
        Reranker wrapped = LangfuseReranker.wrap(new FakeReranker());
        List<RankResult> results = wrapped.rerank("q", List.of("doc-a", "doc-b"));
        assertEquals(2, results.size());

        assertEquals(2, exported.size());
        assertEquals(1, traceRoots());
        RecordedSpan gen = byName("rerank");
        assertEquals("{\"document_count\":2,\"documents_preview\":["
                        + "{\"index\":0,\"length\":5,\"preview\":\"doc-a\"},"
                        + "{\"index\":1,\"length\":5,\"preview\":\"doc-b\"}],\"query\":\"q\"}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_INPUT));
        assertEquals("{\"avg_doc_chars\":5,\"model_id\":\"rr-1\",\"num_queries\":1,\"total_chars\":11}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));
        assertEquals("{\"results\":[{\"index\":0,\"model_score\":0.9,\"preview\":\"doc-a\",\"rank\":1},"
                        + "{\"index\":1,\"model_score\":0.5,\"preview\":\"doc-b\",\"rank\":2}],"
                        + "\"score_stats\":{\"avg\":0.7,\"max\":0.9,\"min\":0.5},\"total_count\":2}",
                gen.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
    }

    @Test
    @DisplayName("未启用：wrap 原样返回（零成本）")
    void disabledWrapReturnsSameInstance() {
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        LlmChatClient chat = new FakeChatClient(new ChatResponse());
        Embedder embedder = new FakeEmbedder();
        Reranker reranker = new FakeReranker();
        assertSame(chat, LangfuseChatClient.wrap(chat));
        assertSame(embedder, LangfuseEmbedder.wrap(embedder));
        assertSame(reranker, LangfuseReranker.wrap(reranker));
        assertTrue(exported.isEmpty());
    }

    // ── 假客户端 ──

    private static StreamResponse streamChunk(ResponseType type, String content) {
        StreamResponse resp = new StreamResponse();
        resp.setResponseType(type);
        resp.setContent(content);
        return resp;
    }

    private static final class FakeChatClient implements LlmChatClient {
        private final ChatResponse response;
        private List<StreamResponse> streamChunks = List.of();

        FakeChatClient(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            return response;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages,
                                                        ChatOptions options) {
            BlockingQueue<StreamResponse> queue = new LinkedBlockingQueue<>(streamChunks);
            return queue;
        }

        @Override
        public String getModelName() {
            return "qwen-test";
        }

        @Override
        public String getModelId() {
            return "m-1";
        }
    }

    private static final class FakeEmbedder implements Embedder {
        @Override
        public float[] embed(String text) {
            return new float[] {0.5f, 1.5f, 2.5f, 3.5f};
        }

        @Override
        public List<float[]> batchEmbed(List<String> texts) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[] {0.5f, 1.5f, 2.5f, 3.5f});
            }
            return out;
        }

        @Override
        public String getModelName() {
            return "emb-test";
        }

        @Override
        public int getDimensions() {
            return 4;
        }

        @Override
        public String getModelID() {
            return "emb-1";
        }
    }

    private static final class FakeReranker implements Reranker {
        @Override
        public List<RankResult> rerank(String query, List<String> documents) {
            List<RankResult> out = new ArrayList<>();
            RankResult first = new RankResult();
            first.setIndex(0);
            first.setRelevanceScore(0.9);
            out.add(first);
            RankResult second = new RankResult();
            second.setIndex(1);
            second.setRelevanceScore(0.5);
            out.add(second);
            return out;
        }

        @Override
        public String getModelName() {
            return "rr-test";
        }

        @Override
        public String getModelID() {
            return "rr-1";
        }
    }
}

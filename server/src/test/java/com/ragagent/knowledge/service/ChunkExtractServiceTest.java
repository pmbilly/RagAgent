package com.ragagent.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.retrieval.graph.RetrieveGraphRepository;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.common.wiki.WikiFinalizePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.common.graph.GraphData;
import com.ragagent.common.graph.GraphNode;
import com.ragagent.common.graph.NameSpace;

/**
 * 分块图抽取服务测试（对照 Go {@code ChunkExtractService.Handle} 的四条出口：
 * supersede 跳过 / 知识中止跳过 / 配置闸门跳过 / 正常抽取写图）——依赖用假件，
 * LLM 用返回空图 JSON 的假客户端（编排验证不需要真模型）。
 */
class ChunkExtractServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 录制式图仓储（写/删记录 + 检索恒空）。 */
    static final class RecordingGraphRepo implements RetrieveGraphRepository {
        final List<NameSpace> addedNamespaces = new ArrayList<>();
        final List<List<GraphData>> addedGraphs = new ArrayList<>();

        @Override
        public void addGraph(NameSpace namespace, List<GraphData> graphs) {
            addedNamespaces.add(namespace);
            addedGraphs.add(graphs);
        }

        @Override
        public void delGraph(List<NameSpace> namespaces) {
        }

        @Override
        public GraphData searchNode(NameSpace namespace, List<String> nodes) {
            return null;
        }
    }

    /** 假聊天客户端：恒返回空图 JSON（{@code []} → parseGraph 得空图）。 */
    static LlmChatClient emptyGraphClient() {
        return new LlmChatClient() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
                ChatResponse r = new ChatResponse();
                r.setContent("[]");
                return r;
            }

            @Override
            public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages,
                                                            ChatOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public String getModelId() {
                return "fake-1";
            }
        };
    }

    private ModelRuntimeFactory modelRuntimeFactory;
    private ChunkRepository chunkRepository;
    private KnowledgeMapper knowledgeMapper;
    private KnowledgeBaseMapper kbMapper;
    private SpanTracker spanTracker;
    private WikiFinalizePort finalizer;
    private RecordingGraphRepo graphRepo;
    private ChunkExtractService service;

    private Chunk chunk;

    @BeforeEach
    void setUp() throws Exception {
        modelRuntimeFactory = mock(ModelRuntimeFactory.class);
        chunkRepository = mock(ChunkRepository.class);
        knowledgeMapper = mock(KnowledgeMapper.class);
        kbMapper = mock(KnowledgeBaseMapper.class);
        spanTracker = mock(SpanTracker.class);
        finalizer = mock(WikiFinalizePort.class);
        graphRepo = new RecordingGraphRepo();

        chunk = new Chunk();
        chunk.setId("ck-1");
        chunk.setContent("张三在腾讯工作");
        chunk.setKnowledgeBaseId("kb-1");
        chunk.setKnowledgeId("kn-1");

        when(modelRuntimeFactory.getChatModel("m-1")).thenReturn(emptyGraphClient());
        when(chunkRepository.getChunkById(7L, "ck-1")).thenReturn(chunk);

        service = new ChunkExtractService(modelRuntimeFactory, chunkRepository, knowledgeMapper,
                kbMapper, spanTracker, graphRepo, finalizer);
    }

    private KnowledgeBase kbWithExtractConfig(String json) throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setExtractConfig(JSON.readTree(json));
        return kb;
    }

    private static ExtractChunkPayload payload() {
        return new ExtractChunkPayload(7L, "ck-1", "m-1", "kn-1", 3, 0);
    }

    @Test
    @DisplayName("supersede：在开 span/取数之前返回，且不减计数、不写图")
    void supersededSkipsEverything() {
        when(spanTracker.isAttemptSuperseded("kn-1", 3)).thenReturn(true);

        service.handle(payload());

        assertTrue(graphRepo.addedGraphs.isEmpty());
        verify(finalizer, never()).finalizeWikiSubtask(anyString());
        verify(chunkRepository, never()).getChunkById(anyLong(), anyString());
        verify(spanTracker, never()).lookupStage(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("知识已中止（deleting/cancelled）：记 skipped 返回，仍释放槽位")
    void abortedKnowledgeSkipsButDrains() {
        Knowledge k = new Knowledge();
        k.setId("kn-1");
        k.setParseStatus(Knowledge.PARSE_CANCELLED);
        when(knowledgeMapper.selectById("kn-1")).thenReturn(k);

        service.handle(payload());

        assertTrue(graphRepo.addedGraphs.isEmpty());
        verify(chunkRepository, never()).getChunkById(anyLong(), anyString());
        verify(finalizer).finalizeWikiSubtask("kn-1");
    }

    @Test
    @DisplayName("抽取配置未启用：读块后闸门拦下，仍释放槽位")
    void extractDisabledSkips() throws Exception {
        when(kbMapper.selectById("kb-1")).thenReturn(kbWithExtractConfig("{\"enabled\":false}"));

        service.handle(payload());

        assertTrue(graphRepo.addedGraphs.isEmpty());
        verify(modelRuntimeFactory, never()).getChatModel(anyString());
        verify(finalizer).finalizeWikiSubtask("kn-1");
    }

    @Test
    @DisplayName("正常路径：抽取（空图）→ AddGraph 命名空间 = 块所属 KB/知识 → 释放槽位")
    void happyPathWritesGraph() throws Exception {
        when(kbMapper.selectById("kb-1")).thenReturn(kbWithExtractConfig(
                "{\"enabled\":true,\"text\":\"示例\",\"nodes\":[{\"name\":\"张三\"}],"
                + "\"relations\":[{\"node1\":\"张三\",\"node2\":\"腾讯\",\"type\":\"works_at\"}],"
                + "\"tags\":[\"人物\"],\"customInstructions\":\"只抽人物\"}"));

        service.handle(payload());

        assertEquals(1, graphRepo.addedGraphs.size());
        assertEquals("kb-1", graphRepo.addedNamespaces.get(0).knowledgeBase());
        assertEquals("kn-1", graphRepo.addedNamespaces.get(0).knowledge());
        verify(finalizer).finalizeWikiSubtask("kn-1");
    }

    @Test
    @DisplayName("模板组装：原描述 + 自定义指令（graph_extraction 标签）+ Tags + 单条 Example")
    void templateAssembly() throws Exception {
        var cfg = JSON.readTree("{\"enabled\":true,\"text\":\"示例文本\",\"tags\":[\"人物\",\"公司\"],"
                + "\"nodes\":[{\"name\":\"张三\"},{\"name\":\"腾讯\"}],"
                + "\"relations\":[{\"node1\":\"张三\",\"node2\":\"腾讯\",\"type\":\"works_at\"}],"
                + "\"customInstructions\":\"只抽人物与公司\"}");

        var template = service.buildTemplate(cfg);

        assertNotNull(template.getDescription());
        assertTrue(template.getDescription().contains("只抽人物与公司"));
        assertEquals(List.of("人物", "公司"), template.getTags());
        assertEquals(1, template.getExamples().size());
        var example = template.getExamples().get(0);
        assertEquals("示例文本", example.getText());
        assertEquals(List.of("张三", "腾讯"),
                example.getNode().stream().map(GraphNode::getName).toList());
        assertEquals("works_at", example.getRelation().get(0).type());
        assertFalse(example.getNode().isEmpty());
    }

    @Test
    @DisplayName("载荷 JSON 入口：坏载荷抛出（队列层据此走重试/死信）")
    void handleJsonRejectsGarbage() {
        // 空载荷可解析（全默认值）；坏 JSON 抛 IllegalArgumentException
        try {
            service.handleJson("{not-json");
            org.junit.jupiter.api.Assertions.fail("应当抛出");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unmarshal extract chunk payload"));
        }
    }

    @Test
    @DisplayName("Map 记录：空图时 nodes_added/relations_added 均为 0（span 输出契约）")
    void graphOutAccounting() throws Exception {
        when(kbMapper.selectById("kb-1")).thenReturn(kbWithExtractConfig("{\"enabled\":true}"));
        // 捕获 endSpan 的输出（子 span 存在时才会调用 → 让 lookupStage 返回非空）
        SpanTracker.SpanHandle parent = new SpanTracker.SpanHandle();
        when(spanTracker.lookupStage("kn-1", 3, "postprocess")).thenReturn(parent);
        List<Map<String, Object>> outputs = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            outputs.add(inv.getArgument(1));
            return null;
        }).when(spanTracker).endSpan(any(), any());
        when(spanTracker.beginSubSpan(any(), anyString(), anyString(), any()))
                .thenReturn(new SpanTracker.SpanHandle());

        service.handle(payload());

        assertEquals(1, outputs.size());
        assertEquals(0, outputs.get(0).get("nodes_added"));
        assertEquals(0, outputs.get(0).get("relations_added"));
        assertEquals("张三在腾讯工作", outputs.get(0).get("chunk_preview"));
    }
}

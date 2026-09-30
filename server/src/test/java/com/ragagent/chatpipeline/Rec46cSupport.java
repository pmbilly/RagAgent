package com.ragagent.chatpipeline;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import com.ragagent.event.Event;
import com.ragagent.event.EventHandler;
import com.ragagent.event.EventJson;
import com.ragagent.event.EventBusInterface;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.memory.service.MemoryRecall;
import com.ragagent.memory.service.MemoryRetrievalContext;
import com.ragagent.rerank.RankResult;
import com.ragagent.retrieval.support.SearchTextUtil;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.Message;
import com.ragagent.common.graph.GraphData;
import com.ragagent.common.graph.NameSpace;
import com.ragagent.retrieval.graph.RetrieveGraphRepository;
import com.ragagent.common.pipeline.SearchParams;

/**
 * 4.6c 实录回放的替身与掩码工具（对照 Go 探针的 zz_ 前缀同款）。
 * 期望值全部是 {@link GoRecording46C} 的 Go 实录常量；两侧掩码后逐字节可比。
 *
 * <p>掩码约定（Go 探针 zzMask 与本类 {@link #mask} 同款）：完整 uuid → MASKED-UUID；
 * 事件 id 的 8-hex 前缀 → xxxxxxxx-；"duration_ms":N 连键带值删除；日期 → DATE；
 * 英文星期名 → WEEKDAY；127.0.0.1:PORT。</p>
 */
final class Rec46cSupport {

    private Rec46cSupport() {}

    private static final Pattern UUID_FULL = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SHORT_ID = Pattern.compile("[0-9a-f]{8}-(thinking|answer|error)");
    private static final Pattern UUID_PREFIX = Pattern.compile("^[0-9a-f]{8}-");
    private static final Pattern DUR_JSON = Pattern.compile(",?\"duration_ms\":\\d+");
    private static final Pattern WEEK = Pattern.compile(
            "(Sunday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday)");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PORT = Pattern.compile("127\\.0\\.0\\.1:\\d+");

    static String mask(String s) {
        s = UUID_FULL.matcher(s).replaceAll("MASKED-UUID");
        s = SHORT_ID.matcher(s).replaceAll("xxxxxxxx-$1");
        s = UUID_PREFIX.matcher(s).replaceFirst("xxxxxxxx-");
        s = DUR_JSON.matcher(s).replaceAll("");
        s = WEEK.matcher(s).replaceAll("WEEKDAY");
        s = DATE.matcher(s).replaceAll("DATE");
        s = PORT.matcher(s).replaceAll("127.0.0.1:PORT");
        return s;
    }

    /** Go json.Marshal 的 Java 等价（map 排序 + HTML 转义 + Go 浮点）。 */
    static String json(Object v) {
        return EventJson.write(v);
    }

    /** 解析 GoRecording46C 常量并断言相等（带上下文 diff）。 */
    static void assertRec(String group, String key, String actual) {
        String expected = GoRecording46C.constant(group, key);
        org.assertj.core.api.Assertions.assertThat(com.ragagent.support.ContractJson.deep(actual))
                .as("recording %s/%s", group, key)
                .isEqualTo(com.ragagent.support.ContractJson.deep(expected));
    }

    /** 常量查找（GoRecording46C 生成的常量名按组/键）。 */
    static String constant(String group, String key) {
        return GoRecording46C.constant(group, key);
    }

    /** 断言"无错误"（对照 Go 的 errString null）。 */
    static Map<String, Object> errOf(PluginError err) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (err == null) {
            return null;
        }
        m.put("description", err.description);
        m.put("error_type", err.errorType);
        if (err.err != null) {
            m.put("err", err.err.getMessage());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 形状函数（对照 Go 探针的 *Shape 函数；map 键序无关——Go 侧 marshal 排序）
    // ------------------------------------------------------------------

    static List<Map<String, Object>> searchResultsShape(List<SearchResult> rs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (SearchResult r : rs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("content", r.getContent());
            m.put("knowledge_id", r.getKnowledgeId());
            m.put("score", r.getScore());
            m.put("match_type", r.getMatchType());
            m.put("chunk_type", r.getChunkType());
            m.put("kb_id", r.getKnowledgeBaseId() == null ? "" : r.getKnowledgeBaseId());
            m.put("knowledge_title", r.getKnowledgeTitle());
            m.put("knowledge_filename", r.getKnowledgeFilename());
            m.put("knowledge_source", r.getKnowledgeSource());
            m.put("metadata", r.getMetadata());
            m.put("sub_chunk_id", r.getSubChunkId());
            m.put("start_at", r.getStartAt());
            m.put("end_at", r.getEndAt());
            m.put("seq", r.getSeq());
            m.put("chunk_index", r.getChunkIndex());
            m.put("parent_chunk_id", r.getParentChunkId());
            m.put("image_info", r.getImageInfo());
            m.put("content_revision", r.getContentRevision());
            m.put("content_rewritten", r.isContentRewritten());
            out.add(m);
        }
        return out;
    }

    static List<String> resultIDs(List<SearchResult> rs) {
        List<String> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (SearchResult r : rs) {
            out.add(r.getId());
        }
        return out;
    }

    static List<Double> scores(List<SearchResult> rs) {
        List<Double> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (SearchResult r : rs) {
            out.add(r.getScore());
        }
        return out;
    }

    static List<Map<String, Object>> historyShape(java.util.List<History> h) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (h == null) {
            return out;
        }
        for (History hh : h) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("query", hh.getQuery());
            m.put("answer", hh.getAnswer());
            m.put("create_at", hh.getCreateAt() == null ? null : rfc3339Nano(hh.getCreateAt()));
            m.put("refs", hh.getKnowledgeReferences() == null ? 0 : hh.getKnowledgeReferences().size());
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> msgsJSON(List<ChatMessage> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : msgs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole());
            row.put("content", m.getContent());
            row.put("images", m.getImages());
            out.add(row);
        }
        return out;
    }

    static List<Map<String, Object>> usedShape(List<com.ragagent.session.domain.UsedMemory> u) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (u == null) {
            return out;
        }
        for (var m : u) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("kind", m.getKind());
            row.put("content", m.getContent());
            out.add(row);
        }
        return out;
    }

    static Object graphShape(GraphData g) {
        if (g == null) {
            return null;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (g.node() != null) {
            for (var n : g.node()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", n.getName());
                m.put("chunks", n.getChunks());
                nodes.add(m);
            }
        }
        List<Map<String, Object>> rels = new ArrayList<>();
        if (g.relation() != null) {
            for (var r : g.relation()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("n1", r.node1());
                m.put("n2", r.node2());
                m.put("type", r.type());
                rels.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("relations", rels);
        return out;
    }

    /** Go time.RFC3339Nano：纳秒尾零修剪（整秒 → 无小数部分）。 */
    static String rfc3339Nano(Instant t) {
        java.time.ZonedDateTime z = t.atZone(java.time.ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(t));
        int nanos = z.getNano();
        if (nanos > 0) {
            String frac = String.format("%09d", nanos);
            frac = frac.replaceAll("0+$", "");
            sb.append('.').append(frac);
        }
        sb.append('Z');
        return sb.toString();
    }

    /**
     * Go chat.ChatOptions 的 JSON 形态（struct 序：temperature…thinking；
     * tools/tool_choice/parallel_tool_calls/format 带 omitempty；值用包装类型过
     * GoDoubleSerializer）。
     */
    @com.fasterxml.jackson.annotation.JsonPropertyOrder({
            "temperature", "top_p", "seed", "max_tokens", "max_completion_tokens",
            "frequency_penalty", "presence_penalty", "thinking",
    })
    static final class GoOpts {
        public Double temperature;
        @com.fasterxml.jackson.annotation.JsonProperty("top_p")
        public Double topP;
        public Integer seed;
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        public Integer maxTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("max_completion_tokens")
        public Integer maxCompletionTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("frequency_penalty")
        public Double frequencyPenalty;
        @com.fasterxml.jackson.annotation.JsonProperty("presence_penalty")
        public Double presencePenalty;
        public Boolean thinking;

        static GoOpts of(ChatOptions o) {
            GoOpts g = new GoOpts();
            g.temperature = o.getTemperature();
            g.topP = o.getTopP();
            g.seed = o.getSeed();
            g.maxTokens = o.getMaxTokens();
            g.maxCompletionTokens = o.getMaxCompletionTokens();
            g.frequencyPenalty = o.getFrequencyPenalty();
            g.presencePenalty = o.getPresencePenalty();
            g.thinking = o.getThinking();
            return g;
        }
    }

    /** 消息行（struct 序 role/content/images；images omitempty）。 */
    @com.fasterxml.jackson.annotation.JsonPropertyOrder({"role", "content", "images"})
    static final class MsgRow {
        public String role;
        public String content;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public List<String> images;

        MsgRow(ChatMessage m) {
            this.role = m.getRole();
            this.content = m.getContent();
            this.images = m.getImages();
        }
    }

    // ------------------------------------------------------------------
    // 替身（对照 zz_ 前缀 fakes）
    // ------------------------------------------------------------------

    /** 对照 zzBus：记录事件的 bus。 */
    static final class RecBus implements EventBusInterface {
        final List<Event> events = new ArrayList<>();
        private final Object lock = new Object();

        @Override
        public void on(String eventType, EventHandler handler) {
        }

        @Override
        public void emit(Event evt) {
            synchronized (lock) {
                events.add(evt);
            }
        }

        List<Event> all() {
            synchronized (lock) {
                return new ArrayList<>(events);
            }
        }

        /** 事件序列 JSON（对照 zzEventsJSON 的 Go struct 键序 id/type/session_id/data）。 */
        String eventsJson() {
            var arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (Event e : all()) {
                var row = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                row.put("id", mask(e.getId()));
                row.put("type", e.getType());
                row.put("session_id", e.getSessionId());
                row.set("data", EventJson.mapper().valueToTree(e.getData()));
                arr.add(row);
            }
            return mask(json(arr));
        }
    }

    /** 对照 zzPlugin：记录调用序。 */
    static final class TestPlugin implements Plugin {
        private final String name;
        private final String[] events;
        private final PluginError fail;
        private final List<String> calls;

        TestPlugin(String name, String[] events, PluginError fail, List<String> calls) {
            this.name = name;
            this.events = events;
            this.fail = fail;
            this.calls = calls;
        }

        @Override
        public PluginError onEvent(String eventType, ChatManage chatManage, Chain next) {
            calls.add(name + ">in");
            if (fail != null) {
                return fail;
            }
            PluginError err = next.next();
            calls.add(name + ">out");
            return err;
        }

        @Override
        public String[] activationEvents() {
            return events;
        }
    }

    /** 对照 zzChat：脚本化聊天替身。 */
    static final class StubChat implements LlmChatClient {
        final List<String> responses;
        final List<RuntimeException> errs;
        final List<String> calls = new ArrayList<>();
        final List<StreamResponse> streamScript;
        private final Object lock = new Object();

        StubChat(List<String> responses, List<RuntimeException> errs) {
            this.responses = responses;
            this.errs = errs;
            this.streamScript = null;
        }

        StubChat(List<StreamResponse> streamScript) {
            this.streamScript = streamScript;
            this.responses = null;
            this.errs = null;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            int idx;
            synchronized (lock) {
                idx = calls.size();
                calls.add(callJson(messages, options));
            }
            if (errs != null && idx < errs.size() && errs.get(idx) != null) {
                throw errs.get(idx);
            }
            String resp = "";
            if (responses != null && !responses.isEmpty()) {
                resp = idx < responses.size() ? responses.get(idx)
                        : responses.get(responses.size() - 1);
            }
            ChatResponse r = new ChatResponse();
            r.setContent(resp);
            r.setFinishReason("stop");
            return r;
        }

        @Override
        public LinkedBlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages,
                                                              ChatOptions options) {
            LinkedBlockingQueue<StreamResponse> ch = new LinkedBlockingQueue<>();
            synchronized (lock) {
                calls.add(callJson(messages, options));
            }
            if (streamScript != null) {
                ch.addAll(streamScript);
            }
            return ch;
        }

        @Override
        public String getModelName() {
            return "zz-model";
        }

        @Override
        public String getModelId() {
            return "zz-1";
        }

        static String callJson(List<ChatMessage> messages, ChatOptions opts) {
            List<MsgRow> msgs = new ArrayList<>();
            for (ChatMessage m : messages) {
                msgs.add(new MsgRow(m));
            }
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("messages", msgs);
            call.put("opts", GoOpts.of(opts));
            return mask(json(call));
        }
    }


    /** 对照 zzModelService（chat + rerank 双查询）。 */
    static final class StubModelService implements PipelinePorts.ModelService {
        final Map<String, LlmChatClient> chatModels = new LinkedHashMap<>();
        final Map<String, RuntimeException> chatErr = new LinkedHashMap<>();
        final Map<String, Reranker> rerankModels = new LinkedHashMap<>();
        final Map<String, RuntimeException> rerankErr = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public LlmChatClient getChatModel(String modelId) {
            calls.add("get_chat_model:" + modelId);
            if (chatErr.containsKey(modelId)) {
                throw chatErr.get(modelId);
            }
            LlmChatClient c = chatModels.get(modelId);
            if (c != null) {
                return c;
            }
            throw new RuntimeException("chat model " + modelId + " not found");
        }

        @Override
        public Reranker getRerankModel(String modelId) {
            calls.add("get_rerank_model:" + modelId);
            RuntimeException err = rerankErr.get(modelId);
            if (err != null) {
                throw err;
            }
            Reranker r = rerankModels.get(modelId);
            if (r != null) {
                return r;
            }
            throw new RuntimeException("rerank model " + modelId + " not found");
        }
    }

    /** 对照 zzReranker。 */
    static final class StubReranker implements Reranker {
        final List<List<RankResult>> resp = new ArrayList<>();
        final List<RuntimeException> errs = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        StubReranker add(List<RankResult> results, RuntimeException err) {
            resp.add(results);
            errs.add(err);
            return this;
        }

        @Override
        public List<RankResult> rerank(String query, List<String> documents) {
            int idx = calls.size();
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("query", query);
            call.put("passages", documents);
            calls.add(json(call));
            if (idx < errs.size() && errs.get(idx) != null) {
                throw errs.get(idx);
            }
            if (idx < resp.size()) {
                return resp.get(idx);
            }
            return null;
        }

        @Override
        public String getModelName() {
            return "zz-rerank";
        }

        @Override
        public String getModelID() {
            return "rr-zz";
        }
    }

    static List<RankResult> rankResults(double... pairs) {
        List<RankResult> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            RankResult rr = new RankResult();
            rr.setIndex((int) pairs[i]);
            rr.setRelevanceScore(pairs[i + 1]);
            out.add(rr);
        }
        return out;
    }

    /** 对照 zzTenantService。 */
    static final class StubTenantService implements PipelinePorts.TenantService {}

    /** 对照 zzKnowledgeService。 */
    static final class StubKnowledgeService implements PipelinePorts.KnowledgeService {
        @Override
        public com.ragagent.knowledge.domain.Knowledge getKnowledgeById(String id) {
            com.ragagent.knowledge.domain.Knowledge k = new com.ragagent.knowledge.domain.Knowledge();
            k.setId(id);
            k.setTitle("标题-" + id);
            k.setFileName(id + ".csv");
            k.setDescription("描述");
            return k;
        }

        @Override
        public List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId,
                                                                               List<String> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatchWithSharedAccess(
                long tenantId, List<String> ids) {
            throw new UnsupportedOperationException();
        }
    }

    /** 对照 zzChunkService。 */
    static final class StubChunkService implements PipelinePorts.ChunkService {}

    /** 对照 zzKnowledgeRepo。 */
    static final class StubKnowledgeRepo implements PipelinePorts.KnowledgeRepository {
        final Map<String, com.ragagent.knowledge.domain.Knowledge> items = new LinkedHashMap<>();

        @Override
        public List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId,
                                                                               List<String> ids) {
            List<com.ragagent.knowledge.domain.Knowledge> out = new ArrayList<>();
            for (String id : ids) {
                var k = items.get(id);
                if (k != null) {
                    out.add(k);
                }
            }
            return out;
        }
    }

    /** 对照 zzGraphRepo。 */
    static final class StubGraphRepo implements RetrieveGraphRepository {
        final Map<String, GraphData> byKB = new LinkedHashMap<>();
        boolean err;

        /** D 批扩展：写/删记录（管线只读，测试可断言写删被调用）。 */
        final List<GraphData> added = new java.util.ArrayList<>();
        final List<NameSpace> deleted = new java.util.ArrayList<>();

        @Override
        public void addGraph(NameSpace namespace, List<GraphData> graphs) {
            if (err) {
                throw new RuntimeException("graph unavailable");
            }
            if (graphs != null) {
                added.addAll(graphs);
            }
        }

        @Override
        public void delGraph(List<NameSpace> namespaces) {
            if (err) {
                throw new RuntimeException("graph unavailable");
            }
            if (namespaces != null) {
                deleted.addAll(namespaces);
            }
        }

        @Override
        public GraphData searchNode(NameSpace namespace, List<String> nodes) {
            if (err) {
                throw new RuntimeException("graph unavailable");
            }
            if (namespace.knowledge() != null && !namespace.knowledge().isEmpty()) {
                var d = byKB.get(namespace.knowledge());
                return d != null ? d : new GraphData(new ArrayList<>(), new ArrayList<>());
            }
            var d = byKB.get(namespace.knowledgeBase());
            return d != null ? d : new GraphData(new ArrayList<>(), new ArrayList<>());
        }
    }

    /** 对照 zzWebSearchService。 */
    static final class StubWebSearch implements PipelinePorts.WebSearch {
        final List<com.ragagent.retrieval.domain.WebSearchResult> results = new ArrayList<>();
        RuntimeException err;

        @Override
        public List<com.ragagent.retrieval.domain.WebSearchResult> search(String providerId,
                                                                          com.ragagent.websearch.service.WebSearchService.WebSearchConfig config,
                                                                          String query) {
            if (err != null) {
                throw err;
            }
            return results;
        }
    }

    /** 对照 zzChunkRepo。 */
    static final class StubChunkRepo implements PipelinePorts.ChunkRepository {
        final Map<String, com.ragagent.knowledge.domain.Chunk> chunks = new LinkedHashMap<>();
        boolean listErr;
        final List<String> calls = new ArrayList<>();

        @Override
        public List<com.ragagent.knowledge.domain.Chunk> listChunksById(long tenantId, List<String> ids) {
            calls.add(String.join(",", ids));
            if (listErr) {
                throw new RuntimeException("db unavailable");
            }
            List<com.ragagent.knowledge.domain.Chunk> out = new ArrayList<>();
            for (String id : ids) {
                var chunk = chunks.get(id);
                if (chunk != null) {
                    out.add(chunk);
                }
            }
            return out;
        }

        @Override
        public List<com.ragagent.knowledge.domain.Chunk> listChunksByParentIds(long tenantId,
                                                                               List<String> parentIds) {
            List<com.ragagent.knowledge.domain.Chunk> out = new ArrayList<>();
            for (var c : chunks.values()) {
                if (c != null && parentIds.contains(c.getParentChunkId())) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    /** 对照 zzKBService。 */
    static final class StubKBService implements PipelinePorts.KnowledgeBaseService {
        final Map<String, com.ragagent.knowledge.domain.KnowledgeBase> kbs = new LinkedHashMap<>();
        RuntimeException kbErr;
        final Map<String, float[]> embed = new LinkedHashMap<>();
        final Map<String, RuntimeException> embedErr = new LinkedHashMap<>();
        final Map<String, List<SearchResult>> hybrid = new LinkedHashMap<>();
        final Map<String, RuntimeException> hybridErr = new LinkedHashMap<>();
        /** 命中这些 id 时 hybridSearch 返回 null（模拟无可用检索管道）。 */
        final java.util.Set<String> hybridNull = new java.util.HashSet<>();
        final List<SearchParams> hybridParams = new ArrayList<>();
        final List<String> hybridIDs = new ArrayList<>();
        Map<String, String> modelKeys;
        int byIDOnlyCalls;
        private final Object lock = new Object();

        @Override
        public com.ragagent.knowledge.domain.KnowledgeBase getKnowledgeBaseByIdOnly(String id) {
            byIDOnlyCalls++;
            if (kbErr != null) {
                throw kbErr;
            }
            var kb = kbs.get(id);
            if (kb == null) {
                throw new RuntimeException("kb " + id + " not found");
            }
            return kb;
        }

        @Override
        public List<com.ragagent.knowledge.domain.KnowledgeBase> getKnowledgeBasesByIdsOnly(List<String> ids) {
            if (kbErr != null) {
                throw kbErr;
            }
            List<com.ragagent.knowledge.domain.KnowledgeBase> out = new ArrayList<>();
            for (String id : ids) {
                var kb = kbs.get(id);
                if (kb != null) {
                    out.add(kb);
                }
            }
            return out;
        }

        @Override
        public Map<String, String> resolveEmbeddingModelKeys(
                List<com.ragagent.knowledge.domain.KnowledgeBase> kbs) {
            if (modelKeys != null) {
                return modelKeys;
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (var kb : kbs) {
                if (kb != null) {
                    out.put(kb.getId(), "model|" + kb.getEmbeddingModelId());
                }
            }
            return out;
        }

        @Override
        public float[] getQueryEmbedding(String kbId, String queryText) {
            if (embedErr.containsKey(kbId)) {
                throw embedErr.get(kbId);
            }
            float[] emb = embed.get(kbId);
            return emb != null ? emb : new float[] {0.1f, 0.2f};
        }

        @Override
        public List<SearchResult> hybridSearch(String id, SearchParams params) {
            synchronized (lock) {
                hybridParams.add(params);
                hybridIDs.add(id);
            }
            if (hybridErr.containsKey(id)) {
                throw hybridErr.get(id);
            }
            if (hybridNull.contains(id)) {
                // 对照生产 HybridSearchService「无可用检索管道 → 返回 null（Go nil 切片）」
                return null;
            }
            List<SearchResult> res = hybrid.get(id);
            return res != null ? res : new ArrayList<>();
        }

        String paramsJson() {
            synchronized (lock) {
                return mask(json(hybridParams));
            }
        }
    }

    static com.ragagent.knowledge.domain.KnowledgeBase kb(String id, String type, boolean vec,
                                                          boolean kw, boolean wiki) {
        com.ragagent.knowledge.domain.KnowledgeBase k = new com.ragagent.knowledge.domain.KnowledgeBase();
        k.setId(id);
        k.setType(type);
        k.setEmbeddingModelId("embedding-" + id);
        var strategy = new com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy();
        strategy.setVectorEnabled(vec);
        strategy.setKeywordEnabled(kw);
        strategy.setWikiEnabled(wiki);
        k.setIndexingStrategy(strategy);
        return k;
    }

    static SearchResult sr(String id, String content, String knowledgeId, double score) {
        SearchResult r = new SearchResult();
        r.setId(id);
        r.setContent(content);
        r.setKnowledgeId(knowledgeId);
        r.setScore(score);
        return r;
    }

    /** 对照 zzMessageService。 */
    static class StubMessageService implements PipelinePorts.MessageService {
        List<Message> messages = new ArrayList<>();
        RuntimeException getErr;
        RuntimeException recentErr;

        @Override
        public Message getMessage(String sessionId, String messageId) {
            if (getErr != null) {
                throw getErr;
            }
            for (Message msg : messages) {
                if (msg.getId().equals(messageId) && msg.getSessionId().equals(sessionId)) {
                    return msg;
                }
            }
            throw new RuntimeException("message " + messageId + " not found");
        }

        @Override
        public List<Message> getRecentMessagesBySession(String sessionId, int limit) {
            if (recentErr != null) {
                throw recentErr;
            }
            return messages;
        }

        @Override
        public void updateMessageImages(String sessionId, String messageId,
                                        List<com.ragagent.session.domain.MessageImage> images) {
        }

        @Override
        public void updateMessageRenderedContent(String sessionId, String messageId,
                                                 String renderedContent) {
        }
    }

    static Message message(String requestId, String role, String content, Instant createdAt) {
        Message m = new Message();
        m.setRequestId(requestId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(java.time.OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
        return m;
    }

    /** 对照 zzMemoryService。 */
    static final class StubMemoryService implements PipelinePorts.MemoryService {
        MemoryRecall recallValue = MemoryRecall.EMPTY;
        MemoryRetrievalContext ctx = MemoryRetrievalContext.EMPTY;
        Map<String, Integer> affinity = new LinkedHashMap<>();

        @Override
        public MemoryRecall recall(String query) {
            return recallValue;
        }

        @Override
        public MemoryRetrievalContext retrievalContextFor() {
            return ctx;
        }

        @Override
        public Map<String, Integer> documentAffinity(List<String> knowledgeIds) {
            return affinity;
        }
    }

    /** StreamResponse 快捷构造。 */
    static StreamResponse streamResponse(ResponseType type, String content, boolean done) {
        return StreamResponse.of(type, content, done);
    }
    // ------------------------------------------------------------------
    // 分词接缝的实录注入（jieba 真值来自 Go 探针同语料；键 = 整句或 Han 连段）
    // ------------------------------------------------------------------

    static final java.util.Map<String, List<String>> SEGMENTS = new LinkedHashMap<>();

    static {
        List<Object[]> pairs = List.of(new Object[][] {
                {"知识库检索配置", new String[] {"知识", "知识库", "检索", "配置"}},
                {"什么是知识库检索", new String[] {"什么", "是", "知识", "知识库", "检索"}},
                {"如何配置检索参数", new String[] {"如何", "配置", "检索", "参数"}},
                {"请告诉我", new String[] {"请", "告诉"}},
                {"向量检索", new String[] {"向量", "检索"}},
                {"和", new String[] {"和"}},
                {"关键词检索", new String[] {"关键", "关键词", "检索"}},
                {"的区别", new String[] {"的", "区别"}},
                {"帮我查一下", new String[] {"帮", "我查", "一下"}},
                {"检索增强生成", new String[] {"检索", "增强", "生成"}},
                {"什么是检索增强生成", new String[] {"什么", "检索", "增强", "生成"}},
                {"向量检索和关键词检索", new String[] {"向量", "检索", "和", "关键", "关键词", "检索"}},
                {"混合一行", new String[] {"混合", "一行"}},
                {"中文的句子", new String[] {"中文", "的", "句子"}},
                {"知识库检索怎么配置", new String[] {"知识", "知识库", "检索", "怎么", "配置"}},
                {"知识库", new String[] {"知识", "知识库"}},
                {"检索", new String[] {"检索"}},
                {"配置", new String[] {"配置"}},
                {"教程", new String[] {"教程"}},
                {"和中文的句子", new String[] {"和", "中文", "的", "句子"}},
                {"企业知识库", new String[] {"企业", "知识", "知识库"}},
                {"检索方式", new String[] {"检索", "方式"}},
                // TokenizeSimple 以整句为入参（小写化后查表）——merge_history 语料的整句键
                {"企业知识库 检索方式", new String[] {"企业", "知识", "知识库", "检索", "方式"}},
                {"weknora 是一个企业知识库检索系统，支持多种检索方式与重排序能力",
                        new String[] {"weknora", "一个", "企业", "知识", "知识库", "检索", "检索系统", "系统",
                                "支持", "多种", "方式", "排序", "能力"}},
                {"完全无关的历史内容，讲的是买菜做饭和天气",
                        new String[] {"完全", "无关", "历史", "内容", "买菜", "做饭", "天气"}},
                {"知识库检索系统的混合检索能力介绍，包含向量与关键词",
                        new String[] {"知识", "知识库", "检索", "检索系统", "系统", "混合", "能力", "介绍",
                                "包含", "向量", "关键", "关键词"}},
                {"知识库检索系统的混合检索能力介绍，包含向量与关键词两种方式",
                        new String[] {"知识", "知识库", "检索", "检索系统", "系统", "混合", "能力", "介绍",
                                "包含", "向量", "关键", "关键词", "两种", "方式"}},
                {"是一个企业知识库检索系统", new String[] {"一个", "企业", "知识", "知识库", "检索", "系统", "检索系统"}},
                {"支持多种检索方式与重排序能力", new String[] {"支持", "多种", "检索", "方式", "与", "重", "排序", "能力"}},
                {"完全无关的历史内容", new String[] {"完全", "无关", "的", "历史", "内容"}},
                {"讲的是买菜做饭和天气", new String[] {"讲", "的", "是", "买菜", "做饭", "和", "天气"}},
                {"企业知识库的检索方式包括向量与关键词混合",
                        new String[] {"企业", "知识", "知识库", "的", "检索", "方式", "包括", "向量", "与", "关键", "关键词", "混合"}},
                {"重排查询", new String[] {"重排", "查询"}},
                {"第一段候选内容", new String[] {"第一", "第一段", "段", "候选", "内容"}},
                {"语义相关", new String[] {"语义", "相关"}},
                {"第二段候选内容", new String[] {"第二", "第二段", "二段", "段", "候选", "内容"}},
                {"语义稍弱", new String[] {"语义", "稍弱"}},
                {"知识库检索系统的混合检索能力介绍",
                        new String[] {"知识", "知识库", "检索", "检索系统", "系统", "的", "混合", "能力", "介绍"}},
                {"包含向量与关键词", new String[] {"包含", "向量", "与", "关键", "关键词"}},
                {"包含向量与关键词两种方式", new String[] {"包含", "向量", "与", "关键", "关键词", "两种", "方式"}},
                {"完全不同的另一段文字", new String[] {"完全", "不同", "的", "另一段", "一段", "文字"}},
                {"说的是数据导入与文档解析的流程说明",
                        new String[] {"说", "的", "是", "数据", "导入", "与", "文档", "解析", "流程", "说明"}},
        });
        for (Object[] pair : pairs) {
            SEGMENTS.put((String) pair[0], List.of((String[]) pair[1]));
        }
    }

    /** 注入实录分词器（jieba 真值表；未命中回落二字滑窗）。 */
    static void installSegmenter() {
        SearchTextUtil.Segmenter jieba = text -> {
            List<String> fixed = SEGMENTS.get(text);
            return fixed != null ? fixed : new SearchTextUtil.UnavailableSegmenter().cutForSearch(text);
        };
        QueryTokenizer.setSegmenter(jieba);
        SearchTextUtil.setSegmenter(jieba);
    }

    static void restoreSegmenter() {
        QueryTokenizer.setSegmenter(new SearchTextUtil.UnavailableSegmenter());
        SearchTextUtil.setSegmenter(new SearchTextUtil.UnavailableSegmenter());
    }
}

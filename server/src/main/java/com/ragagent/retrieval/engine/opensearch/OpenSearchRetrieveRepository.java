package com.ragagent.retrieval.engine.opensearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * OpenSearch k-NN 检索引擎仓库。HTTP 自持（java.net.http），与 ES v7/v8 驱动同一姿态。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li><b>生命周期</b>：构造期验证连通 + 版本 + 每节点 k-NN 插件，
 *       <b>不建索引</b>——Save/Retrieve 首次见到某嵌入维度时惰性建（ensureReady，
 *       逐维索引命名）；瞬时错误（TRANSPORT/CIRCUIT_BREAKER）不持久化、下次重试，
 *       永久错误持久化到 initErr。注意：瞬时失败时 initErr 未写、当次调用也拿 nil，
 *       后续操作以 INDEX_NOT_FOUND 显形（注释与代码的历史分叉见 known-issues）</li>
 *   <li><b>索引命名</b>：base = ResolveIndexName(OPENSEARCH_INDEX, "weknora")；
 *       DB-store 折叠 storeID 前 12 hex（48 位碰撞空间），env-store（前缀 id）映射为
 *       ""；storeId 非空须 ≥16 字符；sanitizeIndexName 正则
 *       {@code ^[a-z0-9][a-z0-9_-]{0,254}$} + 显式拒 {@code *?,\n\r\t/\\}；
 *       别名 {@code <base>_<dim>} → 实体索引 {@code <alias>_v1}；keyword 专用索引
 *       {@code <base>_keywords}（mutex+flag，可重试）</li>
 *   <li><b>版本探针</b>：distribution != "opensearch" 拒；1.x 拒；2.0~2.3 拒
 *       （pre-Lucene-HNSW-GA）；2.4~2.10 WARN 收；2.11+/3.x 收</li>
 *   <li><b>k-NN 插件探针</b>：_cat/plugins 按节点分组，每个节点都要有
 *       opensearch-knn；空结果/缺节点 → CONFIG_INVALID（缺节点列表渲染为
 *       {@code [a b c]} 形态）</li>
 *   <li><b>错误分类</b>：401/403→AUTH；
 *       429+knn_circuit_breaker_exception→CIRCUIT_BREAKER；其余→TRANSPORT；
 *       reason 文案不进异常 message（只进 DEBUG）</li>
 *   <li><b>索引映射</b>：settings（knn=true/shards/replicas/refresh_interval=1s/
 *       knn.algo_param.ef_search）+ properties（embedding knn_vector hnsw cosinesimil、
 *       *_id 全 keyword——无 ES 的 .keyword 后缀探测、source_type integer、
 *       is_enabled/is_recommended boolean）；alias 存在即短路；跨进程竞争
 *       resource_already_exists → 结构指纹比对（dimension/m/ef_construction/engine/
 *       space_type），漂移 → CONFIG_INVALID "manual reindex required"；aliasPut 失败
 *       尽力删孤儿 _v1</li>
 *   <li><b>查询</b>：knn 查询（embedding.vector/k/filter 内嵌 bool.must）+
 *       min_score 直通（COSINESIMIL.scoreTranslation 已映射 (1+cos)/2 ∈ [0,1]）；
 *       BM25 match + terms 过滤；TopK 缺省 10（WARN caller bug）、cap 10000；
 *       过滤是类型化字段（无 JSON 注入面）；is_enabled=true 隐含子句</li>
 *   <li><b>写入</b>：Save 幂等（_id=chunk_id）；BatchSave 的批量上限
 *       （预估 n*(100+dim*5+1024) &gt; 10MB、n &gt; 1000 → BATCH_TOO_LARGE）、
 *       逐项错误检视（≤5 条 "[op id] type"，reason 只进 DEBUG）、混合维度 →
 *       DIMENSION_MISMATCH；三种删除走 _delete_by_query terms + refresh=true、
 *       cap 1000；缺 embedding 的文档/批路由到 keywords 索引</li>
 *   <li><b>复制</b>：批 500 分页扫源（from/size，受 max_result_window 10000 界——
 *       超大批量需 scroll 异步路径，未实现）+ 三态 SourceID 改写 + embedding 按
 *       <b>目标 SourceID</b> 键回填 + BatchSave 逐页落</li>
 *   <li><b>迁移</b>：_update_by_query 于 {@code <base>_*}（跨维）改写
 *       knowledge_base_id 并清 tag_id，painless 脚本 + params 绑定（防注入）、
 *       refresh=true，完整性校验（timed_out/version_conflicts/total==updated）</li>
 *   <li><b>批量更新</b>：按值分组（false 先 true 后 / tag 字典序、组内 id 排序——
 *       确定性）逐组 _update_by_query，常量 painless 源 + params 绑定</li>
 *   <li><b>存储估算</b>：保守下界 n*(1024+4*768+128)
 *       （按 _stats 读取的实现未落地——删除守卫 fail-closed）</li>
 *   <li><b>审计</b>：AuditSink 接口（EmitIndexCreated/EmitReindexExecuted）+
 *       no-op 缺省；生产适配器见 config.OpenSearchAuditSinkAdapter</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>自持 HTTP：TLS 走 HttpClient 缺省 + insecureSkipVerify 时的
 *       trust-all SSLContext；无 per-request 响应超时（ES 驱动同姿态：仅
 *       connectTimeout 15s）；SSRF 为构造期一次校验（ES 驱动同姿态）</li>
 *   <li>漂移/缺节点的分组遍历序：排序输出（日志确定性）</li>
 *   <li>map 序列化一律字母序（TreeMap）</li>
 * </ul>
 */
public class OpenSearchRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchRetrieveRepository.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** CopyIndices 分页批大小（受 max_result_window 10000 界）。 */
    static final int COPY_BATCH_SIZE = 500;
    /** bulk 的文档数上限与体积预估上限。 */
    static final int BULK_DOC_CAP = 1000;
    static final long BULK_BODY_CAP_BYTES = 10L * 1024 * 1024;
    /** 检索响应 16MB / bulk 响应 64MB（limitedDecode 的调用点文档）。 */
    static final long SEARCH_BODY_CAP = 16L << 20;
    static final long BULK_RESPONSE_CAP = 64L << 20;

    final HttpClient http;
    final String addr;
    final String baseIndex;
    private final String username;
    private final String password;
    final String basicAuth;
    final InternalCfg cfg;
    private volatile AuditSink sink;

    /** 逐维惰性初始化（once + initErr 语义，见类注释）。 */
    final ConcurrentHashMap<Integer, DimInit> dimInits = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, OpenSearchDriverException> initErrs =
            new ConcurrentHashMap<>();

    /** keyword 专用索引的懒初始化（mutex+flag，可重试）。 */
    final Object keywordsLock = new Object();
    boolean keywordsReady;
    OpenSearchDriverException keywordsErr;

    final OpenSearchSearchOps searchOps;
    final OpenSearchWriteOps writeOps;
    final OpenSearchAdminOps adminOps;

    /** 驱动内部缺省配置：shards=4/replicas=1/lucene/16/100/100。 */
    static final class InternalCfg {
        final int shards;
        final int replicas;
        final String knnEngine;
        final int hnswM;
        final int hnswEfConstruction;
        final int efSearch;

        InternalCfg(int shards, int replicas, String knnEngine, int hnswM,
                    int hnswEfConstruction, int efSearch) {
            this.shards = shards;
            this.replicas = replicas;
            this.knnEngine = knnEngine;
            this.hnswM = hnswM;
            this.hnswEfConstruction = hnswEfConstruction;
            this.efSearch = efSearch;
        }
    }

    /** 逐维 once 状态（transient 可重置）。 */
    static final class DimInit {
        volatile boolean done;
    }

    /** 审计事件口（驱动自有抽象，依赖箭头单向）。 */
    public interface AuditSink {
        /** 索引创建事件；dim=0 表示 keyword 专用索引。 */
        void emitIndexCreated(String alias, int dim);

        /** reindex 执行完成事件。 */
        void emitReindexExecuted(String srcAlias, String dstAlias, long docs);
    }

    /** 生产构造。 */
    public OpenSearchRetrieveRepository(String addr, String storeId, IndexConfig indexCfg,
                                        String username, String password, boolean insecureSkipVerify,
                                        SsrfGuard guard) {
        this(addr, storeId, indexCfg, username, password, insecureSkipVerify, guard, null);
    }

    /** 测试构造：自持 HttpClient。 */
    public OpenSearchRetrieveRepository(String addr, String storeId, IndexConfig indexCfg,
                                        String username, String password, boolean insecureSkipVerify,
                                        SsrfGuard guard, HttpClient httpClient) {
        String address = addr == null ? "" : addr.trim();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        if (address.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: ConnectionConfig.Addr required: opensearch: invalid index config");
        }
        if (guard != null) {
            // env-path 也过校验：本驱动构造期无条件校验（ES 驱动的 env-path 无校验，两者不同）
            try {
                guard.validateURLForSSRF(address);
            } catch (RuntimeException e) {
                throw new OpenSearchDriverException(
                        OpenSearchDriverException.Kind.CONFIG_INVALID,
                        "opensearch: address failed SSRF validation: " + e.getMessage());
            }
        }
        // storeId 非空须 ≥16 字符；env-store id 由调用方折叠为 ""
        if (storeId != null && !storeId.isEmpty() && storeId.length() < 16) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: storeID must be empty or >=16 chars, got " + storeId.length()
                            + ": opensearch: invalid index config");
        }
        String indexName = indexCfg == null ? "" : indexCfg.indexName;
        String base = EngineTypes.resolveIndexName(indexName,
                EngineTypes.ENV_OPENSEARCH_INDEX, EngineTypes.DEFAULT_OPENSEARCH_INDEX);
        if (storeId != null && !storeId.isEmpty()) {
            base = base + "_" + storeId.substring(0, 12);
        }
        this.baseIndex = sanitizeIndexName(base);
        this.cfg = buildInternalCfg(indexCfg);
        this.addr = address;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.basicAuth = this.username.isEmpty() && this.password.isEmpty() ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (this.username + ":" + this.password).getBytes(StandardCharsets.UTF_8));
        if (httpClient != null) {
            this.http = httpClient;
        } else {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            javax.net.ssl.SSLContext insecureCtx = trustAllOrNull(insecureSkipVerify);
            if (insecureCtx != null) {
                builder.sslContext(insecureCtx);
            }
            this.http = builder.build();
        }
        // 探针在构造期（注册期即显形），不建索引
        this.adminOps = new OpenSearchAdminOps(this);
        probeVersion();
        probeKnnPlugin();
        this.searchOps = new OpenSearchSearchOps(this);
        this.writeOps = new OpenSearchWriteOps(this);
        log.info("[OpenSearch] repository ready (baseIndex={}, knn_engine={}, hnsw_m={})",
                this.baseIndex, this.cfg.knnEngine, this.cfg.hnswM);
    }

    /** 审计 sink 构造后注入；null 忽略。 */
    public void withAuditSink(AuditSink auditSink) {
        if (auditSink != null) {
            this.sink = auditSink;
        }
    }

    private static javax.net.ssl.SSLContext trustAllOrNull(boolean insecureSkipVerify) {
        if (!insecureSkipVerify) {
            return null;
        }
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[] {
                    new javax.net.ssl.X509TrustManager() {
                        @Override public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("opensearch: insecure TLS context init failed", e);
        }
    }

    /**
     * 连通性探针：验证集群可达、版本受支持、
     * 每节点装了 k-NN 插件——VectorStore 服务 CreateStore 健康检查的连通性探针
     * （复用构造期的两个探针）。失败抛哨兵异常，调用方折叠成通用文案。
     */
    public static void testConnection(String addr, String username, String password,
                                      boolean insecureSkipVerify, SsrfGuard guard) {
        new OpenSearchRetrieveRepository(addr, "", null, username, password,
                insecureSkipVerify, guard);
    }

    // ── 端口面（RetrieveEngineRepository） ─────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_OPENSEARCH;
    }

    /** k-NN 单文档同时承载 ANN + BM25；keywords 索引服务无向量路径。 */
    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 存储估算：保守下界 n*(1024+4*768+128)。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return 0;
        }
        return (long) indexInfoList.size() * (1024 + 4 * 768 + 128);
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    // ── 复制 / 批量更新 ─────────────────────────────────────────────────────

    /**
     * SourceID 三态改写：本块 → 目标 chunkID；生成问题（&lt;chunk&gt;-&lt;q&gt;）→
     * 目标 chunkID-q；兜底新 UUID。
     */
    static String transformSourceId(String sourceId, String chunkId, String targetChunkId) {
        if (sourceId.equals(chunkId)) {
            return targetChunkId;
        }
        if (sourceId.startsWith(chunkId + "-")) {
            return targetChunkId + "-" + sourceId.substring(chunkId.length() + 1);
        }
        return UUID.randomUUID().toString();
    }

    // ── 迁移 ────────────────────────────────────────────────────────────────

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** TopK 缺省：≤0 → WARN + 10（caller bug）；&gt;10000 钳 10000。 */
    static int effectiveTopK(RetrieveParams p) {
        if (p.topK <= 0) {
            log.warn("[OpenSearch] Retrieve called with TopK<=0; defaulting to 10 (caller bug?)");
            return 10;
        }
        if (p.topK > 10000) {
            return 10000;
        }
        return p.topK;
    }

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }


    @Override
    public void save(IndexInfo info, Map<String, Object> params) throws Exception {
        writeOps.save(info, params);
    }

    @Override
    public void batchSave(List<IndexInfo> infos, Map<String, Object> params) throws Exception {
        writeOps.batchSave(infos, params);
    }

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteByChunkIdList(chunkIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        writeOps.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
    }

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                            int dimension, String knowledgeType) throws Exception {
        writeOps.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
    }

    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        writeOps.batchUpdateChunkEnabledStatus(chunkStatusMap);
    }

    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        writeOps.batchUpdateChunkTagID(chunkTagMap);
    }

    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        writeOps.moveKnowledgeIndices(sourceKb, targetKb, knowledgeId, chunkIds, dimension,
                knowledgeType);
    }



    /** 维度别名：{@code <base>_<dim>}。 */
    String indexAlias(int dim) {
        return baseIndex + "_" + dim;
    }

    /** keyword 专用索引名：{@code <base>_keywords}。 */
    String keywordsIndex() {
        return baseIndex + "_keywords";
    }


    /** 版本号解析：剥 pre-release 后缀、容忍缺 patch。 */
    static int[] parseMajorMinor(String num) {
        String base = num == null ? "" : num.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length < 2) {
            return new int[] {0, 0};
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }

    // ── 配置 ────────────────────────────────────────────────────────────────

    /** 只补缺省、不拒绝（范围校验是服务层职责）。 */
    static InternalCfg buildInternalCfg(IndexConfig c) {
        InternalCfg cfg = new InternalCfg(4, 1, "lucene", 16, 100, 100);
        if (c == null) {
            return cfg;
        }
        return new InternalCfg(
                c.numberOfShards > 0 ? c.numberOfShards : cfg.shards,
                c.numberOfReplicas > 0 ? c.numberOfReplicas : cfg.replicas,
                c.knnEngine != null && !c.knnEngine.isEmpty() ? c.knnEngine : cfg.knnEngine,
                c.hnswM > 0 ? c.hnswM : cfg.hnswM,
                c.hnswEfConstruction > 0 ? c.hnswEfConstruction : cfg.hnswEfConstruction,
                c.hnswEfSearch > 0 ? c.hnswEfSearch : cfg.efSearch);
    }

    // ── 索引名净化 ──────────────────────────────────────────────────────────

    static String sanitizeIndexName(String name) {
        if (name == null || name.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "empty index name: opensearch: invalid index config");
        }
        if (name.matches(".*[*?,\\n\\r\\t/\\\\].*")) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "invalid char in \"" + name + "\": opensearch: invalid index config");
        }
        if (!name.matches("^[a-z0-9][a-z0-9_-]{0,254}$")) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "name \"" + name + "\" must match ^[a-z0-9][a-z0-9_-]{0,254}$"
                            + ": opensearch: invalid index config");
        }
        return name;
    }

    // ── HTTP 自持 ───────────────────────────────────────────────────────────

    AuditSink auditSink() {
        return sink != null ? sink : new AuditSink() {
            @Override public void emitIndexCreated(String alias, int dim) {
            }

            @Override public void emitReindexExecuted(String srcAlias, String dstAlias, long docs) {
            }
        };
    }

    /**
     * 发请求并返回响应体（cap 内）。非 2xx → 按状态分类（wrapTransport 语义）；
     * 网络失败 → TRANSPORT。body 为 null 时不带实体。
     */
    String send(String method, String pathWithQuery, byte[] body, String contentType,
                        long capBytes) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(addr + pathWithQuery))
                    .timeout(Duration.ofSeconds(120));
            if (basicAuth != null) {
                builder.header("Authorization", basicAuth);
            }
            if (body != null) {
                builder.header("Content-Type", contentType);
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            if ("GET".equals(method) && "/_cat/plugins".equals(pathWithQuery)) {
                builder.header("Accept", "application/json");
            }
            HttpResponse<byte[]> resp =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                byte[] bytes = resp.body();
                if (bytes != null && bytes.length > capBytes) {
                    byte[] capped = new byte[(int) capBytes];
                    System.arraycopy(bytes, 0, capped, 0, (int) capBytes);
                    return new String(capped, StandardCharsets.UTF_8);
                }
                return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            }
            throw classifyFailure(status, resp.body());
        } catch (IOException e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        }
    }

    String send(String method, String pathWithQuery, byte[] body, String contentType) {
        return send(method, pathWithQuery, body, contentType, SEARCH_BODY_CAP);
    }

    /** 传输错误分类：401/403→AUTH；429+断路器→CIRCUIT_BREAKER；其余→TRANSPORT。 */
    static OpenSearchDriverException classifyFailure(int status, byte[] body) {
        String errorType = "";
        try {
            if (body != null && body.length > 0) {
                errorType = MAPPER.readTree(body).path("error").path("type").asText("");
            }
        } catch (Exception ignored) {
            // 非法 JSON 体——类型留空，按状态分类
        }
        if (status == 401 || status == 403) {
            return new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.AUTH,
                    "authentication failed: opensearch: authentication failed",
                    status, errorType);
        }
        if (status == 429 && "knn_circuit_breaker_exception".equals(errorType)) {
            return new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CIRCUIT_BREAKER,
                    "circuit breaker open: opensearch: knn circuit breaker open",
                    status, errorType);
        }
        return new OpenSearchDriverException(
                OpenSearchDriverException.Kind.TRANSPORT,
                "transport error: opensearch: transport error", status, errorType);
    }

    void ensureReady(int dim) {
        adminOps.ensureReady(dim);
    }

    void ensureKeywordsIndex() {
        adminOps.ensureKeywordsIndex();
    }

    private void probeVersion() {
        adminOps.probeVersion();
    }

    private void probeKnnPlugin() {
        adminOps.probeKnnPlugin();
    }



}

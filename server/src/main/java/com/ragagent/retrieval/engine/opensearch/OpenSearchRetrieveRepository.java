package com.ragagent.retrieval.engine.opensearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * OpenSearch k-NN 检索引擎仓库——对照 Go
 * {@code internal/application/repository/retriever/opensearch/} 全包（15 个非测试文件，
 * ~2380 行：repository/config/transport/errors/healthcheck/mapping/crud/query/retrieve/
 * byquery/copy/move/bulk_update/audit/stubs）。HTTP 自持（java.net.http），与
 * ES v7/v8 驱动同一姿态（Go 侧为 opensearch-go v4 SDK；wire 形状逐段对照）。
 *
 * <h2>照抄点（按 Go 文件序）</h2>
 * <ul>
 *   <li><b>生命周期</b>（repository.go）：构造期验证连通 + 版本 + 每节点 k-NN 插件，
 *       <b>不建索引</b>——Save/Retrieve 首次见到某嵌入维度时惰性建（ensureReady，
 *       逐维索引命名）；瞬时错误（TRANSPORT/CIRCUIT_BREAKER）不持久化、下次重试，
 *       永久错误持久化到 initErr（照 Go 代码——注意 Go 注释声称"caller still sees
 *       this attempt's err"与代码不符：瞬时失败时 initErr 未写、当次调用也拿 nil，
 *       后续操作以 INDEX_NOT_FOUND 显形。以代码为准，备案）</li>
 *   <li><b>索引命名</b>：base = ResolveIndexName(OPENSEARCH_INDEX, "weknora")；
 *       DB-store 折叠 storeID 前 12 hex（48 位碰撞空间），env-store（前缀 id）映射为
 *       ""；storeId 非空须 ≥16 字符；sanitizeIndexName 正则
 *       {@code ^[a-z0-9][a-z0-9_-]{0,254}$} + 显式拒 {@code *?,\n\r\t/\\}；
 *       别名 {@code <base>_<dim>} → 实体索引 {@code <alias>_v1}；keyword 专用索引
 *       {@code <base>_keywords}（mutex+flag，可重试）</li>
 *   <li><b>版本探针</b>：distribution != "opensearch" 拒；1.x 拒；2.0~2.3 拒
 *       （pre-Lucene-HNSW-GA）；2.4~2.10 WARN 收；2.11+/3.x 收</li>
 *   <li><b>k-NN 插件探针</b>：_cat/plugins 按节点分组，每个节点都要有
 *       opensearch-knn；空结果/缺节点 → CONFIG_INVALID（缺节点列表按 Go 的
 *       {@code %v} 形态 "[a b c]"）</li>
 *   <li><b>错误分类</b>（errors.go + wrapTransport）：401/403→AUTH；
 *       429+knn_circuit_breaker_exception→CIRCUIT_BREAKER；其余→TRANSPORT；
 *       reason 文案不进异常 message（只进 DEBUG）</li>
 *   <li><b>mapping.go</b>：settings（knn=true/shards/replicas/refresh_interval=1s/
 *       knn.algo_param.ef_search）+ properties（embedding knn_vector hnsw cosinesimil、
 *       *_id 全 keyword——无 ES 的 .keyword 后缀探测、source_type integer、
 *       is_enabled/is_recommended boolean）；alias 存在即短路；跨进程竞争
 *       resource_already_exists → 结构指纹比对（dimension/m/ef_construction/engine/
 *       space_type），漂移 → CONFIG_INVALID "manual reindex required"；aliasPut 失败
 *       尽力删孤儿 _v1</li>
 *   <li><b>query.go</b>：knn 查询（embedding.vector/k/filter 内嵌 bool.must）+
 *       min_score 直通（COSINESIMIL.scoreTranslation 已映射 (1+cos)/2 ∈ [0,1]）；
 *       BM25 match + terms 过滤；TopK 缺省 10（WARN caller bug）、cap 10000；
 *       过滤是类型化字段（无 JSON 注入面）；is_enabled=true 隐含子句</li>
 *   <li><b>crud.go</b>：Save 幂等（_id=chunk_id）；BatchSave 的批量上限
 *       （预估 n*(100+dim*5+1024) &gt; 10MB、n &gt; 1000 → BATCH_TOO_LARGE）、
 *       逐项错误检视（≤5 条 "[op id] type"，reason 只进 DEBUG）、混合维度 →
 *       DIMENSION_MISMATCH；三种删除走 _delete_by_query terms + refresh=true、
 *       cap 1000；缺 embedding 的文档/批次路由到 keywords 索引</li>
 *   <li><b>copy.go</b>：批 500 分页扫源（from/size，受 max_result_window 10000 界——
 *       超大批量需 scroll 异步路径，Go 同缺）+ 三态 SourceID 改写 + embedding 按
 *       <b>目标 SourceID</b> 键回填 + BatchSave 逐页落</li>
 *   <li><b>move.go</b>：_update_by_query 于 {@code <base>_*}（跨维）改写
 *       knowledge_base_id 并清 tag_id，painless 脚本 + params 绑定（防注入）、
 *       refresh=true，完整性校验（timed_out/version_conflicts/total==updated）</li>
 *   <li><b>bulk_update.go</b>：按值分组（false 先 true 后 / tag 字典序、组内 id 排序——
 *       确定性）逐组 _update_by_query，常量 painless 源 + params 绑定</li>
 *   <li><b>stubs.go</b>：EstimateStorageSize 保守下界 n*(1024+4*768+128)
 *       （真实现读 _stats 未落地，Go 同——删除守卫 fail-closed）</li>
 *   <li><b>audit.go</b>：AuditSink 接口（EmitIndexCreated/EmitReindexExecuted）+
 *       no-op 缺省；生产适配器见 config.OpenSearchAuditSinkAdapter</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>SDK → 自持 HTTP：opensearch-go v4 的 TLS 加固（TLS1.2 min、前向保密套件、
 *       连接池 32/90s）在 Java 侧由 HttpClient 缺省 + insecureSkipVerify 的
 *       trust-all SSLContext 承担；Go 的 ResponseHeaderTimeout=30s 在 Java 无
 *       per-request 等价（ES 驱动同姿态：仅 connectTimeout 15s）；
 *       Go 的 SSRFValidatingRoundTripper（逐请求重校验）→ 构造期一次校验
 *       （ES 驱动同姿态）</li>
 *   <li>漂移/缺节点的分组遍历序：Go map 随机 → 本仓排序（日志确定性备案）</li>
 *   <li>map 序列化一律字母序（TreeMap）——Go json.Marshal 对 map 的排序语义</li>
 *   <li>ensureReady 的 transient 分支照 Go <b>代码</b>（不持久化、当次不报错）；
 *       Go 注释与代码的分叉见 known-issues</li>
 * </ul>
 */
public class OpenSearchRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchRetrieveRepository.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 CopyIndices 的 copyBatchSize（受 max_result_window 10000 界，Go 同缺）。 */
    static final int COPY_BATCH_SIZE = 500;
    /** 对照 bulk 的文档数上限与体积预估上限（crud.go）。 */
    static final int BULK_DOC_CAP = 1000;
    static final long BULK_BODY_CAP_BYTES = 10L * 1024 * 1024;
    /** 检索响应 16MB / bulk 响应 64MB（limitedDecode 的调用点文档）。 */
    static final long SEARCH_BODY_CAP = 16L << 20;
    static final long BULK_RESPONSE_CAP = 64L << 20;

    private final HttpClient http;
    private final String addr;
    final String baseIndex;
    private final String username;
    private final String password;
    private final String basicAuth;
    private final InternalCfg cfg;
    private volatile AuditSink sink;

    /** 逐维惰性初始化（照 ensureReady 的 once + initErr 语义）。 */
    private final ConcurrentHashMap<Integer, DimInit> dimInits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, OpenSearchDriverException> initErrs =
            new ConcurrentHashMap<>();

    /** keyword 专用索引的懒初始化（mutex+flag，可重试——照 keywordsIndex 三件套）。 */
    final Object keywordsLock = new Object();
    private boolean keywordsReady;
    OpenSearchDriverException keywordsErr;

    final OpenSearchSearchOps searchOps;
    final OpenSearchWriteOps writeOps;

    /** 对照 internalCfg（config.go）：缺省 shards=4/replicas=1/lucene/16/100/100。 */
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

    /** 逐维 once 状态（Go sync.Once 的 Java 等价物，transient 可重置）。 */
    private static final class DimInit {
        volatile boolean done;
    }

    /** 对照 audit.go 的 AuditSink（驱动自有抽象，依赖箭头单向）。 */
    public interface AuditSink {
        /** 对照 EmitIndexCreated；dim=0 表示 keyword 专用索引。 */
        void emitIndexCreated(String alias, int dim);

        /** 对照 EmitReindexExecuted。 */
        void emitReindexExecuted(String srcAlias, String dstAlias, long docs);
    }

    /** 生产构造（照 NewOpenSearchClient + NewRepository 的合成入口）。 */
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
            // 对照 NewOpenSearchClient："opensearch: ConnectionConfig.Addr required"
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: ConnectionConfig.Addr required: opensearch: invalid index config");
        }
        if (guard != null) {
            // 对照 NewOpenSearchClient 的 ValidateURLForSSRF（env-path 也过——Go 侧该
            // 驱动的客户端构造无条件校验，与 ES 的 env-path 无校验不同）
            try {
                guard.validateURLForSSRF(address);
            } catch (RuntimeException e) {
                throw new OpenSearchDriverException(
                        OpenSearchDriverException.Kind.CONFIG_INVALID,
                        "opensearch: address failed SSRF validation: " + e.getMessage());
            }
        }
        // 对照 NewRepository：storeId 非空须 ≥16 字符；env-store id 由调用方折叠为 ""
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
        // 对照 NewRepository：探针在构造期（注册期即显形），不建索引
        probeVersion();
        probeKnnPlugin();
        this.searchOps = new OpenSearchSearchOps(this);
        this.writeOps = new OpenSearchWriteOps(this);
        log.info("[OpenSearch] repository ready (baseIndex={}, knn_engine={}, hnsw_m={})",
                this.baseIndex, this.cfg.knnEngine, this.cfg.hnswM);
    }

    /** 对照 WithAuditSink（构造后注入；null 忽略——照 Go 的 option 语义）。 */
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
     * 对照 healthcheck.go 的 {@code TestConnection}：验证集群可达、版本受支持、
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

    /** 对照 Support：k-NN 单文档同时承载 ANN + BM25；keywords 索引服务无向量路径。 */
    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    // ── 写入（crud.go） ─────────────────────────────────────────────────────

    /** 对照 Save：幂等（_id=chunk_id）；缺 embedding → keywords 索引。 */
    /** 对照 BatchSave：批量上限 + 混合维度检 + NDJSON + 逐项错误检视。 */
    /** 对照 stubs.go 的 EstimateStorageSize：保守下界 n*(1024+4*768+128)。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return 0;
        }
        return (long) indexInfoList.size() * (1024 + 4 * 768 + 128);
    }

    // ── 删除（crud.go 的三个 DeleteBy* + byquery.go） ───────────────────────

    // ── 复制 / 批量更新（copy.go + bulk_update.go） ─────────────────────────

    /** 对照 CopyIndices：批 500 分页扫源 + 三态 SourceID 改写 + 逐页 BatchSave。 */
    /**
     * 对照 transformSourceID：本块 → 目标 chunkID；生成问题（&lt;chunk&gt;-&lt;q&gt;）→
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

    /** 对照 BatchUpdateChunkEnabledStatus：false 先 true 后（确定性），ids 排序。 */
    /** 对照 BatchUpdateChunkTagID：tag 字典序、组内 id 排序。 */
    // ── 迁移（move.go） ─────────────────────────────────────────────────────

    /**
     * 对照 MoveKnowledgeIndices：跨 {@code <base>_*} 改写（含历史向量——chunk 行已删的
     * 也搬），保向量 id；完整性校验 requireComplete=true。
     */
    // ── 检索（retrieve.go + query.go） ──────────────────────────────────────

    /** 对照 Retrieve：按 RetrieverType 分派；dim 解析序 AdditionalParams &gt; embedding。 */
    /** 对照 effectiveTopK：≤0 → WARN + 10（caller bug）；&gt;10000 钳 10000。 */
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


    // ── 惰性初始化（repository.go ensureReady + mapping.go） ────────────────

    /** 对照 ensureReady；dim 界 (0, 16000]（knn_vector 硬上限）。 */
    void ensureReady(int dim) {
        if (dim <= 0 || dim > 16000) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                    "opensearch: dim " + dim + " out of range (1..16000)"
                            + ": opensearch: embedding dimension mismatch");
        }
        DimInit state = dimInits.computeIfAbsent(dim, k -> new DimInit());
        synchronized (state) {
            if (!state.done) {
                try {
                    createIndexAndAlias(dim);
                    state.done = true;
                    initErrs.remove(dim);
                } catch (OpenSearchDriverException e) {
                    if (OpenSearchDriverException.isTransient(e)) {
                        // 瞬时：不持久化，once 重置——下次调用重试（照 Go）。
                        // ⚠️ 当次调用也不报错（Go 代码 initErr 未写即返回 nil）；
                        // 后续操作以 INDEX_NOT_FOUND 显形。
                        dimInits.remove(dim, state);
                    } else {
                        // 永久：持久化，后续调用直接复现同一失败
                        state.done = true;
                        initErrs.put(dim, e);
                    }
                }
            }
        }
        OpenSearchDriverException persisted = initErrs.get(dim);
        if (persisted != null) {
            throw persisted;
        }
    }

    /** 对照 indexAlias。 */
    String indexAlias(int dim) {
        return baseIndex + "_" + dim;
    }

    /** 对照 keywordsIndex。 */
    String keywordsIndex() {
        return baseIndex + "_keywords";
    }

    /**
     * 对照 createIndexAndAlias：alias 存在短路；already-exists → 指纹比对（漂移 →
     * CONFIG_INVALID）；aliasPut 失败尽力删孤儿 _v1；实际建索引才发审计。
     */
    private void createIndexAndAlias(int dim) {
        String alias = indexAlias(dim);
        String realIndex = alias + "_v1";
        try {
            if (aliasExists(alias)) {
                return;
            }
        } catch (OpenSearchDriverException e) {
            throw new OpenSearchDriverException(e.kind(),
                    "alias check " + alias + ": " + e.getMessage(), e.httpStatus(), e.errorType());
        }
        byte[] body = buildIndexMapping(cfg, dim);
        boolean indexCreated = false;
        try {
            send("PUT", "/" + realIndex, body, "application/json", SEARCH_BODY_CAP);
            indexCreated = true;
        } catch (OpenSearchDriverException e) {
            if (OpenSearchDriverException.isAlreadyExists(e)) {
                // 跨进程竞争/残留孤儿：校验既有 mapping 的结构指纹
                verifyMappingMatches(realIndex, body);
            } else {
                throw new OpenSearchDriverException(e.kind(),
                        "create index " + realIndex + ": " + e.getMessage(),
                        e.httpStatus(), e.errorType());
            }
        }
        try {
            aliasPut(realIndex, alias);
        } catch (OpenSearchDriverException e) {
            if (indexCreated) {
                try {
                    send("DELETE", "/" + realIndex, null, "application/json", SEARCH_BODY_CAP);
                    log.info("[OpenSearch] cleaned up orphan {} after aliasPut failure", realIndex);
                } catch (Exception delErr) {
                    log.warn("[OpenSearch] orphan cleanup failed for {}: {}"
                            + " (operator must DELETE manually)", realIndex, delErr.getMessage());
                }
            }
            throw new OpenSearchDriverException(e.kind(),
                    "put alias " + alias + " → " + realIndex + ": " + e.getMessage(),
                    e.httpStatus(), e.errorType());
        }
        if (indexCreated) {
            auditSink().emitIndexCreated(alias, dim);
        }
    }

    /** 对照 ensureKeywordsIndex：mutex+flag，transient 可重试。 */
    void ensureKeywordsIndex() {
        synchronized (keywordsLock) {
            if (keywordsReady) {
                return;
            }
            if (keywordsErr != null
                    && !OpenSearchDriverException.isTransient(keywordsErr)) {
                throw keywordsErr;
            }
            String name = keywordsIndex();
            try {
                if (aliasExists(name)) {
                    keywordsReady = true;
                    keywordsErr = null;
                    return;
                }
                boolean created = false;
                try {
                    send("PUT", "/" + name, buildKeywordsMapping(cfg), "application/json");
                    created = true;
                } catch (OpenSearchDriverException e) {
                    if (!OpenSearchDriverException.isAlreadyExists(e)) {
                        keywordsErr = e;
                        throw e;
                    }
                    // resource_already_exists_exception——跨进程竞争，按成功处理
                }
                keywordsReady = true;
                keywordsErr = null;
                if (created) {
                    auditSink().emitIndexCreated(name, 0);
                }
            } catch (OpenSearchDriverException e) {
                if (keywordsErr == null) {
                    keywordsErr = e;
                }
                throw e;
            }
        }
    }

    /** 对照 buildIndexMapping（字段/键序照 Go json.Marshal：map 字母序、struct 声明序）。 */
    static byte[] buildIndexMapping(InternalCfg cfg, int dim) {
        Map<String, Object> index = new TreeMap<>();
        index.put("knn", true);
        index.put("number_of_shards", cfg.shards);
        index.put("number_of_replicas", cfg.replicas);
        index.put("refresh_interval", "1s");
        index.put("knn.algo_param.ef_search", cfg.efSearch);
        Map<String, Object> settings = new TreeMap<>();
        settings.put("index", index);
        Map<String, Object> mappings = new TreeMap<>();
        mappings.put("properties", properties(dim, cfg));
        Map<String, Object> body = new TreeMap<>();
        body.put("settings", settings);
        body.put("mappings", mappings);
        return json(body);
    }

    /** 对照 buildKeywordsMapping：同上但无 embedding 字段、settings 无 knn/ef_search。 */
    static byte[] buildKeywordsMapping(InternalCfg cfg) {
        Map<String, Object> index = new TreeMap<>();
        index.put("number_of_shards", cfg.shards);
        index.put("number_of_replicas", cfg.replicas);
        index.put("refresh_interval", "1s");
        Map<String, Object> settings = new TreeMap<>();
        settings.put("index", index);
        Map<String, Object> mappings = new TreeMap<>();
        mappings.put("properties", properties(0, cfg));
        Map<String, Object> body = new TreeMap<>();
        body.put("settings", settings);
        body.put("mappings", mappings);
        return json(body);
    }

    /**
     * properties 共享体（字母序：chunk_id/content/embedding/is_enabled/…/tag_id）。
     * {@code dim > 0} 时含 embedding（knn_vector + method 三键照 cfg）；否则省略。
     */
    private static Map<String, Object> properties(int dim, InternalCfg cfg) {
        Map<String, Object> p = new TreeMap<>();
        if (dim > 0) {
            Map<String, Object> method = new TreeMap<>();
            method.put("engine", cfg.knnEngine);
            method.put("name", "hnsw");
            Map<String, Object> params = new TreeMap<>();
            params.put("ef_construction", cfg.hnswEfConstruction);
            params.put("m", cfg.hnswM);
            method.put("parameters", params);
            method.put("space_type", "cosinesimil");
            Map<String, Object> embedding = new TreeMap<>();
            embedding.put("type", "knn_vector");
            embedding.put("dimension", dim);
            embedding.put("method", method);
            p.put("embedding", embedding);
        }
        Map<String, Object> text = new TreeMap<>();
        text.put("analyzer", "standard");
        text.put("type", "text");
        p.put("content", text);
        p.put("chunk_id", keywordType());
        p.put("knowledge_id", keywordType());
        p.put("knowledge_base_id", keywordType());
        p.put("tag_id", keywordType());
        p.put("source_id", keywordType());
        Map<String, Object> integer = new TreeMap<>();
        integer.put("type", "integer");
        p.put("source_type", integer);
        Map<String, Object> boolType = new TreeMap<>();
        boolType.put("type", "boolean");
        p.put("is_enabled", boolType);
        p.put("is_recommended", boolType);
        return p;
    }

    private static Map<String, Object> keywordType() {
        Map<String, Object> t = new TreeMap<>();
        t.put("type", "keyword");
        return t;
    }

    private static byte[] json(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("opensearch: marshal mapping", e);
        }
    }

    /** 对照 verifyMappingMatches：embedding 字段结构指纹（漂移 → CONFIG_INVALID）。 */
    private void verifyMappingMatches(String index, byte[] expectedBody) {
        String expected = extractFingerprint(expectedBody);
        String response = send("GET", "/" + index + "/_mapping", null, "application/json",
                SEARCH_BODY_CAP);
        String actual = extractFingerprintFromResponse(response, index);
        if (!expected.equals(actual)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "mapping drift on " + index + ": existing index has incompatible mapping;"
                            + " manual reindex required: mapping fingerprint mismatch: expected "
                            + expected + ", got " + actual
                            + ": opensearch: invalid index config");
        }
    }

    private static String extractFingerprint(byte[] mappingBody) {
        try {
            JsonNode props = MAPPER.readTree(mappingBody).path("mappings").path("properties");
            return fingerprint(props);
        } catch (Exception e) {
            throw new IllegalStateException("parse expected fingerprint", e);
        }
    }

    private static String extractFingerprintFromResponse(String response, String index) {
        JsonNode root;
        try {
            root = MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse mapping response: opensearch: transport error");
        }
        JsonNode props = root.path(index).path("mappings").path("properties");
        return fingerprint(props);
    }

    private static String fingerprint(JsonNode props) {
        JsonNode emb = props.path("embedding");
        if (emb.isMissingNode()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "embedding field missing or wrong type");
        }
        return "Dimension=" + emb.path("dimension").asInt()
                + ", M=" + emb.path("method").path("parameters").path("m").asInt()
                + ", EFConstruction="
                + emb.path("method").path("parameters").path("ef_construction").asInt()
                + ", Engine=" + emb.path("method").path("engine").asText()
                + ", SpaceType=" + emb.path("method").path("space_type").asText();
    }

    // ── 探针（repository.go probeVersion / probeKNNPlugin） ────────────────

    /** 对照 probeVersion。 */
    private void probeVersion() {
        String response = send("GET", "/", null, "application/json", 1L << 20);
        JsonNode version;
        try {
            version = MAPPER.readTree(response).path("version");
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: cluster info: transport error: opensearch: transport error");
        }
        String distribution = version.path("distribution").asText("");
        String number = version.path("number").asText("");
        if (!"opensearch".equals(distribution)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: unsupported distribution \"" + distribution
                            + "\": opensearch: cluster version unsupported");
        }
        int[] mm = parseMajorMinor(number);
        int maj = mm[0];
        int min = mm[1];
        if (maj == 1) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: 1.x EOL: opensearch: cluster version unsupported");
        }
        if (maj == 2 && min >= 0 && min <= 3) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: " + number + " lacks Lucene HNSW GA (need 2.4+)"
                            + ": opensearch: cluster version unsupported");
        }
        if (maj == 2 && min >= 4 && min <= 10) {
            log.warn("[OpenSearch] using pre-2.11 cluster {}; recommend 2.11+ LTS", number);
            return;
        }
        if (maj == 2 || maj == 3) {
            return;
        }
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                "opensearch: unsupported version " + number
                        + ": opensearch: cluster version unsupported");
    }

    /** 对照 parseMajorMinor：剥 pre-release 后缀、容忍缺 patch。 */
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

    /** 对照 probeKNNPlugin：每节点都要有 opensearch-knn（缺节点列表 Go %v 形态）。 */
    private void probeKnnPlugin() {
        String response = send("GET", "/_cat/plugins", null, "application/json", 1L << 20);
        JsonNode rows;
        try {
            rows = MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: cat plugins: transport error: opensearch: transport error");
        }
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String name = row.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            nodes.putIfAbsent(name, false);
            if ("opensearch-knn".equals(row.path("component").asText(""))) {
                nodes.put(name, true);
            }
        }
        if (nodes.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: _cat/plugins returned no rows: opensearch: invalid index config");
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : nodes.entrySet()) {
            if (!e.getValue()) {
                missing.add(e.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: opensearch-knn plugin missing on " + missing.size() + "/"
                            + nodes.size() + " nodes ([" + String.join(" ", missing)
                            + "]): opensearch: invalid index config");
        }
    }

    // ── 文档投影与参数查表（crud.go toDoc/lookup*/extractBatchEmbeddings） ───

    /** 对照 lookupEmbedding：按 SourceID 查；形状不对降级 keyword-only + WARN。 */
    /** 对照 lookupChunkEnabled：chunk_enabled 覆写 IndexInfo.isEnabled。 */
    // ── 配置（config.go buildInternalCfg） ──────────────────────────────────

    /** 对照 buildInternalCfg：只补缺省、不拒绝（范围校验是服务层职责）。 */
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

    // ── 索引名净化（repository.go sanitizeIndexName） ───────────────────────

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

    // ── HTTP 自持（对照 transport.go + 各 typed 调用点） ─────────────────────

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

    /** 对照 wrapTransport：401/403→AUTH；429+断路器→CIRCUIT_BREAKER；其余→TRANSPORT。 */
    private static OpenSearchDriverException classifyFailure(int status, byte[] body) {
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

    // ── 别名操作（mapping.go 的 aliasExists/aliasPut） ──────────────────────

    /** HEAD /_alias/&lt;name&gt;：200=true、404=false、其余按 wrapTransport 分类。 */
    private boolean aliasExists(String alias) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(addr + "/_alias/" + alias))
                    .timeout(Duration.ofSeconds(120))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (basicAuth != null) {
                builder.header("Authorization", basicAuth);
            }
            HttpResponse<Void> resp =
                    http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status == 200) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw classifyFailure(status, null);
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

    /** 对照 aliasPut：PUT /_aliases，body {"actions":[{"add":{...}}]}。 */
    private void aliasPut(String index, String alias) {
        Map<String, Object> add = new TreeMap<>();
        add.put("index", index);
        add.put("alias", alias);
        Map<String, Object> action = new TreeMap<>();
        action.put("add", add);
        Map<String, Object> body = new TreeMap<>();
        body.put("actions", List.of(action));
        try {
            send("PUT", "/_aliases", MAPPER.writeValueAsBytes(body), "application/json");
        } catch (OpenSearchDriverException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        }
    }
}

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private final String baseIndex;
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
    private final Object keywordsLock = new Object();
    private boolean keywordsReady;
    private OpenSearchDriverException keywordsErr;

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
    @Override
    public void save(IndexInfo info, Map<String, Object> params) throws Exception {
        float[] emb = lookupEmbedding(params, info.sourceId);
        boolean enabled = lookupChunkEnabled(params, info.chunkId, info.isEnabled);
        String targetIndex;
        if (emb.length > 0) {
            ensureReady(emb.length);
            targetIndex = indexAlias(emb.length);
        } else {
            ensureKeywordsIndex();
            targetIndex = keywordsIndex();
        }
        byte[] doc = MAPPER.writeValueAsBytes(toDoc(info, emb, enabled));
        send("PUT", "/" + targetIndex + "/_doc/" + info.chunkId, doc, "application/json");
    }

    /** 对照 BatchSave：批量上限 + 混合维度检 + NDJSON + 逐项错误检视。 */
    @Override
    public void batchSave(List<IndexInfo> infos, Map<String, Object> params) throws Exception {
        if (infos == null || infos.isEmpty()) {
            return;
        }
        float[][] embs = extractBatchEmbeddings(params, infos);
        int dim = 0;
        for (float[] emb : embs) {
            if (emb.length > 0) {
                dim = emb.length;
                break;
            }
        }
        long estimated = (long) infos.size() * (100 + dim * 5 + 1024);
        if (estimated > BULK_BODY_CAP_BYTES) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: estimated bulk body " + estimated + "B exceeds 10MB cap (n="
                            + infos.size() + ", dim=" + dim + "): opensearch: batch size exceeds"
                            + " driver cap");
        }
        if (infos.size() > BULK_DOC_CAP) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: bulk n=" + infos.size() + " exceeds 1000-doc cap: opensearch:"
                            + " batch size exceeds driver cap");
        }
        String alias;
        if (dim == 0) {
            ensureKeywordsIndex();
            alias = keywordsIndex();
        } else {
            ensureReady(dim);
            alias = indexAlias(dim);
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < infos.size(); i++) {
            IndexInfo info = infos.get(i);
            // 照 Go 的 map 序列化：键字母序 {"index":{"_id":..,"_index":..}}
            Map<String, Object> action = new TreeMap<>();
            Map<String, Object> desc = new TreeMap<>();
            desc.put("_id", info.chunkId);
            desc.put("_index", alias);
            action.put("index", desc);
            buf.append(MAPPER.writeValueAsString(action)).append('\n');
            boolean enabled = lookupChunkEnabled(params, info.chunkId, info.isEnabled);
            buf.append(MAPPER.writeValueAsString(toDoc(info, embs[i], enabled))).append('\n');
        }
        String response = send("POST", "/_bulk",
                buf.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson",
                BULK_RESPONSE_CAP);
        inspectBulkResponse(response);
    }

    /** 对照 stubs.go 的 EstimateStorageSize：保守下界 n*(1024+4*768+128)。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return 0;
        }
        return (long) indexInfoList.size() * (1024 + 4 * 768 + 128);
    }

    // ── 删除（crud.go 的三个 DeleteBy* + byquery.go） ───────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByList(chunkIdList, dimension, "chunk_id");
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByList(sourceIdList, dimension, "source_id");
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByList(knowledgeIdList, dimension, "knowledge_id");
    }

    /** 对照 deleteByList：cap 1000；dim==0 → keywords 索引。 */
    private void deleteByList(List<String> ids, int dim, String field) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.size() > BULK_DOC_CAP) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: " + field + "-delete batch " + ids.size()
                            + " > 1000 cap: opensearch: batch size exceeds driver cap");
        }
        String index;
        if (dim == 0) {
            ensureKeywordsIndex();
            index = keywordsIndex();
        } else {
            ensureReady(dim);
            index = indexAlias(dim);
        }
        Map<String, Object> terms = new TreeMap<>();
        terms.put(field, ids);
        Map<String, Object> query = new TreeMap<>();
        query.put("terms", terms);
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        String response = send("POST", "/" + index + "/_delete_by_query?refresh=true",
                MAPPER.writeValueAsBytes(body), "application/json", SEARCH_BODY_CAP);
        inspectByQueryResponse(response, false);
    }

    // ── 复制 / 批量更新（copy.go + bulk_update.go） ─────────────────────────

    /** 对照 CopyIndices：批 500 分页扫源 + 三态 SourceID 改写 + 逐页 BatchSave。 */
    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                            int dimension, String knowledgeType) throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[OpenSearch] CopyIndices: empty chunk mapping, skipping");
            return;
        }
        if (dimension <= 0) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                    "opensearch: CopyIndices requires dim > 0, got " + dimension
                            + ": opensearch: embedding dimension mismatch");
        }
        ensureReady(dimension);
        String alias = indexAlias(dimension);
        long total = 0;
        for (int from = 0; ; from += COPY_BATCH_SIZE) {
            List<Map<String, Object>> docs = copyScanBatch(alias, sourceKnowledgeBaseId, from);
            if (docs.isEmpty()) {
                break;
            }
            List<IndexInfo> infos = new ArrayList<>();
            Map<String, float[]> embMap = new HashMap<>();
            Map<String, Boolean> enabledMap = new HashMap<>();
            for (Map<String, Object> d : docs) {
                String chunkId = str(d.get("chunk_id"));
                String targetChunkId = sourceToTargetChunkIdMap.get(chunkId);
                if (targetChunkId == null) {
                    log.warn("[OpenSearch] CopyIndices: source chunk {} not mapped, skipping",
                            chunkId);
                    continue;
                }
                String knowledgeId = str(d.get("knowledge_id"));
                String targetKnowledgeId = sourceToTargetKbIdMap.get(knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[OpenSearch] CopyIndices: source knowledge {} not mapped, skipping",
                            knowledgeId);
                    continue;
                }
                String sourceId = str(d.get("source_id"));
                String targetSourceId = transformSourceId(sourceId, chunkId, targetChunkId);
                @SuppressWarnings("unchecked")
                List<Number> embedding = (List<Number>) d.get("embedding");
                if (embedding != null && !embedding.isEmpty()) {
                    // BatchSave 按 SourceID 查 embedding——键用目标 source id
                    //（不是 ES 驱动的 chunk id 约定）
                    float[] vector = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vector[i] = embedding.get(i).floatValue();
                    }
                    embMap.put(targetSourceId, vector);
                }
                boolean enabled = Boolean.TRUE.equals(d.get("is_enabled"));
                enabledMap.put(targetChunkId, enabled);
                IndexInfo info = new IndexInfo();
                info.content = str(d.get("content"));
                info.sourceId = targetSourceId;
                info.sourceType = d.get("source_type") instanceof Number n ? n.intValue() : 0;
                info.chunkId = targetChunkId;
                info.knowledgeId = targetKnowledgeId;
                info.knowledgeBaseId = targetKnowledgeBaseId;
                info.knowledgeType = knowledgeType;
                info.tagId = str(d.get("tag_id"));
                info.isEnabled = enabled;
                info.isRecommended = Boolean.TRUE.equals(d.get("is_recommended"));
                infos.add(info);
            }
            if (!infos.isEmpty()) {
                Map<String, Object> params = new HashMap<>();
                params.put("embedding", embMap);
                params.put("chunk_enabled", enabledMap);
                batchSave(infos, params);
                total += infos.size();
            }
            if (docs.size() < COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[OpenSearch] CopyIndices: copied {} docs (KB {} → {}, dim={})",
                total, sourceKnowledgeBaseId, targetKnowledgeBaseId, dimension);
        auditSink().emitReindexExecuted(alias, alias, total);
    }

    /** 对照 copyScanBatch：全 _source（含 embedding/is_recommended）。 */
    private List<Map<String, Object>> copyScanBatch(String index, String sourceKb, int from)
            throws Exception {
        Map<String, Object> term = new TreeMap<>();
        term.put("knowledge_base_id", sourceKb);
        Map<String, Object> filterClause = new TreeMap<>();
        filterClause.put("term", term);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("filter", List.of(filterClause));
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> body = new TreeMap<>();
        body.put("from", from);
        body.put("query", query);
        body.put("size", COPY_BATCH_SIZE);
        String response = send("POST", "/" + index + "/_search",
                MAPPER.writeValueAsBytes(body), "application/json", BULK_RESPONSE_CAP);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode hits = MAPPER.readTree(response).path("hits").path("hits");
        for (JsonNode h : hits) {
            JsonNode source = h.path("_source");
            Map<String, Object> row = new HashMap<>();
            row.put("content", source.path("content").asText(""));
            row.put("source_id", source.path("source_id").asText(""));
            row.put("source_type", source.path("source_type").asInt(0));
            row.put("chunk_id", source.path("chunk_id").asText(""));
            row.put("knowledge_id", source.path("knowledge_id").asText(""));
            row.put("knowledge_base_id", source.path("knowledge_base_id").asText(""));
            row.put("tag_id", source.path("tag_id").asText(""));
            row.put("is_enabled", source.path("is_enabled").asBoolean(false));
            row.put("is_recommended", source.path("is_recommended").asBoolean(false));
            row.put("embedding", MAPPER.convertValue(source.path("embedding"), List.class));
            out.add(row);
        }
        return out;
    }

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
    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        Map<Boolean, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, Boolean> e : chunkStatusMap.entrySet()) {
            groups.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (boolean value : new boolean[] {false, true}) {
            List<String> ids = groups.get(value);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            List<String> sorted = new ArrayList<>(ids);
            java.util.Collections.sort(sorted);
            Map<String, Object> params = new TreeMap<>();
            params.put("v", value);
            updateByQueryScript(sorted, "ctx._source.is_enabled = params.v", params);
        }
    }

    /** 对照 BatchUpdateChunkTagID：tag 字典序、组内 id 排序。 */
    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        Map<String, List<String>> groups = new TreeMap<>();
        for (Map.Entry<String, String> e : chunkTagMap.entrySet()) {
            groups.computeIfAbsent(e.getValue() == null ? "" : e.getValue(),
                    k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            List<String> ids = new ArrayList<>(e.getValue());
            java.util.Collections.sort(ids);
            Map<String, Object> params = new TreeMap<>();
            params.put("v", e.getKey());
            updateByQueryScript(ids, "ctx._source.tag_id = params.v", params);
        }
    }

    /** 对照 updateByQueryScript：跨维 {@code <base>_*} + painless 常量源 + params 绑定。 */
    private void updateByQueryScript(List<String> chunkIds, String source,
                                     Map<String, Object> scriptParams) throws Exception {
        Map<String, Object> terms = new TreeMap<>();
        terms.put("chunk_id", chunkIds);
        Map<String, Object> query = new TreeMap<>();
        query.put("terms", terms);
        Map<String, Object> script = new TreeMap<>();
        script.put("lang", "painless");
        script.put("params", scriptParams);
        script.put("source", source);
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        body.put("script", script);
        String response = send("POST", "/" + baseIndex + "_*/_update_by_query?refresh=true",
                MAPPER.writeValueAsBytes(body), "application/json", SEARCH_BODY_CAP);
        inspectByQueryResponse(response, false);
    }

    // ── 迁移（move.go） ─────────────────────────────────────────────────────

    /**
     * 对照 MoveKnowledgeIndices：跨 {@code <base>_*} 改写（含历史向量——chunk 行已删的
     * 也搬），保向量 id；完整性校验 requireComplete=true。
     */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        Map<String, Object> termKb = new TreeMap<>();
        termKb.put("knowledge_base_id", sourceKb);
        Map<String, Object> termKbWrap = new TreeMap<>();
        termKbWrap.put("term", termKb);
        Map<String, Object> termKid = new TreeMap<>();
        termKid.put("knowledge_id", knowledgeId);
        Map<String, Object> termKidWrap = new TreeMap<>();
        termKidWrap.put("term", termKid);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("filter", List.of(termKbWrap, termKidWrap));
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> scriptParams = new TreeMap<>();
        scriptParams.put("target", targetKb);
        Map<String, Object> script = new TreeMap<>();
        script.put("lang", "painless");
        script.put("params", scriptParams);
        script.put("source",
                "ctx._source.knowledge_base_id = params.target; ctx._source.tag_id = '';");
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        body.put("script", script);
        String response = send("POST", "/" + baseIndex + "_*/_update_by_query?refresh=true",
                MAPPER.writeValueAsBytes(body), "application/json", SEARCH_BODY_CAP);
        inspectByQueryResponse(response, true);
    }

    // ── 检索（retrieve.go + query.go） ──────────────────────────────────────

    /** 对照 Retrieve：按 RetrieverType 分派；dim 解析序 AdditionalParams &gt; embedding。 */
    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        int dim;
        boolean multiIndex;
        if (params.additionalParams != null
                && params.additionalParams.get("dim") instanceof Integer v && v > 0) {
            dim = v;
            multiIndex = false;
        } else if (params.embedding != null && params.embedding.length > 0) {
            dim = params.embedding.length;
            multiIndex = false;
        } else {
            dim = 0;
            multiIndex = true;
        }
        switch (params.retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR: {
                if (dim == 0) {
                    throw new OpenSearchDriverException(
                            OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                            "opensearch: vector retrieve requires embedding or AdditionalParams"
                                    + "[\"dim\"]: opensearch: embedding dimension mismatch");
                }
                ensureReady(dim);
                String body = knnQueryJson(params.embedding, effectiveTopK(params),
                        params.threshold, filtersOf(params));
                List<IndexWithScore> hits = search(indexAlias(dim), body);
                return List.of(wrapResults(hits, params.retrieverType,
                        EngineTypes.MATCH_EMBEDDING));
            }
            case EngineTypes.RETRIEVER_KEYWORDS: {
                String indexPattern = multiIndex ? baseIndex + "_*" : null;
                if (!multiIndex) {
                    ensureReady(dim);
                    indexPattern = indexAlias(dim);
                }
                String body = keywordQueryJson(params.query, effectiveTopK(params),
                        params.threshold, filtersOf(params));
                List<IndexWithScore> hits = search(indexPattern, body);
                return List.of(wrapResults(hits, params.retrieverType,
                        EngineTypes.MATCH_KEYWORDS));
            }
            default:
                throw new OpenSearchDriverException(
                        OpenSearchDriverException.Kind.CONFIG_INVALID,
                        "opensearch: unsupported retriever type \"" + params.retrieverType + "\"");
        }
    }

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

    /** 对照 retrieveFilters + fromParams：类型化过滤（无 JSON 注入面）。 */
    private static Map<String, Object> filtersOf(RetrieveParams p) {
        Map<String, Object> f = new TreeMap<>();
        f.put("kbIds", p.knowledgeBaseIds == null ? List.of() : p.knowledgeBaseIds);
        f.put("knowledgeIds", p.knowledgeIds == null ? List.of() : p.knowledgeIds);
        f.put("tagIds", p.tagIds == null ? List.of() : p.tagIds);
        f.put("excludeChunkIds", p.excludeChunkIds == null ? List.of() : p.excludeChunkIds);
        f.put("excludeKnowledgeIds",
                p.excludeKnowledgeIds == null ? List.of() : p.excludeKnowledgeIds);
        return f;
    }

    /** 对照 toBoolMust：terms IN → 嵌套 must_not → is_enabled=true 隐含子句。 */
    private static List<Map<String, Object>> toBoolMust(Map<String, Object> f) {
        List<Map<String, Object>> must = new ArrayList<>();
        List<String> kbIds = cast(f.get("kbIds"));
        if (!kbIds.isEmpty()) {
            must.add(wrapTerms("knowledge_base_id", kbIds));
        }
        List<String> knowledgeIds = cast(f.get("knowledgeIds"));
        if (!knowledgeIds.isEmpty()) {
            must.add(wrapTerms("knowledge_id", knowledgeIds));
        }
        List<String> tagIds = cast(f.get("tagIds"));
        if (!tagIds.isEmpty()) {
            must.add(wrapTerms("tag_id", tagIds));
        }
        List<String> excludeChunks = cast(f.get("excludeChunkIds"));
        if (!excludeChunks.isEmpty()) {
            must.add(wrapMustNot("chunk_id", excludeChunks));
        }
        List<String> excludeKnowledge = cast(f.get("excludeKnowledgeIds"));
        if (!excludeKnowledge.isEmpty()) {
            must.add(wrapMustNot("knowledge_id", excludeKnowledge));
        }
        must.add(wrapTerm("is_enabled", true));
        return must;
    }

    @SuppressWarnings("unchecked")
    private static List<String> cast(Object o) {
        return (List<String>) o;
    }

    private static Map<String, Object> wrapTerms(String field, List<String> values) {
        Map<String, Object> terms = new TreeMap<>();
        terms.put(field, values);
        Map<String, Object> out = new TreeMap<>();
        out.put("terms", terms);
        return out;
    }

    private static Map<String, Object> wrapTerm(String field, Object value) {
        Map<String, Object> term = new TreeMap<>();
        term.put(field, value);
        Map<String, Object> out = new TreeMap<>();
        out.put("term", term);
        return out;
    }

    private static Map<String, Object> wrapMustNot(String field, List<String> values) {
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must_not", wrapTerms(field, values));
        Map<String, Object> out = new TreeMap<>();
        out.put("bool", bool);
        return out;
    }

    /** 对照 buildKNNQuery：min_score 直通（COSINESIMIL 已映射 [0,1]）。 */
    private String knnQueryJson(float[] embedding, int topK, double threshold,
                                Map<String, Object> f) throws Exception {
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must", toBoolMust(f));
        Map<String, Object> filter = new TreeMap<>();
        filter.put("bool", bool);
        Map<String, Object> embeddingClause = new TreeMap<>();
        embeddingClause.put("vector", embedding);
        embeddingClause.put("k", topK);
        embeddingClause.put("filter", filter);
        Map<String, Object> knn = new TreeMap<>();
        knn.put("embedding", embeddingClause);
        Map<String, Object> query = new TreeMap<>();
        query.put("knn", knn);
        Map<String, Object> body = new TreeMap<>();
        body.put("size", topK);
        body.put("query", query);
        if (threshold > 0) {
            body.put("min_score", threshold);
        }
        return MAPPER.writeValueAsString(body);
    }

    /** 对照 buildKeywordQuery：BM25 match + 过滤；min_score 语义同上。 */
    private String keywordQueryJson(String queryText, int topK, double threshold,
                                    Map<String, Object> f) throws Exception {
        List<Map<String, Object>> must = toBoolMust(f);
        Map<String, Object> match = new TreeMap<>();
        match.put("content", queryText);
        Map<String, Object> matchWrap = new TreeMap<>();
        matchWrap.put("match", match);
        must.add(matchWrap);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must", must);
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> body = new TreeMap<>();
        body.put("size", topK);
        body.put("query", query);
        if (threshold > 0) {
            body.put("min_score", threshold);
        }
        return MAPPER.writeValueAsString(body);
    }

    /** 对照 search：404 → INDEX_NOT_FOUND；响应 16MB cap。 */
    private List<IndexWithScore> search(String indexPattern, String body) throws Exception {
        String response = send("POST", "/" + indexPattern + "/_search",
                body.getBytes(StandardCharsets.UTF_8), "application/json", SEARCH_BODY_CAP);
        return parseSearchHits(response);
    }

    private static List<IndexWithScore> parseSearchHits(String response) throws Exception {
        List<IndexWithScore> out = new ArrayList<>();
        JsonNode hits = MAPPER.readTree(response).path("hits").path("hits");
        for (JsonNode h : hits) {
            IndexWithScore s = new IndexWithScore();
            s.id = h.path("_id").asText("");
            s.score = h.path("_score").asDouble(0);
            JsonNode source = h.path("_source");
            s.chunkId = source.path("chunk_id").asText("");
            s.knowledgeId = source.path("knowledge_id").asText("");
            s.knowledgeBaseId = source.path("knowledge_base_id").asText("");
            s.sourceId = source.path("source_id").asText("");
            s.sourceType = source.path("source_type").asInt(0);
            s.tagId = source.path("tag_id").asText("");
            s.content = source.path("content").asText("");
            s.isEnabled = source.path("is_enabled").asBoolean(false);
            if (!s.id.equals(s.chunkId)) {
                // 对照 wrapResults 的 D12 不变量告警（_id 恒 = chunk_id）
                log.warn("[OpenSearch] hit._id=\"{}\" != _source.chunk_id=\"{}\""
                        + " (D12 invariant violation)", s.id, s.chunkId);
            }
            out.add(s);
        }
        return out;
    }

    /** 对照 wrapResults：恒返回单包结果（服务层扇出的 one-bundle-per-driver 协议）。 */
    private RetrieveResult wrapResults(List<IndexWithScore> hits, String retrieverType,
                                       int matchType) {
        for (IndexWithScore s : hits) {
            s.matchType = matchType;
        }
        return new RetrieveResult(hits, EngineTypes.ENGINE_OPENSEARCH, retrieverType);
    }

    // ── 惰性初始化（repository.go ensureReady + mapping.go） ────────────────

    /** 对照 ensureReady；dim 界 (0, 16000]（knn_vector 硬上限）。 */
    private void ensureReady(int dim) {
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
    private String indexAlias(int dim) {
        return baseIndex + "_" + dim;
    }

    /** 对照 keywordsIndex。 */
    private String keywordsIndex() {
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
    private void ensureKeywordsIndex() {
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

    /** 对照 toDoc：字母序键；缺 embedding 时整体省略该字段（keyword-only 文档）。 */
    static Map<String, Object> toDoc(IndexInfo info, float[] emb, boolean enabled) {
        Map<String, Object> doc = new TreeMap<>();
        doc.put("chunk_id", info.chunkId);
        doc.put("knowledge_id", info.knowledgeId);
        doc.put("knowledge_base_id", info.knowledgeBaseId);
        doc.put("source_id", info.sourceId);
        doc.put("source_type", info.sourceType);
        doc.put("tag_id", info.tagId);
        doc.put("content", info.content);
        doc.put("is_enabled", enabled);
        doc.put("is_recommended", info.isRecommended);
        if (emb != null && emb.length > 0) {
            doc.put("embedding", emb);
        }
        return doc;
    }

    /** 对照 lookupEmbedding：按 SourceID 查；形状不对降级 keyword-only + WARN。 */
    @SuppressWarnings("unchecked")
    static float[] lookupEmbedding(Map<String, Object> params, String sourceId) {
        if (params == null) {
            return new float[0];
        }
        Object raw = params.get("embedding");
        if (!(raw instanceof Map)) {
            if (raw != null) {
                log.warn("[OpenSearch] additionalParams[\"embedding\"] is {}, want map",
                        raw.getClass().getSimpleName());
            }
            return new float[0];
        }
        Object vector = ((Map<String, Object>) raw).get(sourceId);
        if (vector instanceof float[] v) {
            return v;
        }
        if (vector instanceof List<?> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
            }
            return out;
        }
        return new float[0];
    }

    /** 对照 lookupChunkEnabled：chunk_enabled 覆写 IndexInfo.isEnabled。 */
    @SuppressWarnings("unchecked")
    static boolean lookupChunkEnabled(Map<String, Object> params, String chunkId, boolean def) {
        if (params == null) {
            return def;
        }
        Object raw = params.get("chunk_enabled");
        if (!(raw instanceof Map)) {
            return def;
        }
        Object v = ((Map<String, Object>) raw).get(chunkId);
        if (v instanceof Boolean b) {
            return b;
        }
        return def;
    }

    /** 对照 extractBatchEmbeddings：混合维度 → DIMENSION_MISMATCH。 */
    static float[][] extractBatchEmbeddings(Map<String, Object> params, List<IndexInfo> infos) {
        float[][] out = new float[infos.size()][];
        int dim = 0;
        for (int i = 0; i < infos.size(); i++) {
            float[] emb = lookupEmbedding(params, infos.get(i).sourceId);
            out[i] = emb;
            if (emb.length > 0) {
                if (dim == 0) {
                    dim = emb.length;
                } else if (emb.length != dim) {
                    throw new OpenSearchDriverException(
                            OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                            "opensearch: embedding[" + infos.get(i).sourceId + "] dim="
                                    + emb.length + " != first non-empty dim=" + dim
                                    + ": opensearch: embedding dimension mismatch");
                }
            }
        }
        return out;
    }

    /** 对照 inspectBulkResponse：逐项错误（≤5 条 "[op id] type"；reason 只进 DEBUG）。 */
    private static void inspectBulkResponse(String response) throws Exception {
        JsonNode root;
        try {
            root = MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse bulk response: opensearch: transport error");
        }
        if (!root.path("errors").asBoolean(false)) {
            return;
        }
        int total = 0;
        List<String> msgs = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            // bulk item 形如 {"index": {...}}（单键）
            var it = item.fields();
            if (!it.hasNext()) {
                continue;
            }
            String opName = it.next().getKey();
            JsonNode op = item.path(opName);
            JsonNode err = op.path("error");
            if (err.isMissingNode() || err.isNull()) {
                continue;
            }
            total++;
            log.debug("[OpenSearch] bulk item err: op={} id={} type={} reason={}",
                    opName, op.path("_id").asText(""), err.path("type").asText(""),
                    err.path("reason").asText(""));
            if (msgs.size() < 5) {
                msgs.add("[" + opName + " " + op.path("_id").asText("") + "] "
                        + err.path("type").asText(""));
            }
        }
        if (total == 0) {
            return;
        }
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.TRANSPORT,
                "opensearch: bulk partial failure (" + total + " items failed, first 5: "
                        + String.join("; ", msgs) + "): opensearch: transport error");
    }

    /** 对照 inspectByQueryResult（requireComplete=move 的完整性校验）。 */
    private static void inspectByQueryResponse(String response, boolean requireComplete)
            throws Exception {
        JsonNode root;
        try {
            root = MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse by-query response: opensearch: transport error");
        }
        boolean timedOut = root.path("timed_out").asBoolean(false);
        int versionConflicts = root.path("version_conflicts").asInt(0);
        if (requireComplete && (timedOut || versionConflicts != 0)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: incomplete move (timed_out=" + timedOut
                            + ", version_conflicts=" + versionConflicts
                            + "): opensearch: transport error");
        }
        JsonNode total = root.path("total");
        JsonNode updated = root.path("updated");
        if (requireComplete && (total.isMissingNode() || updated.isMissingNode()
                || total.asLong(-1) < 0 || updated.asLong(-1) != total.asLong(-1))) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: incomplete move document counts: opensearch: transport error");
        }
        JsonNode failures = root.path("failures");
        if (failures.isEmpty()) {
            if (versionConflicts > 0) {
                log.warn("[OpenSearch] by-query had {} version conflicts (proceeded)",
                        versionConflicts);
            }
            return;
        }
        List<String> msgs = new ArrayList<>();
        for (JsonNode f : failures) {
            log.debug("[OpenSearch] by-query failure: id={} type={} reason={}",
                    f.path("id").asText(""), f.path("cause").path("type").asText(""),
                    f.path("cause").path("reason").asText(""));
            if (msgs.size() < 5) {
                msgs.add("[" + f.path("id").asText("") + "] "
                        + f.path("cause").path("type").asText(""));
            }
        }
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.TRANSPORT,
                "opensearch: by-query partial failure (" + failures.size() + " failed, first 5: "
                        + String.join("; ", msgs) + "): opensearch: transport error");
    }

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

    private AuditSink auditSink() {
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
    private String send(String method, String pathWithQuery, byte[] body, String contentType,
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

    private String send(String method, String pathWithQuery, byte[] body, String contentType) {
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

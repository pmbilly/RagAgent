package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * 关键词/向量混合检索引擎服务：对 {@link RetrieveEngineRepository} 做"嵌入 + 分批落库"的
 * 薄封装，其余能力纯转发给仓库。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>骨架：持 {@code indexRepository} + {@code engineType}；{@code Retrieve} / {@code Support} /
 *       三类删除 / {@code CopyIndices} / 两类批量更新<b>纯转发</b>；{@code Index}/{@code BatchIndex}
 *       负责嵌入与分批落库</li>
 *   <li>嵌入前净化（{@code sanitizeForEmbedding}）：仅当内容含 {@code "base64,"} 时才跑 4 条正则
 *       （内联图片载荷 → {@code [image]}；常路不付正则代价），随后按<b>码点</b>截断到
 *       {@code safetyMaxChars = 20000} 并告警</li>
 *   <li>批量嵌入带指数退避（{@code batchEmbedWithBackoff}）：最多 5 次，底延迟 200ms、每次翻倍
 *       （200/400/800/1600）——失败在<b>第 5 次</b>后把最后错误抛出</li>
 *   <li>分批：向量路每批 <b>40</b>、并发上限 <b>5</b>（批数 ≤ 5 时不设上限、全并发）；
 *       非向量路每批 <b>10</b>、同样 ≤ 5 全并发/否则限 5</li>
 *   <li>嵌入映射一律以 <b>SourceID</b> 为键（{@code Index}/{@code BatchIndex}/{@code EstimateStorageSize}
 *       与 {@code ToDBVectorEmbedding} 的查表语义一致）</li>
 *   <li>迁移能力探测（{@code ValidateKnowledgeIndexMove}）：仓库未挂
 *       {@link RetrieveEngineRepository.KnowledgeIndexMover} → 报
 *       {@code retriever <engine> does not support moving indices}</li>
 * </ul>
 *
 * <h2>有意修正</h2>
 * <p>{@code EstimateStorageSize} 的占位向量按 <b>SourceID</b> 为键，与查表口径一致——
 * 生成问题（{@code <chunk>-<qid>}）也能估到向量字节。</p>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>并发用 Java 21 虚拟线程执行器 + {@code Semaphore}；
 *       首批失败即取消其余任务</li>
 *   <li>分批工具为本类内 {@code chunkSlice}（末批可短）</li>
 *   <li>嵌入口直接用全仓统一的 {@link com.ragagent.embedding.Embedder}
 *       （不为检索引擎单独造薄口）</li>
 * </ul>
 */
public class KeywordsVectorHybridRetrieveEngineService
        implements RetrieveEngineService, RetrieveEngineService.KnowledgeIndexMover,
        RetrieveEngineService.KnowledgeIndexMoveValidator {

    private static final Logger log =
            LoggerFactory.getLogger(KeywordsVectorHybridRetrieveEngineService.class);

    /** 嵌入前内容截断上限（码点数）。 */
    public static final int SAFETY_MAX_CHARS = 20000;
    /** 批量嵌入重试次数与底延迟（指数退避）。 */
    static final int EMBED_RETRY_ATTEMPTS = 5;
    static final long EMBED_RETRY_BASE_DELAY_MS = 200L;
    /** 分批大小：向量路 40、非向量路 10。 */
    static final int VECTOR_BATCH_SIZE = 40;
    static final int PLAIN_BATCH_SIZE = 10;
    /** 分批落库的并发上限。 */
    static final int MAX_CONCURRENCY = 5;

    /** 内联图片载荷识别正则（4 条，含内联 (?is)/(?i) 标志）。 */
    static final List<Pattern> EMBEDDING_IMAGE_PAYLOAD_PATTERNS = List.of(
            Pattern.compile("(?is)<img\\b[^>]*\\bsrc=[\"']\\s*data:image/[a-z0-9.+-]+;base64,[^\"']+[\"'][^>]*>"),
            Pattern.compile("(?is)!\\[[^\\]]*\\]\\(\\s*data:image/[a-z0-9.+-]+;base64,[^)]+\\)"),
            Pattern.compile("(?i)data:image/[a-z0-9.+-]+;base64,[a-z0-9+/=]{200,}"),
            Pattern.compile("(?i)data:[a-z0-9.+/-]+;base64,[a-z0-9+/=]{200,}"));

    private final RetrieveEngineRepository indexRepository;
    private final String engineType;
    private final long embedRetryBaseDelayMs;

    public KeywordsVectorHybridRetrieveEngineService(RetrieveEngineRepository indexRepository,
                                                     String engineType) {
        this(indexRepository, engineType, EMBED_RETRY_BASE_DELAY_MS);
    }

    /** 供测试注入底延迟（默认同 {@link #EMBED_RETRY_BASE_DELAY_MS}）。 */
    KeywordsVectorHybridRetrieveEngineService(RetrieveEngineRepository indexRepository,
                                              String engineType, long embedRetryBaseDelayMs) {
        this.indexRepository = indexRepository;
        this.engineType = engineType;
        this.embedRetryBaseDelayMs = embedRetryBaseDelayMs;
    }

    /** 引擎类型标识。 */
    @Override
    public String engineType() {
        return engineType;
    }

    /** 该引擎支持的检索类型。 */
    @Override
    public List<String> support() {
        return indexRepository.support();
    }

    /** 纯转发。 */
    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return indexRepository.retrieve(params);
    }

    // ── 索引 ────────────────────────────────────────────────────────────────

    /** 按需嵌入（向量路）后交给 {@code save}。 */
    @Override
    public void index(Embedder embedder, IndexInfo indexInfo, List<String> retrieverTypes)
            throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, float[]> embeddingMap = new LinkedHashMap<>();
        if (retrieverTypes != null && retrieverTypes.contains(EngineTypes.RETRIEVER_VECTOR)) {
            float[] embedding = embedder.embed(sanitizeForEmbedding(indexInfo.content));
            embeddingMap.put(indexInfo.sourceId, embedding);
        }
        params.put("embedding", embeddingMap);
        indexRepository.save(indexInfo, params);
    }

    /** 向量路分批 40、非向量路分批 10；批数 ≤5 全并发。 */
    @Override
    public void batchIndex(Embedder embedder, List<IndexInfo> indexInfoList,
                           List<String> retrieverTypes) throws Exception {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return;
        }
        if (retrieverTypes != null && retrieverTypes.contains(EngineTypes.RETRIEVER_VECTOR)) {
            List<String> contentList = new ArrayList<>(indexInfoList.size());
            for (IndexInfo info : indexInfoList) {
                contentList.add(sanitizeForEmbedding(info.content));
            }
            List<float[]> embeddings = batchEmbedWithBackoff(embedder, contentList);
            List<List<IndexInfo>> chunks = chunkSlice(indexInfoList, VECTOR_BATCH_SIZE);
            if (chunks.size() <= MAX_CONCURRENCY) {
                concurrentBatchSave(chunks, embeddings, VECTOR_BATCH_SIZE);
            } else {
                boundedConcurrentBatchSave(chunks, embeddings, VECTOR_BATCH_SIZE, MAX_CONCURRENCY);
            }
            return;
        }
        List<List<IndexInfo>> chunks = chunkSlice(indexInfoList, PLAIN_BATCH_SIZE);
        if (chunks.size() <= MAX_CONCURRENCY) {
            concurrentBatchSaveNoEmbedding(chunks);
        } else {
            boundedConcurrentBatchSaveNoEmbedding(chunks, MAX_CONCURRENCY);
        }
    }

    /** 嵌入重试：5 次、200ms 起步翻倍；末次失败即抛最后错误。 */
    List<float[]> batchEmbedWithBackoff(Embedder embedder, List<String> contentList)
            throws Exception {
        long delay = embedRetryBaseDelayMs;
        List<float[]> embeddings = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < EMBED_RETRY_ATTEMPTS; attempt++) {
            try {
                embeddings = embedder.batchEmbed(contentList);
                return embeddings;
            } catch (Exception e) {
                lastError = e;
                log.error("BatchEmbedWithPool attempt {}/{} failed: {}", attempt + 1,
                        EMBED_RETRY_ATTEMPTS, e.toString());
                if (attempt + 1 < EMBED_RETRY_ATTEMPTS) {
                    Thread.sleep(delay);
                    delay *= 2;
                }
            }
        }
        throw lastError;
    }

    /** 仅含 base64 时跑正则；按码点截断到 20000。 */
    static String sanitizeForEmbedding(String content) {
        String sanitized = content == null ? "" : content;
        if (sanitized.contains("base64,")) {
            for (Pattern pattern : EMBEDDING_IMAGE_PAYLOAD_PATTERNS) {
                sanitized = pattern.matcher(sanitized).replaceAll("[image]");
            }
        }
        int codePoints = sanitized.codePointCount(0, sanitized.length());
        if (codePoints <= SAFETY_MAX_CHARS) {
            return sanitized;
        }
        int end = sanitized.offsetByCodePoints(0, SAFETY_MAX_CHARS);
        log.warn("embedding input truncated: {} runes -> {}", codePoints, SAFETY_MAX_CHARS);
        return sanitized.substring(0, end);
    }

    private static List<List<IndexInfo>> chunkSlice(List<IndexInfo> list, int size) {
        List<List<IndexInfo>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return chunks;
    }

    /** 无上限并发，逐批嵌入映射按 SourceID。 */
    void concurrentBatchSave(List<List<IndexInfo>> chunks, List<float[]> embeddings,
                             int batchSize) throws Exception {
        runConcurrently(chunks.size(), 0, i -> {
            List<IndexInfo> indexChunk = chunks.get(i);
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (int j = 0; j < indexChunk.size(); j++) {
                embeddingMap.put(indexChunk.get(j).sourceId, embeddings.get(i * batchSize + j));
            }
            params.put("embedding", embeddingMap);
            indexRepository.batchSave(indexChunk, params);
        });
    }

    /** 有界并发。 */
    void boundedConcurrentBatchSave(List<List<IndexInfo>> chunks, List<float[]> embeddings,
                                    int batchSize, int maxConcurrency) throws Exception {
        runConcurrently(chunks.size(), maxConcurrency, i -> {
            List<IndexInfo> indexChunk = chunks.get(i);
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (int j = 0; j < indexChunk.size(); j++) {
                embeddingMap.put(indexChunk.get(j).sourceId, embeddings.get(i * batchSize + j));
            }
            params.put("embedding", embeddingMap);
            indexRepository.batchSave(indexChunk, params);
        });
    }

    /** 无上限并发、无嵌入。 */
    void concurrentBatchSaveNoEmbedding(List<List<IndexInfo>> chunks) throws Exception {
        runConcurrently(chunks.size(), 0, i -> indexRepository.batchSave(chunks.get(i),
                new LinkedHashMap<>()));
    }

    /** 有界并发、无嵌入。 */
    void boundedConcurrentBatchSaveNoEmbedding(List<List<IndexInfo>> chunks, int maxConcurrency)
            throws Exception {
        runConcurrently(chunks.size(), maxConcurrency, i -> indexRepository.batchSave(chunks.get(i),
                new LinkedHashMap<>()));
    }

    /** 并发跑 n 个任务（maxConcurrency=0 → 不设上限）；首个失败即取消其余（errgroup 语义）。 */
    private void runConcurrently(int count, int maxConcurrency, ThrowingIntConsumer task)
            throws Exception {
        if (count == 0) {
            return;
        }
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore sem = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
        List<Future<?>> futures = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    if (sem != null) {
                        sem.acquire();
                    }
                    try {
                        task.accept(index);
                    } catch (Exception e) {
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    } finally {
                        if (sem != null) {
                            sem.release();
                        }
                    }
                    return null;
                }));
            }
            Exception firstError = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (firstError == null) {
                        firstError = e.getCause() instanceof Exception cause ? cause
                                : new RuntimeException(e.getCause());
                        futures.forEach(f -> f.cancel(true));
                    }
                }
            }
            if (firstError != null) {
                throw firstError;
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int value) throws Exception;
    }

    // ── 删除 / 复制 / 批量更新 / 估算 / 迁移 ────────────────────────────────

    @Override
    public void deleteByChunkIdList(List<String> indexIdList, int dimension, String knowledgeType)
            throws Exception {
        indexRepository.deleteByChunkIdList(indexIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        indexRepository.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        indexRepository.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
    }

    /**
     * 存储估算：向量路用"维度大小的占位向量"估算。
     *
     * <p>向量映射以 {@code SourceID} 为键（与 {@code ToDBVectorEmbedding} 的查表口径一致，
     * 生成问题也能估到向量）。</p>
     */
    @Override
    public long estimateStorageSize(Embedder embedder, List<IndexInfo> indexInfoList,
                                    List<String> retrieverTypes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (retrieverTypes != null && retrieverTypes.contains(EngineTypes.RETRIEVER_VECTOR)) {
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (IndexInfo indexInfo : indexInfoList) {
                embeddingMap.put(indexInfo.sourceId, new float[embedder.getDimensions()]);
            }
            params.put("embedding", embeddingMap);
        }
        return indexRepository.estimateStorageSize(indexInfoList, params);
    }

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("Copy indices from knowledge base {} to {}, mapping relation count: {}",
                sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size());
        indexRepository.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
    }

    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        indexRepository.batchUpdateChunkEnabledStatus(chunkStatusMap);
    }

    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        indexRepository.batchUpdateChunkTagID(chunkTagMap);
    }

    /** 仓库未挂迁移子口即报错。 */
    @Override
    public void validateKnowledgeIndexMove() {
        if (!(indexRepository instanceof RetrieveEngineRepository.KnowledgeIndexMover)) {
            throw new IllegalStateException(
                    "retriever " + engineType + " does not support moving indices");
        }
    }

    /** 先校验再转发。 */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        validateKnowledgeIndexMove();
        ((RetrieveEngineRepository.KnowledgeIndexMover) indexRepository).moveKnowledgeIndices(
                sourceKb, targetKb, knowledgeId, chunkIds, dimension, knowledgeType);
    }
}

package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * 复合检索引擎——对照 Go {@code internal/application/service/retriever/composite.go}
 * （353 行，{@code CompositeRetrieveEngine}）。
 *
 * <p>按"检索类型 → 承载它的引擎"分派：{@code Retrieve} 逐参数挑第一个支持该检索类型的引擎；
 * 其余方法对<b>全部</b>引擎扇出（迁移类操作先整体预检再逐个执行）。</p>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li><b>顺序确定</b>：Go 的 {@code engineInfos} 由 {@code maps.Values} 产出（顺序随机），
 *       并发收集结果也是完成序；本仓一律按"入参序 / 引擎序"回填——同一输入两次跑出的
 *       {@code RetrieveResult} 列表逐项一致（多引擎共同支持同一检索类型时，Go 选谁是不确定的）。</li>
 *   <li><b>并发用虚拟线程</b>（对应 Go 的 goroutine + {@code WaitGroup}）；错误取<b>下标最小</b>的
 *       那一个（Go 取信道里先到的那个，同样不确定）。全部执行完才返回（不提前取消其余），
 *       与 Go 的 {@code concurrentExecWithError} 一致。</li>
 *   <li>{@code common.Deduplicate}（Go 用 map 收集 → 结果无序）→ 本仓按 SourceID
 *       <b>保首次出现顺序</b>去重。</li>
 * </ul>
 */
public class CompositeRetrieveEngine {

    private static final Logger log = LoggerFactory.getLogger(CompositeRetrieveEngine.class);

    /** 对照 {@code engineInfo}：一条引擎 + 它承载的检索类型。 */
    static final class EngineInfo {
        final RetrieveEngineService engine;
        final List<String> retrieverTypes;

        EngineInfo(RetrieveEngineService engine, List<String> retrieverTypes) {
            this.engine = engine;
            this.retrieverTypes = List.copyOf(retrieverTypes);
        }
    }

    private final List<EngineInfo> engineInfos;

    CompositeRetrieveEngine(List<EngineInfo> engineInfos) {
        this.engineInfos = List.copyOf(engineInfos);
    }

    /**
     * 对照 {@code NewCompositeRetrieveEngine}：按租户有效引擎从注册表取服务并合成。
     *
     * <p>与 Go 一样含两道校验：注册表没有该引擎类型 → 报错；引擎不支持该检索类型 → 报错。
     * 同一引擎类型出现多次时把检索类型<b>并起来</b>（不是产生第二条 engineInfo）。</p>
     */
    public static CompositeRetrieveEngine create(RetrieveEngineRegistry registry,
                                                 List<RetrieverEngineParams> engineParams) {
        Map<String, EngineInfo> engineInfos = new LinkedHashMap<>();
        if (engineParams != null) {
            for (RetrieverEngineParams engineParam : engineParams) {
                RetrieveEngineService service =
                        registry.getRetrieveEngineService(engineParam.retrieverEngineType());
                if (service.support() == null
                        || !service.support().contains(engineParam.retrieverType())) {
                    throw new RetrieveEngineException(
                            RetrieveEngineException.Kind.ENGINE_TYPE_NOT_REGISTERED,
                            "retrieval engine " + service.engineType()
                                    + " does not support retriever type: "
                                    + engineParam.retrieverType());
                }
                EngineInfo existing = engineInfos.get(service.engineType());
                if (existing != null) {
                    List<String> merged = new ArrayList<>(existing.retrieverTypes);
                    merged.add(engineParam.retrieverType());
                    engineInfos.put(service.engineType(),
                            new EngineInfo(service, merged));
                    continue;
                }
                engineInfos.put(service.engineType(), new EngineInfo(service,
                        List.of(engineParam.retrieverType())));
            }
        }
        return new CompositeRetrieveEngine(new ArrayList<>(engineInfos.values()));
    }

    /** 单引擎合成（对照 Go 工厂里直接构造 {@code engineInfos} 的那一段）。 */
    static CompositeRetrieveEngine ofSingle(RetrieveEngineService service) {
        return new CompositeRetrieveEngine(List.of(new EngineInfo(service, service.support())));
    }

    /** 引擎条数（测试断言用；Go 的测试直接读 {@code engineInfos}）。 */
    int engineCount() {
        return engineInfos.size();
    }

    /** 取第 i 条引擎（测试断言用）。 */
    RetrieveEngineService engineAt(int index) {
        return engineInfos.get(index).engine;
    }

    /** 取第 i 条引擎承载的检索类型（测试断言用）。 */
    List<String> retrieverTypesAt(int index) {
        return engineInfos.get(index).retrieverTypes;
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Retrieve}：逐参数挑第一个支持该检索类型的引擎，并发执行。 */
    public List<RetrieveResult> retrieve(List<RetrieveParams> retrieveParams) throws Exception {
        if (retrieveParams == null || retrieveParams.isEmpty()) {
            return new ArrayList<>();
        }
        int count = retrieveParams.size();
        // 并发写入按入参下标落位（Go 是完成序，见类注释的确定性备案）。
        AtomicReferenceArray<List<RetrieveResult>> slots =
                new AtomicReferenceArray<>(count);
        Throwable[] errors = new Throwable[count];
        runConcurrently(count, i -> {
            RetrieveParams param = retrieveParams.get(i);
            boolean found = false;
            for (EngineInfo info : engineInfos) {
                if (info == null) {
                    continue;
                }
                if (info.retrieverTypes.contains(param.retrieverType)) {
                    slots.set(i, info.engine.retrieve(param));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException(
                        "retriever type " + param.retrieverType + " not found");
            }
        }, errors);
        rethrowFirst(errors);
        List<RetrieveResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<RetrieveResult> slot = slots.get(i);
            if (slot != null) {
                results.addAll(slot);
            }
        }
        return results;
    }

    /** 对照 {@code SupportRetriever}：任一引擎承载该检索类型即可。 */
    public boolean supportRetriever(String retrieverType) {
        for (EngineInfo info : engineInfos) {
            if (info == null) {
                continue;
            }
            if (info.retrieverTypes.contains(retrieverType)) {
                return true;
            }
        }
        return false;
    }

    // ── 写入 / 删除 / 复制（扇出到全部引擎） ────────────────────────────────

    /** 对照 {@code Index}。 */
    public void index(Embedder embedder, IndexInfo indexInfo) throws Exception {
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.index(embedder, indexInfo, info.retrieverTypes);
            } catch (Exception e) {
                log.error("Repository {} failed to save: {}", info.engine.engineType(),
                        e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code BatchIndex}（先按 SourceID 去重再扇出）。 */
    public void batchIndex(Embedder embedder, List<IndexInfo> indexInfoList) throws Exception {
        List<IndexInfo> deduped = dedupeBySourceId(indexInfoList);
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.batchIndex(embedder, deduped, info.retrieverTypes);
            } catch (Exception e) {
                log.error("Repository {} failed to batch save: {}", info.engine.engineType(),
                        e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code DeleteByChunkIDList}。 */
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.deleteByChunkIdList(chunkIdList, dimension, knowledgeType);
            } catch (Exception e) {
                log.error("Repository {} failed to delete chunk ID list: {}",
                        info.engine.engineType(), e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code DeleteBySourceIDList}。 */
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension,
                                     String knowledgeType) throws Exception {
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
            } catch (Exception e) {
                log.error("Repository {} failed to delete source ID list: {}",
                        info.engine.engineType(), e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code DeleteByKnowledgeIDList}。 */
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
            } catch (Exception e) {
                log.error("Repository {} failed to delete knowledge ID list: {}",
                        info.engine.engineType(), e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code CopyIndices}。 */
    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        rethrowFirst(execPerEngine(info -> {
            try {
                info.engine.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                        sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
            } catch (Exception e) {
                log.error("Repository {} failed to copy indices: {}", info.engine.engineType(),
                        e.toString());
                throw e;
            }
        }));
    }

    /** 对照 {@code BatchUpdateChunkEnabledStatus}。 */
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        rethrowFirst(execPerEngine(info ->
                info.engine.batchUpdateChunkEnabledStatus(chunkStatusMap)));
    }

    /** 对照 {@code BatchUpdateChunkTagID}。 */
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        rethrowFirst(execPerEngine(info ->
                info.engine.batchUpdateChunkTagID(chunkTagMap)));
    }

    /**
     * 对照 {@code EstimateStorageSize}：并发求和；任一引擎失败只记日志，仍返回<b>已累计的部分和</b>
     * （照 Go 的 {@code sum.Add} + 末尾 {@code logger.Errorf}）。
     */
    public long estimateStorageSize(Embedder embedder, List<IndexInfo> indexInfoList) {
        AtomicLong sum = new AtomicLong();
        Throwable[] errors = new Throwable[engineInfos.size()];
        runConcurrently(engineInfos.size(), i -> {
            EngineInfo info = engineInfos.get(i);
            sum.addAndGet(info.engine.estimateStorageSize(embedder, indexInfoList,
                    info.retrieverTypes));
        }, errors);
        Throwable first = firstError(errors);
        if (first != null) {
            log.error("EstimateStorageSize failed: {}", first.toString());
        }
        return sum.get();
    }

    // ── 迁移（先整体预检，再逐个执行） ──────────────────────────────────────

    /** 对照 {@code ValidateKnowledgeIndexMove}：在第一次改动之前验完所有 store。 */
    public void validateKnowledgeIndexMove() {
        for (EngineInfo info : engineInfos) {
            if (!(info.engine instanceof RetrieveEngineService.KnowledgeIndexMover)) {
                throw new IllegalStateException("retriever " + info.engine.engineType()
                        + " does not support moving indices");
            }
            if (info.engine instanceof RetrieveEngineService.KnowledgeIndexMoveValidator validator) {
                validator.validateKnowledgeIndexMove();
            }
        }
    }

    /** 对照 {@code MoveKnowledgeIndices}：先整体预检，再扇出执行。 */
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        validateKnowledgeIndexMove();
        rethrowFirst(execPerEngine(info ->
                ((RetrieveEngineService.KnowledgeIndexMover) info.engine).moveKnowledgeIndices(
                        sourceKb, targetKb, knowledgeId, chunkIds, dimension, knowledgeType)));
    }

    // ── 并发骨架 ────────────────────────────────────────────────────────────

    /** 逐引擎并发执行的任务体（错误由骨架按下标回填）。 */
    @FunctionalInterface
    private interface EngineTask {
        void run(EngineInfo info) throws Exception;
    }

    /** 逐参数并发执行的任务体。 */
    @FunctionalInterface
    private interface IndexedTask {
        void run(int index) throws Exception;
    }

    /** 对照 {@code concurrentExecWithError}：全部跑完，返回下标最小的错误（Go 为信道首错）。 */
    private Throwable[] execPerEngine(EngineTask task) {
        int count = engineInfos.size();
        Throwable[] errors = new Throwable[count];
        runConcurrently(count, i -> task.run(engineInfos.get(i)), errors);
        return errors;
    }

    /**
     * 跑 {@code count} 个任务（虚拟线程）；结果按<b>下标</b>写入 {@code errors}，
     * 不抛出——由调用方决定"首错"语义。
     */
    private void runConcurrently(int count, IndexedTask task, Throwable[] errors) {
        if (count == 0) {
            return;
        }
        List<Thread> threads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int index = i;
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    task.run(index);
                } catch (Throwable t) {
                    errors[index] = t;
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "interrupted while fanning out to retrieval engines");
            }
        }
    }

    /** 取下标最小的错误并抛出（保持 Go 的"抛第一个遇到的错误"语义，但下标确定）。 */
    private static void rethrowFirst(Throwable[] errors) throws Exception {
        Throwable first = firstError(errors);
        if (first == null) {
            return;
        }
        if (first instanceof Exception e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(first);
    }

    private static Throwable firstError(Throwable[] errors) {
        for (Throwable t : errors) {
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** 对照 {@code common.Deduplicate}（按 SourceID），本仓保首次出现顺序。 */
    private static List<IndexInfo> dedupeBySourceId(List<IndexInfo> indexInfoList) {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<IndexInfo> out = new ArrayList<>(indexInfoList.size());
        for (IndexInfo info : indexInfoList) {
            if (seen.add(info.sourceId)) {
                out.add(info);
            }
        }
        return out;
    }
}

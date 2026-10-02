package com.ragagent.embedding;

import java.util.ArrayList;
import com.ragagent.config.AppEnvLookup;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发池化的批量向量化器（对照 Go {@code internal/models/embedding/batch.go} 全文）。
 *
 * <p>把 texts 按 {@code BATCH_EMBED_SIZE}（缺省 5）切成子批并发调用
 * {@code model.batchEmbed}；首个错误后未开始的子批直接跳过（对照 Go 的 firstErr
 * 短路）；子批返回数量与输入不等时报 {@code embedding model returned %d embeddings
 * for %d inputs}。Go 用 ants 协程池限并发；Java 用虚拟线程 + 容量信号量承载同一
 * "池容量"语义（提交全部子批、并发度受 poolSize 钳制）。</p>
 */
public final class BatchEmbedder implements EmbedderPooler {

    private final int poolSize;

    /** 对照 Go NewBatchEmbedder(pool)：pool 容量即并发上限。 */
    public BatchEmbedder(int poolSize) {
        this.poolSize = Math.max(1, poolSize);
    }

    @Override
    public List<float[]> batchEmbedWithPool(Embedder model, List<String> texts) {
        int batchSize = parseBatchSize();
        List<List<String>> subBatches = chunkSlice(texts, batchSize);

        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(null);
        }

        // Go 的 firstErr：首个错误即定案，后续子批看到 firstErr != nil 直接返回
        AtomicReference<RuntimeException> firstErr = new AtomicReference<>();
        Semaphore gate = new Semaphore(poolSize);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>(subBatches.size());
        try {
            for (int b = 0; b < subBatches.size(); b++) {
                final int base = b * batchSize;
                final List<String> subBatch = subBatches.get(b);
                futures.add(executor.submit(() -> {
                    if (firstErr.get() != null) {
                        return;
                    }
                    gate.acquireUninterruptibly();
                    try {
                        if (firstErr.get() != null) {
                            return;
                        }
                        List<float[]> embeddings = model.batchEmbed(subBatch);
                        if (embeddings.size() != subBatch.size()) {
                            firstErr.compareAndSet(null, new EmbeddingHttp.EmbeddingException(
                                    "embedding model returned " + embeddings.size()
                                    + " embeddings for " + subBatch.size() + " inputs"));
                            return;
                        }
                        for (int i = 0; i < subBatch.size(); i++) {
                            results.set(base + i, embeddings.get(i));
                        }
                    } catch (RuntimeException e) {
                        firstErr.compareAndSet(null, e);
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingHttp.EmbeddingException("batch embed interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new EmbeddingHttp.EmbeddingException("batch embed failed: " + e.getMessage(), e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new EmbeddingHttp.EmbeddingException("batch embed timed out", e);
        } finally {
            executor.shutdown();
        }

        if (firstErr.get() != null) {
            throw firstErr.get();
        }
        return results;
    }

    private static int parseBatchSize() {
        String raw = AppEnvLookup.get("BATCH_EMBED_SIZE");
        if (raw == null || raw.isEmpty()) {
            raw = "5";
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new EmbeddingHttp.EmbeddingException(
                    "BATCH_EMBED_SIZE must be an integer, got: " + raw);
        }
    }

    /** 对照 utils.ChunkSlice：按 chunkSize 切片，末片可短。 */
    static <T> List<List<T>> chunkSlice(List<T> list, int chunkSize) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            out.add(new ArrayList<>(list.subList(i, Math.min(i + chunkSize, list.size()))));
        }
        return out;
    }
}
